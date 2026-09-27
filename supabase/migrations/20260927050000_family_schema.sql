-- Family accounts: profiles, families, shared categories, synced expenses.
-- Clients read/write their own rows under RLS; anything touching other
-- people's rows (join, leave, approve, summary) goes through security-definer
-- RPCs below, so private amounts and raw budgets never leave the server.

create schema if not exists private;

-- ---------------------------------------------------------------- tables

create table public.profiles (
  id uuid primary key references auth.users on delete cascade,
  email text not null,
  display_name text not null,
  budget bigint not null default 0 check (budget >= 0), -- UZS, 0 = not set
  default_private boolean not null default false,
  updated_at timestamptz not null default now()
);

create table public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null check (length(name) between 1 and 60),
  created_at timestamptz not null default now(),
  deleted_at timestamptz -- soft delete: history rows keep pointing here
);

create table public.family_members (
  user_id uuid primary key references public.profiles on delete cascade, -- pk = one family per user
  family_id uuid not null references public.families,
  role text not null check (role in ('admin', 'member')),
  joined_at timestamptz not null default now()
);
create unique index family_members_one_admin on public.family_members (family_id) where role = 'admin';
create index family_members_family_idx on public.family_members (family_id);

create table public.invites (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families,
  email text not null check (email = lower(email)),
  invited_by uuid not null references public.profiles on delete cascade,
  created_at timestamptz not null default now(),
  unique (family_id, email)
);
create index invites_email_idx on public.invites (email);
create index invites_invited_by_idx on public.invites (invited_by);

-- Personal (owner_id) before joining a family, family-wide (family_id) after.
create table public.categories (
  id uuid primary key, -- client-generated
  owner_id uuid references public.profiles on delete cascade,
  family_id uuid references public.families,
  name text not null check (length(name) between 1 and 60),
  icon_key text not null default 'category',
  color_hex text not null check (color_hex ~ '^[0-9A-Fa-f]{6}$'),
  is_archived boolean not null default false,
  updated_at timestamptz not null default now(), -- client clock, last-write-wins
  deleted_at timestamptz,
  synced_at timestamptz not null default now(), -- server clock, pull cursor
  check ((owner_id is null) <> (family_id is null))
);
create unique index categories_name_unique
  on public.categories (coalesce(family_id, owner_id), lower(name)) where deleted_at is null;
create index categories_owner_sync_idx on public.categories (owner_id, synced_at);
create index categories_family_sync_idx on public.categories (family_id, synced_at);

create table public.expenses (
  id uuid primary key, -- client-generated
  owner_id uuid not null default auth.uid() references public.profiles on delete cascade,
  family_id uuid references public.families, -- null = personal only
  category_id uuid not null references public.categories,
  description text not null,
  amount bigint not null check (amount >= 0), -- UZS whole units
  date timestamptz not null,
  source text not null check (source in ('typed', 'voice', 'manual')),
  raw_input text,
  is_private boolean not null default false,
  pending_category text, -- requested category name; expense sits in Boshqa until approved
  frozen boolean not null default false, -- owner left the family: read-only history
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  synced_at timestamptz not null default now()
);
create index expenses_owner_sync_idx on public.expenses (owner_id, synced_at);
create index expenses_family_sync_idx on public.expenses (family_id, synced_at);
create index expenses_category_idx on public.expenses (category_id);

create table public.category_requests (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families,
  requested_by uuid not null references public.profiles on delete cascade,
  name text not null check (length(name) between 1 and 60),
  status text not null default 'pending' check (status in ('pending', 'approved', 'rejected')),
  created_at timestamptz not null default now()
);
create unique index category_requests_pending_unique
  on public.category_requests (family_id, lower(name)) where status = 'pending';
create index category_requests_requested_by_idx on public.category_requests (requested_by);

-- ---------------------------------------------------------------- helpers

create function private.my_family_id() returns uuid
language sql stable security definer set search_path = '' as $$
  select family_id from public.family_members where user_id = (select auth.uid())
$$;

create function private.is_admin() returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.family_members
    where user_id = (select auth.uid()) and role = 'admin'
  )
$$;

grant usage on schema private to authenticated;
grant execute on function private.my_family_id(), private.is_admin() to authenticated;

-- Every family has a Boshqa (Other) category: the fallback for unapproved names.
create function private.ensure_boshqa(fid uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare cid uuid;
begin
  select id into cid from public.categories
  where family_id = fid and lower(name) = 'boshqa' and deleted_at is null;
  if cid is null then
    insert into public.categories (id, family_id, name, color_hex)
    values (gen_random_uuid(), fid, 'Boshqa', '9E9E9E')
    returning id into cid;
  end if;
  return cid;
end $$;

create function private.require_admin() returns uuid
language plpgsql security definer set search_path = '' as $$
declare fid uuid;
begin
  select family_id into fid from public.family_members
  where user_id = (select auth.uid()) and role = 'admin';
  if fid is null then raise exception 'not_admin'; end if;
  return fid;
end $$;

-- ---------------------------------------------------------------- triggers

create function private.handle_new_user() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, email, display_name)
  values (
    new.id,
    lower(new.email),
    coalesce(nullif(new.raw_user_meta_data ->> 'name', ''), split_part(new.email, '@', 1))
  );
  return new;
end $$;

create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function private.handle_new_user();

-- Sync stamp: drop stale writes (client updated_at older than stored), then
-- stamp server time for the pull cursor.
-- ponytail: last-write-wins by client clock; a badly skewed device clock can
-- lose edits. Move to server-assigned versions if that ever bites.
create function private.sync_stamp() returns trigger
language plpgsql set search_path = '' as $$
begin
  if tg_op = 'UPDATE' then
    if current_user <> 'authenticated' then
      -- RPC edits always apply, and stay newer than the client's stamp
      new.updated_at := greatest(new.updated_at, old.updated_at);
    elsif new.updated_at < old.updated_at then
      return null;
    end if;
  end if;
  new.synced_at := clock_timestamp();
  return new;
end $$;

-- Clients can't reassign ownership, family, or frozen state; RPCs (which run
-- as the function owner, not 'authenticated') can.
create function private.expense_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' then
    if tg_op = 'INSERT' then
      new.owner_id := auth.uid();
      new.frozen := false;
      -- queued offline after being removed from the family: stays personal
      if new.family_id is distinct from private.my_family_id() then
        new.family_id := null;
      end if;
    else
      new.owner_id := old.owner_id;
      new.family_id := old.family_id;
      new.frozen := old.frozen;
      new.created_at := old.created_at;
    end if;
  end if;
  return new;
end $$;

create function private.category_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' and tg_op = 'UPDATE' then
    new.owner_id := old.owner_id;
    new.family_id := old.family_id;
  end if;
  return new;
end $$;

create trigger expenses_10_guard before insert or update on public.expenses
  for each row execute function private.expense_guard();
create trigger expenses_20_stamp before insert or update on public.expenses
  for each row execute function private.sync_stamp();
create trigger categories_10_guard before update on public.categories
  for each row execute function private.category_guard();
create trigger categories_20_stamp before insert or update on public.categories
  for each row execute function private.sync_stamp();

-- ---------------------------------------------------------------- RLS

alter table public.profiles enable row level security;
alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.invites enable row level security;
alter table public.categories enable row level security;
alter table public.expenses enable row level security;
alter table public.category_requests enable row level security;

revoke all on all tables in schema public from anon;

-- profiles: own row only (budget stays private; family sees contribution via RPC)
create policy profiles_select on public.profiles for select to authenticated
  using (id = (select auth.uid()));
create policy profiles_update on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));
revoke insert, update, delete on public.profiles from authenticated;
grant update (display_name, budget, default_private, updated_at) on public.profiles to authenticated;

-- families: members read; admin renames
create policy families_select on public.families for select to authenticated
  using (id = (select private.my_family_id()));
create policy families_update on public.families for update to authenticated
  using (id = (select private.my_family_id()) and (select private.is_admin()))
  with check (id = (select private.my_family_id()));
revoke insert, update, delete on public.families from authenticated;
grant update (name) on public.families to authenticated;

-- family_members: members read; all writes via RPCs
create policy family_members_select on public.family_members for select to authenticated
  using (family_id = (select private.my_family_id()));
revoke insert, update, delete on public.family_members from authenticated;

-- invites: admin manages; invitee sees and declines their own
create policy invites_select on public.invites for select to authenticated
  using (
    family_id = (select private.my_family_id())
    or email = lower((select auth.jwt()) ->> 'email')
  );
create policy invites_insert on public.invites for insert to authenticated
  with check (
    family_id = (select private.my_family_id())
    and (select private.is_admin())
    and invited_by = (select auth.uid())
  );
create policy invites_delete on public.invites for delete to authenticated
  using (
    (family_id = (select private.my_family_id()) and (select private.is_admin()))
    or email = lower((select auth.jwt()) ->> 'email')
  );
revoke update on public.invites from authenticated;

-- categories: own personal ones, the family's, and any your expenses point at
-- (ex-members keep seeing categories of their frozen history)
create policy categories_select on public.categories for select to authenticated
  using (
    owner_id = (select auth.uid())
    or family_id = (select private.my_family_id())
    or exists (
      select 1 from public.expenses e
      where e.category_id = categories.id and e.owner_id = (select auth.uid())
    )
  );
-- personal categories only outside a family; family categories admin-only
create policy categories_insert on public.categories for insert to authenticated
  with check (
    (owner_id = (select auth.uid()) and family_id is null and (select private.my_family_id()) is null)
    or (owner_id is null and family_id = (select private.my_family_id()) and (select private.is_admin()))
  );
create policy categories_update on public.categories for update to authenticated
  using (
    owner_id = (select auth.uid())
    or (family_id = (select private.my_family_id()) and (select private.is_admin()))
  );
revoke delete on public.categories from authenticated;

-- expenses: own rows only; family reads through family_expenses_since()
create policy expenses_select on public.expenses for select to authenticated
  using (owner_id = (select auth.uid()));
create policy expenses_insert on public.expenses for insert to authenticated
  with check (owner_id = (select auth.uid()));
create policy expenses_update on public.expenses for update to authenticated
  using (owner_id = (select auth.uid()) and not frozen)
  with check (owner_id = (select auth.uid()));
revoke delete on public.expenses from authenticated;

-- category_requests: admin sees all in family, member sees own; writes via RPCs
create policy category_requests_select on public.category_requests for select to authenticated
  using (
    family_id = (select private.my_family_id())
    and ((select private.is_admin()) or requested_by = (select auth.uid()))
  );
revoke insert, update, delete on public.category_requests from authenticated;

-- ---------------------------------------------------------------- RPCs

-- Personal categories become the family's; history optional.
create function public.create_family(p_name text, p_include_history boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  fid uuid;
begin
  if uid is null then raise exception 'not_signed_in'; end if;
  if exists (select 1 from public.family_members where user_id = uid) then
    raise exception 'already_in_family';
  end if;

  insert into public.families (name) values (p_name) returning id into fid;
  insert into public.family_members (user_id, family_id, role) values (uid, fid, 'admin');

  update public.categories
  set family_id = fid, owner_id = null, updated_at = now()
  where owner_id = uid and deleted_at is null;
  perform private.ensure_boshqa(fid);

  if p_include_history then
    update public.expenses set family_id = fid, updated_at = now()
    where owner_id = uid and family_id is null and not frozen;
  end if;
  return fid;
end $$;

-- Same-name categories merge; others go to Boshqa with an auto request.
create function public.accept_invite(p_invite_id uuid, p_include_history boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  my_email text := lower(auth.jwt() ->> 'email');
  fid uuid;
  boshqa uuid;
  c record;
  target uuid;
begin
  if uid is null then raise exception 'not_signed_in'; end if;
  select family_id into fid from public.invites where id = p_invite_id and email = my_email;
  if fid is null then raise exception 'invite_not_found'; end if;
  if exists (select 1 from public.family_members where user_id = uid) then
    raise exception 'already_in_family';
  end if;

  insert into public.family_members (user_id, family_id, role) values (uid, fid, 'member');
  delete from public.invites where email = my_email;
  boshqa := private.ensure_boshqa(fid);

  for c in
    select id, name from public.categories where owner_id = uid and deleted_at is null
  loop
    select id into target from public.categories
    where family_id = fid and lower(name) = lower(c.name) and deleted_at is null;

    if target is not null then
      update public.expenses set category_id = target, updated_at = now()
      where owner_id = uid and category_id = c.id and not frozen;
    elsif exists (
      select 1 from public.expenses
      where owner_id = uid and category_id = c.id and deleted_at is null and not frozen
    ) then
      update public.expenses
      set category_id = boshqa, pending_category = c.name, updated_at = now()
      where owner_id = uid and category_id = c.id and not frozen;
      insert into public.category_requests (family_id, requested_by, name)
      values (fid, uid, c.name)
      on conflict (family_id, lower(name)) where status = 'pending' do nothing;
    end if;

    update public.categories set deleted_at = now(), updated_at = now() where id = c.id;
  end loop;

  if p_include_history then
    update public.expenses set family_id = fid, updated_at = now()
    where owner_id = uid and family_id is null and not frozen;
  end if;
  return fid;
end $$;

-- Member's AI found no category: ask the admin (deduped per name).
create function public.request_category(p_name text)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.my_family_id();
begin
  if fid is null then raise exception 'not_in_family'; end if;
  if exists (
    select 1 from public.categories
    where family_id = fid and lower(name) = lower(trim(p_name)) and deleted_at is null
  ) then
    return;
  end if;
  insert into public.category_requests (family_id, requested_by, name)
  values (fid, auth.uid(), trim(p_name))
  on conflict (family_id, lower(name)) where status = 'pending' do nothing;
end $$;

-- Creates the category (or reuses a same-name one) and moves every waiting
-- expense of current members out of Boshqa into it.
create function public.approve_category_request(p_id uuid, p_color_hex text, p_icon_key text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  fid uuid := private.require_admin();
  req_name text;
  cid uuid;
begin
  select name into req_name from public.category_requests
  where id = p_id and family_id = fid and status = 'pending';
  if req_name is null then raise exception 'request_not_found'; end if;

  select id into cid from public.categories
  where family_id = fid and lower(name) = lower(req_name) and deleted_at is null;
  if cid is null then
    insert into public.categories (id, family_id, name, color_hex, icon_key)
    values (gen_random_uuid(), fid, req_name, p_color_hex, coalesce(p_icon_key, 'category'))
    returning id into cid;
  end if;

  update public.expenses
  set category_id = cid, pending_category = null, updated_at = now()
  where lower(pending_category) = lower(req_name) and not frozen
    and owner_id in (select user_id from public.family_members where family_id = fid);
  update public.category_requests set status = 'approved' where id = p_id;
  return cid;
end $$;

create function public.reject_category_request(p_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  fid uuid := private.require_admin();
  req_name text;
begin
  select name into req_name from public.category_requests
  where id = p_id and family_id = fid and status = 'pending';
  if req_name is null then raise exception 'request_not_found'; end if;

  update public.expenses set pending_category = null, updated_at = now()
  where lower(pending_category) = lower(req_name) and not frozen
    and owner_id in (select user_id from public.family_members where family_id = fid);
  update public.category_requests set status = 'rejected' where id = p_id;
end $$;

-- Shared rows of a departing member stay in family history, read-only.
create function private.freeze_member(p_user uuid, p_family uuid)
returns void language sql security definer set search_path = '' as $$
  update public.expenses set frozen = true, updated_at = now()
  where owner_id = p_user and family_id = p_family and not frozen;
  delete from public.family_members where user_id = p_user;
$$;

create function private.close_family(p_family uuid)
returns void language sql security definer set search_path = '' as $$
  update public.families set deleted_at = now() where id = p_family;
  delete from public.invites where family_id = p_family;
  update public.category_requests set status = 'rejected'
  where family_id = p_family and status = 'pending';
$$;

create function public.leave_family()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  fid uuid;
  my_role text;
  n int;
begin
  select family_id, role into fid, my_role from public.family_members where user_id = uid;
  if fid is null then raise exception 'not_in_family'; end if;
  select count(*) into n from public.family_members where family_id = fid;
  if my_role = 'admin' and n > 1 then raise exception 'transfer_admin_first'; end if;

  perform private.freeze_member(uid, fid);
  if n = 1 then perform private.close_family(fid); end if;
end $$;

create function public.remove_member(p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.require_admin();
begin
  if p_user = auth.uid() then raise exception 'cannot_remove_self'; end if;
  if not exists (select 1 from public.family_members where user_id = p_user and family_id = fid) then
    raise exception 'not_a_member';
  end if;
  perform private.freeze_member(p_user, fid);
end $$;

create function public.transfer_admin(p_user uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.require_admin();
begin
  if not exists (
    select 1 from public.family_members
    where user_id = p_user and family_id = fid and user_id <> auth.uid()
  ) then
    raise exception 'not_a_member';
  end if;
  -- demote first: one-admin unique index is checked per statement
  update public.family_members set role = 'member' where user_id = auth.uid();
  update public.family_members set role = 'admin' where user_id = p_user;
end $$;

create function public.delete_family()
returns void language plpgsql security definer set search_path = '' as $$
declare
  fid uuid := private.require_admin();
  m record;
begin
  for m in select user_id from public.family_members where family_id = fid loop
    perform private.freeze_member(m.user_id, fid);
  end loop;
  perform private.close_family(fid);
end $$;

-- Per current member for [p_from, p_to): shared spending and budget
-- contribution (budget minus own private spending; 0 when no budget set).
-- Raw budgets and private amounts are never returned.
create function public.family_summary(p_from timestamptz, p_to timestamptz)
returns table (user_id uuid, display_name text, role text, shared_total bigint, contribution bigint)
language sql stable security definer set search_path = '' as $$
  select
    m.user_id,
    p.display_name,
    m.role,
    coalesce(sum(e.amount) filter (where not e.is_private), 0)::bigint,
    case when p.budget = 0 then 0
         else p.budget - coalesce(sum(e.amount) filter (where e.is_private), 0)
    end::bigint
  from public.family_members m
  join public.profiles p on p.id = m.user_id
  left join public.expenses e
    on e.owner_id = m.user_id and e.family_id = m.family_id
   and e.deleted_at is null and e.date >= p_from and e.date < p_to
  where m.family_id = private.my_family_id()
  group by m.user_id, p.display_name, m.role, p.budget
$$;

-- Pull other members' family rows changed since the cursor. Private rows come
-- back as tombstones (details nulled) so stale shared copies get dropped.
create function public.family_expenses_since(p_since timestamptz)
returns table (
  id uuid, owner_id uuid, owner_name text, category_id uuid, description text,
  amount bigint, date timestamptz, source text, is_private boolean, frozen boolean,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, synced_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select
    e.id, e.owner_id, p.display_name,
    case when e.is_private then null else e.category_id end,
    case when e.is_private then null else e.description end,
    case when e.is_private then null else e.amount end,
    case when e.is_private then null else e.date end,
    case when e.is_private then null else e.source end,
    e.is_private, e.frozen,
    e.created_at, e.updated_at, e.deleted_at, e.synced_at
  from public.expenses e
  join public.profiles p on p.id = e.owner_id
  where e.family_id = private.my_family_id()
    and e.owner_id <> (select auth.uid())
    and e.synced_at > p_since
  order by e.synced_at
$$;

revoke execute on all functions in schema public from public, anon;
grant execute on all functions in schema public to authenticated;
revoke execute on all functions in schema private from public, anon;
grant execute on function private.my_family_id(), private.is_admin() to authenticated;

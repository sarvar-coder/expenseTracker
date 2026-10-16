-- Per-user categories (#119): every member keeps their own categories. No
-- family-owned categories, no category requests, no Boshqa + pending fallback.
-- Old APKs in a family stop syncing new categories, and expenses filed under
-- such a category stay unsynced on that phone until it updates (accepted).

-- ---------------------------------------------------------------- requests out

drop trigger expenses_15_request on public.expenses;
drop function private.file_category_request();
drop function public.approve_category_request(uuid, text, text);
drop function public.reject_category_request(uuid);
drop table public.category_requests; -- takes its notify trigger along
drop function private.notify_category_request();

-- ---------------------------------------------------------------- data

-- Every (family category, user) pair that needs a personal copy: current
-- members of a live category's family, and owners of expenses pointing at it.
create temp table cat_map (fam_cat uuid, user_id uuid, lname text, personal uuid);

insert into cat_map (fam_cat, user_id, lname)
select c.id, m.user_id, lower(c.name)
from public.categories c
join public.family_members m on m.family_id = c.family_id
where c.family_id is not null and c.deleted_at is null
union
select c.id, e.owner_id, lower(c.name)
from public.categories c
join public.expenses e on e.category_id = c.id
where c.family_id is not null;

-- Reuse the user's own same-name row: a live one, else the latest deleted one
-- (undeleted below), so the unique (owner, lower(name)) index never trips.
update cat_map m set personal = (
  select p.id from public.categories p
  where p.owner_id = m.user_id and lower(p.name) = m.lname
  order by p.deleted_at is not null, p.updated_at desc, p.id
  limit 1
);

update public.categories set deleted_at = null, updated_at = now()
where id in (select personal from cat_map) and deleted_at is not null;

-- No own row yet: one fresh copy per user and name, styled like the family's
-- live row (or the latest deleted one).
insert into public.categories (id, owner_id, name, icon_key, color_hex, is_archived)
select distinct on (m.user_id, m.lname)
  gen_random_uuid(), m.user_id, c.name, c.icon_key, c.color_hex, c.is_archived
from cat_map m
join public.categories c on c.id = m.fam_cat
where m.personal is null
order by m.user_id, m.lname, c.deleted_at is not null, c.updated_at desc;

update cat_map m set personal = p.id
from public.categories p
where m.personal is null
  and p.owner_id = m.user_id and lower(p.name) = m.lname and p.deleted_at is null;

-- Repoint (frozen history included). updated_at stays: sync_stamp bumps
-- synced_at so clients pull the row, and an offline edit queued before this
-- (older client stamp than now()) still lands instead of being dropped as
-- stale; expense_guard then moves its old family category_id to the copy.
update public.expenses e
set category_id = m.personal, pending_category = null
from cat_map m
where e.category_id = m.fam_cat and e.owner_id = m.user_id;

update public.categories set deleted_at = now(), updated_at = now()
where family_id is not null and deleted_at is null;

drop table cat_map;

-- ---------------------------------------------------------------- RLS

drop policy categories_select on public.categories;
drop policy categories_insert on public.categories;
drop policy categories_update on public.categories;

create policy categories_select on public.categories for select to authenticated
  using (
    owner_id = (select auth.uid())
    or exists (
      select 1 from public.expenses e
      where e.category_id = categories.id and e.owner_id = (select auth.uid())
    )
  );
create policy categories_insert on public.categories for insert to authenticated
  with check (owner_id = (select auth.uid()) and family_id is null);
create policy categories_update on public.categories for update to authenticated
  using (owner_id = (select auth.uid()) and family_id is null)
  with check (owner_id = (select auth.uid()) and family_id is null);

-- An expense always points at its owner's category. A write naming an old
-- family category (an offline edit from before this migration, an old APK)
-- is moved to the owner's same-name category, copied over if missing.
-- Another user's personal category is refused (no copying by guessed id).
create function private.own_category(p_cat uuid) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  c record;
  cid uuid;
begin
  select owner_id, name, icon_key, color_hex, is_archived into c
  from public.categories where id = p_cat;
  if not found or c.owner_id = uid then return p_cat; end if; -- missing: the FK reports it
  if c.owner_id is not null then raise exception 'foreign_category'; end if;
  -- another device of this user may insert the same name concurrently
  insert into public.categories (id, owner_id, name, icon_key, color_hex, is_archived)
  values (gen_random_uuid(), uid, c.name, c.icon_key, c.color_hex, c.is_archived)
  on conflict do nothing;
  select id into cid from public.categories
  where owner_id = uid and lower(name) = lower(c.name) and deleted_at is null;
  return cid;
end $$;
revoke execute on function private.own_category(uuid) from public, anon;
grant execute on function private.own_category(uuid) to authenticated;

create or replace function private.expense_guard() returns trigger
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
    new.category_id := private.own_category(new.category_id);
  end if;
  return new;
end $$;

-- ---------------------------------------------------------------- RPCs

-- Same signatures: the app keeps calling them. Categories stay personal.
create or replace function public.create_family(p_name text, p_include_history boolean)
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

  if p_include_history then
    update public.expenses set family_id = fid, updated_at = now()
    where owner_id = uid and family_id is null and not frozen;
  end if;
  return fid;
end $$;

create or replace function public.accept_invite(p_invite_id uuid, p_include_history boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  my_email text := lower(auth.jwt() ->> 'email');
  fid uuid;
begin
  if uid is null then raise exception 'not_signed_in'; end if;
  select family_id into fid from public.invites where id = p_invite_id and email = my_email;
  if fid is null then raise exception 'invite_not_found'; end if;
  if exists (select 1 from public.family_members where user_id = uid) then
    raise exception 'already_in_family';
  end if;

  insert into public.family_members (user_id, family_id, role) values (uid, fid, 'member');
  delete from public.invites where email = my_email;

  if p_include_history then
    update public.expenses set family_id = fid, updated_at = now()
    where owner_id = uid and family_id is null and not frozen;
  end if;
  return fid;
end $$;

-- Shared rows of a departing member stay in family history, read-only.
create or replace function private.freeze_member(p_user uuid, p_family uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  update public.expenses set frozen = true, updated_at = now()
  where owner_id = p_user and family_id = p_family and not frozen;
  delete from public.family_members where user_id = p_user;
end $$;

create or replace function private.close_family(p_family uuid)
returns void language sql security definer set search_path = '' as $$
  update public.families set deleted_at = now() where id = p_family;
  delete from public.invites where family_id = p_family;
$$;

drop function private.ensure_boshqa(uuid);

-- Feed rows carry the owner's category name/color/icon (the Oila tab can't
-- read other members' categories). Trailing columns: old APKs read by name.
drop function public.family_expenses_since(timestamptz);

create function public.family_expenses_since(p_since timestamptz)
returns table (
  id uuid, owner_id uuid, owner_name text, category_id uuid, description text,
  amount bigint, date timestamptz, source text, is_private boolean, frozen boolean,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, synced_at timestamptz,
  raw_input text, category_name text, category_color text, category_icon text
)
language sql stable security definer set search_path = '' as $$
  select
    e.id, e.owner_id, p.display_name,
    case when e.transfer_to is not null then null else e.category_id end,
    case when e.transfer_to is not null then null else e.description end,
    case when e.transfer_to is not null then null else e.amount end,
    case when e.transfer_to is not null then null else e.date end,
    case when e.transfer_to is not null then null else e.source end,
    e.transfer_to is not null, e.frozen,
    e.created_at, e.updated_at, e.deleted_at, e.synced_at,
    case when e.transfer_to is not null then null else e.raw_input end,
    case when e.transfer_to is not null then null else c.name end,
    case when e.transfer_to is not null then null else c.color_hex end,
    case when e.transfer_to is not null then null else c.icon_key end
  from public.expenses e
  join public.profiles p on p.id = e.owner_id
  left join public.categories c on c.id = e.category_id
  where e.family_id = private.my_family_id()
    and e.owner_id <> (select auth.uid())
    and e.synced_at > p_since
  order by e.synced_at
$$;

revoke execute on function public.family_expenses_since(timestamptz) from public, anon;
grant execute on function public.family_expenses_since(timestamptz) to authenticated;

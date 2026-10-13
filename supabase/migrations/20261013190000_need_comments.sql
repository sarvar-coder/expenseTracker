-- Kerakli chat (#111): family members discuss a shared need. The first comment
-- on a 'new' need makes it 'pending'. needs.comment_count is kept here and
-- synced to the app with the need (clients can't write it). Old APKs: they
-- never send comment_count or status pending, and ignore the new push type.

alter table public.needs add column comment_count int not null default 0;

create table public.need_comments (
  id uuid primary key, -- client-generated
  need_id uuid not null references public.needs on delete cascade,
  author_id uuid not null default auth.uid() references public.profiles on delete cascade,
  -- ponytail: name as of posting (ex-members' names stay readable); a rename doesn't rewrite old comments.
  author_name text not null default '',
  body text not null check (length(body) between 1 and 1000 and btrim(body) <> ''),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);
create index need_comments_need_idx on public.need_comments (need_id, created_at);
create index need_comments_author_idx on public.need_comments (author_id);

-- A family need of the caller's current family (personal needs have no chat).
create function private.in_need_family(p_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.needs n
    where n.id = p_id and n.family_id is not null and n.family_id = private.my_family_id()
  )
$$;
revoke all on function private.in_need_family(uuid) from public, anon;
grant execute on function private.in_need_family(uuid) to authenticated;

-- Insert: author and time are the server's. Update: only deleted_at, and only once.
create function private.need_comment_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' then
    if tg_op = 'INSERT' then
      new.author_id := auth.uid();
      new.created_at := now();
      new.deleted_at := null;
    else
      new.id := old.id;
      new.need_id := old.need_id;
      new.author_id := old.author_id;
      new.author_name := old.author_name;
      new.body := old.body;
      new.created_at := old.created_at;
      new.deleted_at := coalesce(old.deleted_at, new.deleted_at);
    end if;
  end if;
  if tg_op = 'INSERT' then
    select coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1), '') into new.author_name
    from public.profiles p where p.id = new.author_id;
    new.author_name := coalesce(new.author_name, '');
  end if;
  return new;
end $$;

create trigger need_comments_10_guard before insert or update on public.need_comments
  for each row execute function private.need_comment_guard();

alter table public.need_comments enable row level security;
revoke all on public.need_comments from anon;
revoke delete, truncate on public.need_comments from authenticated; -- soft delete only

create policy need_comments_select on public.need_comments for select to authenticated
  using ((select private.in_need_family(need_id)));
create policy need_comments_insert on public.need_comments for insert to authenticated
  with check (author_id = (select auth.uid()) and (select private.in_need_family(need_id)));
create policy need_comments_update on public.need_comments for update to authenticated
  using (author_id = (select auth.uid()) and (select private.in_need_family(need_id)))
  with check (author_id = (select auth.uid()));

-- After a comment or its deletion: recount, 'new' -> 'pending' (runs as the
-- owner, so need_guard's client rules don't apply). updated_at is left alone:
-- the stamp bumps synced_at, which is what the app pulls by, and a newer
-- updated_at would make a member's concurrent offline edit of the need lose.
create function private.on_need_comment() returns trigger
language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  update public.needs n set
    comment_count = (select count(*) from public.need_comments c where c.need_id = n.id and c.deleted_at is null),
    status = case when tg_op = 'INSERT' and n.status = 'new' then 'pending' else n.status end
  where n.id = new.need_id
  returning n.family_id, n.text into r;

  if tg_op = 'INSERT' and r.family_id is not null then
    begin
      perform private.push(
        array(select user_id from public.family_members where family_id = r.family_id and user_id <> new.author_id),
        jsonb_build_object('type', 'need_comment', 'need_id', new.need_id, 'name', new.author_name,
                           'item', left(r.text, 60), 'text', left(new.body, 120))
      );
    exception when others then
      raise warning 'need comment push: %', sqlerrm; -- a lost push must never fail the comment
    end;
  end if;
  return null;
end $$;
revoke execute on function private.on_need_comment() from public, anon, authenticated;

create trigger need_comments_20_after after insert or update of deleted_at on public.need_comments
  for each row execute function private.on_need_comment();

-- need_guard (20261013172000) + comment_count is server-only.
create or replace function private.need_guard() returns trigger
language plpgsql set search_path = '' as $$
declare
  existing boolean := tg_op = 'INSERT' and exists (select 1 from public.needs where id = new.id);
  is_owner boolean;
  is_admin boolean;
begin
  -- An upsert of an existing row: its BEFORE INSERT pass feeds `excluded`, so
  -- leave the row alone here; the UPDATE pass below applies the rules.
  if existing then
    -- the INSERT policy still checks this proposed row: owner_id = auth.uid()
    if current_user = 'authenticated' then new.owner_id := auth.uid(); end if;
    return new;
  end if;

  -- done <-> status (old APKs write done only, new ones both)
  if tg_op = 'INSERT' then
    if new.done and new.status = 'new' then new.status := 'done'; end if;
  elsif new.status is distinct from old.status then
    -- status wins; a stale 'new' (comment not pulled yet) doesn't undo pending
    if old.status = 'pending' and new.status = 'new' then new.status := 'pending'; end if;
  elsif new.done is distinct from old.done then
    new.status := case when new.done then 'done' else 'new' end;
  end if;
  new.done := new.status = 'done';

  if current_user = 'authenticated' then
    if tg_op = 'INSERT' then
      new.owner_id := auth.uid();
      new.comment_count := 0;
      if new.family_id is distinct from private.my_family_id() then
        new.family_id := null;
      end if;
      if new.status not in ('new', 'done') then new.status := 'new'; new.done := false; end if;
    else
      new.owner_id := old.owner_id;
      new.family_id := old.family_id;
      new.created_at := old.created_at;
      new.comment_count := old.comment_count;
      new.photo_path := coalesce(new.photo_path, old.photo_path); -- a stale copy can't drop the photo
      is_owner := old.owner_id = auth.uid();
      is_admin := old.family_id is not null and old.family_id = private.my_family_id() and private.is_admin();
      if not (is_owner or is_admin) then
        raise exception 'only the owner or the family admin can change this need' using errcode = '42501';
      end if;
      if not is_owner and (new.text, new.need_money, new.link, new.photo_path)
          is distinct from (old.text, old.need_money, old.link, old.photo_path) then
        raise exception 'only the owner can edit this need' using errcode = '42501';
      end if;
      if new.status is distinct from old.status then
        if (new.status = 'rejected' or old.status = 'rejected') and not is_admin then
          raise exception 'only the family admin can reject or un-reject' using errcode = '42501';
        end if;
        if new.status = 'pending' then
          raise exception 'pending is set by the server' using errcode = '42501';
        end if;
      end if;
    end if;
  end if;
  return new;
end $$;

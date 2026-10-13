-- Kerakli tab (#110): status, money flag, store link, photo.
-- Old APKs only know `done`; the guard keeps done <-> status in sync.
-- Rules: only the owner or the family admin may update a need; only the admin
-- rejects; 'pending' is set by the server (first comment, step 4), never a client;
-- a non-owner admin may change only status / done / deleted_at.

alter table public.needs
  add column status text not null default 'new' check (status in ('new', 'pending', 'done', 'rejected')),
  add column need_money boolean not null default false,
  add column link text check (link is null or (length(link) <= 500 and link ~* '^https?://\S+$')),
  add column photo_path text check (photo_path is null or photo_path = id::text || '/photo.jpg');

update public.needs set status = 'done' where done;

create or replace function private.need_guard() returns trigger
language plpgsql set search_path = '' as $$
declare
  existing boolean := tg_op = 'INSERT' and exists (select 1 from public.needs where id = new.id);
  is_owner boolean;
  is_admin boolean;
begin
  -- An upsert of an existing row: its BEFORE INSERT pass feeds `excluded`, so
  -- leave the row alone here; the UPDATE pass below applies the rules.
  if existing then return new; end if;

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
      if new.family_id is distinct from private.my_family_id() then
        new.family_id := null;
      end if;
      if new.status not in ('new', 'done') then new.status := 'new'; new.done := false; end if;
    else
      new.owner_id := old.owner_id;
      new.family_id := old.family_id;
      new.created_at := old.created_at;
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
        if new.status = 'rejected' and not is_admin then
          raise exception 'only the family admin can reject' using errcode = '42501';
        end if;
        if new.status = 'pending' then
          raise exception 'pending is set by the server' using errcode = '42501';
        end if;
      end if;
    end if;
  end if;
  return new;
end $$;

-- Photos: private bucket, object `{need_id}/photo.jpg`; visible like the need,
-- written by its owner only.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('need-photos', 'need-photos', false, 2097152, array['image/jpeg'])
on conflict (id) do nothing;

create function private.can_see_need(p_id text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.needs n
    where n.id::text = p_id
      and ((n.owner_id = (select auth.uid()) and n.family_id is null) or n.family_id = private.my_family_id())
  )
$$;

create function private.owns_need(p_id text) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (select 1 from public.needs n where n.id::text = p_id and n.owner_id = (select auth.uid()))
$$;

revoke all on function private.can_see_need(text), private.owns_need(text) from public, anon;
grant execute on function private.can_see_need(text), private.owns_need(text) to authenticated;

create policy need_photos_select on storage.objects for select to authenticated
  using (bucket_id = 'need-photos' and private.can_see_need((storage.foldername(name))[1]));
create policy need_photos_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'need-photos' and private.owns_need((storage.foldername(name))[1]));
create policy need_photos_update on storage.objects for update to authenticated
  using (bucket_id = 'need-photos' and private.owns_need((storage.foldername(name))[1]))
  with check (bucket_id = 'need-photos' and private.owns_need((storage.foldername(name))[1]));

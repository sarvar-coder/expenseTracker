-- Review of #110: a non-owner's upsert (admin reject, delete) passes the INSERT
-- policy check; a stale copy can't clear photo_path; photo objects are one per need.
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
      if new.family_id is distinct from private.my_family_id() then
        new.family_id := null;
      end if;
      if new.status not in ('new', 'done') then new.status := 'new'; new.done := false; end if;
    else
      new.owner_id := old.owner_id;
      new.family_id := old.family_id;
      new.created_at := old.created_at;
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

-- One object per need: exactly `{need_id}/photo.jpg`.
drop policy need_photos_insert on storage.objects;
drop policy need_photos_update on storage.objects;
create policy need_photos_insert on storage.objects for insert to authenticated
  with check (bucket_id = 'need-photos' and name = (storage.foldername(name))[1] || '/photo.jpg'
    and private.owns_need((storage.foldername(name))[1]));
create policy need_photos_update on storage.objects for update to authenticated
  using (bucket_id = 'need-photos' and private.owns_need((storage.foldername(name))[1]))
  with check (bucket_id = 'need-photos' and name = (storage.foldername(name))[1] || '/photo.jpg'
    and private.owns_need((storage.foldername(name))[1]));

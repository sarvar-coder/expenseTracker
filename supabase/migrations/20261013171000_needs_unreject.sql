-- #110: a rejected need stays rejected until the admin reopens it (the owner
-- can't un-reject by marking it done or new).
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

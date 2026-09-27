-- Category requests (step 7).

-- A member's expense arrives with pending_category set (it sits in Boshqa).
-- File the admin request here, on sync, so requests made offline still land.
-- If the family already has that category, move the expense straight into it.
create function private.file_category_request() returns trigger
language plpgsql security definer set search_path = '' as $$
declare cid uuid;
begin
  if new.pending_category is null or new.family_id is null or new.frozen
     or (tg_op = 'UPDATE' and new.pending_category is not distinct from old.pending_category) then
    return new;
  end if;
  new.pending_category := left(trim(new.pending_category), 60);
  if new.pending_category = '' then
    new.pending_category := null;
    return new;
  end if;

  select id into cid from public.categories
  where family_id = new.family_id and lower(name) = lower(new.pending_category)
    and deleted_at is null;
  if cid is not null then
    new.category_id := cid;
    new.pending_category := null;
  else
    insert into public.category_requests (family_id, requested_by, name)
    values (new.family_id, new.owner_id, new.pending_category)
    on conflict (family_id, lower(name)) where status = 'pending' do nothing;
  end if;
  return new;
end $$;

revoke execute on function private.file_category_request() from public, anon, authenticated;

-- after expenses_10_guard (owner/family settled), before expenses_20_stamp
create trigger expenses_15_request before insert or update of pending_category on public.expenses
  for each row execute function private.file_category_request();

-- Superseded by the trigger.
drop function public.request_category(text);

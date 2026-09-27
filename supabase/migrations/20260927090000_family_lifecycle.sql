-- Family lifecycle follow-ups (step 6).

-- A departing member keeps the family's categories as personal copies, and
-- their personal (non-family, so unfrozen) expenses move onto the copies.
-- Frozen shared history keeps pointing at the family's categories.
create or replace function private.freeze_member(p_user uuid, p_family uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  c record;
  cid uuid;
begin
  update public.expenses set frozen = true, updated_at = now()
  where owner_id = p_user and family_id = p_family and not frozen;

  for c in
    select id, name, icon_key, color_hex, is_archived from public.categories
    where family_id = p_family and deleted_at is null
  loop
    cid := null;
    insert into public.categories (id, owner_id, name, icon_key, color_hex, is_archived)
    values (gen_random_uuid(), p_user, c.name, c.icon_key, c.color_hex, c.is_archived)
    on conflict do nothing
    returning id into cid;
    if cid is null then
      select id into cid from public.categories
      where owner_id = p_user and lower(name) = lower(c.name) and deleted_at is null;
    end if;
    update public.expenses
    set category_id = cid, pending_category = null, updated_at = now()
    where owner_id = p_user and category_id = c.id and not frozen;
  end loop;

  delete from public.family_members where user_id = p_user;
end $$;

revoke execute on function private.freeze_member(uuid, uuid) from public, anon, authenticated;

-- Invitee's pending invites with names they can't read directly under RLS.
create function public.my_invites()
returns table (id uuid, family_name text, invited_by_name text, created_at timestamptz)
language sql stable security definer set search_path = '' as $$
  select i.id, f.name, p.display_name, i.created_at
  from public.invites i
  join public.families f on f.id = i.family_id and f.deleted_at is null
  join public.profiles p on p.id = i.invited_by
  where i.email = lower((select auth.jwt()) ->> 'email')
  order by i.created_at desc
$$;

revoke execute on function public.my_invites() from public, anon;
grant execute on function public.my_invites() to authenticated;

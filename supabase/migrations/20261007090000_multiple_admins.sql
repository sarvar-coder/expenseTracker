-- A family may have any number of admins. Admins promote/demote each other;
-- the last admin can't step down or leave while others remain.

drop index public.family_members_one_admin;

create or replace function public.leave_family()
returns void language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  fid uuid;
  my_role text;
  n int;
  admins int;
begin
  select family_id, role into fid, my_role from public.family_members where user_id = uid;
  if fid is null then raise exception 'not_in_family'; end if;
  select count(*), count(*) filter (where role = 'admin') into n, admins
  from public.family_members where family_id = fid;
  if my_role = 'admin' and admins = 1 and n > 1 then raise exception 'transfer_admin_first'; end if;

  perform private.freeze_member(uid, fid);
  if n = 1 then perform private.close_family(fid); end if;
end $$;

-- Admin sets anyone's role (self included). Kept: transfer_admin, for old APKs.
create function public.set_role(p_user uuid, p_role text)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.require_admin();
begin
  if p_role not in ('admin', 'member') then raise exception 'bad_role'; end if;
  if not exists (select 1 from public.family_members where user_id = p_user and family_id = fid) then
    raise exception 'not_a_member';
  end if;
  if p_role = 'member' and not exists (
    select 1 from public.family_members where family_id = fid and role = 'admin' and user_id <> p_user
  ) then
    raise exception 'last_admin';
  end if;
  update public.family_members set role = p_role where user_id = p_user;
end $$;

revoke execute on function public.set_role(uuid, text) from public, anon;
grant execute on function public.set_role(uuid, text) to authenticated;

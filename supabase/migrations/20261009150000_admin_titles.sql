-- Only an admin sets family titles, their own included (#72). Same signature,
-- so old APKs that still show the button to members get 'not_admin'.

create or replace function public.set_title(p_user uuid, p_title text)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.my_family_id();
begin
  if fid is null then raise exception 'not_in_family'; end if;
  if not private.is_admin() then raise exception 'not_admin'; end if;
  update public.family_members set title = nullif(trim(p_title), '')
  where user_id = p_user and family_id = fid;
  if not found then raise exception 'not_a_member'; end if;
end $$;

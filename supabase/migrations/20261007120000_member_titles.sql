-- Family role label per member (Ota, Ona, Farzand, ... or free text). Set by
-- the member themself or any admin; family_summary returns it. Additive: old
-- APKs ignore the new column.

alter table public.family_members
  add column title text check (length(title) between 1 and 20);

create function public.set_title(p_user uuid, p_title text)
returns void language plpgsql security definer set search_path = '' as $$
declare fid uuid := private.my_family_id();
begin
  if fid is null then raise exception 'not_in_family'; end if;
  if p_user <> auth.uid() and not private.is_admin() then raise exception 'not_admin'; end if;
  update public.family_members set title = nullif(trim(p_title), '')
  where user_id = p_user and family_id = fid;
  if not found then raise exception 'not_a_member'; end if;
end $$;

-- Return type changes, so drop + create (same name and args for old APKs).
drop function public.family_summary(timestamptz, timestamptz);
create function public.family_summary(p_from timestamptz, p_to timestamptz)
returns table (user_id uuid, display_name text, role text, shared_total bigint, contribution bigint, title text)
language sql stable security definer set search_path = '' as $$
  select
    m.user_id,
    p.display_name,
    m.role,
    coalesce(sum(e.amount) filter (where not e.is_private), 0)::bigint,
    case when p.budget = 0 then 0
         else p.budget - coalesce(sum(e.amount) filter (where e.is_private), 0)
    end::bigint,
    m.title
  from public.family_members m
  join public.profiles p on p.id = m.user_id
  left join public.expenses e
    on e.owner_id = m.user_id and e.family_id = m.family_id
   and e.deleted_at is null and e.date >= p_from and e.date < p_to
  where m.family_id = private.my_family_id()
  group by m.user_id, p.display_name, m.role, p.budget, m.title
$$;

revoke execute on function public.set_title(uuid, text), public.family_summary(timestamptz, timestamptz) from public, anon;
grant execute on function public.set_title(uuid, text), public.family_summary(timestamptz, timestamptz) to authenticated;

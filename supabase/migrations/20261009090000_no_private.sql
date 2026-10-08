-- No private expenses (#82): every family expense is shared. The is_private
-- column and profiles.default_private stay for old APKs; a trigger keeps the
-- flag false so an old APK sending true still writes a shared row.

create function private.no_private() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.is_private := false;
  return new;
end $$;

create trigger expenses_12_no_private before insert or update on public.expenses
  for each row execute function private.no_private();

-- Same signature as 20261007180000: contribution = personal budget (0 = none),
-- shared_total = all non-transfer spending.
create or replace function public.family_summary(p_from timestamptz, p_to timestamptz)
returns table (user_id uuid, display_name text, role text, shared_total bigint, contribution bigint, title text)
language sql stable security definer set search_path = '' as $$
  select
    m.user_id,
    p.display_name,
    m.role,
    coalesce(sum(e.amount), 0)::bigint,
    p.budget::bigint,
    m.title
  from public.family_members m
  join public.profiles p on p.id = m.user_id
  left join public.expenses e
    on e.owner_id = m.user_id and e.family_id = m.family_id
   and e.deleted_at is null and e.date >= p_from and e.date < p_to
   and e.transfer_to is null
  where m.family_id = private.my_family_id()
  group by m.user_id, p.display_name, m.role, p.budget, m.title
$$;

-- Only transfers come back as tombstones. The returned is_private column now
-- means "transfer" so old APKs (which skip is_private rows) still skip them.
create or replace function public.family_expenses_since(p_since timestamptz)
returns table (
  id uuid, owner_id uuid, owner_name text, category_id uuid, description text,
  amount bigint, date timestamptz, source text, is_private boolean, frozen boolean,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, synced_at timestamptz
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
    e.created_at, e.updated_at, e.deleted_at, e.synced_at
  from public.expenses e
  join public.profiles p on p.id = e.owner_id
  where e.family_id = private.my_family_id()
    and e.owner_id <> (select auth.uid())
    and e.synced_at > p_since
  order by e.synced_at
$$;

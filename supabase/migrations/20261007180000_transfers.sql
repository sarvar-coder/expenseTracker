-- O'tkazma: money handed to another member is logged by the giver with
-- transfer_to set. The receiver logs their real purchases, so transfers stay
-- out of family totals and the family list (no double counting). Additive:
-- old APKs never send the column, their rows stay plain expenses.

alter table public.expenses
  add column transfer_to uuid references public.profiles (id) on delete set null;

-- Same signature and return type as 20261007120000: replace in place.
create or replace function public.family_summary(p_from timestamptz, p_to timestamptz)
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
   and e.transfer_to is null
  where m.family_id = private.my_family_id()
  group by m.user_id, p.display_name, m.role, p.budget, m.title
$$;

-- Transfers come back as tombstones like private rows, so a client caching
-- by cursor drops a row that later became a transfer.
create or replace function public.family_expenses_since(p_since timestamptz)
returns table (
  id uuid, owner_id uuid, owner_name text, category_id uuid, description text,
  amount bigint, date timestamptz, source text, is_private boolean, frozen boolean,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, synced_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select
    e.id, e.owner_id, p.display_name,
    case when e.is_private or e.transfer_to is not null then null else e.category_id end,
    case when e.is_private or e.transfer_to is not null then null else e.description end,
    case when e.is_private or e.transfer_to is not null then null else e.amount end,
    case when e.is_private or e.transfer_to is not null then null else e.date end,
    case when e.is_private or e.transfer_to is not null then null else e.source end,
    e.is_private or e.transfer_to is not null, e.frozen,
    e.created_at, e.updated_at, e.deleted_at, e.synced_at
  from public.expenses e
  join public.profiles p on p.id = e.owner_id
  where e.family_id = private.my_family_id()
    and e.owner_id <> (select auth.uid())
    and e.synced_at > p_since
  order by e.synced_at
$$;

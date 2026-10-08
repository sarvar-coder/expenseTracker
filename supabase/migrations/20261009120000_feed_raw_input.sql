-- Read-only expense sheet (#74) shows what the member typed or said.
-- Same RPC plus a trailing raw_input column: old APKs read columns by name,
-- so they ignore it. Adding an OUT column changes the return type: drop first.
drop function public.family_expenses_since(timestamptz);

create function public.family_expenses_since(p_since timestamptz)
returns table (
  id uuid, owner_id uuid, owner_name text, category_id uuid, description text,
  amount bigint, date timestamptz, source text, is_private boolean, frozen boolean,
  created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, synced_at timestamptz,
  raw_input text
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
    e.created_at, e.updated_at, e.deleted_at, e.synced_at,
    case when e.transfer_to is not null then null else e.raw_input end
  from public.expenses e
  join public.profiles p on p.id = e.owner_id
  where e.family_id = private.my_family_id()
    and e.owner_id <> (select auth.uid())
    and e.synced_at > p_since
  order by e.synced_at
$$;

revoke execute on function public.family_expenses_since(timestamptz) from public, anon;
grant execute on function public.family_expenses_since(timestamptz) to authenticated;

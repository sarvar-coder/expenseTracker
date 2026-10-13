-- Hissa page (#108): the family's contributions and transfers (who → who)
-- for [p_from, p_to), newest first. contributions is owner-only under RLS, so
-- the family reads them here. Names are "Ali · Ota" for current members.
-- Outside a family: own contributions and own transfers only. New function:
-- old APKs never call it.
create function public.family_ledger(p_from timestamptz, p_to timestamptz)
returns table (
  kind text, id uuid, from_user uuid, from_name text, to_user uuid, to_name text,
  amount bigint, note text, date timestamptz
)
language sql stable security definer set search_path = '' as $$
  with ctx as (select (select auth.uid()) as me, private.my_family_id() as fid),
  names as (
    select p.id, concat_ws(' · ', p.display_name, m.title) as label
    from public.profiles p
    left join public.family_members m on m.user_id = p.id and m.family_id = (select fid from ctx)
  )
  select 'contribution', c.id, c.owner_id, n.label, null::uuid, null::text, c.amount, c.note, c.date
  from public.contributions c
  join names n on n.id = c.owner_id
  where c.deleted_at is null and c.date >= p_from and c.date < p_to
    and (c.owner_id = (select me from ctx)
      or c.owner_id in (select user_id from public.family_members where family_id = (select fid from ctx)))
  union all
  select 'transfer', e.id, e.owner_id, nf.label, e.transfer_to, nt.label, e.amount, e.description, e.date
  from public.expenses e
  join names nf on nf.id = e.owner_id
  left join names nt on nt.id = e.transfer_to
  where e.transfer_to is not null and e.deleted_at is null and e.date >= p_from and e.date < p_to
    and (e.family_id = (select fid from ctx)
      or ((select fid from ctx) is null and e.owner_id = (select me from ctx)))
  order by date desc
$$;
revoke execute on function public.family_ledger(timestamptz, timestamptz) from public, anon;
grant execute on function public.family_ledger(timestamptz, timestamptz) to authenticated;

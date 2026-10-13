-- Review of #108: other members' contributions only from when they joined
-- this family (not their history from before), and a transfer target is
-- named only when they are the caller, a member, or an ex-member with rows
-- in this family; otherwise null (the app shows "?"). Same signature.
create or replace function public.family_ledger(p_from timestamptz, p_to timestamptz)
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
      or exists (select 1 from public.family_members m
        where m.user_id = c.owner_id and m.family_id = (select fid from ctx) and c.date >= m.joined_at))
  union all
  select 'transfer', e.id, e.owner_id, nf.label, e.transfer_to,
    case when e.transfer_to = (select me from ctx)
      or exists (select 1 from public.family_members m where m.user_id = e.transfer_to and m.family_id = (select fid from ctx))
      or exists (select 1 from public.expenses x where x.owner_id = e.transfer_to and x.family_id = e.family_id)
    then nt.label end,
    e.amount, e.description, e.date
  from public.expenses e
  join names nf on nf.id = e.owner_id
  left join names nt on nt.id = e.transfer_to
  where e.transfer_to is not null and e.deleted_at is null and e.date >= p_from and e.date < p_to
    and (e.family_id = (select fid from ctx)
      or ((select fid from ctx) is null and e.owner_id = (select me from ctx)))
  order by date desc
$$;

-- Hissa (#97): a member's money for the month replaces the personal budget.
-- Repeated top-ups, each with a required note. Own rows only (the family sees
-- totals through family_summary). Synced like needs: client ids, soft delete,
-- newest updated_at wins. profiles.budget stays for old APKs, unused by 2.2.0.

create table public.contributions (
  id uuid primary key, -- client-generated
  owner_id uuid not null default auth.uid() references public.profiles on delete cascade,
  amount bigint not null check (amount > 0),
  note text not null check (length(trim(note)) between 1 and 200),
  date timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  synced_at timestamptz not null default now()
);
create index contributions_owner_sync_idx on public.contributions (owner_id, synced_at);
create index contributions_owner_date_idx on public.contributions (owner_id, date) where deleted_at is null;
-- family_summary's received/given sums.
create index expenses_transfer_to_date_idx on public.expenses (transfer_to, date)
  where transfer_to is not null and deleted_at is null;

-- Clients can't reassign the owner.
create function private.contribution_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' then
    if tg_op = 'INSERT' then
      new.owner_id := auth.uid();
    else
      new.owner_id := old.owner_id;
    end if;
  end if;
  return new;
end $$;
revoke execute on function private.contribution_guard() from public, anon, authenticated;

create trigger contributions_10_guard before insert or update on public.contributions
  for each row execute function private.contribution_guard();
create trigger contributions_20_stamp before insert or update on public.contributions
  for each row execute function private.sync_stamp();

alter table public.contributions enable row level security;
revoke all on public.contributions from anon;
revoke delete on public.contributions from authenticated; -- soft delete only

create policy contributions_select on public.contributions for select to authenticated
  using (owner_id = (select auth.uid()));
create policy contributions_insert on public.contributions for insert to authenticated
  with check (owner_id = (select auth.uid()));
create policy contributions_update on public.contributions for update to authenticated
  using (owner_id = (select auth.uid()));

-- Per current member for [p_from, p_to). Old columns kept for old APKs:
-- contribution = own contributions + transfers received (what the member had),
-- shared_total = non-transfer spending. New: own_contribution (the family
-- budget sums only these, so a transfer isn't counted twice), received, given
-- (transfers out) and available = contribution - all own spending - given.
-- Contributions, given and available's spending are the member's own money,
-- whatever family a row belongs to (personal rows before joining, frozen rows
-- of a previous family): the same rule as the app's Home balance, which sums
-- every own Room row. shared_total stays this family's rows only.
drop function public.family_summary(timestamptz, timestamptz);
create function public.family_summary(p_from timestamptz, p_to timestamptz)
returns table (
  user_id uuid, display_name text, role text, shared_total bigint, contribution bigint, title text,
  own_contribution bigint, received bigint, given bigint, available bigint
)
language sql stable security definer set search_path = '' as $$
  with s as (
    select
      m.user_id as uid, p.display_name as dname, m.role as mrole, m.title as mtitle,
      (select coalesce(sum(e.amount), 0) from public.expenses e
        where e.owner_id = m.user_id and e.family_id = m.family_id and e.deleted_at is null
          and e.date >= p_from and e.date < p_to and e.transfer_to is null) as spent,
      (select coalesce(sum(e.amount), 0) from public.expenses e
        where e.owner_id = m.user_id and e.deleted_at is null
          and e.date >= p_from and e.date < p_to and e.transfer_to is null) as own_spent,
      (select coalesce(sum(c.amount), 0) from public.contributions c
        where c.owner_id = m.user_id and c.deleted_at is null
          and c.date >= p_from and c.date < p_to) as own,
      (select coalesce(sum(e.amount), 0) from public.expenses e
        where e.transfer_to = m.user_id and e.family_id = m.family_id and e.deleted_at is null
          and e.date >= p_from and e.date < p_to) as got,
      (select coalesce(sum(e.amount), 0) from public.expenses e
        where e.owner_id = m.user_id and e.transfer_to is not null and e.deleted_at is null
          and e.date >= p_from and e.date < p_to) as gave
    from public.family_members m
    join public.profiles p on p.id = m.user_id
    where m.family_id = private.my_family_id()
  )
  select uid, dname, mrole, spent::bigint, (own + got)::bigint, mtitle,
    own::bigint, got::bigint, gave::bigint, (own + got - own_spent - gave)::bigint
  from s
$$;
revoke execute on function public.family_summary(timestamptz, timestamptz) from public, anon;
grant execute on function public.family_summary(timestamptz, timestamptz) to authenticated;

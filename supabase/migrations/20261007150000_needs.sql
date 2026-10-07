-- "Kerakli": a to-buy / needs list. family_id null = personal (owner only);
-- set = shared with the whole family, any member may tick or delete it.
-- Synced like expenses: client ids, soft delete, newest updated_at wins.

create table public.needs (
  id uuid primary key, -- client-generated
  owner_id uuid not null default auth.uid() references public.profiles on delete cascade,
  family_id uuid references public.families,
  text text not null check (length(text) between 1 and 200),
  done boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  synced_at timestamptz not null default now()
);
create index needs_owner_sync_idx on public.needs (owner_id, synced_at);
create index needs_family_sync_idx on public.needs (family_id, synced_at);

-- Clients can't reassign owner or family; a family item queued offline after
-- leaving that family stays personal.
create function private.need_guard() returns trigger
language plpgsql set search_path = '' as $$
begin
  if current_user = 'authenticated' then
    if tg_op = 'INSERT' then
      new.owner_id := auth.uid();
      if new.family_id is distinct from private.my_family_id() then
        new.family_id := null;
      end if;
    else
      new.owner_id := old.owner_id;
      new.family_id := old.family_id;
      new.created_at := old.created_at;
    end if;
  end if;
  return new;
end $$;

create trigger needs_10_guard before insert or update on public.needs
  for each row execute function private.need_guard();
create trigger needs_20_stamp before insert or update on public.needs
  for each row execute function private.sync_stamp();

alter table public.needs enable row level security;
revoke all on public.needs from anon;

create policy needs_select on public.needs for select to authenticated
  using (
    (owner_id = (select auth.uid()) and family_id is null)
    or family_id = (select private.my_family_id())
  );
create policy needs_insert on public.needs for insert to authenticated
  with check (owner_id = (select auth.uid()));
create policy needs_update on public.needs for update to authenticated
  using (
    (owner_id = (select auth.uid()) and family_id is null)
    or family_id = (select private.my_family_id())
  );
revoke delete on public.needs from authenticated;

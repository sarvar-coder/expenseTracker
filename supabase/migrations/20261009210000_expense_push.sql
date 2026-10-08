-- New shared expense (#79): every other family member gets a push. Reuses
-- private.push (20261009180000). INSERT only: edits, deletes and the join
-- merge (accept_invite moves rows with UPDATE) never notify. Transfers don't.
-- Bulk guard, per statement: the sync pushes dirty rows as one upsert of up
-- to 500, so an owner inserting more than 3 rows at once (first-sign-in
-- upload, a long offline queue) sends nothing, and rows created more than a
-- day ago (old local history) never do. Upserts that hit an existing row are
-- updates and don't appear in the INSERT transition table.
-- Additive: old APKs ignore the unknown `expense` type.

create function private.notify_new_expenses()
returns trigger language plpgsql security definer set search_path = '' as $$
declare r record;
begin
  for r in
    select n.id, n.owner_id, n.family_id, n.description, n.amount,
           coalesce(nullif(p.display_name, ''), split_part(p.email, '@', 1), '') as who
    from new_rows n
    left join public.profiles p on p.id = n.owner_id
    where n.family_id is not null and n.transfer_to is null and n.deleted_at is null
      and n.created_at > now() - interval '1 day'
      -- ponytail: fixed threshold; a 4-row offline queue is treated as bulk too.
      and (select count(*) from new_rows b where b.owner_id = n.owner_id) <= 3
  loop
    perform private.push(
      array(select user_id from public.family_members where family_id = r.family_id and user_id <> r.owner_id),
      jsonb_build_object('type', 'expense', 'id', r.id, 'owner', r.owner_id, 'name', r.who,
                         'description', r.description, 'amount', r.amount)
    );
  end loop;
  return null;
end $$;
revoke execute on function private.notify_new_expenses() from public, anon, authenticated;

create trigger expenses_notify after insert on public.expenses
  referencing new table as new_rows
  for each statement execute function private.notify_new_expenses();

-- Access-rule check for the family schema. Run in the SQL editor (or via the
-- Supabase MCP execute_sql). It always ends in an exception so everything
-- rolls back: 'ALL_TESTS_PASSED' = green, any other message = the failed assert.
do $$
declare
  a uuid := gen_random_uuid(); b uuid := gen_random_uuid();
  ca uuid := gen_random_uuid(); cb1 uuid := gen_random_uuid(); cb2 uuid := gen_random_uuid();
  e1 uuid := gen_random_uuid(); eb1 uuid := gen_random_uuid(); eb2 uuid := gen_random_uuid(); eb4 uuid := gen_random_uuid();
  eb5 uuid := gen_random_uuid(); eb6 uuid := gen_random_uuid(); eb7 uuid := gen_random_uuid(); ff uuid := gen_random_uuid(); fk uuid := gen_random_uuid();
  fid uuid; inv uuid; newcat uuid; n int; r record; ok boolean;
begin
  insert into auth.users (id, email, aud, role, raw_user_meta_data) values
    (a, 'A@test.uz', 'authenticated', 'authenticated', '{"name":"Ali"}'),
    (b, 'b@test.uz', 'authenticated', 'authenticated', '{}');

  -- A: personal data, then creates the family with history
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  insert into public.categories (id, owner_id, name, color_hex) values (ca, a, 'Food', 'E08A5B');
  insert into public.expenses (id, category_id, description, amount, date, source) values (e1, ca, 'lunch', 100, now(), 'manual');
  fid := public.create_family('Fam', true);
  perform public.register_push_token('tok-a');
  select count(*) into n from public.categories where family_id = fid; assert n = 0, 'family owns no categories';
  select count(*) into n from public.categories where id = ca and owner_id = a and deleted_at is null; assert n = 1, 'Food stays A''s own';
  insert into public.categories (id, owner_id, name, color_hex) values (gen_random_uuid(), a, 'Kino', 'C07FA6'); -- admin in a family: own category
  -- legacy family categories (pre-2.3.0 rows an old APK or offline edit may still name)
  execute 'reset role';
  insert into public.categories (id, family_id, name, color_hex) values (ff, fid, 'FOOD', '6FA86A'), (fk, fid, 'Kino', 'C07FA6');
  execute 'set local role authenticated';
  insert into public.invites (family_id, email, invited_by) values (fid, 'b@test.uz', a) returning id into inv;

  -- B: personal data, joins with history
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  insert into public.categories (id, owner_id, name, color_hex) values (cb1, b, 'food', 'E08A5B'), (cb2, b, 'Gym', '5B8DB8');
  -- push tokens: own rows only; a phone's token moves to whoever signs in on it
  perform public.register_push_token('tok-b');
  perform public.register_push_token('tok-b');
  select count(*) into n from public.device_tokens; assert n = 1, 'B sees only own token (upsert, no dupe), got ' || n;
  begin
    insert into public.device_tokens (token, user_id) values ('tok-x', a); ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'B cannot add a token for A';
  delete from public.device_tokens where token = 'tok-a';
  get diagnostics n = row_count; assert n = 0, 'B cannot delete A token';
  begin perform public.claim_push(gen_random_uuid()); ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'clients cannot claim pushes';
  insert into public.expenses (id, category_id, description, amount, date, source) values
    (eb1, cb1, 'bread', 50, now(), 'manual'), (eb2, cb2, 'gym', 70, now(), 'manual');
  select count(*) into n from public.invites; assert n = 1, 'B sees own invite';
  select * into r from public.my_invites(); assert r.family_name = 'Fam' and r.invited_by_name = 'Ali', 'invite shows family + inviter';
  perform public.accept_invite(inv, true);
  select category_id into newcat from public.expenses where id = eb1; assert newcat = cb1, 'joining keeps own food';
  select count(*) into n from public.expenses where id = eb2 and category_id = cb2 and pending_category is null; assert n = 1, 'joining keeps own Gym';
  select count(*) into n from public.categories where owner_id = b and deleted_at is null; assert n = 2, 'B keeps own categories, got ' || n;
  select count(*) into n from public.categories where id in (ca, ff, fk); assert n = 0, 'B cannot read A or family categories';
  update public.categories set name = 'Hacked', updated_at = now() + interval '1 second' where id = ca;
  get diagnostics n = row_count; assert n = 0, 'B cannot edit A category';
  -- another user's personal category is refused
  begin
    insert into public.expenses (id, category_id, description, amount, date, source) values (gen_random_uuid(), ca, 'x', 1, now(), 'manual');
    ok := false;
  exception when others then ok := sqlerrm = 'foreign_category'; end;
  assert ok, 'expense cannot point at A''s category';
  -- an expense naming an old family category moves to the owner's same-name one
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, is_private, raw_input)
    values (eb4, fid, ff, 'secret', 30, now(), 'typed', true, 'secret 30');
  select count(*) into n from public.expenses where id = eb4 and category_id = cb1; assert n = 1, 'family FOOD -> own food';
  select count(*) into n from public.expenses where id = eb4 and not is_private; assert n = 1, 'old APK is_private forced false';
  update public.expenses set is_private = true, updated_at = now() + interval '1 second' where id = eb4;
  select count(*) into n from public.expenses where id = eb4 and not is_private; assert n = 1, 'is_private stays false on update';
  -- transfer to A: out of family totals and the family list
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, transfer_to, raw_input)
    values (eb7, fid, ff, '-> Ali', 500, now(), 'typed', a, 'Aliga 500');
  -- new-expense push (#79): eb4 only; owner excluded; transfer, edit, delete, bulk and old rows don't enqueue
  update public.expenses set description = 'secret2', updated_at = now() + interval '2 second' where id = eb4;
  update public.expenses set deleted_at = now(), updated_at = now() + interval '3 second' where id = eb2;
  update public.expenses set deleted_at = null, updated_at = now() + interval '4 second' where id = eb2;
  -- A (1 row so far): an old row stays silent, then 4 at once is bulk (A's last-minute writes > 3)
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, created_at)
    values (gen_random_uuid(), fid, ca, 'old', 1, now() - interval '2 day', 'manual', now() - interval '2 day');
  insert into public.expenses (id, family_id, category_id, description, amount, date, source)
    select gen_random_uuid(), fid, ca, 'bulk', 1, now(), 'manual' from generate_series(1, 4);
  update public.expenses set deleted_at = now(), updated_at = now() + interval '5 second' where description in ('bulk', 'old');
  execute 'reset role';
  select count(*) into n from private.push_outbox where data->>'type' = 'expense' and user_ids && array[a, b];
  assert n = 1, 'one expense push (eb4), got ' || n;
  select count(*) into n from private.push_outbox
  where data->>'type' = 'expense' and user_ids = array[a] and data->>'id' = eb4::text and data->>'owner' = b::text
    and data->>'name' = 'b' and data->>'description' = 'secret' and data->>'amount' = '30';
  assert n = 1, 'A (not owner B) gets the eb4 push';
  execute 'set local role authenticated';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  update public.profiles set budget = 1000 where id = b;
  -- contributions (#97): own rows, note required, owner can't be spoofed; profiles.budget no longer counts
  insert into public.contributions (id, amount, note) values (gen_random_uuid(), 800, 'Oylik');
  begin
    insert into public.contributions (id, amount, note) values (gen_random_uuid(), 5, '  '); ok := false;
  exception when check_violation then ok := true; end;
  assert ok, 'contribution note required';
  begin
    insert into public.contributions (id, amount, note) values (gen_random_uuid(), 0, 'x'); ok := false;
  exception when check_violation then ok := true; end;
  assert ok, 'contribution amount > 0';
  insert into public.contributions (id, owner_id, amount, note) values (gen_random_uuid(), a, 7, 'spoof');
  select count(*) into n from public.contributions where owner_id = b; assert n = 2, 'spoofed owner forced to B, got ' || n;
  update public.contributions set deleted_at = now(), updated_at = now() + interval '1 second' where note = 'spoof';
  begin
    insert into public.categories (id, family_id, name, color_hex) values (gen_random_uuid(), fid, 'X', 'AAAAAA');
    ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'nobody creates family categories';
  insert into public.categories (id, owner_id, name, color_hex) values (gen_random_uuid(), b, 'Taxi', '5B8DB8'); -- member: own category
  -- ...or to a copy when the owner has none (old APK, offline edit)
  insert into public.expenses (id, family_id, category_id, description, amount, date, source)
    values (eb5, fid, fk, 'film', 10, now(), 'typed');
  select count(*) into n from public.expenses e join public.categories c on c.id = e.category_id
  where e.id = eb5 and c.owner_id = b and c.name = 'Kino' and c.color_hex = 'C07FA6'; assert n = 1, 'family Kino -> own copy';
  update public.expenses set category_id = fk, updated_at = now() + interval '10 second' where id = eb2;
  select count(*) into n from public.expenses e join public.categories c on c.id = e.category_id
  where e.id = eb2 and c.owner_id = b and c.name = 'Kino'; assert n = 1, 'update to family category -> own copy';
  update public.expenses set category_id = cb2, updated_at = now() + interval '11 second' where id = eb2;
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, pending_category)
    values (eb6, fid, cb1, 'soup', 10, now(), 'typed', 'FOOD');
  select count(*) into n from public.expenses where id = eb6 and category_id = cb1; assert n = 1, 'pending_category ignored';
  update public.expenses set family_id = null, updated_at = now() + interval '1 second' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and family_id = fid; assert n = 1, 'family_id immutable';
  update public.expenses set description = 'stale', updated_at = now() - interval '1 day' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and description = 'bread'; assert n = 1, 'stale write dropped';

  -- A: privacy, summary math, approval, admin rules
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where owner_id = b; assert n = 0, 'A cannot read B rows directly';
  select count(*) into n from public.profiles where id = b; assert n = 0, 'A cannot read B profile/budget';
  select amount, description, is_private, source, raw_input into r from public.family_expenses_since('epoch') where id = eb4;
  assert r.amount = 30 and r.description = 'secret2' and not r.is_private, 'no private: row shared with the family';
  assert r.source = 'typed' and r.raw_input = 'secret 30', 'feed carries source + raw input (read-only sheet)';
  select category_name, category_color, category_icon into r from public.family_expenses_since('epoch') where id = eb4;
  assert r.category_name = 'food' and r.category_color = 'E08A5B' and r.category_icon = 'category', 'feed carries the owner''s category';
  select amount, is_private, raw_input, category_name into r from public.family_expenses_since('epoch') where id = eb7;
  assert r.amount is null and r.is_private and r.raw_input is null and r.category_name is null, 'transfer row is a tombstone';
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = b;
  assert r.shared_total = 170, 'B shared total 170, got ' || r.shared_total;
  assert r.own_contribution = 800 and r.contribution = 800, 'B contribution = own 800 (not budget), got ' || r.contribution;
  assert r.given = 500 and r.received = 0 and r.available = 130, 'B gave 500: available 800-170-500, got ' || r.available;
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = a;
  assert r.received = 500 and r.own_contribution = 0 and r.contribution = 500, 'A received 500 from B, got ' || r.contribution;
  select sum(own_contribution) into n from public.family_summary(now() - interval '1 day', now() + interval '1 day');
  assert n = 800, 'family budget counts the transfer once, got ' || n;
  select count(*) into n from public.contributions; assert n = 0, 'A cannot read B contributions';
  update public.contributions set amount = 1 where owner_id = b;
  get diagnostics n = row_count; assert n = 0, 'A cannot edit B contributions';
  -- Hissa page (#108): the family sees each member's contributions and transfers via family_ledger
  select count(*) into n from public.family_ledger(now() - interval '1 day', now() + interval '1 day') where kind = 'contribution' and from_user = b and amount = 800;
  assert n = 1, 'A sees B contribution in ledger (deleted spoof hidden), got ' || n;
  select * into r from public.family_ledger(now() - interval '1 day', now() + interval '1 day') where kind = 'transfer';
  assert r.id = eb7 and r.from_user = b and r.to_user = a and r.to_name = 'Ali' and r.amount = 500, 'ledger shows B -> Ali 500';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  insert into public.contributions (id, amount, note, date) values (gen_random_uuid(), 9, 'before joining', now() - interval '2 hour');
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.family_ledger(now() - interval '1 day', now() + interval '1 day') where kind = 'contribution';
  assert n = 1, 'ledger hides contributions from before joining, got ' || n;
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  perform public.register_push_token('tok-a'); -- B signs in on A's phone
  select count(*) into n from public.device_tokens where token = 'tok-a'; assert n = 1, 'token taken over by B';
  delete from public.device_tokens where token = 'tok-a'; -- B signs out there
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  begin perform public.leave_family(); ok := false;
  exception when others then ok := sqlerrm = 'transfer_admin_first'; end;
  assert ok, 'admin must transfer first';
  perform public.transfer_admin(b);
  select count(*) into n from public.family_members where role = 'admin' and user_id = b; assert n = 1, 'admin transferred';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  perform public.transfer_admin(a);
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  -- several admins: the last one can't step down or leave
  begin perform public.set_role(a, 'member'); ok := false;
  exception when others then ok := sqlerrm = 'last_admin'; end;
  assert ok, 'last admin cannot step down';
  perform public.set_role(b, 'admin');
  select count(*) into n from public.family_members where role = 'admin'; assert n = 2, 'two admins';
  perform public.set_role(b, 'member');
  -- titles: admin only (own included); family_summary returns them
  perform public.set_title(a, 'Ota');
  perform public.set_title(b, ' Uka ');
  perform public.set_title(b, 'Singil');
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  begin perform public.set_title(b, 'Aka'); ok := false;
  exception when others then ok := sqlerrm = 'not_admin'; end;
  assert ok, 'member cannot set own title';
  begin perform public.set_title(a, 'Ona'); ok := false;
  exception when others then ok := sqlerrm = 'not_admin'; end;
  assert ok, 'member cannot set others title';
  update public.profiles set display_name = 'Bobur' where id = b;
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = b;
  assert r.title = 'Singil' and r.display_name = 'Bobur', 'summary has title + name, got ' || r.title || ' ' || r.display_name;
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  perform public.set_title(b, '');
  select title into r from public.family_members where user_id = b;
  assert r.title is null, 'blank clears title';
  select title into r from public.family_members where user_id = a;
  assert r.title = 'Ota', 'admin sets own title';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  begin perform public.set_role(b, 'admin'); ok := false;
  exception when others then ok := sqlerrm = 'not_admin'; end;
  assert ok, 'member cannot set roles';
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  perform public.remove_member(b);
  select count(*) into n from public.family_expenses_since('epoch') where id = eb1 and frozen; assert n = 1, 'history kept frozen';

  -- B after removal
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  update public.expenses set description = 'edited', updated_at = now() + interval '1 minute' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and description = 'bread'; assert n = 1, 'frozen row read-only';
  select count(*) into n from public.categories where id = cb1; assert n = 1, 'ex-member still sees history category';
  select count(*) into n from public.family_expenses_since('epoch'); assert n = 0, 'ex-member sees no family rows';
  select count(*) into n from public.family_ledger(now() - interval '1 day', now() + interval '1 day');
  assert n = 3, 'ex-member ledger: own 2 contributions + own transfer only, got ' || n;
  select count(*) into n from public.categories where owner_id = b and deleted_at is null;
  assert n = 4, 'ex-member keeps own food, Gym, Kino, Taxi, got ' || n;
  insert into public.categories (id, owner_id, name, color_hex) values (gen_random_uuid(), b, 'Uy', '5B8DB8');

  -- A leaves last: family closes
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.device_tokens; assert n = 0, 'A lost tok-a to B, got ' || n;
  perform public.leave_family();
  select count(*) into n from public.families; assert n = 0, 'last member leaving closes family';

  raise exception 'ALL_TESTS_PASSED';
end $$;

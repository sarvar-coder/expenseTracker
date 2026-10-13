-- Access-rule check for the family schema. Run in the SQL editor (or via the
-- Supabase MCP execute_sql). It always ends in an exception so everything
-- rolls back: 'ALL_TESTS_PASSED' = green, any other message = the failed assert.
do $$
declare
  a uuid := gen_random_uuid(); b uuid := gen_random_uuid();
  ca uuid := gen_random_uuid(); cb1 uuid := gen_random_uuid(); cb2 uuid := gen_random_uuid();
  e1 uuid := gen_random_uuid(); eb1 uuid := gen_random_uuid(); eb2 uuid := gen_random_uuid(); eb4 uuid := gen_random_uuid();
  eb5 uuid := gen_random_uuid(); eb6 uuid := gen_random_uuid(); eb7 uuid := gen_random_uuid();
  nb1 uuid := gen_random_uuid(); nb2 uuid := gen_random_uuid(); nb3 uuid := gen_random_uuid(); na1 uuid := gen_random_uuid();
  fid uuid; inv uuid; req uuid; newcat uuid; bq uuid; n int; r record; ok boolean;
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
  select count(*) into n from public.categories where family_id = fid; assert n = 2, 'family has Food + Boshqa';
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
  select category_id into newcat from public.expenses where id = eb1; assert newcat = ca, 'food merged into Food';
  select count(*) into n from public.expenses where id = eb2 and pending_category = 'Gym'; assert n = 1, 'Gym pending in Boshqa';
  select count(*) into n from public.category_requests where name = 'Gym'; assert n = 1, 'request auto-created';
  execute 'reset role';
  select count(*) into n from private.push_outbox
  where user_ids = array[a] and data->>'type' = 'category_request' and data->>'category' = 'Gym' and data->>'name' = 'b';
  assert n = 1, 'admin A gets a push for the Gym request';
  execute 'set local role authenticated';
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, is_private, raw_input)
    values (eb4, fid, ca, 'secret', 30, now(), 'typed', true, 'secret 30');
  select count(*) into n from public.expenses where id = eb4 and not is_private; assert n = 1, 'old APK is_private forced false';
  update public.expenses set is_private = true, updated_at = now() + interval '1 second' where id = eb4;
  select count(*) into n from public.expenses where id = eb4 and not is_private; assert n = 1, 'is_private stays false on update';
  -- transfer to A: out of family totals and the family list
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, transfer_to, raw_input)
    values (eb7, fid, ca, '-> Ali', 500, now(), 'typed', a, 'Aliga 500');
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
  assert ok, 'member blocked from creating category';
  -- step 7: expense synced with pending_category files the request itself
  select id into bq from public.categories where family_id = fid and name = 'Boshqa';
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, pending_category) values
    (eb5, fid, bq, 'taxi', 10, now(), 'typed', ' Taxi '),
    (eb6, fid, bq, 'soup', 10, now(), 'typed', 'FOOD');
  select count(*) into n from public.category_requests where name = 'Taxi' and requested_by = b; assert n = 1, 'offline request filed on sync';
  select count(*) into n from public.expenses where id = eb6 and category_id = ca and pending_category is null;
  assert n = 1, 'existing category used instead of a request';
  update public.expenses set description = 'taxi2', updated_at = now() + interval '1 second' where id = eb5;
  select count(*) into n from public.category_requests where name = 'Taxi'; assert n = 1, 'no duplicate request';
  update public.expenses set family_id = null, updated_at = now() + interval '1 second' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and family_id = fid; assert n = 1, 'family_id immutable';
  update public.expenses set description = 'stale', updated_at = now() - interval '1 day' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and description = 'bread'; assert n = 1, 'stale write dropped';
  -- needs: one family item, one personal
  insert into public.needs (id, family_id, text) values (nb1, fid, 'milk'), (nb2, null, 'socks');

  -- A: privacy, summary math, approval, admin rules
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where owner_id = b; assert n = 0, 'A cannot read B rows directly';
  select count(*) into n from public.profiles where id = b; assert n = 0, 'A cannot read B profile/budget';
  select count(*) into n from public.needs; assert n = 1, 'A sees family need only, got ' || n;
  insert into public.needs (id, owner_id, family_id, text, done, updated_at)
    values (nb1, a, null, 'milk', true, now() + interval '1 second')
    on conflict (id) do update set done = excluded.done, family_id = excluded.family_id, updated_at = excluded.updated_at;
  select count(*) into n from public.needs where id = nb1 and done and family_id = fid and owner_id = b;
  assert n = 1, 'member ticks family need via upsert, owner/family kept';
  update public.needs set done = true where id = nb2;
  get diagnostics n = row_count; assert n = 0, 'A cannot touch B personal need';
  -- needs status (#110): owner marks own done, admin marks any done / rejects, pending is server-only
  select count(*) into n from public.needs where id = nb1 and status = 'done'; assert n = 1, 'old-style done=true sets status';
  insert into public.needs (id, family_id, text, link) values (na1, fid, 'bike', 'https://shop.uz/bike');
  begin
    insert into public.needs (id, text, link) values (gen_random_uuid(), 'x', 'javascript:alert(1)'); ok := false;
  exception when check_violation then ok := true; end;
  assert ok, 'link must be http(s)';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  begin update public.needs set status = 'done', done = true where id = na1; ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'member cannot mark others done';
  begin update public.needs set status = 'rejected' where id = nb1; ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'member cannot reject (even own)';
  begin update public.needs set status = 'pending' where id = nb1; ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'client cannot set pending';
  update public.needs set done = false where id = nb1; -- old APK untick
  select count(*) into n from public.needs where id = nb1 and status = 'new' and not done; assert n = 1, 'old-style untick reopens';
  update public.needs set status = 'done' where id = nb1;
  select count(*) into n from public.needs where id = nb1 and done; assert n = 1, 'owner marks own done, done follows status';
  -- photo: owner uploads; family sees; others can't write it
  insert into storage.objects (bucket_id, name) values ('need-photos', nb1 || '/photo.jpg');
  begin insert into storage.objects (bucket_id, name) values ('need-photos', na1 || '/photo.jpg'); ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'member cannot upload a photo for another need';
  insert into storage.objects (bucket_id, name) values ('need-photos', nb2 || '/photo.jpg');
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from storage.objects where bucket_id = 'need-photos'; assert n = 1, 'A sees family photo only, got ' || n;
  update public.needs set status = 'rejected' where id = nb1;
  select count(*) into n from public.needs where id = nb1 and status = 'rejected' and not done; assert n = 1, 'admin rejects';
  begin update public.needs set text = 'hijack' where id = nb1; ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'admin cannot edit others text';
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  begin update public.needs set done = true where id = nb1; ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'owner cannot un-reject';
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select amount, description, is_private, source, raw_input into r from public.family_expenses_since('epoch') where id = eb4;
  assert r.amount = 30 and r.description = 'secret2' and not r.is_private, 'no private: row shared with the family';
  assert r.source = 'typed' and r.raw_input = 'secret 30', 'feed carries source + raw input (read-only sheet)';
  select amount, is_private, raw_input into r from public.family_expenses_since('epoch') where id = eb7;
  assert r.amount is null and r.is_private and r.raw_input is null, 'transfer row is a tombstone';
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
  select id into req from public.category_requests where name = 'Gym';
  newcat := public.approve_category_request(req, '5B8DB8', null);
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where id = eb2 and category_id = newcat and pending_category is null;
  assert n = 1, 'approval moved Gym expense';
  execute 'reset role';
  select count(*) into n from private.push_outbox
  where user_ids = array[b] and data->>'type' = 'category_resolved' and data->>'status' = 'approved';
  assert n = 1, 'requester B gets a push for the approval';
  execute 'set local role authenticated';
  perform public.register_push_token('tok-a'); -- B signs in on A's phone
  select count(*) into n from public.device_tokens where token = 'tok-a'; assert n = 1, 'token taken over by B';
  delete from public.device_tokens where token = 'tok-a'; -- B signs out there
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select id into req from public.category_requests where name = 'Taxi' and status = 'pending';
  perform public.reject_category_request(req);
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where id = eb5 and category_id = bq and pending_category is null;
  assert n = 1, 'rejection leaves expense in Boshqa';
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
  select count(*) into n from public.categories where id = ca; assert n = 1, 'ex-member still sees history category';
  select count(*) into n from public.family_expenses_since('epoch'); assert n = 0, 'ex-member sees no family rows';
  select count(*) into n from public.family_ledger(now() - interval '1 day', now() + interval '1 day');
  assert n = 3, 'ex-member ledger: own 2 contributions + own transfer only, got ' || n;
  select count(*) into n from public.needs; assert n = 1, 'ex-member sees only own personal need, got ' || n;
  insert into public.needs (id, family_id, text) values (nb3, fid, 'queued offline');
  select count(*) into n from public.needs where id = nb3 and family_id is null; assert n = 1, 'stale family need becomes personal';
  select count(*) into n from public.categories where owner_id = b and deleted_at is null;
  assert n = 3, 'ex-member keeps Food, Boshqa, Gym as personal copies, got ' || n;
  insert into public.categories (id, owner_id, name, color_hex) values (gen_random_uuid(), b, 'Taxi', '5B8DB8');

  -- A leaves last: family closes
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.device_tokens; assert n = 0, 'A lost tok-a to B, got ' || n;
  perform public.leave_family();
  select count(*) into n from public.families; assert n = 0, 'last member leaving closes family';

  raise exception 'ALL_TESTS_PASSED';
end $$;

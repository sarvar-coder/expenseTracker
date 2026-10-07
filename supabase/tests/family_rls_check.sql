-- Access-rule check for the family schema. Run in the SQL editor (or via the
-- Supabase MCP execute_sql). It always ends in an exception so everything
-- rolls back: 'ALL_TESTS_PASSED' = green, any other message = the failed assert.
do $$
declare
  a uuid := gen_random_uuid(); b uuid := gen_random_uuid();
  ca uuid := gen_random_uuid(); cb1 uuid := gen_random_uuid(); cb2 uuid := gen_random_uuid();
  e1 uuid := gen_random_uuid(); eb1 uuid := gen_random_uuid(); eb2 uuid := gen_random_uuid(); eb4 uuid := gen_random_uuid();
  eb5 uuid := gen_random_uuid(); eb6 uuid := gen_random_uuid(); eb7 uuid := gen_random_uuid();
  nb1 uuid := gen_random_uuid(); nb2 uuid := gen_random_uuid(); nb3 uuid := gen_random_uuid();
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
  select count(*) into n from public.categories where family_id = fid; assert n = 2, 'family has Food + Boshqa';
  insert into public.invites (family_id, email, invited_by) values (fid, 'b@test.uz', a) returning id into inv;

  -- B: personal data, joins with history
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  insert into public.categories (id, owner_id, name, color_hex) values (cb1, b, 'food', 'E08A5B'), (cb2, b, 'Gym', '5B8DB8');
  insert into public.expenses (id, category_id, description, amount, date, source) values
    (eb1, cb1, 'bread', 50, now(), 'manual'), (eb2, cb2, 'gym', 70, now(), 'manual');
  select count(*) into n from public.invites; assert n = 1, 'B sees own invite';
  select * into r from public.my_invites(); assert r.family_name = 'Fam' and r.invited_by_name = 'Ali', 'invite shows family + inviter';
  perform public.accept_invite(inv, true);
  select category_id into newcat from public.expenses where id = eb1; assert newcat = ca, 'food merged into Food';
  select count(*) into n from public.expenses where id = eb2 and pending_category = 'Gym'; assert n = 1, 'Gym pending in Boshqa';
  select count(*) into n from public.category_requests where name = 'Gym'; assert n = 1, 'request auto-created';
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, is_private)
    values (eb4, fid, ca, 'secret', 30, now(), 'manual', true);
  -- transfer to A: out of family totals and the family list
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, transfer_to)
    values (eb7, fid, ca, '-> Ali', 500, now(), 'manual', a);
  update public.profiles set budget = 1000 where id = b;
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
  select amount, description into r from public.family_expenses_since('epoch') where id = eb4;
  assert r.amount is null and r.description is null, 'private row is a tombstone';
  select amount, is_private into r from public.family_expenses_since('epoch') where id = eb7;
  assert r.amount is null and r.is_private, 'transfer row is a tombstone';
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = b;
  assert r.shared_total = 140, 'B shared total 140, got ' || r.shared_total;
  assert r.contribution = 970, 'B contribution 970, got ' || r.contribution;
  select id into req from public.category_requests where name = 'Gym';
  newcat := public.approve_category_request(req, '5B8DB8', null);
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where id = eb2 and category_id = newcat and pending_category is null;
  assert n = 1, 'approval moved Gym expense';
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
  -- titles: self or admin; family_summary returns them
  perform public.set_title(b, ' Uka ');
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  perform public.set_title(b, 'Singil');
  begin perform public.set_title(a, 'Ota'); ok := false;
  exception when others then ok := sqlerrm = 'not_admin'; end;
  assert ok, 'member cannot set others title';
  update public.profiles set display_name = 'Bobur' where id = b;
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = b;
  assert r.title = 'Singil' and r.display_name = 'Bobur', 'summary has title + name, got ' || r.title || ' ' || r.display_name;
  perform public.set_title(b, '');
  select title into r from public.family_members where user_id = b;
  assert r.title is null, 'blank clears title';
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
  select count(*) into n from public.needs; assert n = 1, 'ex-member sees only own personal need, got ' || n;
  insert into public.needs (id, family_id, text) values (nb3, fid, 'queued offline');
  select count(*) into n from public.needs where id = nb3 and family_id is null; assert n = 1, 'stale family need becomes personal';
  select count(*) into n from public.categories where owner_id = b and deleted_at is null;
  assert n = 3, 'ex-member keeps Food, Boshqa, Gym as personal copies, got ' || n;
  insert into public.categories (id, owner_id, name, color_hex) values (gen_random_uuid(), b, 'Taxi', '5B8DB8');

  -- A leaves last: family closes
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  perform public.leave_family();
  select count(*) into n from public.families; assert n = 0, 'last member leaving closes family';

  raise exception 'ALL_TESTS_PASSED';
end $$;

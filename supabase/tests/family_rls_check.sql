-- Access-rule check for the family schema. Run in the SQL editor (or via the
-- Supabase MCP execute_sql). It always ends in an exception so everything
-- rolls back: 'ALL_TESTS_PASSED' = green, any other message = the failed assert.
do $$
declare
  a uuid := gen_random_uuid(); b uuid := gen_random_uuid();
  ca uuid := gen_random_uuid(); cb1 uuid := gen_random_uuid(); cb2 uuid := gen_random_uuid();
  e1 uuid := gen_random_uuid(); eb1 uuid := gen_random_uuid(); eb2 uuid := gen_random_uuid(); eb4 uuid := gen_random_uuid();
  fid uuid; inv uuid; req uuid; newcat uuid; n int; r record; ok boolean;
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
  perform public.accept_invite(inv, true);
  select category_id into newcat from public.expenses where id = eb1; assert newcat = ca, 'food merged into Food';
  select count(*) into n from public.expenses where id = eb2 and pending_category = 'Gym'; assert n = 1, 'Gym pending in Boshqa';
  select count(*) into n from public.category_requests where name = 'Gym'; assert n = 1, 'request auto-created';
  insert into public.expenses (id, family_id, category_id, description, amount, date, source, is_private)
    values (eb4, fid, ca, 'secret', 30, now(), 'manual', true);
  update public.profiles set budget = 1000 where id = b;
  begin
    insert into public.categories (id, family_id, name, color_hex) values (gen_random_uuid(), fid, 'X', 'AAAAAA');
    ok := false;
  exception when insufficient_privilege then ok := true; end;
  assert ok, 'member blocked from creating category';
  update public.expenses set family_id = null, updated_at = now() + interval '1 second' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and family_id = fid; assert n = 1, 'family_id immutable';
  update public.expenses set description = 'stale', updated_at = now() - interval '1 day' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and description = 'bread'; assert n = 1, 'stale write dropped';

  -- A: privacy, summary math, approval, admin rules
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where owner_id = b; assert n = 0, 'A cannot read B rows directly';
  select count(*) into n from public.profiles where id = b; assert n = 0, 'A cannot read B profile/budget';
  select amount, description into r from public.family_expenses_since('epoch') where id = eb4;
  assert r.amount is null and r.description is null, 'private row is a tombstone';
  select * into r from public.family_summary(now() - interval '1 day', now() + interval '1 day') where user_id = b;
  assert r.shared_total = 120, 'B shared total 120, got ' || r.shared_total;
  assert r.contribution = 970, 'B contribution 970, got ' || r.contribution;
  select id into req from public.category_requests where name = 'Gym';
  newcat := public.approve_category_request(req, '5B8DB8', null);
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  select count(*) into n from public.expenses where id = eb2 and category_id = newcat and pending_category is null;
  assert n = 1, 'approval moved Gym expense';
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  begin perform public.leave_family(); ok := false;
  exception when others then ok := sqlerrm = 'transfer_admin_first'; end;
  assert ok, 'admin must transfer first';
  perform public.remove_member(b);
  select count(*) into n from public.family_expenses_since('epoch') where id = eb1 and frozen; assert n = 1, 'history kept frozen';

  -- B after removal
  perform set_config('request.jwt.claims', json_build_object('sub', b, 'email', 'b@test.uz', 'role', 'authenticated')::text, true);
  update public.expenses set description = 'edited', updated_at = now() + interval '1 minute' where id = eb1;
  select count(*) into n from public.expenses where id = eb1 and description = 'bread'; assert n = 1, 'frozen row read-only';
  select count(*) into n from public.categories where id = ca; assert n = 1, 'ex-member still sees history category';
  select count(*) into n from public.family_expenses_since('epoch'); assert n = 0, 'ex-member sees no family rows';

  -- A leaves last: family closes
  perform set_config('request.jwt.claims', json_build_object('sub', a, 'email', 'a@test.uz', 'role', 'authenticated')::text, true);
  perform public.leave_family();
  select count(*) into n from public.families; assert n = 0, 'last member leaving closes family';

  raise exception 'ALL_TESTS_PASSED';
end $$;

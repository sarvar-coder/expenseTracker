-- Push notifications (FCM). Devices register their token; database triggers
-- queue a push in private.push_outbox and ping the `notify` Edge Function
-- through pg_net with only the outbox id. `notify` claims (deletes) the row
-- with the service role, so nobody on the internet can pick recipients or
-- text, or replay a send: an unknown id sends nothing. Additive: old APKs
-- never register a token and simply get no pushes.

create extension if not exists pg_net with schema extensions;

-- One row per device. The token is the key: when another account signs in on
-- the same phone the token moves to it (register_push_token).
create table public.device_tokens (
  token text primary key check (length(token) between 1 and 4096),
  user_id uuid not null default auth.uid() references public.profiles on delete cascade,
  updated_at timestamptz not null default now()
);
create index device_tokens_user_idx on public.device_tokens (user_id);

alter table public.device_tokens enable row level security;
create policy device_tokens_own on public.device_tokens for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
revoke all on public.device_tokens from anon;

-- Upsert that also takes the token over from a previous account on this
-- phone (a plain upsert can't: that row isn't ours under RLS).
create function public.register_push_token(p_token text)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  insert into public.device_tokens (token, user_id) values (p_token, auth.uid())
  on conflict (token) do update set user_id = excluded.user_id, updated_at = now();
end $$;
revoke execute on function public.register_push_token(text) from public, anon;
grant execute on function public.register_push_token(text) to authenticated;

create table private.push_outbox (
  id uuid primary key default gen_random_uuid(),
  user_ids uuid[] not null,
  data jsonb not null, -- flat string map: FCM data payload; `type` picks text, channel and tap route in the app
  created_at timestamptz not null default now()
);
alter table private.push_outbox enable row level security; -- no policies: definer functions only

-- Queue one push to [p_users] and wake `notify` (pg_net sends after commit;
-- a rolled-back transaction sends nothing).
create function private.push(p_users uuid[], p_data jsonb)
returns void language plpgsql security definer set search_path = '' as $$
declare oid uuid;
begin
  if not exists (select 1 from public.device_tokens where user_id = any(p_users)) then return; end if;
  -- ponytail: rows notify never claimed (function down) are swept here, no cron.
  delete from private.push_outbox where created_at < now() - interval '1 day';
  insert into private.push_outbox (user_ids, data) values (p_users, p_data) returning id into oid;
  perform net.http_post(
    url := 'https://xgxygopxnchdobuooyzw.supabase.co/functions/v1/notify',
    body := jsonb_build_object('id', oid),
    headers := '{"Content-Type": "application/json"}'::jsonb
  );
end $$;
revoke execute on function private.push(uuid[], jsonb) from public, anon, authenticated;

-- notify (service role only): take the queued push once, with its tokens.
create function public.claim_push(p_id uuid)
returns table (token text, user_id uuid, data jsonb)
language sql security definer set search_path = '' as $$
  with o as (delete from private.push_outbox where id = p_id returning user_ids, data)
  select t.token, t.user_id, o.data from o join public.device_tokens t on t.user_id = any(o.user_ids)
$$;

-- notify (service role only): tokens FCM reports as gone.
create function public.drop_push_tokens(p_tokens text[])
returns void language sql security definer set search_path = '' as $$
  delete from public.device_tokens where token = any(p_tokens)
$$;

revoke execute on function public.claim_push(uuid), public.drop_push_tokens(text[]) from public, anon, authenticated;
grant execute on function public.claim_push(uuid), public.drop_push_tokens(text[]) to service_role;

-- Category requests: admins hear about a new one, the requester about the answer.
-- Not when the family is being deleted (close_family rejects all pending).
create function private.notify_category_request()
returns trigger language plpgsql security definer set search_path = '' as $$
declare who text;
begin
  if exists (select 1 from public.families where id = new.family_id and deleted_at is not null) then
    return null;
  end if;
  if tg_op = 'INSERT' then
    if new.status <> 'pending' then return null; end if;
    select coalesce(nullif(display_name, ''), split_part(email, '@', 1)) into who
    from public.profiles where id = new.requested_by;
    perform private.push(
      array(select user_id from public.family_members
            where family_id = new.family_id and role = 'admin' and user_id <> new.requested_by),
      jsonb_build_object('type', 'category_request', 'name', coalesce(who, ''), 'category', new.name)
    );
  elsif new.status <> old.status and new.status in ('approved', 'rejected') then
    perform private.push(
      array[new.requested_by],
      jsonb_build_object('type', 'category_resolved', 'category', new.name, 'status', new.status)
    );
  end if;
  return null;
end $$;
revoke execute on function private.notify_category_request() from public, anon, authenticated;

create trigger category_requests_notify after insert or update of status on public.category_requests
  for each row execute function private.notify_category_request();

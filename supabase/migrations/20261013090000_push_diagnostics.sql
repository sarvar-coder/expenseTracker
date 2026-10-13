-- Push diagnostics (#96): each phone reports whether it can show
-- notifications and which app version it runs, so a silent phone can be
-- told apart from a missing token without access to the device.
alter table public.device_tokens
  add column can_notify boolean,
  add column app_version text check (length(app_version) <= 64);

-- Replaced (not overloaded): one signature keeps PostgREST unambiguous.
-- Old APKs send only p_token; the new columns then stay as they were.
drop function public.register_push_token(text);
create function public.register_push_token(p_token text, p_can_notify boolean default null, p_version text default null)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  insert into public.device_tokens (token, user_id, can_notify, app_version)
  values (p_token, auth.uid(), p_can_notify, p_version)
  on conflict (token) do update set
    user_id = excluded.user_id,
    can_notify = coalesce(excluded.can_notify, public.device_tokens.can_notify),
    app_version = coalesce(excluded.app_version, public.device_tokens.app_version),
    updated_at = now();
end $$;
revoke execute on function public.register_push_token(text, boolean, text) from public, anon;
grant execute on function public.register_push_token(text, boolean, text) to authenticated;

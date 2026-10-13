-- Review fix (#96): a token taken over by another account starts with that
-- caller's diagnostics, not the previous owner's (nulls from old APKs included).
create or replace function public.register_push_token(p_token text, p_can_notify boolean default null, p_version text default null)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not_signed_in'; end if;
  insert into public.device_tokens (token, user_id, can_notify, app_version)
  values (p_token, auth.uid(), p_can_notify, p_version)
  on conflict (token) do update set
    can_notify = case when public.device_tokens.user_id = excluded.user_id
      then coalesce(excluded.can_notify, public.device_tokens.can_notify) else excluded.can_notify end,
    app_version = case when public.device_tokens.user_id = excluded.user_id
      then coalesce(excluded.app_version, public.device_tokens.app_version) else excluded.app_version end,
    user_id = excluded.user_id,
    updated_at = now();
end $$;

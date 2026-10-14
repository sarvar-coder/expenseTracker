-- 2.3.0: Kerakli (needs + chat + photos) removed; the tab becomes Muhokama (blank).
-- Tables go with their triggers, policies and indexes. private.sync_stamp is shared, kept.
drop table if exists public.need_comments;
drop table if exists public.needs;

drop function if exists private.need_guard();
drop function if exists private.need_comment_guard();
drop function if exists private.on_need_comment();
drop function if exists private.in_need_family(uuid);

drop policy if exists need_photos_select on storage.objects;
drop policy if exists need_photos_insert on storage.objects;
drop policy if exists need_photos_update on storage.objects;
drop function if exists private.can_see_need(text);
drop function if exists private.owns_need(text);

-- The `need-photos` bucket itself: Supabase blocks SQL deletes on storage tables,
-- so it is emptied and deleted through the Storage API / dashboard.

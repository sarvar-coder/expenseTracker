-- Review of #111: a deleted need has no chat (no reads, comments, pending
-- flip or push); a hard delete (author's account removed) recounts too.

create or replace function private.in_need_family(p_id uuid) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.needs n
    where n.id = p_id and n.deleted_at is null
      and n.family_id is not null and n.family_id = private.my_family_id()
  )
$$;

create or replace function private.on_need_comment() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  r record;
  nid uuid := coalesce(new.need_id, old.need_id);
begin
  update public.needs n set
    comment_count = (select count(*) from public.need_comments c where c.need_id = n.id and c.deleted_at is null),
    status = case when tg_op = 'INSERT' and n.status = 'new' then 'pending' else n.status end
  where n.id = nid
  returning n.family_id, n.text into r;

  if tg_op = 'INSERT' and r.family_id is not null then
    begin
      perform private.push(
        array(select user_id from public.family_members where family_id = r.family_id and user_id <> new.author_id),
        jsonb_build_object('type', 'need_comment', 'need_id', new.need_id, 'name', new.author_name,
                           'item', left(r.text, 60), 'text', left(new.body, 120))
      );
    exception when others then
      raise warning 'need comment push: %', sqlerrm; -- a lost push must never fail the comment
    end;
  end if;
  return null;
end $$;

drop trigger need_comments_20_after on public.need_comments;
create trigger need_comments_20_after after insert or update of deleted_at or delete on public.need_comments
  for each row execute function private.on_need_comment();

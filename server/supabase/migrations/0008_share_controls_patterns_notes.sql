begin;
-- 보고서 「핸들온 고도화 사례와 근거」 1·2·7순위.
-- 1) 공유 통제: 받는 사람별 운전 패턴 공유 여부, 운전자 전체 일시중지(휴대폰·차 어디서나), 차에서 모두 끊기, 가족 화면 미리보기.
-- 2) L1 운전 패턴: 월별 횟수·시간·비율만(지명·좌표·거리 km 없음). 생활반경·가는 곳은 본인 기준 대비 비율로만.
-- 7) 가족 한마디: 가족 → 운전자. 차는 주차가 확정된 뒤에만 보여 준다(앱 쪽 규칙).

-- 기존 공유는 패턴에 동의한 적이 없으므로 false 로 채우고, 이후로는 RPC 가 값을 반드시 받는다(기본값 없음).
alter table public.family_links add column share_pattern boolean not null default false;
alter table public.family_links alter column share_pattern drop default;
alter table public.invites add column share_pattern boolean not null default false;
alter table public.invites alter column share_pattern drop default;
alter table public.profiles add column sharing_paused_at timestamptz;

-- 휴대폰의 운전자 본인 또는 그 운전자와 연결된 차. 둘 다 아니면 null.
create function public.sharing_owner() returns uuid
language sql stable security definer set search_path = public as $$
  select coalesce(
    (select p.id from public.profiles p where p.id = auth.uid() and p.role = 'driver'),
    (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
$$;
revoke execute on function public.sharing_owner() from public, anon, authenticated;

create table public.monthly_patterns (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  month date not null check (extract(day from month) = 1),
  partial boolean not null,
  trips integer not null check (trips between 0 and 2000),
  drive_minutes integer not null check (drive_minutes between 0 and 44640),
  drive_days integer not null check (drive_days between 0 and 31),
  night_ratio real check (night_ratio between 0 and 1),
  long_ratio real check (long_ratio between 0 and 1),
  radius_ratio real check (radius_ratio between 0 and 20),
  places_ratio real check (places_ratio between 0 and 20),
  usual_trips integer check (usual_trips between 0 and 2000),
  usual_minutes integer check (usual_minutes between 0 and 44640),
  usual_night_ratio real check (usual_night_ratio between 0 and 1),
  usual_long_ratio real check (usual_long_ratio between 0 and 1),
  source text not null check (source in ('car','replay')),
  created_at timestamptz not null default now(),
  primary key (driver_id, month)
);
create trigger stamp_pattern before insert or update on public.monthly_patterns for each row execute function public.stamp_summary_upload();
alter table public.monthly_patterns enable row level security;
revoke all on public.monthly_patterns from anon, authenticated;
grant select, insert, update on public.monthly_patterns to authenticated;
grant all on public.monthly_patterns to service_role;
create policy read_pattern on public.monthly_patterns for select to authenticated
using (driver_id = auth.uid() or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy insert_pattern on public.monthly_patterns for insert to authenticated
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy update_pattern on public.monthly_patterns for update to authenticated
using (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));

create table public.family_notes (
  id bigserial primary key,
  driver_id uuid not null,
  family_id uuid not null,
  body text not null check (length(btrim(body)) between 1 and 120),
  created_at timestamptz not null default now(),
  seen_at timestamptz,
  foreign key (driver_id, family_id) references public.family_links(driver_id, family_id) on delete cascade
);
create index family_notes_unseen_idx on public.family_notes(driver_id, created_at) where seen_at is null;
alter table public.family_notes enable row level security;
revoke all on public.family_notes from anon, authenticated;
grant all on public.family_notes to service_role;
grant usage, select on sequence public.family_notes_id_seq to service_role;

-- 가족 화면에 나가는 행은 이 두 함수만 만든다. 가족 피드와 운전자의 미리보기가 같은 결과를 보게 하려는 것.
create function public.family_feed_for(p_family uuid, p_driver uuid)
returns table(driver_id uuid, driver_name text, month date, stage text, domains jsonb, top jsonb, letter text, scope text)
language sql stable security definer set search_path = public as $$
  select m.driver_id, p.display_name, m.month, m.stage,
    case when f.scope in ('trend','detail') then m.domains end,
    case when f.scope = 'detail' then m.top end,
    case when f.scope = 'detail' then m.letter end,
    f.scope
  from public.family_links f join public.monthly_summaries m on m.driver_id = f.driver_id
    join public.profiles p on p.id = f.driver_id
  where f.family_id = p_family and (p_driver is null or f.driver_id = p_driver)
    and f.revoked_at is null and p.sharing_paused_at is null
    and m.created_at <= now() - make_interval(hours => f.delay_days * 24)
  order by m.month desc, m.driver_id
$$;
revoke execute on function public.family_feed_for(uuid,uuid) from public, anon, authenticated;

create function public.family_patterns_for(p_family uuid, p_driver uuid)
returns table(driver_id uuid, driver_name text, month date, partial boolean, trips integer, drive_minutes integer,
  drive_days integer, night_ratio real, long_ratio real, radius_ratio real, places_ratio real,
  usual_trips integer, usual_minutes integer, usual_night_ratio real, usual_long_ratio real)
language sql stable security definer set search_path = public as $$
  select t.driver_id, p.display_name, t.month, t.partial, t.trips, t.drive_minutes, t.drive_days,
    t.night_ratio, t.long_ratio, t.radius_ratio, t.places_ratio,
    t.usual_trips, t.usual_minutes, t.usual_night_ratio, t.usual_long_ratio
  from public.family_links f join public.monthly_patterns t on t.driver_id = f.driver_id
    join public.profiles p on p.id = f.driver_id
  where f.family_id = p_family and (p_driver is null or f.driver_id = p_driver)
    and f.revoked_at is null and f.share_pattern and p.sharing_paused_at is null
    and t.created_at <= now() - make_interval(hours => f.delay_days * 24)
  order by t.month desc, t.driver_id
$$;
revoke execute on function public.family_patterns_for(uuid,uuid) from public, anon, authenticated;

create or replace function public.get_family_feed()
returns table(driver_id uuid, driver_name text, month date, stage text, domains jsonb, top jsonb, letter text, scope text)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select * from public.family_feed_for(auth.uid(), null);
end $$;

create function public.get_family_patterns()
returns table(driver_id uuid, driver_name text, month date, partial boolean, trips integer, drive_minutes integer,
  drive_days integer, night_ratio real, long_ratio real, radius_ratio real, places_ratio real,
  usual_trips integer, usual_minutes integer, usual_night_ratio real, usual_long_ratio real)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select * from public.family_patterns_for(auth.uid(), null);
end $$;
revoke execute on function public.get_family_patterns() from public, anon;
grant execute on function public.get_family_patterns() to authenticated;

-- 가족이 자기에게 열린 공유를 본다: 범위, 시점, 패턴 여부, 일시중지 여부.
create function public.get_family_links()
returns table(driver_id uuid, driver_name text, scope text, delay_days integer, share_pattern boolean, paused boolean)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select f.driver_id, p.display_name, f.scope, f.delay_days, f.share_pattern, p.sharing_paused_at is not null
    from public.family_links f join public.profiles p on p.id = f.driver_id
    where f.family_id = auth.uid() and f.revoked_at is null
    order by f.created_at, f.driver_id;
end $$;
revoke execute on function public.get_family_links() from public, anon;
grant execute on function public.get_family_links() to authenticated;

-- 운전자가 "이 가족에게 지금 무엇이 보이는지"를 가족 피드와 같은 함수로 본다.
create function public.preview_family_view(p_family_id uuid) returns jsonb
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if not exists(select 1 from public.family_links where driver_id = auth.uid() and family_id = p_family_id and revoked_at is null) then
    raise exception 'share_not_found';
  end if;
  return jsonb_build_object(
    'paused', (select sharing_paused_at is not null from public.profiles where id = auth.uid()),
    'feed', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_feed_for(p_family_id, auth.uid()) r), '[]'::jsonb),
    'patterns', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_patterns_for(p_family_id, auth.uid()) r), '[]'::jsonb));
end $$;
revoke execute on function public.preview_family_view(uuid) from public, anon;
grant execute on function public.preview_family_view(uuid) to authenticated;

-- 휴대폰과 차가 함께 쓰는 공유 상태.
create function public.get_sharing_state() returns jsonb
language plpgsql stable security definer set search_path = public as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  return jsonb_build_object(
    'paused', (select sharing_paused_at is not null from public.profiles where id = owner),
    'recipients', coalesce((select jsonb_agg(jsonb_build_object('family_name', p.display_name, 'scope', f.scope,
        'delay_days', f.delay_days, 'share_pattern', f.share_pattern) order by f.created_at, f.family_id)
      from public.family_links f join public.profiles p on p.id = f.family_id
      where f.driver_id = owner and f.revoked_at is null), '[]'::jsonb));
end $$;
revoke execute on function public.get_sharing_state() from public, anon;
grant execute on function public.get_sharing_state() to authenticated;

create function public.set_sharing_paused(p_paused boolean) returns void
language plpgsql security definer set search_path = public as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  if p_paused is null then raise exception 'choice_required'; end if;
  -- enqueue_summary_notifications 와 같은 순서(링크 → 큐)로 잠근다.
  perform 1 from public.family_links where driver_id = owner order by family_id for update;
  update public.profiles set sharing_paused_at = case when p_paused then coalesce(sharing_paused_at, clock_timestamp()) end
    where id = owner;
  -- 멈춘 동안 쌓인 알림이 다시 켤 때 한꺼번에 가지 않게 지운다.
  if p_paused then
    update public.notification_queue set cancelled_at = clock_timestamp()
      where driver_id = owner and sent_at is null and cancelled_at is null;
  end if;
end $$;
revoke execute on function public.set_sharing_paused(boolean) from public, anon;
grant execute on function public.set_sharing_paused(boolean) to authenticated;

create function public.revoke_all_shares() returns integer
language plpgsql security definer set search_path = public as $$
declare owner uuid := public.sharing_owner(); n integer;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  perform 1 from public.family_links where driver_id = owner order by family_id for update;
  update public.family_links set revoked_at = clock_timestamp(), updated_at = clock_timestamp()
    where driver_id = owner and revoked_at is null;
  get diagnostics n = row_count;
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = owner and sent_at is null and cancelled_at is null;
  return n;
end $$;
revoke execute on function public.revoke_all_shares() from public, anon;
grant execute on function public.revoke_all_shares() to authenticated;

-- 초대·수정은 패턴 공유 여부까지 반드시 받는다. 이전 서명은 지워서 빠뜨린 호출이 조용히 false 로 가지 않게 한다.
drop function public.create_invite(text, integer);
create function public.create_invite(p_scope text, p_delay_days integer, p_share_pattern boolean) returns text
language plpgsql security definer set search_path = public as $$
declare result text; alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; bytes bytea; i integer;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if p_scope is null or p_delay_days is null or p_share_pattern is null
    or p_scope not in ('stage','trend','detail') or p_delay_days not in (0,3,7,14) then raise exception 'scope_and_timing_required'; end if;
  loop
    result := ''; bytes := extensions.gen_random_bytes(8);
    for i in 0..7 loop result := result || substr(alphabet, (get_byte(bytes,i) % 32) + 1, 1); end loop;
    begin
      insert into public.invites(code,driver_id,scope,delay_days,share_pattern,expires_at)
      values(result,auth.uid(),p_scope,p_delay_days,p_share_pattern,clock_timestamp() + interval '7 days');
      return result;
    exception when unique_violation then null;
    end;
  end loop;
end $$;
revoke execute on function public.create_invite(text,integer,boolean) from public, anon;
grant execute on function public.create_invite(text,integer,boolean) to authenticated;

create or replace function public.accept_invite(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare invitation public.invites;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  select * into invitation from public.invites where code = p_code for update;
  if not found or invitation.accepted_by is not null or invitation.expires_at <= clock_timestamp() then raise exception 'invalid_or_expired_code'; end if;
  insert into public.family_links(driver_id,family_id,scope,delay_days,share_pattern)
  values(invitation.driver_id,auth.uid(),invitation.scope,invitation.delay_days,invitation.share_pattern)
  on conflict (driver_id,family_id) do update set scope = excluded.scope, delay_days = excluded.delay_days,
    share_pattern = excluded.share_pattern, revoked_at = null, updated_at = clock_timestamp();
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = invitation.driver_id and family_id = auth.uid() and sent_at is null and cancelled_at is null;
  update public.invites set accepted_by = auth.uid() where code = p_code;
end $$;

drop function public.preview_invite(text);
create function public.preview_invite(p_code text)
returns table(driver_name text, scope text, delay_days integer, share_pattern boolean)
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  return query select p.display_name, i.scope, i.delay_days, i.share_pattern
    from public.invites i join public.profiles p on p.id = i.driver_id
    where i.code = p_code and i.accepted_by is null and i.expires_at > clock_timestamp();
  if not found then raise exception 'invalid_or_expired_code'; end if;
end $$;
revoke execute on function public.preview_invite(text) from public, anon;
grant execute on function public.preview_invite(text) to authenticated;

drop function public.update_share(uuid, text, integer);
create function public.update_share(p_family_id uuid, p_scope text, p_delay_days integer, p_share_pattern boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if p_scope is null or p_delay_days is null or p_share_pattern is null
    or p_scope not in ('stage','trend','detail') or p_delay_days not in (0,3,7,14) then raise exception 'scope_and_timing_required'; end if;
  update public.family_links set scope = p_scope, delay_days = p_delay_days, share_pattern = p_share_pattern, updated_at = clock_timestamp()
    where driver_id = auth.uid() and family_id = p_family_id and revoked_at is null;
  if not found then raise exception 'share_not_found'; end if;
  update public.notification_queue q set due_at = m.created_at + make_interval(hours => p_delay_days * 24)
    from public.monthly_summaries m where q.driver_id = auth.uid() and q.family_id = p_family_id
      and m.driver_id = q.driver_id and m.month = q.month and q.sent_at is null and q.cancelled_at is null;
end $$;
revoke execute on function public.update_share(uuid,text,integer,boolean) from public, anon;
grant execute on function public.update_share(uuid,text,integer,boolean) to authenticated;

drop function public.get_my_shares();
create function public.get_my_shares()
returns table(family_id uuid, family_name text, scope text, delay_days integer, share_pattern boolean, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  return query select f.family_id, p.display_name, f.scope, f.delay_days, f.share_pattern, f.created_at
    from public.family_links f join public.profiles p on p.id = f.family_id
    where f.driver_id = auth.uid() and f.revoked_at is null
    order by f.created_at, f.family_id;
end $$;
revoke execute on function public.get_my_shares() from public, anon;
grant execute on function public.get_my_shares() to authenticated;

-- 멈춘 동안에는 알림을 새로 만들지 않는다. 링크를 잠근 뒤에 읽어야 동시에 들어온 멈춤을 놓치지 않는다.
create or replace function public.enqueue_summary_notifications() returns trigger
language plpgsql security definer set search_path = public as $$
declare f record; changed boolean; paused boolean;
begin
  changed := tg_op = 'INSERT';
  if tg_op = 'UPDATE' then changed := new.stage is distinct from old.stage; end if;
  for f in select * from public.family_links where driver_id = new.driver_id order by family_id for update loop
    if paused is null then
      select p.sharing_paused_at is not null into paused from public.profiles p where p.id = new.driver_id;
    end if;
    if f.revoked_at is not null then continue; end if;
    if paused or new.stage not in ('watch','talk') then
      update public.notification_queue set cancelled_at = clock_timestamp()
        where driver_id = new.driver_id and family_id = f.family_id and month = new.month
          and sent_at is null and cancelled_at is null;
    else
      update public.notification_queue set due_at = new.created_at + make_interval(hours => f.delay_days * 24)
        where driver_id = new.driver_id and family_id = f.family_id and month = new.month
          and sent_at is null and cancelled_at is null;
      if changed then
        insert into public.notification_queue(driver_id,family_id,month,due_at)
          values(new.driver_id,f.family_id,new.month,new.created_at + make_interval(hours => f.delay_days * 24));
      end if;
    end if;
  end loop;
  return new;
end $$;

-- 가족 한마디. 읽지 않은 한마디는 가족 한 명당 3개까지(차 화면을 메시지로 채우지 않게).
create function public.send_family_note(p_driver_id uuid, p_body text) returns bigint
language plpgsql security definer set search_path = public as $$
declare result bigint;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  if p_body is null or length(btrim(p_body)) not between 1 and 120 then raise exception 'invalid_note'; end if;
  perform 1 from public.family_links where driver_id = p_driver_id and family_id = auth.uid() and revoked_at is null for update;
  if not found then raise exception 'share_not_found'; end if;
  if (select count(*) from public.family_notes where driver_id = p_driver_id and family_id = auth.uid() and seen_at is null) >= 3 then
    raise exception 'too_many_unseen';
  end if;
  insert into public.family_notes(driver_id, family_id, body) values(p_driver_id, auth.uid(), btrim(p_body)) returning id into result;
  return result;
end $$;
revoke execute on function public.send_family_note(uuid,text) from public, anon;
grant execute on function public.send_family_note(uuid,text) to authenticated;

create function public.get_car_notes()
returns table(id bigint, family_name text, body text, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  return query select n.id, p.display_name, n.body, n.created_at
    from public.family_notes n join public.family_links f on f.driver_id = n.driver_id and f.family_id = n.family_id
      join public.profiles p on p.id = n.family_id
    where n.driver_id = owner and n.seen_at is null and f.revoked_at is null
    order by n.created_at, n.id limit 3;
end $$;
revoke execute on function public.get_car_notes() from public, anon;
grant execute on function public.get_car_notes() to authenticated;

create function public.mark_note_seen(p_id bigint) returns void
language plpgsql security definer set search_path = public as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  update public.family_notes set seen_at = coalesce(seen_at, clock_timestamp()) where id = p_id and driver_id = owner;
  if not found then raise exception 'note_not_found'; end if;
end $$;
revoke execute on function public.mark_note_seen(bigint) from public, anon;
grant execute on function public.mark_note_seen(bigint) to authenticated;

create function public.get_sent_notes()
returns table(id bigint, driver_id uuid, driver_name text, body text, created_at timestamptz, seen_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  -- 반환 열 id 와 겹치지 않게 테이블 이름을 붙인다.
  if not exists(select 1 from public.profiles pr where pr.id = auth.uid() and pr.role = 'family') then raise exception 'family_required'; end if;
  return query select n.id, n.driver_id, p.display_name, n.body, n.created_at, n.seen_at
    from public.family_notes n join public.profiles p on p.id = n.driver_id
    where n.family_id = auth.uid()
    order by n.created_at desc, n.id desc limit 20;
end $$;
revoke execute on function public.get_sent_notes() from public, anon;
grant execute on function public.get_sent_notes() to authenticated;

-- 한마디는 90일 뒤 지운다.
select cron.schedule('handleon-purge-notes', '17 3 * * *',
  $job$delete from public.family_notes where created_at < now() - interval '90 days'$job$);
commit;

begin;

-- Weekly top has no free text, coordinates, grade or arbitrary/nested JSON keys.
create function public.valid_weekly_json(p_domains jsonb, p_top jsonb) returns boolean
language plpgsql immutable set search_path = '' as $$
declare item jsonb;
begin
  if p_domains is null or p_top is null or jsonb_typeof(p_domains) <> 'array' or jsonb_typeof(p_top) <> 'array' then return false; end if;
  if jsonb_array_length(p_domains) not between 1 and 3 or jsonb_array_length(p_top) > 3 then return false; end if;
  for item in select value from jsonb_array_elements(p_domains) loop
    if item not in ('"MOBILITY"'::jsonb,'"WAYFINDING"'::jsonb,'"OPERATION"'::jsonb) then return false; end if;
  end loop;
  for item in select value from jsonb_array_elements(p_top) loop
    if jsonb_typeof(item) <> 'object' then return false; end if;
    if not (item ?& array['feature','direction']) or item - array['feature','direction'] <> '{}'::jsonb then return false; end if;
    if jsonb_typeof(item->'feature') <> 'string' or item->>'feature' not in
      ('M1','M2','M3','M4','M5','M6','M7','W2','W3','W4','O1','O2','O7','O8','O9')
      or jsonb_typeof(item->'direction') <> 'string' or item->>'direction' not in ('up','down') then return false; end if;
  end loop;
  return true;
end $$;
revoke execute on function public.valid_weekly_json(jsonb,jsonb) from public, anon, authenticated;
grant execute on function public.valid_weekly_json(jsonb,jsonb) to authenticated, service_role;

create table public.weekly_changes (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  week_start date not null check (isfinite(week_start) and extract(isodow from week_start) = 1),
  flagged boolean not null,
  domains jsonb not null, top jsonb not null,
  source text not null check (source in ('car','replay')),
  created_at timestamptz not null default clock_timestamp(),
  primary key (driver_id, week_start),
  check (public.valid_weekly_json(domains, top))
);
create table public.weekly_habits (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  week_start date not null check (isfinite(week_start) and extract(isodow from week_start) = 1),
  trips integer not null check (trips between 0 and 200),
  minutes integer not null check (minutes between 0 and 10080),
  signal_kept integer not null check (signal_kept >= 0),
  signal_total integer not null check (signal_total >= 0 and signal_kept <= signal_total),
  hard_brakes integer not null check (hard_brakes >= 0),
  hard_accels integer not null check (hard_accels >= 0),
  calm_trips integer not null check (calm_trips between 0 and trips),
  long_stretches integer not null check (long_stretches >= 0),
  night_trips integer not null check (night_trips between 0 and trips),
  promises_kept integer not null check (promises_kept >= 0),
  promises_total integer not null check (promises_total >= 0 and promises_kept <= promises_total),
  story text not null check (char_length(story) <= 60)
    check (story !~ '[0-9]+[.][0-9]+')
    -- A blacklist cannot exclude arbitrary place names; accept only location-free templates.
    check (story in ('', '이번 주 운전 기록을 함께 돌아보세요', '이번 주에도 쉬어 가며 운전했어요',
      '방향지시등을 모두 켰어요', '운전 약속을 모두 지켰어요')
      or story ~ '^이번 주 [0-9]{1,3}번 운전했어요$'),
  source text not null check (source in ('car','replay')),
  created_at timestamptz not null default clock_timestamp(),
  primary key (driver_id, week_start)
);
create trigger stamp_weekly_change before insert or update on public.weekly_changes for each row execute function public.stamp_summary_upload();
create trigger stamp_weekly_habits before insert or update on public.weekly_habits for each row execute function public.stamp_summary_upload();
alter table public.weekly_changes enable row level security;
alter table public.weekly_habits enable row level security;
revoke all on public.weekly_changes, public.weekly_habits from public, anon, authenticated;
grant select, insert, update on public.weekly_changes, public.weekly_habits to authenticated;
grant all on public.weekly_changes, public.weekly_habits to service_role;
-- Like monthly_patterns: linked cars need SELECT for ON CONFLICT UPDATE. S2 also permits the driver to write.
create policy read_weekly_change on public.weekly_changes for select to authenticated
using (driver_id = auth.uid() or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy insert_weekly_change on public.weekly_changes for insert to authenticated
with check ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy update_weekly_change on public.weekly_changes for update to authenticated
using ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
with check ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy read_weekly_habits on public.weekly_habits for select to authenticated
using (driver_id = auth.uid() or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy insert_weekly_habits on public.weekly_habits for insert to authenticated
with check ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy update_weekly_habits on public.weekly_habits for update to authenticated
using ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
with check ((driver_id = auth.uid() and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));

create unique index notification_weekly_pending_idx on public.notification_queue(driver_id,family_id,ref)
  where kind = 'weekly_change' and sent_at is null and cancelled_at is null;
create function public.enqueue_weekly_notifications() returns trigger
language plpgsql security definer set search_path = '' as $$
declare f record; paused boolean; enabled boolean;
begin
  for f in select * from public.family_links where driver_id = new.driver_id order by family_id for update loop
    select p.sharing_paused_at is not null into paused from public.profiles p where p.id = new.driver_id;
    select o.weekly_change into enabled from public.share_options o where o.driver_id = new.driver_id and o.family_id = f.family_id;
    if f.revoked_at is not null or paused or not coalesce(enabled,false) or not new.flagged then
      update public.notification_queue set cancelled_at = clock_timestamp()
        where driver_id = new.driver_id and family_id = f.family_id and kind = 'weekly_change' and ref = new.week_start::text
          and sent_at is null and cancelled_at is null;
      continue;
    end if;
    update public.notification_queue set due_at = new.created_at + make_interval(hours => f.delay_days * 24)
      where driver_id = new.driver_id and family_id = f.family_id and kind = 'weekly_change' and ref = new.week_start::text
        and sent_at is null and cancelled_at is null;
    if found then continue; end if;
    -- Exact retries cannot requeue a cancelled or delivered upload. A changed upload may queue again.
    if tg_op = 'UPDATE' then
      if to_jsonb(new) is not distinct from to_jsonb(old) then continue; end if;
    end if;
    insert into public.notification_queue(driver_id,family_id,month,kind,ref,due_at)
      values(new.driver_id,f.family_id,date_trunc('month',new.week_start)::date,'weekly_change',new.week_start::text,
        new.created_at + make_interval(hours => f.delay_days * 24));
  end loop;
  return new;
end $$;
revoke execute on function public.enqueue_weekly_notifications() from public, anon, authenticated;
create trigger enqueue_weekly after insert or update on public.weekly_changes for each row execute function public.enqueue_weekly_notifications();

-- A shared immutable calendar function makes the exact KST Monday boundary testable.
create function public.kst_week_start(p_at timestamptz) returns date
language sql immutable set search_path = '' as $$
  select date_trunc('week', p_at at time zone 'Asia/Seoul')::date
$$;
revoke execute on function public.kst_week_start(timestamptz) from public, anon, authenticated;
grant execute on function public.kst_week_start(timestamptz) to service_role;

create function public.family_weekly_for(p_family uuid, p_driver uuid)
returns table(driver_id uuid, driver_name text, week_start date, flagged boolean, domains jsonb, top jsonb)
language sql stable security definer set search_path = '' as $$
  select w.driver_id, p.display_name, w.week_start, w.flagged, w.domains, w.top
    from public.family_links f join public.share_options o using(driver_id,family_id)
      join public.weekly_changes w on w.driver_id = f.driver_id join public.profiles p on p.id = f.driver_id
    where f.family_id = p_family and (p_driver is null or f.driver_id = p_driver)
      and f.revoked_at is null and p.sharing_paused_at is null and o.weekly_change
      and w.created_at <= now() - make_interval(hours => f.delay_days * 24)
      and w.week_start between public.kst_week_start(now()) - 77
        and public.kst_week_start(now())
    order by w.week_start desc, w.driver_id
$$;
revoke execute on function public.family_weekly_for(uuid,uuid) from public, anon, authenticated;

create function public.family_habits_for(p_family uuid, p_driver uuid)
returns table(driver_id uuid, driver_name text, week_start date, trips integer, minutes integer,
  signal_kept integer, signal_total integer, hard_brakes integer, hard_accels integer, calm_trips integer,
  long_stretches integer, night_trips integer, promises_kept integer, promises_total integer, story text)
language sql stable security definer set search_path = '' as $$
  select w.driver_id, p.display_name, w.week_start, w.trips, w.minutes, w.signal_kept, w.signal_total,
    w.hard_brakes, w.hard_accels, w.calm_trips, w.long_stretches, w.night_trips, w.promises_kept, w.promises_total, w.story
    from public.family_links f join public.share_options o using(driver_id,family_id)
      join public.weekly_habits w on w.driver_id = f.driver_id join public.profiles p on p.id = f.driver_id
    where f.family_id = p_family and (p_driver is null or f.driver_id = p_driver)
      and f.revoked_at is null and p.sharing_paused_at is null and o.habits
      and w.created_at <= now() - make_interval(hours => f.delay_days * 24)
      and w.week_start between public.kst_week_start(now()) - 77
        and public.kst_week_start(now())
    order by w.week_start desc, w.driver_id
$$;
revoke execute on function public.family_habits_for(uuid,uuid) from public, anon, authenticated;

create function public.get_family_weekly()
returns table(driver_id uuid, driver_name text, week_start date, flagged boolean, domains jsonb, top jsonb)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select * from public.family_weekly_for(auth.uid(), null);
end $$;
revoke execute on function public.get_family_weekly() from public, anon, authenticated;
grant execute on function public.get_family_weekly() to authenticated;

create function public.get_family_habits()
returns table(driver_id uuid, driver_name text, week_start date, trips integer, minutes integer,
  signal_kept integer, signal_total integer, hard_brakes integer, hard_accels integer, calm_trips integer,
  long_stretches integer, night_trips integer, promises_kept integer, promises_total integer, story text)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select * from public.family_habits_for(auth.uid(), null);
end $$;
revoke execute on function public.get_family_habits() from public, anon, authenticated;
grant execute on function public.get_family_habits() to authenticated;

create or replace function public.preview_family_view(p_family_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner(); choices jsonb;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  if not exists(select 1 from public.family_links where driver_id = owner and family_id = p_family_id and revoked_at is null) then raise exception 'share_not_found'; end if;
  select jsonb_build_object('drive_done', o.drive_done, 'weekly_change', coalesce(o.weekly_change,false), 'habits', coalesce(o.habits,false))
    into choices from public.family_links f left join public.share_options o using(driver_id,family_id)
    where f.driver_id = owner and f.family_id = p_family_id;
  -- 'habits' is the data array; the boolean stays in options.habits (no duplicate JSON key).
  return jsonb_build_object('options', choices, 'drive_done', choices->'drive_done', 'weekly_change', choices->'weekly_change',
    'paused', (select sharing_paused_at is not null from public.profiles where id = owner),
    'feed', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_feed_for(p_family_id, owner) r), '[]'::jsonb),
    'patterns', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_patterns_for(p_family_id, owner) r), '[]'::jsonb),
    'drive_events', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_drive_events_for(p_family_id, owner) r), '[]'::jsonb),
    'weekly', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_weekly_for(p_family_id, owner) r), '[]'::jsonb),
    'habits', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_habits_for(p_family_id, owner) r), '[]'::jsonb));
end $$;

create or replace function public.update_share(p_family_id uuid, p_scope text, p_delay_days integer, p_share_pattern boolean) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if p_scope is null or p_delay_days is null or p_share_pattern is null
    or p_scope not in ('stage','trend','detail') or p_delay_days not in (0,3,7,14) then raise exception 'scope_and_timing_required'; end if;
  update public.family_links set scope = p_scope, delay_days = p_delay_days, share_pattern = p_share_pattern, updated_at = clock_timestamp()
    where driver_id = auth.uid() and family_id = p_family_id and revoked_at is null;
  if not found then raise exception 'share_not_found'; end if;
  update public.notification_queue q set due_at = m.created_at + make_interval(hours => p_delay_days * 24)
    from public.monthly_summaries m where q.driver_id = auth.uid() and q.family_id = p_family_id and q.kind = 'stage'
      and m.driver_id = q.driver_id and m.month = q.month and q.sent_at is null and q.cancelled_at is null;
  update public.notification_queue q set due_at = w.created_at + make_interval(hours => p_delay_days * 24)
    from public.weekly_changes w where q.driver_id = auth.uid() and q.family_id = p_family_id and q.kind = 'weekly_change'
      and w.driver_id = q.driver_id and w.week_start::text = q.ref and q.sent_at is null and q.cancelled_at is null;
end $$;
revoke execute on function public.preview_family_view(uuid), public.update_share(uuid,text,integer,boolean) from public, anon, authenticated;
grant execute on function public.preview_family_view(uuid), public.update_share(uuid,text,integer,boolean) to authenticated;
commit;

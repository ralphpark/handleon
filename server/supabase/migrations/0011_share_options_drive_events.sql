begin;

-- family_links already has primary key (driver_id, family_id).
create table public.share_options (
  driver_id uuid not null, family_id uuid not null,
  drive_done text check (drive_done in ('each','evening')),
  weekly_change boolean not null default false,
  habits boolean not null default false,
  updated_at timestamptz not null default clock_timestamp(),
  primary key (driver_id, family_id),
  foreign key (driver_id, family_id) references public.family_links(driver_id, family_id) on delete cascade
);
create table public.drive_events (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  outing_key text not null check (outing_key ~ '^[0-9]{10,16}$'),
  ended_at timestamptz not null check (isfinite(ended_at)),
  minutes integer not null check (minutes between 1 and 1440),
  rested boolean not null,
  retracted_at timestamptz,
  created_at timestamptz not null default clock_timestamp(),
  primary key (driver_id, outing_key)
);
create index drive_events_recent_idx on public.drive_events(driver_id, ended_at desc);
alter table public.share_options enable row level security;
alter table public.drive_events enable row level security;
revoke all on public.share_options, public.drive_events from public, anon, authenticated;
grant all on public.share_options, public.drive_events to service_role;

alter table public.notification_queue
  add column kind text not null default 'stage' check (kind in ('stage','drive_done','drive_evening','weekly_change')),
  add column ref text;
-- Preserve the original stage FK/cascade without requiring a monthly summary for drive/weekly news.
alter table public.notification_queue drop constraint notification_queue_driver_id_month_fkey;
alter table public.notification_queue
  add column stage_month date generated always as (case when kind = 'stage' then month end) stored,
  add constraint notification_queue_stage_month_fkey foreign key (driver_id, stage_month)
    references public.monthly_summaries(driver_id, month) on delete cascade;
create unique index notification_drive_pending_idx on public.notification_queue(driver_id, family_id)
  where kind = 'drive_done' and sent_at is null and cancelled_at is null;
create unique index notification_evening_once_idx on public.notification_queue(driver_id, family_id, ref)
  where kind = 'drive_evening' and cancelled_at is null;

create function public.drive_evening_date(p_end timestamptz) returns date
language sql immutable set search_path = '' as $$
  select (p_end at time zone 'Asia/Seoul' - interval '20 hours')::date + 1
$$;
revoke execute on function public.drive_evening_date(timestamptz) from public, anon, authenticated;
grant execute on function public.drive_evening_date(timestamptz) to service_role;

create function public.set_share_options(p_family_id uuid, p_drive_done text, p_weekly_change boolean, p_habits boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner(); previous public.share_options;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  if p_family_id is null or p_weekly_change is null or p_habits is null then raise exception 'choice_required'; end if;
  if p_drive_done is not null and p_drive_done not in ('each','evening') then raise exception 'invalid_choice'; end if;
  perform 1 from public.family_links where driver_id = owner and family_id = p_family_id and revoked_at is null for update;
  if not found then raise exception 'share_not_found'; end if;
  select * into previous from public.share_options where driver_id = owner and family_id = p_family_id;
  insert into public.share_options(driver_id, family_id, drive_done, weekly_change, habits)
    values(owner, p_family_id, p_drive_done, p_weekly_change, p_habits)
    on conflict (driver_id, family_id) do update set drive_done = excluded.drive_done,
      weekly_change = excluded.weekly_change, habits = excluded.habits, updated_at = clock_timestamp();
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = owner and family_id = p_family_id and sent_at is null and cancelled_at is null
      and ((kind in ('drive_done','drive_evening') and previous.drive_done is distinct from p_drive_done)
        or (kind = 'weekly_change' and not p_weekly_change));
end $$;
revoke execute on function public.set_share_options(uuid,text,boolean,boolean) from public, anon, authenticated;
grant execute on function public.set_share_options(uuid,text,boolean,boolean) to authenticated;

-- New consent starts with all new options off, including reacceptance of an existing live link.
-- Keep the existing invitation -> link -> queue locking order and RPC signature.
create or replace function public.accept_invite(p_code text) returns void
language plpgsql security definer set search_path = '' as $$
declare invitation public.invites;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  select * into invitation from public.invites where code = p_code for update;
  if not found or invitation.accepted_by is not null or invitation.expires_at <= clock_timestamp() then raise exception 'invalid_or_expired_code'; end if;
  insert into public.family_links(driver_id,family_id,scope,delay_days,share_pattern)
    values(invitation.driver_id,auth.uid(),invitation.scope,invitation.delay_days,invitation.share_pattern)
    on conflict (driver_id,family_id) do update set scope = excluded.scope, delay_days = excluded.delay_days,
      share_pattern = excluded.share_pattern, revoked_at = null, updated_at = clock_timestamp();
  delete from public.share_options where driver_id = invitation.driver_id and family_id = auth.uid();
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = invitation.driver_id and family_id = auth.uid() and sent_at is null and cancelled_at is null;
  update public.invites set accepted_by = auth.uid() where code = p_code;
end $$;

create function public.report_drive_end(p_outing_key text, p_ended_at timestamptz, p_minutes integer, p_rested boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  if p_ended_at is null or not isfinite(p_ended_at) or p_ended_at > clock_timestamp() + interval '5 minutes' then raise exception 'invalid_time'; end if;
  insert into public.drive_events as d(driver_id, outing_key, ended_at, minutes, rested)
    values(owner, p_outing_key, p_ended_at, p_minutes, p_rested)
    on conflict (driver_id, outing_key) do update set ended_at = excluded.ended_at,
      minutes = excluded.minutes, rested = excluded.rested
    -- Retraction is permanent; out-of-order uploads and exact retries cannot rewind or revive it.
    where d.retracted_at is null and excluded.ended_at >= d.ended_at
      and (d.ended_at, d.minutes, d.rested) is distinct from (excluded.ended_at, excluded.minutes, excluded.rested);
end $$;
revoke execute on function public.report_drive_end(text,timestamptz,integer,boolean) from public, anon, authenticated;
grant execute on function public.report_drive_end(text,timestamptz,integer,boolean) to authenticated;

create function public.enqueue_drive_notifications() returns trigger
language plpgsql security definer set search_path = '' as $$
declare f record; paused boolean; mode text; evening date;
begin
  -- Event writes serialize by PK; all consent writers take links before touching queues.
  for f in select * from public.family_links where driver_id = new.driver_id order by family_id for update loop
    select p.sharing_paused_at is not null into paused from public.profiles p where p.id = new.driver_id;
    select o.drive_done into mode from public.share_options o where o.driver_id = new.driver_id and o.family_id = f.family_id;
    -- Retraction or movement into a later bucket removes empty evening notifications as well.
    update public.notification_queue q set cancelled_at = clock_timestamp()
      where q.driver_id = new.driver_id and q.family_id = f.family_id and q.sent_at is null and q.cancelled_at is null
        and ((q.kind = 'drive_done' and q.ref = new.outing_key and new.retracted_at is not null)
          or (q.kind = 'drive_evening' and not exists(
            select 1 from public.drive_events d where d.driver_id = new.driver_id and d.retracted_at is null
              and public.drive_evening_date(d.ended_at)::text = q.ref)));
    if f.revoked_at is not null or paused or mode is null or new.retracted_at is not null then continue; end if;
    if mode = 'each' then
      -- A late older outing must not cancel or overtake the newest outing (including an already sent one).
      if new.ended_at < clock_timestamp() - interval '6 hours' or exists(
        select 1 from public.drive_events d where d.driver_id = new.driver_id
          and (d.ended_at, d.outing_key) > (new.ended_at, new.outing_key)) then continue; end if;
      if exists(select 1 from public.notification_queue q where q.driver_id = new.driver_id and q.family_id = f.family_id
        and q.kind = 'drive_done' and q.ref = new.outing_key and q.sent_at is not null) then continue; end if;
      update public.notification_queue set cancelled_at = clock_timestamp()
        where driver_id = new.driver_id and family_id = f.family_id and kind = 'drive_done'
          and sent_at is null and cancelled_at is null;
      insert into public.notification_queue(driver_id,family_id,month,kind,ref,due_at)
        values(new.driver_id,f.family_id,date_trunc('month', new.ended_at at time zone 'Asia/Seoul')::date,
          'drive_done',new.outing_key,new.ended_at + interval '30 minutes');
    else
      evening := public.drive_evening_date(new.ended_at);
      -- Do not deliver an old offline digest behind newer news. The age is measured
      -- from its scheduled evening, not from the prior night's intentional next-day trip.
      if (evening + time '20:00') at time zone 'Asia/Seoul' < clock_timestamp() - interval '6 hours' then continue; end if;
      insert into public.notification_queue(driver_id,family_id,month,kind,ref,due_at)
        values(new.driver_id,f.family_id,date_trunc('month', new.ended_at at time zone 'Asia/Seoul')::date,
          'drive_evening',evening::text,(evening + time '20:00') at time zone 'Asia/Seoul')
        on conflict (driver_id,family_id,ref) where kind = 'drive_evening' and cancelled_at is null do nothing;
    end if;
  end loop;
  return new;
end $$;
revoke execute on function public.enqueue_drive_notifications() from public, anon, authenticated;
create trigger enqueue_drive after insert or update on public.drive_events for each row execute function public.enqueue_drive_notifications();

create function public.retract_drive(p_outing_key text) returns void
language plpgsql security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  update public.drive_events set retracted_at = clock_timestamp()
    where driver_id = owner and outing_key = p_outing_key and retracted_at is null;
end $$;
revoke execute on function public.retract_drive(text) from public, anon, authenticated;
grant execute on function public.retract_drive(text) to authenticated;

create function public.family_drive_events_for(p_family uuid, p_driver uuid)
returns table(driver_id uuid, driver_name text, outing_key text, ended_at timestamptz, minutes integer, rested boolean)
language sql stable security definer set search_path = '' as $$
  select d.driver_id, p.display_name, d.outing_key, d.ended_at, d.minutes, d.rested
    from public.family_links f join public.share_options o using(driver_id,family_id)
      join public.drive_events d on d.driver_id = f.driver_id join public.profiles p on p.id = f.driver_id
    where f.family_id = p_family and (p_driver is null or f.driver_id = p_driver)
      and f.revoked_at is null and p.sharing_paused_at is null and o.drive_done is not null
      and d.retracted_at is null and d.ended_at >= now() - interval '14 days' and d.ended_at <= now()
    order by d.ended_at desc, d.driver_id, d.outing_key
$$;
revoke execute on function public.family_drive_events_for(uuid,uuid) from public, anon, authenticated;

create function public.get_family_drive_events()
returns table(driver_id uuid, driver_name text, outing_key text, ended_at timestamptz, minutes integer, rested boolean)
language plpgsql stable security definer set search_path = '' as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select * from public.family_drive_events_for(auth.uid(), null);
end $$;
revoke execute on function public.get_family_drive_events() from public, anon, authenticated;
grant execute on function public.get_family_drive_events() to authenticated;

create or replace function public.get_sharing_state() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner();
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  return jsonb_build_object(
    'paused', (select sharing_paused_at is not null from public.profiles where id = owner),
    'recipients', coalesce((select jsonb_agg(jsonb_build_object('family_name', p.display_name, 'scope', f.scope,
        'delay_days', f.delay_days, 'share_pattern', f.share_pattern,
        'drive_done', o.drive_done, 'weekly_change', coalesce(o.weekly_change,false), 'habits', coalesce(o.habits,false))
        order by f.created_at, f.family_id)
      from public.family_links f join public.profiles p on p.id = f.family_id
        left join public.share_options o using(driver_id,family_id)
      where f.driver_id = owner and f.revoked_at is null), '[]'::jsonb));
end $$;

create or replace function public.preview_family_view(p_family_id uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner(); choices jsonb;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  if not exists(select 1 from public.family_links where driver_id = owner and family_id = p_family_id and revoked_at is null) then raise exception 'share_not_found'; end if;
  select jsonb_build_object('drive_done', o.drive_done, 'weekly_change', coalesce(o.weekly_change,false), 'habits', coalesce(o.habits,false))
    into choices from public.family_links f left join public.share_options o using(driver_id,family_id)
    where f.driver_id = owner and f.family_id = p_family_id;
  return choices || jsonb_build_object('options', choices,
    'paused', (select sharing_paused_at is not null from public.profiles where id = owner),
    'feed', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_feed_for(p_family_id, owner) r), '[]'::jsonb),
    'patterns', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_patterns_for(p_family_id, owner) r), '[]'::jsonb),
    'drive_events', coalesce((select jsonb_agg(to_jsonb(r)) from public.family_drive_events_for(p_family_id, owner) r), '[]'::jsonb));
end $$;

-- Existing monthly writes must never cancel or reschedule unrelated kinds in the same month.
create or replace function public.enqueue_summary_notifications() returns trigger
language plpgsql security definer set search_path = '' as $$
declare f record; changed boolean; paused boolean;
begin
  changed := tg_op = 'INSERT';
  if tg_op = 'UPDATE' then changed := new.stage is distinct from old.stage; end if;
  for f in select * from public.family_links where driver_id = new.driver_id order by family_id for update loop
    select p.sharing_paused_at is not null into paused from public.profiles p where p.id = new.driver_id;
    if f.revoked_at is not null then continue; end if;
    if paused or new.stage not in ('watch','talk') then
      update public.notification_queue set cancelled_at = clock_timestamp()
        where driver_id = new.driver_id and family_id = f.family_id and month = new.month and kind = 'stage'
          and sent_at is null and cancelled_at is null;
    else
      update public.notification_queue set due_at = new.created_at + make_interval(hours => f.delay_days * 24)
        where driver_id = new.driver_id and family_id = f.family_id and month = new.month and kind = 'stage'
          and sent_at is null and cancelled_at is null;
      if changed then
        insert into public.notification_queue(driver_id,family_id,month,due_at)
          values(new.driver_id,f.family_id,new.month,new.created_at + make_interval(hours => f.delay_days * 24));
      end if;
    end if;
  end loop;
  return new;
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
end $$;

-- CREATE OR REPLACE retains ACLs; explicitly pin the public entry points as well.
revoke execute on function public.accept_invite(text), public.get_sharing_state(), public.preview_family_view(uuid),
  public.update_share(uuid,text,integer,boolean) from public, anon, authenticated;
grant execute on function public.accept_invite(text), public.get_sharing_state(), public.preview_family_view(uuid),
  public.update_share(uuid,text,integer,boolean) to authenticated;
commit;

begin;

create table public.coach_requests (
  family_id uuid not null references public.profiles(id) on delete cascade,
  driver_id uuid not null references public.profiles(id) on delete cascade,
  day date not null check (isfinite(day)),
  count integer not null check (count between 1 and 5),
  input_hash text not null check (input_hash ~ '^[0-9a-f]{64}$'),
  result jsonb check (result is null or jsonb_typeof(result) = 'object'),
  primary key (family_id, driver_id, day)
);
alter table public.coach_requests enable row level security;
revoke all on public.coach_requests from public, anon, authenticated;
grant all on public.coach_requests to service_role;

-- Reuse the existing family projections: scope, delay, pause and revoke stay there.
-- Never carry driver_name, IDs, letter, monthly top.text, or free-form story.
create function public.coach_context_for(p_family uuid, p_driver uuid) returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare context jsonb := '{}'::jsonb; part jsonb;
begin
  if not exists (
    select 1 from public.family_links f
      join public.profiles p on p.id = f.driver_id
      join public.profiles recipient on recipient.id = f.family_id
    where f.family_id = p_family and f.driver_id = p_driver and f.revoked_at is null
      and p.sharing_paused_at is null and recipient.role = 'family'
  ) then return null; end if;

  select jsonb_strip_nulls(jsonb_build_object(
    'month', r.month, 'stage', r.stage, 'domains', r.domains,
    'top', case when r.top is null then null else coalesce((
      select jsonb_agg(jsonb_build_object('feature', t.value->>'feature') order by t.ord)
      from jsonb_array_elements(r.top) with ordinality t(value,ord)
      where t.value->>'feature' in ('M1','M2','M3','M4','M5','M6','M7','W2','W3','W4','O1','O2','O7','O8','O9')
    ), '[]'::jsonb) end)) into part
    from public.family_feed_for(p_family, p_driver) r order by r.month desc limit 1;
  if part is not null then context := context || jsonb_build_object('feed', part); end if;

  select jsonb_agg(jsonb_build_object(
    'month', r.month, 'partial', r.partial, 'trips', r.trips, 'drive_minutes', r.drive_minutes,
    'drive_days', r.drive_days, 'night_ratio', r.night_ratio, 'long_ratio', r.long_ratio,
    'radius_ratio', r.radius_ratio, 'places_ratio', r.places_ratio,
    'usual_trips', r.usual_trips, 'usual_minutes', r.usual_minutes,
    'usual_night_ratio', r.usual_night_ratio, 'usual_long_ratio', r.usual_long_ratio
  ) order by r.month desc) into part from public.family_patterns_for(p_family, p_driver) r;
  if part is not null then context := context || jsonb_build_object('patterns', part); end if;

  select jsonb_agg(jsonb_build_object('week_start', r.week_start, 'flagged', r.flagged,
    'domains', r.domains, 'top', r.top) order by r.week_start desc) into part
    from public.family_weekly_for(p_family, p_driver) r;
  if part is not null then context := context || jsonb_build_object('weekly', part); end if;

  select jsonb_agg(jsonb_build_object(
    'week_start', r.week_start, 'trips', r.trips, 'minutes', r.minutes,
    'signal_kept', r.signal_kept, 'signal_total', r.signal_total,
    'hard_brakes', r.hard_brakes, 'hard_accels', r.hard_accels, 'calm_trips', r.calm_trips,
    'long_stretches', r.long_stretches, 'night_trips', r.night_trips,
    'promises_kept', r.promises_kept, 'promises_total', r.promises_total
  ) order by r.week_start desc) into part from public.family_habits_for(p_family, p_driver) r;
  if part is not null then context := context || jsonb_build_object('habits', part); end if;

  -- Only the opt-in determines whether a zero is meaningful. Visible events are
  -- still exclusively selected by family_drive_events_for, including retraction.
  if exists(select 1 from public.share_options o where o.family_id = p_family
    and o.driver_id = p_driver and o.drive_done is not null) then
    context := context || jsonb_build_object('drive_count_7d', (
      select count(*) from public.family_drive_events_for(p_family, p_driver) r
      where r.ended_at >= now() - interval '7 days'
    ));
  end if;
  return context;
end $$;

-- Serialize all drivers for this family. Reserve before calling the model, so
-- concurrent requests cannot exceed the daily family-wide budget.
create function public.begin_coach_request(p_family uuid, p_driver uuid) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare context jsonb; input_hash text; today date; prior public.coach_requests;
begin
  perform 1 from public.profiles p where p.id = p_family and p.role = 'family' for update;
  if not found then return jsonb_build_object('status', 'share_not_found'); end if;
  today := (clock_timestamp() at time zone 'Asia/Seoul')::date;
  context := public.coach_context_for(p_family, p_driver);
  if context is null then return jsonb_build_object('status', 'share_not_found'); end if;
  input_hash := encode(extensions.digest(convert_to(context::text, 'UTF8'), 'sha256'), 'hex');
  select r.* into prior from public.coach_requests r
    where r.family_id = p_family and r.driver_id = p_driver and r.day = today;
  if found and prior.input_hash = input_hash then
    if prior.result is not null then return jsonb_build_object('status', 'cached', 'result', prior.result); end if;
    return jsonb_build_object('status', 'pending');
  end if;
  if (select coalesce(sum(r.count), 0) from public.coach_requests r
    where r.family_id = p_family and r.day = today) >= 5 then
    return jsonb_build_object('status', 'daily_limit');
  end if;
  insert into public.coach_requests as r(family_id, driver_id, day, count, input_hash, result)
    values(p_family, p_driver, today, 1, input_hash, null)
    on conflict (family_id, driver_id, day) do update
      set count = r.count + 1, input_hash = excluded.input_hash, result = null;
  return jsonb_build_object('status', 'generate', 'context', context, 'day', today, 'input_hash', input_hash);
end $$;

-- Never publish an old generation after consent, data, or the active reservation changed.
create function public.finish_coach_request(p_family uuid, p_driver uuid, p_day date, p_input_hash text, p_result jsonb) returns text
language plpgsql security definer set search_path = '' as $$
declare context jsonb;
begin
  perform 1 from public.profiles p where p.id = p_family and p.role = 'family' for update;
  if not found then return 'share_not_found'; end if;
  context := public.coach_context_for(p_family, p_driver);
  if context is null then return 'share_not_found'; end if;
  if p_day is distinct from (clock_timestamp() at time zone 'Asia/Seoul')::date
    or p_input_hash is distinct from encode(extensions.digest(convert_to(context::text, 'UTF8'), 'sha256'), 'hex') then
    return 'context_changed';
  end if;
  if p_result is null or jsonb_typeof(p_result) <> 'object'
    or p_result->>'source' is null or p_result->>'source' not in ('ai','fallback')
    or jsonb_typeof(p_result->'advice') is distinct from 'object' then
    raise exception 'invalid_result';
  end if;
  update public.coach_requests r set result = p_result
    where r.family_id = p_family and r.driver_id = p_driver and r.day = p_day
      and r.input_hash = p_input_hash and r.result is null;
  if not found then return 'context_changed'; end if;
  return 'ok';
end $$;

revoke execute on function public.coach_context_for(uuid,uuid), public.begin_coach_request(uuid,uuid),
  public.finish_coach_request(uuid,uuid,date,text,jsonb) from public, anon, authenticated;
grant execute on function public.coach_context_for(uuid,uuid), public.begin_coach_request(uuid,uuid),
  public.finish_coach_request(uuid,uuid,date,text,jsonb) to service_role;

commit;

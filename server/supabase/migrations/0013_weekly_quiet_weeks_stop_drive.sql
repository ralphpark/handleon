begin;
-- 평소와 같은 주(flagged = false)도 올릴 수 있게 영역 0개를 허용한다. 표시된 주에는 영역이 하나 이상 있어야 한다.
-- 표시됐던 주가 다시 평소로 돌아오면 같은 행을 flagged = false 로 덮어써 예정된 알림을 거둔다.
create or replace function public.valid_weekly_json(p_domains jsonb, p_top jsonb) returns boolean
language plpgsql immutable set search_path = '' as $$
declare item jsonb;
begin
  if p_domains is null or p_top is null or jsonb_typeof(p_domains) <> 'array' or jsonb_typeof(p_top) <> 'array' then return false; end if;
  if jsonb_array_length(p_domains) > 3 or jsonb_array_length(p_top) > 3 then return false; end if;
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
alter table public.weekly_changes add constraint weekly_flag_needs_domain
  check (not flagged or jsonb_array_length(domains) >= 1);

-- 차는 가족 id 를 모른다. 차 화면의 "운전 마침 소식 멈추기"는 모든 수신자를 한 번에 끈다.
-- set_share_options 와 같은 순서(링크 → 선택 → 큐)로 잠근다.
create function public.stop_drive_notices() returns integer
language plpgsql security definer set search_path = '' as $$
declare owner uuid := public.sharing_owner(); n integer;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  perform 1 from public.family_links where driver_id = owner order by family_id for update;
  update public.share_options set drive_done = null, updated_at = clock_timestamp()
    where driver_id = owner and drive_done is not null;
  get diagnostics n = row_count;
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = owner and kind in ('drive_done','drive_evening') and sent_at is null and cancelled_at is null;
  return n;
end $$;
revoke execute on function public.stop_drive_notices() from public, anon, authenticated;
grant execute on function public.stop_drive_notices() to authenticated;
commit;

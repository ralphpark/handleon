begin;
-- 모두 끊기는 아직 쓰지 않은 초대 코드도 끝낸다(차 화면: "다시 나누려면 휴대폰에서 새로 초대해야 해요").
-- accept_invite 와 같은 순서(초대 → 링크 → 큐)로 잠근다.
create or replace function public.revoke_all_shares() returns integer
language plpgsql security definer set search_path = public as $$
declare owner uuid := public.sharing_owner(); n integer;
begin
  if owner is null then raise exception 'driver_or_car_required'; end if;
  perform 1 from public.invites where driver_id = owner and accepted_by is null order by code for update;
  update public.invites set expires_at = clock_timestamp()
    where driver_id = owner and accepted_by is null and expires_at > clock_timestamp();
  perform 1 from public.family_links where driver_id = owner order by family_id for update;
  update public.family_links set revoked_at = clock_timestamp(), updated_at = clock_timestamp()
    where driver_id = owner and revoked_at is null;
  get diagnostics n = row_count;
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = owner and sent_at is null and cancelled_at is null;
  return n;
end $$;
commit;

begin;
-- 휴대폰 화면용 읽기 RPC: 운전자의 공유 목록, 초대 미리보기, 가족 피드의 공유 범위.
-- family_links·invites·남의 profiles 는 테이블 권한이 없으므로 역할을 검사하는 함수로만 읽는다.

create function public.get_my_shares()
returns table(family_id uuid, family_name text, scope text, delay_days integer, created_at timestamptz)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  return query select f.family_id, p.display_name, f.scope, f.delay_days, f.created_at
    from public.family_links f join public.profiles p on p.id = f.family_id
    where f.driver_id = auth.uid() and f.revoked_at is null
    order by f.created_at, f.family_id;
end $$;
revoke execute on function public.get_my_shares() from public, anon;
grant execute on function public.get_my_shares() to authenticated;

-- 수락 전에 "누가, 무엇을, 언제" 보여 주는지 확인한다. 쓰지 않은 유효한 코드만.
create function public.preview_invite(p_code text)
returns table(driver_name text, scope text, delay_days integer)
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  return query select p.display_name, i.scope, i.delay_days
    from public.invites i join public.profiles p on p.id = i.driver_id
    where i.code = p_code and i.accepted_by is null and i.expires_at > clock_timestamp();
  if not found then raise exception 'invalid_or_expired_code'; end if;
end $$;
revoke execute on function public.preview_invite(text) from public, anon;
grant execute on function public.preview_invite(text) to authenticated;

-- 가족 카드에 "○○ 님이 정한 범위"를 보이려고 scope 를 함께 돌려준다(반환형이 바뀌어 다시 만든다).
drop function public.get_family_feed();
create function public.get_family_feed()
returns table(driver_id uuid, driver_name text, month date, stage text, domains jsonb, top jsonb, letter text, scope text)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select m.driver_id, p.display_name, m.month, m.stage,
    case when f.scope in ('trend','detail') then m.domains else null end,
    case when f.scope = 'detail' then m.top else null end,
    case when f.scope = 'detail' then m.letter else null end,
    f.scope
  from public.family_links f join public.monthly_summaries m on m.driver_id = f.driver_id
    join public.profiles p on p.id = f.driver_id
  where f.family_id = auth.uid() and f.revoked_at is null
    and m.created_at <= now() - make_interval(hours => f.delay_days * 24)
  order by m.month desc, m.driver_id;
end $$;
revoke execute on function public.get_family_feed() from public, anon;
grant execute on function public.get_family_feed() to authenticated;
commit;

begin;
create function public.create_pairing_code() returns text
language plpgsql security definer set search_path = public as $$
declare result text; alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; bytes bytea; i integer;
begin
  if auth.uid() is null or not coalesce((auth.jwt() ->> 'is_anonymous')::boolean, false) then raise exception 'car_required'; end if;
  -- Serialize generation and claiming for this car, including re-pairing to another driver.
  perform pg_advisory_xact_lock(hashtextextended(auth.uid()::text, 0));
  delete from public.pairing_codes where car_id = auth.uid() and claimed_by is null;
  loop
    result := ''; bytes := extensions.gen_random_bytes(6);
    for i in 0..5 loop result := result || substr(alphabet, (get_byte(bytes,i) % 32) + 1, 1); end loop;
    begin
      insert into public.pairing_codes(code, car_id, expires_at) values(result, auth.uid(), clock_timestamp() + interval '10 minutes');
      return result;
    exception when unique_violation then null;
    end;
  end loop;
end $$;
revoke execute on function public.create_pairing_code() from public, anon;
grant execute on function public.create_pairing_code() to authenticated;

create function public.claim_pairing(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare pairing public.pairing_codes; target_car uuid;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  select car_id into target_car from public.pairing_codes where code = p_code;
  if target_car is null then raise exception 'invalid_or_expired_code'; end if;
  perform pg_advisory_xact_lock(hashtextextended(target_car::text, 0));
  select * into pairing from public.pairing_codes where code = p_code for update;
  if not found or pairing.claimed_by is not null or pairing.expires_at <= clock_timestamp() then raise exception 'invalid_or_expired_code'; end if;
  insert into public.car_links(car_id, driver_id) values(pairing.car_id, auth.uid())
  on conflict (car_id) do update set driver_id = excluded.driver_id, linked_at = clock_timestamp();
  update public.pairing_codes set claimed_by = auth.uid() where code = p_code;
end $$;
revoke execute on function public.claim_pairing(text) from public, anon;
grant execute on function public.claim_pairing(text) to authenticated;

create function public.create_invite(p_scope text, p_delay_days integer) returns text
language plpgsql security definer set search_path = public as $$
declare result text; alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; bytes bytea; i integer;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if p_scope is null or p_delay_days is null or p_scope not in ('stage','trend','detail') or p_delay_days not in (0,3,7,14) then raise exception 'scope_and_timing_required'; end if;
  loop
    result := ''; bytes := extensions.gen_random_bytes(8);
    for i in 0..7 loop result := result || substr(alphabet, (get_byte(bytes,i) % 32) + 1, 1); end loop;
    begin
      insert into public.invites(code,driver_id,scope,delay_days,expires_at)
      values(result,auth.uid(),p_scope,p_delay_days,clock_timestamp() + interval '7 days');
      return result;
    exception when unique_violation then null;
    end;
  end loop;
end $$;
revoke execute on function public.create_invite(text,integer) from public, anon;
grant execute on function public.create_invite(text,integer) to authenticated;

create function public.accept_invite(p_code text) returns void
language plpgsql security definer set search_path = public as $$
declare invitation public.invites;
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'family') then raise exception 'family_required'; end if;
  select * into invitation from public.invites where code = p_code for update;
  if not found or invitation.accepted_by is not null or invitation.expires_at <= clock_timestamp() then raise exception 'invalid_or_expired_code'; end if;
  insert into public.family_links(driver_id,family_id,scope,delay_days)
  values(invitation.driver_id,auth.uid(),invitation.scope,invitation.delay_days)
  on conflict (driver_id,family_id) do update set scope = excluded.scope, delay_days = excluded.delay_days,
    revoked_at = null, updated_at = clock_timestamp();
  -- Superseded queued consent is never revived when a new invitation is accepted.
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = invitation.driver_id and family_id = auth.uid() and sent_at is null and cancelled_at is null;
  update public.invites set accepted_by = auth.uid() where code = p_code;
end $$;
revoke execute on function public.accept_invite(text) from public, anon;
grant execute on function public.accept_invite(text) to authenticated;

create function public.update_share(p_family_id uuid, p_scope text, p_delay_days integer) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  if p_scope is null or p_delay_days is null or p_scope not in ('stage','trend','detail') or p_delay_days not in (0,3,7,14) then raise exception 'scope_and_timing_required'; end if;
  update public.family_links set scope = p_scope, delay_days = p_delay_days, updated_at = clock_timestamp()
    where driver_id = auth.uid() and family_id = p_family_id and revoked_at is null;
  if not found then raise exception 'share_not_found'; end if;
  update public.notification_queue q set due_at = m.created_at + make_interval(hours => p_delay_days * 24)
    from public.monthly_summaries m where q.driver_id = auth.uid() and q.family_id = p_family_id
      and m.driver_id = q.driver_id and m.month = q.month and q.sent_at is null and q.cancelled_at is null;
end $$;
revoke execute on function public.update_share(uuid,text,integer) from public, anon;
grant execute on function public.update_share(uuid,text,integer) to authenticated;

create function public.revoke_share(p_family_id uuid) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid() and role = 'driver') then raise exception 'driver_required'; end if;
  update public.family_links set revoked_at = clock_timestamp(), updated_at = clock_timestamp()
    where driver_id = auth.uid() and family_id = p_family_id;
  if not found then raise exception 'share_not_found'; end if;
  update public.notification_queue set cancelled_at = clock_timestamp()
    where driver_id = auth.uid() and family_id = p_family_id and sent_at is null and cancelled_at is null;
end $$;
revoke execute on function public.revoke_share(uuid) from public, anon;
grant execute on function public.revoke_share(uuid) to authenticated;

create function public.get_family_feed()
returns table(driver_id uuid, driver_name text, month date, stage text, domains jsonb, top jsonb, letter text)
language plpgsql stable security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'family') then raise exception 'family_required'; end if;
  return query select m.driver_id, p.display_name, m.month, m.stage,
    case when f.scope in ('trend','detail') then m.domains else null end,
    case when f.scope = 'detail' then m.top else null end,
    case when f.scope = 'detail' then m.letter else null end
  from public.family_links f join public.monthly_summaries m on m.driver_id = f.driver_id
    join public.profiles p on p.id = f.driver_id
  where f.family_id = auth.uid() and f.revoked_at is null
    and m.created_at <= now() - make_interval(hours => f.delay_days * 24)
  order by m.month desc, m.driver_id;
end $$;
revoke execute on function public.get_family_feed() from public, anon;
grant execute on function public.get_family_feed() to authenticated;

create function public.register_push_token(p_token text, p_platform text) returns void
language plpgsql security definer set search_path = public as $$
begin
  if not exists(select 1 from public.profiles where id = auth.uid()) then raise exception 'profile_required'; end if;
  if p_token is null or length(btrim(p_token)) not between 1 and 4096 or p_platform is null or p_platform not in ('android','ios') then raise exception 'invalid_push_token'; end if;
  -- A device signed into a new account must no longer receive the previous account's pushes.
  perform pg_advisory_xact_lock(hashtextextended(p_token, 1));
  delete from public.push_tokens where token = p_token and user_id <> auth.uid();
  insert into public.push_tokens(user_id,token,platform) values(auth.uid(),p_token,p_platform)
    on conflict (user_id,token) do update set platform = excluded.platform;
end $$;
revoke execute on function public.register_push_token(text,text) from public, anon;
grant execute on function public.register_push_token(text,text) to authenticated;

-- Internal Edge persistence RPC: authenticated has EXECUTE per project convention,
-- but the explicit service-role guard rejects all car/person JWTs before any row access.
create function public.save_monthly_letter(p_car_id uuid, p_driver_id uuid, p_month date, p_created_at timestamptz, p_letter text) returns void
language plpgsql security definer set search_path = public as $$
declare current_driver uuid;
begin
  if auth.role() is distinct from 'service_role' then raise exception 'service_role_required'; end if;
  select c.driver_id into current_driver from public.car_links c where c.car_id = p_car_id for update;
  if not found or current_driver is distinct from p_driver_id then raise exception 'linked_car_required'; end if;
  update public.monthly_summaries set letter = p_letter
    where driver_id = p_driver_id and month = p_month and created_at = p_created_at;
  if not found then raise exception 'summary_changed'; end if;
end $$;
revoke execute on function public.save_monthly_letter(uuid,uuid,date,timestamptz,text) from public, anon;
grant execute on function public.save_monthly_letter(uuid,uuid,date,timestamptz,text) to authenticated, service_role;
commit;

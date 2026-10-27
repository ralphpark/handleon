begin;

alter table public.profiles add column fed_opt_in boolean not null default false;
-- The old table-wide INSERT grant would let signup set the new opt-in column.
-- Preserve access to the pre-existing columns while making opt-in RPC-only.
revoke insert on public.profiles from authenticated;
grant insert (id, role, display_name, created_at, sharing_paused_at) on public.profiles to authenticated;

create function public.valid_fed_vector(p_values double precision[], p_clipped boolean) returns boolean
language plpgsql immutable set search_path = '' as $$
declare x double precision;
begin
  if p_values is null or p_clipped is null or array_ndims(p_values) is distinct from 1
    or array_lower(p_values, 1) is distinct from 1 or cardinality(p_values) <> 15 then return false; end if;
  foreach x in array p_values loop
    -- NaN compares above Infinity in PostgreSQL; strict finite bounds reject both.
    if x is null or not (x > '-Infinity'::double precision and x < 'Infinity'::double precision)
      or (p_clipped and (x < -1 or x > 1)) then return false; end if;
  end loop;
  return true;
end $$;

create table public.fed_models (
  round integer primary key check (round >= 0),
  weights double precision[] not null check (public.valid_fed_vector(weights, false)),
  clients integer not null check ((round = 0 and clients = 0) or (round > 0 and clients >= 2)),
  created_at timestamptz not null default clock_timestamp()
);
create table public.fed_updates (
  round integer not null references public.fed_models(round) on delete cascade,
  driver_id uuid not null references public.profiles(id) on delete cascade,
  client_tag text not null check (client_tag in ('self', 'virtual-1', 'virtual-2')),
  delta double precision[] not null check (public.valid_fed_vector(delta, true)),
  n integer not null check (n between 1 and 520),
  created_at timestamptz not null default clock_timestamp(),
  primary key (round, driver_id, client_tag)
);
create index fed_updates_driver_idx on public.fed_updates(driver_id, round);
alter table public.fed_models enable row level security;
alter table public.fed_updates enable row level security;
revoke all on public.fed_models, public.fed_updates from public, anon, authenticated;
grant all on public.fed_models, public.fed_updates to service_role;
insert into public.fed_models(round, weights, clients) values (0, array_fill(0::double precision, array[15]), 0);

create function public.set_fed_opt_in(p_on boolean) returns void
language plpgsql security definer set search_path = '' as $$
declare owner uuid;
begin
  if p_on is null then raise exception 'choice_required'; end if;
  -- All three mutations take the same federation lock before reading the round.
  perform pg_advisory_xact_lock(150015, 1);
  owner := public.sharing_owner();
  if owner is null then raise exception 'driver_or_linked_car_required'; end if;
  update public.profiles p set fed_opt_in = p_on where p.id = owner and p.role = 'driver';
  if not found then raise exception 'driver_or_linked_car_required'; end if;
  if not p_on then
    delete from public.fed_updates u where u.driver_id = owner
      and u.round = (select max(m.round) from public.fed_models m);
  end if;
end $$;

create function public.submit_fed_update(p_round integer, p_client_tag text, p_delta double precision[], p_n integer) returns void
language plpgsql security definer set search_path = '' as $$
declare owner uuid; current_round integer;
begin
  perform pg_advisory_xact_lock(150015, 1);
  owner := public.sharing_owner();
  if owner is null then raise exception 'driver_or_linked_car_required'; end if;
  if not exists(select 1 from public.profiles p where p.id = owner and p.role = 'driver' and p.fed_opt_in) then
    raise exception 'fed_opt_in_required';
  end if;
  select max(m.round) into current_round from public.fed_models m;
  if p_round is distinct from current_round then raise exception 'round_changed'; end if;
  if p_client_tag is null or p_client_tag not in ('self', 'virtual-1', 'virtual-2') then raise exception 'invalid_client_tag'; end if;
  if not public.valid_fed_vector(p_delta, true) then raise exception 'invalid_delta'; end if;
  if p_n is null or p_n not between 1 and 520 then raise exception 'invalid_sample_count'; end if;
  insert into public.fed_updates as u(round, driver_id, client_tag, delta, n)
    values(p_round, owner, p_client_tag, p_delta, p_n)
    on conflict (round, driver_id, client_tag) do update
      set delta = excluded.delta, n = excluded.n, created_at = clock_timestamp()
      where u.delta is distinct from excluded.delta or u.n is distinct from excluded.n;
end $$;

create function public.get_fed_model() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
begin
  if public.sharing_owner() is null then raise exception 'driver_or_linked_car_required'; end if;
  -- Reading the shared model does not require donating a local update.
  return (select jsonb_build_object('round', m.round, 'weights', m.weights)
    from public.fed_models m order by m.round desc limit 1);
end $$;

create function public.fed_round_snapshot() returns jsonb
language plpgsql stable security definer set search_path = '' as $$
declare model public.fed_models; body jsonb;
begin
  select m.* into strict model from public.fed_models m order by m.round desc limit 1;
  body := jsonb_build_object('round', model.round, 'weights', model.weights, 'updates', coalesce((
    select jsonb_agg(jsonb_build_object('driver_id', u.driver_id, 'client_tag', u.client_tag, 'delta', u.delta, 'n', u.n)
      order by u.driver_id, u.client_tag)
    from public.fed_updates u join public.profiles p on p.id = u.driver_id
    where u.round = model.round and p.role = 'driver' and p.fed_opt_in
  ), '[]'::jsonb));
  return body || jsonb_build_object('input_hash', encode(extensions.digest(convert_to(body::text, 'UTF8'), 'sha256'), 'hex'));
end $$;

create function public.publish_fed_round(p_round integer, p_input_hash text, p_weights double precision[]) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare snapshot jsonb; current_round integer; client_count integer;
begin
  perform pg_advisory_xact_lock(150015, 1);
  snapshot := public.fed_round_snapshot();
  current_round := (snapshot->>'round')::integer;
  client_count := jsonb_array_length(snapshot->'updates');
  if p_round is distinct from current_round then
    return jsonb_build_object('advanced', false, 'round', current_round, 'clients', client_count, 'reason', 'round_changed');
  end if;
  if p_input_hash is distinct from snapshot->>'input_hash' then
    return jsonb_build_object('advanced', false, 'round', current_round, 'clients', client_count, 'reason', 'inputs_changed');
  end if;
  if client_count < 2 then
    return jsonb_build_object('advanced', false, 'round', current_round, 'clients', client_count, 'reason', 'insufficient_clients');
  end if;
  if not public.valid_fed_vector(p_weights, false) then raise exception 'invalid_weights'; end if;
  -- client_tag identifies virtual demo clients as well as self. This is not a
  -- distinct-human/privacy threshold, secure aggregation, or differential privacy.
  insert into public.fed_models(round, weights, clients) values(current_round + 1, p_weights, client_count);
  return jsonb_build_object('advanced', true, 'round', current_round + 1, 'clients', client_count);
end $$;

revoke execute on function public.valid_fed_vector(double precision[],boolean),
  public.set_fed_opt_in(boolean), public.submit_fed_update(integer,text,double precision[],integer),
  public.get_fed_model(), public.fed_round_snapshot(), public.publish_fed_round(integer,text,double precision[])
  from public, anon, authenticated;
grant execute on function public.set_fed_opt_in(boolean), public.submit_fed_update(integer,text,double precision[],integer),
  public.get_fed_model() to authenticated;
grant execute on function public.valid_fed_vector(double precision[],boolean), public.fed_round_snapshot(),
  public.publish_fed_round(integer,text,double precision[]) to service_role;

-- pg_cron uses UTC in this project: 18:40 UTC = the following day 03:40 KST.
-- Existing Vault names are reused; no request is made while either secret is absent.
select cron.schedule('handleon-fed-average', '40 18 * * *', $job$
  select net.http_post(
    url := rtrim(project.decrypted_secret, '/') || '/functions/v1/fed-average',
    headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer ' || service.decrypted_secret),
    body := '{}'::jsonb, timeout_milliseconds := 120000
  )
  from (select decrypted_secret from vault.decrypted_secrets where name = 'project_url' limit 1) project
  cross join (select decrypted_secret from vault.decrypted_secrets where name = 'service_role_key' limit 1) service
  where project.decrypted_secret like 'https://%' and length(service.decrypted_secret) > 0;
$job$);

commit;

begin;
create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  role text not null check (role in ('driver', 'family')),
  display_name text check (length(display_name) <= 80),
  created_at timestamptz not null default now()
);
create table public.car_links (
  car_id uuid primary key references auth.users(id) on delete cascade,
  driver_id uuid not null references public.profiles(id) on delete cascade,
  linked_at timestamptz not null default now()
);
create index car_links_driver_idx on public.car_links(driver_id);
create table public.pairing_codes (
  code text primary key,
  car_id uuid not null references auth.users(id) on delete cascade,
  expires_at timestamptz not null,
  claimed_by uuid references public.profiles(id) on delete cascade
);
create index pairing_codes_car_idx on public.pairing_codes(car_id);

-- Strict JSON shapes prevent raw location keys/nested trip objects from entering summaries.
create function public.valid_summary_json(p_domains jsonb, p_top jsonb) returns boolean
language plpgsql immutable security definer set search_path = public as $$
declare item jsonb; k text; v jsonb;
begin
  if jsonb_typeof(p_domains) <> 'object' or jsonb_typeof(p_top) <> 'array' then return false; end if;
  if jsonb_array_length(p_top) > 3 then return false; end if;
  for k, v in select * from jsonb_each(p_domains) loop
    if k not in ('mobility','wayfinding','operation') or jsonb_typeof(v) <> 'number' then return false; end if;
  end loop;
  for item in select * from jsonb_array_elements(p_top) loop
    if jsonb_typeof(item) <> 'object' then return false; end if;
    if not (item ?& array['feature','text','grade']) or (item - array['feature','text','grade']) <> '{}'::jsonb then return false; end if;
    if jsonb_typeof(item->'feature') <> 'string' or (item->>'feature') !~ '^[MWO][1-9][0-9]?$'
      or jsonb_typeof(item->'text') <> 'string' or length(item->>'text') > 300
      or jsonb_typeof(item->'grade') <> 'string' or item->>'grade' not in ('A','B','C') then return false; end if;
  end loop;
  return true;
end $$;
revoke execute on function public.valid_summary_json(jsonb,jsonb) from public, anon;
grant execute on function public.valid_summary_json(jsonb,jsonb) to authenticated, service_role;

create table public.monthly_summaries (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  month date not null check (extract(day from month) = 1),
  mode text not null check (mode in ('maeum','condition')),
  stage text not null check (stage in ('building','stable','watch','talk')),
  domains jsonb not null,
  top jsonb not null,
  letter text,
  source text not null check (source in ('car','replay')),
  created_at timestamptz not null default now(),
  primary key (driver_id, month),
  check (public.valid_summary_json(domains, top))
);
create table public.daily_conditions (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  day date not null, ewma real not null, threshold real not null, alert boolean not null,
  created_at timestamptz not null default now(), primary key (driver_id, day)
);
create table public.family_links (
  driver_id uuid not null references public.profiles(id) on delete cascade,
  family_id uuid not null references public.profiles(id) on delete cascade,
  scope text not null check (scope in ('stage','trend','detail')),
  delay_days integer not null check (delay_days in (0,3,7,14)),
  created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
  revoked_at timestamptz, primary key (driver_id, family_id), check (driver_id <> family_id)
);
create index family_links_family_idx on public.family_links(family_id);
create table public.invites (
  code text primary key,
  driver_id uuid not null references public.profiles(id) on delete cascade,
  scope text not null check (scope in ('stage','trend','detail')),
  delay_days integer not null check (delay_days in (0,3,7,14)),
  expires_at timestamptz not null,
  accepted_by uuid references public.profiles(id) on delete cascade
);
create table public.push_tokens (
  user_id uuid not null references public.profiles(id) on delete cascade,
  token text not null check (length(token) between 1 and 4096),
  platform text not null check (platform in ('android','ios')), primary key (user_id, token)
);
create table public.notification_queue (
  id bigserial primary key,
  driver_id uuid not null, family_id uuid not null,
  month date not null,
  due_at timestamptz not null, sent_at timestamptz, cancelled_at timestamptz,
  created_at timestamptz not null default now(),
  claim_token uuid, claimed_at timestamptz,
  next_attempt_at timestamptz not null default now(),
  foreign key (driver_id, family_id) references public.family_links(driver_id, family_id) on delete cascade,
  foreign key (driver_id, month) references public.monthly_summaries(driver_id, month) on delete cascade
);
create index notification_queue_due_idx on public.notification_queue(next_attempt_at, due_at) where sent_at is null and cancelled_at is null;

-- Use receipt time, not a client-supplied/backdated timestamp. Letter-only updates preserve it.
-- A changed car summary starts a new grace period; exact retries preserve the original time.
create function public.stamp_summary_upload() returns trigger
language plpgsql security definer set search_path = public as $$
begin
  if auth.role() = 'authenticated' then
    if tg_op = 'INSERT' then new.created_at := clock_timestamp();
    elsif to_jsonb(new) - array['created_at','letter'] is distinct from to_jsonb(old) - array['created_at','letter'] then
      new.created_at := clock_timestamp();
      if tg_table_name = 'monthly_summaries' then new.letter := null; end if;
    else new.created_at := old.created_at;
    end if;
  end if;
  return new;
end $$;
revoke execute on function public.stamp_summary_upload() from public, anon, authenticated;
create trigger stamp_month before insert or update on public.monthly_summaries for each row execute function public.stamp_summary_upload();
create trigger stamp_day before insert or update on public.daily_conditions for each row execute function public.stamp_summary_upload();

alter table public.profiles enable row level security;
alter table public.car_links enable row level security;
alter table public.pairing_codes enable row level security;
alter table public.monthly_summaries enable row level security;
alter table public.daily_conditions enable row level security;
alter table public.family_links enable row level security;
alter table public.invites enable row level security;
alter table public.push_tokens enable row level security;
alter table public.notification_queue enable row level security;

revoke all on public.profiles, public.car_links, public.pairing_codes, public.monthly_summaries,
  public.daily_conditions, public.family_links, public.invites, public.push_tokens, public.notification_queue from anon, authenticated;
-- 역할(role)은 가입 때 한 번만 정한다: 프로필은 이름만 고칠 수 있다(가족이 운전자로 바꿔 RPC 를 쓰는 것을 막음).
grant select, insert, delete on public.profiles to authenticated;
grant update (display_name) on public.profiles to authenticated;
grant select, insert, update, delete on public.push_tokens to authenticated;
grant select on public.car_links to authenticated;
grant select, insert, update on public.monthly_summaries, public.daily_conditions to authenticated;
grant all on public.profiles, public.car_links, public.pairing_codes, public.monthly_summaries,
  public.daily_conditions, public.family_links, public.invites, public.push_tokens, public.notification_queue to service_role;
grant usage, select on sequence public.notification_queue_id_seq to service_role;

create policy profiles_self on public.profiles for all to authenticated
using (id = auth.uid() and not coalesce((auth.jwt()->>'is_anonymous')::boolean, false))
with check (id = auth.uid() and not coalesce((auth.jwt()->>'is_anonymous')::boolean, false));
create policy own_car_link on public.car_links for select to authenticated
using (car_id = auth.uid() or driver_id = auth.uid());
-- PostgreSQL ON CONFLICT UPDATE requires SELECT visibility for the linked car as well.
create policy read_month on public.monthly_summaries for select to authenticated
using (driver_id = auth.uid() or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy insert_month on public.monthly_summaries for insert to authenticated
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy update_month on public.monthly_summaries for update to authenticated
using (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy read_day on public.daily_conditions for select to authenticated
using (driver_id = auth.uid() or driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy insert_day on public.daily_conditions for insert to authenticated
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy update_day on public.daily_conditions for update to authenticated
using (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()))
with check (driver_id in (select c.driver_id from public.car_links c where c.car_id = auth.uid()));
create policy own_push_tokens on public.push_tokens for all to authenticated
using (user_id = auth.uid()) with check (user_id = auth.uid());
commit;

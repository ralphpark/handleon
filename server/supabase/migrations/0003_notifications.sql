begin;
create extension if not exists pg_cron;
create extension if not exists pg_net with schema extensions;
-- Deployment prerequisite: Claude must populate Supabase Vault secrets named
-- 'service_role_key' (the same legacy service-role JWT used by the Edge Function)
-- and 'project_url' (https://<project-ref>.supabase.co). Never embed their values here.
-- The Vault extension/schema is supplied by Supabase. Missing secrets fail closed:
-- the cron SELECT produces zero HTTP requests until both nonempty values exist.

create function public.enqueue_summary_notifications() returns trigger
language plpgsql security definer set search_path = public as $$
declare f record; changed boolean;
begin
  changed := tg_op = 'INSERT';
  if tg_op = 'UPDATE' then changed := new.stage is distinct from old.stage; end if;
  -- Lock links before queues, matching revoke_share/update_share, to serialize consent updates.
  for f in select * from public.family_links where driver_id = new.driver_id order by family_id for update loop
    if f.revoked_at is not null then continue; end if;
    if new.stage not in ('watch','talk') then
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
revoke execute on function public.enqueue_summary_notifications() from public, anon, authenticated;
create trigger enqueue_month after insert or update on public.monthly_summaries
  for each row execute function public.enqueue_summary_notifications();

select cron.schedule('handleon-dispatch-notifications', '*/10 * * * *', $job$
  select net.http_post(
    url := rtrim(project.decrypted_secret, '/') || '/functions/v1/dispatch-notifications',
    headers := jsonb_build_object('Content-Type', 'application/json', 'Authorization', 'Bearer ' || service.decrypted_secret),
    body := '{}'::jsonb, timeout_milliseconds := 120000
  )
  from (select decrypted_secret from vault.decrypted_secrets where name = 'project_url' limit 1) project
  cross join (select decrypted_secret from vault.decrypted_secrets where name = 'service_role_key' limit 1) service
  where project.decrypted_secret like 'https://%' and length(service.decrypted_secret) > 0;
$job$);
commit;

begin;
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('imports','imports', false, 5242880, array['application/json']) on conflict do nothing;

create policy imports_driver_insert on storage.objects for insert to authenticated
with check (bucket_id = 'imports' and (storage.foldername(name))[1] = auth.uid()::text
  and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'));
create policy imports_participant_select on storage.objects for select to authenticated
using (bucket_id = 'imports' and (
  ((storage.foldername(name))[1] = auth.uid()::text
    and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or exists(select 1 from public.car_links c where c.car_id = auth.uid() and c.driver_id::text = (storage.foldername(name))[1])
));
create policy imports_participant_delete on storage.objects for delete to authenticated
using (bucket_id = 'imports' and (
  ((storage.foldername(name))[1] = auth.uid()::text
    and exists(select 1 from public.profiles p where p.id = auth.uid() and p.role = 'driver'))
  or exists(select 1 from public.car_links c where c.car_id = auth.uid() and c.driver_id::text = (storage.foldername(name))[1])
));
commit;

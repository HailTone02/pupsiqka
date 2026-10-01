begin;

alter table public.profiles
    add column if not exists username_last_changed_at timestamptz;

revoke all privileges (username_last_changed_at)
    on table public.profiles from public, anon, authenticated;
grant select (username_last_changed_at)
    on table public.profiles to authenticated;

create or replace function public.change_profile_username(p_username text)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    normalized_username text := lower(btrim(p_username));
    current_username text;
    last_changed_at timestamptz;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if normalized_username is null
        or normalized_username !~ '^[a-z0-9_]{3,30}$'
        or normalized_username in (
            'admin', 'administrator', 'api', 'billing', 'contact', 'help',
            'hailtone', 'null', 'root', 'security', 'support', 'system', 'www'
        ) then
        raise exception 'Username is invalid' using errcode = '22023';
    end if;

    select username, username_last_changed_at
      into current_username, last_changed_at
      from public.profiles
     where user_id = authenticated_user_id
     for update;
    if not found then
        raise exception 'Profile not found' using errcode = 'P0002';
    end if;
    if current_username = normalized_username then
        return;
    end if;
    if last_changed_at is not null and last_changed_at > now() - interval '30 days' then
        raise exception 'Username can only be changed once every 30 days' using errcode = '22023';
    end if;

    update public.profiles
       set username = normalized_username,
           username_last_changed_at = now()
     where user_id = authenticated_user_id;
end;
$$;

revoke all on function public.change_profile_username(text) from public, anon, authenticated;
grant execute on function public.change_profile_username(text) to authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('profile-photos', 'profile-photos', true, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update
set public = excluded.public,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists "Public can read profile photos" on storage.objects;
create policy "Public can read profile photos"
    on storage.objects for select to public
    using (bucket_id = 'profile-photos');

drop policy if exists "Users upload their profile photos" on storage.objects;
create policy "Users upload their profile photos"
    on storage.objects for insert to authenticated
    with check (bucket_id = 'profile-photos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "Users update their profile photos" on storage.objects;
create policy "Users update their profile photos"
    on storage.objects for update to authenticated
    using (bucket_id = 'profile-photos' and (storage.foldername(name))[1] = auth.uid()::text)
    with check (bucket_id = 'profile-photos' and (storage.foldername(name))[1] = auth.uid()::text);

drop policy if exists "Users delete their profile photos" on storage.objects;
create policy "Users delete their profile photos"
    on storage.objects for delete to authenticated
    using (bucket_id = 'profile-photos' and (storage.foldername(name))[1] = auth.uid()::text);

commit;
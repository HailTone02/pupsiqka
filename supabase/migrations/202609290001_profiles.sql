create table public.profiles (
    user_id uuid primary key references auth.users (id) on delete cascade,
    display_name text,
    avatar_path text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_display_name_check check (
        display_name is null or (
            display_name = btrim(display_name)
            and char_length(display_name) between 1 and 80
            and display_name !~ '[[:cntrl:]]'
        )
    ),
    constraint profiles_avatar_path_check check (
        avatar_path is null or (
            char_length(avatar_path) between 1 and 512
            and avatar_path !~ '(^/|(^|/)\.\.?(/|$)|://)'
        )
    )
);

create index profiles_display_name_lower_idx
    on public.profiles (lower(display_name))
    where display_name is not null;

alter table public.profiles enable row level security;

revoke all on table public.profiles from public, anon, authenticated;
grant select (user_id, display_name, avatar_path)
    on table public.profiles to authenticated;
grant update (display_name, avatar_path)
    on table public.profiles to authenticated;

create policy "Users read their own profile"
    on public.profiles for select to authenticated
    using (user_id = auth.uid());

create policy "Users update their own profile"
    on public.profiles for update to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());

create function public.get_public_profiles(p_user_ids uuid[])
returns table (user_id uuid, display_name text, avatar_path text)
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    if p_user_ids is null or cardinality(p_user_ids) not between 1 and 50 then
        raise exception 'Provide between 1 and 50 profile IDs' using errcode = '22023';
    end if;

    return query
    select profile.user_id, profile.display_name, profile.avatar_path
    from public.profiles as profile
    where profile.user_id = any (p_user_ids)
      and profile.user_id <> auth.uid();
end;
$$;

revoke all on function public.get_public_profiles(uuid[]) from public, anon, authenticated;
grant execute on function public.get_public_profiles(uuid[]) to authenticated;

create function public.set_profile_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create trigger profiles_set_updated_at
    before update on public.profiles
    for each row execute function public.set_profile_updated_at();

create function public.create_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    insert into public.profiles (user_id)
    values (new.id)
    on conflict (user_id) do nothing;
    return new;
end;
$$;

create trigger on_auth_user_created_profile
    after insert on auth.users
    for each row execute function public.create_profile_for_auth_user();

insert into public.profiles (user_id)
select id from auth.users
on conflict (user_id) do nothing;
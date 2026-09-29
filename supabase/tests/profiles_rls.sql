begin;

insert into auth.users (id, email)
values
    ('00000000-0000-4000-8000-000000000101', 'profile-rls-owner@example.test'),
    ('00000000-0000-4000-8000-000000000102', 'profile-rls-other@example.test')
on conflict (id) do nothing;

update public.profiles
set display_name = case user_id
    when '00000000-0000-4000-8000-000000000101' then 'Owner Profile'
    when '00000000-0000-4000-8000-000000000102' then 'Other Profile'
end
where user_id in (
    '00000000-0000-4000-8000-000000000101',
    '00000000-0000-4000-8000-000000000102'
);

do $$
begin
    if (select count(*) from public.profiles where user_id in (
        '00000000-0000-4000-8000-000000000101',
        '00000000-0000-4000-8000-000000000102'
    )) <> 2 then
        raise exception 'auth.users insert did not create profile rows';
    end if;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000101', true);

do $$
declare
    public_profile jsonb;
    affected_rows integer;
begin
    if (select count(*) from public.profiles where user_id = '00000000-0000-4000-8000-000000000101') <> 1 then
        raise exception 'authenticated user cannot read own profile';
    end if;
    if (select count(*) from public.profiles where user_id = '00000000-0000-4000-8000-000000000102') <> 0 then
        raise exception 'authenticated user can read another profile from the base table';
    end if;

    select to_jsonb(profile_row) into public_profile
    from public.get_public_profiles(array['00000000-0000-4000-8000-000000000102'::uuid]) as profile_row;

    if public_profile is null
        or jsonb_object_length(public_profile) <> 3
        or public_profile ->> 'user_id' <> '00000000-0000-4000-8000-000000000102'
        or public_profile ->> 'display_name' <> 'Other Profile'
        or public_profile ?| array['email', 'phone', 'user_metadata', 'app_metadata', 'access_token'] then
        raise exception 'public profile API returned an unexpected projection';
    end if;

    begin
        perform * from public.get_public_profiles(array_fill(
            '00000000-0000-4000-8000-000000000102'::uuid,
            array[51]
        ));
        raise exception 'public profile API accepted an unbounded ID list';
    exception when invalid_parameter_value then
        null;
    end;

    update public.profiles set display_name = 'Updated own profile'
    where user_id = '00000000-0000-4000-8000-000000000101';
    get diagnostics affected_rows = row_count;
    if affected_rows <> 1 then
        raise exception 'authenticated user cannot update own profile';
    end if;

    begin
        insert into public.profiles (user_id)
        values ('00000000-0000-4000-8000-000000000101');
        raise exception 'authenticated user inserted a profile directly';
    exception when insufficient_privilege then
        null;
    end;

    update public.profiles set display_name = 'Unauthorized update'
    where user_id = '00000000-0000-4000-8000-000000000102';
    get diagnostics affected_rows = row_count;
    if affected_rows <> 0 then
        raise exception 'authenticated user modified another profile';
    end if;

    begin
        update public.profiles set user_id = '00000000-0000-4000-8000-000000000102'
        where user_id = '00000000-0000-4000-8000-000000000101';
        raise exception 'authenticated user changed profile identity';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

rollback;
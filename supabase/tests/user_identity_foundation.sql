begin;

do $$
declare
    owner_id uuid := gen_random_uuid();
    other_id uuid := gen_random_uuid();
    duplicate_id uuid := gen_random_uuid();
    invalid_id uuid := gen_random_uuid();
    reserved_id uuid := gen_random_uuid();
    missing_username_id uuid := gen_random_uuid();
    invitation_id uuid := gen_random_uuid();
begin
    insert into auth.users (id, email, raw_user_meta_data)
    values (
        owner_id,
        'identity-owner@example.test',
        jsonb_build_object('hailtone_name', 'Ada', 'hailtone_surname', 'Lovelace', 'hailtone_username', 'Ada_L')
    );
    insert into auth.users (id, email, email_confirmed_at, raw_user_meta_data)
    values (
        other_id,
        'identity-other@example.test',
        now(),
        jsonb_build_object('hailtone_name', 'Grace', 'hailtone_surname', 'Hopper', 'hailtone_username', 'Grace_1')
    );

    if (select username from public.profiles where user_id = owner_id) <> 'ada_l'
        or (select count(*) from public.profiles where user_id = owner_id) <> 1
        or not (select identity_required from public.profiles where user_id = owner_id)
        or (select identity_complete from public.profiles where user_id = owner_id) then
        raise exception 'profile provisioning, username normalization, or verification state is incorrect';
    end if;

    update auth.users set email_confirmed_at = now() where id = owner_id;
    if (select identity_complete from public.profiles where user_id = owner_id) then
        raise exception 'email verification alone completed a phone-required identity';
    end if;
    update auth.users set phone = '+12025550101', phone_confirmed_at = now() where id = owner_id;
    if not (select identity_complete from public.profiles where user_id = owner_id) then
        raise exception 'same Auth UUID email and phone verification did not complete identity';
    end if;
    update public.profiles set avatar_path = 'avatars/identity-other.png' where user_id = other_id;

    insert into public.hailtone_contact_invites (id, token_hash, inviter_user_id, expires_at)
    values (invitation_id, extensions.digest(extensions.gen_random_bytes(32), 'sha256'), owner_id, now() + interval '1 day');
    begin
        insert into public.hailtone_contact_links (owner_user_id, contact_user_id, invitation_id)
        values (owner_id, other_id, invitation_id);
        raise exception 'unverified account entered the accepted-contact graph';
    exception when insufficient_privilege then
        null;
    end;

    begin
        insert into auth.users (id, email, raw_user_meta_data)
        values (
            duplicate_id,
            'identity-duplicate@example.test',
            jsonb_build_object('hailtone_name', 'A', 'hailtone_surname', 'B', 'hailtone_username', 'ADA_L')
        );
        raise exception 'case-insensitive username collision was accepted';
    exception when unique_violation then
        null;
    end;

    begin
        insert into auth.users (id, email, raw_user_meta_data)
        values (
            missing_username_id,
            'identity-no-username@example.test',
            jsonb_build_object('hailtone_name', 'A', 'hailtone_surname', 'B')
        );
        raise exception 'missing username was accepted';
    exception when invalid_parameter_value then
        null;
    end;

    begin
        insert into auth.users (id, email, raw_user_meta_data)
        values (
            invalid_id,
            'identity-invalid@example.test',
            jsonb_build_object('hailtone_name', 'A', 'hailtone_surname', 'B', 'hailtone_username', 'bad-name')
        );
        raise exception 'invalid username was accepted';
    exception when invalid_parameter_value then
        null;
    end;

    begin
        insert into auth.users (id, email, raw_user_meta_data)
        values (
            reserved_id,
            'identity-reserved@example.test',
            jsonb_build_object('hailtone_name', 'A', 'hailtone_surname', 'B', 'hailtone_username', 'Support')
        );
        raise exception 'reserved username was accepted';
    exception when invalid_parameter_value then
        null;
    end;

    perform set_config('test.identity.owner', owner_id::text, true);
    perform set_config('test.identity.other', other_id::text, true);
end;
$$;

select set_config('request.jwt.claim.sub', current_setting('test.identity.owner'), true);
set local role authenticated;
do $$
declare
    owner_id uuid := current_setting('test.identity.owner')::uuid;
    other_id uuid := current_setting('test.identity.other')::uuid;
    changed_rows integer;
    public_profile jsonb;
begin
    if (select count(*) from public.profiles where user_id = owner_id) <> 1
        or exists (select 1 from public.profiles where user_id = other_id) then
        raise exception 'profile RLS does not isolate accounts';
    end if;
    select to_jsonb(profile_row) into public_profile
    from public.get_public_profiles(array[other_id]) as profile_row;
    if coalesce((
            select array_agg(profile_key order by profile_key)
            from jsonb_object_keys(public_profile) as profile_keys(profile_key)
        ), array[]::text[]) <> array['avatar_path', 'username']::text[]
        or public_profile ->> 'username' <> 'grace_1'
        or public_profile ->> 'avatar_path' <> 'avatars/identity-other.png'
        or public_profile ?| array[
            'user_id', 'display_name', 'name', 'surname', 'email', 'phone',
            'identity_required', 'identity_complete', 'confirmed_at', 'email_confirmed_at', 'phone_confirmed_at',
            'raw_user_meta_data', 'user_metadata', 'app_metadata'
        ] then
        raise exception 'public profile projection contains private identity data or omits username';
    end if;
    begin
        update public.profiles set username = 'other_name' where user_id = other_id;
        get diagnostics changed_rows = row_count;
        if changed_rows <> 0 then raise exception 'account changed another profile'; end if;
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

reset role;
rollback;
begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    (
        '00000000-0000-4000-8000-000000000601',
        'push-token-owner@example.test',
        jsonb_build_object('hailtone_name', 'Push', 'hailtone_surname', 'Owner', 'hailtone_username', 'push_owner')
    ),
    (
        '00000000-0000-4000-8000-000000000602',
        'push-token-other@example.test',
        jsonb_build_object('hailtone_name', 'Push', 'hailtone_surname', 'Other', 'hailtone_username', 'push_other')
    )
on conflict (id) do nothing;

do $$
begin
    if not (select relrowsecurity and relforcerowsecurity from pg_class where oid = 'public.call_push_tokens'::regclass)
        or has_table_privilege('authenticated', 'public.call_push_tokens', 'SELECT')
        or has_table_privilege('authenticated', 'public.call_push_tokens', 'INSERT')
        or has_table_privilege('authenticated', 'public.call_push_tokens', 'UPDATE')
        or has_table_privilege('authenticated', 'public.call_push_tokens', 'DELETE')
        or has_table_privilege('anon', 'public.call_push_tokens', 'SELECT')
        or has_function_privilege('anon', 'public.register_call_push_token(uuid,text)', 'EXECUTE')
        or has_function_privilege('anon', 'public.unregister_call_push_token(uuid)', 'EXECUTE') then
        raise exception 'push token access exceeds authenticated RPC/server boundary';
    end if;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000601', true);

do $$
declare
    installation_id uuid := '00000000-0000-4000-8000-000000000611';
begin
    perform public.register_call_push_token(installation_id, 'fcm-test-token-owner-initial-0001');
    perform public.register_call_push_token(installation_id, 'fcm-test-token-owner-rotated-0002');
    begin
        perform * from public.call_push_tokens;
        raise exception 'authenticated user directly read push tokens';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform public.register_call_push_token(installation_id, 'short');
        raise exception 'invalid FCM token was accepted';
    exception when invalid_parameter_value then
        null;
    end;
end;
$$;

select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000602', true);

do $$
begin
    begin
        perform public.register_call_push_token(
            '00000000-0000-4000-8000-000000000612',
            'fcm-test-token-owner-rotated-0002'
        );
        raise exception 'another account claimed an existing FCM token';
    exception when unique_violation then
        null;
    end;
    perform public.unregister_call_push_token('00000000-0000-4000-8000-000000000611');
    perform public.register_call_push_token(
        '00000000-0000-4000-8000-000000000612',
        'fcm-test-token-other-account-0004'
    );
end;
$$;

reset role;

do $$
begin
    if (select count(*) from public.call_push_tokens) <> 2
        or not exists (
            select 1 from public.call_push_tokens
            where installation_id = '00000000-0000-4000-8000-000000000611'
              and user_id = '00000000-0000-4000-8000-000000000601'
              and fcm_token = 'fcm-test-token-owner-rotated-0002'
                ) or exists (
                        select 1 from public.call_push_tokens
                        where installation_id = '00000000-0000-4000-8000-000000000612'
                            and fcm_token = 'fcm-test-token-owner-rotated-0002'
        ) then
        raise exception 'token rotation, ownership, or cross-user unregister failed';
    end if;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-4000-8000-000000000601', true);
select public.unregister_call_push_token('00000000-0000-4000-8000-000000000611');
reset role;

do $$
begin
    if exists (select 1 from public.call_push_tokens where installation_id = '00000000-0000-4000-8000-000000000611') then
        raise exception 'authenticated owner could not unregister its installation';
    end if;
    if (select count(*) from public.call_push_tokens) <> 1 then
        raise exception 'owner unregister removed another user token';
    end if;
end;
$$;

set local role anon;
do $$
begin
    begin
        perform public.register_call_push_token(
            '00000000-0000-4000-8000-000000000613',
            'fcm-test-token-anonymous-account-0004'
        );
        raise exception 'anonymous user registered a push token';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

rollback;
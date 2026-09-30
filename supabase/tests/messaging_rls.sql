begin;

create temporary table secure_messaging_test_ids on commit drop as
select gen_random_uuid() as user_a, gen_random_uuid() as user_b, gen_random_uuid() as user_c;

insert into auth.users (id, email)
select user_a, user_a::text || '@secure-messaging-test.invalid' from secure_messaging_test_ids
union all
select user_b, user_b::text || '@secure-messaging-test.invalid' from secure_messaging_test_ids
union all
select user_c, user_c::text || '@secure-messaging-test.invalid' from secure_messaging_test_ids;

do $$
declare
    user_a uuid;
    user_b uuid;
    invite_id uuid := gen_random_uuid();
    invite_token text := encode(extensions.gen_random_bytes(32), 'hex');
begin
    select secure_messaging_test_ids.user_a, secure_messaging_test_ids.user_b into user_a, user_b
    from secure_messaging_test_ids;
    insert into public.hailtone_contact_invites (
        id, token_hash, inviter_user_id, accepted_by_user_id, expires_at, accepted_at
    ) values (
        invite_id, extensions.digest(convert_to(invite_token, 'UTF8'), 'sha256'),
        user_a, user_b, now() + interval '1 day', now()
    );
    insert into public.hailtone_contact_links (owner_user_id, contact_user_id, invitation_id)
    values (user_a, user_b, invite_id), (user_b, user_a, invite_id);
end;
$$;

insert into public.hailtone_message_devices (owner_user_id, device_id, matrix_user_id, device_keys)
select user_a, 'device-a', public.hailtone_matrix_user_id(user_a),
       jsonb_build_object('user_id', public.hailtone_matrix_user_id(user_a), 'device_id', 'device-a',
           'keys', jsonb_build_object('ed25519:device-a', 'test-public-key-a', 'curve25519:device-a', 'test-curve-key-a'))
from secure_messaging_test_ids
union all
select user_b, 'device-b', public.hailtone_matrix_user_id(user_b),
       jsonb_build_object('user_id', public.hailtone_matrix_user_id(user_b), 'device_id', 'device-b',
           'keys', jsonb_build_object('ed25519:device-b', 'test-public-key-b', 'curve25519:device-b', 'test-curve-key-b'))
from secure_messaging_test_ids;

select set_config('test.secure.user_a', user_a::text, true),
       set_config('test.secure.user_b', user_b::text, true),
       set_config('test.secure.user_c', user_c::text, true)
from secure_messaging_test_ids;

select set_config('request.jwt.claim.sub', current_setting('test.secure.user_a'), true);
set local role authenticated;
do $$
declare
    user_b uuid := current_setting('test.secure.user_b')::uuid;
    conversation_id uuid;
    duplicate_id uuid;
    fixture_client_id uuid := gen_random_uuid();
begin
    if to_regclass('public.messages') is not null
       or to_regprocedure('public.send_message(uuid,uuid,text)') is not null
       or to_regprocedure('public.get_conversation_messages(uuid,timestamptz,uuid,integer)') is not null then
        raise exception 'plaintext message archive or RPC remains available';
    end if;
    if not (select relrowsecurity from pg_class where oid = 'public.hailtone_message_devices'::regclass)
       or not (select relrowsecurity from pg_class where oid = 'public.hailtone_message_one_time_keys'::regclass)
       or not (select relrowsecurity from pg_class where oid = 'public.hailtone_message_envelopes'::regclass)
       or has_table_privilege('authenticated', 'public.hailtone_message_devices', 'SELECT')
       or has_table_privilege('authenticated', 'public.hailtone_message_one_time_keys', 'SELECT')
    or has_table_privilege('authenticated', 'public.hailtone_message_envelopes', 'SELECT')
       or has_table_privilege('authenticated', 'public.hailtone_message_envelopes', 'INSERT')
       or has_table_privilege('authenticated', 'public.hailtone_message_envelopes', 'DELETE') then
        raise exception 'secure message grants or RLS are misconfigured';
    end if;
    select created.conversation_id into conversation_id
    from public.get_or_create_direct_conversation(user_b) as created;
    select created.conversation_id into duplicate_id
    from public.get_or_create_direct_conversation(user_b) as created;
    if conversation_id is null or conversation_id <> duplicate_id then
        raise exception 'accepted direct conversation was not canonical';
    end if;
    perform set_config('test.secure.conversation_id', conversation_id::text, true);
    perform set_config('test.secure.client_message_id', fixture_client_id::text, true);
    perform public.send_hailtone_olm_envelopes(
        conversation_id,
        fixture_client_id,
        'device-a',
        'm.room.encrypted',
        jsonb_build_object(
            public.hailtone_matrix_user_id(user_b),
            jsonb_build_object('device-b', jsonb_build_object(
                'algorithm', 'm.olm.v1.curve25519-aes-sha2',
                'ciphertext', jsonb_build_object('opaque-key', jsonb_build_object('type', 0, 'body', 'opaque-ciphertext'))
            ))
        )
    );
end;
$$;
reset role;

select set_config('test.secure.envelope_id', id::text, true)
from public.hailtone_message_envelopes
where client_message_id = current_setting('test.secure.client_message_id')::uuid;

select set_config('request.jwt.claim.sub', current_setting('test.secure.user_b'), true);
set local role authenticated;
do $$
declare
    envelope public.hailtone_message_envelopes%rowtype;
    envelope_id uuid := current_setting('test.secure.envelope_id')::uuid;
begin
    select * into envelope from public.fetch_hailtone_olm_envelopes('device-b') where id = envelope_id;
    if envelope.id is null or envelope.event_type <> 'm.room.encrypted'
       or envelope.ciphertext #>> '{ciphertext,opaque-key,body}' <> 'opaque-ciphertext' then
        raise exception 'recipient did not receive a ciphertext-only envelope';
    end if;
    if not public.ack_hailtone_olm_envelope(envelope_id, 'device-b') then
        raise exception 'recipient ACK did not remove envelope';
    end if;
    if exists (select 1 from public.hailtone_message_envelopes where id = envelope_id) then
        raise exception 'ACKed envelope was retained';
    end if;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.secure.user_c'), true);
set local role authenticated;
do $$
begin
    if exists (select 1 from public.list_direct_conversations(null, null, 50)) then
        raise exception 'unlinked user listed conversations';
    end if;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.secure.user_a'), true);
set local role authenticated;
select public.block_hailtone_contact(current_setting('test.secure.user_b')::uuid);
do $$
declare
    conversation_id uuid := current_setting('test.secure.conversation_id')::uuid;
begin
    begin
        perform public.send_hailtone_olm_envelopes(
            conversation_id, gen_random_uuid(), 'device-a', 'm.room.encrypted',
            jsonb_build_object(
                public.hailtone_matrix_user_id(current_setting('test.secure.user_b')::uuid),
                jsonb_build_object('device-b', jsonb_build_object('algorithm', 'm.olm.v1.curve25519-aes-sha2', 'ciphertext', '{}'::jsonb))
            )
        );
        raise exception 'blocked contact received a message envelope';
    exception when insufficient_privilege then
        null;
    end;
    if exists (select 1 from public.list_direct_conversations(null, null, 50)) then
        raise exception 'blocked contact remained in conversation list';
    end if;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', '', true);
set local role anon;
do $$
begin
    if has_function_privilege('anon', 'public.publish_hailtone_olm_keys(jsonb,jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'public.query_hailtone_olm_keys(jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'public.claim_hailtone_olm_keys(jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'public.send_hailtone_olm_envelopes(uuid,uuid,text,text,jsonb)', 'EXECUTE')
       or has_function_privilege('anon', 'public.fetch_hailtone_olm_envelopes(text)', 'EXECUTE')
       or has_function_privilege('anon', 'public.ack_hailtone_olm_envelope(uuid,text)', 'EXECUTE') then
        raise exception 'anonymous role has secure messaging access';
    end if;
end;
$$;
reset role;

rollback;
begin;

create temporary table contact_identity_test_ids on commit drop as
select gen_random_uuid() as owner_id,
       gen_random_uuid() as peer_id,
       gen_random_uuid() as unrelated_id,
       gen_random_uuid() as unverified_id;

insert into auth.users (id, email, phone, phone_confirmed_at)
select owner_id, owner_id::text || '@contact-test.invalid', '+15550000001', now() from contact_identity_test_ids
union all
select peer_id, peer_id::text || '@contact-test.invalid', '+15550000002', now() from contact_identity_test_ids
union all
select unrelated_id, unrelated_id::text || '@contact-test.invalid', '+15550000003', now() from contact_identity_test_ids
union all
select unverified_id, unverified_id::text || '@contact-test.invalid', null, null from contact_identity_test_ids;

update public.profiles
set display_name = case user_id
    when (select owner_id from contact_identity_test_ids) then 'Invite Owner'
    when (select peer_id from contact_identity_test_ids) then 'Invite Peer'
    when (select unrelated_id from contact_identity_test_ids) then 'Unrelated User'
end
where user_id in (
    select owner_id from contact_identity_test_ids
    union all select peer_id from contact_identity_test_ids
    union all select unrelated_id from contact_identity_test_ids
);

select set_config('test.contact.owner', owner_id::text, true),
       set_config('test.contact.peer', peer_id::text, true),
       set_config('test.contact.unrelated', unrelated_id::text, true),
       set_config('test.contact.unverified', unverified_id::text, true)
from contact_identity_test_ids;

insert into public.hailtone_message_devices (owner_user_id, device_id, matrix_user_id, device_keys)
select owner_id, 'contact-owner-device', public.hailtone_matrix_user_id(owner_id),
       jsonb_build_object(
           'user_id', public.hailtone_matrix_user_id(owner_id),
           'device_id', 'contact-owner-device',
           'keys', jsonb_build_object('ed25519:contact-owner-device', 'owner-ed25519', 'curve25519:contact-owner-device', 'owner-curve25519')
       )
from contact_identity_test_ids
union all
select peer_id, 'contact-peer-device', public.hailtone_matrix_user_id(peer_id),
       jsonb_build_object(
           'user_id', public.hailtone_matrix_user_id(peer_id),
           'device_id', 'contact-peer-device',
           'keys', jsonb_build_object('ed25519:contact-peer-device', 'peer-ed25519', 'curve25519:contact-peer-device', 'peer-curve25519')
       )
from contact_identity_test_ids;

do $$
begin
    if not (select relrowsecurity from pg_class where oid = 'public.hailtone_contact_invites'::regclass)
        or not (select relrowsecurity from pg_class where oid = 'public.hailtone_contact_links'::regclass)
        or not (select relrowsecurity from pg_class where oid = 'public.hailtone_user_blocks'::regclass)
        or has_table_privilege('authenticated', 'public.hailtone_contact_invites', 'SELECT')
        or has_table_privilege('authenticated', 'public.hailtone_contact_links', 'SELECT')
        or has_table_privilege('authenticated', 'public.hailtone_user_blocks', 'SELECT')
        or has_function_privilege('anon', 'public.create_hailtone_contact_invite()', 'EXECUTE')
        or has_function_privilege('anon', 'public.accept_hailtone_contact_invite(text)', 'EXECUTE')
        or has_function_privilege('anon', 'public.list_hailtone_contacts()', 'EXECUTE') then
        raise exception 'contact identity grants exceed the authenticated RPC boundary';
    end if;
    if pg_get_function_arguments('public.list_hailtone_contacts()'::regprocedure) <> '' then
        raise exception 'contact directory RPC accepts caller-supplied lookup parameters';
    end if;
    if pg_get_function_result('public.list_hailtone_contacts()'::regprocedure)
        <> 'TABLE(contact_invite_id uuid, contact_user_id uuid, display_name text, avatar_path text, blocked_by_me boolean, blocked_me boolean)' then
        raise exception 'contact directory RPC exposes an unexpected result projection';
    end if;
end;
$$;

set local role anon;
do $$
begin
    begin
        perform * from public.list_hailtone_contacts();
        raise exception 'anonymous user listed HailTone contacts';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', '', true);
set local role authenticated;
do $$
begin
    begin
        perform * from public.list_hailtone_contacts();
        raise exception 'authenticated role without auth.uid() listed contacts';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.unverified'), true);
set local role authenticated;
do $$
begin
    begin
        perform * from public.create_hailtone_contact_invite();
        raise exception 'unverified user created a contact invitation';
    exception when insufficient_privilege then
        null;
    end;
    if exists (select 1 from public.list_hailtone_contacts()) then
        raise exception 'unverified user enumerated unlinked identities';
    end if;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.owner'), true);
set local role authenticated;
do $$
declare
    created_invite record;
    attempt integer;
begin
    select * into created_invite from public.create_hailtone_contact_invite();
    if created_invite.contact_invite_id is null
        or created_invite.invitation_code !~ '^[0-9a-f]{64}$' then
        raise exception 'contact invite did not return an opaque 256-bit code';
    end if;
    perform set_config('test.contact.invite_id', created_invite.contact_invite_id::text, true);
    perform set_config('test.contact.invite_code', created_invite.invitation_code, true);

    begin
        perform * from public.accept_hailtone_contact_invite(created_invite.invitation_code);
        raise exception 'inviter accepted their own invitation';
    exception when invalid_parameter_value then
        null;
    end;

    for attempt in 2..20 loop
        perform * from public.create_hailtone_contact_invite();
    end loop;
    begin
        perform * from public.create_hailtone_contact_invite();
        raise exception 'per-user invitation rate limit was bypassed';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.unverified'), true);
set local role authenticated;
do $$
begin
    begin
        perform * from public.accept_hailtone_contact_invite(current_setting('test.contact.invite_code'));
        raise exception 'unverified user accepted a contact invitation';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.peer'), true);
set local role authenticated;
do $$
declare
    accepted_contact record;
    linked_contact jsonb;
    created_conversation_id uuid;
begin
    begin
        perform * from public.accept_hailtone_contact_invite(repeat('0', 64));
        raise exception 'arbitrary invalid invite code was accepted';
    exception when invalid_parameter_value then
        null;
    end;

    select * into accepted_contact
    from public.accept_hailtone_contact_invite(current_setting('test.contact.invite_code'));
    if accepted_contact.contact_user_id <> current_setting('test.contact.owner')::uuid
        or accepted_contact.display_name <> 'Invite Owner' then
        raise exception 'invite acceptance did not return only the verified linked profile';
    end if;
    select created.conversation_id into created_conversation_id
    from public.get_or_create_direct_conversation(current_setting('test.contact.owner')::uuid) as created;
    perform set_config('test.contact.conversation_id', created_conversation_id::text, true);
    perform public.send_hailtone_olm_envelopes(
        created_conversation_id,
        gen_random_uuid(),
        'contact-peer-device',
        'm.room.encrypted',
        jsonb_build_object(
            public.hailtone_matrix_user_id(current_setting('test.contact.owner')::uuid),
            jsonb_build_object('contact-owner-device', jsonb_build_object('algorithm', 'm.olm.v1.curve25519-aes-sha2', 'ciphertext', '{}'::jsonb))
        )
    );

    select to_jsonb(contact_row) into linked_contact
    from public.list_hailtone_contacts() as contact_row
    where contact_row.contact_user_id = current_setting('test.contact.owner')::uuid;
    if linked_contact is null
        or coalesce((select array_agg(key order by key) from jsonb_object_keys(linked_contact) as keys(key)), array[]::text[])
            <> array['avatar_path', 'blocked_by_me', 'blocked_me', 'contact_invite_id', 'contact_user_id', 'display_name']::text[]
        or linked_contact ->> 'blocked_by_me' <> 'false'
        or linked_contact ->> 'blocked_me' <> 'false'
        or linked_contact ?| array['phone', 'email', 'access_token', 'invitation_code'] then
        raise exception 'linked identity result was not minimal';
    end if;

    select * into accepted_contact
    from public.accept_hailtone_contact_invite(current_setting('test.contact.invite_code'));
    if accepted_contact.contact_invite_id <> current_setting('test.contact.invite_id')::uuid
        or accepted_contact.contact_user_id <> current_setting('test.contact.owner')::uuid then
        raise exception 'same redeemer could not safely retry invite acceptance';
    end if;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.unrelated'), true);
set local role authenticated;
do $$
begin
    if exists (select 1 from public.list_hailtone_contacts()) then
        raise exception 'unrelated account enumerated another user contact links';
    end if;
    begin
        perform * from public.accept_hailtone_contact_invite(current_setting('test.contact.invite_code'));
        raise exception 'another account reused an accepted invitation';
    exception when invalid_parameter_value then
        null;
    end;
    begin
        perform public.block_hailtone_contact(current_setting('test.contact.peer')::uuid);
        raise exception 'unrelated account blocked an unlinked user';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform * from public.create_call_session(gen_random_uuid(), current_setting('test.contact.peer')::uuid);
        raise exception 'unlinked user created a call invitation';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform * from public.get_or_create_direct_conversation(current_setting('test.contact.peer')::uuid);
        raise exception 'unlinked user created a direct conversation';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', current_setting('test.contact.peer'), true);
set local role authenticated;
select public.block_hailtone_contact(current_setting('test.contact.owner')::uuid);
do $$
declare
    blocked_contact jsonb;
begin
    select to_jsonb(contact_row) into blocked_contact
    from public.list_hailtone_contacts() as contact_row
    where contact_row.contact_user_id = current_setting('test.contact.owner')::uuid;
    if blocked_contact ->> 'blocked_by_me' <> 'true' then
        raise exception 'block did not persist for the authenticated blocker';
    end if;
    begin
        perform * from public.create_call_session(gen_random_uuid(), current_setting('test.contact.owner')::uuid);
        raise exception 'blocked user initiated a call invitation';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform * from public.get_or_create_direct_conversation(current_setting('test.contact.owner')::uuid);
        raise exception 'blocked user created a direct conversation';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform public.send_hailtone_olm_envelopes(
            current_setting('test.contact.conversation_id')::uuid,
            gen_random_uuid(),
            'contact-peer-device',
            'm.room.encrypted',
            jsonb_build_object(
                public.hailtone_matrix_user_id(current_setting('test.contact.owner')::uuid),
                jsonb_build_object('contact-owner-device', jsonb_build_object('algorithm', 'm.olm.v1.curve25519-aes-sha2', 'ciphertext', '{}'::jsonb))
            )
        );
        raise exception 'blocked user sent an encrypted envelope in an existing conversation';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;
select set_config('request.jwt.claim.sub', current_setting('test.contact.owner'), true);
set local role authenticated;
do $$
begin
    begin
        perform * from public.create_call_session(
            gen_random_uuid(), current_setting('test.contact.peer')::uuid
        );
        raise exception 'caller created a call after the callee blocked them';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;
reset role;
select set_config('request.jwt.claim.sub', current_setting('test.contact.peer'), true);
set local role authenticated;
select public.unblock_hailtone_contact(current_setting('test.contact.owner')::uuid);
do $$
begin
    if exists (
        select 1 from public.list_hailtone_contacts()
        where contact_user_id = current_setting('test.contact.owner')::uuid and blocked_by_me
    ) then
        raise exception 'unblock did not clear the authenticated block';
    end if;
    perform * from public.get_or_create_direct_conversation(current_setting('test.contact.owner')::uuid);
end;
$$;
reset role;

select set_config('request.jwt.claim.sub', '', true);
set local role anon;
do $$
begin
    if has_table_privilege('anon', 'public.hailtone_contact_invites', 'SELECT')
        or has_table_privilege('anon', 'public.hailtone_contact_links', 'SELECT')
        or has_table_privilege('anon', 'public.hailtone_user_blocks', 'SELECT')
        or has_function_privilege('anon', 'public.block_hailtone_contact(uuid)', 'EXECUTE')
        or has_function_privilege('anon', 'public.unblock_hailtone_contact(uuid)', 'EXECUTE') then
        raise exception 'anonymous role has contact identity or blocking access';
    end if;
end;
$$;

rollback;

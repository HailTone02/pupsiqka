begin;

create temporary table messaging_test_ids on commit drop as
select gen_random_uuid() as user_a, gen_random_uuid() as user_b, gen_random_uuid() as user_c;

insert into auth.users (id, email)
select user_a, user_a::text || '@messaging-test.invalid' from messaging_test_ids
union all
select user_b, user_b::text || '@messaging-test.invalid' from messaging_test_ids
union all
select user_c, user_c::text || '@messaging-test.invalid' from messaging_test_ids;

select set_config('test.messaging.user_a', user_a::text, true),
       set_config('test.messaging.user_b', user_b::text, true),
       set_config('test.messaging.user_c', user_c::text, true)
from messaging_test_ids;

select set_config('request.jwt.claim.sub', current_setting('test.messaging.user_a'), true);
set local role authenticated;

do $$
declare
    user_a uuid := current_setting('test.messaging.user_a')::uuid;
    user_b uuid := current_setting('test.messaging.user_b')::uuid;
    direct_id uuid;
    duplicate_id uuid;
    message_id uuid;
    retry_id uuid;
    fixture_message_id uuid := gen_random_uuid();
    expected_ids uuid[];
    page_ids uuid[];
begin
    if has_table_privilege('authenticated', 'public.conversations', 'INSERT')
        or has_table_privilege('authenticated', 'public.conversations', 'UPDATE')
        or has_table_privilege('authenticated', 'public.conversations', 'DELETE')
        or has_table_privilege('authenticated', 'public.conversation_participants', 'INSERT')
        or has_table_privilege('authenticated', 'public.conversation_participants', 'UPDATE')
        or has_table_privilege('authenticated', 'public.conversation_participants', 'DELETE')
        or has_table_privilege('authenticated', 'public.messages', 'INSERT')
        or has_table_privilege('authenticated', 'public.messages', 'UPDATE')
        or has_table_privilege('authenticated', 'public.messages', 'DELETE') then
        raise exception 'messaging grants exceed the approved client access';
    end if;

    if not (select relrowsecurity from pg_class where oid = 'public.conversations'::regclass)
        or not (select relrowsecurity from pg_class where oid = 'public.conversation_participants'::regclass)
        or not (select relrowsecurity from pg_class where oid = 'public.messages'::regclass) then
        raise exception 'messaging RLS is disabled';
    end if;

    select conversation_id into direct_id
    from public.get_or_create_direct_conversation(user_b);
    select conversation_id into duplicate_id
    from public.get_or_create_direct_conversation(user_b);
    if direct_id is null or duplicate_id <> direct_id then
        raise exception 'direct conversation was not canonical/idempotent';
    end if;
    perform set_config('test.messaging.conversation_id', direct_id::text, true);

    if (select count(*) from public.conversations where id = direct_id) <> 1
        or (select count(*) from public.conversation_participants where conversation_id = direct_id) <> 2 then
        raise exception 'direct conversation does not have exactly two participants';
    end if;
    if (select count(*) from public.list_direct_conversations(null, null, 50)
        where conversation_id = direct_id and other_user_id = user_b) <> 1 then
        raise exception 'member conversation list is incorrect';
    end if;

    select id into message_id
    from public.send_message(direct_id, fixture_message_id, 'first message');
    select id into retry_id
    from public.send_message(direct_id, fixture_message_id, 'first message');
    if retry_id <> message_id
        or (select count(*) from public.messages where conversation_id = direct_id and client_message_id = fixture_message_id) <> 1 then
        raise exception 'message retry was not idempotent';
    end if;
    if (select sender_id from public.messages where id = message_id) <> user_a then
        raise exception 'message sender was not derived from auth.uid()';
    end if;

    perform public.send_message(direct_id, gen_random_uuid(), 'second message');
    select array_agg(message.id order by message.created_at desc, message.id desc)
        into expected_ids
    from public.messages as message
    where message.conversation_id = direct_id;
    select array_agg(page.id order by page.created_at desc, page.id desc) into page_ids
    from public.get_conversation_messages(direct_id, null, null, 50) as page;
    if page_ids <> expected_ids then
        raise exception 'message page order is not stable by created_at and id';
    end if;

    if (select count(*) from public.conversations where id = direct_id) <> 1
        or (select count(*) from public.conversation_participants where conversation_id = direct_id) <> 2
        or (select count(*) from public.messages where conversation_id = direct_id) <> 2 then
        raise exception 'conversation member cannot read their conversation and messages';
    end if;

    begin
        insert into public.messages (conversation_id, sender_id, client_message_id, body)
        values (direct_id, user_b, gen_random_uuid(), 'spoofed sender');
        raise exception 'client inserted a message with a supplied sender';
    exception when insufficient_privilege then
        null;
    end;

    begin
        insert into public.conversation_participants (conversation_id, user_id)
        values (direct_id, current_setting('test.messaging.user_c')::uuid);
        raise exception 'client manipulated conversation participants';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

reset role;
select set_config('request.jwt.claim.sub', current_setting('test.messaging.user_c'), true);
set local role authenticated;

do $$
declare
    user_c uuid := current_setting('test.messaging.user_c')::uuid;
    conversation_id uuid := current_setting('test.messaging.conversation_id')::uuid;
begin
    if exists (
        select 1 from public.conversations
        where participant_a = user_c or participant_b = user_c
    ) then
        raise exception 'unrelated user can see conversations';
    end if;
    if exists (
        select 1 from public.messages as message
        join public.conversations as conversation on conversation.id = message.conversation_id
        where conversation.participant_a <> user_c and conversation.participant_b <> user_c
    ) then
        raise exception 'unrelated user can read messages';
    end if;
    if exists (
        select 1 from public.conversation_participants as participant
        where participant.conversation_id = current_setting('test.messaging.conversation_id')::uuid
    ) then
        raise exception 'unrelated user can read conversation participants';
    end if;

    begin
        perform * from public.send_message(conversation_id, gen_random_uuid(), 'unauthorized');
        raise exception 'unrelated user sent a message';
    exception when insufficient_privilege then
        null;
    end;

    begin
        insert into public.conversation_participants (conversation_id, user_id)
        values (conversation_id, user_c);
        raise exception 'unrelated user added themselves to a conversation';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

reset role;
select set_config('request.jwt.claim.sub', '', true);
set local role anon;

do $$
begin
    if has_table_privilege('anon', 'public.conversations', 'SELECT')
        or has_table_privilege('anon', 'public.conversation_participants', 'SELECT')
        or has_table_privilege('anon', 'public.messages', 'SELECT')
        or has_function_privilege('anon', 'public.get_or_create_direct_conversation(uuid)', 'EXECUTE')
        or has_function_privilege('anon', 'public.list_direct_conversations(timestamptz,uuid,integer)', 'EXECUTE')
        or has_function_privilege('anon', 'public.send_message(uuid,uuid,text)', 'EXECUTE')
        or has_function_privilege('anon', 'public.get_conversation_messages(uuid,timestamptz,uuid,integer)', 'EXECUTE') then
        raise exception 'anon has messaging access';
    end if;

    begin
        perform * from public.get_conversation_messages(gen_random_uuid());
        raise exception 'anon executed the message page RPC';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

reset role;
do $$
begin
    if not (select relrowsecurity from pg_class where oid = 'public.profiles'::regclass)
        or not has_function_privilege('authenticated', 'public.get_public_profiles(uuid[])', 'EXECUTE')
        or has_function_privilege('anon', 'public.get_public_profiles(uuid[])', 'EXECUTE')
        or pg_get_function_result('public.get_public_profiles(uuid[])'::regprocedure)
            <> 'TABLE(user_id uuid, display_name text, avatar_path text)'
        or not exists (
            select 1 from pg_policies
            where schemaname = 'realtime'
              and tablename = 'messages'
              and policyname = 'Conversation members join private message channels'
        ) then
        raise exception 'existing public profile security changed';
    end if;
end;
$$;

rollback;
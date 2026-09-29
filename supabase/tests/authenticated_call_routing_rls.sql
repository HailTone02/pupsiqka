begin;

create temporary table call_test_ids on commit drop as
select gen_random_uuid() caller_id, gen_random_uuid() callee_id, gen_random_uuid() unrelated_id;

insert into auth.users (id, email)
select caller_id, caller_id::text || '@call-test.invalid' from call_test_ids
union all select callee_id, callee_id::text || '@call-test.invalid' from call_test_ids
union all select unrelated_id, unrelated_id::text || '@call-test.invalid' from call_test_ids;

select set_config('test.call.caller', caller_id::text, true),
       set_config('test.call.callee', callee_id::text, true),
       set_config('test.call.unrelated', unrelated_id::text, true)
from call_test_ids;
select set_config('request.jwt.claim.sub', current_setting('test.call.caller'), true);
set local role authenticated;

do $$
declare
    caller_id uuid := current_setting('test.call.caller')::uuid;
    callee_id uuid := current_setting('test.call.callee')::uuid;
    requested_call_id uuid := gen_random_uuid();
    cancelled_call_id uuid := gen_random_uuid();
    failed_call_id uuid := gen_random_uuid();
    declined_call_id uuid := gen_random_uuid();
    missed_call_id uuid := gen_random_uuid();
    call_row public.call_sessions%rowtype;
    repeated_row public.call_sessions%rowtype;
begin
    if has_table_privilege('authenticated', 'public.call_sessions', 'INSERT')
        or has_table_privilege('authenticated', 'public.call_sessions', 'UPDATE')
        or has_table_privilege('authenticated', 'public.call_sessions', 'DELETE')
        or has_function_privilege('anon', 'public.create_call_session(uuid,uuid)', 'EXECUTE') then
        raise exception 'call-session grants exceed the RPC boundary';
    end if;
    if not (select relrowsecurity from pg_class where oid = 'public.call_sessions'::regclass)
        or pg_get_function_arguments('public.create_call_session(uuid,uuid)'::regprocedure)
            <> 'p_call_id uuid, p_callee_user_id uuid' then
        raise exception 'call-session RLS or server-derived caller identity is missing';
    end if;

    select * into call_row from public.create_call_session(requested_call_id, callee_id);
    select * into repeated_row from public.create_call_session(requested_call_id, callee_id);
    if call_row.id <> requested_call_id or repeated_row.id <> call_row.id
        or call_row.caller_user_id <> caller_id or call_row.callee_user_id <> callee_id or call_row.status <> 'preparing' then
        raise exception 'caller identity was not derived from auth.uid()';
    end if;
    if not exists (select 1 from public.call_sessions where id = call_row.id) then
        raise exception 'caller cannot read its own call session';
    end if;
    perform set_config('test.call.id', call_row.id::text, true);
    select * into repeated_row from public.ring_call_session(call_row.id);
    if repeated_row.status <> 'ringing' then
        raise exception 'call invite was not activated';
    end if;
    perform * from public.ring_call_session(call_row.id);
    if (select count(*) from public.call_sessions where id = call_row.id) <> 1 then
        raise exception 'ring retry created duplicate call sessions';
    end if;

    begin
        perform * from public.create_call_session(requested_call_id, current_setting('test.call.unrelated')::uuid);
        raise exception 'call participant pair was rewritten';
    exception when insufficient_privilege then
        null;
    end;

    perform * from public.create_call_session(cancelled_call_id, callee_id);
    perform * from public.ring_call_session(cancelled_call_id);
    select * into call_row from public.cancel_call_session(cancelled_call_id);
    select * into repeated_row from public.cancel_call_session(cancelled_call_id);
    if call_row.status <> 'cancelled' or repeated_row.status <> 'cancelled'
        or repeated_row.ended_at <> call_row.ended_at then
        raise exception 'cancel retry changed the terminal result';
    end if;

    perform * from public.create_call_session(failed_call_id, callee_id);
    select * into call_row from public.fail_call_session(failed_call_id, 'setup_failed');
    select * into repeated_row from public.fail_call_session(failed_call_id, 'permission_denied');
    if call_row.status <> 'failed' or call_row.failure_code <> 'setup_failed'
        or repeated_row.failure_code <> 'setup_failed' or repeated_row.ended_at <> call_row.ended_at then
        raise exception 'failed call retry rewrote its terminal result';
    end if;

    perform * from public.create_call_session(declined_call_id, callee_id);
    perform * from public.ring_call_session(declined_call_id);
    perform * from public.create_call_session(missed_call_id, callee_id);
    perform * from public.ring_call_session(missed_call_id);
    perform set_config('test.call.cancelled_id', cancelled_call_id::text, true);
    perform set_config('test.call.failed_id', failed_call_id::text, true);
    perform set_config('test.call.declined_id', declined_call_id::text, true);
    perform set_config('test.call.missed_id', missed_call_id::text, true);
end;
$$;

reset role;
select set_config('request.jwt.claim.sub', current_setting('test.call.callee'), true);
set local role authenticated;

do $$
declare
    callee_id uuid := current_setting('test.call.callee')::uuid;
    current_call_id uuid := current_setting('test.call.id')::uuid;
    declined_call_id uuid := current_setting('test.call.declined_id')::uuid;
    missed_call_id uuid := current_setting('test.call.missed_id')::uuid;
    call_row public.call_sessions%rowtype;
    repeated_row public.call_sessions%rowtype;
    history_row record;
begin
    select * into call_row from public.call_sessions where id = current_call_id;
    if not found or call_row.callee_user_id <> callee_id or call_row.status <> 'ringing' then
        raise exception 'callee cannot read its own addressed invitation';
    end if;
    select * into call_row from public.accept_call_session(current_call_id);
    select * into repeated_row from public.accept_call_session(current_call_id);
    if call_row.status <> 'accepted' or repeated_row.accepted_at <> call_row.accepted_at then
        raise exception 'accept retry was not idempotent';
    end if;
    begin
        perform * from public.cancel_call_session(current_call_id);
        raise exception 'callee cancelled a caller-owned session';
    exception when insufficient_privilege then
        null;
    end;
    perform * from public.mark_call_connected(current_call_id);
    select * into call_row from public.mark_call_connected(current_call_id);
    if call_row.status <> 'connected' or call_row.connected_at is null then
        raise exception 'connected state/timestamp missing';
    end if;
    perform * from public.finish_call_session(current_call_id);
    select * into repeated_row from public.finish_call_session(current_call_id);
    if repeated_row.status <> 'completed' or repeated_row.ended_at is null then
        raise exception 'completion/finalization failed';
    end if;

    select * into history_row from public.list_call_history(null, null, 100)
    where call_id = current_call_id;
    if not found or history_row.counterpart_user_id <> current_setting('test.call.caller')::uuid
        or history_row.direction <> 'incoming' or history_row.status <> 'completed' then
        raise exception 'callee history did not contain the incoming completed call';
    end if;

    select * into call_row from public.decline_call_session(declined_call_id);
    select * into repeated_row from public.decline_call_session(declined_call_id);
    if call_row.status <> 'declined' or repeated_row.status <> 'declined'
        or repeated_row.ended_at <> call_row.ended_at then
        raise exception 'decline retry changed the terminal result';
    end if;
    select * into call_row from public.finish_call_session(missed_call_id);
    if call_row.status <> 'missed' or call_row.connected_at is not null then
        raise exception 'unanswered call was not finalized as missed';
    end if;
end;
$$;

reset role;
update public.call_sessions
set created_at = now() - interval '1 hour',
    accepted_at = now() - interval '45 minutes',
    connected_at = now() - interval '15 minutes',
    ended_at = now() - interval '15 minutes' + interval '73 seconds'
where id = current_setting('test.call.id')::uuid;
select set_config('request.jwt.claim.sub', current_setting('test.call.caller'), true);
set local role authenticated;

do $$
declare
    current_call_id uuid := current_setting('test.call.id')::uuid;
    expected_id uuid;
    first_page_id uuid;
    second_page_id uuid;
    history_row record;
begin
    select * into history_row from public.list_call_history(null, null, 100)
    where call_id = current_call_id;
    if not found or history_row.direction <> 'outgoing' or history_row.status <> 'completed'
        or history_row.counterpart_user_id <> current_setting('test.call.callee')::uuid
        or history_row.duration_seconds <> 73 then
        raise exception 'history direction, counterpart, or connected duration is incorrect';
    end if;
    if history_row.duration_seconds = floor(extract(epoch from (history_row.ended_at - history_row.created_at)))::bigint then
        raise exception 'call duration included ringing time';
    end if;

    if not exists (
        select 1 from public.list_call_history(null, null, 100)
        where call_id = current_setting('test.call.cancelled_id')::uuid and status = 'cancelled' and duration_seconds is null
    ) or not exists (
        select 1 from public.list_call_history(null, null, 100)
        where call_id = current_setting('test.call.failed_id')::uuid and status = 'failed' and duration_seconds is null
    ) then
        raise exception 'cancelled or failed history status/duration is incorrect';
    end if;
    if not exists (
        select 1 from public.list_call_history(null, null, 100)
        where call_id = current_setting('test.call.declined_id')::uuid and status = 'declined' and duration_seconds is null
    ) or not exists (
        select 1 from public.list_call_history(null, null, 100)
        where call_id = current_setting('test.call.missed_id')::uuid and status = 'missed' and duration_seconds is null
    ) then
        raise exception 'declined or missed history status/duration is incorrect';
    end if;

    select call_session.id into expected_id
    from public.call_sessions as call_session
    where current_setting('test.call.caller')::uuid in (call_session.caller_user_id, call_session.callee_user_id)
      and call_session.status in ('completed', 'declined', 'cancelled', 'missed', 'failed')
    order by call_session.created_at desc, call_session.id desc
    limit 1;
    select call_id into first_page_id from public.list_call_history(null, null, 1);
    select call_id into second_page_id
    from public.list_call_history(
        (select created_at from public.call_sessions where id = first_page_id),
        first_page_id,
        1
    );
    if first_page_id <> expected_id or second_page_id is null or second_page_id >= first_page_id then
        raise exception 'call history keyset pagination is not deterministic';
    end if;
end;
$$;

reset role;
select set_config('request.jwt.claim.sub', current_setting('test.call.unrelated'), true);
set local role authenticated;

do $$
declare
    call_id uuid := current_setting('test.call.id')::uuid;
begin
    if exists (select 1 from public.call_sessions) then
        raise exception 'unrelated user can enumerate call sessions';
    end if;
    if exists (select 1 from public.list_call_history(null, null, 100)) then
        raise exception 'unrelated user can enumerate call history';
    end if;
    begin
        perform * from public.finish_call_session(call_id);
        raise exception 'unrelated user finalized another call';
    exception when insufficient_privilege then
        null;
    end;
    begin
        perform * from public.accept_call_session(gen_random_uuid());
        raise exception 'unrelated user accepted an unrelated call';
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
    if has_table_privilege('anon', 'public.call_sessions', 'SELECT')
        or has_function_privilege('anon', 'public.create_call_session(uuid,uuid)', 'EXECUTE')
        or has_function_privilege('anon', 'public.list_pending_call_sessions()', 'EXECUTE')
        or has_function_privilege('anon', 'public.list_call_history(timestamptz,uuid,integer)', 'EXECUTE')
        or has_function_privilege('anon', 'public.accept_call_session(uuid)', 'EXECUTE') then
        raise exception 'anon has call-session access';
    end if;
    begin
        perform * from public.create_call_session(gen_random_uuid(), gen_random_uuid());
        raise exception 'anon created a call session';
    exception when insufficient_privilege then
        null;
    end;
end;
$$;

reset role;
do $$
begin
    if not exists (select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages' and policyname = 'Authenticated users receive their call inbox')
        or not exists (select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages' and policyname = 'Call inbox topics require addressed user' and permissive = 'RESTRICTIVE')
        or not exists (select 1 from pg_policies where schemaname = 'realtime' and tablename = 'messages' and policyname = 'Call participants receive private media')
                or not exists (
                        select 1 from pg_policies
                        where schemaname = 'realtime'
                            and tablename = 'messages'
                            and policyname = 'Accepted call participants send private media'
                            and with_check like '%senderUserId%'
                            and with_check like '%callerUserId%'
                            and with_check like '%calleeUserId%'
                              and with_check like '%auth.uid()%'
                              and with_check like '%callId%'
                )
                or not exists (
                        select 1 from pg_policies
                        where schemaname = 'realtime'
                            and tablename = 'messages'
                            and policyname = 'Call topics require participant for receive'
                            and permissive = 'RESTRICTIVE'
                )
                or not exists (
                        select 1 from pg_policies
                        where schemaname = 'realtime'
                            and tablename = 'messages'
                            and policyname = 'Call topics require authenticated participant sender'
                            and permissive = 'RESTRICTIVE'
                )
        or not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'call_sessions') then
        raise exception 'private call Realtime authorization/publication is incomplete';
    end if;
end;
$$;

rollback;

begin;

create table if not exists public.call_sessions (
    id uuid primary key default gen_random_uuid(),
    caller_user_id uuid not null references auth.users (id) on delete cascade,
    callee_user_id uuid not null references auth.users (id) on delete cascade,
    status text not null default 'preparing',
    created_at timestamptz not null default now(),
    accepted_at timestamptz,
    connected_at timestamptz,
    ended_at timestamptz,
    failure_code text,
    constraint call_sessions_distinct_participants check (caller_user_id <> callee_user_id),
    constraint call_sessions_status_check check (
        status in ('preparing', 'ringing', 'accepted', 'connected', 'completed', 'declined', 'cancelled', 'missed', 'failed')
    ),
    constraint call_sessions_failure_code_check check (
        failure_code is null or failure_code in ('media_failed', 'permission_denied', 'signaling_failed', 'setup_failed')
    ),
    constraint call_sessions_failure_status_check check (failure_code is null or status = 'failed'),
    constraint call_sessions_timestamp_order_check check (
        (accepted_at is null or accepted_at >= created_at)
        and (connected_at is null or connected_at >= coalesce(accepted_at, created_at))
        and (ended_at is null or ended_at >= coalesce(connected_at, accepted_at, created_at))
    ),
    constraint call_sessions_terminal_timestamp_check check (
        (status in ('completed', 'declined', 'cancelled', 'missed', 'failed')) = (ended_at is not null)
    )
);

create index if not exists call_sessions_caller_created_idx
    on public.call_sessions (caller_user_id, created_at desc, id desc);

create index if not exists call_sessions_callee_created_idx
    on public.call_sessions (callee_user_id, created_at desc, id desc);

alter table public.call_sessions enable row level security;
revoke all on table public.call_sessions from public, anon, authenticated;
grant select on table public.call_sessions to authenticated;

drop policy if exists "Call participants read their sessions" on public.call_sessions;
create policy "Call participants read their sessions"
    on public.call_sessions for select to authenticated
    using (
        caller_user_id = auth.uid()
        or (callee_user_id = auth.uid() and status <> 'preparing')
    );

create or replace function public.create_call_session(p_call_id uuid, p_callee_user_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    created_session public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_call_id is null or p_callee_user_id is null or p_callee_user_id = authenticated_user_id then
        raise exception 'Invalid call recipient' using errcode = '22023';
    end if;
    if not exists (select 1 from auth.users where id = p_callee_user_id) then
        raise exception 'Call recipient unavailable' using errcode = '22023';
    end if;

    insert into public.call_sessions (id, caller_user_id, callee_user_id, status)
    values (p_call_id, authenticated_user_id, p_callee_user_id, 'preparing')
    on conflict (id) do nothing;

    select * into created_session from public.call_sessions where id = p_call_id;
    if created_session.caller_user_id <> authenticated_user_id
        or created_session.callee_user_id <> p_callee_user_id then
        raise exception 'Call identifier belongs to a different participant pair' using errcode = '42501';
    end if;

    return next created_session;
end;
$$;

create or replace function public.list_pending_call_sessions()
returns setof public.call_sessions
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    return query
    select call_session.*
    from public.call_sessions as call_session
    where call_session.callee_user_id = authenticated_user_id
      and call_session.status = 'ringing'
    order by call_session.created_at desc, call_session.id desc
    limit 50;
end;
$$;

create or replace function public.ring_call_session(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or call_row.caller_user_id <> authenticated_user_id then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status = 'preparing' then
        update public.call_sessions set status = 'ringing' where id = p_call_id returning * into call_row;
    elsif call_row.status <> 'ringing' then
        raise exception 'Call cannot be rung in its current state' using errcode = '22023';
    end if;
    return next call_row;
end;
$$;

create or replace function public.accept_call_session(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or call_row.callee_user_id <> authenticated_user_id then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status = 'ringing' then
        update public.call_sessions
        set status = 'accepted', accepted_at = now()
        where id = p_call_id
        returning * into call_row;
    elsif call_row.status not in ('accepted', 'connected') then
        raise exception 'Call cannot be accepted in its current state' using errcode = '22023';
    end if;
    return next call_row;
end;
$$;

create or replace function public.decline_call_session(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or call_row.callee_user_id <> authenticated_user_id then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status = 'ringing' then
        update public.call_sessions set status = 'declined', ended_at = now()
        where id = p_call_id returning * into call_row;
    elsif call_row.status <> 'declined' then
        raise exception 'Call cannot be declined in its current state' using errcode = '22023';
    end if;
    return next call_row;
end;
$$;

create or replace function public.cancel_call_session(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or call_row.caller_user_id <> authenticated_user_id then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status in ('preparing', 'ringing', 'accepted') then
        update public.call_sessions set status = 'cancelled', ended_at = now()
        where id = p_call_id returning * into call_row;
    elsif call_row.status not in ('cancelled', 'declined', 'missed', 'failed', 'completed') then
        raise exception 'Call cannot be cancelled in its current state' using errcode = '22023';
    end if;
    return next call_row;
end;
$$;

create or replace function public.mark_call_connected(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or authenticated_user_id not in (call_row.caller_user_id, call_row.callee_user_id) then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status = 'accepted' then
        update public.call_sessions set status = 'connected', connected_at = coalesce(connected_at, now())
        where id = p_call_id returning * into call_row;
    elsif call_row.status <> 'connected' then
        raise exception 'Call cannot connect in its current state' using errcode = '22023';
    end if;
    return next call_row;
end;
$$;

create or replace function public.finish_call_session(p_call_id uuid)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
    final_status text;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or authenticated_user_id not in (call_row.caller_user_id, call_row.callee_user_id) then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status in ('completed', 'declined', 'cancelled', 'missed', 'failed') then
        return next call_row;
        return;
    end if;
    final_status := case
        when call_row.status = 'connected' then 'completed'
        when authenticated_user_id = call_row.caller_user_id then 'cancelled'
        else 'missed'
    end;
    update public.call_sessions set status = final_status, ended_at = now()
    where id = p_call_id returning * into call_row;
    return next call_row;
end;
$$;

create or replace function public.fail_call_session(p_call_id uuid, p_failure_code text)
returns setof public.call_sessions
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    call_row public.call_sessions%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_failure_code not in ('media_failed', 'permission_denied', 'signaling_failed', 'setup_failed') then
        raise exception 'Invalid call failure code' using errcode = '22023';
    end if;
    select * into call_row from public.call_sessions where id = p_call_id for update;
    if not found or authenticated_user_id not in (call_row.caller_user_id, call_row.callee_user_id) then
        raise exception 'Call unavailable' using errcode = '42501';
    end if;
    if call_row.status in ('completed', 'declined', 'cancelled', 'missed', 'failed') then
        return next call_row;
        return;
    end if;
    update public.call_sessions
    set status = 'failed', ended_at = now(), failure_code = p_failure_code
    where id = p_call_id returning * into call_row;
    return next call_row;
end;
$$;

create or replace function public.list_call_history(
    p_before_created_at timestamptz default null,
    p_before_call_id uuid default null,
    p_limit integer default 50
)
returns table (
    call_id uuid,
    counterpart_user_id uuid,
    direction text,
    status text,
    created_at timestamptz,
    accepted_at timestamptz,
    connected_at timestamptz,
    ended_at timestamptz,
    duration_seconds bigint
)
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_limit is null or p_limit not between 1 and 100
        or ((p_before_created_at is null) <> (p_before_call_id is null)) then
        raise exception 'Invalid call-history page request' using errcode = '22023';
    end if;

    return query
    select call_session.id,
           case
               when call_session.caller_user_id = authenticated_user_id then call_session.callee_user_id
               else call_session.caller_user_id
           end,
           case
               when call_session.caller_user_id = authenticated_user_id then 'outgoing'
               else 'incoming'
           end,
           call_session.status,
           call_session.created_at,
           call_session.accepted_at,
           call_session.connected_at,
           call_session.ended_at,
           case
               when call_session.connected_at is not null and call_session.ended_at is not null
                   then floor(extract(epoch from (call_session.ended_at - call_session.connected_at)))::bigint
               else null
           end
    from public.call_sessions as call_session
    where authenticated_user_id in (call_session.caller_user_id, call_session.callee_user_id)
      and call_session.status in ('completed', 'declined', 'cancelled', 'missed', 'failed')
      and (
          p_before_created_at is null
          or (call_session.created_at, call_session.id) < (p_before_created_at, p_before_call_id)
      )
    order by call_session.created_at desc, call_session.id desc
    limit p_limit;
end;
$$;

revoke all on function public.create_call_session(uuid, uuid) from public, anon, authenticated;
revoke all on function public.list_pending_call_sessions() from public, anon, authenticated;
revoke all on function public.ring_call_session(uuid) from public, anon, authenticated;
revoke all on function public.accept_call_session(uuid) from public, anon, authenticated;
revoke all on function public.decline_call_session(uuid) from public, anon, authenticated;
revoke all on function public.cancel_call_session(uuid) from public, anon, authenticated;
revoke all on function public.mark_call_connected(uuid) from public, anon, authenticated;
revoke all on function public.finish_call_session(uuid) from public, anon, authenticated;
revoke all on function public.fail_call_session(uuid, text) from public, anon, authenticated;
revoke all on function public.list_call_history(timestamptz, uuid, integer) from public, anon, authenticated;
grant execute on function public.create_call_session(uuid, uuid) to authenticated;
grant execute on function public.list_pending_call_sessions() to authenticated;
grant execute on function public.ring_call_session(uuid) to authenticated;
grant execute on function public.accept_call_session(uuid) to authenticated;
grant execute on function public.decline_call_session(uuid) to authenticated;
grant execute on function public.cancel_call_session(uuid) to authenticated;
grant execute on function public.mark_call_connected(uuid) to authenticated;
grant execute on function public.finish_call_session(uuid) to authenticated;
grant execute on function public.fail_call_session(uuid, text) to authenticated;
grant execute on function public.list_call_history(timestamptz, uuid, integer) to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_class
        where oid = 'realtime.messages'::regclass and relrowsecurity
    ) then
        raise exception 'Realtime message RLS must be enabled for private call channels';
    end if;
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        raise exception 'Supabase Realtime publication is unavailable';
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'call_sessions'
    ) then
        alter publication supabase_realtime add table public.call_sessions;
    end if;
end;
$$;

drop policy if exists "Authenticated users receive their call inbox" on realtime.messages;
create policy "Authenticated users receive their call inbox"
    on realtime.messages for select to authenticated
    using (
        topic ~ 'realtime:pupsikcall-inbox:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        and lower(substring(
            topic from 'realtime:pupsikcall-inbox:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
        )) = auth.uid()::text
    );

drop policy if exists "Call inbox topics require addressed user" on realtime.messages;
create policy "Call inbox topics require addressed user"
    on realtime.messages as restrictive for select to public
    using (
        topic !~ '^realtime:pupsikcall-inbox:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        or lower(substring(
            topic from '^realtime:pupsikcall-inbox:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
        )) = auth.uid()::text
    );

drop policy if exists "Call participants receive private media" on realtime.messages;
create policy "Call participants receive private media"
    on realtime.messages for select to authenticated
    using (
        topic ~ 'realtime:pupsikcall-call:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        and exists (
            select 1 from public.call_sessions as call_session
            where call_session.id::text = lower(substring(
                topic from 'realtime:pupsikcall-call:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
            ))
              and auth.uid() in (call_session.caller_user_id, call_session.callee_user_id)
        )
    );

drop policy if exists "Call topics require participant for receive" on realtime.messages;
create policy "Call topics require participant for receive"
    on realtime.messages as restrictive for select to public
    using (
        topic !~ '^realtime:pupsikcall-call:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        or exists (
            select 1 from public.call_sessions as call_session
            where call_session.id::text = lower(substring(
                topic from '^realtime:pupsikcall-call:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
            ))
              and auth.uid() in (call_session.caller_user_id, call_session.callee_user_id)
        )
    );

drop policy if exists "Accepted call participants send private media" on realtime.messages;
create policy "Accepted call participants send private media"
    on realtime.messages for insert to authenticated
    with check (
        extension = 'broadcast'
        and jsonb_typeof(payload) = 'object'
        and topic ~ 'realtime:pupsikcall-call:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        and exists (
            select 1 from public.call_sessions as call_session
            where call_session.id::text = lower(substring(
                topic from 'realtime:pupsikcall-call:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
            ))
              and payload ->> 'callId' = call_session.id::text
              and payload ->> 'callerUserId' = call_session.caller_user_id::text
              and payload ->> 'calleeUserId' = call_session.callee_user_id::text
              and payload ->> 'senderUserId' = auth.uid()::text
              and auth.uid() in (call_session.caller_user_id, call_session.callee_user_id)
              and call_session.status in ('accepted', 'connected')
              and (
                  (payload ->> 'type' = 'offer' and auth.uid() = call_session.caller_user_id)
                  or (payload ->> 'type' = 'answer' and auth.uid() = call_session.callee_user_id)
                  or payload ->> 'type' = 'ice'
              )
        )
    );

drop policy if exists "Call topics require authenticated participant sender" on realtime.messages;
create policy "Call topics require authenticated participant sender"
    on realtime.messages as restrictive for insert to public
    with check (
        topic !~ '^realtime:pupsikcall-call:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        or (
            extension = 'broadcast'
            and jsonb_typeof(payload) = 'object'
            and exists (
                select 1 from public.call_sessions as call_session
                where call_session.id::text = lower(substring(
                    topic from '^realtime:pupsikcall-call:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
                ))
                  and payload ->> 'callId' = call_session.id::text
                  and payload ->> 'callerUserId' = call_session.caller_user_id::text
                  and payload ->> 'calleeUserId' = call_session.callee_user_id::text
                  and payload ->> 'senderUserId' = auth.uid()::text
                  and auth.uid() in (call_session.caller_user_id, call_session.callee_user_id)
                  and call_session.status in ('accepted', 'connected')
                  and (
                      (payload ->> 'type' = 'offer' and auth.uid() = call_session.caller_user_id)
                      or (payload ->> 'type' = 'answer' and auth.uid() = call_session.callee_user_id)
                      or payload ->> 'type' = 'ice'
                  )
            )
        )
    );

commit;
begin;

create table if not exists public.conversations (
    id uuid primary key default gen_random_uuid(),
    participant_a uuid not null references auth.users (id) on delete cascade,
    participant_b uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint conversations_distinct_participants check (participant_a <> participant_b),
    constraint conversations_canonical_pair check (participant_a < participant_b),
    constraint conversations_unique_direct_pair unique (participant_a, participant_b)
);

create table if not exists public.conversation_participants (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    user_id uuid not null references auth.users (id) on delete cascade,
    joined_at timestamptz not null default now(),
    constraint conversation_participants_unique_member unique (conversation_id, user_id)
);

create index if not exists conversation_participants_user_conversation_idx
    on public.conversation_participants (user_id, conversation_id);

create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    sender_id uuid not null references auth.users (id) on delete cascade,
    client_message_id uuid not null,
    body text not null,
    created_at timestamptz not null default now(),
    constraint messages_body_length check (char_length(btrim(body)) between 1 and 4000),
    constraint messages_idempotency_key unique (conversation_id, sender_id, client_message_id)
);

create index if not exists messages_conversation_created_id_idx
    on public.messages (conversation_id, created_at desc, id desc);

create index if not exists messages_sender_created_idx
    on public.messages (sender_id, created_at desc);

alter table public.conversations enable row level security;
alter table public.conversation_participants enable row level security;
alter table public.messages enable row level security;

revoke all on table public.conversations from public, anon, authenticated;
revoke all on table public.conversation_participants from public, anon, authenticated;
revoke all on table public.messages from public, anon, authenticated;
grant select on table public.conversations to authenticated;
grant select on table public.conversation_participants to authenticated;
grant select on table public.messages to authenticated;

drop policy if exists "Conversation members read their conversations" on public.conversations;
create policy "Conversation members read their conversations"
    on public.conversations for select to authenticated
    using (participant_a = auth.uid() or participant_b = auth.uid());

drop policy if exists "Conversation members read participants" on public.conversation_participants;
create policy "Conversation members read participants"
    on public.conversation_participants for select to authenticated
    using (
        exists (
            select 1
            from public.conversations as conversation
            where conversation.id = conversation_participants.conversation_id
        )
    );

drop policy if exists "Conversation members read messages" on public.messages;
create policy "Conversation members read messages"
    on public.messages for select to authenticated
    using (
        exists (
            select 1
            from public.conversation_participants as participant
            where participant.conversation_id = messages.conversation_id
              and participant.user_id = auth.uid()
        )
    );

create or replace function public.get_or_create_direct_conversation(p_other_user_id uuid)
returns table (conversation_id uuid)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    lower_user_id uuid;
    higher_user_id uuid;
    direct_conversation_id uuid;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_other_user_id is null or p_other_user_id = authenticated_user_id then
        raise exception 'Invalid conversation participants' using errcode = '22023';
    end if;
    if not exists (select 1 from auth.users where id = p_other_user_id) then
        raise exception 'Recipient is unavailable' using errcode = '22023';
    end if;

    lower_user_id := least(authenticated_user_id, p_other_user_id);
    higher_user_id := greatest(authenticated_user_id, p_other_user_id);

    insert into public.conversations (participant_a, participant_b)
    values (lower_user_id, higher_user_id)
    on conflict (participant_a, participant_b) do nothing;

    select id into direct_conversation_id
    from public.conversations
    where participant_a = lower_user_id and participant_b = higher_user_id;

    insert into public.conversation_participants (conversation_id, user_id)
    values
        (direct_conversation_id, lower_user_id),
        (direct_conversation_id, higher_user_id)
    on conflict (conversation_id, user_id) do nothing;

    return query select direct_conversation_id;
end;
$$;

create or replace function public.list_direct_conversations(
    p_before_created_at timestamptz default null,
    p_before_conversation_id uuid default null,
    p_limit integer default 50
)
returns table (
    conversation_id uuid,
    other_user_id uuid,
    created_at timestamptz
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
        or ((p_before_created_at is null) <> (p_before_conversation_id is null)) then
        raise exception 'Invalid conversation page request' using errcode = '22023';
    end if;

    return query
    select conversation.id,
           case
               when conversation.participant_a = authenticated_user_id then conversation.participant_b
               else conversation.participant_a
           end,
           conversation.created_at
    from public.conversations as conversation
    where (conversation.participant_a = authenticated_user_id or conversation.participant_b = authenticated_user_id)
      and (
          p_before_created_at is null
          or (conversation.created_at, conversation.id) < (p_before_created_at, p_before_conversation_id)
      )
    order by conversation.created_at desc, conversation.id desc
    limit p_limit;
end;
$$;

create or replace function public.send_message(
    p_conversation_id uuid,
    p_client_message_id uuid,
    p_body text
)
returns table (
    id uuid,
    conversation_id uuid,
    sender_id uuid,
    client_message_id uuid,
    body text,
    created_at timestamptz
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    normalized_body text := btrim(p_body);
    saved_message public.messages%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_conversation_id is null or p_client_message_id is null
        or normalized_body is null
        or char_length(normalized_body) not between 1 and 4000 then
        raise exception 'Invalid message' using errcode = '22023';
    end if;
    if not exists (
        select 1
        from public.conversation_participants as participant
        where participant.conversation_id = p_conversation_id
          and participant.user_id = authenticated_user_id
    ) then
        raise exception 'Conversation unavailable' using errcode = '42501';
    end if;

    insert into public.messages (conversation_id, sender_id, client_message_id, body)
    values (p_conversation_id, authenticated_user_id, p_client_message_id, normalized_body)
    on conflict (conversation_id, sender_id, client_message_id) do nothing;

    select message.* into saved_message
    from public.messages as message
    where message.conversation_id = p_conversation_id
      and message.sender_id = authenticated_user_id
      and message.client_message_id = p_client_message_id;

    if saved_message.body <> normalized_body then
        raise exception 'Idempotency key was already used' using errcode = '22023';
    end if;

    return query
    select saved_message.id, saved_message.conversation_id, saved_message.sender_id,
           saved_message.client_message_id, saved_message.body, saved_message.created_at;
end;
$$;

create or replace function public.get_conversation_messages(
    p_conversation_id uuid,
    p_before_created_at timestamptz default null,
    p_before_id uuid default null,
    p_limit integer default 50
)
returns table (
    id uuid,
    conversation_id uuid,
    sender_id uuid,
    client_message_id uuid,
    body text,
    created_at timestamptz
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
    if p_conversation_id is null or p_limit is null or p_limit not between 1 and 100
        or ((p_before_created_at is null) <> (p_before_id is null)) then
        raise exception 'Invalid message page request' using errcode = '22023';
    end if;
    if not exists (
        select 1
        from public.conversation_participants as participant
        where participant.conversation_id = p_conversation_id
          and participant.user_id = authenticated_user_id
    ) then
        raise exception 'Conversation unavailable' using errcode = '42501';
    end if;

    return query
    select message.id, message.conversation_id, message.sender_id,
           message.client_message_id, message.body, message.created_at
    from public.messages as message
    where message.conversation_id = p_conversation_id
      and (
          p_before_created_at is null
          or (message.created_at, message.id) < (p_before_created_at, p_before_id)
      )
    order by message.created_at desc, message.id desc
    limit p_limit;
end;
$$;

revoke all on function public.get_or_create_direct_conversation(uuid) from public, anon, authenticated;
revoke all on function public.list_direct_conversations(timestamptz, uuid, integer) from public, anon, authenticated;
revoke all on function public.send_message(uuid, uuid, text) from public, anon, authenticated;
revoke all on function public.get_conversation_messages(uuid, timestamptz, uuid, integer) from public, anon, authenticated;
grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.list_direct_conversations(timestamptz, uuid, integer) to authenticated;
grant execute on function public.send_message(uuid, uuid, text) to authenticated;
grant execute on function public.get_conversation_messages(uuid, timestamptz, uuid, integer) to authenticated;

do $$
begin
    if not exists (
        select 1 from pg_class
        where oid = 'realtime.messages'::regclass and relrowsecurity
    ) then
        raise exception 'Realtime message RLS must be enabled for private messaging channels';
    end if;
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        raise exception 'Supabase Realtime publication is unavailable';
    end if;
    if not exists (
        select 1 from pg_publication_tables
        where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'messages'
    ) then
        alter publication supabase_realtime add table public.messages;
    end if;
end;
$$;

drop policy if exists "Conversation members join private message channels" on realtime.messages;
create policy "Conversation members join private message channels"
    on realtime.messages for select to authenticated
    using (
        topic ~ 'pupsikcall-messages:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
        and exists (
            select 1
            from public.conversation_participants as participant
            where participant.user_id = auth.uid()
              and participant.conversation_id::text = lower(substring(
                  topic from 'pupsikcall-messages:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$'
              ))
        )
    );

commit;
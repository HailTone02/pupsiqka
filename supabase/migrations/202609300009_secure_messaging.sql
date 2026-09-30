begin;

create extension if not exists pg_cron with schema pg_catalog;

create table if not exists public.hailtone_message_devices (
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    device_id text not null,
    matrix_user_id text not null,
    device_keys jsonb not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint hailtone_message_devices_pk primary key (owner_user_id, device_id),
    constraint hailtone_message_devices_id_check check (device_id ~ '^[A-Za-z0-9._=-]{1,255}$'),
    constraint hailtone_message_devices_keys_check check (jsonb_typeof(device_keys) = 'object')
);

create index if not exists hailtone_message_devices_matrix_user_idx
    on public.hailtone_message_devices (matrix_user_id);

create table if not exists public.hailtone_message_one_time_keys (
    owner_user_id uuid not null,
    device_id text not null,
    key_id text not null,
    key_material jsonb not null,
    created_at timestamptz not null default now(),
    constraint hailtone_message_one_time_keys_pk primary key (owner_user_id, device_id, key_id),
    constraint hailtone_message_one_time_keys_device_fk foreign key (owner_user_id, device_id)
        references public.hailtone_message_devices (owner_user_id, device_id) on delete cascade,
    constraint hailtone_message_one_time_keys_material_check check (jsonb_typeof(key_material) = 'object')
);

create table if not exists public.hailtone_message_envelopes (
    id uuid primary key default gen_random_uuid(),
    conversation_id uuid not null references public.conversations (id) on delete cascade,
    client_message_id uuid not null,
    sender_user_id uuid not null references auth.users (id) on delete cascade,
    sender_device_id text not null,
    recipient_user_id uuid not null references auth.users (id) on delete cascade,
    recipient_device_id text not null,
    event_type text not null check (event_type = 'm.room.encrypted'),
    ciphertext jsonb not null check (jsonb_typeof(ciphertext) = 'object'),
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days'),
    constraint hailtone_message_envelopes_idempotency_key unique
        (conversation_id, sender_user_id, client_message_id, recipient_user_id, recipient_device_id),
    constraint hailtone_message_envelopes_expiry_check check (
        expires_at > created_at and expires_at <= created_at + interval '7 days'
    )
);

create index if not exists hailtone_message_envelopes_recipient_idx
    on public.hailtone_message_envelopes (recipient_user_id, recipient_device_id, created_at, id);
create index if not exists hailtone_message_envelopes_expiry_idx
    on public.hailtone_message_envelopes (expires_at);

alter table public.hailtone_message_devices enable row level security;
alter table public.hailtone_message_one_time_keys enable row level security;
alter table public.hailtone_message_envelopes enable row level security;

revoke all on public.hailtone_message_devices from public, anon, authenticated;
revoke all on public.hailtone_message_one_time_keys from public, anon, authenticated;
revoke all on public.hailtone_message_envelopes from public, anon, authenticated;

create or replace function public.hailtone_matrix_user_id(p_user_id uuid)
returns text
language sql immutable strict
set search_path = pg_catalog
as $$
    select '@' || p_user_id::text || ':hailtone.invalid';
$$;

create or replace function public.hailtone_matrix_user_uuid(p_matrix_user_id text)
returns uuid
language plpgsql immutable strict
set search_path = pg_catalog
as $$
declare
    localpart text;
begin
    if p_matrix_user_id !~ '^@[0-9a-fA-F-]{36}:hailtone\.invalid$' then
        raise exception 'Invalid messaging identity' using errcode = '22023';
    end if;
    localpart := substring(p_matrix_user_id from 2 for 36);
    return localpart::uuid;
end;
$$;

create or replace function public.publish_hailtone_olm_keys(
    p_device_keys jsonb,
    p_one_time_keys jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    matrix_user_id text;
    device_id text;
    previous_keys jsonb;
    key_entry record;
    one_time_key_count integer;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    matrix_user_id := public.hailtone_matrix_user_id(authenticated_user_id);
    device_id := p_device_keys ->> 'device_id';
    if jsonb_typeof(p_device_keys) <> 'object'
       or jsonb_typeof(p_one_time_keys) <> 'object'
       or p_device_keys ->> 'user_id' <> matrix_user_id
       or device_id is null
       or p_device_keys ->> 'device_id' !~ '^[A-Za-z0-9._=-]{1,255}$'
       or jsonb_typeof(p_device_keys -> 'keys') <> 'object'
       or not (p_device_keys -> 'keys' ? ('ed25519:' || device_id))
       or not (p_device_keys -> 'keys' ? ('curve25519:' || device_id)) then
        raise exception 'Invalid device key upload' using errcode = '22023';
    end if;

    select device_keys into previous_keys
    from public.hailtone_message_devices
    where owner_user_id = authenticated_user_id and hailtone_message_devices.device_id = publish_hailtone_olm_keys.device_id
    for update;
    if previous_keys is not null and (
        previous_keys #>> array['keys', 'ed25519:' || device_id] <> p_device_keys #>> array['keys', 'ed25519:' || device_id]
        or previous_keys #>> array['keys', 'curve25519:' || device_id] <> p_device_keys #>> array['keys', 'curve25519:' || device_id]
    ) then
        raise exception 'Device identity changed; explicit reverification required' using errcode = '42501';
    end if;

    insert into public.hailtone_message_devices (owner_user_id, device_id, matrix_user_id, device_keys)
    values (authenticated_user_id, device_id, matrix_user_id, p_device_keys - 'unsigned')
    on conflict (owner_user_id, device_id) do update
        set device_keys = excluded.device_keys, updated_at = now();

    for key_entry in select key, value from jsonb_each(p_one_time_keys)
    loop
        if key_entry.key !~ '^[A-Za-z0-9_-]+:[A-Za-z0-9._=-]{1,255}$'
           or jsonb_typeof(key_entry.value) <> 'object' then
            raise exception 'Invalid one-time key upload' using errcode = '22023';
        end if;
        insert into public.hailtone_message_one_time_keys (owner_user_id, device_id, key_id, key_material)
        values (authenticated_user_id, device_id, key_entry.key, key_entry.value)
        on conflict (owner_user_id, device_id, key_id) do nothing;
    end loop;

    select count(*)::integer into one_time_key_count
    from public.hailtone_message_one_time_keys
    where owner_user_id = authenticated_user_id and hailtone_message_one_time_keys.device_id = publish_hailtone_olm_keys.device_id;

    return jsonb_build_object(
        'one_time_key_counts', jsonb_build_object('signed_curve25519', one_time_key_count),
        'unused_fallback_key_types', jsonb_build_array()
    );
end;
$$;

create or replace function public.query_hailtone_olm_keys(p_matrix_user_ids jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    requested_user text;
    requested_uuid uuid;
    device_map jsonb;
    all_device_keys jsonb := '{}'::jsonb;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if jsonb_typeof(p_matrix_user_ids) <> 'array' or jsonb_array_length(p_matrix_user_ids) > 50 then
        raise exception 'Invalid key query' using errcode = '22023';
    end if;
    for requested_user in select jsonb_array_elements_text(p_matrix_user_ids)
    loop
        requested_uuid := public.hailtone_matrix_user_uuid(requested_user);
        if requested_uuid <> authenticated_user_id and (
            not exists (
                select 1 from public.hailtone_contact_links
                where owner_user_id = authenticated_user_id and contact_user_id = requested_uuid
            ) or exists (
                select 1 from public.hailtone_user_blocks
                where (blocker_user_id = authenticated_user_id and blocked_user_id = requested_uuid)
                   or (blocker_user_id = requested_uuid and blocked_user_id = authenticated_user_id)
            )
        ) then
            continue;
        end if;
        select coalesce(jsonb_object_agg(device_id, device_keys), '{}'::jsonb) into device_map
        from public.hailtone_message_devices
        where owner_user_id = requested_uuid;
        all_device_keys := all_device_keys || jsonb_build_object(requested_user, device_map);
    end loop;
    return jsonb_build_object('device_keys', all_device_keys, 'failures', '{}'::jsonb);
end;
$$;

create or replace function public.claim_hailtone_olm_keys(p_one_time_key_request jsonb)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    requested_user record;
    requested_device record;
    owner_id uuid;
    claimed_id text;
    claimed_material jsonb;
    claimed_keys jsonb := '{}'::jsonb;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if jsonb_typeof(p_one_time_key_request -> 'one_time_keys') <> 'object' then
        raise exception 'Invalid one-time key claim' using errcode = '22023';
    end if;
    for requested_user in select key, value from jsonb_each(p_one_time_key_request -> 'one_time_keys')
    loop
        owner_id := public.hailtone_matrix_user_uuid(requested_user.key);
        if owner_id <> authenticated_user_id and (
            not exists (
                select 1 from public.hailtone_contact_links
                where owner_user_id = authenticated_user_id and contact_user_id = owner_id
            ) or exists (
                select 1 from public.hailtone_user_blocks
                where (blocker_user_id = authenticated_user_id and blocked_user_id = owner_id)
                   or (blocker_user_id = owner_id and blocked_user_id = authenticated_user_id)
            )
        ) then
            continue;
        end if;
        for requested_device in select key, value from jsonb_each(requested_user.value)
        loop
            claimed_id := null;
            claimed_material := null;
            select key_id, key_material into claimed_id, claimed_material
            from public.hailtone_message_one_time_keys
            where owner_user_id = owner_id
              and device_id = requested_device.key
              and split_part(key_id, ':', 1) = trim(both '"' from requested_device.value::text)
            order by created_at, key_id
            for update skip locked
            limit 1;
            if claimed_id is not null then
                delete from public.hailtone_message_one_time_keys
                where owner_user_id = owner_id and device_id = requested_device.key and key_id = claimed_id;
                claimed_keys := jsonb_set(claimed_keys, array[requested_user.key, requested_device.key, claimed_id], claimed_material, true);
            end if;
        end loop;
    end loop;
    return jsonb_build_object('one_time_keys', claimed_keys, 'failures', '{}'::jsonb);
end;
$$;

create or replace function public.send_hailtone_olm_envelopes(
    p_conversation_id uuid,
    p_client_message_id uuid,
    p_sender_device_id text,
    p_event_type text,
    p_messages jsonb
)
returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    other_user_id uuid;
    recipient record;
    recipient_uuid uuid;
    recipient_devices record;
    created_event_id text := '$' || gen_random_uuid()::text || ':hailtone.invalid';
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
     if p_conversation_id is null or p_client_message_id is null or p_sender_device_id is null
       or p_event_type <> 'm.room.encrypted'
         or jsonb_typeof(p_messages) <> 'object'
         or jsonb_object_length(p_messages) not between 1 and 2 then
        raise exception 'Invalid encrypted delivery request' using errcode = '22023';
    end if;
    if not exists (
        select 1 from public.hailtone_message_devices
        where owner_user_id = authenticated_user_id and device_id = p_sender_device_id
    ) then
        raise exception 'Sender device unavailable' using errcode = '42501';
    end if;
    select case when participant_a = authenticated_user_id then participant_b else participant_a end
      into other_user_id from public.conversations
      where id = p_conversation_id and authenticated_user_id in (participant_a, participant_b);
    if other_user_id is null
       or not exists (select 1 from public.hailtone_contact_links where owner_user_id = authenticated_user_id and contact_user_id = other_user_id)
       or exists (
            select 1 from public.hailtone_user_blocks
            where (blocker_user_id = authenticated_user_id and blocked_user_id = other_user_id)
               or (blocker_user_id = other_user_id and blocked_user_id = authenticated_user_id)
       ) then
        raise exception 'Conversation unavailable' using errcode = '42501';
    end if;
    delete from public.hailtone_message_envelopes where expires_at <= now();

    for recipient in select key, value from jsonb_each(p_messages)
    loop
        recipient_uuid := public.hailtone_matrix_user_uuid(recipient.key);
        if recipient_uuid <> other_user_id and recipient_uuid <> authenticated_user_id then
            raise exception 'Envelope recipient does not match conversation' using errcode = '42501';
        end if;
        if recipient_uuid <> authenticated_user_id and not exists (
            select 1 from public.hailtone_contact_links
            where owner_user_id = authenticated_user_id and contact_user_id = recipient_uuid
        ) then
            raise exception 'Recipient unavailable' using errcode = '42501';
        end if;
        if recipient_uuid <> authenticated_user_id and exists (
            select 1 from public.hailtone_user_blocks
            where (blocker_user_id = authenticated_user_id and blocked_user_id = recipient_uuid)
               or (blocker_user_id = recipient_uuid and blocked_user_id = authenticated_user_id)
        ) then
            raise exception 'Recipient unavailable' using errcode = '42501';
        end if;
        if jsonb_typeof(recipient.value) <> 'object' then
            raise exception 'Invalid device envelope map' using errcode = '22023';
        end if;
        for recipient_devices in select key, value from jsonb_each(recipient.value)
        loop
            if not exists (
                select 1 from public.hailtone_message_devices
                where owner_user_id = recipient_uuid and device_id = recipient_devices.key
            ) or jsonb_typeof(recipient_devices.value) <> 'object' then
                raise exception 'Recipient device unavailable' using errcode = '42501';
            end if;
            insert into public.hailtone_message_envelopes (
                conversation_id, client_message_id, sender_user_id, sender_device_id,
                recipient_user_id, recipient_device_id, event_type, ciphertext
            ) values (
                p_conversation_id, p_client_message_id, authenticated_user_id, p_sender_device_id,
                recipient_uuid, recipient_devices.key, p_event_type, recipient_devices.value
            ) on conflict on constraint hailtone_message_envelopes_idempotency_key do nothing;
        end loop;
    end loop;
    return jsonb_build_object('event_id', created_event_id);
end;
$$;

create or replace function public.fetch_hailtone_olm_envelopes(p_device_id text)
returns table (
    id uuid,
    conversation_id uuid,
    sender_user_id uuid,
    sender_device_id text,
    event_type text,
    ciphertext jsonb,
    created_at timestamptz
)
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.hailtone_message_devices
        where owner_user_id = authenticated_user_id and device_id = p_device_id
    ) then
        raise exception 'Recipient device unavailable' using errcode = '42501';
    end if;
    delete from public.hailtone_message_envelopes where expires_at <= now();
    return query
    select envelope.id, envelope.conversation_id, envelope.sender_user_id,
           envelope.sender_device_id, envelope.event_type, envelope.ciphertext, envelope.created_at
    from public.hailtone_message_envelopes as envelope
    where envelope.recipient_user_id = authenticated_user_id
      and envelope.recipient_device_id = p_device_id
      and envelope.expires_at > now()
      and (envelope.sender_user_id = authenticated_user_id or exists (
          select 1 from public.hailtone_contact_links
          where owner_user_id = authenticated_user_id and contact_user_id = envelope.sender_user_id
      ))
      and not exists (
          select 1 from public.hailtone_user_blocks
          where (blocker_user_id = authenticated_user_id and blocked_user_id = envelope.sender_user_id)
             or (blocker_user_id = envelope.sender_user_id and blocked_user_id = authenticated_user_id)
      )
    order by envelope.created_at, envelope.id;
end;
$$;

create or replace function public.ack_hailtone_olm_envelope(p_envelope_id uuid, p_device_id text)
returns boolean
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    authenticated_user_id uuid := auth.uid();
    removed_count integer;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    delete from public.hailtone_message_envelopes
    where id = p_envelope_id
      and recipient_user_id = authenticated_user_id
      and recipient_device_id = p_device_id;
    get diagnostics removed_count = row_count;
    return removed_count = 1;
end;
$$;

create or replace function public.list_direct_conversations(
    p_before_created_at timestamptz default null,
    p_before_conversation_id uuid default null,
    p_limit integer default 50
)
returns table (conversation_id uuid, other_user_id uuid, created_at timestamptz)
language plpgsql stable security definer
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
           case when conversation.participant_a = authenticated_user_id then conversation.participant_b else conversation.participant_a end,
           conversation.created_at
    from public.conversations as conversation
    where authenticated_user_id in (conversation.participant_a, conversation.participant_b)
      and (p_before_created_at is null or (conversation.created_at, conversation.id) < (p_before_created_at, p_before_conversation_id))
      and exists (
          select 1 from public.hailtone_contact_links as link
          where link.owner_user_id = authenticated_user_id
            and link.contact_user_id = case when conversation.participant_a = authenticated_user_id then conversation.participant_b else conversation.participant_a end
      )
      and not exists (
          select 1 from public.hailtone_user_blocks as user_block
          where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = case when conversation.participant_a = authenticated_user_id then conversation.participant_b else conversation.participant_a end)
             or (user_block.blocker_user_id = case when conversation.participant_a = authenticated_user_id then conversation.participant_b else conversation.participant_a end and user_block.blocked_user_id = authenticated_user_id)
      )
    order by conversation.created_at desc, conversation.id desc
    limit p_limit;
end;
$$;

revoke all on function public.publish_hailtone_olm_keys(jsonb, jsonb) from public, anon, authenticated;
revoke all on function public.query_hailtone_olm_keys(jsonb) from public, anon, authenticated;
revoke all on function public.claim_hailtone_olm_keys(jsonb) from public, anon, authenticated;
revoke all on function public.send_hailtone_olm_envelopes(uuid, uuid, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.fetch_hailtone_olm_envelopes(text) from public, anon, authenticated;
revoke all on function public.ack_hailtone_olm_envelope(uuid, text) from public, anon, authenticated;
grant execute on function public.publish_hailtone_olm_keys(jsonb, jsonb) to authenticated;
grant execute on function public.query_hailtone_olm_keys(jsonb) to authenticated;
grant execute on function public.claim_hailtone_olm_keys(jsonb) to authenticated;
grant execute on function public.send_hailtone_olm_envelopes(uuid, uuid, text, text, jsonb) to authenticated;
grant execute on function public.fetch_hailtone_olm_envelopes(text) to authenticated;
grant execute on function public.ack_hailtone_olm_envelope(uuid, text) to authenticated;
grant execute on function public.list_direct_conversations(timestamptz, uuid, integer) to authenticated;
revoke all on function public.hailtone_matrix_user_id(uuid) from public, anon, authenticated;
revoke all on function public.hailtone_matrix_user_uuid(text) from public, anon, authenticated;

do $$
begin
    if exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and schemaname = 'public' and tablename = 'messages') then
        alter publication supabase_realtime drop table public.messages;
    end if;
    if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
        raise exception 'Supabase Realtime publication is unavailable';
    end if;
end;
$$;

drop policy if exists "Conversation members join private message channels" on realtime.messages;
drop policy if exists "Recipients join private encrypted delivery channels" on realtime.messages;

drop function if exists public.get_conversation_messages(uuid, timestamptz, uuid, integer);
drop function if exists public.send_message(uuid, uuid, text);
drop table if exists public.messages;

do $$
begin
    if not exists (select 1 from cron.job where jobname = 'hailtone-expire-olm-envelopes') then
        perform cron.schedule(
            'hailtone-expire-olm-envelopes',
            '*/15 * * * *',
            'delete from public.hailtone_message_envelopes where expires_at <= now()'
        );
    end if;
end;
$$;

commit;
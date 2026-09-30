begin;

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
       or p_event_type <> 'm.room.encrypted' or jsonb_typeof(p_messages) <> 'object' then
        raise exception 'Invalid encrypted delivery request' using errcode = '22023';
    end if;
    if (select count(*) from jsonb_object_keys(p_messages)) not between 1 and 2 then
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

revoke all on function public.send_hailtone_olm_envelopes(uuid, uuid, text, text, jsonb) from public, anon, authenticated;
grant execute on function public.send_hailtone_olm_envelopes(uuid, uuid, text, text, jsonb) to authenticated;

commit;
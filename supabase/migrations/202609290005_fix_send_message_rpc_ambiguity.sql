begin;

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
    on conflict on constraint messages_idempotency_key do nothing;

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

revoke all on function public.send_message(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.send_message(uuid, uuid, text) to authenticated;

commit;

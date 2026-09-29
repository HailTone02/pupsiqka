begin;

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
    if not exists (
        select 1 from auth.users as recipient where recipient.id = p_other_user_id
    ) then
        raise exception 'Recipient is unavailable' using errcode = '22023';
    end if;

    lower_user_id := least(authenticated_user_id, p_other_user_id);
    higher_user_id := greatest(authenticated_user_id, p_other_user_id);

    insert into public.conversations (participant_a, participant_b)
    values (lower_user_id, higher_user_id)
    on conflict (participant_a, participant_b) do nothing;

    select conversation.id into direct_conversation_id
    from public.conversations as conversation
    where conversation.participant_a = lower_user_id
      and conversation.participant_b = higher_user_id;

    insert into public.conversation_participants (conversation_id, user_id)
    values
        (direct_conversation_id, lower_user_id),
        (direct_conversation_id, higher_user_id)
    on conflict on constraint conversation_participants_unique_member do nothing;

    return query select direct_conversation_id;
end;
$$;

revoke all on function public.get_or_create_direct_conversation(uuid) from public, anon, authenticated;
grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;

commit;

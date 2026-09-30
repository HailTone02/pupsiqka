begin;

create extension if not exists pgcrypto with schema extensions;

create table if not exists public.hailtone_contact_invites (
    id uuid primary key default gen_random_uuid(),
    token_hash bytea not null unique,
    inviter_user_id uuid not null references auth.users (id) on delete cascade,
    accepted_by_user_id uuid references auth.users (id) on delete set null,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    accepted_at timestamptz,
    constraint hailtone_contact_invites_expiry_check check (expires_at > created_at),
    constraint hailtone_contact_invites_acceptance_check check (
        (accepted_by_user_id is null) = (accepted_at is null)
    ),
    constraint hailtone_contact_invites_not_self_check check (
        accepted_by_user_id is null or accepted_by_user_id <> inviter_user_id
    )
);

create index if not exists hailtone_contact_invites_inviter_created_idx
    on public.hailtone_contact_invites (inviter_user_id, created_at desc);

create table if not exists public.hailtone_contact_links (
    owner_user_id uuid not null references auth.users (id) on delete cascade,
    contact_user_id uuid not null references auth.users (id) on delete cascade,
    invitation_id uuid not null references public.hailtone_contact_invites (id) on delete cascade,
    linked_at timestamptz not null default now(),
    constraint hailtone_contact_links_pk primary key (owner_user_id, invitation_id),
    constraint hailtone_contact_links_not_self_check check (owner_user_id <> contact_user_id),
    constraint hailtone_contact_links_invitation_unique unique (owner_user_id, invitation_id)
);

create index if not exists hailtone_contact_links_contact_owner_idx
    on public.hailtone_contact_links (contact_user_id, owner_user_id);

create table if not exists public.hailtone_user_blocks (
    blocker_user_id uuid not null references auth.users (id) on delete cascade,
    blocked_user_id uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint hailtone_user_blocks_pk primary key (blocker_user_id, blocked_user_id),
    constraint hailtone_user_blocks_not_self_check check (blocker_user_id <> blocked_user_id)
);

alter table public.hailtone_contact_invites enable row level security;
alter table public.hailtone_contact_links enable row level security;
alter table public.hailtone_user_blocks enable row level security;
revoke all on table public.hailtone_contact_invites from public, anon, authenticated;
revoke all on table public.hailtone_contact_links from public, anon, authenticated;
revoke all on table public.hailtone_user_blocks from public, anon, authenticated;

create or replace function public.create_hailtone_contact_invite()
returns table (contact_invite_id uuid, invitation_code text)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
    authenticated_user_id uuid := auth.uid();
    token text;
    created_invite_id uuid;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    perform 1
    from auth.users as auth_user
    where auth_user.id = authenticated_user_id
      and auth_user.phone_confirmed_at is not null
    for update;
    if not found then
        raise exception 'Verified phone required' using errcode = '42501';
    end if;

    if (select count(*)
        from public.hailtone_contact_invites as invite
        where invite.inviter_user_id = authenticated_user_id
          and invite.created_at > now() - interval '24 hours') >= 20 then
        raise exception 'Contact invitation limit reached' using errcode = '42501';
    end if;

    token := encode(gen_random_bytes(32), 'hex');
    insert into public.hailtone_contact_invites (token_hash, inviter_user_id, expires_at)
    values (digest(convert_to(token, 'UTF8'), 'sha256'), authenticated_user_id, now() + interval '24 hours')
    returning id into created_invite_id;

    return query select created_invite_id, token;
end;
$$;

create or replace function public.accept_hailtone_contact_invite(p_invitation_code text)
returns table (
    contact_invite_id uuid,
    contact_user_id uuid,
    display_name text,
    avatar_path text
)
language plpgsql
security definer
set search_path = pg_catalog, public, extensions
as $$
declare
    authenticated_user_id uuid := auth.uid();
    invite_row public.hailtone_contact_invites%rowtype;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if not exists (
        select 1 from auth.users as auth_user
        where auth_user.id = authenticated_user_id
          and auth_user.phone_confirmed_at is not null
    ) then
        raise exception 'Verified phone required' using errcode = '42501';
    end if;
    if p_invitation_code is null or p_invitation_code !~ '^[0-9a-fA-F]{64}$' then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;

    select * into invite_row
    from public.hailtone_contact_invites as invite
    where invite.token_hash = digest(convert_to(lower(p_invitation_code), 'UTF8'), 'sha256')
            and (invite.accepted_by_user_id is null or invite.accepted_by_user_id = authenticated_user_id)
            and (invite.expires_at > now() or invite.accepted_by_user_id = authenticated_user_id)
    for update;
    if not found or invite_row.inviter_user_id = authenticated_user_id then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;
    if not exists (
        select 1 from auth.users as inviter
        where inviter.id = invite_row.inviter_user_id
          and inviter.phone_confirmed_at is not null
    ) then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = invite_row.inviter_user_id)
           or (user_block.blocker_user_id = invite_row.inviter_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;

    if invite_row.accepted_by_user_id is null then
        update public.hailtone_contact_invites as invite
        set accepted_by_user_id = authenticated_user_id,
            accepted_at = now()
        where invite.id = invite_row.id;
    end if;

    insert into public.hailtone_contact_links (owner_user_id, contact_user_id, invitation_id)
    values
        (invite_row.inviter_user_id, authenticated_user_id, invite_row.id),
        (authenticated_user_id, invite_row.inviter_user_id, invite_row.id)
        on conflict on constraint hailtone_contact_links_pk do nothing;

    return query
        select link.invitation_id, profile.user_id, profile.display_name, profile.avatar_path
        from public.hailtone_contact_links as link
        join public.profiles as profile on profile.user_id = link.contact_user_id
        where link.owner_user_id = authenticated_user_id
            and link.contact_user_id = invite_row.inviter_user_id
            and link.invitation_id = invite_row.id
            and profile.user_id = invite_row.inviter_user_id;
end;
$$;

create or replace function public.list_hailtone_contacts()
returns table (
    contact_invite_id uuid,
    contact_user_id uuid,
    display_name text,
    avatar_path text,
    blocked_by_me boolean,
    blocked_me boolean
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
    return query
    select link.invitation_id,
           profile.user_id,
           profile.display_name,
           profile.avatar_path,
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = authenticated_user_id
                 and user_block.blocked_user_id = link.contact_user_id
           ),
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = link.contact_user_id
                 and user_block.blocked_user_id = authenticated_user_id
           )
    from public.hailtone_contact_links as link
    join public.profiles as profile on profile.user_id = link.contact_user_id
    where link.owner_user_id = authenticated_user_id
    order by link.linked_at desc, link.contact_user_id;
end;
$$;

create or replace function public.block_hailtone_contact(p_contact_user_id uuid)
returns void
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
    if p_contact_user_id is null or p_contact_user_id = authenticated_user_id
        or not exists (
            select 1 from public.hailtone_contact_links as link
            where link.owner_user_id = authenticated_user_id
              and link.contact_user_id = p_contact_user_id
        ) then
        raise exception 'Contact is unavailable' using errcode = '42501';
    end if;

    insert into public.hailtone_user_blocks (blocker_user_id, blocked_user_id)
    values (authenticated_user_id, p_contact_user_id)
    on conflict (blocker_user_id, blocked_user_id) do nothing;
end;
$$;

create or replace function public.unblock_hailtone_contact(p_contact_user_id uuid)
returns void
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
    if p_contact_user_id is null or p_contact_user_id = authenticated_user_id
        or not exists (
            select 1 from public.hailtone_contact_links as link
            where link.owner_user_id = authenticated_user_id
              and link.contact_user_id = p_contact_user_id
        ) then
        raise exception 'Contact is unavailable' using errcode = '42501';
    end if;

    delete from public.hailtone_user_blocks as user_block
    where user_block.blocker_user_id = authenticated_user_id
      and user_block.blocked_user_id = p_contact_user_id;
end;
$$;

revoke all on function public.create_hailtone_contact_invite() from public, anon, authenticated;
revoke all on function public.accept_hailtone_contact_invite(text) from public, anon, authenticated;
revoke all on function public.list_hailtone_contacts() from public, anon, authenticated;
revoke all on function public.block_hailtone_contact(uuid) from public, anon, authenticated;
revoke all on function public.unblock_hailtone_contact(uuid) from public, anon, authenticated;
grant execute on function public.create_hailtone_contact_invite() to authenticated;
grant execute on function public.accept_hailtone_contact_invite(text) to authenticated;
grant execute on function public.list_hailtone_contacts() to authenticated;
grant execute on function public.block_hailtone_contact(uuid) to authenticated;
grant execute on function public.unblock_hailtone_contact(uuid) to authenticated;

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
    if not exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id
          and link.contact_user_id = p_callee_user_id
    ) or exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = p_callee_user_id)
           or (user_block.blocker_user_id = p_callee_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
        raise exception 'Call recipient unavailable' using errcode = '42501';
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
    if not exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id
          and link.contact_user_id = call_row.callee_user_id
    ) or exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = call_row.callee_user_id)
           or (user_block.blocker_user_id = call_row.callee_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
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
    if not exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id
          and link.contact_user_id = p_other_user_id
    ) or exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = p_other_user_id)
           or (user_block.blocker_user_id = p_other_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
        raise exception 'Recipient is unavailable' using errcode = '42501';
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
    on conflict on constraint conversation_participants_unique_member do nothing;

    return query select direct_conversation_id;
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
    other_user_id uuid;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_conversation_id is null or p_client_message_id is null
        or normalized_body is null
        or char_length(normalized_body) not between 1 and 4000 then
        raise exception 'Invalid message' using errcode = '22023';
    end if;
    select case
        when conversation.participant_a = authenticated_user_id then conversation.participant_b
        else conversation.participant_a
    end into other_user_id
    from public.conversations as conversation
    where conversation.id = p_conversation_id
      and authenticated_user_id in (conversation.participant_a, conversation.participant_b);
    if other_user_id is null or not exists (
        select 1 from public.conversation_participants as participant
        where participant.conversation_id = p_conversation_id
          and participant.user_id = authenticated_user_id
    ) then
        raise exception 'Conversation unavailable' using errcode = '42501';
    end if;
    if not exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id
          and link.contact_user_id = other_user_id
    ) or exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = other_user_id)
           or (user_block.blocker_user_id = other_user_id and user_block.blocked_user_id = authenticated_user_id)
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

revoke all on function public.get_or_create_direct_conversation(uuid) from public, anon, authenticated;
revoke all on function public.send_message(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.get_or_create_direct_conversation(uuid) to authenticated;
grant execute on function public.send_message(uuid, uuid, text) to authenticated;

commit;
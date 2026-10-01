begin;

create table if not exists public.hailtone_contact_request_send_events (
    event_id uuid primary key default gen_random_uuid(),
    requester_user_id uuid not null references auth.users (id) on delete cascade,
    recipient_user_id uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint hailtone_contact_request_send_events_not_self_check check (requester_user_id <> recipient_user_id)
);
alter table public.hailtone_contact_request_send_events enable row level security;
revoke all on table public.hailtone_contact_request_send_events from public, anon, authenticated;
insert into public.hailtone_contact_request_send_events (requester_user_id, recipient_user_id, created_at)
select requester_user_id, recipient_user_id, created_at
from public.hailtone_contact_requests
where created_at > now() - interval '24 hours';
create index if not exists hailtone_contact_request_send_events_requester_created_idx
    on public.hailtone_contact_request_send_events (requester_user_id, created_at desc);

delete from public.hailtone_contact_requests as request
where request.accepted_at is null
  and exists (
      select 1 from public.hailtone_contact_links as link
      where (link.owner_user_id = request.requester_user_id and link.contact_user_id = request.recipient_user_id)
         or (link.owner_user_id = request.recipient_user_id and link.contact_user_id = request.requester_user_id)
  );
with duplicate_requests as (
    select ctid, row_number() over (
        partition by least(requester_user_id, recipient_user_id), greatest(requester_user_id, recipient_user_id)
        order by created_at, request_id
    ) as duplicate_rank
    from public.hailtone_contact_requests where accepted_at is null
)
delete from public.hailtone_contact_requests as request
using duplicate_requests
where request.ctid = duplicate_requests.ctid and duplicate_requests.duplicate_rank > 1;
create unique index if not exists hailtone_contact_requests_pending_unordered_pair_uidx
    on public.hailtone_contact_requests (
        least(requester_user_id, recipient_user_id), greatest(requester_user_id, recipient_user_id)
    ) where accepted_at is null;

create or replace function public.send_hailtone_contact_request(p_recipient_user_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    recent_send_count integer;
    inserted_request_count integer;
begin
    if authenticated_user_id is null or not exists (
        select 1 from auth.users as auth_user
        left join public.profiles as own_profile on own_profile.user_id = auth_user.id
        where auth_user.id = authenticated_user_id and auth_user.phone_confirmed_at is not null
          and (not coalesce(own_profile.identity_required, false) or own_profile.identity_complete)
    ) then raise exception 'Verified account required' using errcode = '42501'; end if;
    if p_recipient_user_id is null or p_recipient_user_id = authenticated_user_id or not exists (
        select 1 from auth.users as recipient
        join public.profiles as recipient_profile on recipient_profile.user_id = recipient.id
        where recipient.id = p_recipient_user_id and recipient.phone_confirmed_at is not null
          and (not recipient_profile.identity_required or recipient_profile.identity_complete)
    ) then raise exception 'Contact account is unavailable' using errcode = '22023'; end if;

    perform pg_advisory_xact_lock(hashtextextended('hailtone-request-sender:' || authenticated_user_id::text, 0));
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, p_recipient_user_id)::text
            || ':' || greatest(authenticated_user_id, p_recipient_user_id)::text, 0
    ));
        delete from public.hailtone_contact_request_send_events as event
        where event.requester_user_id = authenticated_user_id
            and event.created_at <= now() - interval '24 hours';
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = p_recipient_user_id)
           or (user_block.blocker_user_id = p_recipient_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then raise exception 'Contact account is unavailable' using errcode = '42501'; end if;
    if exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id and link.contact_user_id = p_recipient_user_id
    ) then
        delete from public.hailtone_contact_requests as request
        where request.accepted_at is null
          and least(request.requester_user_id, request.recipient_user_id) = least(authenticated_user_id, p_recipient_user_id)
          and greatest(request.requester_user_id, request.recipient_user_id) = greatest(authenticated_user_id, p_recipient_user_id);
        return;
    end if;
    if exists (
        select 1 from public.hailtone_contact_requests as request
        where request.accepted_at is null
          and least(request.requester_user_id, request.recipient_user_id) = least(authenticated_user_id, p_recipient_user_id)
          and greatest(request.requester_user_id, request.recipient_user_id) = greatest(authenticated_user_id, p_recipient_user_id)
    ) then return; end if;

    select count(*) into recent_send_count from public.hailtone_contact_request_send_events as event
    where event.requester_user_id = authenticated_user_id and event.created_at > now() - interval '24 hours';
    if recent_send_count >= 30 then raise exception 'Contact request limit reached' using errcode = '42501'; end if;
    insert into public.hailtone_contact_requests (requester_user_id, recipient_user_id)
    values (authenticated_user_id, p_recipient_user_id) on conflict do nothing;
    get diagnostics inserted_request_count = row_count;
    if inserted_request_count = 1 then
        insert into public.hailtone_contact_request_send_events (requester_user_id, recipient_user_id)
        values (authenticated_user_id, p_recipient_user_id);
    end if;
end;
$$;

alter function public.accept_hailtone_contact_request(uuid) rename to accept_hailtone_contact_request_unlocked;
revoke all on function public.accept_hailtone_contact_request_unlocked(uuid) from public, anon, authenticated;
create function public.accept_hailtone_contact_request(p_request_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    requester_user_id uuid;
    existing_link boolean;
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    select request.requester_user_id into requester_user_id from public.hailtone_contact_requests as request
    where request.request_id = p_request_id and request.recipient_user_id = authenticated_user_id
      and request.accepted_at is null;
    if requester_user_id is null then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, requester_user_id)::text
            || ':' || greatest(authenticated_user_id, requester_user_id)::text, 0
    ));
    perform 1 from public.hailtone_contact_requests as request
    where request.request_id = p_request_id and request.recipient_user_id = authenticated_user_id
      and request.accepted_at is null for update;
    if not found then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
    if not exists (
        select 1 from auth.users as auth_user
        left join public.profiles as own_profile on own_profile.user_id = auth_user.id
        where auth_user.id = authenticated_user_id and auth_user.phone_confirmed_at is not null
          and (not coalesce(own_profile.identity_required, false) or own_profile.identity_complete)
    ) or not exists (
        select 1 from auth.users as requester
        join public.profiles as requester_profile on requester_profile.user_id = requester.id
        where requester.id = requester_user_id and requester.phone_confirmed_at is not null
          and (not requester_profile.identity_required or requester_profile.identity_complete)
    ) then raise exception 'Verified account required' using errcode = '42501'; end if;
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = requester_user_id)
           or (user_block.blocker_user_id = requester_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
    select exists (select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id and link.contact_user_id = requester_user_id)
    into existing_link;
    if not existing_link then
        perform public.accept_hailtone_contact_request_unlocked(p_request_id);
    else
        update public.hailtone_contact_requests set accepted_at = now() where request_id = p_request_id;
    end if;
    delete from public.hailtone_contact_requests as request
    where request.request_id <> p_request_id and request.accepted_at is null
      and least(request.requester_user_id, request.recipient_user_id) = least(authenticated_user_id, requester_user_id)
      and greatest(request.requester_user_id, request.recipient_user_id) = greatest(authenticated_user_id, requester_user_id);
end;
$$;

create or replace function public.decline_hailtone_contact_request(p_request_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare authenticated_user_id uuid := auth.uid(); requester_user_id uuid;
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    select request.requester_user_id into requester_user_id from public.hailtone_contact_requests as request
    where request.request_id = p_request_id and request.recipient_user_id = authenticated_user_id and request.accepted_at is null;
    if requester_user_id is null then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, requester_user_id)::text
            || ':' || greatest(authenticated_user_id, requester_user_id)::text, 0
    ));
    delete from public.hailtone_contact_requests as request where request.request_id = p_request_id
      and request.recipient_user_id = authenticated_user_id and request.accepted_at is null;
    if not found then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
end;
$$;

create or replace function public.cancel_hailtone_contact_request(p_request_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare authenticated_user_id uuid := auth.uid(); recipient_user_id uuid;
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    select request.recipient_user_id into recipient_user_id from public.hailtone_contact_requests as request
    where request.request_id = p_request_id and request.requester_user_id = authenticated_user_id and request.accepted_at is null;
    if recipient_user_id is null then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, recipient_user_id)::text
            || ':' || greatest(authenticated_user_id, recipient_user_id)::text, 0
    ));
    delete from public.hailtone_contact_requests as request where request.request_id = p_request_id
      and request.requester_user_id = authenticated_user_id and request.accepted_at is null;
    if not found then raise exception 'Contact request is unavailable' using errcode = '42501'; end if;
end;
$$;

create or replace function public.block_hailtone_contact(p_contact_user_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    if p_contact_user_id is null or p_contact_user_id = authenticated_user_id then
        raise exception 'Contact is unavailable' using errcode = '42501';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, p_contact_user_id)::text
            || ':' || greatest(authenticated_user_id, p_contact_user_id)::text, 0
    ));
    if not exists (select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id and link.contact_user_id = p_contact_user_id)
       and not exists (select 1 from public.hailtone_contact_requests as request
        where request.requester_user_id = p_contact_user_id and request.recipient_user_id = authenticated_user_id
          and request.accepted_at is null) then
        raise exception 'Contact is unavailable' using errcode = '42501';
    end if;
    insert into public.hailtone_user_blocks (blocker_user_id, blocked_user_id)
    values (authenticated_user_id, p_contact_user_id) on conflict do nothing;
    delete from public.hailtone_contact_requests as request where request.accepted_at is null
      and least(request.requester_user_id, request.recipient_user_id) = least(authenticated_user_id, p_contact_user_id)
      and greatest(request.requester_user_id, request.recipient_user_id) = greatest(authenticated_user_id, p_contact_user_id);
end;
$$;

create or replace function public.unblock_hailtone_contact(p_contact_user_id uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, auth
as $$
declare authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    if p_contact_user_id is null or p_contact_user_id = authenticated_user_id then
        raise exception 'Contact is unavailable' using errcode = '42501';
    end if;
    delete from public.hailtone_user_blocks as user_block
    where user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = p_contact_user_id;
    if not found then raise exception 'Contact is unavailable' using errcode = '42501'; end if;
end;
$$;

create or replace function public.accept_hailtone_contact_invite(p_invitation_code text)
returns table (contact_invite_id uuid, contact_user_id uuid, display_name text, avatar_path text)
language plpgsql security definer
set search_path = pg_catalog, public, auth, extensions
as $$
declare
    authenticated_user_id uuid := auth.uid();
    invite_row public.hailtone_contact_invites%rowtype;
    existing_invitation_id uuid;
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    if not exists (select 1 from auth.users where id = authenticated_user_id and phone_confirmed_at is not null) then
        raise exception 'Verified phone required' using errcode = '42501';
    end if;
    if p_invitation_code is null or p_invitation_code !~ '^[0-9a-fA-F]{64}$' then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;
    select * into invite_row from public.hailtone_contact_invites as invite
    where invite.token_hash = digest(convert_to(lower(p_invitation_code), 'UTF8'), 'sha256')
      and (invite.accepted_by_user_id is null or invite.accepted_by_user_id = authenticated_user_id)
      and (invite.expires_at > now() or invite.accepted_by_user_id = authenticated_user_id)
    for update;
    if not found or invite_row.inviter_user_id = authenticated_user_id then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;
    if not exists (select 1 from auth.users where id = invite_row.inviter_user_id and phone_confirmed_at is not null) then
        raise exception 'Invitation is invalid or expired' using errcode = '22023';
    end if;
    perform pg_advisory_xact_lock(hashtextextended(
        'hailtone-contact-pair:' || least(authenticated_user_id, invite_row.inviter_user_id)::text
            || ':' || greatest(authenticated_user_id, invite_row.inviter_user_id)::text, 0
    ));
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = invite_row.inviter_user_id)
           or (user_block.blocker_user_id = invite_row.inviter_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then raise exception 'Invitation is invalid or expired' using errcode = '22023'; end if;
    if invite_row.accepted_by_user_id is null then
        update public.hailtone_contact_invites set accepted_by_user_id = authenticated_user_id, accepted_at = now()
        where id = invite_row.id;
    end if;
    select link.invitation_id into existing_invitation_id from public.hailtone_contact_links as link
    where link.owner_user_id = authenticated_user_id and link.contact_user_id = invite_row.inviter_user_id
    order by link.linked_at, link.invitation_id limit 1;
    if existing_invitation_id is null then
        insert into public.hailtone_contact_links (owner_user_id, contact_user_id, invitation_id)
        values (invite_row.inviter_user_id, authenticated_user_id, invite_row.id),
               (authenticated_user_id, invite_row.inviter_user_id, invite_row.id)
        on conflict (owner_user_id, invitation_id) do nothing;
        existing_invitation_id := invite_row.id;
    end if;
    delete from public.hailtone_contact_requests as request where request.accepted_at is null
      and least(request.requester_user_id, request.recipient_user_id) = least(authenticated_user_id, invite_row.inviter_user_id)
      and greatest(request.requester_user_id, request.recipient_user_id) = greatest(authenticated_user_id, invite_row.inviter_user_id);
    return query select existing_invitation_id, profile.user_id, profile.display_name, profile.avatar_path
    from public.profiles as profile where profile.user_id = invite_row.inviter_user_id;
end;
$$;

create or replace function public.list_hailtone_blocked_users()
returns table (user_id uuid, display_name text, username text, avatar_path text)
language plpgsql stable security definer
set search_path = pg_catalog, public, auth
as $$
declare authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then raise exception 'Authentication required' using errcode = '42501'; end if;
    return query select profile.user_id, profile.display_name, profile.username, profile.avatar_path
    from public.hailtone_user_blocks as user_block
    join public.profiles as profile on profile.user_id = user_block.blocked_user_id
    where user_block.blocker_user_id = authenticated_user_id
    order by user_block.created_at desc, profile.user_id;
end;
$$;

revoke all on function public.send_hailtone_contact_request(uuid) from public, anon, authenticated;
revoke all on function public.accept_hailtone_contact_request(uuid) from public, anon, authenticated;
revoke all on function public.decline_hailtone_contact_request(uuid) from public, anon, authenticated;
revoke all on function public.cancel_hailtone_contact_request(uuid) from public, anon, authenticated;
revoke all on function public.block_hailtone_contact(uuid) from public, anon, authenticated;
revoke all on function public.unblock_hailtone_contact(uuid) from public, anon, authenticated;
revoke all on function public.accept_hailtone_contact_invite(text) from public, anon, authenticated;
revoke all on function public.list_hailtone_blocked_users() from public, anon, authenticated;
grant execute on function public.send_hailtone_contact_request(uuid) to authenticated;
grant execute on function public.accept_hailtone_contact_request(uuid) to authenticated;
grant execute on function public.decline_hailtone_contact_request(uuid) to authenticated;
grant execute on function public.cancel_hailtone_contact_request(uuid) to authenticated;
grant execute on function public.block_hailtone_contact(uuid) to authenticated;
grant execute on function public.unblock_hailtone_contact(uuid) to authenticated;
grant execute on function public.accept_hailtone_contact_invite(text) to authenticated;
grant execute on function public.list_hailtone_blocked_users() to authenticated;

commit;

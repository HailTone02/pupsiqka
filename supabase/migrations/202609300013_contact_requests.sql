begin;

create table if not exists public.hailtone_contact_requests (
    request_id uuid primary key default gen_random_uuid(),
    requester_user_id uuid not null references auth.users (id) on delete cascade,
    recipient_user_id uuid not null references auth.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    accepted_at timestamptz,
    constraint hailtone_contact_requests_not_self_check check (requester_user_id <> recipient_user_id)
);

create unique index if not exists hailtone_contact_requests_pending_pair_uidx
    on public.hailtone_contact_requests (requester_user_id, recipient_user_id)
    where accepted_at is null;
create index if not exists hailtone_contact_requests_recipient_created_idx
    on public.hailtone_contact_requests (recipient_user_id, created_at desc)
    where accepted_at is null;
create index if not exists hailtone_contact_requests_requester_created_idx
    on public.hailtone_contact_requests (requester_user_id, created_at desc)
    where accepted_at is null;

alter table public.hailtone_contact_requests enable row level security;
revoke all on table public.hailtone_contact_requests from public, anon, authenticated;

create or replace function public.search_hailtone_accounts(p_query text)
returns table (
    user_id uuid,
    display_name text,
    username text,
    avatar_path text,
    blocked_by_me boolean,
    blocked_me boolean
)
language plpgsql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    search_value text := btrim(p_query);
    phone_digits text := regexp_replace(coalesce(p_query, ''), '[^0-9]', '', 'g');
begin
    if authenticated_user_id is null or not exists (
        select 1
        from auth.users as auth_user
        left join public.profiles as own_profile on own_profile.user_id = auth_user.id
        where auth_user.id = authenticated_user_id
          and auth_user.phone_confirmed_at is not null
          and (not coalesce(own_profile.identity_required, false) or own_profile.identity_complete)
    ) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;
    if search_value is null or char_length(search_value) < 3 or char_length(search_value) > 254 then
        return;
    end if;

    return query
    select profile.user_id,
           profile.display_name,
           profile.username,
           profile.avatar_path,
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = authenticated_user_id
                 and user_block.blocked_user_id = profile.user_id
           ),
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = profile.user_id
                 and user_block.blocked_user_id = authenticated_user_id
           )
    from public.profiles as profile
    join auth.users as auth_user on auth_user.id = profile.user_id
    where profile.user_id <> authenticated_user_id
      and auth_user.phone_confirmed_at is not null
      and (not profile.identity_required or profile.identity_complete)
      and (
          (left(search_value, 1) = '@'
              and profile.username = lower(substr(search_value, 2))
              and substr(search_value, 2) ~ '^[a-zA-Z0-9_]{3,30}$')
          or (left(search_value, 1) <> '@'
              and position('@' in search_value) = 0
              and profile.username = lower(search_value)
              and search_value ~ '^[a-zA-Z0-9_]{3,30}$')
          or (left(search_value, 1) <> '@'
              and position('@' in search_value) > 1
              and lower(auth_user.email) = lower(search_value))
          or (char_length(phone_digits) between 8 and 15
              and regexp_replace(coalesce(auth_user.phone, ''), '[^0-9]', '', 'g') = phone_digits)
      )
    order by profile.user_id
    limit 5;
end;
$$;

create or replace function public.list_hailtone_contact_requests()
returns table (
    request_id uuid,
    user_id uuid,
    display_name text,
    username text,
    avatar_path text,
    direction text,
    blocked_by_me boolean,
    blocked_me boolean
)
language plpgsql
stable
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    return query
    select request.request_id, profile.user_id, profile.display_name, profile.username, profile.avatar_path,
           'incoming'::text,
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = authenticated_user_id
                 and user_block.blocked_user_id = profile.user_id
           ),
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = profile.user_id
                 and user_block.blocked_user_id = authenticated_user_id
           )
    from public.hailtone_contact_requests as request
    join public.profiles as profile on profile.user_id = request.requester_user_id
    where request.recipient_user_id = authenticated_user_id and request.accepted_at is null
    union all
    select request.request_id, profile.user_id, profile.display_name, profile.username, profile.avatar_path,
           'outgoing'::text,
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = authenticated_user_id
                 and user_block.blocked_user_id = profile.user_id
           ),
           exists (
               select 1 from public.hailtone_user_blocks as user_block
               where user_block.blocker_user_id = profile.user_id
                 and user_block.blocked_user_id = authenticated_user_id
           )
    from public.hailtone_contact_requests as request
    join public.profiles as profile on profile.user_id = request.recipient_user_id
    where request.requester_user_id = authenticated_user_id and request.accepted_at is null
    order by 6, 3;
end;
$$;

create or replace function public.send_hailtone_contact_request(p_recipient_user_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
begin
    if authenticated_user_id is null or not exists (
        select 1
        from auth.users as auth_user
        left join public.profiles as own_profile on own_profile.user_id = auth_user.id
        where auth_user.id = authenticated_user_id
          and auth_user.phone_confirmed_at is not null
          and (not coalesce(own_profile.identity_required, false) or own_profile.identity_complete)
    ) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;
    if p_recipient_user_id is null or p_recipient_user_id = authenticated_user_id or not exists (
        select 1 from auth.users as recipient
        join public.profiles as recipient_profile on recipient_profile.user_id = recipient.id
        where recipient.id = p_recipient_user_id
          and recipient.phone_confirmed_at is not null
          and (not recipient_profile.identity_required or recipient_profile.identity_complete)
    ) then
        raise exception 'Contact account is unavailable' using errcode = '22023';
    end if;
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = p_recipient_user_id)
           or (user_block.blocker_user_id = p_recipient_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
        raise exception 'Contact account is unavailable' using errcode = '42501';
    end if;
    if exists (
        select 1 from public.hailtone_contact_links as link
        where link.owner_user_id = authenticated_user_id and link.contact_user_id = p_recipient_user_id
    ) then
        return;
    end if;
    if exists (
        select 1 from public.hailtone_contact_requests as request
        where request.requester_user_id = p_recipient_user_id
          and request.recipient_user_id = authenticated_user_id
          and request.accepted_at is null
    ) then
        return;
    end if;
    if (select count(*) from public.hailtone_contact_requests as request
        where request.requester_user_id = authenticated_user_id
          and request.created_at > now() - interval '24 hours') >= 30 then
        raise exception 'Contact request limit reached' using errcode = '42501';
    end if;

    insert into public.hailtone_contact_requests (requester_user_id, recipient_user_id)
    values (authenticated_user_id, p_recipient_user_id)
    on conflict (requester_user_id, recipient_user_id) where accepted_at is null do nothing;
end;
$$;

create or replace function public.accept_hailtone_contact_request(p_request_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, auth, extensions
as $$
declare
    authenticated_user_id uuid := auth.uid();
    request_row public.hailtone_contact_requests%rowtype;
    invitation_id uuid;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    select * into request_row
      from public.hailtone_contact_requests as request
     where request.request_id = p_request_id
       and request.recipient_user_id = authenticated_user_id
       and request.accepted_at is null
     for update;
    if not found then
        raise exception 'Contact request is unavailable' using errcode = '42501';
    end if;
    if not exists (
        select 1 from auth.users as auth_user
        left join public.profiles as own_profile on own_profile.user_id = auth_user.id
        where auth_user.id = authenticated_user_id
          and auth_user.phone_confirmed_at is not null
          and (not coalesce(own_profile.identity_required, false) or own_profile.identity_complete)
    ) or not exists (
        select 1 from auth.users as requester
        join public.profiles as requester_profile on requester_profile.user_id = requester.id
        where requester.id = request_row.requester_user_id
          and requester.phone_confirmed_at is not null
          and (not requester_profile.identity_required or requester_profile.identity_complete)
    ) then
        raise exception 'Verified account required' using errcode = '42501';
    end if;
    if exists (
        select 1 from public.hailtone_user_blocks as user_block
        where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = request_row.requester_user_id)
           or (user_block.blocker_user_id = request_row.requester_user_id and user_block.blocked_user_id = authenticated_user_id)
    ) then
        raise exception 'Contact request is unavailable' using errcode = '42501';
    end if;

    insert into public.hailtone_contact_invites (
        token_hash, inviter_user_id, accepted_by_user_id, expires_at, accepted_at
    ) values (
        extensions.digest(extensions.gen_random_bytes(32), 'sha256'),
        request_row.requester_user_id,
        authenticated_user_id,
        now() + interval '1 day',
        now()
    ) returning id into invitation_id;

    insert into public.hailtone_contact_links (owner_user_id, contact_user_id, invitation_id)
    values
        (request_row.requester_user_id, authenticated_user_id, invitation_id),
        (authenticated_user_id, request_row.requester_user_id, invitation_id);

    update public.hailtone_contact_requests
       set accepted_at = now()
     where request_id = request_row.request_id;
end;
$$;

revoke all on function public.search_hailtone_accounts(text) from public, anon, authenticated;
revoke all on function public.list_hailtone_contact_requests() from public, anon, authenticated;
revoke all on function public.send_hailtone_contact_request(uuid) from public, anon, authenticated;
revoke all on function public.accept_hailtone_contact_request(uuid) from public, anon, authenticated;
grant execute on function public.search_hailtone_accounts(text) to authenticated;
grant execute on function public.list_hailtone_contact_requests() to authenticated;
grant execute on function public.send_hailtone_contact_request(uuid) to authenticated;
grant execute on function public.accept_hailtone_contact_request(uuid) to authenticated;

commit;
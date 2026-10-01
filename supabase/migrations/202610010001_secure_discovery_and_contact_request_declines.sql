begin;

create table if not exists public.hailtone_discovery_rate_limits (
    user_id uuid primary key references auth.users (id) on delete cascade,
    window_started_at timestamptz not null,
    request_count integer not null check (request_count > 0)
);

alter table public.hailtone_discovery_rate_limits enable row level security;
revoke all on table public.hailtone_discovery_rate_limits from public, anon, authenticated;

drop function if exists public.search_hailtone_accounts(text);

create function public.search_hailtone_accounts(p_query text)
returns table (
    user_id uuid,
    display_name text,
    username text
)
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    search_value text := btrim(p_query);
    phone_digits text := regexp_replace(coalesce(p_query, ''), '[^0-9]', '', 'g');
    request_window timestamptz := statement_timestamp();
    discovery_request_count integer;
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

    insert into public.hailtone_discovery_rate_limits as rate_limit (
        user_id, window_started_at, request_count
    ) values (
        authenticated_user_id, request_window, 1
    )
    on conflict (user_id) do update
       set window_started_at = case
               when rate_limit.window_started_at <= request_window - interval '1 minute' then request_window
               else rate_limit.window_started_at
           end,
           request_count = case
               when rate_limit.window_started_at <= request_window - interval '1 minute' then 1
               else rate_limit.request_count + 1
           end
    returning request_count into discovery_request_count;

    if discovery_request_count > 20 then
        raise exception 'Discovery rate limit exceeded' using errcode = 'P0001';
    end if;

    if search_value is null or char_length(search_value) < 3 or char_length(search_value) > 254 then
        return;
    end if;

    return query
    select profile.user_id, profile.display_name, profile.username
    from public.profiles as profile
    join auth.users as auth_user on auth_user.id = profile.user_id
    where profile.user_id <> authenticated_user_id
      and auth_user.phone_confirmed_at is not null
      and (not profile.identity_required or profile.identity_complete)
      and not exists (
          select 1 from public.hailtone_user_blocks as user_block
          where (user_block.blocker_user_id = authenticated_user_id and user_block.blocked_user_id = profile.user_id)
             or (user_block.blocker_user_id = profile.user_id and user_block.blocked_user_id = authenticated_user_id)
      )
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

create or replace function public.decline_hailtone_contact_request(p_request_id uuid)
returns void
language plpgsql
security definer
set search_path = pg_catalog, public, auth
as $$
declare
    authenticated_user_id uuid := auth.uid();
    declined_request_id uuid;
begin
    if authenticated_user_id is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;

    delete from public.hailtone_contact_requests as request
    where request.request_id = p_request_id
      and request.recipient_user_id = authenticated_user_id
      and request.accepted_at is null
    returning request.request_id into declined_request_id;

    if declined_request_id is null then
        raise exception 'Contact request is unavailable' using errcode = '42501';
    end if;
end;
$$;

revoke all on function public.search_hailtone_accounts(text) from public, anon, authenticated;
revoke all on function public.decline_hailtone_contact_request(uuid) from public, anon, authenticated;
grant execute on function public.search_hailtone_accounts(text) to authenticated;
grant execute on function public.decline_hailtone_contact_request(uuid) to authenticated;

commit;
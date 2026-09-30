begin;

alter table public.profiles
    add column if not exists name text,
    add column if not exists surname text,
    add column if not exists username text,
    add column if not exists identity_required boolean not null default false,
    add column if not exists identity_complete boolean not null default false;

alter table public.profiles
    add constraint profiles_name_check check (
        name is null or (name = btrim(name) and char_length(name) between 1 and 80 and name !~ '[[:cntrl:]]')
    ),
    add constraint profiles_surname_check check (
        surname is null or (surname = btrim(surname) and char_length(surname) between 1 and 80 and surname !~ '[[:cntrl:]]')
    ),
    add constraint profiles_username_check check (
        username is null or (
            username = lower(username)
            and username ~ '^[a-z0-9_]{3,30}$'
            and username not in (
                'admin', 'administrator', 'api', 'billing', 'contact', 'help',
                'hailtone', 'null', 'root', 'security', 'support', 'system', 'www'
            )
        )
    ),
    add constraint profiles_identity_required_check check (
        not identity_required or (name is not null and surname is not null and username is not null)
    ),
    add constraint profiles_identity_complete_check check (not identity_complete or identity_required);

create unique index if not exists profiles_username_lower_uidx
    on public.profiles (lower(username))
    where username is not null;

revoke all on table public.profiles from public, anon, authenticated;
revoke all privileges (user_id, name, surname, username, identity_required, identity_complete, display_name, avatar_path, created_at, updated_at)
    on table public.profiles from public, anon, authenticated;
grant select (user_id, name, surname, username, identity_required, identity_complete, display_name, avatar_path)
    on table public.profiles to authenticated;
grant update (display_name, avatar_path)
    on table public.profiles to authenticated;

create or replace function public.create_profile_for_auth_user()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
    profile_name text := btrim(new.raw_user_meta_data ->> 'hailtone_name');
    profile_surname text := btrim(new.raw_user_meta_data ->> 'hailtone_surname');
    profile_username text := lower(btrim(new.raw_user_meta_data ->> 'hailtone_username'));
begin
    if profile_name is null or profile_name = '' or char_length(profile_name) > 80 or profile_name ~ '[[:cntrl:]]'
        or profile_surname is null or profile_surname = '' or char_length(profile_surname) > 80 or profile_surname ~ '[[:cntrl:]]'
        or char_length(profile_name || ' ' || profile_surname) > 80
        or profile_username is null
        or profile_username !~ '^[a-z0-9_]{3,30}$'
        or profile_username in (
            'admin', 'administrator', 'api', 'billing', 'contact', 'help',
            'hailtone', 'null', 'root', 'security', 'support', 'system', 'www'
        ) then
        raise exception 'Registration identity is invalid' using errcode = '22023';
    end if;

    insert into public.profiles (
        user_id, name, surname, username, display_name, identity_required, identity_complete
    ) values (
        new.id, profile_name, profile_surname, profile_username,
        profile_name || ' ' || profile_surname, true,
        new.email_confirmed_at is not null and new.phone_confirmed_at is not null
    );
    return new;
end;
$$;

revoke all on function public.create_profile_for_auth_user() from public, anon, authenticated;

create or replace function public.sync_profile_identity_verification()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    update public.profiles
       set identity_complete = identity_required
           and new.email_confirmed_at is not null
           and new.phone_confirmed_at is not null
     where user_id = new.id and identity_required;
    return new;
end;
$$;

revoke all on function public.sync_profile_identity_verification() from public, anon, authenticated;
drop trigger if exists auth_user_sync_profile_identity_verification on auth.users;
create trigger auth_user_sync_profile_identity_verification
    after update of email_confirmed_at, phone_confirmed_at on auth.users
    for each row execute function public.sync_profile_identity_verification();

create or replace function public.require_complete_identity_for_contact_link()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
begin
    if exists (
        select 1 from public.profiles as profile
        where profile.user_id in (new.owner_user_id, new.contact_user_id)
          and profile.identity_required
          and not profile.identity_complete
    ) then
        raise exception 'Contact identity verification required' using errcode = '42501';
    end if;
    return new;
end;
$$;

revoke all on function public.require_complete_identity_for_contact_link() from public, anon, authenticated;
drop trigger if exists hailtone_contact_links_require_complete_identity on public.hailtone_contact_links;
create trigger hailtone_contact_links_require_complete_identity
    before insert or update on public.hailtone_contact_links
    for each row execute function public.require_complete_identity_for_contact_link();

drop function public.get_public_profiles(uuid[]);
create function public.get_public_profiles(p_user_ids uuid[])
returns table (username text, avatar_path text)
language plpgsql
stable
security definer
set search_path = pg_catalog, public
as $$
begin
    if auth.uid() is null then
        raise exception 'Authentication required' using errcode = '42501';
    end if;
    if p_user_ids is null or cardinality(p_user_ids) not between 1 and 50 then
        raise exception 'Provide between 1 and 50 profile IDs' using errcode = '22023';
    end if;
    return query
    select case when requested.user_id <> auth.uid() then profile.username else null end,
           case when requested.user_id <> auth.uid() then profile.avatar_path else null end
    from unnest(p_user_ids) with ordinality as requested(user_id, request_order)
    left join public.profiles as profile on profile.user_id = requested.user_id
    order by requested.request_order;
end;
$$;
revoke all on function public.get_public_profiles(uuid[]) from public, anon, authenticated;
grant execute on function public.get_public_profiles(uuid[]) to authenticated;

drop function public.list_hailtone_contacts();
create function public.list_hailtone_contacts()
returns table (
    contact_invite_id uuid,
    contact_user_id uuid,
    display_name text,
    username text,
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
    select link.invitation_id, profile.user_id, profile.display_name, profile.username, profile.avatar_path,
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
revoke all on function public.list_hailtone_contacts() from public, anon, authenticated;
grant execute on function public.list_hailtone_contacts() to authenticated;

commit;
begin;

create table if not exists public.call_push_tokens (
    installation_id uuid primary key,
    user_id uuid not null references auth.users (id) on delete cascade,
    fcm_token text not null unique,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint call_push_tokens_token_length check (char_length(fcm_token) between 20 and 4096),
    constraint call_push_tokens_token_trimmed check (fcm_token = btrim(fcm_token)),
    constraint call_push_tokens_token_no_controls check (fcm_token !~ '[[:cntrl:]]')
);

create index if not exists call_push_tokens_user_id_idx
    on public.call_push_tokens (user_id);

alter table public.call_push_tokens enable row level security;
alter table public.call_push_tokens force row level security;
revoke all on table public.call_push_tokens from public, anon, authenticated;
grant select, delete on table public.call_push_tokens to service_role;

create or replace function public.register_call_push_token(p_installation_id uuid, p_fcm_token text)
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
    if p_installation_id is null or p_fcm_token is null
        or char_length(p_fcm_token) not between 20 and 4096
        or p_fcm_token <> btrim(p_fcm_token)
        or p_fcm_token ~ '[[:cntrl:]]' then
        raise exception 'Invalid push token registration' using errcode = '22023';
    end if;

    delete from public.call_push_tokens
     where (installation_id = p_installation_id and user_id = authenticated_user_id)
         or (fcm_token = p_fcm_token and user_id = authenticated_user_id);

    insert into public.call_push_tokens (installation_id, user_id, fcm_token)
    values (p_installation_id, authenticated_user_id, p_fcm_token);
end;
$$;

create or replace function public.unregister_call_push_token(p_installation_id uuid)
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
    if p_installation_id is null then
        raise exception 'Invalid push token installation' using errcode = '22023';
    end if;

    delete from public.call_push_tokens
    where installation_id = p_installation_id
      and user_id = authenticated_user_id;
end;
$$;

revoke all on function public.register_call_push_token(uuid, text) from public, anon, authenticated;
revoke all on function public.unregister_call_push_token(uuid) from public, anon, authenticated;
grant execute on function public.register_call_push_token(uuid, text) to authenticated;
grant execute on function public.unregister_call_push_token(uuid) to authenticated;

commit;
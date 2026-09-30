begin;

create or replace function public.enqueue_incoming_call_push()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog
as $$
declare
    webhook_secret text;
begin
    begin
        select decrypted_secret
        into webhook_secret
        from vault.decrypted_secrets
        where name = 'hailtone_call_push_webhook_secret'
        limit 1;

        if webhook_secret is null or webhook_secret = '' then
            raise exception 'Incoming-call push configuration is unavailable';
        end if;

        perform net.http_post(
            url := 'https://hozhaqmvvnsyzuxgjowy.supabase.co/functions/v1/send-incoming-call',
            body := jsonb_build_object(
                'type', 'UPDATE',
                'table', 'call_sessions',
                'schema', 'public',
                'record', jsonb_build_object('id', new.id, 'status', new.status),
                'old_record', jsonb_build_object('status', old.status)
            ),
            headers := jsonb_build_object(
                'Content-Type', 'application/json',
                'x-call-push-webhook-secret', webhook_secret
            )
        );
    exception when others then
        raise warning 'Incoming-call push enqueue failed; call state update will continue';
    end;

    return new;
end;
$$;

revoke all on function public.enqueue_incoming_call_push() from public, anon, authenticated;

create trigger call_sessions_enqueue_incoming_call_push
    after update of status on public.call_sessions
    for each row
    when (old.status = 'preparing' and new.status = 'ringing')
    execute function public.enqueue_incoming_call_push();

commit;
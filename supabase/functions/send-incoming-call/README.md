# Incoming call push sender

This Supabase Edge Function is called by the pg_net trigger installed by migration `202609290007_incoming_call_push_pg_net.sql`. It accepts only a `call_id` as FCM data, then re-reads the call and callee's device tokens using server-only credentials. The Android client re-fetches the authenticated pending invitation before displaying anything.

Deploy with JWT verification disabled only because the database trigger authenticates with its separately configured shared header:

```sh
supabase functions deploy send-incoming-call --no-verify-jwt
supabase secrets set CALL_PUSH_WEBHOOK_SECRET='<random webhook secret>' FCM_SERVICE_ACCOUNT_JSON='<Firebase service account JSON>'
```

The migration posts only the call ID and status metadata needed by the function, and only when a call changes from `preparing` to `ringing`. It reads the shared header secret at runtime from Supabase Vault secret `hailtone_call_push_webhook_secret`; the Edge Function URL is the public endpoint for this Supabase project. Add the same random value configured as `CALL_PUSH_WEBHOOK_SECRET` to Vault using the Dashboard's Vault secret UI. The trigger does not use `supabase_functions.http_request` or a Dashboard Database Webhook. Supabase supplies `SUPABASE_URL` and `SUPABASE_SERVICE_ROLE_KEY` to the Edge Function runtime. Do not put any of these server secrets in Android or source control.

If adding the Vault secret through the SQL Editor, use this statement with the value entered directly in the Dashboard and never commit it:

```sql
select vault.create_secret(
	'<same random value configured as CALL_PUSH_WEBHOOK_SECRET>',
	'hailtone_call_push_webhook_secret',
	'HailTone incoming call push trigger'
);
```

The service account needs the Firebase Cloud Messaging API enabled and permission to send FCM messages for its Firebase project. Android client setup separately requires `firebase.applicationId`, `firebase.apiKey`, `firebase.projectId`, and `firebase.senderId` in ignored `local.properties`, copied from the registered Android app's Firebase configuration. No service-account JSON belongs in `local.properties`.
# HailTone Contact Identity and Blocking

## Discovery model

HailTone does not look up accounts by phone number. Android keeps phone numbers on-device and sends none of them to Supabase. A contact becomes an authenticated HailTone contact only after an explicit invitation is accepted by another signed-in account whose phone is verified in Supabase Auth.

The inviter RPC creates a 256-bit random code with `pgcrypto`, stores only its SHA-256 digest, expires it after 24 hours, and allows at most 20 issued codes per verified account in a rolling 24-hour window. The inviter shares the opaque code out of band. The recipient selects the corresponding local contact and enters the code; the server derives both account IDs from the invitation row and `auth.uid()`, verifies both phone-confirmation states, consumes the code for that account, and creates a bilateral link. Repeating acceptance by the same authenticated account is idempotent to recover from a lost response; another account cannot reuse the consumed code. No client-provided phone number, display name, avatar, local contact ID, or UUID establishes identity.

The Android app stores only the accepted invitation ID-to-local-contact association in private app preferences. It sends neither that local key nor phone data to Supabase. The app resolves a contact only when the authenticated `list_hailtone_contacts()` RPC returns the same accepted invitation ID and its server-authoritative contact UUID.

## Enumeration and abuse threat model

There is no RPC that accepts arbitrary phone numbers, UUID arrays, or search terms. The contact-list RPC accepts no arguments and returns only accounts already linked to the caller through an accepted invite, with the minimum contact profile fields and block flags. Listing and managing existing links remains available to an authenticated account if its phone is later unverified; this cannot create or discover a new link. Direct reads of invitation, link, and block tables are revoked and RLS is enabled. Invalid, expired, reused, self, and blocked invitations return the same generic invalid-invitation error, without identifying an account for an arbitrary query.

The 256-bit random code is infeasible to guess; only its SHA-256 digest is stored. A code can be consumed by one account, expires after 24 hours, and issuance is rate-limited to 20 per verified user per day. It is a bearer capability until accepted: users should share it only with the intended person. A recipient must explicitly accept while authenticated and phone-verified. Acceptance reveals only the inviter's public display name/avatar and UUID to that recipient, and only as part of the new bilateral relationship.

Blocking is keyed by UUIDs from established links. The blocker is always `auth.uid()`. Database call invitation, direct conversation creation, and message-send RPCs check blocks in both directions, so modified clients cannot bypass the UI. Existing calls/messages are not deleted by blocking.

## Live deployment

Migration `202609300008_contact_identity_and_blocking.sql` is additive and must be applied before the Android invite/resolve/block flow can work. This workspace's linked Supabase CLI currently lacks an access token; local Postgres validation does not mean the migration has been applied remotely.

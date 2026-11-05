# Expanded mention picker and audience tags — 2.8.23 (55)

## UI

Native anchored mention list is wider/taller, with 44dp DP, name, secondary Profile label, existing verified flag tick and blue Creator badge. `@followers` and `@highlight` appear above matching people (also filterable by typing their prefix). Their secondary labels explicitly say some followers/friends might receive notifications. Selecting a row persists a blue mention identity; bulk tags use reserved `bulk:followers` / `bulk:highlight` identities and open explanatory text, never a fake profile. Individual names still open the actual UID profile. Auto reply mentions and repeated-comment composer fix remain.

This is the Friendbook implementation inspired by the supplied screenshot, not a pixel-identical Facebook UI, page directory, Facebook audience targeting algorithm or delivery guarantee. The popup is anchored to the native input and its keyboard placement still needs device validation. Suggestions use the existing bounded/debounced name-prefix queries, not a full-text index.

## Server path and permission limits

- Successful post writes and primary/fallback comment commits enqueue one durable WorkManager `bulk_mention` request with post/comment IDs only; never client recipient lists. Only post-backed comments are supported for fanout (including video posts viewed via reels). Native standalone reel bulk fanout is not enabled.
- New `bulk-mentions` Supabase Edge Function verifies Firebase RS256 issuer/audience for friendbook-17551. Existing direct-FCM gateway remains unchanged. No Firebase Cloud Functions/Blaze or new client secrets.
- Handler re-reads the owned persisted post/comment and validates exact selected tag ranges/labels against content. Source must be under 24 hours old, public, not group/page/private/deleted/flagged. `@followers` requires the post owner (including owner comments on their own post); `@highlight` uses the sender's persisted friends. Unsupported selections remain text and do not broadcast; the selection toast explains restrictions.
- Followers require both the actor's followers entry and recipient's reciprocal following entry. Highlight uses a bounded friends participants query. Recipients are deduplicated and exclude the actor, either-side blocked accounts, banned accounts and server `bulkMentionsEnabled=false`. Existing device Tags/@mentions preferences still govern notification display. Local notification toggles are not automatically a server fanout opt-out.
- Up to 20 eligible recipients total per actor per new broadcast, at most one new broadcast per rolling 24 hours. SQL `reserve_bulk_mention` uses actor advisory locking, RLS and service-role-only execution; retries retain the original reserved audience. First eligible subset, not random/engagement-ranked selection; no promise everyone is notified. Candidate reads are bounded (40 followers, 40 friends records, up to 80 candidates).
- Deterministic SHA-256 inbox IDs and create-only Firestore writes avoid duplicate/re-read/reset inbox state. Existing inbox documents are validated before reuse. The function invokes the unchanged verified FCM gateway per recipient, preserving per-device claims and routing. Some failures retry via WorkManager; missing/invalid devices may receive only the in-app inbox record. No durable server scheduler: retries depend on the originating app's outbox and source age.

## Deployment / validation

Migration `202611020001_bulk_mentions.sql` applied to friendbook-push (`iqtccjgpbtnfavbkbogr`). New Edge Function `bulk-mentions` deployed ACTIVE v1 (a6f6a435-cad7-4b72-b5f0-eb873089998b), verify_jwt=false because Firebase tokens are verified inside the handler. Deployed implementation is whitespace-compacted equivalent to repository source. Existing send-fcm-notification was not modified/redeployed.

24 backend mock tests (16 existing + 8 new) verify Firebase auth, persisted source permission, private/group/stale/forged source rejection, reciprocal followers, friends query, blocks/opt-out/bans, client spoof resistance, daily-denial no-write and cap20. Python wiring guards and Robolectric bulk identity tests cover UI/persistence/endpoint selection and no fake-profile navigation.

Pending physical acceptance: keyboard/list positioning; select/filter people and both tags; post/send/reopen blue mentions; source owner/public eligibility; receiving devices with tags enabled/disabled; overlapping audiences, retry/offline/app restart/account switch, actual inbox and FCM delivery. ACTIVE deployment, mocks and signed build are NOT proof of delivery to a phone.

## D1 source preparation update

The existing `bulk-mentions` function is ACTIVE v3 with the repository D1 comment
source/ownership validation branch. `send-fcm-notification` was not modified.
Backend provider-mocked tests (45) and unauthenticated runtime rejection checks
passed in GitHub Actions run 41948533932. Both D1 gates remain OFF; this does not
establish authenticated D1 source access, FCM/device delivery or a live cutover.

# Counter core — isolated, NOT a production cutover

This commit adds server-only SQL state and executable PostgreSQL tests. It does **not**
remove the four legacy readers, deploy SQL, migrate/reset source records or change Android code.
Do not call this "all four paths fixed". Android/FCM behavior is unchanged.

## Implemented
- Per-user `notifications`, `requests`, `messages`: separate `unread_count` and `badge_count`.
- Transactional, stable-event-ID dedup; conflicting replay rejected. Signed balances make
  out-of-order create/read/delete deltas commute; only the displayed value clamps at zero.
- Per-device acknowledgement records only the displayed arrival total: it never marks source
  notifications/requests/messages read, clears another device, or consumes later arrivals.
- Unknown baseline returns `ready=false`/NULL counts, never fabricated zero.
- Explicit one-time read-time-fenced baseline replays buffered newer events. Seed only
  verified consistent counts; never seed zero merely because a user existed before rollout.
- RLS plus revoked mobile/anonymous RPC/table privileges; only service_role executes RPCs.
  No service key or actor-selected write endpoint is added to Android.

## Required before enabling / removing old listeners
1. Trusted canonical before/after event adapter for ALL create/read/delete/accept/cancel and
   chat room unread changes, with durable retry. FCM success is NOT an authoritative change
   stream; missing devices, failed deliveries and duplicate pushes must not lose/double counts.
2. Authenticated snapshot/ack endpoint deriving UID from verified Firebase session, never body.
3. Read-only legacy baseline at a consistent source read time; use decimal-string nanoseconds
   (not JS Number), matching canonical event commit times and stable event IDs.
4. Graph: exact per-peer relationship state, not friend count. Calls: targeted inbox pointer
   validated against canonical signaling; a numeric call counter cannot route a call.
5. Device/emulator integration and real PostgreSQL multi-connection race tests, then explicit
   deployment/cutover. No Blaze/paid trigger is assumed or authorized by this commit.

Local validation: `npm test --prefix supabase` =34 passed:9 new real PostgreSQL/WASM tests
plus25 existing mocked backend tests. PGlite is test-only; Promise.all duplicates do not prove
multi-connection locking/load. The event ledger grows; no automatic pruning or lifetime-free
capacity guarantee is supplied. Event adapters and operational retention need a separate patch.

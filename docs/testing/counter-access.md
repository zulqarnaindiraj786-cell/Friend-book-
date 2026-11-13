# Private counter read/ack API — not deployed or connected to Android

POST `counter-state` with a real Firebase ID token in Authorization: Bearer.
`{"action":"snapshot","device_id":"registered-device-id"}` reads only the verified UID.
`{"action":"ack","device_id":"registered-device-id","kind":"messages","observed":"12"}`
acknowledges only that device's observed arrival total. Kind can also be notifications/requests.
Counts/totals/revisions are decimal strings (no JS precision loss); unknown/unseeded state
returns ready:false, never an invented zero. API has NO apply/seed/source-mutation action.

One private database RPC checks existing FCM-device ownership, applies an optional ack and
reads at most three counter rows. Device reassignment is locked during access. HTTP caller
cannot choose another UID, change canonical records or read device tokens/service credentials.
JWT validation pins Firebase project/issuer/RS256 and requires exp/iat/sub; local tests use
synthetic signing keys, NOT real production sessions. Body streaming is capped at2048 bytes;
RPC timeout8 seconds, no automatic retries. Responses are private/no-store.

Server-only SQL access window allows30 calls/account/minute across devices, returning429
with a60-second retry hint. This limits counter-processing abuse, NOT account counts or
correct badge values. It is NOT an Edge Function invocation/billing cap: rejected traffic
still invokes the function, so this does not guarantee zero spend or DDoS protection.

No deployment, billing plan change, paid trigger or old source-data update was performed.
No live rule/listener replacement until trusted canonical events + read-time baseline are
ready. Graph relationship projection and canonical-validated incoming-call routing remain.
Existing FCM verifier was factored into a shared module; existing gateway regression tests pass.
44 local backend tests pass. PGlite tests are not real multi-connection capacity certification.

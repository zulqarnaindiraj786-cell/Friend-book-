# Admin global Quota Saver mode

## Behavior

The Admin Panel now exposes a server-configured **Global Quota Saver** switch. It is stored as the typed optional `quota_saver_v1` field on the existing admin-only `config/ads` document, so it reuses the app's existing configuration snapshot listener rather than adding a new per-user D1 listener/read. Non-admin writes remain forbidden; malformed values fail closed. `adsView` returns only the allow-listed public config fields.

When enabled, only screen-owned automatic cursor-page initial loads may reuse an unchanged cached page for up to 15 minutes. Existing per-query invalidation from writes/push remains authoritative. Cold-cache reads, explicit user refreshes, user-triggered next-page reads, writes, authorization and privacy checks are unchanged. The default is OFF until the admin explicitly turns it on. Turning it off restores each screen's normal cache age.

## What it does not promise

This is a **soft saver**, not a per-user hard quota or a provider-enforced cap. It reduces repeated automatic list revalidation; it does not suppress necessary fresh reads or genuine user actions, and it does not bound total D1 rows read/written by a user. Cached pages may show unchanged content for longer between external changes; explicit refresh and local mutation invalidation remain available. Other app read families and trigger/index work are not covered by this switch.

## Validation boundary

Worker config authorization/type tests and Python source contracts were added; JVM policy tests cover OFF/on/cache ages/explicit refresh boundary. Production Worker validation and Android candidate verification must pass before delivery. No production config was toggled, no Worker deployed, no APK/AAB built, no merge, device trace or daily quota acceptance performed by the source change alone.

# Notification and repeated-room-lookup fixes

## Production state

Source d4e7d430de34639e91227283e988600e52e88e32 deployed successfully using the no-D1-migration pipeline:
https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45174184389
Provider-reported job completion: 2026-11-23T12:26:08Z. Deployment, binding/flag/cron preservation, health 200 and unauthenticated API 401 steps succeeded. Rollback metadata retained by pipeline. PR #36 remains unmerged. No APK update needed for these server-only changes.

## 1. Unread notifications: irrelevant exceptions no longer disable bounded paging

The bounded notification path previously tested for unusual timestamps/IDs in ALL recipient rows, even for an unread-only query. A read historical row could therefore force the entire unread inbox through the complete compatibility path.

The exception probe now applies the unchanged unread filter when the request is unread-only. Actual unread exceptions still trigger compatibility behavior; all-notification requests remain unchanged.

Regression fixture: 600 standard unread rows plus one read row with a string timestamp. A 20-item request now loads 20 inbox rows rather than the complete unread collection, and returns identical IDs to the complete reference path. When the exceptional row is changed to unread, compatibility is retained and the historical row appears in results. This is local rows-loaded proof, NOT billed D1 savings or proof that this condition caused every production notification read.

## 2. Repeated room ACL reads: request-local coalescing

`visibleDocuments` previously authorized each root message independently; many messages in the same room could repeatedly fetch identical canonical room membership. It now owns a map for that one visibility call, shared by its parallel workers. `chatReadAllowed`/`loadChat` coalesce the pending canonical-room promise within that map.

Local test: twenty parallel checks for one room use one lookup. Other rooms remain isolated. A new request uses a new map and sees membership revocation immediately. Storage failures remain rejected, not successful/empty results, and a new request retries. No global/TTL authorization cache exists. Generic single-document calls still read fresh membership.

This reduces duplicate room checks for matching root-message-list workloads. It does NOT eliminate all 1,905 observed point lookups, nor the existing-room chat-list scan.

## Validation and limitations

- 246 Worker tests pass.
- 372 Python tests pass.
- Local 30-user real Worker replay passes with network disabled; not D1 billing/capacity proof.
- Production schema unchanged; no new indexes or migrations.
- The earlier 185-read chat-membership-list shape, notification paths involving genuinely unread historical exceptions, and mirror-write caller attribution remain open. No measured production savings percentage or full-day target pass is claimed.
- Future strict daily verification must use the latest rollout boundary, not an earlier fix's timestamp.

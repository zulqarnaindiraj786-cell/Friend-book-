# Remaining-demand fixes: group invitations and legacy reactors

## Android changes

### Group invitation picker
- Removed `.collection("users").limit(200).get()` from `GroupDetailActivity.showInviteMembersDialog`.
- Uses actor-scoped CursorPager with document-ID ordering, 10 records per request, 5-minute exact-head reuse, explicit load-more/retry and cached-first display.
- Selected IDs survive row reconstruction when a page completes; existing checkboxes are restored before listeners are installed.
- One dialog at a time. Dialog dismissal closes its owned pager and clears references. Activity stop dismisses the dialog/closes any remaining pager; stale callbacks are rejected by CursorPager ownership/generation checks.
- Membership, banned-user and existing-invitation filtering and acknowledgement-before-notification remain unchanged. A filtered page does not automatically drain further pages.

### Legacy reaction people UI
- Removed individual `Fb.getUser(r.uid)` calls from the row renderer.
- Missing visible identities are hydrated with document-ID IN queries, at most 10 IDs per batch; cached/embedded profiles need no query.
- Attempts are marked before transport. Missing or failed profiles do not recursively retry on rerender. Dialog reopening/new data permits fresh attempts; no invented profile data.
- Actor/dialog guards reject stale responses. Per-paint token prevents completion from replacing a newer filter, including an empty filter.
- Post reaction cursor-page size and local reveal steps reduced from 30 to 10. Canonical D1 reactor routes are not claimed changed by this legacy UI patch.

## Verification

- 444 Python tests PASS (two new source ownership/pagination contracts).
- `ReactorProfileBatchDemandTest` invokes the actual page hydrator: 10 missing people across 100 render demands -> one bounded user query; zero individual `Fb.getUser` calls; an empty response cannot create a refetch/render chain; a dismissed dialog starts no further query.
- `FeedAuthorBatchDemandTest`, comment-conflict tests and existing pagination/cancellation tests remain in the full Android suite.
- Source assertions for invitation selection and dismissal are not a device dialog interaction test.

## Boundaries / remaining work

- Group post bulk loading is now replaced by an actor-scoped 10-item CursorPager. Pending and moderation queues use their own cursor-backed queries, and a pinned post outside the current page is resolved by explicit ID. This preserves access to older approvals without loading 120 posts up front; see `docs/verification/app-group-post-pagination.md` and `scripts/tests/test_group_post_demand.py`.
- Other outstanding audit items remain in `docs/quota/full-app-audit.md`: candidate-specific fresh relationship/profile checks, durable background push behavior, exactly-one-startup endpoint, Room migration, complete route billing attribution and real-device corrected-APK acceptance.
- No Worker/D1/backend changes, Supabase access, deployment, APK release or PR merge. Calculator untouched.
- Real one-user/minute billed reads, total Worker invocations and production quota savings remain unknown. Controlled demand results must not be substituted for those metrics.

Final code `20e9a0a`: Android production compile/full JVM suite PASS — https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45323834535 . Earlier run 45323789869 was superseded/cancelled by the empty-filter paint-token correction, not reported as green proof.

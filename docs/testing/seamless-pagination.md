# Seamless pagination UX changes

## Source changes in this iteration
- Near-end metadata loading for inbox, notifications and followers/following; native scrolling only (layout/initial hydration does not trigger it).
- Profile parent scroll routes demand to only the active posts, Reels or friends page. Search parent scroll requests the next bounded result pages.
- Comments load older pages near the relevant edge: upwards in chronological mode, downwards in newest/relevant modes. Older prepended comments retain the first visible comment ID/pixel offset.
- Shared ScrollPageGate permits one automatic attempt per list-size boundary. Scrolling away/re-entering or using the manual retry allows retry; no timers or retry storm while stationary. Empty/fully filtered lists retain explicit controls rather than reading every page automatically.
- Existing rows remain visible while a background page request changes loading state; avoid clearing/rebuilding unchanged profile, follower, notification, search and comment lists just to display a loading indicator.
- Existing feed/reel/watch/story loading remains in place. No offscreen full-video prefetch was added. Manual page/retry controls remain available, including reaction dialogs.

## Preservation and validation
No data migration, bulk deletion, auth/account reset, billing change, or media-storage move was performed. These are loading limits, not a limit on the number of users/records that may exist.

Local regression/source contracts and Java parsing were rerun for v2.8.28. New Java gate/candidate policy tests include 5000-reader models. Signed Android CI must be checked before claiming compilation/unit-test success; no device/network latency proof or real Firebase 5k-load certification is implied.

The checkout was reconciled non-destructively against the same branch's existing v2.8.27 commit, preserving local smooth-loading changes and the remote list/action rollout. No private credentials or binary backups were staged.

Four legacy graph/badge/call query paths still require a verified bounded replacement, as documented in remaining-rollout-2.8.27.md. These performance changes do not claim that every app-wide query is now bounded.

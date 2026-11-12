# v2.8.32 / code 64 — circle stories and bounded pages

- Open chat: latest 40, older 40 upward, fenced resume gaps 40; inbox remains 10. Failed history requests need explicit retry rather than another scroll request.
- Suggestions: 10 candidate documents per fetch (Home session cache and Friends). Filtering existing friends/self/blocked users can yield fewer visible people.
- Stories: 10 candidate documents per request; both cached previews and viewer apply own/friend/followed author policy, privacy, mute and block checks. Following does not grant Friends/Only me access. Exact-pair friend aggregate verifies viewer access without downloading a graph. Legacy global discovery query can yield fewer than 10 visible stories; it does not automatically drain strangers' pages. Home reuses fetched stories for 120 seconds; pull-refresh forces a request.
- Profile posts: 7 per page. Home remains 7; all other screen sizes retained.
- Shared cursor cache retains first-page `more` metadata; unchanged canonical revalidation does not emit another redraw. Fresh server reads still occur; this is not exactly-once lifetime network access.
- Settings: attach 12 existing local views initially and another 12 near the bottom on downward scroll with an inline next-frame spinner. All setting views are still constructed locally; search attaches all rows. No network request or artificial timer.
- Reels: remove competing full-file download during stream startup; eligible Cloudinary cellular URLs use existing 480p eco transform, Wi-Fi keeps 720p. Existing transformations, visible autoplay, manual pause and offscreen lifecycle guards preserved. Actual device/2G startup improvement has not been measured.

Local checks: 73 Python regression guards, 44 mocked backend tests, Java syntax parse passed. New JVM policy tests cover strangers, friends, following, privacy, blocks, mute and own stories; compilation and execution must pass signed-release CI before delivery. Firestore demo fixture has 483 writes (<500 batch limit); latest40/older40 and a 90-message fenced gap are SDK-tested in CI, not production. In-memory model: 5,000 virtual sessions, 240,000 simulated pages, no duplicate/missing IDs, zero real requests/devices; not capacity certification.

No production database migration, deletion, counter cutover, paid-resource activation or old-data reset.

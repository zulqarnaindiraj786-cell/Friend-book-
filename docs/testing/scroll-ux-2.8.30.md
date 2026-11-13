# v2.8.30 / 62 — scroll-loading UX correction

- Home first and subsequent metadata pages are7 posts (latest explicit user requirement).
  Initial cached paint is also capped at7; older posts remain reachable through the cursor.
- Scrolling near the bottom starts the next page. A real ProgressBar lives in the feed footer;
  ordinary Home pagination no longer requires a Load more button. Failure/fully-filtered-page
  fallback remains tappable, including first-page failure when no cursor exists. No auto-drain loop.
- Footer-only notifications preserve post rows/ad views; defer them outside RecyclerView layout.
  Pull spinner follows actual completion, not a420ms timer. Floating discovery pill stays hidden.
- Default Friends shows requests AND People you may know inline (separate20-record pagers).
  All friends, Sent and standalone Suggestions shortcuts remain. Request prefetch is allowed
  only before the suggestions header enters view; scrolling suggestions never inserts requests
  above the reader. Loading/retry rows belong to each section, not a fixed Refresh strip.
- Pull down in Friends retries/refreshes both pages. Empty/filtered suggestions retain an explicit
  continuation fallback rather than silently looking exhausted or querying page after page.
- Redundant Refresh strips hidden on shared legacy lists, notifications and inbox. Notification
  and inbox status controls move below the list. These legacy screens still have manual controls;
  this is NOT a claim that every old screen has an in-list automatic spinner.
- All four Friends shortcuts fit the tab row; no horizontal swipe to discover hidden chips.
  Explicit feed refresh supersedes an older in-flight page using the existing generation guard.
- No old account/content migration, deletion, backend deployment or counter cutover.

Local:66 Python/source regression checks,44 backend tests,145 Java sources parsed.
New Robolectric tests exercise actual feed-footer loading/retry/end states and recycled people
header/progress views; execution and release signing require Android CI.
5000-session in-memory model uses7/10/20/50/30/20 sizes:200000 simulated pages, no missing/
duplicate records, ZERO production requests/devices. Not a Facebook-latency or2G certification.
Physical-device scrolling, fling/slow-network gestures and production Firestore indexes remain
runtime checks. Four legacy graph/badge/call queries are unchanged by this UX correction.

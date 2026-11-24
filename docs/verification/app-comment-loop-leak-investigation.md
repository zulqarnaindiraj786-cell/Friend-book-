# Loop/leak investigation — Android only

## Confirmed source defects (not yet attributed to production quota)

### 1. Comment revision-conflict refetch chain

File: `post/CommentsEngine.java`, `Handle.d1Page` response processing.

Before: a GET captured local `revision`; if a local comment ACK/mutation changed that revision before response, callback immediately called `d1Page(first,background)` again. Every further conflict could start another GET, with no total bound or delay. This is conditional on repeated concurrent mutations; it is NOT evidence that simply opening Home loops or that this caused the owner's 20k reads/user.

After: conflict recovery has at most **three total attempts** (initial + two), each retry separated by **2 seconds**. Exhaustion leaves local state intact and displays manual retry. A pending retry owns the loading lock. Handle removal cancels its runnable and page deadline; account checks prevent old-actor reads. Ordinary HTTP failures remain manual retry, not an automatic storm.

New `CommentConflictLoopTest` drives the actual Handle with mocked transport and changed revisions: verifies no retry at 1999ms, next at 2000ms, three requests total and no further requests after a virtual minute; separate close test verifies a queued retry is canceled.

### 2. Destroyed Post Detail ownership leak

File: `post/PostDetailActivity.java`.

Before: `onStop` removed post listener/comment handle only for `isFinishing()`. Rotation/configuration destruction is not finishing, and there was no unconditional `onDestroy` cleanup. An old Activity could remain referenced through a listener or the static comment-handle registry.

After: `onDestroy` always unregisters the reaction observer, removes/nulls post registration and comment handle, then calls super. Source ownership guard checks the destruction path without an isFinishing condition. Actual Handle cancellation has its own behavioral test; no claim of a device rotation/network test.

### 3. Existing count-read retry timing

`CountReadRetryPolicy` was already bounded to three attempts, but first retry was after 1s. Both transient retries now wait 2s. Quota/auth/permission/query errors still do not retry. Existing policy tests updated, not removed. This is not a claim that every durable push retry policy was changed.

## Requested paths checked

| Concern | Result |
|---|---|
| Home bind API/listener | Home passes bundled engagement and sets `feedEngagementInPage=true`; inline comment preview uses cached/embedded rows, not an API. Non-bundled adapters still have conditional `D1Reactions.loadPost`; not declared universally network-free. |
| Missing-author feedback | Prior patch moved lookup to page submission, <=10 IDs/query. Attempt set is populated before query. Completion only caches and notifies adapter; bind does not call the author hydrator. Existing actual test verifies 100 repeated submissions do not start another query. |
| LiveData/Flow observer self-trigger | No specific production infinite observer loop proven. FastCache reaction observer repaints UI; do not label every notifyDataSetChanged a network loop. |
| Feed pagination | `loadingPosts` checked before work, set before transport, cleared on completion/failure/timeout; generation/actor checks reject stale results. No automatic empty-page drain. |
| Shared pager | `CursorPager.next` checks active/loading/more; ScrollPageGate permits one boundary attempt per loaded count and no layout/idle fetch. Existing tests retained. |
| Listener polling | Shared ListenerPollingPolicy already returns NEVER for all paths. Existing test covers foreground/background intervals. This does not imply durable background push is disabled. |
| Home lifecycle | onStop increments generations, cancels secondary task, removes reaction observer and stops watchers. |
| Post Detail lifecycle | Unconditional destruction cleanup added in this pass. |
| Comment conflict retries | Unbounded immediate response-to-refetch chain fixed in this pass. |
| Shared HTTP replay | CloudflareHttp disables connection retry and strips 503 Retry-After replay; authentication refresh is distinct. |

## One-user / one-minute evidence

**Real device Worker-call count: unavailable. Real billed D1 rows: unavailable.** No connected Android device, network screenshots, or matched one-user billing capture was obtained. No synthetic test is presented as a real user's log.

Existing instrumentation is already available in debug Android builds:
- Splash starts one 60s `CloudflareRequestTrace` window.
- Shared CloudflareHttp network interceptor counts actual attempts (including token-retry attempts).
- `FBQuotaWindow` logs START then an attempts/categories JSON summary locally. It adds no network or database logging.
- Capture on the test device with `adb logcat -v time -s FBQuotaWindow:I '*:S'`, then cold-launch and perform the same 60s feed/scroll sequence. Run baseline and corrected builds with identical actor/dataset/cache state. Release APK178 does not emit this debug-only trace.
- Scope is shared HTTP attempts, NOT every raw client, not necessarily every Worker invocation in the account, and NOT billed rows. To establish D1 reads/writes, correlate a controlled isolated session window with server/query row metrics, including retries/background/cron and other clients.

A threshold of 100 billed reads is an investigation trigger, not mathematical loop proof: indexed query cost, privacy filtering, full scans, fanout and retries can exceed returned-row counts without an infinite client loop. The owner's reset-before-usage observations remain valid; attribution is still required.

No quota calculator changes, backend changes/deployment, APK release, or PR merge in this investigation. Until corrected APK device evidence is available, the 5-user quota exhaustion root cause and daily outcome remain unresolved.

## Verification

- Code `cf56471` (conflict guard `f097de5` + destruction cleanup).
- 442 Python tests PASS.
- Android production compile/full JVM suite PASS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45323311033
- Controlled conflict test: three total comment GET starts with 2s gaps; a further virtual minute starts zero requests. Closing before scheduled retry leaves one initial GET only. This is behavioral test evidence, not a real one-user billed-row result.

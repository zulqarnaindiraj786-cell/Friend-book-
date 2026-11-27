# APK179 / 2.10.37 — local Loop Trace candidate

Owner authorized packaging the cumulative Android fixes and adding an easy trace/share button. No backend code changed in this tranche, so no Worker redeployment is needed to activate this device-local feature. PR36 remains unmerged; this is a signed prerelease candidate, not Latest/Play/auto-update.

## Owner steps
1. Install the signed APK as an update over the existing same-signature FriendBook app. Do not clear app data merely to install it.
2. Open **Settings → Loop Trace · Start / Stop & Share**.
3. Choose **Start 1-minute trace** for a short reproducible Feed/scroll test, or **Start 15-minute trace** to exercise Feed, profile, comments, search, requests, chat, stories/reels, groups and settings.
4. Use the app normally. Include a short background/foreground interval if testing background behavior. The label FG/BG is process visibility.
5. Before force-closing the process, return to the same button → **Stop & Share report**. Send the plain-text report to this conversation using Android's share/copy destination. The app does not automatically upload it.
6. **View counts / last report** previews it. **Delete trace** removes the saved local report. Starting again replaces the previous report.

A report is saved locally on explicit stop/share or automatic timeout. In-progress data is memory-only: process death before stop/timeout loses it. The user-facing dialog discloses this; capture short sessions first.

## What is captured
- User opt-in works in signed release builds (unlike the separate older debug-only app-entry logger).
- Shared Cloudflare HTTP attempt totals, bounded route/screen/method/FG-BG counts, most recent 160 start/end events, response status/error and time-to-response-headers.
- Known dynamic resource IDs are replaced with `:id`; unknown paths become `other`.
- Most recently resumed Activity is context, **not a guaranteed request-origin screen**. Async work can belong to an older screen.
- No query parameters, request/response bodies, authorization headers, tokens, IDs, message text or media contents are read into the report.
- Bounded key count with overflow aggregation. No per-click server logging, polling, database writes or new permissions are added by tracing.
- Exactly one trace is active; restarting increments generation so prior completion/timeout callbacks cannot finish the new session.

## What it cannot prove
- Not every raw URL/third-party/media client passes through shared CloudflareHttp. Trace is explicitly labelled shared HTTP only.
- Attempt counts are not billed D1 rows or necessarily the full account's Worker invocation count. Server cron, other clients, SQL filtering/index scans and downstream work need separate attribution.
- Repeated requests may be legitimate pagination/user actions. A high count is a lead, not automatic loop proof. It does not promise every remaining loop is fixed or detectable.
- Existing known Group posts 120-item path and other audit residuals are not silently claimed fixed by packaging.

## Build scope
Cumulative current-branch Android changes include operation sharing, feed/search/profile cache policies, bounded reaction actions, comment conflict-loop guard and destroyed-screen cleanup, group invite pagination and legacy reactor profile batching. Backend changes from earlier authorized deployments are separate; this build does not deploy Workers, migrate D1/Supabase or merge PR36.

Pure buffer tests cover opt-in/window boundaries, sanitization, 10,000-request bounded storage/exact attempt counts, reset/stop/error behavior. Full release build, signatures and final downloadable asset evidence to be recorded after CI succeeds.

## Final build/release proof
- Source: `5ca974116d91a26eeabee4c643ce8c58469b0d01` (initial feature `4487ead`, stop-duration/session isolation correction `5ca9741`).
- 444 Python tests PASS.
- Android compile/full JVM suite SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45324838240
- Signed release APK/AAB build SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45324838260 . Release JVM tests, actual-test-result gate and APK/AAB signature verification steps SUCCESS.
- Earlier build 45324714639 was superseded by the correction, not used for delivery.
- Signed prerelease (not Latest): https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/tag/friendbook-2.10.37-signed-5ca97411
- APK: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/download/friendbook-2.10.37-signed-5ca97411/friendbook.apk
- APK 4,980,305 bytes; GitHub asset metadata SHA256 `693a4b93a223880f19cfa5dd37af069abc3d5bf8e4d411aeb61d6d7952d3a801`.
- AAB 8,869,206 bytes; GitHub asset metadata SHA256 `f4a0043b71c5db0ac25fe31f8a1e8122ed5246f8e636288077a557df79bc6f27`.
- Checksums and R8 mapping are attached to the release. Metadata digests are not claimed as a locally downloaded-file hash.
- `git diff 6dfdebb..HEAD -- worker.js cloudflare` confirms no changed runtime Worker modules since the last deployed Worker; differences are already-handled migration0093 and tests. No redundant Worker deployment performed.
- Real installed APK/trace interaction and billed-row telemetry still require the owner's test. No claimed production quota result.

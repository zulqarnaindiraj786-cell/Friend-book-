# APK180 / 2.10.38: trace dialog controls fix

Owner screenshot of installed APK179 showed only description and Close. Root cause: native AlertDialog builder combined setMessage and setItems; the message panel took precedence over the list in this dialog implementation. The advertised tracing controls were inaccessible. Prior buffer-only tests did not exercise the dialog and missed this UI defect.

Fix: replace message/items combination with a custom scrollable vertical view containing explanatory text and six explicit Android Buttons: Start 1-minute, Start 15-minute, Stop trace, Stop & Share, View, Delete. Start/Stop change the dialog title immediately. A separate Stop saves the report without invoking Android share UI. Long content remains scrollable on small screens.

`LoopTraceDialogTest` creates the actual Activity/dialog, verifies all six buttons exist and are enabled/visible, clicks Start, records one shared request attempt, clicks Stop, verifies capture is off and the saved report has Attempts=1, then clicks Delete and verifies saved report removal. This is Robolectric evidence, not a physical-device screenshot. Buffer privacy/bounds tests retained.

- Source `37866c61222a5411fab89a2470e1148294cfd2f6`.
- 444 Python tests PASS.
- Android compile/JVM suite SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45326383977
- Signed release JVM/build/signature verification SUCCESS: https://github.com/zulqarnaindiraj786-cell/Friend-book-/actions/runs/45326384029
- Prerelease: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/tag/friendbook-2.10.38-signed-37866c61
- APK: https://github.com/zulqarnaindiraj786-cell/Friend-book-/releases/download/friendbook-2.10.38-signed-37866c61/friendbook.apk
- APK size 4,980,104 bytes. GitHub asset metadata SHA256 `6eda1a3bf02330d41e72dc1ad4ec5282327604eef51cbc2717da30b2c5ff280d` (not claimed local download hash).

Install APK180 as an update; do not uninstall/clear data just to update. Settings > Loop Trace > Start 15-minute; use app; return > Stop & Share. Keep process alive until capture saved. Shared HTTP only, not billed D1 rows or automatic detection of every loop. No backend/Supabase changes, no merge, no Play upload, no Latest/automatic update.

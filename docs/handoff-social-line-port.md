# Handoff: porting the Cloudflare-only work to the Play Store line (`com.friendbook.social`)

This branch (`arena/97139ab1-friend-book`) is the **test line** (`com.example.frienbook`, version
`525.0.0.102`). It is a reference only: no PR was opened from it, nothing was merged from it, and the
production Worker was never deployed from it. The Play Store line lives on `main`
(`com.friendbook.social`, v2.8.79 / versionCode 111) and already contains the Worker, the Analytics
Engine views, the D1 migrations 0001-0013, presence, R2 and the Reels spinner fix (PR #29).

What the test line added that the social line does NOT have yet, and exactly how to port it:

## 1. `core/CloudflareDocs.java` — the D1 mirror client (NEW file on the social line)

Source here: `app/src/main/java/com/example/frienbook/core/CloudflareDocs.java`.
Port: change the package to `com.friendbook.social.core` and replace the `CloudTransport` references
with the social line's existing `D1HttpTransport` (package-private `send(URL, method, token, body)`),
plus a small private `withToken` copied from the social line's `CloudflareViews` (or make
`D1HttpTransport.withToken` public and share it). Keep: `mirror()` (PUT `/v1/docs/<collection>/<id>`,
fire-and-forget), `list()` (GET, bounded 1-50), and `sanitize()` (FieldValue serverTimestamp becomes
the client clock, every other sentinel is dropped). The Worker side needs nothing: `/v1/docs` and the
`friendbook_cf_documents` table (migration 0011) already exist in production.

## 2. Mirror call sites (two one-line additions)

- `app/src/main/java/com/friendbook/social/post/CreatePostActivity.java` — in the
  `.addOnSuccessListener` of `Fb.db().collection("posts").add(m)`:
  `CloudflareDocs.mirror("posts", x.getId(), m);`
- `app/src/main/java/com/friendbook/social/reels/AddReelActivity.java` — same for `reels`.
  (Exact line shapes on this branch: commit `e8b4a3f`.)

## 3. `app/build.gradle` — one flag only

`buildConfigField 'boolean', 'CLOUDFLARE_DOCS_MIRROR', 'true'` next to the existing
`CLOUDFLARE_VIEWS_ENABLED` / `D1_INTERACTIONS_ENABLED`. Do NOT touch versionCode/versionName in the
same commit; the release bump (111 -> 112, '2.8.79' -> '2.8.80') is its own release commit.

## 4. CI quota fix (URGENT for `main`, independent of everything else)

- `.github/workflows/cleanup-artifacts.yml` — copy verbatim from this branch (commit `a8fdaa0`):
  weekly + manual deletion of every Actions artifact with the job token's `actions: write`.
  The account storage is full (488 artifacts, ~3 GB); until this lands on the default branch every
  `upload-artifact` on every line fails with "Artifact storage quota has been hit".
- `.github/workflows/build-apk.yml` — remove the `upload-artifact` steps (keep a `find
  app/build/outputs -name '*.apk'` listing step for visibility); signed binaries belong to GitHub
  Releases, which the publish step already handles.

## 5. Guards + docs

- `scripts/tests/test_cloudflare_view_counters.py` — port only `test_new_documents_are_mirrored_to_cloudflare_d1`
  (commit `f040dfd`), repathed to `com/friendbook/social`; the social line's existing guards stay.
- `cloudflare/test/android-release.test.js` — replace the hardcoded package assertion with a read of
  `applicationId` from `app/build.gradle` (commit `f040dfd`), so the metadata test can never again
  drift from the line it runs on.
- `docs/cloudflare-auth-only-migration.md` — rewrite the table for the social line: there, views are
  ALREADY on Analytics Engine and the Home Feed already does zero views work; the open items are the
  read-path switches (batch 2) and the remaining collections (batches 3-5).

## Explicitly DO NOT port

- The test line's `ReelsFragment` view wiring (the social line has the newer, proven one: count on
  every real watch, AE de-dupes each uid forever).
- `cloudflare/android-release.js` metadata from this branch (it names the test package/version).
- `StudioVectorIcons.java` (already on the social line), gradle versions, the test package name.

## Safety rules carried over

- The mirror is additive only: merge-upsert, no DELETE anywhere, Firestore stays the source of truth
  for reads until a later batch switches a read path with a union fallback.
- Play Store: same `applicationId`, monotonic versionCode, same CI signing, AAB + mapping from the
  same workflow, listing untouched, Play Console upload stays a manual owner step.
- No PR and no merge from any port branch without the owner's explicit go-ahead.

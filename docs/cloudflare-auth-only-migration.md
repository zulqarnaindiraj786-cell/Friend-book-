# Cloudflare-only backend: map of this line (`com.friendbook.social`, v2.8.80)

**The rule, final:** Firebase is **authentication only**. The Firebase ID token is the credential the
Worker verifies; no app document is ever created, read or written through Firestore, Firebase Storage
or the Realtime Database. Every Reel, Post, View, Like, Comment, Photo and Video lives in **Cloudflare**
(D1 + R2 + Analytics Engine), and v2.8.80 is the first step that ships in both directions: new
documents are mirrored *out* of Firestore (`/v1/docs`) and Reels *read* from Cloudflare first.

## Already on Cloudflare in this line

| What | Where | State |
|---|---|---|
| View counters | Analytics Engine (`friendbook_views`) + KV aggregate key, `/v1/views` | **Done** (v2.8.74–2.8.79). One event per real watch, AE counts each uid once per resource, zero D1 writes from the view path. |
| Reel views | same, `/v1/views/reels/<id>` | **Done** |
| Post views | same, `/v1/views/posts/<id>` | **Done** |
| Documents | D1 `friendbook_cf_documents` + `/v1/docs` (migration 0011) | **Live**, read by the app since v2.8.79 |
| View snapshots | KV, R2 snapshot fallback | **Done** |
| Video bytes | R2 / Cloudinary | **Done** |

## What v2.8.80 adds (this port)

1. **`core/CloudflareDocs.java`** — the mirror client for `/v1/docs`: `mirror()` (PUT, merge-upsert,
   fire-and-forget, 24 KiB client ceiling under the Worker's 32 KiB transport / 95 KiB Worker limit)
   and `list()` (GET, bounded 1–50). Firestore sentinels are sanitised: `serverTimestamp` becomes the
   client clock, every other sentinel is dropped, so no Firestore internals ever reach the Worker.
   Firebase appears only as `getIdToken(false)`.
2. **Mirror call sites** — every created/edited Post (`CreatePostActivity`, both the create and the
   edit path) and every published Reel (`ReelUploadManager`) now mirrors its metadata to D1 the moment
   the write succeeds. Additive only: there is no DELETE anywhere in the mirror.
3. **`CLOUDFLARE_DOCS_MIRROR = true`** in `app/build.gradle`, next to `CLOUDFLARE_VIEWS_ENABLED` and
   `D1_INTERACTIONS_ENABLED`, so the mirror can be switched off without a store release.
4. **Reels first open** — `ReelsFragment` asks Cloudflare **first** (`/v1/docs?collection=reels`) and
   falls back to Firestore only when the mirror has nothing to show, with a single settle point so
   "Load…" can never become a resting state, plus a session seed so a second visit paints instantly.
5. **CI quota** — no `upload-artifact` in the release workflow (signed APK/AAB/mapping go to GitHub
   Releases) and a new `cleanup-artifacts.yml` that drains the exhausted Actions storage weekly or on
   demand with `actions: write`.

## Batches still open

| Batch | Scope | Blocker |
|---|---|---|
| 2 | Switch the remaining **read paths** to D1 with a union fallback (feed, profile, chat list) | needs the mirror to have backfilled history first |
| 3 | **Reactions / comments / likes** (`D1_INTERACTIONS_ENABLED`) | the D1 interaction tables exist (0003–0008) but the gate is deliberately still `false` |
| 4 | **Photos / videos**: upload straight to R2 (Cloudinary leaves the write path) | `R2VideoClient` handles reels only today |
| 5 | **Chat, stories, notifications, groups, events, pages** write D1-first | mirror coverage of these collections first |
| 6 | Delete the Firestore SDK from the APK, drop the Firestore rules files | only after 2–5 are green in production |

Migration 0011 (`friendbook_cf_documents`) and the Worker routes are already deployed, so nothing in
this release needs a server change: it is additive on both sides.

## Safety rules carried over (unchanged)

- Additive only: merge-upsert, never a DELETE from this client.
- Firestore stays the source of truth for reads until a batch switches a read path with a fallback.
- Play Store: same `applicationId` (`com.friendbook.social`), monotonic `versionCode` (112), same CI
  signing, listing untouched, and the Play Console upload is a manual owner step.
- No PR, no merge and no store upload without the owner's explicit go-ahead.

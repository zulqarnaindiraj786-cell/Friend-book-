# FriendBook: Git-connected Cloudflare Worker

**Status:** additive backend foundation, NOT a complete Firestore replacement.
Firebase login and Cloudinary media remain untouched. The Android app still uses
its existing database; no users, posts, chats or files are migrated or reset.

Root `wrangler.toml` deploys root `worker.js` to existing `rough-firefly-dd7b`,
binding existing `friendbook-db` as `DB`. No credentials are committed. Do not
create a replacement database. Any previously exposed token should be revoked.

## Mobile Dashboard: connect Git once (no code pasting)

After merging PR #27 into `main`, open Workers & Pages, select the existing
`rough-firefly-dd7b` Worker, then Settings > Builds > Connect repository (labels
may vary). Authorize Cloudflare's GitHub integration and select:

- Repository: `zulqarnaindiraj786-cell/Friend-book-`
- Production branch: `main` (confirm this is the PR's merge target)
- Root directory: repository root (`/`), NOT `cloudflare` or `app`
- Build command: `npm ci && npm test`
- Deploy command: `npm run deploy` (pinned Wrangler; runs `wrangler deploy` only)
- Non-production branch deployments: disable for now
- If watch paths are offered: `worker.js`, `wrangler.toml`, `package.json`,
  `package-lock.json`, `cloudflare/**`

If Connect repository is unavailable, send a screenshot with private details hidden
so the correct Dashboard path can be identified. Do not create a duplicate Worker
or database or overwrite unrelated services to bypass an error.
GitHub integration authorizes builds inside Cloudflare; no token belongs in chat,
source code or Android. Merging alone does not connect a repository automatically.
Cloudflare must be authorized in the Dashboard once. Later production-branch pushes
then deploy automatically. No GitHub Actions Cloudflare API secret is required.

## Routes and verification

`GET /health` performs only `SELECT 1 AS connected`. After successful deployment:
`https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/health`
should return `{"ok":true}`. This verifies the binding, not the application schema.

`GET /v1/me` requires `Authorization: Bearer <Firebase ID token>`; verifies RS256
signature against Google's public keys plus project audience, issuer, expiry,
issued-at, subject and auth_time. A valid token reads only its own optional D1
profile. Missing/invalid tokens fail closed. No public records or write routes.
Cloudinary URLs may be stored in the optional profile table, but no upload,
credentials or storage behavior changes. This is not a Cloudinary upload backend.

## Optional schema: separate approval, never automatic on deploy

`cloudflare/migrations/0001_additive_profiles.sql` contains only `CREATE TABLE IF
NOT EXISTS friendbook_cf_profiles`. Deployment does NOT apply it. If approved,
execute that SQL in D1 Console or use Wrangler D1 migrations with appropriate
account authorization. No DROP, DELETE, UPDATE, restore, Time Travel or pruning.
Without the new table, `/health` works but authenticated `/v1/me` returns 503.
Existing app functionality is unaffected. No endpoint inserts profiles yet.

A full database switch needs an inventory of existing records/permissions,
backup, concurrent-write synchronization, migration validation and rollback.
Do not point the Android app at this scaffold as a replacement for Firestore.
Do not run Time Travel/restore; this PR does not alter retention settings.

Tests: `npm ci && npm test` (mock D1 plus real local RSA signature verification).
Bundle check: `npx --yes wrangler@4.157.0 deploy --dry-run` (not a real deployment).

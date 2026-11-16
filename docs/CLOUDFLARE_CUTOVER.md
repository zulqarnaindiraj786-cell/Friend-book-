# Cloudflare Cutover Runbook (Firebase = Auth + FCM only)

Goal: Firestore se data nikal kar Cloudflare D1 pe le jana, taake app lakhon users
sambhal sake — bina credit card, Firebase free tier ke 20,000 writes/day se aage.

**Kya pehle se ready hai (code):** `npm test` 66/66, `scripts/tests` 189/189, CI green.

---

## 0. Ab tak kya ho chuka

| Cheez | Halat |
|---|---|
| Document store queries (WHERE / ORDER BY / pagination) | ✅ `cloudflare/doc-query.js`, `/v1/docs` pe live |
| DELETE support (owner-only) | ✅ `/v1/docs/:collection/:id` |
| Android query DSL + paging client | ✅ `DocQuery.java`, `CloudflareStore.query/delete` |
| Realtime ka replacement | ✅ `DocWatch.java` + `DocDelta.java` (polling, delta-only) |
| App REST API (`/v1/posts`, `/v1/chats`, ...) | ✅ `data-api.js` wired, **flag off** (`DATA_API_ENABLED`) |
| Migration API | ✅ routed, **no default token** (fail-closed) |
| Migration script | ✅ fail-closed (token na ho to refuse) |
| D1 tables (34) | ✅ `cloudflare/migrations/` |
| **Worker deploy** | ❌ is branch se nahi hota |
| **Firestore → D1 data copy** | ❌ chala nahi |

---

## 1. Worker deploy karna (ek baar, aap)

Deploy workflow sirf `main` pe chalta hai aur `CLOUDFLARE_API_TOKEN` secret maangta hai.
Main us tak nahi pahunch sakta (403). Aap ye karein:

1. GitHub → repo → **Actions** → **"Deploy existing Cloudflare Worker (D1 OFF)"** → **Run workflow** → branch `main`.
2. Agar `CLOUDFLARE_API_TOKEN` secret set hai to deploy ho jayega (pehle 12 Nov ko hua tha).
3. Verify: `https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/health` → `{"ok":true,...}`

> `wrangler deploy --dry-run` yahan sandbox mein pass hota hai, matlab code deploy-able hai.
> CI ka alag check "Workers Builds: rough-firefly-dd7b" **har commit pe** fail hai (purana,
> code se related nahi) — us se deploy ko mat rokna.

## 2. Migration token set karna

```bash
npx wrangler secret put MIGRATION_ADMIN_TOKEN
# 32+ characters ka random string do, e.g. openssl rand -hex 32
```
Iske baghair `/v1/admin/migrate/*` 503 deta hai — jaan bujh kar. Koi default token nahi hai.

## 3. Data copy (Firestore → D1)

> **Pehle ye parho - write path ab do jagah likhta hai.** Migration script
> normalised tables (`users`, `posts`, `chats`, ...) mein rows likhti hai, lekin
> app har document `/v1/docs` se padhta hai, jo `friendbook_cf_documents` se
> aata hai. Yeh do shapes pehle kabhi jude nahi the - migration "1,234 migrated,
> 0 errors" bol sakti thi aur app bilkul khaali rehta tha. Ab `migration-api.js`
> har document dono jagah likhta hai (`mirrored` field response mein aata hai).
> `mirrorErrors` 0 hona chahiye; agar nahi, to `/v1/docs` wali copies fail ho
> rahi hain aur app ko data nahi dikhega.
>
> **Blocker (verified 2026-11-16 04:12 UTC):** Firestore ne
> `ResourceExhausted: 429 Quota exceeded` diya ek single-document read par bhi.
> Spark plan ke 50,000 reads/day khatam the, is liye copy 00:00 UTC reset ke
> baghair nahi chal sakti. Kuch bhi zaya nahi hua: worker ka `/verify` har D1
> table 0 batata hai aur Firestore ko chhua hi nahi gaya.

Run the pipeline: Actions -> **Migrate Firebase to Cloudflare**.

It first proves itself against the live worker, then copies, then verifies:

1. `Apply D1 schema` - `wrangler d1 migrations apply friendbook-db --remote`
2. `Make sure the worker has a migration token` - uses `MIGRATION_ADMIN_TOKEN`
   if it is a repository secret, otherwise generates one for the run and
   installs it on the worker
3. `Confirm the worker routes are live` - must get HTTP 200 from
   `/v1/admin/migrate/verify`; 401/403/503 each print a specific cause
4. `Run Firestore to D1 Migration` - probes Firestore with one read first, so a
   quota or credential problem is reported as such instead of as "0 documents"
5. `Verify Migration` - compares counts; this step no longer swallows failures

**Canary pehle, phir batches.** Ek dum se poora dump mat maaro:

| Run | `max_docs` | `only_collections` | Kya check karna hai |
|---|---|---|---|
| 1 (canary) | `100` | `users,posts` | `errors=0`, `mirrorErrors=0`, share kiya hua post app mein dikhta hai |
| 2 | `500` | *(khaali = baaki sab)* | `mirrored` = `inserted` |
| 3 | `0` | *(khaali = poora)* | `/verify` ke counts Firestore se match karein |

Canary green hone tak batch 2 mat chalao. Har run ke baad `/verify`.


Laptop + Firebase service-account key chahiye. GitHub Actions se bhi ho sakta hai:

Actions → **"migrate-to-cloudflare"** workflow → Run workflow. Ye 4 secret maangta hai:
`FIREBASE_ADMIN_SERVICE_ACCOUNT`, `CLOUDFLARE_API_TOKEN`, `CF_ACCOUNT_ID`, `MIGRATION_ADMIN_TOKEN`.

Script ab: token na ho to **start hi nahi hoti** (exit 2), aur 0 documents migrate
hue to **success report nahi karti** (exit 3). Purana behaviour "skipping actual upload"
+ success return karta tha — wahi data-loss pattern tha.

## 4. Verify (migrate se pehle aur baad)

```bash
curl -H "Authorization: Bearer $MIGRATION_ADMIN_TOKEN" \
  https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/admin/migrate/verify
```
Firestore counts se milao. **Counts match na hon to aage mat barhna.**

## 5. Read path - pehle se ON hai

`wrangler.toml` ke `[vars]` mein `DATA_API_ENABLED = "true"` ab committed hai
(v2.9.0 ke saath). Yeh `/v1/posts`, `/v1/chats`, `/v1/users/...` routes kholta hai.
Off karna ho to value hata kar redeploy - purana behaviour turant wapas.

## 6. App - pehle se ON hai

`app/build.gradle` mein `CLOUDFLARE_DATA_ENABLED = true` ab committed hai (v2.9.0,
versionCode 126). Auth + FCM + Crashlytics Firebase pe hi rehte hain; baaki data
plane Worker + D1 hai.

## 7. Force update - Play Store ke baghair kuch nahi

`ForceUpdate.java` launcher par synchronous gate chalata hai:

- `MIN_SUPPORTED_VERSION_CODE = 126` (v2.9.0). Isse purana build aage nahi barhta.
- Dialog `cancelable(false)` hai, BACK khata hai, aur sirf ek button hai:
  **"Play Store Se Update Karo"** -> Play listing.
- Koi skip nahi, koi "baad me" nahi, koi GitHub/APK link nahi.
- Upar se Play In-App Updates **Immediate** mode chalta hai (Play se install hui
  builds ke liye). Woh actual replace karta hai; gate usko rokta nahi.
- `/v1/android-release` ka `minVersionCode` remote floor hai. Policy server na mile
  to gate apni baked-in floor hi use karta hai - network down ka matlab "up to date"
  kabhi nahi.

---

## 8. Data plane - kya D1 par hai

v2.9.0 (versionCode 126) ke baad:

| Cheez | Kahan |
|---|---|
| Feed (page 1 + pages), Reels card | D1, `DocQuery` -> `/v1/docs` |
| Profile (`users/{me}`) | D1 (`hydrateMeFromD1`) |
| Naye post / chat / story / reel / friend / live writes | D1 (mirror) + Firestore queue |
| Document writes (91 sites) | D1 mirror (see `DataRouter`) |
| Auth, FCM, Crashlytics, App Check | Firebase |
| Firestore network | **OFF** (`Fb.disableFirestoreNetwork()`) |

Yeh screens D1 se padhti hain: feed, profile, **chat list, friends, notifications,
settings, groups, live**. Inhe Firestore cache ki zaroorat nahi - naye install par
bhi yeh seedha D1 se bharengi.

Counters aur member lists (`friendsCount`, `followers`, `invitedUids`,
`unreadCount`) `PATCH /v1/docs/{collection}/{id}` se jaate hain, jisme
`increment` / `arrayUnion` / `arrayRemove` server par lagte hain. Firestore ke
`FieldValue.increment` sentinels ko seedha D1 mein likhna galat tha - woh object
hain, number nahi, aur counter hamesha ke liye kharab ho jata.

Batch writes ab mirror hote hain: 35 `batch.set/update/delete` calls, das files
mein. Har ek ka mirror uske apne reference se banta hai, prefix guess nahi kiya
jata.

Firestore par ab bhi jo baaki hai: `auth` (login), FCM (push), Crashlytics, App
Check. Yeh data plane nahi hain.

## Rollback

Koi bhi step reversible hai:

| Problem | Wapas kaise |
|---|---|
| Data API galat behave kare | `DATA_API_ENABLED` hata dein, redeploy |
| App kharab lage | `CLOUDFLARE_DATA_ENABLED=false` karke naya APK |
| Migration adhoora | Firestore chhua hi nahi gaya — data wahi hai. D1 ko `DELETE`/re-run |

App kabhi Firestore **delete** nahi karta, is liye adhoora migration data loss nahi karta.

---

## Scale numbers (kyun ye kaafi hai)

| | Firebase Spark (free) | D1 (free) |
|---|---|---|
| Reads/day | 50,000 | 5,000,000 |
| Writes/day | 20,000 | 100,000 |
| Storage | 1 GB | 5 GB |
| Card | — | — |

Presence typing writes ab 0 hain (Cloudflare socket pe), heartbeat 2 min. Lekin 200 users
bhi Firebase ke 20,000 writes/day ke qareeb hain — **poora cutover hi asli hal hai**,
presence tuning akela kaafi nahi.

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

## 5. Read path on karna

`wrangler.toml` mein `[vars]` ke andar:

```toml
DATA_API_ENABLED = "true"
```

Phir deploy dobara. Ye ek switch hai — off karne se turant purana behaviour wapas.

## 6. App

`app/build.gradle`:
```gradle
buildConfigField 'boolean', 'CLOUDFLARE_DATA_ENABLED', 'true'
```
`CfClient` pehle se `/v1/posts`, `/v1/chats`, `/v1/users/...` call karta hai — routes ab
maujood hain. Auth + FCM Firebase pe hi rehte hain.

---

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

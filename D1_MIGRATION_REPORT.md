# Firebase = auth only, saara data D1 par — FINAL report (v2.8.81, versionCode 113)

Branch: `arena/362c2faf-friend-book` (base `91164516`, HEAD `da0adc6`)
Build: **CI green** — `Build FriendBook Signed APK and AAB` ✅ + `Validate Cloudflare Worker` ✅ + `Deploy existing Cloudflare Worker` ✅
Artefacts: `friendbook.apk`, `friendbook-playstore.aab`, `friendbook-mapping.txt` (tag `v2.8.81-test-362c2faf-friend-book`, target = HEAD)

---

## 1. Kaam kya hua (6 commits)

| commit | kaam |
|---|---|
| `60f76dd` | **batch-6**: app ke saare writes D1 par — naya chokepoint `core/D1Write` (write/create/add/put/delete), offline par `CloudflareOutbox` queue, chat flat rows (`chats/<id>` + `messages/<chatId>_<id>`), worker counters/lists/deep-merge |
| `64d269c` | **batch-7**: live rooms, admin broadcasts, dono background workers (PushFanoutWorker, OfficialBroadcastWorker) D1 par; 5 compile errors fix |
| `a8f8b71` | **batch-8**: do instrumented tests jo D1 move ne invalid kar diye the (transport 64 KiB ceiling, comment id device par) |
| `4846c66` | **batch-9**: profile reads D1-first (`Models.User.fromMap`, `Fb.getUser`, drawer card, blocked list, profile header) |
| `0502ffa` | **batch-10**: Active Now strip D1-first |
| `c8ead3f`, `da0adc6` | release v2.8.81 + Worker deploy is branch se |

## 2. Contract jo ab lagta hai

- **Firestore = auth only + purani history ka read.** Koi naya write nahi (`addSnapshotListener`/`update`/`set`/`delete`/`batch` sirf flag-off fallback ke andar ya purani history padhne ke liye).
- Har write `core/D1Write` se: D1 accept → success callback; D1 fail → outbox (offline replay, account-scoped); Firestore statement sirf `CLOUDFLARE_DOCS_MIRROR=false` build mein.
- **Chat:** `chats/<chatId>` (participants, lastMessage, lastMessageTimeMs, unreadCount) + `messages/<chatId>_<messageId>`; read D1 poll, D1 khaali ho to hi purana listener.
- **Live:** saare 20 packet (join/chat/reaction/gift/poll/co-host/PK) flat `live_chat/<liveId>_<id>` rows.
- **Broadcast:** job row + progress D1 par, delivery per-account notification row deterministic id se (replay safe).
- **Worker (`cloudflare/app-documents.js`):** naye collections (`live_chat`, `officialBroadcasts`), notification row recipient ki owned, message dono ends se readable, list add/remove ownership stored owner se judge hoti hai (`interested`, `favoriteUids`, `savedReelIds`, `membersAdd/Remove`…), `uid` page order.

## 3. Verify (jo main actually chala saka)

- `python3 -m unittest discover -s scripts/tests` → **182 tests OK**
- `node --test cloudflare/test/*.test.js` → **62 pass / 0 fail**
- `tree_sitter_java` parse over `app/src` → **0 parse errors**
- GitHub Actions: APK+AAB build **success**, worker validate **success**, worker deploy **success** (live Worker par naya code)
- Baaki Firestore writes ka AST audit (tree-sitter, sirf lambda/flag-off fallbacks bache) → **0 unexpected sites**

## 4. Honest gaps (jhooth nahi bolunga)

1. **Real device par maine app nahi chalaya** — CI compile + unit/instrumented tests + code contract se verify kiya, actual phone par end-to-end flow nahi.
2. **Live Worker par D1 rows ka read/write test nahi kar paaya** — sandbox se workers.dev tak network pahunch nahi hai (curl 000). Deploy green hai, par "production par ek row likhi gayi" ka proof mere paas nahi.
3. **Kuch writes flag-off fallback mein Firestore statement rakhte hain** (design ke mutabiq). Build mein flag `true` hai, is liye wo branch chalti nahi — par code mein maujood hai.
4. **Purani history migrate/backfill nahi ki** (aapka decision) — jo document pehle Firestore mein tha wo ab bhi wahi se padha jaata hai; naye build ki nayi row D1 par.
5. **Read paths sab D1-first nahi hue** — feed/reels/stories/chat/comments/notifications/profile/blocked/Active Now D1-first hain; kuch secondary screens (jaise Groups/Pages ke andar ke detail listeners, Live ka room document) abhi bhi purane listener se update hote hain (writes phir bhi D1 par hi jaate hain).
6. **D1 query surface ki seemayein**: `order` sirf whitelisted fields par, `contains`/`in` filters Worker JS mein lagte hain (400-row bounded window), aur page 50 rows max — ye purane Firestore paging se kam flexible hai.

## 5. Aage kya (agar chahein)

- Baaki secondary read paths ko bhi D1-first karna (Groups detail, Pages detail, Live room doc, Admin screens).
- Worker par naye index/order fields add karna jahan screen ko chahiye.
- Ek device smoke-test: register → post → comment → chat → live → notification, har step ke baad D1 row check.

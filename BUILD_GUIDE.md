# friendbook 2.8.92 — Build bina GitHub Billing ke

Aap ke paas 2 raaste hain. **Path A (local build)** recommended hai — koi billing,
koi limit, koi 16 din ka intezaar nahi.

---

# PATH A — Local Build (recommended, 100% free)

Aap ke computer par ek dafa setup karna hai, phir har build ~2-5 minute.
Main ne sab kuch ready kar diya hai: `./build-apk.sh` aur Gradle wrapper.

## A1. Android Studio install karein (free)

https://developer.android.com/studio → download → install.
Iske andar **JDK 17** aur **Android SDK** dono aa jate hain — alag se kuch
install karne ki zaroorat nahi.

Pehli dafa khulte waqt Android Studio khud SDK download karta hai. Agar na kare:

**Android Studio → Settings → Languages & Frameworks → Android SDK**
- **SDK Platforms** tab → ✅ **Android 16.0 (API 36)**
- **SDK Tools** tab → ✅ **Android SDK Build-Tools 36**
- ✅ **Android SDK Platform-Tools**
- **Apply** dabayein

## A2. Project ke saath 3 files rakhna zaroori hai

Yeh files security ki wajah se Git se hata di gayi hain (repo public ho to leak
na ho). Aap ke paas **pehle se** hain — project folder mein maujood hain:

```
Friend-book-/
├── app/
│   ├── friendbook-release.p12     ← signing key  (ZAROORI, warna APK unsigned)
│   └── google-services.json       ← Firebase config (already in repo)
├── build-apk.sh                   ← build script
└── gradlew                        ← Gradle wrapper
```

> `app/friendbook-release.p12` aap ke isi repo mein pehle tha — ab `.gitignore`
> mein hai magar aap ke computer par file mojood rahegi. **Ise kabhi delete na karein
> aur kabhi share na karein** — yeh aap ki app ki pehchaan hai. Isi key se app
> update hoti hai.

## A3. Build chalayein

**Windows:**
```bat
cd Friend-book-
build-apk.bat
```
(ya Android Studio mein: **File → Open** → project folder → khulne dein →
**Build → Build Bundle(s)/APK(s) → Build APK(s)**)

**macOS / Linux:**
```bash
cd Friend-book-
chmod +x build-apk.sh
./build-apk.sh
```

Agar SDK khud na mile to path de dein:
```bash
./build-apk.sh "$HOME/Android/Sdk"                      # Linux
./build-apk.sh "$HOME/Library/Android/sdk"              # macOS
```

Script yeh sab khud karti hai: JDK check → SDK check → missing components
install → keystore check → `assembleRelease` → APK copy.

## A4. APK kahan milegi

```
Friend-book-/friendbook-2.8.92.apk
```

Yehi file phone par bhejein aur tap karein → **Update / Install**.

---

# PATH B — Repo Public kar ke free Actions

Public repository par GitHub Actions **bilkul free** hoti hai — aap ka $12
spending limit use hi nahi hota.

## B1. Public karne se pehle — security (yeh main ne khud kar diya hai)

Maine keystore ko Git se hata diya hai, is liye **signing key leak nahi hogi**:

- `app/friendbook-release.p12` → `.gitignore` mein add + Git se untracked
- `app/build.gradle` ab keystore/passwords **properties ya env vars** se leta hai
  (koi hardcoded password nahi)
- CI workflow keystore ko `FB_KEYSTORE_B64` **secret** se restore karta hai

### ⚠️ Iske bawajood 2 cheezein public ho jayengi (aap ka faisla):

1. **`app/google-services.json`** — Firebase API key. Yeh Android mein
   **design se public** hota hai (har installed APK ke andar hota hai), magar
   public repo par bots ise scan karte hain. Firebase console mein
   **API key restrictions** (Android app + SHA-1) laga dena behtar hai.
2. **`wrangler.toml`** — Cloudflare account ID, D1 database ID, Cloudinary
   cloud name. Yeh secret nahi hain, magar infrastructure detail public ho jayegi.

> Agar yeh theek na lage to **Path A use karein** — kuch bhi public nahi karna padega.

## B2. Keystore secret add karein (ek dafa)

APK **signed** honi chahiye, warna phone update reject karega. Is liye keystore
ko GitHub secret mein daalein.

**Local se base64 banayein:**
```bash
# macOS / Linux
base64 -w0 app/friendbook-release.p12     # Linux
base64 -i app/friendbook-release.p12      # macOS
```
```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("app\friendbook-release.p12"))
```

**GitHub par:** repo → **Settings → Secrets and variables → Actions →
New repository secret**
- Name: `FB_KEYSTORE_B64`
- Value: upar wala pura base64 (ek line, ~3700 characters)

Agar aap chahein to passwords bhi secret se de sakte hain (optional):
`FB_STORE_PASSWORD`, `FB_KEY_PASSWORD` — default `friendbook2026` hai.

## B3. Repo public karein

Repo → **Settings → General** → neeche **Danger Zone** → **Change visibility**
→ **Change to public** → naam likh kar confirm.

## B4. Build chalayein

Public hone ke baad Actions free hai. Repo → **Actions** → **"Build FriendBook
Signed APK and AAB"** → **Run workflow** → branch `arena/f6bf61bd-friend-book`
→ **Run workflow**.

~10-15 minute baad **Releases** mein `v2.8.92` aa jayegi jismein:
- **`friendbook.apk`** ← yehi install karni hai (signed release)
- `friendbook-playstore.aab` — Play Store upload ke liye
- `friendbook-mapping.txt` — R8 map

---

# Install kaise karein (dono paths ke liye same)

1. **`friendbook.apk`** download karein — **`app-debug.apk` NAHI**
2. Purani app **uninstall na karein**, data **clear na karein**
3. APK par tap → **Update / Install**

Yeh purani app ke **upar** update hogi (same package `com.friendbook.social`,
versionCode **124** > 113), aap ka data — chats, posts, friends — bacha rahega.

### Agar "App not installed" aaye

Iska matlab purani app kisi **doosri signing key** se install thi. Check karein:

```bash
# purani installed app ki signature
adb shell dumpsys package com.friendbook.social | grep -A2 signatures

# nayi APK ki signature (fingerprint)
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs friendbook-2.8.92.apk
```

Agar SHA-256 match na kare to sirf us surat mein uninstall karna padega.
Pehle mujhe batayein, main check kar dunga.

---

# Kya 2.8.92 mein theek hua (yaad dahani)

| # | Masla | Status |
|---|---|---|
| 1 | Purani app bheji gayi / alag install hui (`com.example.frienbook`, vc 6) | ✅ ab `com.friendbook.social`, vc 124 |
| 2 | Workflow sirf **debug** APK publish karta tha | ✅ ab **signed release** APK |
| 3 | Group Chat tap karne par kuch nahi hota tha | ✅ fix (`chat_id` + `is_group`) |
| 4 | Reel tap → Home wapas | ✅ fix |
| 5 | Story Viewer kaali screen | ✅ listeners pehle + poster frame |
| 6 | Ad queue duplicate/stale ad | ✅ fix |
| 7 | `AdsConfig.java` duplicate methods (compile error) | ✅ fix |
| 8 | Play Store link | ✅ `com.friendbook.social` (listing live) |

Aur ek **naya guard**: build fail ho jayega agar `applicationId` badle ya
`versionCode` 121 se barha na ho — yeh ghalti dobara nahi ho sakti.

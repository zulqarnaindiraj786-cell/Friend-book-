# friendbook v2.8.92 (versionCode 124) — kya theek hua

Branch: `arena/f6bf61bd-friend-book`

---

## 1. "Purani app aa gayi / nayi alag install ho rahi hai" — asli wajah

Do alag masle the, dono ab theek hain:

**A. Galat APK bheji gayi thi**

Jo APK aap ko di gayi thi (`v2.8.89-6-20261115-052959`) wo **purane skeleton** se bani thi:

| | Purani bheji gayi APK | Asli app | Ab (2.8.92) |
|---|---|---|---|
| Package | `com.example.frienbook` | `com.friendbook.social` | `com.friendbook.social` ✅ |
| versionCode | 6 | 113 | **124** ✅ |

Package naam alag hone ki wajah se Android ne usay **bilkul nayi app** samjha —
isliye wo alag se install hui aur update nahi hui.

**B. Build workflow sirf DEBUG APK publish kar raha tha**

Us branch ka workflow sirf `app-debug.apk` aur `.aab` upload karta tha —
signed release APK **kabhi** publish nahi hoti thi. Ab `main` ka proven workflow
lagaya hai jo publish karta hai:

- `friendbook.apk` — **signed release** (yehi install karni hai)
- `friendbook-playstore.aab` — Play Store upload ke liye
- `friendbook-mapping.txt` — R8 deobfuscation map

Aur ek **naya CI guard** lagaya hai jo build fail kar dega agar:
- `applicationId` `com.friendbook.social` se badal jaye (warna alag app install hogi), ya
- `versionCode` 121 se barha na ho (warna Android upgrade reject karega), ya
- signing keystore `app/friendbook-release.p12` ghayab ho.

Yaani yeh ghalti dobara khud-ba-khud nahi ho sakti.

---

## 2. Group Chat fix

**Bug:** chat list mein group par tap karne se kuch nahi hota tha.

Chat list group ko `"chatId"` key se kholti thi, lekin `ChatActivity` `"chat_id"`
padhta hai — aur group mein koi `uid` (peer) nahi hota, is liye activity
foran `finish()` ho jati thi.

**Fix:** group ab `chat_id` + `is_group=true` ke sath khulta hai.

## 3. Reel tap fix

**Bug:** Home feed ke Reels card par tap karne se wohi Reel na khul kar Home tab
wapas aa jata tha.

**Fix:** `focusFeaturedReel()` ke baad function `select(tab)` par gir raha tha jo
tab ko dobara show kar ke Reel ko mita deta tha. Ab wahan se `return` ho jata hai.

## 4. Story Viewer black screen fix

**Bug:** video story par kaali screen.

**Wajah 1:** `setOnPreparedListener()` **`setVideoURI()` ke baad** register ho raha
tha. Cached video foran prepare ho jati hai, listener khaali chala jata hai,
playback start hi nahi hota → kaali screen.
**Fix:** ab saare listeners `setVideoURI()` se **pehle** register hote hain.

**Wajah 2:** buffer hone tak screen mukammal kaali thi.
**Fix:** ab video ka asli **poster/thumbnail** neeche dikhta hai
(`ReelVideoCache.deriveThumbUrl`, Cloudinary `w_480,f_webp`) jo pehla frame
aate hi hat jata hai. Video fail ho to poster dikh kar agla story chal jata hai
— kaali screen kabhi nahi.

## 5. Ad Queue (ads) fix

- Delivery par ad queue se **nikal** diya jata hai. Pehle wohi ad queue mein bhi
  reh jata tha, is liye wo **do baar** dikhta tha aur pull-to-refresh ke baad
  **stale** ad aata tha.
- `AdsConfig.java` mein 10 queue methods **do dafa** defined the — yeh hard
  **compile error** tha, ab remove kar diya.

## 6. Play Store link

App ke **tamam** copy/share actions ab yeh link dete hain:

```
https://play.google.com/store/apps/details?id=com.friendbook.social
```

Yeh listing live hai (Play Store par "friendbook", developer Zulqarnain Abbas).

---

## Install kaise karein (yehi zaroori hai)

1. Neeche wali release se **`friendbook.apk`** download karein
   (**`app-debug.apk` NAHI** — wo is baar publish hi nahi hoti).
2. Purani app ko **uninstall na karein** aur data **clear na karein**.
3. APK par tap karein → **Update / Install** dabayein.
   Same package (`com.friendbook.social`) aur barha hua versionCode (124 > 113)
   hone ki wajah se yeh purani app ke **upar** update ho jayegi, aap ka data
   (chats, posts, friends) bacha rahega.

> Agar phone kehti hai "App not installed" to is ka matlab purani app kisi
> **doosri signing key** se install thi. Us surat mein sirf tab uninstall karna
> padega — lekin pehle mujhe bata dein, main signing check kar dunga.

---

## ⚠️ Abhi ek rukawat hai: GitHub Actions billing

Build abhi **chal hi nahi raha**. Har run 3–4 second mein fail ho jata hai,
koi step start nahi hota, aur annotation yeh hai:

> The job was not started because recent account payments have failed or your
> spending limit needs to be increased. Please check the 'Billing & plans'
> section in your settings.

Yeh **code ka masla nahi** hai — GitHub ka billing/spending-limit block hai.
Yeh theek karte hi build chal kar **v2.8.92 ka signed `friendbook.apk`**
automatically release ho jayega.

**Kya karein:** GitHub → Settings → **Billing & plans** → payment method theek
karein ya spending limit barhayein.

## Jaanch ka status (imaandari se)

- ✅ Saare 6 code fixes review kiye gaye hain, braces/parens balance verify kiya
- ✅ Repo ke 174 existing tests chalaye — jaise the waise hi (10 failures, jo
  pehle bhi the; mera change ek test aur theek kar gaya)
- ✅ Naya workflow YAML parse-verified hai (24 steps, sahi)
- ⚠️ **Compile locally verify nahi ho saka** — is sandbox mein JDK aur Android
  SDK nahi hai, aur CI billing ki wajah se ruka hua hai
- ⚠️ Signed APK abhi bani nahi — billing theek hone ke baad hi banegi

Jab build ho jaye, main aap ko `friendbook.apk` ka direct link de dunga.

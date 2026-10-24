# Firebase setup checklist — FriendBook

The app talks to project **friendbook-17551** (`app/google-services.json` is real and
already included). Follow these steps **once** in the
[Firebase console](https://console.firebase.google.com/project/friendbook-17551).

## 1. Authentication
- **Build → Authentication → Sign-in method → Email/Password → Enable** (both toggles off/on as needed; no email link required).

## 2. Cloud Firestore
- **Build → Firestore Database → Create database** → start in **production mode** → pick a region close to you.
- **Rules tab:** paste [`firebase/firestore.rules`](firebase/firestore.rules) → **Publish**.

## 3. Realtime Database (presence + typing)
- **Build → Realtime Database → Create database** → location **United States**
  (so it matches `https://friendbook-17551-default-rtdb.firebaseio.com`) → **Locked mode** is fine.
- **Rules tab:** paste [`firebase/database.rules.json`](firebase/database.rules.json) → **Publish**.
- If your RTDB URL is different (e.g. `.europe-west1.firebasedatabase.app`), copy it into
  `Fb.RTDB_URL` in `app/src/main/java/com/example/frienbook/core/Fb.java`.
- *Note:* if RTDB doesn't exist, the app still works — presence/typing gracefully fall
  back to Firestore-only.

## 4. Cloud Storage
- **Build → Storage → Get started** → production mode.
- **Rules tab:** paste [`firebase/storage.rules`](firebase/storage.rules) → **Publish**.

## 5. Android app registration (package name)
Firebase already knows `com.example.friendbook` (the Flutter app).
This native app uses **`com.example.frienbook`**. You have two options:

**Option A — recommended:** In *Project settings → Your apps* add an Android app with
package `com.example.frienbook`, download the new `google-services.json` and replace
`app/google-services.json` with it.

**Option B — keep the included config:** The included `google-services.json` already
contains a client entry for `com.example.frienbook` and will build & run fine.
If Firebase rejects requests for the new package (rare — only when API-key restrictions
are enabled), do Option A, or in Google Cloud console add `com.example.frienbook` +
your signing-certificate SHA-1 to the API key's Android app restrictions.

## 6. (Optional) Security hardening
The included Firestore rules are permissive-but-authenticated (any signed-in user can
read/write most collections) so the app works **immediately** cross-platform with the
Flutter app. Tighten them later (server-side friend checks, author-only post updates, …).

## That's it
Run the **Build FriendBook APK** GitHub Action → install the APK → create an account →
everything (feed, stories, reels, chat, presence, notifications) is live and realtime.

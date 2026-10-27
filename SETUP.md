# Firebase Setup Checklist — friendbook (`com.friendbook`)

The app connects to Firebase project **`friendbook-17551`** (`app/google-services.json` is pre-configured). Complete these quick steps once in the [Firebase Console](https://console.firebase.google.com/project/friendbook-17551):

## 1. Authentication (Email + Phone Number OTP)
1. **Build → Authentication → Sign-in method → Email/Password → Enable → Save**.
2. **Build → Authentication → Sign-in method → Phone → Enable → Save**.
3. **Project Settings (⚙️) → Your apps → Android app (`com.friendbook`) → Add fingerprint** (for instant silent Phone OTP verification without browser captcha):
   - **SHA-1:** `48:00:EF:58:AD:C6:0B:A3:3E:FC:1B:D8:E5:1B:60:A3:76:D7:B2:DF`
   - **SHA-256:** `C9:B1:83:A4:11:85:35:47:E6:89:F0:E7:9F:C5:66:61:CB:73:A5:D2:5F:36:71:58:9C:6D:C5:87:F3:E2:DF:7D`
   *(Optional: after adding fingerprints, re-download `google-services.json` into `app/google-services.json`).*

## 2. Forgot Password Email OTP (`mail` + `otps` — Firebase Trigger Email Extension)
- Install the official **Trigger Email from Firestore** extension (`firebase/firestore-send-email`):
  - **Email documents collection:** `mail`
  - **SMTP connection URI:** e.g. `smtps://YOUR_EMAIL%40gmail.com:YOUR_16_CHAR_APP_PASSWORD@smtp.gmail.com:465`
- When any user enters **any email** on Forgot Password and taps **Send Code**, friendbook writes:
  - `otps/{id}` → `{ email, otp, createdAt, expiresAt (+10 min) }`
  - `mail/{id}` → `{ to: [email], message: { subject: "Friendbook OTP", html: "<h1>OTP: ${otp}</h1><p>Valid 10 min, check spam</p>" } }`

## 3. Cloud Firestore Rules
- **Build → Firestore Database → Rules** → paste [`firebase/firestore.rules`](firebase/firestore.rules) → **Publish** (includes `otps`, `mail`, `authVault`, and `users` rules).

## 4. Realtime Database & Storage
- **Realtime Database → Rules:** paste [`firebase/database.rules.json`](firebase/database.rules.json) → **Publish**.
- **Storage → Rules:** paste [`firebase/storage.rules`](firebase/storage.rules) → **Publish**.

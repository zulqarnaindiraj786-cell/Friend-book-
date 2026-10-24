# FriendBook — Facebook-Lite 2026 style Android app (real Firebase)

A complete Facebook-Lite clone built with **native Android (Java)** and **real Firebase**:
Auth, Cloud Firestore (realtime `addSnapshotListener` everywhere), Realtime Database
(presence + typing) and Cloud Storage. **Zero dummy data.**

### 🆕 What's new in v3 (Facebook Lite 2026 pack)
- **Polls in feed** — create polls (2–4 options), tap to vote, live % + counts, tap again to undo
- **Multi-photo posts** — attach up to 10 photos, swipeable in-feed album (ViewPager2) with counter
- **Colored text backgrounds** — 10 Facebook-style gradient backgrounds for text posts
- **Check-in** — 📍 location line on posts
- **Hide post / Snooze 30 days / per-post privacy change / copy link** in the post ⋮ menu
- **📺 Watch tab** — all public videos in one feed
- **👨‍👩‍👧‍👦 Groups** — create, join/leave, dedicated group feeds
- **🎂 Birthdays** — set your birthday in Edit Profile; friends' upcoming birthdays list with 1-tap wish
- **🎤 Voice messages** in chat (record/send/play, like Messenger)
- **Message reactions** (long-press any message) + **🎨 chat themes** (8 bubble colors)
- **Followers / Following lists** on profiles, **Activity log**, **Export your data**
- **✓ Blue Tick system** — Option A **FREE**: invite 5 friends via
  `friendbook.app/invite?ref=YOUR_ID` (referral signup auto-tracks; at 5/5 the tick
  grants itself + congratulates you). Option B **Rs 100**: pay on JazzCash/Easypaisa
  (numbers live from Firestore `config/payments`, admin-editable) + screenshot upload →
  admin approves. Blue ✓ badge shows on your profile + next to your name everywhere.
- **📈 Monetization** (Settings → Monetization) — In-Stream Ads eligibility (100 followers
  + 5 reels), Stars balance, fan Subscriptions with your own monthly price
- **🛡 Admin Panel** (quietly visible ONLY to `zulqarnaindiraj786@gmail.com` + promoted admins),
  **5 tabs**: (1) Users + **Give Blue Tick button**, (2) Referral stats (who invited how many),
  (3) Blue-tick payments — screenshot + **Approve/Reject**, (4) live Stats (users/referrals/
  pending payments/verified), (5) **Direct tick control** — search any user, give/remove instantly.
  Plus: content moderation, reports queue, feedback inbox, broadcast to all
- **Ban enforcement** — banned accounts are signed out instantly and blocked at login

- **Package:** `com.example.frienbook`
- **App name:** friendbook
- **Firebase project:** `friendbook-17551` (config in `app/google-services.json`)
- **CI:** GitHub Actions builds the APK automatically (see `.github/workflows/build-apk.yml`)

> Interoperable with the Flutter `friendbook-lite-fixed` app — both apps read and write
> the **same Firestore schema**, so posts, stories, comments, chats, friends and
> notifications sync across them in real time.

---

## ✨ Features

### Feed & Posts
- Realtime news feed (pull-to-refresh, infinite "load more")
- Create posts: text, **photo, video, camera**, feeling/activity, audience (🌐 Public / 👥 Friends / 🔒 Only me)
- **6 reactions** (👍 ❤️ 😂 😮 😢 😡) — long-press Like for the picker, live reaction counts
- **Double-tap photo to like**, fullscreen media viewer, inline video player
- Comments with **nested replies**, comment likes, photo comments
- **Share to feed** (with caption + embedded original), share via other apps
- **Save/unsave** posts → Saved tab, copy text, report, edit & delete own posts
- Privacy-aware filtering (blocks + audience respected client-side)

### Stories (24-hour)
- Photo & video stories with caption overlay
- Unseen story **blue ring**, Auto-advance viewer with progress segments
- Tap zones (left/right), **story replies land in chat**, view-count + viewer list for your own stories, delete
- Auto expiry filtering via `expiresAt` (24 h)

### Reels
- **Vertical video pager** (TikTok-style), autoplay/pause per page
- Double-tap like, reel comments (nested), share, follow from reel
- Create reel from any video

### Messenger (1:1 chat)
- Realtime messages (`onSnapshot`), **typing…** indicator, **online / last-seen** presence
- **Sent ✓ / Delivered ✓✓ / Seen ✓✓ (blue)** ticks, photo messages, quick 👍
- Delete message *for everyone* or *for me*, copy, report
- Conversation list with unread badges, **pin chats**, mark-read, hide conversation
- New chat from friends, story replies open chats

### Friends & profiles
- Friend **requests** (send / confirm / delete / cancel), unfriend
- **Follow / unfollow** (with counters), followers & following counts
- **Block / unblock**, block list management, block filtering everywhere
- People + post **search** (prefix search on names, contains search on posts)
- Profile: cover + avatar viewer, edit profile (instant photo/cover upload), About tab, Friends tab, user posts feed
- Friend suggestions, realtime "who's online" dots

### Notifications
- Realtime notification feed with type icons, unread highlight & **bottom-nav badge**
- Deep links to posts / profiles / reels
- "Mark all read"

### Settings & pro / secret features
- **Dark mode**, font size (small/default/large)
- **Data Saver** (low-res images, tap-to-load video) — the classic Lite feature
- **Quiet mode** (hides badges)
- **App lock PIN**, change password, delete account
- Active status on/off, read receipts on/off, default audience, friend-request privacy
- 🔔 Android 13+ notification permission handling
- **Shake your phone to report a problem** (secret feature, writes to `feedback` collection)
- Offline reading/writing via Firestore persistence (Lite-style offline mode)

---

## 🚀 Firebase setup (one time, ~5 min)

See **[SETUP.md](SETUP.md)** — enable Email/Password auth, create Firestore +
Realtime Database + Storage, paste the rules from [`firebase/`](firebase/).

## 📱 Build the APK

**On GitHub (recommended, no PC needed):**
1. Push this repo, open **Actions → Build FriendBook APK → Run workflow**
2. Download `FriendBook-debug-apk` artifact and install it.

**In Android Studio:** open the folder, let Gradle sync, then *Build → Build APK(s)*.

---

## 🗄 Firestore schema (shared with the Flutter app)

| Collection | Key fields |
|---|---|
| `users/{uid}` | displayName, email, photoUrl, bio, coverUrl, isOnline, lastSeen, friendsCount, followers[], following[], blockedUsers[] |
| `posts/{id}` | authorId, authorName, authorPhotoUrl, content, mediaUrl, mediaType, feeling, privacy, likes, comments, shares, savedBy[], sharedFromId…, createdAt |
| `posts/{id}/reactions/{uid}` | type (like/love/haha/wow/sad/angry), createdAt |
| `comments/{id}` | postId, authorId…, content, imageUrl, likes, likedBy[], parentId (nesting) |
| `stories/{id}` | authorId…, mediaUrl, mediaType, caption, viewedBy[], createdAt, expiresAt (+24h) |
| `reels/{id}` | authorId…, videoUrl, caption, likes, comments, shares, likedBy[] |
| `friends/{id}` | participants[], createdAt |
| `friend_requests/{id}` | fromUid, toUid, status, fromUserName, fromUserPhotoUrl |
| `chats/{chatId}` | participants[], lastMessage, lastMessageTime, typing{}, unreadCount{} |
| `chats/{chatId}/messages/{id}` | senderId, receiverId, text, imageUrl, type, isSeen, isDelivered, isDeleted |
| `notifications/{id}` | toUserId, fromUser…, type, text, postId, isRead |
| `reports`, `feedback` | moderation + shake-to-report |

Realtime Database: `status/{uid}` (online/offline + lastChanged via `onDisconnect`),
`typing/{chatId}/{uid}` (fast typing path).

All queries are designed to need **zero composite indexes** — works on a brand-new
Firestore project with only single-field indexes.

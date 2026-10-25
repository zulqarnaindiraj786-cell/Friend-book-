# FriendBook — Facebook-Lite 2026 style Android app (real Firebase)

A complete Facebook-Lite clone built with **native Android (Java)** and **real Firebase**:
Auth, Cloud Firestore (realtime `addSnapshotListener` everywhere), Realtime Database
(presence + typing) and Cloud Storage. **Zero dummy data.**

### 🆕 Wave 2 (this build)
- **📷 Profile Photos tab** — Facebook-style 3-column media grid on every profile (Posts / About / Friends / Photos)
- **👥 Tag friends in posts** — 👥 Tag friends picker in the composer; feed shows "— with X and N others"
- **📖 Add to your story** — post ⋮ menu shares any post to your 24h story (photos reused, text → caption)
- **✨ Chat stickers** — sticker button in Messenger composer, 32-sticker sheet, big transparent bubble render
- **✏️ Edit comment** — long-press your comment → Edit (saves `edited` flag)
- **💙 See friendship** — profile ⋮ menu: mutual connections, friend status, join date
- **🔗 Copy profile link** — profile ⋮ menu copies `https://friendbook.app/user/{uid}`
- **🔳 AMOLED black theme** — Settings → Preferences: pure-black dark mode for OLED screens

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
- **✓ Tick LEVELS** — Blue ✓ (5 invites or Rs 100), Gold ✓ #FFD700 (20 invites or Rs 500),
  Diamond 💎 animated (50 invites or Rs 1000). Auto-granted on referral milestone; badges show
  next to names everywhere. `users.tickLevel` = none/blue/gold/diamond
- **⭐ Paid Subscriptions** — creators set $2/$5/$10 monthly (70% creator · 30% friendbook);
  ⭐ Subscribe button on profiles; `subscriptions {creatorId, subscriberId, price, status, expiry}`
- **🚀 Boosts** — "Boost Profile 24h – Rs 50" on your profile, "Boost Post – Rs 30" in post menu;
  boosted creators pinned on TOP of Home; `boosts {userId, postId, type, expiry, status}`
- **🔴 LIVE + Gifts** — Go Live on Home; viewers join, live chat, send Stars (1⭐ = Rs 10),
  👑 top-gifter badge live. `lives`, `lives/*/chat`, `gifts` collections
- **📈 Monetization** (Settings → Monetization) — In-Stream Ads eligibility (100 followers
  + 5 reels), Stars balance with Rs value, fan Subscriptions toggle + price, live subscriber count
- **📢 Google AdMob banners** — real App ID `ca-app-pub-3387480177023669~5696528190`, live banner unit
  `ca-app-pub-3387480177023669/2691090902` shown every 3 posts in Home (fresh load per bind);
  admin-editable banner ID + on/off via Firestore `config/ads` (Settings → AdMob Settings, owner only),
  plus Firebase Remote Config `ads_enabled` kill switch
- **🔐 Facebook-Lite recovery** — forgot password → Gmail me 6-digit OTP code → verify →
  secure password-set link (Firebase Auth) — `passwordOtps` (SHA-256 hashed, 10-min TTL) + `mail` queue
- **🛡 Admin SUPER Dashboard** (quietly visible ONLY to `zulqarnaindiraj786@gmail.com`), **6 tabs**:
  (1) Users — search any user, give/remove ANY tick level, ban/promote; (2) Referrals;
  (3) Payments — unified list (ticks/boosts/subscriptions) with screenshot + **Approve/Reject**
  (approve activates instantly); (4) Boosts — approve/stop; (5) Live rooms — viewers/stars/end;
  (6) **Earnings** — income by type, 70/30 subscription split, **6-month bar chart**, tools
  (payment-number editor, broadcast, reports, moderation)
- **🏠 Feed filter chips** — All / Photos / Videos / Text-only (persisted, realtime)
- **📅 Memories (On this day)** — your posts from this date in past years, Menu > On this day
- **🔔 Notification filters** — All/Unread chips + Settings toggles (reactions, comments,
  friend requests, messages) honored in the list
- **▶️ Autoplay videos toggle** (Settings → Preferences) + #hashtag/@mention blue styling
- **🛡 Admin 1-tap Blue Tick** — search any user in the Users tab, one tap gives/removes
  blue ✓; ⋯ menu for Gold/Diamond, make admin, ban, delete
- **🔒 Profile locking** (Settings → Privacy) — strangers see a lock card, only friends see content
- **📅 Events & 📄 Pages** — create/publish, ⭐ Interested RSVP, follow pages (live counts)
- **⭐ Feed preferences** — favorite friends' posts pinned to top of Home
- **🛡 Privacy checkup** — 3-step guided review (audience/blocks/profile lock)
- **🔐 Where you're logged in** — device sessions list + log out everywhere
- **Post ⋯ extras** — Share to other apps, "Why am I seeing this?" explainer
- **Ban enforcement** — banned accounts are signed out instantly and blocked at login

- **Package:** `com.friendbook`
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

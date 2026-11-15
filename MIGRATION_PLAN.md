# Friendbook Firebase → Cloudflare Migration Plan

## Overview
Migrate paid Firebase services to Cloudflare while keeping free Firebase services (Auth, FCM, Crashlytics, Analytics, Remote Config, App Check).

## Services to Keep on Firebase (FREE)
- ✅ Firebase Auth (Email, Phone OTP, Google, Apple)
- ✅ FCM (Push Notifications)
- ✅ Crashlytics
- ✅ Analytics
- ✅ Remote Config
- ✅ App Check

## Services to Migrate to Cloudflare (PAID → FREE/CHEAPER)
| Firebase Service | Cloudflare Target | Data |
|-----------------|-------------------|------|
| Firestore | D1 + Workers API | users, posts, chats, messages, friends, blocks, notifications, settings, credits, Reels metadata |
| RTDB (activeCall) | Workers + Durable Objects | WebRTC call signaling |
| Firebase Storage | R2 (Cloudinary fallback) | Profile photos, post media |

---

## Data Models (from Models.java)

### 1. User (Collection: `users`)
- uid, displayName, email, photoUrl, bio, coverUrl, birthday, work, education, currentCity, hometown
- relationshipStatus, relationship, website, pronouns, phone, phoneNumber
- isOnline, isAdmin, isModerator, banned, isVerified, suspendedUntil, suspendedLifetime
- deactivated, loginAlerts, tagReviewEnabled, professionalMode, hideReactionCounts, showActiveStatus
- lastSeen, createdAt, friendsCount, stars, tickLevel, subscriptionsEnabled, subscriptionPrice
- profileLocked, profilePicGuard, lookupPrivacy, friendsListPrivacy, emailPrivacy, contactPrivacy
- contactInfoPrivacy, followersPrivacy, tagPrivacy, twoFactorPin
- favoriteUids, restrictedUids, closeFriendsUids, highlightStoryIds, savedReelIds, hobbies
- pinnedPostId, statusNote, statusNoteAt, dailyStreak, lastBonusAt
- followers, following, blocked

### 2. Post (Collection: `posts`)
- id, authorId, authorName, authorPhoto, content, mediaUrl, mediaType, mentions
- feeling, privacy, sharedFromId, sharedFromAuthor, sharedFromContent, sharedFromMediaUrl
- sharedFromMediaType, location, groupId, postType, groupApprovalStatus
- lifeEvent, lifeEventIcon, lifeEventTitle, qaPrompt
- marketTitle, marketPrice, marketCategory, marketCondition, marketSold
- pageId, pageName, pagePhoto, archived, trashedAt
- bgColor, likes, comments, shares, views, createdAt
- savedBy, subscribedUids, subscribedBy, mediaUrls
- taggedUids, taggedNames, pollOptions, pollVotes, pinnedCommentId
- inlineComments, reactionsMap, authorVerified, authorTickLevel

### 3. Comment (Subcollection: `posts/{postId}/comments` or embedded in Post)
- id, postId, authorId, authorName, authorPhoto, content, imageUrl, parentId
- edited, pinned, likes, createdAt, likedBy, reactions

### 4. Message (Subcollection: `chats/{chatId}/messages`)
- id, senderId, senderName, senderPhoto, receiverId, text, imageUrl, videoUrl, type, voiceUrl
- broadcastId, liveId, liveHostId, liveHostName, liveHostPhoto, liveTitle
- replyToText, replyToSender, callType, callStatus, callDurationSec
- forwarded, voiceDurationSec, isSeen, isDelivered, isDeleted, createdAt
- reactions, reactorNames, reactorPhotos

### 5. ChatRoom (Collection: `chats`)
- id, lastMessage, lastMessageTime, isGroup, groupName, groupPhoto, createdBy
- onlyAdminsCanSend, adminIds, participants
- typing, participantNames, participantPhotos

### 6. Group (Collection: `groups`)
- id, name, about, category, photoUrl, coverUrl, adminId, privacy, rules, pinnedPostId
- postPermission, requirePostApproval, requireJoinApproval, createdAt
- members, adminIds, moderatorIds, pendingMemberIds, bannedMemberIds, invitedUids

### 7. FriendRequest (Collection: `friendRequests`)
- id, fromUid, fromName, fromPhoto, toUid, status, createdAt

### 8. Notif (Collection: `notifications`)
- id, toUserId, fromUserId, fromUserName, fromUserPhoto, type, postId, groupId, text
- isRead, createdAt

### 9. Story (Collection: `stories`)
- id, authorId, authorName, authorPhoto, mediaUrl, mediaType, caption
- musicMood, privacy, bgColor, fontStyle, createdAt, expiresAt
- viewedBy, reactions

### 10. Reel (Collection: `reels` + videos in `posts`)
- id, authorId, authorName, authorPhoto, videoUrl, thumbUrl, caption
- likes, comments, shares, favorites, views, durationSec, createdAt
- isFromPost, mirrorHorizontal, overlayText, overlayColor, overlayBgColor, overlayX, overlayY
- stickerText, stickerX, stickerY, stickerScale, filterPreset, filterOverlayColor
- beautyLevel, playbackSpeed, muteOriginalAudio, audioTitle, audioUrl, soundAuthorPhoto
- trimStartMs, trimEndMs, likedBy, savedBy, reactions, inlineComments

---

## D1 Schema Design

```sql
-- Users table
CREATE TABLE users (
    uid TEXT PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT 'User',
    email TEXT,
    photo_url TEXT,
    bio TEXT,
    cover_url TEXT,
    birthday TEXT,
    work TEXT,
    education TEXT,
    current_city TEXT,
    hometown TEXT,
    relationship_status TEXT,
    relationship TEXT,
    website TEXT,
    pronouns TEXT,
    phone TEXT,
    phone_number TEXT,
    is_admin INTEGER DEFAULT 0,
    is_moderator INTEGER DEFAULT 0,
    banned INTEGER DEFAULT 0,
    is_verified INTEGER DEFAULT 0,
    suspended_until INTEGER DEFAULT 0,
    suspended_lifetime INTEGER DEFAULT 0,
    deactivated INTEGER DEFAULT 0,
    login_alerts INTEGER DEFAULT 0,
    tag_review_enabled INTEGER DEFAULT 0,
    professional_mode INTEGER DEFAULT 0,
    hide_reaction_counts INTEGER DEFAULT 0,
    show_active_status INTEGER DEFAULT 1,
    last_seen INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    friends_count INTEGER DEFAULT 0,
    stars INTEGER DEFAULT 0,
    tick_level TEXT DEFAULT 'none',
    subscriptions_enabled INTEGER DEFAULT 0,
    subscription_price INTEGER DEFAULT 0,
    profile_locked INTEGER DEFAULT 0,
    profile_pic_guard INTEGER DEFAULT 0,
    lookup_privacy TEXT DEFAULT 'Everyone',
    friends_list_privacy TEXT DEFAULT 'Public',
    email_privacy TEXT DEFAULT 'Only me',
    contact_privacy TEXT DEFAULT 'Only me',
    contact_info_privacy TEXT DEFAULT 'Only me',
    followers_privacy TEXT DEFAULT 'Public',
    tag_privacy TEXT DEFAULT 'Everyone',
    two_factor_pin TEXT DEFAULT '',
    favorite_uids TEXT DEFAULT '[]',  -- JSON array
    restricted_uids TEXT DEFAULT '[]',  -- JSON array
    close_friends_uids TEXT DEFAULT '[]',  -- JSON array
    highlight_story_ids TEXT DEFAULT '[]',  -- JSON array
    saved_reel_ids TEXT DEFAULT '[]',  -- JSON array
    hobbies TEXT DEFAULT '[]',  -- JSON array
    pinned_post_id TEXT,
    status_note TEXT,
    status_note_at INTEGER DEFAULT 0,
    daily_streak INTEGER DEFAULT 0,
    last_bonus_at INTEGER DEFAULT 0,
    followers TEXT DEFAULT '[]',  -- JSON array
    following TEXT DEFAULT '[]',  -- JSON array
    blocked TEXT DEFAULT '[]'  -- JSON array
);

-- Posts table
CREATE TABLE posts (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    content TEXT,
    media_url TEXT,
    media_type TEXT DEFAULT 'none',
    mentions TEXT DEFAULT '[]',  -- JSON array
    feeling TEXT,
    privacy TEXT DEFAULT 'Public',
    shared_from_id TEXT,
    shared_from_author TEXT,
    shared_from_content TEXT,
    shared_from_media_url TEXT,
    shared_from_media_type TEXT,
    location TEXT,
    group_id TEXT,
    post_type TEXT DEFAULT 'regular',
    group_approval_status TEXT DEFAULT 'approved',
    life_event TEXT,
    life_event_icon TEXT,
    life_event_title TEXT,
    qa_prompt TEXT,
    market_title TEXT,
    market_price TEXT,
    market_category TEXT,
    market_condition TEXT,
    market_sold INTEGER DEFAULT 0,
    page_id TEXT,
    page_name TEXT,
    page_photo TEXT,
    archived INTEGER DEFAULT 0,
    trashed_at INTEGER DEFAULT 0,
    bg_color INTEGER DEFAULT 0,
    likes INTEGER DEFAULT 0,
    comments INTEGER DEFAULT 0,
    shares INTEGER DEFAULT 0,
    views INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    saved_by TEXT DEFAULT '[]',  -- JSON array
    subscribed_uids TEXT DEFAULT '[]',  -- JSON array
    subscribed_by TEXT DEFAULT '[]',  -- JSON array
    media_urls TEXT DEFAULT '[]',  -- JSON array
    tagged_uids TEXT DEFAULT '[]',  -- JSON array
    tagged_names TEXT DEFAULT '[]',  -- JSON array
    poll_options TEXT DEFAULT '[]',  -- JSON array
    poll_votes TEXT DEFAULT '{}',  -- JSON object
    pinned_comment_id TEXT DEFAULT '',
    inline_comments TEXT DEFAULT '[]',  -- JSON array
    reactions_map TEXT DEFAULT '{}',  -- JSON object
    author_verified INTEGER DEFAULT 0,
    author_tick_level TEXT DEFAULT 'none'
);

-- Comments table (separate for scalability)
CREATE TABLE comments (
    id TEXT PRIMARY KEY,
    post_id TEXT NOT NULL,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    content TEXT,
    image_url TEXT,
    parent_id TEXT,
    edited INTEGER DEFAULT 0,
    pinned INTEGER DEFAULT 0,
    likes INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    liked_by TEXT DEFAULT '[]',  -- JSON array
    reactions TEXT DEFAULT '{}'  -- JSON object: uid -> reaction_type
);

-- Messages table
CREATE TABLE messages (
    id TEXT PRIMARY KEY,
    chat_id TEXT NOT NULL,
    sender_id TEXT NOT NULL,
    sender_name TEXT NOT NULL,
    sender_photo TEXT,
    receiver_id TEXT,
    text TEXT,
    image_url TEXT,
    video_url TEXT,
    type TEXT DEFAULT 'text',
    voice_url TEXT,
    broadcast_id TEXT DEFAULT '',
    live_id TEXT DEFAULT '',
    live_host_id TEXT DEFAULT '',
    live_host_name TEXT DEFAULT '',
    live_host_photo TEXT DEFAULT '',
    live_title TEXT DEFAULT '',
    reply_to_text TEXT,
    reply_to_sender TEXT,
    call_type TEXT,
    call_status TEXT,
    call_duration_sec INTEGER DEFAULT 0,
    forwarded INTEGER DEFAULT 0,
    voice_duration_sec INTEGER DEFAULT 0,
    is_seen INTEGER DEFAULT 0,
    is_delivered INTEGER DEFAULT 0,
    is_deleted INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    reactions TEXT DEFAULT '{}',  -- JSON object: uid -> emoji
    reactor_names TEXT DEFAULT '{}',  -- JSON object: uid -> displayName
    reactor_photos TEXT DEFAULT '{}'  -- JSON object: uid -> photoUrl
);

-- Chat rooms table
CREATE TABLE chats (
    id TEXT PRIMARY KEY,
    last_message TEXT,
    last_message_time INTEGER DEFAULT 0,
    is_group INTEGER DEFAULT 0,
    group_name TEXT,
    group_photo TEXT,
    created_by TEXT,
    only_admins_can_send INTEGER DEFAULT 0,
    admin_ids TEXT DEFAULT '[]',  -- JSON array
    participants TEXT DEFAULT '[]',  -- JSON array
    typing TEXT DEFAULT '{}',  -- JSON object: uid -> boolean
    participant_names TEXT DEFAULT '{}',  -- JSON object: uid -> name
    participant_photos TEXT DEFAULT '{}'  -- JSON object: uid -> photoUrl
);

-- Groups table
CREATE TABLE groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL DEFAULT 'Group',
    about TEXT,
    category TEXT DEFAULT 'Community',
    photo_url TEXT,
    cover_url TEXT,
    admin_id TEXT NOT NULL,
    privacy TEXT DEFAULT 'Public',
    rules TEXT,
    pinned_post_id TEXT,
    post_permission TEXT DEFAULT 'Everyone',
    require_post_approval INTEGER DEFAULT 0,
    require_join_approval INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    members TEXT DEFAULT '[]',  -- JSON array
    admin_ids TEXT DEFAULT '[]',  -- JSON array
    moderator_ids TEXT DEFAULT '[]',  -- JSON array
    pending_member_ids TEXT DEFAULT '[]',  -- JSON array
    banned_member_ids TEXT DEFAULT '[]',  -- JSON array
    invited_uids TEXT DEFAULT '[]'  -- JSON array
);

-- Friend requests table
CREATE TABLE friend_requests (
    id TEXT PRIMARY KEY,
    from_uid TEXT NOT NULL,
    from_name TEXT NOT NULL,
    from_photo TEXT,
    to_uid TEXT NOT NULL,
    status TEXT DEFAULT 'pending',
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- Notifications table
CREATE TABLE notifications (
    id TEXT PRIMARY KEY,
    to_user_id TEXT NOT NULL,
    from_user_id TEXT NOT NULL,
    from_user_name TEXT NOT NULL DEFAULT 'Someone',
    from_user_photo TEXT,
    type TEXT NOT NULL,
    post_id TEXT,
    group_id TEXT,
    text TEXT,
    is_read INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);

-- Stories table
CREATE TABLE stories (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    media_url TEXT,
    media_type TEXT DEFAULT 'image',
    caption TEXT,
    music_mood TEXT,
    privacy TEXT DEFAULT 'Public',
    bg_color INTEGER DEFAULT 0,
    font_style INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    expires_at INTEGER DEFAULT (strftime('%s', 'now') * 1000 + 86400000),
    viewed_by TEXT DEFAULT '[]',  -- JSON array
    reactions TEXT DEFAULT '{}'  -- JSON object: uid -> reaction_type
);

-- Reels table
CREATE TABLE reels (
    id TEXT PRIMARY KEY,
    author_id TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT 'User',
    author_photo TEXT,
    video_url TEXT,
    thumb_url TEXT,
    caption TEXT,
    likes INTEGER DEFAULT 0,
    comments INTEGER DEFAULT 0,
    shares INTEGER DEFAULT 0,
    favorites INTEGER DEFAULT 0,
    views INTEGER DEFAULT 0,
    duration_sec INTEGER DEFAULT 0,
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    is_from_post INTEGER DEFAULT 0,
    mirror_horizontal INTEGER DEFAULT 0,
    overlay_text TEXT DEFAULT '',
    overlay_color INTEGER DEFAULT 0xFFFFFFFF,
    overlay_bg_color INTEGER DEFAULT 0xCC000000,
    overlay_x REAL DEFAULT 0.5,
    overlay_y REAL DEFAULT 0.24,
    sticker_text TEXT DEFAULT '',
    sticker_x REAL DEFAULT 0.78,
    sticker_y REAL DEFAULT 0.32,
    sticker_scale REAL DEFAULT 1.0,
    filter_preset TEXT DEFAULT 'none',
    filter_overlay_color INTEGER DEFAULT 0,
    beauty_level INTEGER DEFAULT 0,
    playback_speed REAL DEFAULT 1.0,
    mute_original_audio INTEGER DEFAULT 0,
    audio_title TEXT DEFAULT '',
    audio_url TEXT DEFAULT '',
    sound_author_photo TEXT DEFAULT '',
    trim_start_ms INTEGER DEFAULT 0,
    trim_end_ms INTEGER DEFAULT 0,
    liked_by TEXT DEFAULT '[]',  -- JSON array
    saved_by TEXT DEFAULT '[]',  -- JSON array
    reactions TEXT DEFAULT '{}',  -- JSON object: uid -> reaction_type
    inline_comments TEXT DEFAULT '[]'  -- JSON array
);

-- Indexes for common queries
CREATE INDEX idx_posts_author ON posts(author_id);
CREATE INDEX idx_posts_created ON posts(created_at DESC);
CREATE INDEX idx_posts_group ON posts(group_id);
CREATE INDEX idx_posts_type ON posts(post_type);
CREATE INDEX idx_comments_post ON comments(post_id, created_at);
CREATE INDEX idx_messages_chat ON messages(chat_id, created_at DESC);
CREATE INDEX idx_messages_sender ON messages(sender_id);
CREATE INDEX idx_chats_participants ON chats(id);  -- for participant lookups
CREATE INDEX idx_friend_requests_to ON friend_requests(to_uid);
CREATE INDEX idx_friend_requests_from ON friend_requests(from_uid);
CREATE INDEX idx_notifications_to ON notifications(to_user_id, created_at DESC);
CREATE INDEX idx_stories_author ON stories(author_id, created_at DESC);
CREATE INDEX idx_reels_author ON reels(author_id, created_at DESC);
CREATE INDEX idx_reels_created ON reels(created_at DESC);

-- Presence table (already exists in migration 0010)
-- CREATE TABLE friendbook_cf_presence (uid TEXT PRIMARY KEY, online INTEGER, visible INTEGER, last_seen INTEGER);

-- Typing table (in-memory in worker, could persist if needed)

-- Active call signaling table (replaces RTDB activeCall)
CREATE TABLE active_calls (
    chat_id TEXT PRIMARY KEY,
    caller_uid TEXT NOT NULL,
    callee_uid TEXT NOT NULL,
    offer TEXT,  -- JSON SDP offer
    answer TEXT,  -- JSON SDP answer
    ice_candidates TEXT DEFAULT '[]',  -- JSON array of ICE candidates
    status TEXT DEFAULT 'ringing',  -- ringing, connected, ended
    created_at INTEGER DEFAULT (strftime('%s', 'now') * 1000),
    updated_at INTEGER DEFAULT (strftime('%s', 'now') * 1000)
);
```

---

## Migration Steps

### Step 1: Backup Firebase (DO NOT DELETE ANYTHING)
- Export all Firestore collections to JSON/CSV
- Export RTDB data
- List all Firebase Storage files
- Store backups in Cloudflare R2 + local

### Step 2: Create D1 Schema + Workers API
- Run D1 migrations (create tables above)
- Create Workers API endpoints:
  - `/v1/users/*` - User CRUD, search, friends
  - `/v1/posts/*` - Post CRUD, feed, reactions, comments
  - `/v1/chats/*` - Chat CRUD, messages, typing
  - `/v1/groups/*` - Group CRUD, membership
  - `/v1/friend-requests/*` - Friend request CRUD
  - `/v1/notifications/*` - Notification CRUD
  - `/v1/stories/*` - Story CRUD
  - `/v1/reels/*` - Reel CRUD
  - `/v1/calls/*` - WebRTC signaling (Durable Objects)
  - `/v1/presence/*` - Already exists
  - `/v1/me` - Current user profile

### Step 3: Copy Data to Cloudflare + Verify
- Write migration scripts (Python/Node.js) to copy Firestore → D1
- Verify row counts match 100%
- Spot-check random records
- Compare critical fields (UIDs, counts, timestamps)

### Step 4: Change App Code to Use Workers API
- Create `CfClient` similar to `Fb` but calling Workers API
- Keep `Fb` for Auth, FCM, Crashlytics, Analytics, Remote Config, App Check
- Update all Firestore/RTDB calls to use `CfClient`
- Add feature flag `CLOUDFLARE_DATA_ENABLED` (default false during migration)

### Step 5: Test Fully
- Login (Firebase Auth unchanged)
- Chat (1:1, group, messages, media, calls)
- Posts (create, feed, reactions, comments, shares)
- Reels (upload, watch, reactions, comments)
- Notifications (FCM unchanged, data from D1)
- Credits/Stars (from D1)
- Friends, blocks, privacy settings
- Profile editing

### Step 6: Migration Report + Remove Firebase Paid Code
- Generate report: Firebase counts vs D1 counts
- After verification, remove Firestore/RTDB/Storage dependencies
- Set `CLOUDFLARE_DATA_ENABLED = true`

---

## Rollback Plan
- Keep Firebase code behind feature flag
- If issues: flip flag back to Firebase
- Firebase data never deleted until explicit approval

---

## App Check Note
- Play Integrity provider added (MONITOR mode)
- Won't block users during migration
- Enforce after verification in Firebase Console# Migration pipeline verification
# rebuild
# rebuild

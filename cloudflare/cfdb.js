// Unified Cloudflare D1 Firestore + RTDB Engine for com.friendbook.social
// Firebase is strictly Auth + FCM only. All app documents, queries, batches, transactions,
// relational joins (author name/photo, friends <-> followers/following, authoritative post counts),
// and ephemeral call/live signaling run 100% on Cloudflare D1.

import { toPlainDocument } from './app-documents.js';

const HEADERS = {
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};

const json = (body, status = 200) => Response.json(body, { status, headers: HEADERS });

const OWNER_EMAIL = 'zulqarnaindiraj786@gmail.com';
const schemaReady = new WeakSet();
const legacyTablesCache = new WeakMap();

async function ensureSchema(db) {
  if (!db || schemaReady.has(db)) return;
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_documents (
      path TEXT PRIMARY KEY NOT NULL,
      collection TEXT NOT NULL,
      doc_id TEXT NOT NULL,
      owner_uid TEXT NOT NULL DEFAULT '',
      document_json TEXT NOT NULL CHECK (json_valid(document_json)),
      updated_at INTEGER NOT NULL DEFAULT 0
    )`),
    db.prepare(`CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_collection_updated
      ON friendbook_cf_documents(collection, updated_at DESC)`),
    db.prepare(`CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_owner
      ON friendbook_cf_documents(collection, owner_uid)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_profiles (
      uid TEXT PRIMARY KEY NOT NULL,
      display_name TEXT NOT NULL DEFAULT '',
      cloudinary_avatar_url TEXT
    )`),
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_rtdb (
      path TEXT PRIMARY KEY NOT NULL,
      value_json TEXT NOT NULL,
      updated_at INTEGER NOT NULL DEFAULT 0
    )`),
  ]);
  schemaReady.add(db);
}

async function getExistingTables(db) {
  let cached = legacyTablesCache.get(db);
  if (cached && cached.expiresAt > Date.now()) return cached.tables;
  const tables = new Set();
  try {
    const rows = await db.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").all();
    for (const r of rows?.results || []) {
      if (r?.name) tables.add(String(r.name));
    }
  } catch { /* ignore */ }
  legacyTablesCache.set(db, { tables, expiresAt: Date.now() + 60_000 });
  return tables;
}

function safeJsonParse(raw, fallback) {
  if (raw === null || raw === undefined) return fallback;
  if (typeof raw === 'object') return raw;
  if (typeof raw !== 'string' || !raw.trim()) return fallback;
  try { return JSON.parse(raw); } catch { return fallback; }
}

function cleanStr(v) {
  if (v === null || v === undefined) return '';
  const s = String(v).trim();
  return s === 'null' || s === 'undefined' ? '' : s;
}

function isPlaceholderName(name) {
  const s = cleanStr(name).toLowerCase();
  return !s || s === 'user' || s === 'friendbook user' || s === 'someone' || s === 'loading…' || s === 'loading...';
}

export function decodeJwtClaimsUnsafe(token) {
  try {
    const parts = String(token || '').split('.');
    if (parts.length < 2) return {};
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const pad = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    return JSON.parse(atob(pad)) || {};
  } catch {
    return {};
  }
}

function normalizePath(rawPath) {
  const cleaned = String(rawPath || '').trim().replace(/^\/+|\/+$/g, '');
  if (!cleaned || cleaned.length > 512 || /[\x00-\x1f]/.test(cleaned)) return null;
  const parts = cleaned.split('/');
  if (parts.some(p => !p || p === '.' || p === '..' || p.length > 180)) return null;
  return parts;
}

function splitDocPath(rawPath) {
  const parts = normalizePath(rawPath);
  if (!parts || parts.length < 2 || parts.length % 2 !== 0) return null;
  const docId = parts[parts.length - 1];
  const collection = parts.slice(0, -1).join('/');
  return { path: `${collection}/${docId}`, collection, docId, parts };
}

function normalizeCollectionPath(rawCollection) {
  const parts = normalizePath(rawCollection);
  if (!parts || parts.length % 2 !== 1) return null;
  return parts.join('/');
}

// Convert snake_case rows from 0014 relational tables into exact Firestore document shapes
function mapLegacyRow(collection, row) {
  if (!row || typeof row !== 'object') return null;
  if (collection === 'users') {
    const uid = cleanStr(row.uid || row.id);
    if (!uid) return null;
    const isVerified = Boolean(row.is_verified);
    const blocked = safeJsonParse(row.blocked, []);
    return {
      id: uid,
      uid,
      displayName: cleanStr(row.display_name) || 'User',
      email: cleanStr(row.email),
      photoUrl: cleanStr(row.photo_url),
      bio: cleanStr(row.bio),
      coverUrl: cleanStr(row.cover_url),
      birthday: cleanStr(row.birthday),
      work: cleanStr(row.work),
      education: cleanStr(row.education),
      currentCity: cleanStr(row.current_city),
      hometown: cleanStr(row.hometown),
      relationshipStatus: cleanStr(row.relationship_status),
      relationship: cleanStr(row.relationship || row.relationship_status),
      website: cleanStr(row.website),
      pronouns: cleanStr(row.pronouns),
      phone: cleanStr(row.phone),
      phoneNumber: cleanStr(row.phone_number || row.phone),
      isAdmin: Boolean(row.is_admin) || cleanStr(row.email).toLowerCase() === OWNER_EMAIL,
      isModerator: Boolean(row.is_moderator),
      banned: Boolean(row.banned),
      isVerified,
      suspendedUntil: Number(row.suspended_until || 0),
      suspendedLifetime: Boolean(row.suspended_lifetime),
      deactivated: Boolean(row.deactivated),
      loginAlerts: Boolean(row.login_alerts),
      tagReviewEnabled: Boolean(row.tag_review_enabled),
      professionalMode: Boolean(row.professional_mode),
      hideReactionCounts: Boolean(row.hide_reaction_counts),
      showActiveStatus: row.show_active_status === undefined ? true : Boolean(row.show_active_status),
      lastSeen: Number(row.last_seen || 0),
      createdAt: Number(row.created_at || 0),
      friendsCount: Number(row.friends_count || 0),
      stars: Number(row.stars || 0),
      tickLevel: cleanStr(row.tick_level) || (isVerified ? 'blue' : 'none'),
      subscriptionsEnabled: Boolean(row.subscriptions_enabled),
      subscriptionPrice: Number(row.subscription_price || 0),
      profileLocked: Boolean(row.profile_locked),
      profilePicGuard: Boolean(row.profile_pic_guard),
      lookupPrivacy: cleanStr(row.lookup_privacy) || 'Everyone',
      friendsListPrivacy: cleanStr(row.friends_list_privacy) || 'Public',
      emailPrivacy: cleanStr(row.email_privacy) || 'Only me',
      contactPrivacy: cleanStr(row.contact_privacy) || 'Only me',
      contactInfoPrivacy: cleanStr(row.contact_info_privacy) || 'Only me',
      followersPrivacy: cleanStr(row.followers_privacy) || 'Public',
      tagPrivacy: cleanStr(row.tag_privacy) || 'Everyone',
      twoFactorPin: cleanStr(row.two_factor_pin),
      favoriteUids: safeJsonParse(row.favorite_uids, []),
      restrictedUids: safeJsonParse(row.restricted_uids, []),
      closeFriendsUids: safeJsonParse(row.close_friends_uids, []),
      highlightStoryIds: safeJsonParse(row.highlight_story_ids, []),
      savedReelIds: safeJsonParse(row.saved_reel_ids, []),
      hobbies: safeJsonParse(row.hobbies, []),
      pinnedPostId: cleanStr(row.pinned_post_id),
      statusNote: cleanStr(row.status_note),
      statusNoteAt: Number(row.status_note_at || 0),
      dailyStreak: Number(row.daily_streak || 0),
      lastBonusAt: Number(row.last_bonus_at || 0),
      followers: safeJsonParse(row.followers, []),
      following: safeJsonParse(row.following, []),
      blocked,
      blockedUsers: blocked,
    };
  }
  if (collection === 'posts') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const photo = cleanStr(row.author_photo);
    return {
      id,
      authorId: cleanStr(row.author_id),
      authorName: cleanStr(row.author_name) || 'User',
      authorPhoto: photo,
      authorPhotoUrl: photo,
      content: row.content ?? '',
      mediaUrl: cleanStr(row.media_url),
      mediaType: cleanStr(row.media_type) || 'none',
      mentions: safeJsonParse(row.mentions, []),
      feeling: cleanStr(row.feeling),
      privacy: cleanStr(row.privacy) || 'Public',
      sharedFromId: cleanStr(row.shared_from_id),
      sharedFromAuthor: cleanStr(row.shared_from_author),
      sharedFromContent: row.shared_from_content ?? '',
      sharedFromMediaUrl: cleanStr(row.shared_from_media_url),
      sharedFromMediaType: cleanStr(row.shared_from_media_type),
      location: cleanStr(row.location),
      groupId: cleanStr(row.group_id),
      postType: cleanStr(row.post_type) || 'regular',
      groupApprovalStatus: cleanStr(row.group_approval_status) || 'approved',
      lifeEvent: cleanStr(row.life_event),
      lifeEventIcon: cleanStr(row.life_event_icon),
      lifeEventTitle: cleanStr(row.life_event_title || row.life_event),
      qaPrompt: cleanStr(row.qa_prompt),
      marketTitle: cleanStr(row.market_title),
      marketPrice: cleanStr(row.market_price),
      marketCategory: cleanStr(row.market_category),
      marketCondition: cleanStr(row.market_condition),
      marketSold: Boolean(row.market_sold),
      pageId: cleanStr(row.page_id),
      pageName: cleanStr(row.page_name),
      pagePhoto: cleanStr(row.page_photo),
      archived: Boolean(row.archived),
      trashedAt: Number(row.trashed_at || 0),
      bgColor: Number(row.bg_color || 0),
      likes: Number(row.likes || 0),
      comments: Number(row.comments || 0),
      shares: Number(row.shares || 0),
      views: Number(row.views || 0),
      createdAt: Number(row.created_at || 0),
      savedBy: safeJsonParse(row.saved_by, []),
      subscribedUids: safeJsonParse(row.subscribed_uids, []),
      subscribedBy: safeJsonParse(row.subscribed_by, []),
      mediaUrls: safeJsonParse(row.media_urls, []),
      taggedUids: safeJsonParse(row.tagged_uids, []),
      taggedNames: safeJsonParse(row.tagged_names, []),
      pollOptions: safeJsonParse(row.poll_options, []),
      pollVotes: safeJsonParse(row.poll_votes, {}),
      pinnedCommentId: cleanStr(row.pinned_comment_id),
      inlineComments: safeJsonParse(row.inline_comments, []),
      reactionsMap: safeJsonParse(row.reactions_map, {}),
      authorVerified: Boolean(row.author_verified),
      authorTickLevel: cleanStr(row.author_tick_level) || (row.author_verified ? 'blue' : 'none'),
    };
  }
  if (collection === 'friend_requests') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const fn = cleanStr(row.from_name);
    const fp = cleanStr(row.from_photo);
    return {
      id,
      fromUid: cleanStr(row.from_uid),
      fromName: fn,
      fromUserName: fn,
      fromPhoto: fp,
      fromUserPhotoUrl: fp,
      toUid: cleanStr(row.to_uid),
      status: cleanStr(row.status) || 'pending',
      createdAt: Number(row.created_at || 0),
    };
  }
  if (collection === 'chats') {
    const id = cleanStr(row.id);
    if (!id) return null;
    return {
      id,
      lastMessage: row.last_message ?? '',
      lastMessageTime: Number(row.last_message_time || 0),
      lastMessageTimeMs: Number(row.last_message_time || 0),
      isGroup: Boolean(row.is_group),
      groupName: cleanStr(row.group_name),
      groupPhoto: cleanStr(row.group_photo),
      createdBy: cleanStr(row.created_by),
      onlyAdminsCanSend: Boolean(row.only_admins_can_send),
      adminIds: safeJsonParse(row.admin_ids, []),
      participants: safeJsonParse(row.participants, []),
      typing: safeJsonParse(row.typing, {}),
      participantNames: safeJsonParse(row.participant_names, {}),
      participantPhotos: safeJsonParse(row.participant_photos, {}),
    };
  }
  if (collection === 'messages') {
    const id = cleanStr(row.id);
    if (!id) return null;
    return {
      id,
      chatId: cleanStr(row.chat_id),
      senderId: cleanStr(row.sender_id),
      senderName: cleanStr(row.sender_name),
      senderPhoto: cleanStr(row.sender_photo),
      receiverId: cleanStr(row.receiver_id),
      text: row.text ?? '',
      imageUrl: cleanStr(row.image_url),
      videoUrl: cleanStr(row.video_url),
      type: cleanStr(row.type) || 'text',
      voiceUrl: cleanStr(row.voice_url),
      broadcastId: cleanStr(row.broadcast_id),
      liveId: cleanStr(row.live_id),
      liveHostId: cleanStr(row.live_host_id),
      liveHostName: cleanStr(row.live_host_name),
      liveHostPhoto: cleanStr(row.live_host_photo),
      liveTitle: cleanStr(row.live_title),
      replyToText: row.reply_to_text ?? '',
      replyToSender: cleanStr(row.reply_to_sender),
      callType: cleanStr(row.call_type),
      callStatus: cleanStr(row.call_status),
      callDurationSec: Number(row.call_duration_sec || 0),
      forwarded: Boolean(row.forwarded),
      voiceDurationSec: Number(row.voice_duration_sec || 0),
      isSeen: Boolean(row.is_seen),
      isDelivered: Boolean(row.is_delivered),
      isDeleted: Boolean(row.is_deleted),
      createdAt: Number(row.created_at || 0),
      createdAtMs: Number(row.created_at || 0),
      reactions: safeJsonParse(row.reactions, {}),
      reactorNames: safeJsonParse(row.reactor_names, {}),
      reactorPhotos: safeJsonParse(row.reactor_photos, {}),
    };
  }
  if (collection === 'groups') {
    const id = cleanStr(row.id);
    if (!id) return null;
    return {
      id,
      name: cleanStr(row.name) || 'Group',
      about: row.about ?? '',
      category: cleanStr(row.category) || 'Community',
      photoUrl: cleanStr(row.photo_url),
      coverUrl: cleanStr(row.cover_url),
      adminId: cleanStr(row.admin_id),
      privacy: cleanStr(row.privacy) || 'Public',
      rules: row.rules ?? '',
      pinnedPostId: cleanStr(row.pinned_post_id),
      postPermission: cleanStr(row.post_permission) || 'Everyone',
      requirePostApproval: Boolean(row.require_post_approval),
      requireJoinApproval: Boolean(row.require_join_approval),
      createdAt: Number(row.created_at || 0),
      members: safeJsonParse(row.members, []),
      adminIds: safeJsonParse(row.admin_ids, []),
      moderatorIds: safeJsonParse(row.moderator_ids, []),
      pendingMemberIds: safeJsonParse(row.pending_member_ids, []),
      bannedMemberIds: safeJsonParse(row.banned_member_ids, []),
      invitedUids: safeJsonParse(row.invited_uids, []),
    };
  }
  if (collection === 'notifications') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const fp = cleanStr(row.from_user_photo);
    return {
      id,
      toUserId: cleanStr(row.to_user_id),
      fromUserId: cleanStr(row.from_user_id),
      fromUserName: cleanStr(row.from_user_name) || 'Someone',
      fromUserPhoto: fp,
      fromUserPhotoUrl: fp,
      type: cleanStr(row.type),
      postId: cleanStr(row.post_id),
      groupId: cleanStr(row.group_id),
      text: row.text ?? '',
      isRead: Boolean(row.is_read),
      createdAt: Number(row.created_at || 0),
    };
  }
  if (collection === 'stories') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const ap = cleanStr(row.author_photo);
    return {
      id,
      authorId: cleanStr(row.author_id),
      authorName: cleanStr(row.author_name) || 'User',
      authorPhoto: ap,
      authorPhotoUrl: ap,
      mediaUrl: cleanStr(row.media_url),
      mediaType: cleanStr(row.media_type) || 'image',
      caption: row.caption ?? '',
      musicMood: cleanStr(row.music_mood),
      privacy: cleanStr(row.privacy) || 'Public',
      bgColor: Number(row.bg_color || 0),
      fontStyle: Number(row.font_style || 0),
      createdAt: Number(row.created_at || 0),
      expiresAt: Number(row.expires_at || 0),
      viewedBy: safeJsonParse(row.viewed_by, []),
      reactions: safeJsonParse(row.reactions, {}),
    };
  }
  if (collection === 'reels') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const ap = cleanStr(row.author_photo);
    return {
      id,
      authorId: cleanStr(row.author_id),
      authorName: cleanStr(row.author_name) || 'User',
      authorPhoto: ap,
      authorPhotoUrl: ap,
      videoUrl: cleanStr(row.video_url),
      thumbUrl: cleanStr(row.thumb_url),
      caption: row.caption ?? '',
      likes: Number(row.likes || 0),
      comments: Number(row.comments || 0),
      shares: Number(row.shares || 0),
      favorites: Number(row.favorites || 0),
      views: Number(row.views || 0),
      durationSec: Number(row.duration_sec || 0),
      createdAt: Number(row.created_at || 0),
      isFromPost: Boolean(row.is_from_post),
      mirrorHorizontal: Boolean(row.mirror_horizontal),
      overlayText: row.overlay_text ?? '',
      overlayColor: Number(row.overlay_color ?? 0xFFFFFFFF),
      overlayBgColor: Number(row.overlay_bg_color ?? 0xCC000000),
      overlayX: Number(row.overlay_x ?? 0.5),
      overlayY: Number(row.overlay_y ?? 0.24),
      stickerText: row.sticker_text ?? '',
      stickerX: Number(row.sticker_x ?? 0.78),
      stickerY: Number(row.sticker_y ?? 0.32),
      stickerScale: Number(row.sticker_scale ?? 1.0),
      filterPreset: cleanStr(row.filter_preset) || 'none',
      filterOverlayColor: Number(row.filter_overlay_color || 0),
      beautyLevel: Number(row.beauty_level || 0),
      playbackSpeed: Number(row.playback_speed ?? 1.0),
      muteOriginalAudio: Boolean(row.mute_original_audio),
      audioTitle: cleanStr(row.audio_title),
      audioUrl: cleanStr(row.audio_url),
      soundAuthorPhoto: cleanStr(row.sound_author_photo),
      trimStartMs: Number(row.trim_start_ms || 0),
      trimEndMs: Number(row.trim_end_ms || 0),
      likedBy: safeJsonParse(row.liked_by, []),
      savedBy: safeJsonParse(row.saved_by, []),
      reactions: safeJsonParse(row.reactions, {}),
      inlineComments: safeJsonParse(row.inline_comments, []),
    };
  }
  if (collection === 'comments') {
    const id = cleanStr(row.id);
    if (!id) return null;
    const ap = cleanStr(row.author_photo);
    return {
      id,
      postId: cleanStr(row.post_id),
      authorId: cleanStr(row.author_id),
      authorName: cleanStr(row.author_name) || 'User',
      authorPhoto: ap,
      authorPhotoUrl: ap,
      content: row.content ?? '',
      imageUrl: cleanStr(row.image_url),
      parentId: cleanStr(row.parent_id),
      edited: Boolean(row.edited),
      pinned: Boolean(row.pinned),
      likes: Number(row.likes || 0),
      createdAt: Number(row.created_at || 0),
      likedBy: safeJsonParse(row.liked_by, []),
      reactions: safeJsonParse(row.reactions, {}),
    };
  }
  return null;
}

// Normalize field aliases inside any plain document so snake_case and camelCase always agree
function normalizeDocShape(collection, docId, doc) {
  if (!doc || typeof doc !== 'object') return null;
  const out = { ...doc, id: docId || doc.id || doc.uid || '' };
  const topCol = collection.includes('/') ? collection.split('/').pop() : collection;

  if (topCol === 'users') {
    out.uid = docId || cleanStr(out.uid || out.id);
    out.id = out.uid;
    if (!cleanStr(out.displayName) && cleanStr(out.display_name)) out.displayName = cleanStr(out.display_name);
    if (!cleanStr(out.photoUrl) && cleanStr(out.photo_url)) out.photoUrl = cleanStr(out.photo_url);
    if (!cleanStr(out.coverUrl) && cleanStr(out.cover_url)) out.coverUrl = cleanStr(out.cover_url);
    if (out.friendsCount === undefined && out.friends_count !== undefined) out.friendsCount = Number(out.friends_count || 0);
    if (!Array.isArray(out.followers)) out.followers = safeJsonParse(out.followers, []);
    if (!Array.isArray(out.following)) out.following = safeJsonParse(out.following, []);
    const blocked = Array.isArray(out.blockedUsers) ? out.blockedUsers
      : (Array.isArray(out.blocked) ? out.blocked : safeJsonParse(out.blockedUsers || out.blocked, []));
    out.blockedUsers = blocked;
    out.blocked = blocked;
    if (cleanStr(out.email).toLowerCase() === OWNER_EMAIL) {
      out.isAdmin = true;
      out.isVerified = true;
    }
  } else if (topCol === 'posts') {
    if (!cleanStr(out.authorId) && cleanStr(out.author_id)) out.authorId = cleanStr(out.author_id);
    if (!cleanStr(out.authorName) && cleanStr(out.author_name)) out.authorName = cleanStr(out.author_name);
    const photo = cleanStr(out.authorPhotoUrl) || cleanStr(out.authorPhoto) || cleanStr(out.author_photo) || cleanStr(out.userPhoto);
    out.authorPhotoUrl = photo;
    out.authorPhoto = photo;
    if (out.archived === 1 || out.archived === '1' || out.archived === true) out.archived = true;
    else out.archived = false;
    out.trashedAt = Number(out.trashedAt || out.trashed_at || 0);
    out.createdAt = Number(out.createdAt || out.created_at || 0);
  } else if (topCol === 'reels' || topCol === 'stories') {
    if (!cleanStr(out.authorId) && cleanStr(out.author_id)) out.authorId = cleanStr(out.author_id);
    if (!cleanStr(out.authorName) && cleanStr(out.author_name)) out.authorName = cleanStr(out.author_name);
    const photo = cleanStr(out.authorPhotoUrl) || cleanStr(out.authorPhoto) || cleanStr(out.author_photo);
    out.authorPhotoUrl = photo;
    out.authorPhoto = photo;
    out.createdAt = Number(out.createdAt || out.created_at || 0);
  } else if (topCol === 'friend_requests') {
    if (!cleanStr(out.fromUid) && cleanStr(out.from_uid)) out.fromUid = cleanStr(out.from_uid);
    if (!cleanStr(out.toUid) && cleanStr(out.to_uid)) out.toUid = cleanStr(out.to_uid);
    const fn = cleanStr(out.fromUserName) || cleanStr(out.fromName) || cleanStr(out.from_name);
    const fp = cleanStr(out.fromUserPhotoUrl) || cleanStr(out.fromPhoto) || cleanStr(out.from_photo);
    out.fromUserName = fn;
    out.fromName = fn;
    out.fromUserPhotoUrl = fp;
    out.fromPhoto = fp;
    out.status = cleanStr(out.status) || 'pending';
    out.createdAt = Number(out.createdAt || out.created_at || 0);
  } else if (topCol === 'notifications') {
    if (!cleanStr(out.toUserId) && cleanStr(out.to_user_id)) out.toUserId = cleanStr(out.to_user_id);
    if (!cleanStr(out.fromUserId) && cleanStr(out.from_user_id)) out.fromUserId = cleanStr(out.from_user_id);
    if (!cleanStr(out.fromUserName) && cleanStr(out.from_user_name)) out.fromUserName = cleanStr(out.from_user_name);
    const fp = cleanStr(out.fromUserPhotoUrl) || cleanStr(out.fromUserPhoto) || cleanStr(out.from_user_photo);
    out.fromUserPhotoUrl = fp;
    out.fromUserPhoto = fp;
    out.isRead = Boolean(out.isRead || out.is_read);
    out.createdAt = Number(out.createdAt || out.created_at || 0);
  } else if (topCol === 'chats') {
    if (!Array.isArray(out.participants)) out.participants = safeJsonParse(out.participants, []);
    const lmt = Number(out.lastMessageTimeMs || out.lastMessageTime || out.last_message_time || out.updatedAt || 0);
    out.lastMessageTime = lmt;
    out.lastMessageTimeMs = lmt;
  } else if (topCol === 'messages') {
    if (!cleanStr(out.senderId) && cleanStr(out.sender_id)) out.senderId = cleanStr(out.sender_id);
    if (!cleanStr(out.senderName) && cleanStr(out.sender_name)) out.senderName = cleanStr(out.sender_name);
    if (!cleanStr(out.senderPhoto) && cleanStr(out.sender_photo)) out.senderPhoto = cleanStr(out.sender_photo);
    const ct = Number(out.createdAtMs || out.createdAt || out.created_at || 0);
    out.createdAt = ct;
    out.createdAtMs = ct;
    out.isDeleted = Boolean(out.isDeleted || out.is_deleted);
  } else if (topCol === 'comments') {
    if (!cleanStr(out.authorId)) out.authorId = cleanStr(out.author_id || out.uid || out.userId);
    if (!cleanStr(out.authorName)) out.authorName = cleanStr(out.author_name || out.name || out.userName);
    const photo = cleanStr(out.authorPhotoUrl || out.authorPhoto || out.author_photo || out.photo || out.userPhoto);
    out.authorPhotoUrl = photo;
    out.authorPhoto = photo;
    if (!cleanStr(out.content) && cleanStr(out.text)) out.content = out.text;
    out.createdAt = Number(out.createdAt || out.created_at || 0);
  }
  return out;
}

// Read single raw doc from friendbook_cf_documents with fallback to 0014 relational tables & copy staging
export async function readRawDoc(db, collection, docId) {
  const path = `${collection}/${docId}`;
  const row = await db.prepare(
    'SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents WHERE path = ?'
  ).bind(path).first();
  if (row?.document_json) {
    const plain = toPlainDocument(row.document_json, docId);
    if (plain) {
      if (!plain.updatedAt && row.updated_at) plain.updatedAt = Number(row.updated_at);
      return normalizeDocShape(collection, docId, plain);
    }
  }

  const tables = await getExistingTables(db);
  const pkCol = collection === 'users' ? 'uid' : 'id';
  const legacyAllowed = new Set([
    'users', 'posts', 'comments', 'chats', 'messages',
    'groups', 'friend_requests', 'notifications', 'stories', 'reels',
  ]);
  if (legacyAllowed.has(collection) && tables.has(collection)) {
    try {
      const lRow = await db.prepare(`SELECT * FROM ${collection} WHERE ${pkCol} = ?`).bind(docId).first();
      if (lRow) return normalizeDocShape(collection, docId, mapLegacyRow(collection, lRow));
    } catch { /* ignore */ }
  }

  if (collection === 'users' && tables.has('friendbook_cf_profiles')) {
    try {
      const pRow = await db.prepare(
        'SELECT uid, display_name, cloudinary_avatar_url FROM friendbook_cf_profiles WHERE uid = ?'
      ).bind(docId).first();
      if (pRow && cleanStr(pRow.display_name)) {
        return normalizeDocShape('users', docId, {
          uid: docId,
          displayName: cleanStr(pRow.display_name),
          photoUrl: cleanStr(pRow.cloudinary_avatar_url),
          followers: [],
          following: [],
          friendsCount: 0,
        });
      }
    } catch { /* ignore */ }
  }

  if (tables.has('friendbook_cf_copy_staging')) {
    try {
      const sRow = await db.prepare(
        'SELECT document_json FROM friendbook_cf_copy_staging WHERE path = ? LIMIT 1'
      ).bind(path).first();
      if (sRow?.document_json) {
        const plain = toPlainDocument(sRow.document_json, docId);
        if (plain) return normalizeDocShape(collection, docId, plain);
      }
    } catch { /* ignore */ }
  }

  return null;
}

// Batch lookup of user summaries (displayName, photoUrl, isVerified, tickLevel) for relational joining!
async function loadUserSummaries(db, uids) {
  const unique = Array.from(new Set((uids || []).map(cleanStr).filter(Boolean)));
  const out = new Map();
  if (!unique.length) return out;

  const tables = await getExistingTables(db);
  for (let offset = 0; offset < unique.length; offset += 40) {
    const chunk = unique.slice(offset, offset + 40);
    const paths = chunk.map(u => `users/${u}`);
    const ph = paths.map(() => '?').join(',');
    try {
      const rows = await db.prepare(
        `SELECT doc_id, document_json FROM friendbook_cf_documents WHERE path IN (${ph})`
      ).bind(...paths).all();
      for (const r of rows?.results || []) {
        const plain = normalizeDocShape('users', r.doc_id, toPlainDocument(r.document_json, r.doc_id));
        if (plain) out.set(r.doc_id, plain);
      }
    } catch { /* ignore */ }

    const missing = chunk.filter(u => !out.has(u));
    if (missing.length && tables.has('users')) {
      const mph = missing.map(() => '?').join(',');
      try {
        const lRows = await db.prepare(`SELECT * FROM users WHERE uid IN (${mph})`).bind(...missing).all();
        for (const lr of lRows?.results || []) {
          const mapped = mapLegacyRow('users', lr);
          if (mapped && !out.has(mapped.uid)) out.set(mapped.uid, mapped);
        }
      } catch { /* ignore */ }
    }

    const stillMissing = chunk.filter(u => !out.has(u) || (!cleanStr(out.get(u)?.photoUrl) && isPlaceholderName(out.get(u)?.displayName)));
    if (stillMissing.length && tables.has('friendbook_cf_profiles')) {
      const pph = stillMissing.map(() => '?').join(',');
      try {
        const pRows = await db.prepare(
          `SELECT uid, display_name, cloudinary_avatar_url FROM friendbook_cf_profiles WHERE uid IN (${pph})`
        ).bind(...stillMissing).all();
        for (const pr of pRows?.results || []) {
          const existing = out.get(pr.uid) || { uid: pr.uid, id: pr.uid };
          if (isPlaceholderName(existing.displayName) && cleanStr(pr.display_name)) {
            existing.displayName = cleanStr(pr.display_name);
          }
          if (!cleanStr(existing.photoUrl) && cleanStr(pr.cloudinary_avatar_url)) {
            existing.photoUrl = cleanStr(pr.cloudinary_avatar_url);
          }
          out.set(pr.uid, existing);
        }
      } catch { /* ignore */ }
    }
  }
  return out;
}

// Authoritative friend UIDs for a user from the `friends` collection + mutual follow fallback
export async function getAuthoritativeFriendUids(db, uid) {
  const cleanUid = cleanStr(uid);
  if (!cleanUid) return [];
  const friendSet = new Set();
  try {
    const rows = await db.prepare(
      `SELECT doc_id, document_json FROM friendbook_cf_documents
       WHERE collection = 'friends'
         AND (doc_id LIKE ? OR doc_id LIKE ? OR instr(document_json, ?) > 0)
       LIMIT 5000`
    ).bind(`${cleanUid}_%`, `%_${cleanUid}`, `"${cleanUid}"`).all();
    for (const r of rows?.results || []) {
      const doc = toPlainDocument(r.document_json, r.doc_id);
      const parts = Array.isArray(doc?.participants) ? doc.participants.map(cleanStr).filter(Boolean) : [];
      if (parts.includes(cleanUid)) {
        for (const p of parts) {
          if (p && p !== cleanUid) friendSet.add(p);
        }
      }
    }
  } catch { /* ignore */ }
  return Array.from(friendSet);
}

// Compute exact visible post count for a profile `targetUid` when viewed by `viewerUid`
export async function countVisibleUserPosts(db, targetUid, viewerUid, isFriend = false) {
  const cleanTarget = cleanStr(targetUid);
  if (!cleanTarget) return 0;
  const allPosts = await loadCollectionDocs(db, 'posts', { authorIdHint: cleanTarget });
  const isOwner = cleanTarget === cleanStr(viewerUid);
  let count = 0;
  for (const p of allPosts) {
    if (cleanStr(p.authorId) !== cleanTarget) continue;
    if (p.archived || Number(p.trashedAt || 0) > 0) continue;
    if (Boolean(p.safetyFlagged)) continue;
    const priv = cleanStr(p.privacy || 'Public').toLowerCase();
    if (isOwner) {
      count++;
    } else if (priv === 'only me') {
      continue;
    } else if (priv === 'friends') {
      if (isFriend) count++;
    } else {
      count++;
    }
  }
  return count;
}

// Enrich a single user document with exact friendUids, unified followers/following, friendsCount & postsCount!
async function enrichUserDoc(db, userDoc, viewerUid, includePostCount = true) {
  if (!userDoc) return null;
  const uid = cleanStr(userDoc.uid || userDoc.id);
  if (!uid) return userDoc;

  const friendUids = await getAuthoritativeFriendUids(db, uid);
  const rawFollowers = Array.isArray(userDoc.followers) ? userDoc.followers.map(cleanStr).filter(Boolean) : [];
  const rawFollowing = Array.isArray(userDoc.following) ? userDoc.following.map(cleanStr).filter(Boolean) : [];

  const followers = Array.from(new Set([...rawFollowers, ...friendUids])).filter(x => x && x !== uid);
  const following = Array.from(new Set([...rawFollowing, ...friendUids])).filter(x => x && x !== uid);
  const friendsCount = friendUids.length > 0 ? friendUids.length : Math.max(0, Number(userDoc.friendsCount || 0));

  const enriched = {
    ...userDoc,
    id: uid,
    uid,
    friendUids,
    followers,
    following,
    friendsCount,
  };

  if (includePostCount) {
    const isFriend = uid === viewerUid || friendUids.includes(cleanStr(viewerUid));
    enriched.postsCount = await countVisibleUserPosts(db, uid, viewerUid, isFriend);
  }
  return enriched;
}

// Join author names, photos, ticks, and engagement totals onto a batch of documents!
async function joinDocuments(db, collection, docs) {
  if (!docs || !docs.length) return docs;
  const topCol = collection.includes('/') ? collection.split('/').pop() : collection;

  if (topCol === 'posts' || topCol === 'reels' || topCol === 'stories' || topCol === 'comments') {
    const authorIds = docs.map(d => cleanStr(d.authorId)).filter(Boolean);
    const users = await loadUserSummaries(db, authorIds);

    // Sync D1 interaction reaction/comment totals if present for posts/reels
    const tables = await getExistingTables(db);
    const reactionTotals = new Map();
    const myReactions = new Map();
    const commentTotals = new Map();
    if ((topCol === 'posts' || topCol === 'reels') && tables.has('friendbook_cf_reaction_totals') && docs.length <= 60) {
      const resources = docs.map(d => `${topCol}/${d.id}`);
      const rph = resources.map(() => '?').join(',');
      try {
        const rRows = await db.prepare(
          `SELECT resource, SUM(total) AS sum_total FROM friendbook_cf_reaction_totals WHERE resource IN (${rph}) GROUP BY resource`
        ).bind(...resources).all();
        for (const r of rRows?.results || []) {
          reactionTotals.set(r.resource, Number(r.sum_total || 0));
        }
        const rawRx = await db.prepare(
          `SELECT resource, uid, type FROM friendbook_cf_reactions WHERE resource IN (${rph}) AND type IS NOT NULL`
        ).bind(...resources).all();
        for (const r of rawRx?.results || []) {
          if (!myReactions.has(r.resource)) myReactions.set(r.resource, {});
          myReactions.get(r.resource)[r.uid] = { userId: r.uid, type: r.type };
        }
        if (tables.has('friendbook_cf_comments')) {
          const cRows = await db.prepare(
            `SELECT c.resource, COUNT(*) AS cnt FROM friendbook_cf_comments c
             LEFT JOIN friendbook_cf_comment_details d ON d.resource = c.resource AND d.id = c.id
             WHERE c.resource IN (${rph}) AND COALESCE(d.removed, 0) = 0
             GROUP BY c.resource`
          ).bind(...resources).all();
          for (const c of cRows?.results || []) {
            commentTotals.set(c.resource, Number(c.cnt || 0));
          }
        }
      } catch { /* ignore */ }
    }

    return docs.map(d => {
      const u = users.get(cleanStr(d.authorId));
      const out = { ...d };
      const liveName = cleanStr(u?.displayName);
      const storedName = cleanStr(out.authorName);
      const finalName = !isPlaceholderName(liveName) ? liveName : (!isPlaceholderName(storedName) ? storedName : (liveName || storedName || 'friendbook User'));
      const livePhoto = cleanStr(u?.photoUrl);
      const storedPhoto = cleanStr(out.authorPhotoUrl || out.authorPhoto);
      const finalPhoto = livePhoto || storedPhoto;

      out.authorName = finalName;
      out.authorPhotoUrl = finalPhoto;
      out.authorPhoto = finalPhoto;

      if (u && (topCol === 'posts' || topCol === 'reels')) {
        out.authorVerified = Boolean(u.isVerified || out.authorVerified);
        const tl = cleanStr(u.tickLevel);
        if (tl && tl !== 'none') out.authorTickLevel = tl;
        else if (out.authorVerified && (!out.authorTickLevel || out.authorTickLevel === 'none')) out.authorTickLevel = 'blue';
      }

      if (topCol === 'posts' || topCol === 'reels') {
        const resKey = `${topCol}/${out.id}`;
        if (myReactions.has(resKey)) {
          out.reactionsMap = { ...(out.reactionsMap || {}), ...myReactions.get(resKey) };
        }
        const rxCount = reactionTotals.get(resKey) || 0;
        const mapCount = out.reactionsMap && typeof out.reactionsMap === 'object' ? Object.keys(out.reactionsMap).length : 0;
        out.likes = Math.max(Number(out.likes || 0), rxCount, mapCount);
        const cmCount = commentTotals.get(resKey) || 0;
        out.comments = Math.max(Number(out.comments || 0), cmCount);
      }
      return out;
    });
  }

  if (topCol === 'friend_requests') {
    const uids = docs.flatMap(d => [cleanStr(d.fromUid), cleanStr(d.toUid)]).filter(Boolean);
    const users = await loadUserSummaries(db, uids);
    return docs.map(d => {
      const fromU = users.get(cleanStr(d.fromUid));
      const fn = !isPlaceholderName(fromU?.displayName) ? cleanStr(fromU.displayName)
        : (cleanStr(d.fromUserName || d.fromName) || 'friendbook User');
      const fp = cleanStr(fromU?.photoUrl) || cleanStr(d.fromUserPhotoUrl || d.fromPhoto);
      return {
        ...d,
        fromUserName: fn,
        fromName: fn,
        fromUserPhotoUrl: fp,
        fromPhoto: fp,
      };
    });
  }

  if (topCol === 'notifications') {
    const uids = docs.map(d => cleanStr(d.fromUserId)).filter(u => u && u !== 'friendbook_team');
    const users = await loadUserSummaries(db, uids);
    return docs.map(d => {
      if (cleanStr(d.fromUserId) === 'friendbook_team') return d;
      const fromU = users.get(cleanStr(d.fromUserId));
      const fn = !isPlaceholderName(fromU?.displayName) ? cleanStr(fromU.displayName)
        : (cleanStr(d.fromUserName) || 'Someone');
      const fp = cleanStr(fromU?.photoUrl) || cleanStr(d.fromUserPhotoUrl || d.fromUserPhoto);
      return {
        ...d,
        fromUserName: fn,
        fromUserPhotoUrl: fp,
        fromUserPhoto: fp,
      };
    });
  }

  if (topCol === 'chats') {
    const uids = docs.flatMap(d => Array.isArray(d.participants) ? d.participants : [])
      .map(cleanStr)
      .filter(u => u && u !== 'friendbook_team');
    const users = await loadUserSummaries(db, uids);
    return docs.map(d => {
      const names = { ...(d.participantNames || {}) };
      const photos = { ...(d.participantPhotos || {}) };
      for (const p of Array.isArray(d.participants) ? d.participants : []) {
        const u = users.get(cleanStr(p));
        if (u) {
          if (!isPlaceholderName(u.displayName)) names[p] = cleanStr(u.displayName);
          if (cleanStr(u.photoUrl)) photos[p] = cleanStr(u.photoUrl);
        }
      }
      return { ...d, participantNames: names, participantPhotos: photos };
    });
  }

  if (topCol === 'messages') {
    const uids = docs.map(d => cleanStr(d.senderId)).filter(u => u && u !== 'friendbook_team');
    const users = await loadUserSummaries(db, uids);
    return docs.map(d => {
      if (cleanStr(d.senderId) === 'friendbook_team') return d;
      const u = users.get(cleanStr(d.senderId));
      const sn = !isPlaceholderName(u?.displayName) ? cleanStr(u.displayName)
        : (cleanStr(d.senderName) || 'friendbook User');
      const sp = cleanStr(u?.photoUrl) || cleanStr(d.senderPhoto);
      return { ...d, senderName: sn, senderPhoto: sp };
    });
  }

  return docs;
}

// Load documents for a collection (merging friendbook_cf_documents + subcollection bridges + 0014 legacy tables)
async function loadCollectionDocs(db, collection, hints = {}) {
  const byId = new Map();
  const tables = await getExistingTables(db);

  // 1. Primary: friendbook_cf_documents
  try {
    let rows;
    if (hints.authorIdHint) {
      rows = await db.prepare(
        `SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents
         WHERE collection = ? AND (owner_uid = ? OR instr(document_json, ?) > 0)
         ORDER BY updated_at DESC LIMIT 2000`
      ).bind(collection, hints.authorIdHint, `"${hints.authorIdHint}"`).all();
    } else {
      rows = await db.prepare(
        `SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents
         WHERE collection = ? ORDER BY updated_at DESC LIMIT 3000`
      ).bind(collection).all();
    }
    for (const r of rows?.results || []) {
      const plain = toPlainDocument(r.document_json, r.doc_id);
      if (plain) {
        if (!plain.updatedAt && r.updated_at) plain.updatedAt = Number(r.updated_at);
        byId.set(r.doc_id, normalizeDocShape(collection, r.doc_id, plain));
      }
    }
  } catch { /* ignore */ }

  // 2. Subcollection bridges: chats/{chatId}/messages
  const chatMsgMatch = /^chats\/([^/]+)\/messages$/.exec(collection);
  if (chatMsgMatch) {
    const chatId = chatMsgMatch[1];
    try {
      const flatRows = await db.prepare(
        `SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents
         WHERE collection = 'messages' AND (doc_id LIKE ? OR instr(document_json, ?) > 0)
         ORDER BY updated_at DESC LIMIT 1000`
      ).bind(`${chatId}_%`, `"${chatId}"`).all();
      for (const r of flatRows?.results || []) {
        const plain = toPlainDocument(r.document_json, r.doc_id);
        if (!plain) continue;
        const msgId = r.doc_id.startsWith(`${chatId}_`) ? r.doc_id.slice(chatId.length + 1) : r.doc_id;
        if (r.doc_id.startsWith(`${chatId}_`) || cleanStr(plain.chatId) === chatId) {
          if (!byId.has(msgId)) {
            byId.set(msgId, normalizeDocShape('messages', msgId, { ...plain, id: msgId, chatId }));
          }
        }
      }
    } catch { /* ignore */ }
    if (tables.has('messages')) {
      try {
        const mRows = await db.prepare(
          'SELECT * FROM messages WHERE chat_id = ? ORDER BY created_at DESC LIMIT 1000'
        ).bind(chatId).all();
        for (const mr of mRows?.results || []) {
          const mapped = mapLegacyRow('messages', mr);
          if (mapped && !byId.has(mapped.id)) byId.set(mapped.id, mapped);
        }
      } catch { /* ignore */ }
    }
  }

  // 3. Subcollection bridges: posts/{postId}/comments & reels/{reelId}/comments
  const commentSubMatch = /^(posts|reels)\/([^/]+)\/comments$/.exec(collection);
  if (commentSubMatch) {
    const [, parentCol, parentId] = commentSubMatch;
    const resource = `${parentCol}/${parentId}`;
    if (tables.has('friendbook_cf_comments')) {
      try {
        const cRows = await db.prepare(
          `SELECT c.id, c.uid, c.text, c.parent_id, c.created_at,
                  d.media_url, d.mentions, d.name, d.photo, d.edited, d.removed
           FROM friendbook_cf_comments c
           LEFT JOIN friendbook_cf_comment_details d ON d.resource = c.resource AND d.id = c.id
           WHERE c.resource = ? AND COALESCE(d.removed, 0) = 0
           ORDER BY c.created_at DESC LIMIT 500`
        ).bind(resource).all();
        for (const cr of cRows?.results || []) {
          if (!byId.has(cr.id)) {
            byId.set(cr.id, normalizeDocShape('comments', cr.id, {
              id: cr.id,
              postId: parentId,
              authorId: cr.uid,
              authorName: cleanStr(cr.name) || 'friendbook User',
              authorPhotoUrl: cleanStr(cr.photo),
              authorPhoto: cleanStr(cr.photo),
              content: cr.text || '',
              imageUrl: cleanStr(cr.media_url),
              parentId: cleanStr(cr.parent_id),
              edited: Boolean(cr.edited),
              mentions: safeJsonParse(cr.mentions, []),
              createdAt: Number(cr.created_at || 0),
            }));
          }
        }
      } catch { /* ignore */ }
    }
    if (parentCol === 'posts' && tables.has('comments')) {
      try {
        const lRows = await db.prepare(
          'SELECT * FROM comments WHERE post_id = ? ORDER BY created_at DESC LIMIT 500'
        ).bind(parentId).all();
        for (const lr of lRows?.results || []) {
          const mapped = mapLegacyRow('comments', lr);
          if (mapped && !byId.has(mapped.id)) byId.set(mapped.id, mapped);
        }
      } catch { /* ignore */ }
    }
  }

  // 4. Fallback/merge from 0014 relational tables for top-level collections
  const legacyAllowed = new Set([
    'users', 'posts', 'comments', 'chats', 'groups',
    'friend_requests', 'notifications', 'stories', 'reels',
  ]);
  if (legacyAllowed.has(collection) && tables.has(collection)) {
    try {
      let sql = `SELECT * FROM ${collection}`;
      let binds = [];
      if (collection === 'posts' && hints.authorIdHint) {
        sql += ' WHERE author_id = ? ORDER BY created_at DESC LIMIT 1000';
        binds = [hints.authorIdHint];
      } else if (collection === 'users') {
        sql += ' ORDER BY created_at DESC LIMIT 2000';
      } else {
        sql += ' LIMIT 2000';
      }
      const lRows = await db.prepare(sql).bind(...binds).all();
      for (const lr of lRows?.results || []) {
        const mapped = mapLegacyRow(collection, lr);
        if (mapped && !byId.has(mapped.id)) {
          byId.set(mapped.id, normalizeDocShape(collection, mapped.id, mapped));
        }
      }
    } catch { /* ignore */ }
  }

  // 5. Fallback from copy staging if collection is still empty
  if (byId.size === 0 && tables.has('friendbook_cf_copy_staging') && !collection.includes('/')) {
    try {
      const sRows = await db.prepare(
        'SELECT path, document_json FROM friendbook_cf_copy_staging WHERE path LIKE ? LIMIT 1000'
      ).bind(`${collection}/%`).all();
      for (const sr of sRows?.results || []) {
        const rest = String(sr.path || '').slice(collection.length + 1);
        if (!rest || rest.includes('/')) continue;
        const plain = toPlainDocument(sr.document_json, rest);
        if (plain && !byId.has(rest)) {
          byId.set(rest, normalizeDocShape(collection, rest, plain));
        }
      }
    } catch { /* ignore */ }
  }

  return Array.from(byId.values());
}

function getFieldValue(doc, field) {
  if (!doc || !field) return undefined;
  if (field === '__name__') return doc.id;
  if (field in doc) return doc[field];
  if (field.includes('.')) {
    let cur = doc;
    for (const part of field.split('.')) {
      if (cur === null || cur === undefined || typeof cur !== 'object') return undefined;
      cur = cur[part];
    }
    return cur;
  }
  return undefined;
}

function valuesEqual(a, b) {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((v, i) => valuesEqual(v, b[i]));
  }
  if (a === null || b === null || a === undefined || b === undefined) return false;
  return String(a) === String(b) && typeof a === typeof b;
}

function compareValues(a, b) {
  if (valuesEqual(a, b)) return 0;
  if (a === undefined || a === null) return -1;
  if (b === undefined || b === null) return 1;
  if (typeof a === 'number' && typeof b === 'number') return a < b ? -1 : 1;
  if (typeof a === 'boolean' && typeof b === 'boolean') return a === b ? 0 : (a ? 1 : -1);
  return String(a) < String(b) ? -1 : 1;
}

function matchesFilter(doc, f) {
  const field = f.field;
  const op = f.op;
  const target = f.value;
  const actual = getFieldValue(doc, field);

  switch (op) {
    case '==':
      if (target === false && (actual === undefined || actual === null || actual === false || actual === 0)) return true;
      return valuesEqual(actual, target);
    case '!=':
      return !valuesEqual(actual, target);
    case '<':
      return actual !== undefined && actual !== null && compareValues(actual, target) < 0;
    case '<=':
      return actual !== undefined && actual !== null && compareValues(actual, target) <= 0;
    case '>':
      return actual !== undefined && actual !== null && compareValues(actual, target) > 0;
    case '>=':
      return actual !== undefined && actual !== null && compareValues(actual, target) >= 0;
    case 'array-contains':
      return Array.isArray(actual) && actual.some(item => valuesEqual(item, target));
    case 'array-contains-any':
      return Array.isArray(actual) && Array.isArray(target) && actual.some(item => target.some(t => valuesEqual(item, t)));
    case 'in':
      return Array.isArray(target) && target.some(t => valuesEqual(actual, t));
    default:
      return true;
  }
}

function compareDocs(a, b, orders) {
  const effectiveOrders = (Array.isArray(orders) && orders.length)
    ? orders
    : [{ field: '__name__', dir: 'ASC' }];
  for (const ord of effectiveOrders) {
    const dir = String(ord.dir || 'ASC').toUpperCase() === 'DESC' ? -1 : 1;
    const cmp = compareValues(getFieldValue(a, ord.field), getFieldValue(b, ord.field));
    if (cmp !== 0) return cmp * dir;
  }
  return compareValues(a.id, b.id);
}

function applyCursorBound(docs, bound, orders, mode) {
  if (!bound || !docs.length) return docs;
  if (bound.docId) {
    const idx = docs.findIndex(d => d.id === bound.docId);
    if (idx >= 0) {
      if (mode === 'startAfter') return docs.slice(idx + 1);
      if (mode === 'startAt') return docs.slice(idx);
      if (mode === 'endBefore') return docs.slice(0, idx);
      if (mode === 'endAt') return docs.slice(0, idx + 1);
    }
  }
  if (Array.isArray(bound.values) && bound.values.length) {
    const effectiveOrders = (Array.isArray(orders) && orders.length)
      ? orders
      : [{ field: '__name__', dir: 'ASC' }];
    return docs.filter(doc => {
      let cmp = 0;
      for (let i = 0; i < Math.min(bound.values.length, effectiveOrders.length); i++) {
        const ord = effectiveOrders[i];
        const dir = String(ord.dir || 'ASC').toUpperCase() === 'DESC' ? -1 : 1;
        const c = compareValues(getFieldValue(doc, ord.field), bound.values[i]) * dir;
        if (c !== 0) {
          cmp = c;
          break;
        }
      }
      if (mode === 'startAfter') return cmp > 0;
      if (mode === 'startAt') return cmp >= 0;
      if (mode === 'endBefore') return cmp < 0;
      if (mode === 'endAt') return cmp <= 0;
      return true;
    });
  }
  return docs;
}

// Apply FieldValue sentinels (__fv) onto an existing document object
function applyFieldValue(existingVal, incomingVal, now) {
  if (incomingVal && typeof incomingVal === 'object' && !Array.isArray(incomingVal) && typeof incomingVal.__fv === 'string') {
    const kind = incomingVal.__fv;
    if (kind === 'serverTimestamp') return now;
    if (kind === 'delete') return Symbol.for('cfdb.delete');
    if (kind === 'increment') {
      const base = typeof existingVal === 'number' && Number.isFinite(existingVal) ? existingVal : Number(existingVal || 0);
      const delta = Number(incomingVal.n || 0);
      return (Number.isFinite(base) ? base : 0) + (Number.isFinite(delta) ? delta : 0);
    }
    if (kind === 'arrayUnion') {
      const base = Array.isArray(existingVal) ? [...existingVal] : [];
      for (const item of Array.isArray(incomingVal.v) ? incomingVal.v : []) {
        if (!base.some(x => valuesEqual(x, item))) base.push(item);
      }
      return base;
    }
    if (kind === 'arrayRemove') {
      const base = Array.isArray(existingVal) ? existingVal : [];
      const rem = Array.isArray(incomingVal.v) ? incomingVal.v : [];
      return base.filter(x => !rem.some(r => valuesEqual(x, r)));
    }
  }
  if (Array.isArray(incomingVal)) {
    return incomingVal.map(item => {
      const res = applyFieldValue(undefined, item, now);
      return res === Symbol.for('cfdb.delete') ? null : res;
    });
  }
  if (incomingVal && typeof incomingVal === 'object') {
    const baseObj = (existingVal && typeof existingVal === 'object' && !Array.isArray(existingVal))
      ? { ...existingVal }
      : {};
    const out = {};
    for (const [k, v] of Object.entries(incomingVal)) {
      const res = applyFieldValue(baseObj[k], v, now);
      if (res !== Symbol.for('cfdb.delete')) out[k] = res;
    }
    return out;
  }
  return incomingVal;
}

function mergePayload(existingDoc, incoming, mode, now) {
  const DEL = Symbol.for('cfdb.delete');
  if (mode === 'replace') {
    const out = {};
    for (const [k, v] of Object.entries(incoming || {})) {
      const res = applyFieldValue(existingDoc?.[k], v, now);
      if (res !== DEL) out[k] = res;
    }
    return out;
  }

  const out = { ...(existingDoc || {}) };
  for (const [k, v] of Object.entries(incoming || {})) {
    if (mode === 'update' && k.includes('.')) {
      const segs = k.split('.');
      let cur = out;
      for (let i = 0; i < segs.length - 1; i++) {
        const s = segs[i];
        if (!cur[s] || typeof cur[s] !== 'object' || Array.isArray(cur[s])) cur[s] = {};
        else cur[s] = { ...cur[s] };
        cur = cur[s];
      }
      const leaf = segs[segs.length - 1];
      const res = applyFieldValue(cur[leaf], v, now);
      if (res === DEL) delete cur[leaf];
      else cur[leaf] = res;
    } else if (
      mode === 'merge'
      && v && typeof v === 'object' && !Array.isArray(v) && !v.__fv
      && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])
    ) {
      out[k] = mergePayload(out[k], v, 'merge', now);
    } else {
      const res = applyFieldValue(out[k], v, now);
      if (res === DEL) delete out[k];
      else out[k] = res;
    }
  }
  return out;
}

async function saveRawDoc(db, collection, docId, docObj, fallbackOwnerUid, now) {
  const normalized = normalizeDocShape(collection, docId, docObj);
  normalized.id = docId;
  if (!normalized.createdAt) normalized.createdAt = now;
  normalized.updatedAt = now;
  const ownerUid = cleanStr(
    normalized.authorId || normalized.uid || normalized.senderId
    || normalized.fromUid || normalized.toUserId || normalized.adminId
    || normalized.createdBy || fallbackOwnerUid
  );
  const path = `${collection}/${docId}`;
  const serialized = JSON.stringify(normalized);
  await db.prepare(
    `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET
       owner_uid = excluded.owner_uid,
       document_json = excluded.document_json,
       updated_at = excluded.updated_at`
  ).bind(path, collection, docId, ownerUid, serialized, now).run();

  if (collection === 'users') {
    const dn = cleanStr(normalized.displayName).slice(0, 200);
    const av = cleanStr(normalized.photoUrl).slice(0, 2048) || null;
    await db.prepare(
      `INSERT INTO friendbook_cf_profiles(uid, display_name, cloudinary_avatar_url)
       VALUES (?, ?, ?)
       ON CONFLICT(uid) DO UPDATE SET
         display_name = CASE WHEN excluded.display_name != '' THEN excluded.display_name ELSE friendbook_cf_profiles.display_name END,
         cloudinary_avatar_url = COALESCE(excluded.cloudinary_avatar_url, friendbook_cf_profiles.cloudinary_avatar_url)`
    ).bind(docId, dn, av).run();
  }

  if (collection === 'posts' || collection === 'reels') {
    try {
      await db.prepare(
        "INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state = 'verified'"
      ).bind(path).run();
    } catch { /* optional table */ }
  }
  return normalized;
}

// Keep `users.followers`, `users.following`, and `users.friendsCount` 100% synced whenever friendships change!
async function syncFriendshipUsers(db, uidA, uidB, isAdd, now) {
  const a = cleanStr(uidA);
  const b = cleanStr(uidB);
  if (!a || !b || a === b) return;

  for (const [selfUid, otherUid] of [[a, b], [b, a]]) {
    const existing = (await readRawDoc(db, 'users', selfUid)) || {
      id: selfUid,
      uid: selfUid,
      displayName: 'friendbook User',
      followers: [],
      following: [],
      friendsCount: 0,
      createdAt: now,
    };
    let followers = Array.isArray(existing.followers) ? existing.followers.map(cleanStr).filter(Boolean) : [];
    let following = Array.isArray(existing.following) ? existing.following.map(cleanStr).filter(Boolean) : [];

    if (isAdd) {
      if (!followers.includes(otherUid)) followers.push(otherUid);
      if (!following.includes(otherUid)) following.push(otherUid);
    } else {
      followers = followers.filter(x => x !== otherUid);
      following = following.filter(x => x !== otherUid);
    }
    const friendUids = await getAuthoritativeFriendUids(db, selfUid);
    existing.followers = Array.from(new Set([...followers, ...friendUids])).filter(x => x && x !== selfUid);
    existing.following = Array.from(new Set([...following, ...friendUids])).filter(x => x && x !== selfUid);
    existing.friendsCount = friendUids.length;
    await saveRawDoc(db, 'users', selfUid, existing, selfUid, now);
  }
}

export async function handleCfDb(request, env, identity) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = cleanStr(identity?.uid);
  if (!uid) return json({ error: 'Unauthorized' }, 401);

  await ensureSchema(env.DB);

  const url = new URL(request.url);
  const path = url.pathname;
  if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

  let payload;
  try { payload = await request.json(); }
  catch { return json({ error: 'Invalid JSON body' }, 400); }
  if (!payload || typeof payload !== 'object') return json({ error: 'Invalid request' }, 400);

  // 1. Single Document Read: POST /v1/cfdb/get
  if (path === '/v1/cfdb/get') {
    const parsed = splitDocPath(payload.path);
    if (!parsed) return json({ error: 'Invalid document path' }, 400);
    let doc = await readRawDoc(env.DB, parsed.collection, parsed.docId);
    if (!doc) return json({ exists: false, id: parsed.docId, path: parsed.path, data: null });

    if (parsed.collection === 'users') {
      doc = await enrichUserDoc(env.DB, doc, uid, true);
    } else {
      const joined = await joinDocuments(env.DB, parsed.collection, [doc]);
      doc = joined[0] || doc;
    }
    return json({ exists: true, id: parsed.docId, path: parsed.path, data: doc });
  }

  // 2. Collection / Subcollection Query & Count: POST /v1/cfdb/query
  if (path === '/v1/cfdb/query') {
    const collection = normalizeCollectionPath(payload.collection);
    if (!collection) return json({ error: 'Invalid collection path' }, 400);

    const filters = Array.isArray(payload.filters) ? payload.filters : [];
    const orders = Array.isArray(payload.orders) ? payload.orders : [];
    const countOnly = Boolean(payload.countOnly);

    let authorIdHint = null;
    for (const f of filters) {
      if (f?.field === 'authorId' && f?.op === '==' && typeof f.value === 'string') {
        authorIdHint = f.value;
      }
    }

    // Special unified handling for FollowListActivity queries:
    // - Followers of targetUid: users where `following` array-contains targetUid
    // - Following of targetUid: users where `followers` array-contains targetUid
    // Must ALWAYS include targetUid's friends + targetUid's followers/following list so Count == List!
    if (collection === 'users' && filters.length === 1 && filters[0]?.op === 'array-contains'
        && (filters[0].field === 'following' || filters[0].field === 'followers')) {
      const targetUid = cleanStr(filters[0].value);
      const targetDoc = await readRawDoc(env.DB, 'users', targetUid);
      const enrichedTarget = await enrichUserDoc(env.DB, targetDoc || { id: targetUid, uid: targetUid }, uid, false);
      const wantFollowers = filters[0].field === 'following';
      const memberIds = new Set(wantFollowers ? (enrichedTarget?.followers || []) : (enrichedTarget?.following || []));

      // Also include any user whose own doc explicitly lists targetUid
      const allUsers = await loadCollectionDocs(env.DB, 'users');
      for (const u of allUsers) {
        if (matchesFilter(u, filters[0]) && u.id !== targetUid) memberIds.add(u.id);
      }
      memberIds.delete(targetUid);

      const loadedMap = await loadUserSummaries(env.DB, Array.from(memberIds));
      let matchedUsers = [];
      for (const mid of memberIds) {
        const u = loadedMap.get(mid);
        if (u && !u.deactivated) matchedUsers.push(normalizeDocShape('users', mid, u));
      }
      matchedUsers.sort((a, b) => compareDocs(a, b, orders));
      if (countOnly) return json({ count: matchedUsers.length });
      const lim = Math.max(1, Math.min(500, Number(payload.limit || 200)));
      matchedUsers = applyCursorBound(matchedUsers, payload.startAfter, orders, 'startAfter');
      return json({
        documents: matchedUsers.slice(0, lim).map(d => ({ id: d.id, path: `users/${d.id}`, data: d })),
      });
    }

    let docs = await loadCollectionDocs(env.DB, collection, { authorIdHint });

    // Exclude soft-deleted / archived posts on Count queries or general feed queries when appropriate
    if (collection === 'posts') {
      docs = docs.filter(p => !p.archived && !(Number(p.trashedAt || 0) > 0));
    }
    if (collection.endsWith('/messages')) {
      docs = docs.filter(m => !m.isDeleted);
    }

    // Apply all query filters
    for (const f of filters) {
      if (f && f.field && f.op) {
        docs = docs.filter(doc => matchesFilter(doc, f));
      }
    }

    // Deduplicate pending friend_requests by (fromUid, toUid) so legacy duplicates never clutter UI
    if (collection === 'friend_requests') {
      const seenPairs = new Set();
      const deduped = [];
      for (const r of docs) {
        if (r.status === 'pending') {
          const key = `${cleanStr(r.fromUid)}->${cleanStr(r.toUid)}`;
          if (seenPairs.has(key)) continue;
          seenPairs.add(key);
        }
        deduped.push(r);
      }
      docs = deduped;
    }

    // Deduplicate friendships by sorted participant pair
    if (collection === 'friends') {
      const seenPairs = new Set();
      const deduped = [];
      for (const f of docs) {
        const parts = Array.isArray(f.participants) ? [...f.participants].map(cleanStr).filter(Boolean).sort() : [];
        if (parts.length >= 2) {
          const key = parts.join('_');
          if (seenPairs.has(key)) continue;
          seenPairs.add(key);
        }
        deduped.push(f);
      }
      docs = deduped;
    }

    // Sort according to orderBy (default: createdAt DESC if present on collection, else docId ASC)
    const effectiveOrders = orders.length
      ? orders
      : (collection === 'posts' || collection === 'reels' || collection === 'notifications' || collection === 'friend_requests'
          ? [{ field: 'createdAt', dir: 'DESC' }]
          : [{ field: '__name__', dir: 'ASC' }]);
    docs.sort((a, b) => compareDocs(a, b, effectiveOrders));

    // Apply cursors
    docs = applyCursorBound(docs, payload.startAt, effectiveOrders, 'startAt');
    docs = applyCursorBound(docs, payload.startAfter, effectiveOrders, 'startAfter');
    docs = applyCursorBound(docs, payload.endBefore, effectiveOrders, 'endBefore');
    docs = applyCursorBound(docs, payload.endAt, effectiveOrders, 'endAt');

    if (countOnly) {
      return json({ count: docs.length });
    }

    const rawLimit = Number(payload.limit || 0);
    const rawLimitToLast = Number(payload.limitToLast || 0);
    if (rawLimitToLast > 0) {
      const lim = Math.min(500, Math.trunc(rawLimitToLast));
      if (docs.length > lim) docs = docs.slice(docs.length - lim);
    } else if (rawLimit > 0) {
      const lim = Math.min(500, Math.trunc(rawLimit));
      if (docs.length > lim) docs = docs.slice(0, lim);
    } else if (docs.length > 300) {
      docs = docs.slice(0, 300);
    }

    // Relational join on the selected page ONLY (fast, bounded SQL IN lookup!)
    if (collection === 'users') {
      if (docs.length <= 25) {
        docs = await Promise.all(docs.map(u => enrichUserDoc(env.DB, u, uid, false)));
      }
    } else {
      docs = await joinDocuments(env.DB, collection, docs);
    }

    return json({
      documents: docs.map(d => ({
        id: d.id,
        path: `${collection}/${d.id}`,
        data: d,
      })),
    });
  }

  // 3. Atomic Document / Batch / Transaction Writes: POST /v1/cfdb/write
  if (path === '/v1/cfdb/write') {
    const writes = Array.isArray(payload.writes) ? payload.writes : [];
    if (!writes.length || writes.length > 250) return json({ error: 'Invalid write batch' }, 400);

    const now = Date.now();
    const claims = decodeJwtClaimsUnsafe(identity?.token);
    const callerEmail = cleanStr(claims?.email).toLowerCase();
    const results = [];
    const friendSyncPairs = [];

    for (const w of writes) {
      const parsed = splitDocPath(w?.path);
      if (!parsed) return json({ error: 'Invalid write path' }, 400);
      const op = String(w.op || 'set');
      let { collection, docId } = parsed;

      if (op === 'delete') {
        if (collection === 'friends') {
          const existing = await readRawDoc(env.DB, 'friends', docId);
          const parts = Array.isArray(existing?.participants) ? existing.participants.map(cleanStr).filter(Boolean) : [];
          await env.DB.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(parsed.path).run();
          if (parts.length === 2) {
            const [uA, uB] = parts;
            // Remove any duplicate legacy friend docs for this pair too
            await env.DB.prepare(
              'DELETE FROM friendbook_cf_documents WHERE path IN (?, ?)'
            ).bind(`friends/${uA}_${uB}`, `friends/${uB}_${uA}`).run();
            friendSyncPairs.push([uA, uB, false]);
          }
        } else if (collection === 'posts') {
          await env.DB.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(parsed.path).run();
          try { await env.DB.prepare('UPDATE posts SET archived = 1, trashed_at = ? WHERE id = ?').bind(now, docId).run(); } catch {}
        } else {
          await env.DB.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(parsed.path).run();
          const cSub = /^(posts|reels)\/([^/]+)\/comments$/.exec(collection);
          if (cSub) {
            const [, pCol, pId] = cSub;
            try {
              await env.DB.prepare(
                'INSERT INTO friendbook_cf_comment_details(resource, id, removed) VALUES (?, ?, 1) ON CONFLICT(resource, id) DO UPDATE SET removed = 1'
              ).bind(`${pCol}/${pId}`, docId).run();
            } catch {}
          }
        }
        results.push({ path: parsed.path, deleted: true });
        continue;
      }

      const incoming = (w.data && typeof w.data === 'object' && !Array.isArray(w.data)) ? w.data : {};

      // Canonicalize `friends` doc ID to sorted `uidA_uidB` so duplicates never happen!
      if (collection === 'friends' && Array.isArray(incoming.participants) && incoming.participants.length === 2) {
        const sorted = incoming.participants.map(cleanStr).filter(Boolean).sort();
        if (sorted.length === 2 && sorted[0] !== sorted[1]) {
          docId = `${sorted[0]}_${sorted[1]}`;
          incoming.participants = sorted;
        }
      }

      const existing = await readRawDoc(env.DB, collection, docId);
      const mode = op === 'update' ? 'update' : (w.merge ? 'merge' : 'replace');
      const merged = mergePayload(existing, incoming, mode, now);

      // Hydrate author fields on posts / reels / stories at write time too so nothing is ever blank!
      if (collection === 'posts' || collection === 'reels' || collection === 'stories') {
        const authorUid = cleanStr(merged.authorId) || uid;
        merged.authorId = authorUid;
        const authorDoc = await readRawDoc(env.DB, 'users', authorUid);
        if (isPlaceholderName(merged.authorName)) {
          merged.authorName = !isPlaceholderName(authorDoc?.displayName)
            ? cleanStr(authorDoc.displayName)
            : (cleanStr(claims?.name) || (callerEmail ? callerEmail.split('@')[0] : 'friendbook User'));
        }
        const photo = cleanStr(merged.authorPhotoUrl || merged.authorPhoto || authorDoc?.photoUrl || claims?.picture);
        merged.authorPhotoUrl = photo;
        merged.authorPhoto = photo;
        if (authorDoc) {
          merged.authorVerified = Boolean(authorDoc.isVerified || merged.authorVerified);
          if (cleanStr(authorDoc.tickLevel) && authorDoc.tickLevel !== 'none') {
            merged.authorTickLevel = authorDoc.tickLevel;
          }
        }
      }

      if (collection === 'users' && docId === uid && callerEmail === OWNER_EMAIL) {
        merged.isAdmin = true;
        merged.isVerified = true;
        if (!merged.email) merged.email = OWNER_EMAIL;
      }

      const saved = await saveRawDoc(env.DB, collection, docId, merged, uid, now);

      // Sync mutual followers/following & friendsCount after all batch ops so increments never double-count!
      if (collection === 'friends' && Array.isArray(saved.participants) && saved.participants.length === 2) {
        const [uA, uB] = saved.participants;
        friendSyncPairs.push([uA, uB, true]);
      }

      // Sync follow/unfollow array mutations on users
      results.push({ path: `${collection}/${docId}`, id: docId, data: saved });
    }

    for (const [uA, uB, added] of friendSyncPairs) {
      await syncFriendshipUsers(env.DB, uA, uB, added, now);
    }

    return json({ ok: true, results });
  }

  return json({ error: 'Not found' }, 404);
}

// Ephemeral RTDB compatibility layer (/v1/rtdb/*) for Calls, Live fast-lane & Active Call status
export async function handleCfRtdb(request, env, identity) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  if (!cleanStr(identity?.uid)) return json({ error: 'Unauthorized' }, 401);
  await ensureSchema(env.DB);

  const url = new URL(request.url);
  const rtdbPath = decodeURIComponent(url.pathname.replace(/^\/v1\/rtdb\/?/, '')).replace(/^\/+|\/+$/g, '');
  if (!rtdbPath || rtdbPath.length > 512) return json({ error: 'Invalid path' }, 400);

  if (rtdbPath === '.info/serverTimeOffset') {
    return json({ path: rtdbPath, value: 0 });
  }

  if (request.method === 'GET') {
    const exact = await env.DB.prepare(
      'SELECT value_json FROM friendbook_cf_rtdb WHERE path = ?'
    ).bind(rtdbPath).first();
    const base = exact ? safeJsonParse(exact.value_json, null) : null;

    // Also assemble any child nodes stored under rtdbPath/*
    const children = await env.DB.prepare(
      'SELECT path, value_json FROM friendbook_cf_rtdb WHERE path LIKE ? LIMIT 200'
    ).bind(`${rtdbPath}/%`).all();

    if (!exact && (!children?.results || children.results.length === 0)) {
      return json({ path: rtdbPath, value: null });
    }

    if (!children?.results || children.results.length === 0) {
      return json({ path: rtdbPath, value: base });
    }

    const tree = (base && typeof base === 'object' && !Array.isArray(base)) ? { ...base } : {};
    for (const row of children.results) {
      const rel = String(row.path).slice(rtdbPath.length + 1).split('/');
      let cur = tree;
      for (let i = 0; i < rel.length - 1; i++) {
        if (!cur[rel[i]] || typeof cur[rel[i]] !== 'object') cur[rel[i]] = {};
        cur = cur[rel[i]];
      }
      cur[rel[rel.length - 1]] = safeJsonParse(row.value_json, null);
    }
    return json({ path: rtdbPath, value: tree });
  }

  const now = Date.now();
  if (request.method === 'DELETE') {
    await env.DB.batch([
      env.DB.prepare('DELETE FROM friendbook_cf_rtdb WHERE path = ?').bind(rtdbPath),
      env.DB.prepare('DELETE FROM friendbook_cf_rtdb WHERE path LIKE ?').bind(`${rtdbPath}/%`),
    ]);
    return json({ ok: true });
  }

  let body;
  try { body = await request.json(); }
  catch { return json({ error: 'Invalid JSON' }, 400); }

  if (request.method === 'PUT') {
    const val = body && 'value' in body ? body.value : body;
    if (val === null || val === undefined) {
      await env.DB.batch([
        env.DB.prepare('DELETE FROM friendbook_cf_rtdb WHERE path = ?').bind(rtdbPath),
        env.DB.prepare('DELETE FROM friendbook_cf_rtdb WHERE path LIKE ?').bind(`${rtdbPath}/%`),
      ]);
      return json({ ok: true, value: null });
    }
    await env.DB.prepare(
      `INSERT INTO friendbook_cf_rtdb(path, value_json, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(path) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at`
    ).bind(rtdbPath, JSON.stringify(val), now).run();
    return json({ ok: true, value: val });
  }

  if (request.method === 'PATCH') {
    const patch = (body && typeof body.value === 'object' && body.value !== null) ? body.value : body;
    const exact = await env.DB.prepare(
      'SELECT value_json FROM friendbook_cf_rtdb WHERE path = ?'
    ).bind(rtdbPath).first();
    const current = exact ? safeJsonParse(exact.value_json, {}) : {};
    const merged = { ...(typeof current === 'object' && current ? current : {}), ...(patch || {}) };
    for (const [k, v] of Object.entries(merged)) {
      if (v === null) delete merged[k];
    }
    // Clear overridden child rows so parent snapshot stays authoritative
    const stmts = [
      env.DB.prepare(
        `INSERT INTO friendbook_cf_rtdb(path, value_json, updated_at) VALUES (?, ?, ?)
         ON CONFLICT(path) DO UPDATE SET value_json = excluded.value_json, updated_at = excluded.updated_at`
      ).bind(rtdbPath, JSON.stringify(merged), now),
    ];
    for (const k of Object.keys(patch || {})) {
      stmts.push(env.DB.prepare('DELETE FROM friendbook_cf_rtdb WHERE path = ?').bind(`${rtdbPath}/${k}`));
    }
    await env.DB.batch(stmts);
    return json({ ok: true, value: merged });
  }

  return json({ error: 'Method not allowed' }, 405);
}

export async function handleCfRestUser(request, env, identity) {
  if (!env?.DB) return json({ error: 'Database unavailable' }, 503);
  const callerUid = cleanStr(identity?.uid);
  if (!callerUid) return json({ error: 'Unauthorized' }, 401);
  await ensureSchema(env.DB);
  const url = new URL(request.url);
  const path = url.pathname;
  const friendsMatch = /^\/v1\/users\/([^/]+)\/friends$/.exec(path);
  if (friendsMatch && request.method === 'GET') {
    const rawUid = decodeURIComponent(friendsMatch[1]);
    const targetUid = rawUid === 'me' ? callerUid : rawUid;
    const friendUids = await getAuthoritativeFriendUids(env.DB, targetUid);
    const profiles = await loadUserSummaries(env.DB, friendUids);
    const friends = friendUids
      .map(uid => profiles.get(uid))
      .filter(p => p && p.uid)
      .map(p => ({
        id: p.uid,
        uid: p.uid,
        displayName: p.displayName || '',
        username: p.username || '',
        photoUrl: p.photoUrl || '',
        isVerified: Boolean(p.isVerified),
        tickLevel: p.tickLevel || 'none',
      }));
    return json({ friends, count: friends.length });
  }
  const userMatch = /^\/v1\/users\/([^/]+)$/.exec(path);
  if (userMatch && request.method === 'GET') {
    const rawUid = decodeURIComponent(userMatch[1]);
    const targetUid = rawUid === 'me' ? callerUid : rawUid;
    let doc = await readRawDoc(env.DB, 'users', targetUid);
    doc = await enrichUserDoc(env.DB, doc || { id: targetUid, uid: targetUid }, callerUid, true);
    return json({ id: targetUid, ...doc });
  }
  return json({ error: 'Not found' }, 404);
}


// Server-side access rules for the Cloudflare document store.
//
// Firebase is authentication only: it proves WHO is calling, and this module decides WHAT they may
// touch. Nothing here trusts a uid, author, visibility or participant claim that arrived in a
// request body or query string - every decision is made from the verified token uid and from rows
// already stored in D1.
//
// The rules are the intent of `firebase/firestore.rules`, tightened where the old file was
// world-readable only because the client always scoped its own queries (notifications, chats,
// messages, friend requests, payments). A list request on those collections is scoped by the Worker
// itself, so one signed-in user can never page through somebody else's inbox or thread.
export const OWNER_EMAIL = 'zulqarnaindiraj786@gmail.com';

/** Collections whose documents any signed-in user may read one by one (public social content). */
const PUBLIC_READ = new Set([
  'users', 'posts', 'comments', 'stories', 'reels', 'groups', 'pages',
  'lives', 'official_broadcasts', 'events', 'boosts', 'config',
]);

/** Moderation inboxes: users may file a report, only admins may read or clear them. */
const ADMIN_ONLY_READ = new Set(['reports', 'feedback', 'mail', 'crashReports', 'passwordOtps']);

/** Fields a list query may filter or order on, per collection. Anything else is rejected. */
export const QUERYABLE = {
  users: { eq: ['displayName', 'isAdmin', 'banned', 'deactivated'], has: ['blocked', 'friends'], order: ['displayName', 'stars', 'createdAt', 'updatedAt'] },
  posts: { eq: ['authorId', 'groupId', 'mediaType', 'privacy', 'postId', 'type', 'archived'], has: ['likedBy', 'savedBy', 'viewedBy', 'participants'], order: ['createdAt', 'likes', 'comments', 'shares', 'updatedAt'] },
  reels: { eq: ['authorId', 'toUid', 'privacy'], has: ['likedBy', 'viewedBy'], order: ['createdAt', 'likes', 'comments', 'shares', 'updatedAt'] },
  comments: { eq: ['postId', 'reelId', 'authorId', 'toUid', 'fromUid', 'parentId'], has: ['likedBy'], order: ['createdAt', 'likes', 'updatedAt'] },
  stories: { eq: ['authorId', 'toUid', 'fromUid', 'mediaType'], has: ['viewedBy'], order: ['createdAt', 'expiresAt', 'updatedAt'] },
  notifications: { eq: ['toUid', 'toUserId', 'fromUid', 'isRead', 'type', 'postId'], has: ['participants'], order: ['createdAt', 'updatedAt'] },
  chats: { eq: ['chatId', 'kind'], has: ['participants', 'members'], order: ['lastMessageAt', 'createdAt', 'updatedAt'] },
  messages: { eq: ['chatId', 'senderId', 'kind'], has: ['seenBy'], order: ['createdAt', 'updatedAt'] },
  friends: { eq: ['uidA', 'uidB', 'status'], has: ['participants'], order: ['createdAt', 'updatedAt'] },
  friend_requests: { eq: ['toUid', 'fromUid', 'status'], has: ['participants'], order: ['createdAt', 'updatedAt'] },
  follows: { eq: ['followerId', 'targetId', 'uid', 'targetUid'], has: ['participants'], order: ['createdAt', 'updatedAt'] },
  groups: { eq: ['adminId', 'privacy', 'kind'], has: ['members', 'adminIds', 'moderatorIds', 'bannedMemberIds'], order: ['createdAt', 'members', 'updatedAt'] },
  pages: { eq: ['ownerId', 'kind'], has: ['followers', 'admins'], order: ['createdAt', 'followers', 'updatedAt'] },
  lives: { eq: ['hostId', 'status'], has: ['viewers', 'coHosts'], order: ['createdAt', 'stars', 'updatedAt'] },
  pokes: { eq: ['from', 'to', 'status'], has: [], order: ['createdAt', 'updatedAt'] },
  payments: { eq: ['userId', 'status', 'kind'], has: [], order: ['createdAt', 'updatedAt'] },
  boosts: { eq: ['userId', 'status', 'postId', 'kind'], has: [], order: ['createdAt', 'updatedAt'] },
  subscriptions: { eq: ['subscriberId', 'creatorId', 'status'], has: [], order: ['createdAt', 'updatedAt'] },
  referrals: { eq: ['refTo', 'refBy', 'status'], has: [], order: ['createdAt', 'updatedAt'] },
  gifts: { eq: ['senderId', 'receiverId', 'liveId'], has: [], order: ['createdAt', 'updatedAt'] },
  events: { eq: ['hostId', 'status'], has: ['interested', 'going'], order: ['createdAt', 'startsAt', 'updatedAt'] },
  sessions: { eq: ['uid', 'device'], has: [], order: ['createdAt', 'updatedAt'] },
  reports: { eq: ['status', 'kind', 'postId', 'reportedUid'], has: [], order: ['createdAt', 'updatedAt'] },
  feedback: { eq: ['uid', 'kind', 'status'], has: [], order: ['createdAt', 'updatedAt'] },
  official_broadcasts: { eq: ['kind'], has: [], order: ['createdAt', 'updatedAt'] },
  config: { eq: ['kind'], has: [], order: ['updatedAt'] },
  mail: { eq: ['to', 'status'], has: [], order: ['createdAt', 'updatedAt'] },
  crashReports: { eq: ['uid', 'version'], has: [], order: ['createdAt', 'updatedAt'] },
  passwordOtps: { eq: ['emailKey'], has: [], order: ['createdAt'] },
};

/**
 * The uid field of a document, per collection: whoever owns it may delete or edit it. `owner_uid`
 * (stamped by the Worker on write) is always accepted as well, so documents migrated from the legacy
 * store keep working even when the field name differs.
 */
const AUTHOR_FIELD = {
  posts: 'authorId', reels: 'authorId', comments: 'authorId', stories: 'authorId',
  lives: 'hostId', events: 'hostId', pages: 'ownerId', groups: 'adminId',
  payments: 'userId', boosts: 'userId', subscriptions: 'subscriberId', referrals: 'refTo',
  gifts: 'senderId', pokes: 'from', sessions: 'uid', reports: 'uid', feedback: 'uid',
};

/** Participant arrays: being in one of these makes a private document readable and writable. */
const PARTICIPANT_FIELDS = {
  chats: ['participants', 'members'],
  friends: ['participants'],
  friend_requests: ['participants'],
  follows: ['participants'],
};

/**
 * Chat messages are gated by their parent chat, not by a field on the message: the Worker reads
 * `chats/<chatId>` and requires the caller to be a participant. A list request on messages must
 * therefore carry `eq[chatId]`, and a single message is checked the same way.
 */
const CHAT_SCOPED = new Set(['messages']);

/**
 * Non-owners may only touch the collaborative counters on these collections - the same restriction
 * `affectedKeys().hasOnly([...])` enforced in firestore.rules.
 */
const COLLABORATIVE_ONLY = {
  lives: ['viewers', 'stars', 'viewCount', 'starTotal'],
  events: ['interested', 'going', 'invited'],
  pages: ['followers', 'followersCount'],
};

/** Equality fields that also prove "this document is about me" on a private collection. */
const ABOUT_ME_FIELDS = {
  notifications: ['toUid', 'toUserId'],
  friend_requests: ['toUid', 'fromUid'],
  pokes: ['to', 'from'],
  payments: ['userId'],
  boosts: ['userId'],
  subscriptions: ['subscriberId', 'creatorId'],
  referrals: ['refTo', 'refBy'],
  gifts: ['senderId', 'receiverId'],
  sessions: ['uid'],
  follows: ['followerId', 'targetId', 'uid', 'targetUid'],
};

/**
 * Creating these documents is bound to one field: the uid it belongs to. A signed-in user can never
 * file a payment, a poke, a gift, a live room, an event or a page on somebody else's behalf.
 */
const CREATE_OWNER_FIELD = {
  pokes: 'from', payments: 'userId', boosts: 'userId', subscriptions: 'subscriberId',
  referrals: 'refTo', gifts: 'senderId', sessions: 'uid', lives: 'hostId', events: 'hostId',
  pages: 'ownerId', groups: 'adminId',
};

/** Money and moderation documents start pending: only an admin may move them on. */
const PENDING_ONLY_CREATE = new Set(['payments', 'boosts', 'subscriptions']);

/** Fields only an admin may change on an existing document (approval state and its audit trail). */
const ADMIN_ONLY_FIELDS = {
  payments: ['status', 'approvedBy', 'approvedAt', 'rejectedAt', 'note'],
  boosts: ['status', 'approvedBy', 'approvedAt', 'stoppedAt'],
  subscriptions: ['status', 'approvedBy', 'approvedAt'],
  referrals: ['status', 'rewarded', 'creditedAt'],
  gifts: ['status', 'creditedAt'],
  reports: ['status', 'resolution', 'resolvedBy'],
  feedback: ['status', 'resolution'],
  users: ['isAdmin', 'banned', 'isModerator', 'deactivated', 'tickLevel', 'stars'],
};

/** Private collections whose owner may delete their own document (the rest are admin-only). */
const OWNER_MAY_DELETE = new Set([
  'notifications', 'pokes', 'friend_requests', 'friends', 'follows', 'sessions', 'chats', 'messages',
]);

export function createOwnerField(collection) {
  return CREATE_OWNER_FIELD[collection] || null;
}

export function adminOnlyFields(collection) {
  return ADMIN_ONLY_FIELDS[collection] || null;
}

export function isPublicRead(collection) {
  return PUBLIC_READ.has(collection);
}

export function isAdminOnlyRead(collection) {
  return ADMIN_ONLY_READ.has(collection);
}

export function authorField(collection) {
  return AUTHOR_FIELD[collection] || null;
}

export function participantFields(collection) {
  return PARTICIPANT_FIELDS[collection] || [];
}

export function isChatScoped(collection) {
  return CHAT_SCOPED.has(collection);
}

export function collaborativeOnly(collection) {
  return COLLABORATIVE_ONLY[collection] || null;
}

export function aboutMeFields(collection) {
  return ABOUT_ME_FIELDS[collection] || [];
}

export function queryable(collection) {
  if (!collection) return null;
  // A nested collection is stored under its parent's path (chats/<chatId>/messages); the rules that
  // decide what may be filtered, ordered or compared belong to the leaf, while the ACL decision uses
  // the parent - so this returns the leaf's spec and callers keep the full path for storage.
  const leaf = collection.includes('/') ? collection.split('/').pop() : collection;
  return QUERYABLE[leaf] || null;
}

/**
 * Can this caller read ONE document of this collection? `doc` is the stored plain document (or null
 * when the row does not exist yet) and `admin` is already resolved by the caller.
 */
export function canRead(collection, doc, uid, admin) {
  if (admin) return true;
  if (isPublicRead(collection)) return true;
  if (isChatScoped(collection)) return false;   // decided by the parent chat, in app-documents.js
  if (isAdminOnlyRead(collection)) return false;
  if (!doc) return false;
  if (matchesAboutMe(collection, doc, uid)) return true;
  if (matchesParticipant(collection, doc, uid)) return true;
  const author = authorField(collection);
  if (author && String(doc[author] || '') === uid) return true;
  if (String(doc.owner_uid || doc.ownerUid || '') === uid) return true;
  return false;
}

/**
 * Can this caller write ONE document? `fields` are the incoming changes, `doc` the stored document
 * (null on create). Mirrors firestore.rules: collaborative counters stay open on public content,
 * private threads need participation, moderation inboxes need an admin.
 */
export function canWrite(collection, doc, fields, uid, admin, method) {
  if (admin) return true;
  if (isAdminOnlyRead(collection)) {
    // Filing a report or feedback is open to any signed-in user; reading or clearing is not.
    return method === 'create' && (collection === 'reports' || collection === 'feedback' || collection === 'crashReports');
  }
  if (collection === 'config') return false;
  if (collection === 'mail' || collection === 'passwordOtps') return method === 'create';
  if (collection === 'users') {
    if (doc === null) return String(fields.uid || uid) === uid;
    if (String(doc.id || uid) === uid || String(doc.uid || '') === uid) {
      // A normal user can never grant themselves admin or lift their own ban.
      const guarded = ['isAdmin', 'banned', 'isModerator', 'deactivated', 'tickLevel'];
      return !guarded.some(key => Object.prototype.hasOwnProperty.call(fields, key));
    }
    // Collaborative counters only (follow/unfollow, friend count) - exactly what the rules allowed.
    const social = ['followers', 'following', 'friendsCount'];
    return Object.keys(fields).every(key => social.includes(key));
  }
  if (collection === 'notifications') {
    if (doc === null) return true;                 // a push writes somebody else's notification
    return matchesAboutMe(collection, doc, uid);   // only the recipient may read or clear it
  }
  const createOwner = createOwnerField(collection);
  const adminFields = adminOnlyFields(collection);
  const incoming = fields && typeof fields === 'object' ? fields : {};

  // Admin-only fields (approval state, ban flags, stars) can never be set by a normal user, not even
  // on their own document - except the one create case below where "pending" is the required value.
  if (adminFields) {
    for (const key of adminFields) {
      if (!Object.prototype.hasOwnProperty.call(incoming, key)) continue;
      if (doc === null && PENDING_ONLY_CREATE.has(collection) && key === 'status'
          && String(incoming.status) === 'pending') continue;
      if (doc !== null && String(incoming[key]) === String(doc[key])) continue;
      return false;
    }
  }

  if (isPrivateCollection(collection)) {
    if (doc === null) {
      const mine = writesAboutMe(collection, incoming, uid) || isParticipantWrite(collection, incoming, uid);
      if (!mine) return false;
      if (createOwner && String(incoming[createOwner] || '') !== uid) return false;
      if (PENDING_ONLY_CREATE.has(collection) && String(incoming.status || '') !== 'pending') return false;
      return true;
    }
    const mine = matchesAboutMe(collection, doc, uid) || matchesParticipant(collection, doc, uid)
      || String(doc[authorField(collection) || ''] || '') === uid;
    if (!mine) return false;
    if (method === 'delete') return OWNER_MAY_DELETE.has(collection);
    return true;
  }
  // Public social content: any signed-in user may create and may touch collaborative counters;
  // deleting stays with the author (or an admin, handled above).
  if (doc === null && createOwnerField(collection) && String((fields || {})[createOwnerField(collection)] || '') !== uid) {
    return false;
  }
  const only = collaborativeOnly(collection);
  if (only && doc) {
    const author = authorField(collection);
    const isAuthor = author && String(doc[author] || '') === uid;
    if (!isAuthor && String(doc.owner_uid || '') !== uid) {
      const keys = Object.keys(fields).filter(key => !['id', 'updatedAt', 'createdAt'].includes(key));
      if (!keys.length || !keys.every(key => only.includes(key))) return false;
    }
  }
  if (method === 'delete') {
    const author = authorField(collection);
    return Boolean(doc && author && String(doc[author] || '') === uid) || Boolean(doc && String(doc.owner_uid || '') === uid);
  }
  return true;
}

function isPrivateCollection(collection) {
  return Boolean(ABOUT_ME_FIELDS[collection] || PARTICIPANT_FIELDS[collection]);
}

function matchesAboutMe(collection, doc, uid) {
  return aboutMeFields(collection).some(field => String(doc[field] || '') === uid);
}

function matchesParticipant(collection, doc, uid) {
  return participantFields(collection).some(field => Array.isArray(doc[field]) && doc[field].map(String).includes(uid));
}

function writesAboutMe(collection, fields, uid) {
  return aboutMeFields(collection).some(field => String(fields[field] || '') === uid);
}

function isParticipantWrite(collection, fields, uid) {
  return participantFields(collection).some(field => Array.isArray(fields[field]) && fields[field].map(String).includes(uid));
}

/**
 * The SQL fragment that scopes a LIST query to this caller, plus its binds. Public collections get
 * no fragment at all; private ones get a positive test on the caller's own uid, so a list request
 * can only ever return documents that belong to the caller (or, for admins, everything).
 */
export function listScope(collection, uid, admin) {
  if (admin) return { sql: '', binds: [] };
  // A nested collection (chats/<chatId>/messages) was already admitted or refused by its parent, so
  // re-scoping it by a field on the child would either leak nothing or - worse - hide real rows from
  // somebody who is entitled to them. The parent's participation check is the whole decision.
  if (String(collection).includes('/')) return { sql: '', binds: [] };
  if (isPublicRead(collection)) return { sql: '', binds: [] };
  // Chat messages are scoped by the parent chat participation check, not by a field on the message.
  if (isChatScoped(collection)) return { sql: '', binds: [] };
  if (isAdminOnlyRead(collection)) return { sql: ' AND 0', binds: [] };
  const clauses = [];
  const binds = [];
  for (const field of aboutMeFields(collection)) {
    clauses.push(`json_extract(document_json, '$.${field}') = ?`);
    binds.push(uid);
  }
  for (const field of participantFields(collection)) {
    clauses.push(`EXISTS (SELECT 1 FROM json_each(json_extract(document_json, '$.${field}')) WHERE value = ?)`);
    binds.push(uid);
  }
  const author = authorField(collection);
  if (author) {
    clauses.push(`json_extract(document_json, '$.${author}') = ?`);
    binds.push(uid);
  }
  clauses.push('owner_uid = ?');
  binds.push(uid);
  if (!clauses.length) return { sql: ' AND 0', binds: [] };
  return { sql: ` AND (${clauses.join(' OR ')})`, binds };
}

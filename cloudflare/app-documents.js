// Cloudflare D1 App Document Store (/v1/docs) + D1-first ACL helpers.
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const ALLOWED_COLLECTIONS = new Set([
  'users', 'posts', 'reels', 'stories', 'chats', 'messages', 'comments',
  'friends', 'friend_requests', 'follows', 'groups', 'pages', 'events',
  'notifications', 'official_broadcasts', 'lives', 'pokes',
  // Everything else this build writes: moderation, money, sessions, marketplace, feedback.
  'reports', 'sessions', 'payments', 'referrals', 'feedback', 'gifts', 'config',
  'subscriptions', 'crashReports', 'broadcasts', 'activity',
]);

function validSegment(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[\/\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
}

export function fromFirestoreValue(v) {
  if (!v || typeof v !== 'object') return null;
  if ('stringValue' in v) return String(v.stringValue);
  if ('booleanValue' in v) return Boolean(v.booleanValue);
  if ('integerValue' in v) return Number(v.integerValue);
  if ('doubleValue' in v) return Number(v.doubleValue);
  if ('timestampValue' in v) {
    const ms = Date.parse(v.timestampValue);
    return Number.isFinite(ms) ? ms : 0;
  }
  if ('nullValue' in v) return null;
  if ('arrayValue' in v) {
    const arr = v.arrayValue?.values;
    return Array.isArray(arr) ? arr.map(fromFirestoreValue) : [];
  }
  if ('mapValue' in v) {
    return fromFirestoreFields(v.mapValue?.fields || {});
  }
  return null;
}

export function fromFirestoreFields(fields) {
  const out = {};
  if (!fields || typeof fields !== 'object') return out;
  for (const [k, v] of Object.entries(fields)) {
    out[k] = fromFirestoreValue(v);
  }
  return out;
}

export function toPlainDocument(rawJson, docId) {
  let parsed;
  try { parsed = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson; }
  catch { return null; }
  if (!parsed || typeof parsed !== 'object') return null;
  const plain = (parsed.fields && typeof parsed.fields === 'object' && !Array.isArray(parsed.fields))
    ? fromFirestoreFields(parsed.fields)
    : { ...parsed };
  if (docId && !plain.id) plain.id = docId;
  if (!plain.createdAt && typeof parsed.createTime === 'string') {
    const ms = Date.parse(parsed.createTime);
    if (Number.isFinite(ms)) plain.createdAt = ms;
  }
  return plain;
}

function toFirestoreValue(v) {
  if (v === null || v === undefined) return { nullValue: null };
  if (typeof v === 'boolean') return { booleanValue: v };
  if (typeof v === 'number') {
    return Number.isInteger(v) ? { integerValue: String(v) } : { doubleValue: v };
  }
  if (typeof v === 'string') return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(toFirestoreValue) } };
  if (typeof v === 'object') {
    const fields = {};
    for (const [k, item] of Object.entries(v)) fields[k] = toFirestoreValue(item);
    return { mapValue: { fields } };
  }
  return { stringValue: String(v) };
}

export function toFirestoreFields(rawJson) {
  let parsed;
  try { parsed = typeof rawJson === 'string' ? JSON.parse(rawJson) : rawJson; }
  catch { return null; }
  if (!parsed || typeof parsed !== 'object') return null;
  if (parsed.fields && typeof parsed.fields === 'object' && !Array.isArray(parsed.fields)) {
    return parsed.fields;
  }
  const fields = {};
  for (const [k, v] of Object.entries(parsed)) {
    fields[k] = toFirestoreValue(v);
  }
  return fields;
}

const DOC_CACHE_TTL_MS = 600_000;
const LIST_CACHE_TTL_MS = 600_000;
const docCache = new WeakMap();
const listCache = new WeakMap();

function getDbMap(store, db) {
  let map = store.get(db);
  if (!map) {
    map = new Map();
    store.set(db, map);
  }
  return map;
}

export async function readD1DocumentFields(db, path) {
  if (!db) return null;
  const now = Date.now();
  const cache = getDbMap(docCache, db);
  const hit = cache.get(path);
  if (hit && hit.expiresAt > now) return hit.fields;
  try {
    const row = await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').bind(path).first();
    const fields = row?.document_json ? toFirestoreFields(row.document_json) : null;
    cache.set(path, { fields, expiresAt: now + DOC_CACHE_TTL_MS });
    return fields;
  } catch {
    return null;
  }
}

export async function hasD1Friendship(db, uidA, uidB) {
  if (!db) return null;
  try {
    const p1 = `friends/${uidA}_${uidB}`, p2 = `friends/${uidB}_${uidA}`;
    const byKey = await db.prepare(
      'SELECT path FROM friendbook_cf_documents WHERE path IN (?, ?) LIMIT 1'
    ).bind(p1, p2).first();
    if (byKey) return true;
    // The app also stores a friendship as one document with a `participants` pair; a friendship
    // written to D1 after the update must unlock "Friends" posts the same way.
    const byParticipants = await db.prepare(
      `SELECT document_json FROM friendbook_cf_documents
        WHERE collection = 'friends'
          AND json_extract(document_json, '$.participants') LIKE ?
          AND json_extract(document_json, '$.participants') LIKE ?
        LIMIT 5`
    ).bind(`%${uidA}%`, `%${uidB}%`).all();
    for (const row of byParticipants?.results || []) {
      try {
        const parsed = JSON.parse(row.document_json);
        const pair = Array.isArray(parsed?.participants) ? parsed.participants.map(String) : [];
        if (pair.includes(uidA) && pair.includes(uidB)) return true;
      } catch { /* a malformed row simply does not prove a friendship */ }
    }
    return null;
  } catch {
    return null;
  }
}

/**
 * The read engine behind `/v1/docs` — the piece that makes "Firebase is auth only" possible for
 * reads, not just for new writes.
 *
 * Firestore's query surface is reproduced on D1 without storing a second copy of the document:
 *  - `order` is an ORDER BY over a JSON path (`json_extract`), `where` becomes a WHERE clause;
 *  - a page is addressed by the same cursor the app already uses (the last document's id), so the
 *    existing paging model (5-15 documents per page) keeps working unchanged;
 *  - a filter that cannot run in SQL (nested array membership, "blocks me" checks) is applied in
 *    the Worker after a bounded read, so a caller can never bypass visibility by passing a filter;
 *  - owner/visibility (`private`, `owner`) is enforced HERE, from the stored document, never from
 *    the request: a caller cannot ask for another user's private rows.
 *
 * Everything is additive: reads never write, and a document that only exists in Firestore simply
 * is not here — the client's union fallback fetches it from the previous source.
 */
const ORDER_FIELDS = new Set([
  'createdAt', 'updatedAt', 'lastMessageTime', 'expiry', 'expiresAt', 'timestamp',
  'displayName', 'likes', 'comments', 'views', 'sharedAt', 'joinedAt',
  // Fields the client's own rows are ordered by: a chat sorts by the millisecond of a message, a
  // marketplace listing by price, a story by when it was posted.
  'createdAtMs', 'lastMessageAt', 'updatedAtMs', 'at', 'ts', 'price', 'amount', 'postedAt',
  'sentAt', 'seenAt', 'viewers',
]);
const FILTER_OPS = new Set(['eq', 'in', 'contains', 'gt', 'gte', 'lt', 'lte', 'ne']);
const MAX_FILTERS = 6;
/*
 * A document belongs to the user who wrote it (`owner_uid`, stored by the Worker - never taken from
 * the request). Writes since the update land here, so this is the only place ownership is enforced.
 * Another signed-in user may only touch the shared counter fields below; the content, the author and
 * the privacy of a document can never be changed by somebody else.
 */
const COUNTER_FIELDS = new Set([
  'likes', 'comments', 'shares', 'views', 'reactions', 'reactionsMap', 'savedBy', 'subscribedBy',
  'reportedBy', 'hiddenBy', 'pollVotes', 'lastSeen', 'reactionTypes',
  // Deltas are how a reader bumps somebody else's counter (a comment, a like, a friend): the Worker
  // applies them to the stored number, so they are counters, not content.
  'commentsDelta', 'likesDelta', 'sharesDelta', 'friendsCount', 'followersCount', 'viewsDelta',
]);

/** Lists a reader may append to / remove themselves from on somebody else's document. */
const SHARED_ARRAYS = new Set([
  'likedBy', 'savedBy', 'viewedBy', 'subscribedBy', 'reportedBy', 'hiddenBy', 'reactions', 'voters',
]);

/** Lists only their owner may change (membership lists, blocks, saved ids on an own profile). */
const OWNED_ARRAYS = new Set([
  'blocked', 'blockedUsers', 'restrictedUids', 'friends', 'following', 'followers',
  'participants', 'participantIds', 'members', 'memberIds', 'adminIds', 'moderatorIds',
  'pendingMemberIds', 'invitedUids', 'bannedMemberIds', 'mutedUids', 'highlightStoryIds',
  'savedReelIds', 'savedPostIds', 'pinnedPostIds', 'storyIds', 'reelIds', 'tags', 'hashtags',
]);

/** `fieldArray` request: `field` + `Add` or `Remove`, applied to the stored list by the database. */
function arrayOp(key) {
  if (typeof key !== 'string') return null;
  for (const suffix of ['Add', 'Remove']) {
    if (!key.endsWith(suffix)) continue;
    const base = key.slice(0, -suffix.length);
    if (!/^[A-Za-z0-9_]{1,48}$/.test(base)) return null;
    if (!SHARED_ARRAYS.has(base) && !OWNED_ARRAYS.has(base)) return null;
    return { base, add: suffix === 'Add' };
  }
  return null;
}

/** Recursive merge: objects merge key by key, `null` deletes a key, everything else replaces. */
function deepMerge(target, patch) {
  const out = { ...target };
  for (const [key, value] of Object.entries(patch || {})) {
    if (value === null) { delete out[key]; continue; }
    if (value && typeof value === 'object' && !Array.isArray(value)
        && out[key] && typeof out[key] === 'object' && !Array.isArray(out[key])) {
      out[key] = deepMerge(out[key], value);
      continue;
    }
    out[key] = value;
  }
  return out;
}

function asList(value) {
  if (Array.isArray(value)) return value.filter((v) => v !== null && v !== undefined);
  return value === null || value === undefined ? [] : [value];
}

/** Counters that other people legitimately move on somebody else's document. */
function personalCounter(field) {
  return field === 'friendsCount' || field === 'followersCount';
}

/** `field + "Delta"` request: the caller is asking for an adjustment of `field`, not a new value. */
function deltaField(key) {
  if (typeof key !== 'string' || !key.endsWith('Delta')) return null;
  const base = key.slice(0, -'Delta'.length);
  return /^[A-Za-z0-9_]{1,48}$/.test(base) && base !== 'is' ? base : null;
}

function jsonPath(field) {
  if (!/^[A-Za-z0-9_]{1,64}$/.test(field || '')) return null;
  return `$.${field}`;
}

function queryPlan(url, collection, limit) {
  const orderField = url.searchParams.get('order') || 'updatedAt';
  const orderPath = jsonPath(orderField);
  if (!orderPath || !ORDER_FIELDS.has(orderField)) return { error: 'Unsupported order field' };
  const dir = (url.searchParams.get('dir') || 'desc').toLowerCase() === 'asc' ? 'ASC' : 'DESC';
  const cursor = url.searchParams.get('cursor') || '';
  if (cursor && !validSegment(cursor)) return { error: 'Invalid cursor' };

  const rawFilters = url.searchParams.get('where') || '';
  const filters = [];
  if (rawFilters) {
    if (rawFilters.length > 512) return { error: 'Filter too large' };
    for (const part of rawFilters.split(',')) {
      if (!part) continue;
      if (filters.length >= MAX_FILTERS) return { error: 'Too many filters' };
      const bits = part.split(':');
      if (bits.length !== 3) return { error: 'Invalid filter' };
      const [field, op, rawValue] = bits;
      const path = jsonPath(field);
      if (!path || !FILTER_OPS.has(op)) return { error: 'Invalid filter' };
      const value = rawValue;
      if (value.length > 256) return { error: 'Filter value too large' };
      filters.push({ field, path, op, value });
    }
  }
  const ownerOnly = url.searchParams.get('owner') === '1';
  const includePrivate = url.searchParams.get('private') === '1';
  const excludeArchived = url.searchParams.get('archived') !== '1';
  // Visibility is one exclusive mode per read, which is also what keeps a shared cache honest:
  //   ownerOnly -> that user's own documents (writes keep their history here)
  //   mine+public/friends -> the feed the query needs (own documents + what he is allowed to see)
  const mineScope = url.searchParams.get('mine') === '1';
  const scope = mineScope
    ? { mode: 'feed', publicOnly: url.searchParams.get('public') === '1', friendsOnly: url.searchParams.get('friends') === '1' }
    : ownerOnly ? { mode: 'owner' } : { mode: 'public' };
  // `private=1` is accepted for compatibility but never widens anything: it only participates in
  // the cache key, so a client cannot opt out of the visibility rules.
  // Every page is per-user now: the visibility rules run on the Worker, so the engine must read a
  // wider window than the page it returns.
  const perUser = true;
  // A per-user page is never cached under a shared key.
  const privateFilter = perUser || filters.length > 0;
  const cacheKey = `${collection}:${limit}:${orderField}:${dir}:${cursor}:${rawFilters}:${scope.mode}:${scope.publicOnly}:${scope.friendsOnly}:${excludeArchived}:${includePrivate}`;

  // SQL can only express the scalar comparisons; array membership is finished in JS below.
  const sqlParts = ['collection = ?'];
  const sqlArgs = [collection];
  if (cursor) {
    sqlParts.push(`doc_id ${dir === 'ASC' ? '>' : '<'} ?`);
    sqlArgs.push(cursor);
  }
  if (excludeArchived) {
    sqlParts.push("json_extract(document_json, '$.archived') IS NOT 1");
  }
  for (const f of filters) {
    if (f.op === 'contains' || f.op === 'in') continue; // applied in JS after the bounded read
    const op = { eq: '=', ne: '!=', gt: '>', gte: '>=', lt: '<', lte: '<=' }[f.op];
    sqlParts.push(`CAST(json_extract(document_json, ?) AS ${f.op === 'eq' || f.op === 'ne' ? 'TEXT' : 'NUMERIC'}) ${op} CAST(? AS ${f.op === 'eq' || f.op === 'ne' ? 'TEXT' : 'NUMERIC'})`);
    sqlArgs.push(f.path, f.value);
  }
  const sql = `SELECT doc_id, document_json, updated_at, owner_uid FROM friendbook_cf_documents `
    + `WHERE ${sqlParts.join(' AND ')} ORDER BY json_extract(document_json, '${orderPath}') ${dir}, doc_id ${dir} LIMIT ?`;

  const accept = (doc) => {
    if (excludeArchived && doc?.archived) return false;
    for (const f of filters) {
      const actual = doc?.[f.field];
      if (f.op === 'contains') {
        if (!Array.isArray(actual) || !actual.map(String).includes(f.value)) return false;
      } else if (f.op === 'in') {
        const wanted = f.value.split('|');
        if (!wanted.includes(String(actual ?? ''))) return false;
      } else if (f.op === 'eq') {
        if (String(actual ?? '') !== f.value) return false;
      } else if (f.op === 'ne') {
        if (String(actual ?? '') === f.value) return false;
      } else {
        const n = Number(actual), bound = Number(f.value);
        if (!Number.isFinite(n) || !Number.isFinite(bound)) return false;
        if (f.op === 'gt' && !(n > bound)) return false;
        if (f.op === 'gte' && !(n >= bound)) return false;
        if (f.op === 'lt' && !(n < bound)) return false;
        if (f.op === 'lte' && !(n <= bound)) return false;
      }
    }
    return true;
  };

  return {
    filters, privateFilter, cacheKey, accept, scope,
    // One extra row answers "is there another page"; a JS-side filter gets a wider window so the
    // page still fills after rows it has to drop. A per-user scope needs the widest window: rows it
    // may not see are dropped, and the reader must still get a full page.
    // Visibility is always applied AFTER the SQL read, so the window must be wide enough that a page
    // still fills once rows the reader may not see are dropped: 8x, capped, is a bounded read that
    // keeps a 15-document page a single D1 query.
    run: (db) => db.prepare(sql).bind(...sqlArgs,
      perUser || filters.some((f) => f.op === 'contains' || f.op === 'in')
        ? Math.min(400, limit * 8) + 1
        : limit + 1).all(),
  };
}


/*
 * Who may see a shared document.
 *
 * The feed used to be filtered by Firestore rules. Once the row lives in D1 nobody else filters it,
 * so the Worker has to apply the same rules itself - from the STORED document and the STORED user
 * profile, never from anything the request claims. Firebase still only proves *who* is asking.
 */
/**
 * Collections whose documents more than one person reads. Everything not listed here is private to
 * its owner: a `messages` row belongs to its chat, so it is read through the room, never by id.
 */
const SHARED_COLLECTIONS = new Set([
  'posts', 'reels', 'stories', 'users', 'groups', 'pages',
  'official_broadcasts', 'lives', 'pokes', 'events', 'messages', 'comments',
]);
const OWNER_FIELDS = ['authorId', 'uid', 'senderId', 'ownerId', 'userId', 'recipientId', 'fromUid'];
const MEMBER_FIELDS = ['participants', 'participantIds', 'members', 'memberIds', 'adminIds', 'moderatorIds'];

function docOwner(doc) {
  for (const field of OWNER_FIELDS) {
    const value = doc?.[field];
    if (typeof value === 'string' && value) return value;
  }
  return '';
}

function stringList(value) {
  return Array.isArray(value) ? value.map(String) : [];
}

/** Reads one stored document by path: D1 first, Firestore only when it predates the migration. */
async function readProfile(db, path, firestore, fetcher = fetch) {
  if (firestore?.cache?.has(path)) return firestore.cache.get(path);
  try {
    const row = await db.prepare(
      'SELECT document_json FROM friendbook_cf_documents WHERE path = ?'
    ).bind(path).first();
    if (row?.document_json) {
      const parsed = JSON.parse(row.document_json);
      firestore?.cache?.set(path, parsed);
      return parsed;
    }
  } catch { /* fall through to the pre-migration source */ }
  if (!firestore?.project || !firestore?.token) return null;
  try {
    const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(firestore.project)}`
      + `/databases/(default)/documents/${path.split('/').map(encodeURIComponent).join('/')}`;
    const r = await fetcher(url, { headers: { Authorization: `Bearer ${firestore.token}` }, signal: AbortSignal.timeout(6000) });
    if (!r.ok) return null;
    const parsed = fromFirestoreFields((await r.json()).fields || {});
    firestore?.cache?.set(path, parsed);
    return parsed;
  } catch {
    return null;
  }
}

/**
 * Returns { allowed, reason }. A document without an owner is treated as unreadable: the mirror only
 * ever holds documents written by a signed-in user, so a missing owner means a malformed row.
 */
export async function canReadDocument(db, doc, uid, options = {}) {
  const owner = docOwner(doc);
  if (!owner) return { allowed: false, reason: 'unowned' };
  if (owner === uid) return { allowed: true };
  if (!SHARED_COLLECTIONS.has(options.collection)) return { allowed: false, reason: 'not shared' };
  if (doc.archived) return { allowed: false };
  if (Number(doc.trashedAt) > 0) return { allowed: false };

  const me = await readProfile(db, `users/${uid}`, options.firestore, options.fetcher);
  const author = await readProfile(db, `users/${owner}`, options.firestore, options.fetcher);
  if (me?.banned || me?.deactivated) return { allowed: false, reason: 'account' };
  if (author?.banned || author?.deactivated) return { allowed: false, reason: 'author' };
  if (stringList(me?.blocked).includes(owner)) return { allowed: false, reason: 'blocked' };
  if (stringList(me?.blockedUsers).includes(owner)) return { allowed: false, reason: 'blocked' };
  if (stringList(author?.blocked).includes(uid) || stringList(author?.blockedUsers).includes(uid)) {
    return { allowed: false, reason: 'blocked by author' };
  }

  // Members-only containers: a chat, a group, a page.
  for (const field of MEMBER_FIELDS) {
    const list = stringList(doc?.[field]);
    if (list.length && !list.includes(uid)) return { allowed: false, reason: 'members' };
  }

  // A message belongs to its room, not to "everyone": the sender, the receiver, and the members of a
  // group room may read it - nobody else.
  if (options.collection === 'messages') {
    if (doc.senderId === uid || doc.receiverId === uid) return { allowed: true };
    const roomId = String(doc.chatId || '');
    if (!roomId) return { allowed: false, reason: 'room' };
    const room = await readProfile(db, `chats/${roomId}`, options.firestore, options.fetcher);
    const members = [...stringList(room?.participants), ...stringList(room?.members)];
    return members.includes(uid) ? { allowed: true } : { allowed: false, reason: 'room' };
  }

  const privacy = String(doc.privacy || 'Public').toLowerCase();
  if (privacy === 'public' || privacy === 'everyone') return { allowed: true };
  if (privacy === 'friends' || privacy === 'followers') {
    const friend = await hasD1Friendship(db, uid, owner);
    return friend === true ? { allowed: true } : { allowed: false, reason: 'friends' };
  }
  if (privacy === 'group') {
    if (!doc.groupId) return { allowed: false, reason: 'group' };
    const group = await readProfile(db, `groups/${doc.groupId}`, options.firestore, options.fetcher);
    if (!group) return { allowed: false, reason: 'group' };
    const members = [...stringList(group.members), ...stringList(group.memberIds), ...stringList(group.adminIds)];
    const isMember = members.includes(uid) || group.adminId === uid || group.ownerId === uid;
    const open = String(group.privacy || 'Public').toLowerCase() !== 'private';
    return open || isMember ? { allowed: true } : { allowed: false, reason: 'group' };
  }
  // "Only me", an unknown privacy string and any future private value stay private.
  return { allowed: false, reason: 'private' };
}

export async function appDocuments(request, env, identity) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = identity?.uid;
  if (!validSegment(uid)) return json({ error: 'Unauthorized' }, 401);
  const url = new URL(request.url);
  const path = url.pathname;

  if (path === '/v1/docs') {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    const collection = url.searchParams.get('collection') || '';
    if (!ALLOWED_COLLECTIONS.has(collection)) return json({ error: 'Unsupported collection' }, 400);
    const rawLimit = Number(url.searchParams.get('limit') || '20');
    const limit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(50, Math.trunc(rawLimit))) : 20;
    const plan = queryPlan(url, collection, limit);
    if (plan.error) return json({ error: plan.error }, 400);
    const now = Date.now();
    const lCache = getDbMap(listCache, env.DB);
    const cacheKey = plan.cacheKey;
    // Only the plain "newest N" page is cached: a filtered page is per-user and must never be
    // served from a shared key.
    if (!plan.privateFilter) {
      const cached = lCache.get(cacheKey);
      if (cached && cached.expiresAt > now) {
        return json({ collection, documents: cached.documents, cached: true });
      }
    }
    try {
      const rows = await plan.run(env.DB);
      const collected = [];
      const aclOptions = { collection, firestore: identity?.firestore, fetcher: identity?.fetcher || fetch };
      for (const row of rows?.results || []) {
        const doc = toPlainDocument(row.document_json, row.doc_id);
        if (!doc) continue;
        if (plan.scope.mode === 'owner') {
          if (docOwner(doc) !== uid) continue;   // own history only, from the stored owner
        } else {
          const verdict = await canReadDocument(env.DB, doc, uid, aclOptions);
          if (!verdict.allowed) continue;
          if (plan.scope.mode === 'feed' && plan.scope.publicOnly) {
            if (docOwner(doc) !== uid && String(doc.privacy || 'Public').toLowerCase() !== 'public') continue;
          }
          if (plan.scope.mode === 'feed' && plan.scope.friendsOnly) {
            if (docOwner(doc) !== uid && String(doc.privacy || '').toLowerCase() !== 'friends') continue;
          }
        }
        if (!plan.accept(doc)) continue;
        collected.push(doc);
      }
      // One extra row is always fetched, so "is there another page" is a fact, not a guess.
      const hasMore = collected.length > limit;
      const documents = hasMore ? collected.slice(0, limit) : collected;
      const body = {
        collection,
        documents,
        cursor: documents.length ? String(documents[documents.length - 1].id) : null,
        hasMore,
        filtered: plan.privateFilter || plan.filters.length > 0,
        scope: plan.scope.mode,
      };
      if (!body.filtered) lCache.set(cacheKey, { documents, expiresAt: now + LIST_CACHE_TTL_MS });
      return json(body);
    } catch {
      return json({ error: 'Document listing unavailable' }, 503);
    }
  }

  const match = /^\/v1\/docs\/([^/]+)\/([^/]+)$/.exec(path);
  if (!match) return json({ error: 'Not found' }, 404);
  let collection, docId;
  try {
    collection = decodeURIComponent(match[1]);
    docId = decodeURIComponent(match[2]);
  } catch {
    return json({ error: 'Invalid path' }, 400);
  }
  if (!ALLOWED_COLLECTIONS.has(collection) || !validSegment(docId)) {
    return json({ error: 'Invalid collection or document ID' }, 400);
  }
  const docPath = `${collection}/${docId}`;

  if (request.method === 'GET') {
    try {
      const row = await env.DB.prepare(
        'SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents WHERE path = ?'
      ).bind(docPath).first();
      if (!row) return json({ error: 'Not found' }, 404);
      const doc = toPlainDocument(row.document_json, docId);
      // A single document is not more public than a page of them: same rules, same source of truth.
      const verdict = await canReadDocument(env.DB, doc, uid, {
        collection, firestore: identity?.firestore, fetcher: identity?.fetcher || fetch,
      });
      if (!verdict.allowed) return json({ error: 'Not found' }, 404);
      return json({ id: docId, document: doc });
    } catch {
      return json({ error: 'Document read unavailable' }, 503);
    }
  }

  if (request.method === 'PUT') {
    let incoming;
    try { incoming = await request.json(); }
    catch { return json({ error: 'Invalid JSON payload' }, 400); }
    if (!incoming || typeof incoming !== 'object' || Array.isArray(incoming)) {
      return json({ error: 'Document object required' }, 400);
    }
    const now = Date.now();
    try {
      const existingRow = await env.DB.prepare(
        'SELECT owner_uid, document_json FROM friendbook_cf_documents WHERE path = ?'
      ).bind(docPath).first();
      const existingPlain = existingRow ? (toPlainDocument(existingRow.document_json, docId) || {}) : {};
      const existingOwner = String(existingRow?.owner_uid || '');
      const claim = String(incoming.authorId || incoming.uid || incoming.senderId || incoming.ownerId || '');
      let payload = incoming;
      if (existingOwner && existingOwner !== uid) {
        // Somebody else's document: only the shared counters may be written, never the content.
        // A merge-write may carry a map field whose members are its own (`reactionsMap: { uid: emoji }`),
        // so a nested object key is judged by its name, not rejected for being an object.
        const keys = Object.keys(incoming).filter((k) => k !== 'id');
        // A friend counter is the one shared number another person may move (accepting a request),
        // so its delta is allowed through the same door as likes and comments.
        const allowed = (k) => COUNTER_FIELDS.has(k)
          || (k.endsWith('Delta') && personalCounter(deltaField(k)))
          || (arrayOp(k) !== null && SHARED_ARRAYS.has(arrayOp(k).base));
        if (keys.some((k) => !allowed(k))) {
          return json({ error: 'Not your document' }, 403);
        }
        payload = Object.fromEntries(keys.map((k) => [k, incoming[k]]));
      } else if (!existingOwner && claim && claim !== uid) {
        // A brand new document owned by somebody else would let a caller write as that person.
        return json({ error: 'Not your document' }, 403);
      }
      // A counter delta is not a stored value: it is applied to the stored number, by the database,
      // so two devices commenting at the same moment cannot overwrite each other's increment.
      const cleanPayload = { ...payload };
      for (const key of Object.keys(payload)) {
        const base = deltaField(key);
        if (!base) continue;
        const delta = Math.max(-1_000, Math.min(1_000, Math.trunc(Number(payload[key]) || 0)));
        delete cleanPayload[key];
        cleanPayload[base] = Math.max(0, Number(existingPlain[base] || 0) + delta);
      }
      // A list membership change is expressed as `fieldAdd`/`fieldRemove` and applied to the stored
      // list here, so two people liking the same post cannot overwrite each other's id.
      for (const key of Object.keys(cleanPayload)) {
        const op = arrayOp(key);
        if (!op) continue;
        delete cleanPayload[key];
        const current = Array.isArray(existingPlain[op.base]) ? existingPlain[op.base].slice() : [];
        const wanted = asList(payload[key]);
        let next;
        if (op.add) {
          next = current.slice();
          for (const item of wanted) if (!next.some((v) => JSON.stringify(v) === JSON.stringify(item))) next.push(item);
        } else {
          next = current.filter((v) => !wanted.some((item) => JSON.stringify(v) === JSON.stringify(item)));
        }
        cleanPayload[op.base] = next;
      }
      // A map field is merged key by key, the way a Firestore merge-write behaves: `reactionsMap.<uid>`
      // adds one member without dropping the others, and a null member deletes just that key.
      const merged = deepMerge({ ...existingPlain }, cleanPayload);
      merged.id = docId;
      // A profile row is owned by its own id: without this a `users/<uid>` document written without a
      // `uid` field would read back as "unowned" and its own owner would get a 404.
      if (collection === 'users' && !docOwner(merged)) merged.uid = docId;
      if (!merged.createdAt) merged.createdAt = now;
      merged.updatedAt = now;
      const ownerUid = String(merged.authorId || merged.uid || merged.senderId || existingOwner || uid);
      const serialized = JSON.stringify(merged);
      if (serialized.length > 95_000) return json({ error: 'Document too large' }, 413);
      await env.DB.prepare(
        `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(path) DO UPDATE SET
           owner_uid = excluded.owner_uid,
           document_json = excluded.document_json,
           updated_at = excluded.updated_at`
      ).bind(docPath, collection, docId, ownerUid, serialized, now).run();
      getDbMap(docCache, env.DB).delete(docPath);
      getDbMap(listCache, env.DB).clear();

      if (collection === 'posts' || collection === 'reels') {
        await env.DB.prepare(
          "INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state = 'verified'"
        ).bind(docPath).run();
      }
      if (collection === 'users' && docId === uid) {
        const displayName = typeof merged.displayName === 'string' ? merged.displayName.slice(0, 200) : '';
        const avatarUrl = typeof merged.photoUrl === 'string' ? merged.photoUrl.slice(0, 2048) : null;
        await env.DB.prepare(
          'INSERT INTO friendbook_cf_profiles(uid, display_name, cloudinary_avatar_url) VALUES (?, ?, ?) '
          + 'ON CONFLICT(uid) DO UPDATE SET display_name = excluded.display_name, cloudinary_avatar_url = excluded.cloudinary_avatar_url'
        ).bind(uid, displayName, avatarUrl).run();
      }
      return json({ id: docId, path: docPath, document: merged });
    } catch {
      return json({ error: 'Document write unavailable' }, 503);
    }
  }

  return json({ error: 'Method not allowed' }, 405);
}

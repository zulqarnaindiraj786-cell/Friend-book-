// Cloudflare D1 App Document Store (/v1/docs) - the app's database.
//
// Firebase is authentication only: it proves WHO is calling, and this module decides WHAT they may
// touch. Every document lives in `friendbook_cf_documents`, and the HTTP surface is deliberately
// Firestore-shaped so the Android client asks the same questions it used to ask Firestore:
//
//   GET    /v1/docs?collection=posts&eq[authorId]=uid&order=createdAt&dir=desc&limit=20&after=<cur>
//   GET    /v1/docs?collection=comments&eq[postId]=x&count=1
//   GET    /v1/docs?collection=messages&eq[chatId]=c&since=<ms>     (what changed since I looked)
//   GET    /v1/docs/<collection>/<id>
//   PUT    /v1/docs/<collection>/<id>          merge-upsert a document
//   PATCH  /v1/docs/<collection>/<id>          atomic field ops (set/inc/add/remove/unset/stamp)
//   DELETE /v1/docs/<collection>/<id>          tombstone (the row stays, the content is gone)
//   POST   /v1/docs/batch                      up to 20 operations in one D1 batch
//
// Access rules live in `doc-access.js` and are the intent of `firebase/firestore.rules`, tightened
// where the old file was world-readable only because the client always scoped its own queries: a
// list on notifications, chats, messages, friend requests or payments is scoped by the Worker, so
// one signed-in user can never page through somebody else's inbox or thread.
//
// Nothing is ever destroyed. DELETE writes a tombstone and every read filters it out, so history and
// ids survive exactly as the owner's data rules require.
import {
  canRead, canWrite, isPublicRead, isAdminOnlyRead, isChatScoped, queryable, OWNER_EMAIL,
} from './doc-access.js';
import {
  QueryError, buildListQuery, applyOps, documentFromOps, parseBatch, encodeCursor,
  MAX_DOC_BYTES,
} from './doc-queries.js';

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

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

// Short TTLs on purpose: a chat or a feed must never show a five minute old truth, and a burst of
// reads inside one screen still costs one D1 statement instead of ten.
const DOC_CACHE_TTL_MS = 5_000;
const LIST_CACHE_TTL_MS = 5_000;
const ADMIN_CACHE_TTL_MS = 60_000;
const docCache = new WeakMap();
const listCache = new WeakMap();
const adminCache = new WeakMap();

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

/** The stored row as a plain document, plus the Worker-stamped owner (never a client claim). */
async function readStored(db, path, docId) {
  const now = Date.now();
  const cache = getDbMap(docCache, db);
  const key = `stored:${path}`;
  const hit = cache.get(key);
  if (hit && hit.expiresAt > now) return hit.row;
  let row = null;
  try {
    const found = await db.prepare(
      'SELECT document_json, owner_uid, updated_at FROM friendbook_cf_documents WHERE path = ?'
    ).bind(path).first();
    if (found) {
      const doc = toPlainDocument(found.document_json, docId);
      if (doc) {
        row = { doc, ownerUid: String(found.owner_uid || ''), updatedAt: Number(found.updated_at) || 0 };
      }
    }
  } catch {
    row = null;
  }
  cache.set(key, { row, expiresAt: now + DOC_CACHE_TTL_MS });
  return row;
}

function invalidate(db, path) {
  const cache = getDbMap(docCache, db);
  cache.delete(path);
  cache.delete(`stored:${path}`);
  getDbMap(listCache, db).clear();
}

export async function hasD1Friendship(db, uidA, uidB) {
  if (!db) return null;
  try {
    const p1 = `friends/${uidA}_${uidB}`, p2 = `friends/${uidB}_${uidA}`;
    const byKey = await db.prepare(
      'SELECT path FROM friendbook_cf_documents WHERE path IN (?, ?) LIMIT 1'
    ).bind(p1, p2).first();
    if (byKey) return true;
    // Documents that arrived with a generated id still carry both participants.
    const row = await db.prepare(
      `SELECT path FROM friendbook_cf_documents
       WHERE collection = 'friends'
         AND EXISTS (SELECT 1 FROM json_each(json_extract(document_json, '$.participants')) WHERE value = ?)
         AND EXISTS (SELECT 1 FROM json_each(json_extract(document_json, '$.participants')) WHERE value = ?)
       LIMIT 1`
    ).bind(uidA, uidB).first();
    return row ? true : null;
  } catch {
    return null;
  }
}

/** Admin = the owner's email, an explicitly listed uid, or a stored user document that says isAdmin. */
export async function resolveAdmin(db, uid, email, env) {
  if (typeof email === 'string' && email.toLowerCase() === OWNER_EMAIL) return true;
  const listed = String(env?.ADMIN_UIDS || '').split(',').map(v => v.trim()).filter(Boolean);
  if (listed.includes(uid)) return true;
  const now = Date.now();
  const cache = getDbMap(adminCache, db);
  const hit = cache.get(uid);
  if (hit && hit.expiresAt > now) return hit.value;
  let value = false;
  try {
    const row = await db.prepare(
      "SELECT COALESCE(json_extract(document_json, '$.isAdmin'), 0) AS admin FROM friendbook_cf_documents WHERE path = ?"
    ).bind(`users/${uid}`).first();
    value = row?.admin === 1 || row?.admin === true;
  } catch {
    value = false;
  }
  cache.set(uid, { value, expiresAt: now + ADMIN_CACHE_TTL_MS });
  return value;
}

async function readBody(request, maxBytes) {
  const length = Number(request.headers.get('Content-Length') || '0');
  if (!Number.isFinite(length) || length < 0 || length > maxBytes) return null;
  try {
    const text = await request.text();
    if (text.length > maxBytes) return null;
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function ownerUidOf(merged, existingOwner, uid) {
  return String(merged.authorId || merged.uid || merged.senderId || merged.userId || merged.toUid
    || existingOwner || uid);
}

/** Is this caller a participant of `chats/<chatId>`? The chat row decides, never the request. */
async function isChatParticipant(db, chatId, uid, admin) {
  if (admin) return true;
  if (!validSegment(chatId)) return false;
  const chat = await readStored(db, `chats/${chatId}`, chatId);
  if (!chat || chat.doc.deleted) return false;
  const people = [...(Array.isArray(chat.doc.participants) ? chat.doc.participants : []),
    ...(Array.isArray(chat.doc.members) ? chat.doc.members : [])].map(String);
  return people.includes(uid) || chat.ownerUid === uid;
}

/** The public shape of a document: no Worker internals, no tombstoned content. */
function expose(row) {
  return { document: row.doc, updatedAt: row.updatedAt };
}

export async function appDocuments(request, env, identity) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = identity?.uid;
  if (!validSegment(uid)) return json({ error: 'Unauthorized' }, 401);
  const db = env.DB;
  const url = new URL(request.url);
  const path = url.pathname;

  // Resolved at most once per request, and only when a decision actually needs it.
  let adminState;
  const isAdmin = async () => {
    if (adminState === undefined) adminState = await resolveAdmin(db, uid, identity?.email, env);
    return adminState;
  };

  // ------------------------------------------------------------- POST /v1/docs/batch
  if (path === '/v1/docs/batch') {
    if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);
    const body = await readBody(request, 400_000);
    let ops;
    try { ops = parseBatch(body); }
    catch (error) { return json({ error: error.message }, error instanceof QueryError ? error.status : 400); }
    const admin = await isAdmin();
    const now = Date.now();
    const statements = [];
    const touched = [];
    try {
      for (const op of ops) {
        if (!queryable(op.collection) || !validSegment(op.id)) {
          return json({ error: 'Invalid collection or document ID' }, 400);
        }
        const docPath = `${op.collection}/${op.id}`;
        const stored = await readStored(db, docPath, op.id);
        if (stored && stored.doc.deleted) return json({ error: 'Document is deleted' }, 404);
        const fields = op.fields && typeof op.fields === 'object' && !Array.isArray(op.fields) ? op.fields : {};
        const chatOk = !isChatScoped(op.collection) || await isChatParticipant(
          db, String(fields.chatId || stored?.doc?.chatId || ''), uid, admin);
        const allowed = chatOk && (op.op === 'delete'
          ? canWrite(op.collection, stored?.doc || null, {}, uid, admin, 'delete')
          : canWrite(op.collection, stored?.doc || null, fields, uid, admin, stored ? 'update' : 'create'));
        if (!allowed) return json({ error: 'Not allowed' }, 403);
        if (op.op === 'delete') {
          statements.push(db.prepare(
            `UPDATE friendbook_cf_documents
             SET document_json = json_set(json_set(document_json, '$.deleted', json('true')), '$.deletedAt', ?),
                 updated_at = ?
             WHERE path = ? AND COALESCE(json_extract(document_json, '$.deleted'), 0) = 0`
          ).bind(now, now, docPath));
        } else {
          const merged = op.op === 'set'
            ? { ...fields, id: op.id }
            : { ...(stored?.doc || {}), ...fields, id: op.id };
          if (!merged.createdAt) merged.createdAt = stored?.doc?.createdAt || now;
          merged.updatedAt = now;
          const serialized = JSON.stringify(merged);
          if (serialized.length > MAX_DOC_BYTES) return json({ error: 'Document too large' }, 413);
          statements.push(db.prepare(
            `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(path) DO UPDATE SET
               owner_uid = excluded.owner_uid,
               document_json = excluded.document_json,
               updated_at = excluded.updated_at`
          ).bind(docPath, op.collection, op.id, ownerUidOf(merged, stored?.ownerUid, uid), serialized, now));
        }
        touched.push(docPath);
      }
      await db.batch(statements);
    } catch {
      return json({ error: 'Batch write unavailable' }, 503);
    }
    for (const docPath of touched) invalidate(db, docPath);
    return json({ ok: true, applied: touched.length });
  }

  // ------------------------------------------------------------- GET /v1/docs (list, count, watch)
  if (path === '/v1/docs') {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    const collection = url.searchParams.get('collection') || '';
    if (!queryable(collection)) return json({ error: 'Unsupported collection' }, 400);
    const wantsCount = ['1', 'true', 'yes'].includes(String(url.searchParams.get('count') || '').toLowerCase());
    const admin = await isAdmin();
    if (isChatScoped(collection)) {
      // Messages are only ever listed for one chat, and only for somebody inside that chat.
      const chatId = url.searchParams.get('eq[chatId]');
      if (!validSegment(chatId)) return json({ error: 'chatId filter required' }, 400);
      if (!(await isChatParticipant(db, chatId, uid, admin))) return json({ error: 'Not allowed' }, 403);
    }
    let built;
    try {
      built = buildListQuery(collection, url.searchParams, uid, admin, { count: wantsCount });
    } catch (error) {
      return json({ error: error.message }, error instanceof QueryError ? error.status : 400);
    }

    // Only unfiltered public lists are cacheable: a scoped list belongs to exactly one caller.
    const filtered = [...url.searchParams.keys()].some(key => key.startsWith('eq[') || key.startsWith('has[') || key === 'since');
    const cacheable = isPublicRead(collection) && !filtered;
    const cacheKey = `${collection}?${[...url.searchParams.entries()].map(([k, v]) => `${k}=${v}`).sort().join('&')}`;
    const lCache = getDbMap(listCache, db);
    const now = Date.now();
    if (cacheable) {
      const cached = lCache.get(cacheKey);
      if (cached && cached.expiresAt > now) return json({ ...cached.body, cached: true });
    }

    try {
      const rows = await db.prepare(built.sql).bind(...built.binds).all();
      if (wantsCount) return json({ collection, count: Number(rows?.results?.[0]?.total) || 0 });
      const results = rows?.results || [];
      const documents = [];
      for (const row of results.slice(0, built.limit)) {
        const doc = toPlainDocument(row.document_json, row.doc_id);
        if (doc) documents.push(doc);
      }
      let next = null;
      if (results.length > built.limit) {
        const last = results[built.limit - 1];
        if (last) next = encodeCursor(last.order_value ?? last.updated_at, last.path);
      }
      const body = { collection, documents, next };
      if (cacheable) lCache.set(cacheKey, { body, expiresAt: now + LIST_CACHE_TTL_MS });
      return json(body);
    } catch {
      return json({ error: 'Document listing unavailable' }, 503);
    }
  }

  // ------------------------------------------------------------- /v1/docs/<collection>/<id>
  const match = /^\/v1\/docs\/([^/]+)\/([^/]+)$/.exec(path);
  if (!match) return json({ error: 'Not found' }, 404);
  let collection, docId;
  try {
    collection = decodeURIComponent(match[1]);
    docId = decodeURIComponent(match[2]);
  } catch {
    return json({ error: 'Invalid path' }, 400);
  }
  if (!queryable(collection) || !validSegment(docId)) {
    return json({ error: 'Invalid collection or document ID' }, 400);
  }
  const docPath = `${collection}/${docId}`;

  if (request.method === 'GET') {
    const admin = await isAdmin();
    try {
      const stored = await readStored(db, docPath, docId);
      if (!stored || stored.doc.deleted) return json({ error: 'Not found' }, 404);
      const allowed = isChatScoped(collection)
        ? await isChatParticipant(db, String(stored.doc.chatId || ''), uid, admin)
        : canRead(collection, stored.doc, uid, admin);
      if (!allowed) return json({ error: 'Not allowed' }, 403);
      return json({ id: docId, ...expose(stored) });
    } catch {
      return json({ error: 'Document read unavailable' }, 503);
    }
  }

  // Atomic field operations: counters and arrays that survive concurrent writers.
  if (request.method === 'PATCH') {
    const body = await readBody(request, 128_000);
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
      return json({ error: 'Operations object required' }, 400);
    }
    const admin = await isAdmin();
    const now = Date.now();
    try {
      // Compare-and-swap: read the stored bytes, apply the operations, and write only if nobody else
      // wrote in between. A loser re-reads and re-applies, so two likes can never cancel each other.
      for (let attempt = 0; attempt < 4; attempt += 1) {
        const row = await db.prepare(
          'SELECT document_json, owner_uid FROM friendbook_cf_documents WHERE path = ?'
        ).bind(docPath).first();

        if (!row) {
          const fresh = documentFromOps(body, docId, now);
          if (!canWrite(collection, null, fresh, uid, admin, 'create')) return json({ error: 'Not allowed' }, 403);
          const serialized = JSON.stringify(fresh);
          if (serialized.length > MAX_DOC_BYTES) return json({ error: 'Document too large' }, 413);
          const inserted = await db.prepare(
            `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
             VALUES (?, ?, ?, ?, ?, ?)
             ON CONFLICT(path) DO NOTHING`
          ).bind(docPath, collection, docId, ownerUidOf(fresh, null, uid), serialized, now).run();
          const created = Number(inserted?.meta?.changes ?? inserted?.changes ?? 1);
          if (created > 0) {
            invalidate(db, docPath);
            return json({ id: docId, path: docPath, document: fresh });
          }
          continue;   // somebody else created it a moment ago: re-read and patch that document
        }

        const existing = toPlainDocument(row.document_json, docId);
        if (!existing) return json({ error: 'Document unreadable' }, 500);
        if (existing.deleted) return json({ error: 'Document is deleted' }, 404);
        const preview = { ...existing, ...(body.set && typeof body.set === 'object' && !Array.isArray(body.set) ? body.set : {}) };
        const chatOk = !isChatScoped(collection)
          || await isChatParticipant(db, String(preview.chatId || existing.chatId || ''), uid, admin);
        if (!chatOk || !canWrite(collection, existing, preview, uid, admin, 'update')) {
          return json({ error: 'Not allowed' }, 403);
        }

        let next;
        try { next = applyOps(existing, body, now); }
        catch (error) { return json({ error: error.message }, error instanceof QueryError ? error.status : 400); }
        const serialized = JSON.stringify(next);
        if (serialized.length > MAX_DOC_BYTES) return json({ error: 'Document too large' }, 413);

        const written = await db.prepare(
          `UPDATE friendbook_cf_documents
           SET document_json = ?, updated_at = ?, owner_uid = ?
           WHERE path = ? AND document_json = ?`
        ).bind(serialized, now, ownerUidOf(next, row.owner_uid, uid), docPath, row.document_json).run();
        const changed = Number(written?.meta?.changes ?? written?.changes ?? 0);
        if (changed > 0) {
          invalidate(db, docPath);
          return json({ id: docId, path: docPath, document: next });
        }
      }
      return json({ error: 'Concurrent write, please retry' }, 409);
    } catch {
      return json({ error: 'Document update unavailable' }, 503);
    }
  }

  // Tombstone: the row stays (history is never destroyed) and every read filters it out.
  if (request.method === 'DELETE') {
    const admin = await isAdmin();
    try {
      const stored = await readStored(db, docPath, docId);
      if (!stored || stored.doc.deleted) return json({ error: 'Not found' }, 404);
      const chatOk = !isChatScoped(collection)
        || await isChatParticipant(db, String(stored.doc.chatId || ''), uid, admin);
      if (!chatOk || !canWrite(collection, stored.doc, {}, uid, admin, 'delete')) {
        return json({ error: 'Not allowed' }, 403);
      }
      const now = Date.now();
      await db.prepare(
        `UPDATE friendbook_cf_documents
         SET document_json = json_set(json_set(document_json, '$.deleted', json('true')), '$.deletedAt', ?),
             updated_at = ?
         WHERE path = ? AND COALESCE(json_extract(document_json, '$.deleted'), 0) = 0`
      ).bind(now, now, docPath).run();
      invalidate(db, docPath);
      return json({ id: docId, path: docPath, deleted: true, kept: true });
    } catch {
      return json({ error: 'Document delete unavailable' }, 503);
    }
  }

  if (request.method === 'PUT') {
    const incoming = await readBody(request, 128_000);
    if (!incoming || typeof incoming !== 'object' || Array.isArray(incoming)) {
      return json({ error: 'Document object required' }, 400);
    }
    const admin = await isAdmin();
    const now = Date.now();
    try {
      const stored = await readStored(db, docPath, docId);
      if (stored && stored.doc.deleted) return json({ error: 'Document is deleted' }, 404);
      const chatOk = !isChatScoped(collection)
        || await isChatParticipant(db, String(incoming.chatId || stored?.doc?.chatId || ''), uid, admin);
      if (!chatOk || !canWrite(collection, stored?.doc || null, incoming, uid, admin, stored ? 'update' : 'create')) {
        return json({ error: 'Not allowed' }, 403);
      }
      const merged = { ...(stored?.doc || {}), ...incoming, id: docId };
      if (!merged.createdAt) merged.createdAt = now;
      merged.updatedAt = now;
      const ownerUid = ownerUidOf(merged, stored?.ownerUid, uid);
      const serialized = JSON.stringify(merged);
      if (serialized.length > MAX_DOC_BYTES) return json({ error: 'Document too large' }, 413);
      await db.prepare(
        `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(path) DO UPDATE SET
           owner_uid = excluded.owner_uid,
           document_json = excluded.document_json,
           updated_at = excluded.updated_at`
      ).bind(docPath, collection, docId, ownerUid, serialized, now).run();
      invalidate(db, docPath);

      if (collection === 'posts' || collection === 'reels') {
        await db.prepare(
          "INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state = 'verified'"
        ).bind(docPath).run();
      }
      if (collection === 'users' && docId === uid) {
        const displayName = typeof merged.displayName === 'string' ? merged.displayName.slice(0, 200) : '';
        const avatarUrl = typeof merged.photoUrl === 'string' ? merged.photoUrl.slice(0, 2048) : null;
        await db.prepare(
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

export { isAdminOnlyRead };

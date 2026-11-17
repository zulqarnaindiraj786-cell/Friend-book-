// Cloudflare D1 App Document Store (/v1/docs) + D1-first ACL helpers.
import { authorizePost } from './firebase-post-access.js';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const ALLOWED_COLLECTIONS = new Set([
  'users', 'posts', 'reels', 'stories', 'chats', 'messages',
  'friends', 'friend_requests', 'follows', 'groups', 'pages',
  'notifications', 'official_broadcasts', 'lives', 'pokes',
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

const LIST_CACHE_TTL_MS = 60_000;
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
  // Authorization and privacy fields must reflect the current D1 row, not a
  // stale per-isolate cache after another device changes a block or ban.
  try {
    const row = await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path = ?').bind(path).first();
    return row?.document_json ? toFirestoreFields(row.document_json) : null;
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
    return byKey ? true : null;
  } catch {
    return null;
  }
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
    const now = Date.now();
    const lCache = getDbMap(listCache, env.DB);
    const cacheKey = `${uid}:${collection}:${limit}`;
    const cached = lCache.get(cacheKey);
    if (cached && cached.expiresAt > now) {
      return json({ collection, documents: cached.documents, cached: true });
    }
    try {
      const rows = await env.DB.prepare(
        'SELECT doc_id, document_json, updated_at FROM friendbook_cf_documents WHERE collection = ? ORDER BY updated_at DESC LIMIT ?'
      ).bind(collection, limit).all();
      let documents = [];
      for (const row of rows?.results || []) {
        const doc = toPlainDocument(row.document_json, row.doc_id);
        if (doc) documents.push(doc);
      }
      if (collection === 'posts' || collection === 'reels') {
        const allowed = await Promise.all(documents.map(doc => authorizePost({ uid, db: env.DB }, collection, doc.id)));
        documents = documents.filter((_doc, index) => allowed[index]);
      }
      lCache.set(cacheKey, { documents, expiresAt: now + LIST_CACHE_TTL_MS });
      return json({ collection, documents });
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
      return json({ id: docId, document: toPlainDocument(row.document_json, docId) });
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
      const merged = { ...existingPlain, ...incoming, id: docId };
      if (!merged.createdAt) merged.createdAt = now;
      merged.updatedAt = now;
      const ownerUid = String(merged.authorId || merged.uid || merged.senderId || existingRow?.owner_uid || uid);
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

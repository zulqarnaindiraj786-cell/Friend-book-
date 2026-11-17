// Cloudflare D1 App Document Store (/v1/docs) + D1-first ACL helpers.
import { buildQuery, buildCountQuery, parseQueryParams, encodeCursor } from './doc-query.js';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const ALLOWED_COLLECTIONS = new Set([
  'users', 'posts', 'reels', 'stories', 'chats', 'messages',
  'friends', 'friend_requests', 'follows', 'groups', 'pages',
  'notifications', 'official_broadcasts', 'lives', 'pokes',
  // Live broadcast chat packets. Firestore kept these in a subcollection; D1 is flat, so they
  // are keyed by liveId and queried with whereEqualTo("liveId", ...).
  'live_chat', 'comments', 'subscriptions',
  // Written from the app but missing here: a report, a payment request and a live gift all go
  // through the same document write, and 'Unsupported collection' is a 400 the caller cannot
  // retry. Reporting therefore failed silently, and a paid request never reached the admin.
  'reports', 'payments', 'gifts',
]);

// Field names come from the client and are interpolated into a JSON path, so they are
// restricted to plain identifiers. A dotted or crafted name would address elsewhere in the
// document, or break out of the '$.' prefix entirely.
const FIELD_NAME = /^[A-Za-z_][A-Za-z0-9_]{0,63}$/;

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

// Used after a bulk rebuild so this isolate does not keep serving its cached reads.
export function invalidateDocumentCaches(db) {
  docCache.delete(db);
  listCache.delete(db);
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

    let query;
    try {
      query = parseQueryParams(url);
    } catch (error) {
      return json({ error: error.status === 400 ? error.message : 'Invalid query' }, 400);
    }

    // An unfiltered, cursor-less list is cacheable; anything with a filter or cursor is not,
    // because the answer depends on the caller's parameters.
    const cacheable = query.wheres.length === 0 && !query.after && !query.orderBy;
    const lCache = getDbMap(listCache, env.DB);
    const cacheKey = `${collection}:${query.limit}`;
    if (cacheable) {
      const cached = lCache.get(cacheKey);
      if (cached && cached.expiresAt > Date.now()) {
        return json({ collection, documents: cached.documents, cached: true });
      }
    }

    let built;
    try {
      built = buildQuery(query);
    } catch (error) {
      return json({ error: error.status === 400 ? error.message : 'Invalid query' }, 400);
    }

    try {
      const rows = await env.DB.prepare(built.sql).bind(...built.params).all();
      const results = rows?.results || [];
      const hasMore = results.length > built.limit;
      const pageRows = hasMore ? results.slice(0, built.limit) : results;
      const documents = [];
      for (const row of pageRows) {
        const doc = toPlainDocument(row.document_json, row.doc_id);
        if (doc) documents.push(doc);
      }
      let nextCursor = null;
      if (hasMore && pageRows.length > 0) {
        const last = pageRows[pageRows.length - 1];
        const lastDoc = toPlainDocument(last.document_json, last.doc_id) || {};
        nextCursor = encodeCursor(lastDoc[built.sortField], last.doc_id);
      }
      if (cacheable) lCache.set(cacheKey, { documents, expiresAt: Date.now() + LIST_CACHE_TTL_MS });
      return json({ collection, documents, hasMore, nextCursor });
    } catch {
      return json({ error: 'Document listing unavailable' }, 503);
    }
  }

  // Count without listing. The app used to answer "how many referrals / subscribers /
  // messages" by fetching a page and taking its length, which reads everything to answer a
  // one-number question and reports the page ceiling once the real number is larger.
  if (path === '/v1/docs/count') {
    let query;
    try { query = parseQueryParams(request.url); }
    catch { return json({ error: 'Invalid query' }, 400); }
    if (!ALLOWED_COLLECTIONS.has(query.collection)) {
      return json({ error: 'Unsupported collection' }, 400);
    }
    try {
      const built = buildCountQuery(query);
      const row = await env.DB.prepare(built.sql).bind(...built.params).first();
      return json({ collection: query.collection, total: Number(row?.total || 0) });
    } catch {
      return json({ error: 'Count unavailable' }, 503);
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

  if (request.method === 'DELETE') {
    // Deletion is owner-only. A document created by another user must never be
    // removable through this endpoint, otherwise any authenticated caller could
    // wipe another member's post by guessing its id.
    try {
      const row = await env.DB.prepare(
        'SELECT owner_uid FROM friendbook_cf_documents WHERE path = ?'
      ).bind(docPath).first();
      if (!row) {
        // Idempotent delete: absent is the desired end state, so this is a success.
        return json({ id: docId, deleted: false, alreadyAbsent: true });
      }
      if (row.owner_uid && row.owner_uid !== uid) {
        return json({ error: 'Forbidden' }, 403);
      }
      await env.DB.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(docPath).run();
      getDbMap(docCache, env.DB).delete(docPath);
      getDbMap(listCache, env.DB).clear();
      return json({ id: docId, deleted: true });
    } catch {
      return json({ error: 'Document delete unavailable' }, 503);
    }
  }

  if (request.method === 'PATCH') {
    // Atomic field operations. Firestore's increment/arrayUnion/arrayRemove cannot be
    // expressed as a plain document PUT: sending the sentinel object would store it
    // literally, and read-modify-write in the app would lose concurrent updates. So the
    // read, the arithmetic and the write all happen inside one D1 statement pair, and two
    // callers incrementing the same counter both count.
    let patch;
    try { patch = await request.json(); }
    catch { return json({ error: 'Invalid JSON payload' }, 400); }
    const ops = patch && Array.isArray(patch.ops) ? patch.ops : null;
    if (!ops || ops.length === 0 || ops.length > 32) {
      return json({ error: 'ops array required' }, 400);
    }
    const now = Date.now();
    try {
      for (const op of ops) {
        if (!op || typeof op.field !== 'string' || !FIELD_NAME.test(op.field)) {
          return json({ error: 'Invalid field' }, 400);
        }
        const field = op.field;
        if (op.increment !== undefined) {
          const amount = Number(op.increment);
          if (!Number.isFinite(amount)) return json({ error: 'Invalid increment' }, 400);
          // Read-modify-write inside one statement is not possible for a JSON blob, so the
          // arithmetic uses SQLite's own JSON functions on the stored value.
          await env.DB.prepare(
            `UPDATE friendbook_cf_documents
                SET document_json = json_set(
                      document_json,
                      '$.' || ?,
                      COALESCE(json_extract(document_json, '$.' || ?), 0) + ?),
                    updated_at = ?
              WHERE path = ?`
          ).bind(field, field, amount, now, docPath).run();
        } else if (op.arrayUnion !== undefined || op.arrayRemove !== undefined) {
          const adding = op.arrayUnion !== undefined;
          const values = adding ? op.arrayUnion : op.arrayRemove;
          if (!Array.isArray(values)) return json({ error: 'Invalid array operation' }, 400);
          const row = await env.DB.prepare(
            'SELECT document_json FROM friendbook_cf_documents WHERE path = ?'
          ).bind(docPath).first();
          if (!row) return json({ error: 'Not found' }, 404);
          const current = toPlainDocument(row.document_json, docId) || {};
          const existing = Array.isArray(current[field]) ? current[field] : [];
          const wanted = values.map(String);
          const next = adding
            ? [...existing, ...wanted.filter(v => !existing.includes(v))]
            : existing.filter(v => !wanted.includes(v));
          await env.DB.prepare(
            'UPDATE friendbook_cf_documents SET document_json = json_set(document_json, ?, json(?)), updated_at = ? WHERE path = ?'
          ).bind(`$.${field}`, JSON.stringify(next), now, docPath).run();
        } else {
          await env.DB.prepare(
            'UPDATE friendbook_cf_documents SET document_json = json_set(document_json, ?, json(?)), updated_at = ? WHERE path = ?'
          ).bind(`$.${field}`, JSON.stringify(op.value === undefined ? null : op.value), now, docPath).run();
        }
        const changed = await env.DB.prepare('SELECT changes() AS n').first();
        if (!changed || changed.n === 0) {
          // A counter for a document that does not exist yet must not silently vanish.
          return json({ error: 'Not found' }, 404);
        }
      }
      getDbMap(docCache, env.DB).delete(docPath);
      getDbMap(listCache, env.DB).clear();
      const fresh = await env.DB.prepare(
        'SELECT document_json FROM friendbook_cf_documents WHERE path = ?'
      ).bind(docPath).first();
      return json({ id: docId, document: fresh ? toPlainDocument(fresh.document_json, docId) : null });
    } catch {
      return json({ error: 'Document patch unavailable' }, 503);
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
      getDbMap(docCache, env.DB).delete(docPath);
      getDbMap(listCache, env.DB).clear();

      // Deliberately does NOT touch friendbook_cf_interaction_resources. Writing a
      // document here says nothing about whether its history was migrated, and
      // self-marking a resource 'verified' is exactly how engagement data was lost
      // before. Verification is owned by the migration pipeline, not the write path.
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

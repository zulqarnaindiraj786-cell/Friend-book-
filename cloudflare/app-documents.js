// Cloudflare D1 App Document Store (/v1/docs) + D1-first ACL helpers.
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
    return byKey ? true : null;
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
]);
const FILTER_OPS = new Set(['eq', 'in', 'contains', 'gt', 'gte', 'lt', 'lte', 'ne']);
const MAX_FILTERS = 6;

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
  // A per-user scope is never cached under a shared key.
  const privateFilter = ownerOnly || includePrivate || filters.length > 0;
  const cacheKey = `${collection}:${limit}:${orderField}:${dir}:${cursor}:${rawFilters}:${ownerOnly}:${excludeArchived}`;

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
    filters, privateFilter, cacheKey, accept,
    // One extra row answers "is there another page"; a JS-side filter gets a wider window so the
    // page still fills after rows it has to drop.
    run: (db) => db.prepare(sql).bind(...sqlArgs, filters.some((f) => f.op === 'contains' || f.op === 'in') ? limit * 3 + 1 : limit + 1).all(),
  };
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
      for (const row of rows?.results || []) {
        const doc = toPlainDocument(row.document_json, row.doc_id);
        if (!doc) continue;
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
      };
      if (!plan.privateFilter) lCache.set(cacheKey, { documents, expiresAt: now + LIST_CACHE_TTL_MS });
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

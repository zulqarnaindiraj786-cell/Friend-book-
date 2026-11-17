// Cloudflare D1 Firestore-compatible document API (/v1/firestore/*).
// Android's com.google.firebase.firestore shim talks here with a Firebase Auth
// bearer token (verified in worker.js). All app data lands in D1; Firebase
// keeps ONLY Auth + FCM. Legacy Firestore docs are lazily backfilled on read
// miss (read-only REST, never a write to Firestore) so old users keep working.
import { fromFirestoreFields } from './app-documents.js';

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

function validSegment(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= 512
    && !/[\\/\\x00-\\x1f]/.test(v) && !['.', '..'].includes(v);
}
function splitPath(path) {
  if (typeof path !== 'string' || !path) return null;
  const parts = path.split('/');
  if (parts.some(p => !validSegment(p))) return null;
  return parts;
}
function isDocumentPath(path) {
  const parts = splitPath(path);
  return parts && parts.length % 2 === 0;
}
function isCollectionPath(path) {
  const parts = splitPath(path);
  return parts && parts.length % 2 === 1;
}
function parentPath(path) {
  const parts = splitPath(path);
  if (!parts || parts.length < 2) return '';
  return parts.slice(0, -1).join('/');
}
function docIdOf(path) {
  const parts = splitPath(path);
  return parts ? parts[parts.length - 1] : '';
}
function collectionIdOf(path) {
  const parts = splitPath(path);
  return parts && parts.length >= 2 ? parts[parts.length - 2] : '';
}
function autoId() {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  const bytes = new Uint8Array(20);
  crypto.getRandomValues(bytes);
  let out = '';
  for (const b of bytes) out += chars[b % chars.length];
  return out;
}

// ---------- value encoding (Timestamp / Reference survive JSON round-trip) ----------
function encodeValue(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'boolean' || typeof v === 'string') return v;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (Array.isArray(v)) return v.map(encodeValue);
  if (typeof v === 'object') {
    if (v.__type === 'timestamp' && Number.isFinite(v.millis)) {
      return { __type: 'timestamp', millis: Math.trunc(v.millis) };
    }
    if (v.__type === 'reference' && typeof v.path === 'string') {
      return { __type: 'reference', path: v.path };
    }
    const out = {};
    for (const [k, item] of Object.entries(v)) out[k] = encodeValue(item);
    return out;
  }
  return String(v);
}
function decodeValue(v) {
  if (v === null || v === undefined) return null;
  if (typeof v === 'boolean' || typeof v === 'string' || typeof v === 'number') return v;
  if (Array.isArray(v)) return v.map(decodeValue);
  if (typeof v === 'object') {
    if (v.__type === 'timestamp' && Number.isFinite(v.millis)) {
      return { __type: 'timestamp', millis: Math.trunc(v.millis) };
    }
    if (v.__type === 'reference' && typeof v.path === 'string') {
      return { __type: 'reference', path: v.path };
    }
    const out = {};
    for (const [k, item] of Object.entries(v)) out[k] = decodeValue(item);
    return out;
  }
  return v;
}
function isFieldValue(v) {
  return v && typeof v === 'object' && typeof v.__fieldValue === 'string';
}
function resolveValue(v, existing, now) {
  if (isFieldValue(v)) {
    const kind = v.__fieldValue;
    if (kind === 'serverTimestamp') return { __type: 'timestamp', millis: now };
    if (kind === 'increment') {
      const delta = Number(v.value) || 0;
      const base = typeof existing === 'number' && Number.isFinite(existing) ? existing : 0;
      const sum = base + delta;
      return Number.isInteger(delta) && Number.isInteger(base) ? Math.trunc(sum) : sum;
    }
    if (kind === 'arrayUnion') {
      const cur = Array.isArray(existing) ? [...existing] : [];
      for (const item of (v.values || [])) {
        const enc = encodeValue(item);
        if (!cur.some(x => JSON.stringify(x) === JSON.stringify(enc))) cur.push(enc);
      }
      return cur;
    }
    if (kind === 'arrayRemove') {
      const cur = Array.isArray(existing) ? [...existing] : [];
      const remove = (v.values || []).map(encodeValue);
      return cur.filter(x => !remove.some(r => JSON.stringify(r) === JSON.stringify(x)));
    }
    if (kind === 'delete') return undefined; // sentinel: delete field
    return null;
  }
  if (Array.isArray(v)) return v.map(item => {
    const r = resolveValue(item, undefined, now);
    return r === undefined ? null : r;
  });
  if (v && typeof v === 'object') {
    if (v.__type === 'timestamp' || v.__type === 'reference') return encodeValue(v);
    const out = {};
    for (const [k, item] of Object.entries(v)) {
      const r = resolveValue(item, undefined, now);
      if (r !== undefined) out[k] = r;
    }
    return out;
  }
  return encodeValue(v);
}
function deepMerge(target, source, now) {
  const out = { ...(target || {}) };
  for (const [k, v] of Object.entries(source || {})) {
    if (isFieldValue(v) && v.__fieldValue === 'delete') {
      delete out[k];
      continue;
    }
    if (v && typeof v === 'object' && !Array.isArray(v) && !isFieldValue(v)
        && !(v.__type === 'timestamp' || v.__type === 'reference')
        && out[k] && typeof out[k] === 'object' && !Array.isArray(out[k])
        && !(out[k].__type === 'timestamp' || out[k].__type === 'reference')) {
      out[k] = deepMerge(out[k], v, now);
    } else {
      const r = resolveValue(v, out[k], now);
      if (r === undefined) delete out[k];
      else out[k] = r;
    }
  }
  return out;
}
function parseFieldPath(field) {
  if (Array.isArray(field)) return field.map(String);
  if (typeof field === 'string') {
    if (field === '__name__') return ['__name__'];
    return field.split('.');
  }
  return [String(field)];
}
function getField(doc, fieldPath) {
  const parts = parseFieldPath(fieldPath);
  if (parts.length === 1 && parts[0] === '__name__') return undefined; // handled by caller
  let cur = doc;
  for (const p of parts) {
    if (cur === null || typeof cur !== 'object' || Array.isArray(cur)) return undefined;
    cur = cur[p];
    if (cur === undefined) return undefined;
  }
  return cur;
}
function setField(doc, fieldPath, value) {
  const parts = parseFieldPath(fieldPath);
  let cur = doc;
  for (let i = 0; i < parts.length - 1; i++) {
    const p = parts[i];
    if (cur[p] === null || typeof cur[p] !== 'object' || Array.isArray(cur[p])) cur[p] = {};
    cur = cur[p];
  }
  cur[parts[parts.length - 1]] = value;
}
function deleteField(doc, fieldPath) {
  const parts = parseFieldPath(fieldPath);
  let cur = doc;
  for (let i = 0; i < parts.length - 1; i++) {
    cur = cur?.[parts[i]];
    if (cur === null || typeof cur !== 'object') return;
  }
  if (cur && typeof cur === 'object') delete cur[parts[parts.length - 1]];
}
function applyUpdate(existing, fields, now) {
  const out = existing ? JSON.parse(JSON.stringify(existing)) : {};
  for (const [field, raw] of Object.entries(fields || {})) {
    const existingVal = getField(out, field);
    const resolved = resolveValue(raw, existingVal, now);
    if (resolved === undefined) deleteField(out, field);
    else setField(out, field, resolved);
  }
  return out;
}
function applySet(existing, data, merge, mergeFields, now) {
  const incoming = data || {};
  if (mergeFields && mergeFields.length) {
    const out = existing ? JSON.parse(JSON.stringify(existing)) : {};
    for (const f of mergeFields) {
      const parts = parseFieldPath(f);
      const key = parts[parts.length - 1];
      // mergeFields are top-level in practice; support dotted by reading from incoming.
      let src = incoming;
      for (const p of parts) src = src?.[p];
      if (src === undefined) continue;
      const resolved = resolveValue(src, getField(out, f), now);
      if (resolved === undefined) deleteField(out, f);
      else setField(out, f, resolved);
    }
    return out;
  }
  if (merge) return deepMerge(existing || {}, incoming, now);
  // Replace: resolve transforms against nothing (increment starts at delta).
  const out = {};
  for (const [k, v] of Object.entries(incoming)) {
    const r = resolveValue(v, undefined, now);
    if (r !== undefined) out[k] = r;
  }
  return out;
}
function ownerOf(doc, fallback) {
  const d = doc || {};
  const v = d.authorId || d.uid || d.senderId || d.from || d.toUserId || d.toUid || d.userId || d.creatorId || d.hostId;
  return typeof v === 'string' && v ? v : (fallback || '');
}

// ---------- D1 helpers ----------
async function readRow(db, path) {
  const row = await db.prepare(
    'SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE path = ?'
  ).bind(path).first();
  return row || null;
}
async function writeRow(db, path, doc, now, existing) {
  const parent = parentPath(path);
  const coll = collectionIdOf(path);
  const id = docIdOf(path);
  const owner = ownerOf(doc, existing?.owner_uid || '');
  const createTime = existing?.create_time || now;
  const serialized = JSON.stringify(doc);
  if (serialized.length > 1_800_000) throw new Error('Document too large');
  await db.prepare(
    `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
  ).bind(path, parent, coll, id, owner, serialized, createTime, now).run();
  return { path, parent_path: parent, collection_id: coll, doc_id: id, owner_uid: owner, document_json: serialized, create_time: createTime, update_time: now };
}
function rowToDocument(row) {
  if (!row) return null;
  let data = null;
  try { data = JSON.parse(row.document_json); } catch { data = null; }
  if (!data || typeof data !== 'object') data = {};
  return {
    id: row.doc_id,
    path: row.path,
    parent: row.parent_path,
    collection: row.collection_id,
    data: decodeValue(data),
    createTime: row.create_time || 0,
    updateTime: row.update_time || 0,
  };
}

// ---------- legacy Firestore read-only backfill (never writes to Firestore) ----------
async function backfillDocument(db, path, identity, env) {
  const project = env.FIREBASE_PROJECT_ID;
  const token = identity?.token;
  if (!project || !token || !isDocumentPath(path)) return null;
  try {
    const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(project)}/databases/(default)/documents/${path.split('/').map(encodeURIComponent).join('/')}`;
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(8000) });
    if (!r.ok) return null;
    const body = await r.json();
    if (!body || typeof body !== 'object') return null;
    const plain = fromFirestoreFields(body.fields || {});
    const now = Date.now();
    const createMs = body.createTime ? Date.parse(body.createTime) : now;
    const updateMs = body.updateTime ? Date.parse(body.updateTime) : now;
    const encoded = encodeValue(plain);
    const row = await writeRow(db, path, encoded, Number.isFinite(updateMs) ? updateMs : now, null);
    // Preserve original create time if available.
    if (Number.isFinite(createMs) && createMs > 0) {
      await db.prepare('UPDATE friendbook_cf_firestore_documents SET create_time = ? WHERE path = ?').bind(createMs, path).run();
      row.create_time = createMs;
    }
    return rowToDocument(row);
  } catch {
    return null; // quota / permission / network: never crash the app
  }
}
async function backfillCollection(db, parent, identity, env) {
  const project = env.FIREBASE_PROJECT_ID;
  const token = identity?.token;
  if (!project || !token || !isCollectionPath(parent)) return 0;
  try {
    const url = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(project)}/databases/(default)/documents/${parent.split('/').map(encodeURIComponent).join('/')}?pageSize=100&showMissing=true`;
    const r = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(8000) });
    if (!r.ok) return 0;
    const body = await r.json();
    const docs = Array.isArray(body?.documents) ? body.documents : [];
    let count = 0;
    const now = Date.now();
    for (const d of docs) {
      const name = d?.name || '';
      const prefix = `projects/${project}/databases/(default)/documents/`;
      if (!name.startsWith(prefix)) continue;
      const path = name.slice(prefix.length);
      if (!isDocumentPath(path) || parentPath(path) !== parent) continue;
      if (await readRow(db, path)) continue;
      const plain = fromFirestoreFields(d.fields || {});
      await writeRow(db, path, encodeValue(plain), now, null);
      count++;
    }
    return count;
  } catch {
    return 0;
  }
}

// ---------- query engine ----------
function typeRank(v) {
  if (v === undefined || v === null) return 0;
  if (typeof v === 'boolean') return 1;
  if (typeof v === 'number') return 2;
  if (typeof v === 'string') return 3;
  if (v && typeof v === 'object' && v.__type === 'timestamp') return 4;
  if (Array.isArray(v)) return 5;
  return 6;
}
function compareValues(a, b) {
  const ra = typeRank(a), rb = typeRank(b);
  if (ra !== rb) return ra - rb;
  if (a === undefined || a === null) return 0;
  if (typeof a === 'boolean') return (a === b ? 0 : a ? 1 : -1);
  if (typeof a === 'number') return a - b;
  if (typeof a === 'string') return a < b ? -1 : a > b ? 1 : 0;
  if (a && typeof a === 'object' && a.__type === 'timestamp') return (a.millis || 0) - (b.millis || 0);
  const sa = JSON.stringify(a), sb = JSON.stringify(b);
  return sa < sb ? -1 : sa > sb ? 1 : 0;
}
function valuesEqual(a, b) {
  if (a === undefined || b === undefined) return false;
  if (a === null || b === null) return a === b;
  if (typeof a === 'number' && typeof b === 'number') return a === b;
  if (typeof a !== typeof b) {
    // Firestore compares numbers across int/double; other types must match.
    return false;
  }
  if (typeof a === 'object') return JSON.stringify(a) === JSON.stringify(b);
  return a === b;
}
function matchesFilter(doc, filter, docId) {
  const field = filter.field;
  const op = filter.op;
  const expected = decodeValue(filter.value);
  let actual;
  if (field === '__name__') actual = docId;
  else actual = getField(doc, field);
  switch (op) {
    case '==': return actual !== undefined && valuesEqual(actual, expected);
    case '!=': return actual !== undefined && !valuesEqual(actual, expected);
    case '<': return actual !== undefined && compareValues(actual, expected) < 0;
    case '<=': return actual !== undefined && compareValues(actual, expected) <= 0;
    case '>': return actual !== undefined && compareValues(actual, expected) > 0;
    case '>=': return actual !== undefined && compareValues(actual, expected) >= 0;
    case 'in': {
      const list = Array.isArray(filter.value) ? filter.value.map(decodeValue) : [];
      return actual !== undefined && list.some(v => valuesEqual(actual, v));
    }
    case 'array-contains': return Array.isArray(actual) && actual.some(v => valuesEqual(v, expected));
    case 'array-contains-any': {
      const list = Array.isArray(filter.value) ? filter.value.map(decodeValue) : [];
      return Array.isArray(actual) && list.some(v => actual.some(x => valuesEqual(x, v)));
    }
    default: return false;
  }
}
function sortDocuments(docs, orderBy) {
  const orders = Array.isArray(orderBy) && orderBy.length ? orderBy : [{ field: '__name__', direction: 'ASCENDING' }];
  return [...docs].sort((a, b) => {
    for (const o of orders) {
      const field = o.field;
      const av = field === '__name__' ? a.id : getField(a.data, field);
      const bv = field === '__name__' ? b.id : getField(b.data, field);
      const cmp = compareValues(av, bv);
      if (cmp !== 0) return o.direction === 'DESCENDING' ? -cmp : cmp;
    }
    return a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
  });
}
function cursorIndex(sorted, cursor) {
  if (!cursor || typeof cursor !== 'object') return -1;
  const id = cursor.docId || cursor.id || (cursor.path ? docIdOf(cursor.path) : null);
  if (!id) return -1;
  return sorted.findIndex(d => d.id === id);
}
function applyQuery(docs, spec) {
  const filters = Array.isArray(spec?.filters) ? spec.filters : [];
  let filtered = docs.filter(d => filters.every(f => matchesFilter(d.data, f, d.id)));
  let sorted = sortDocuments(filtered, spec?.orderBy);
  let start = 0;
  let end = sorted.length;
  if (spec?.startAfter) {
    const idx = cursorIndex(sorted, spec.startAfter);
    if (idx >= 0) start = Math.max(start, idx + 1);
  }
  if (spec?.startAt) {
    const idx = cursorIndex(sorted, spec.startAt);
    if (idx >= 0) start = Math.max(start, idx);
  }
  if (spec?.endBefore) {
    const idx = cursorIndex(sorted, spec.endBefore);
    if (idx >= 0) end = Math.min(end, idx);
  }
  if (spec?.endAt) {
    const idx = cursorIndex(sorted, spec.endAt);
    if (idx >= 0) end = Math.min(end, idx + 1);
  }
  let sliced = sorted.slice(start, end);
  const limit = Number.isFinite(spec?.limit) ? Math.trunc(spec.limit) : null;
  const limitToLast = Number.isFinite(spec?.limitToLast) ? Math.trunc(spec.limitToLast) : null;
  if (limitToLast !== null && limitToLast > 0) {
    sliced = sliced.slice(-limitToLast);
  } else if (limit !== null && limit > 0) {
    sliced = sliced.slice(0, limit);
  }
  return sliced;
}

// ---------- operations ----------
async function applyOperation(db, op, uid, now, existingCache) {
  const path = op.path;
  if (!isDocumentPath(path)) throw new Error('Invalid document path');
  const existingRow = existingCache?.has(path) ? existingCache.get(path) : await readRow(db, path);
  const existingDoc = existingRow ? decodeValue(JSON.parse(existingRow.document_json)) : null;
  const type = op.type || 'set';
  if (type === 'delete') {
    await db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).run();
    return { path, deleted: true };
  }
  if (type === 'update') {
    if (!existingRow) throw new Error('NOT_FOUND');
    const next = applyUpdate(existingDoc, op.fields || op.data || {}, now);
    const row = await writeRow(db, path, next, now, existingRow);
    return rowToDocument(row);
  }
  if (type === 'create') {
    if (existingRow) throw new Error('ALREADY_EXISTS');
    const next = applySet(null, op.data || {}, false, null, now);
    const row = await writeRow(db, path, next, now, null);
    return rowToDocument(row);
  }
  // set
  if (op.precondition === 'exists=false' && existingRow) throw new Error('ALREADY_EXISTS');
  const next = applySet(existingDoc, op.data || {}, Boolean(op.merge), op.mergeFields || null, now);
  const row = await writeRow(db, path, next, now, existingRow);
  return rowToDocument(row);
}

export async function firestoreDocuments(request, env, identity) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = identity?.uid;
  if (!validSegment(uid)) return json({ error: 'Unauthorized' }, 401);
  const url = new URL(request.url);
  const path = url.pathname;
  const db = env.DB;
  const now = Date.now();

  // GET /v1/firestore/document?path=...
  if (path === '/v1/firestore/document' && request.method === 'GET') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    let row = await readRow(db, docPath).catch(() => null);
    if (!row) row = await backfillDocument(db, docPath, identity, env).then(d => d ? { path: d.path, parent_path: d.parent, collection_id: d.collection, doc_id: d.id, owner_uid: '', document_json: JSON.stringify(encodeValue(d.data)), create_time: d.createTime, update_time: d.updateTime } : null).catch(() => null);
    // backfillDocument already wrote the row; re-read for canonical shape.
    if (!row) {
      const reread = await readRow(db, docPath).catch(() => null);
      if (reread) row = reread;
    }
    if (!row) return json({ exists: false, id: docIdOf(docPath), path: docPath, data: null });
    return json(rowToDocument(row));
  }

  // PUT /v1/firestore/document?path=...  {data, merge, mergeFields}
  if (path === '/v1/firestore/document' && request.method === 'PUT') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return json({ error: 'Invalid body' }, 400);
    try {
      const existing = await readRow(db, docPath);
      const existingDoc = existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const next = applySet(existingDoc, body.data || {}, Boolean(body.merge), body.mergeFields || null, now);
      const row = await writeRow(db, docPath, next, now, existing);
      return json(rowToDocument(row));
    } catch (e) {
      return json({ error: e.message === 'Document too large' ? 'Document too large' : 'Write unavailable' }, e.message === 'Document too large' ? 413 : 503);
    }
  }

  // PATCH /v1/firestore/document?path=...  {fields}
  if (path === '/v1/firestore/document' && request.method === 'PATCH') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    try {
      const existing = await readRow(db, docPath);
      if (!existing) return json({ error: 'NOT_FOUND' }, 404);
      const existingDoc = decodeValue(JSON.parse(existing.document_json));
      const next = applyUpdate(existingDoc, body?.fields || body?.data || {}, now);
      const row = await writeRow(db, docPath, next, now, existing);
      return json(rowToDocument(row));
    } catch {
      return json({ error: 'Update unavailable' }, 503);
    }
  }

  // DELETE /v1/firestore/document?path=...
  if (path === '/v1/firestore/document' && request.method === 'DELETE') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    await db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path = ?').bind(docPath).run();
    return json({ deleted: true, path: docPath });
  }

  // POST /v1/firestore/document?parent=...  {data}  -> add() with auto id
  if (path === '/v1/firestore/document' && request.method === 'POST') {
    const parent = url.searchParams.get('parent') || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const id = autoId();
    const docPath = `${parent}/${id}`;
    try {
      const next = applySet(null, body?.data || {}, false, null, now);
      const row = await writeRow(db, docPath, next, now, null);
      return json(rowToDocument(row), 201);
    } catch {
      return json({ error: 'Write unavailable' }, 503);
    }
  }

  // POST /v1/firestore/query  {parent, filters, orderBy, limit, limitToLast, startAfter, startAt, endBefore, endAt}
  if (path === '/v1/firestore/query' && request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const parent = body?.parent || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    const fetchDocs = async () => {
      const rows = await db.prepare(
        'SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE parent_path = ?'
      ).bind(parent).all();
      return (rows?.results || []).map(rowToDocument).filter(Boolean);
    };
    let docs = await fetchDocs().catch(() => []);
    if (docs.length === 0) {
      await backfillCollection(db, parent, identity, env).catch(() => 0);
      docs = await fetchDocs().catch(() => []);
    }
    const result = applyQuery(docs, body || {});
    return json({ parent, documents: result });
  }

  // GET /v1/firestore/count?parent=...&filters=...
  if (path === '/v1/firestore/count' && request.method === 'GET') {
    const parent = url.searchParams.get('parent') || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    let filters = [];
    try { filters = JSON.parse(url.searchParams.get('filters') || '[]'); } catch { filters = []; }
    const rows = await db.prepare(
      'SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE parent_path = ?'
    ).bind(parent).all().catch(() => ({ results: [] }));
    const docs = (rows?.results || []).map(rowToDocument).filter(Boolean);
    const filtered = docs.filter(d => (Array.isArray(filters) ? filters : []).every(f => matchesFilter(d.data, f, d.id)));
    return json({ parent, count: filtered.length });
  }

  // POST /v1/firestore/batch  {operations:[...]}  (also used for transactions)
  if ((path === '/v1/firestore/batch' || path === '/v1/firestore/transaction') && request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const ops = Array.isArray(body?.operations) ? body.operations : [];
    if (ops.length === 0 || ops.length > 500) return json({ error: 'Invalid operations' }, 400);
    // Pre-read existing docs so FieldValue transforms resolve against current state.
    const cache = new Map();
    for (const op of ops) {
      if (op?.path && !cache.has(op.path)) {
        try { cache.set(op.path, await readRow(db, op.path)); } catch { cache.set(op.path, null); }
      }
    }
    // Compute final docs in memory, then write atomically via D1 batch.
    const finalDocs = new Map(); // path -> {doc|null(deleted), existingRow}
    const order = [];
    for (const op of ops) {
      const p = op.path;
      if (!isDocumentPath(p)) return json({ error: 'Invalid path' }, 400);
      if (!finalDocs.has(p)) {
        const existingRow = cache.get(p) || null;
        const existingDoc = existingRow ? decodeValue(JSON.parse(existingRow.document_json)) : null;
        finalDocs.set(p, { doc: existingDoc, existingRow, deleted: false });
        order.push(p);
      }
      const entry = finalDocs.get(p);
      const type = op.type || 'set';
      if (type === 'delete') {
        entry.deleted = true;
        entry.doc = null;
        continue;
      }
      if (type === 'update') {
        if (!entry.existingRow && !entry.doc) return json({ error: 'NOT_FOUND' }, 404);
        entry.doc = applyUpdate(entry.doc, op.fields || op.data || {}, now);
        entry.deleted = false;
        continue;
      }
      if (type === 'create') {
        if (entry.existingRow || entry.doc) return json({ error: 'ALREADY_EXISTS' }, 409);
        entry.doc = applySet(null, op.data || {}, false, null, now);
        entry.deleted = false;
        continue;
      }
      if (op.precondition === 'exists=false' && (entry.existingRow || entry.doc)) {
        return json({ error: 'ALREADY_EXISTS' }, 409);
      }
      entry.doc = applySet(entry.doc, op.data || {}, Boolean(op.merge), op.mergeFields || null, now);
      entry.deleted = false;
    }
    try {
      const statements = [];
      for (const p of order) {
        const entry = finalDocs.get(p);
        if (entry.deleted) {
          statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path = ?').bind(p));
        } else {
          const parent = parentPath(p);
          const coll = collectionIdOf(p);
          const id = docIdOf(p);
          const owner = ownerOf(entry.doc, entry.existingRow?.owner_uid || uid);
          const createTime = entry.existingRow?.create_time || now;
          const serialized = JSON.stringify(entry.doc);
          if (serialized.length > 1_800_000) return json({ error: 'Document too large' }, 413);
          statements.push(db.prepare(
            `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
          ).bind(p, parent, coll, id, owner, serialized, createTime, now));
        }
      }
      if (typeof db.batch === 'function' && statements.length) {
        await db.batch(statements);
      } else {
        for (const s of statements) await s.run();
      }
      return json({ ok: true, operations: ops.length });
    } catch {
      return json({ error: 'Batch unavailable' }, 503);
    }
  }

  return json({ error: 'Not found' }, 404);
}

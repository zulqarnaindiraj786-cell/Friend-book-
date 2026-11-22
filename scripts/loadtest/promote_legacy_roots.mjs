// Bulk, resumable promotion of legacy-only ROOT documents into the canonical
// Firestore-shaped table. It is deliberately narrower than "copy every row":
// a row is written only when the canonical projection is *provably* the same
// document (identical plain projection, and typed fields identical apart from
// the redundant `id` that canonical storage now preserves). Rows whose
// projection would change real values, or whose authorization fields differ,
// are skipped and reported by category and field name only.
//
// No document contents, paths, ids or values are ever printed.
import { isDeepStrictEqual } from 'node:util';
import { appDocumentToFirestoreRow } from '../../cloudflare/firestore-documents.js';
import { toFirestoreFields, toPlainDocument } from '../../cloudflare/app-documents.js';

export const BULK_COLLECTIONS = new Set(['chats', 'messages', 'notifications', 'posts', 'comments',
  'friends', 'friend_requests', 'users']);

const FIELDS = ['path', 'parent_path', 'collection_id', 'doc_id', 'owner_uid', 'document_json', 'create_time', 'update_time'];
const SAFE = new Set(['equivalent_root_only', 'facade_projection_diff']);
// D1 rejects more than 100 bound variables per statement. Each guarded row
// binds 13 values (8 canonical columns + 5 source guards), so seven rows fit.
export 

// Keyset page over one collection. The composite index (collection, updated_at)
// avoids a table scan; the NOT EXISTS probe is answered by the canonical
// primary key.
const PAGE = `SELECT l.path,l.collection,l.doc_id,l.owner_uid,l.document_json,l.updated_at
 FROM friendbook_cf_documents l INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE l.collection=? AND (l.updated_at>? OR (l.updated_at=? AND l.path>?))
 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)
 ORDER BY l.updated_at,l.path LIMIT ?`;

export const INVENTORY = `SELECT COUNT(*) AS sampled,
 COALESCE(SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END),0) AS legacy_only
 FROM (SELECT path FROM friendbook_cf_documents INDEXED BY idx_friendbook_cf_documents_collection_updated
       WHERE collection=? LIMIT 25001) s
 LEFT JOIN friendbook_cf_firestore_documents f ON f.path=s.path`;

// A representation-only difference keeps the same value and changes only how it
// is encoded (for example a numeric string versus the number the canonical
// write path stores). It is promotable when every differing field is in the
// caller's explicit allowlist and both sides are numerically equal.
function representationOnly(fromJson, toJson, docId, allowed) {
  const left = toPlainDocument(fromJson, docId);
  const right = toPlainDocument(toJson, docId);
  const paths = [];
  const walk = (a, b, prefix) => {
    if (isDeepStrictEqual(a, b)) return;
    if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
      for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) walk(a[key], b[key], prefix ? `${prefix}.${key}` : key);
      return;
    }
    paths.push([prefix || '(document)', a, b]);
  };
  walk(left, right, '');
  if (!paths.length) return false;
  return paths.every(([path, a, b]) => {
    if (!allowed.has(path)) return false;
    const numeric = value => (typeof value === 'number' ? value
      : typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value) ? Number(value)
        : value && typeof value === 'object' && value.__type === 'timestamp' && Number.isFinite(value.millis) ? value.millis
          : NaN);
    return Number.isFinite(numeric(a)) && Number.isFinite(numeric(b)) && numeric(a) === numeric(b);
  });
}

export function prepare(source, { representationFields = new Set(), allowEncodingOnly = false } = {}) {
  if (!source || typeof source.collection !== 'string' || !BULK_COLLECTIONS.has(source.collection)
      || typeof source.path !== 'string' || source.path !== `${source.collection}/${source.doc_id}`
      || typeof source.doc_id !== 'string' || !source.doc_id || source.doc_id.includes('/')
      || typeof source.owner_uid !== 'string' || typeof source.document_json !== 'string'
      || source.document_json.length > 100_000 || !Number.isSafeInteger(source.updated_at))
    return { status: 'skip', reason: 'unsafe_shape' };
  let normalized;
  try { normalized = appDocumentToFirestoreRow(source); } catch { return { status: 'skip', reason: 'unsafe_shape' }; }
  if (!normalized || !Number.isSafeInteger(normalized.create_time) || !Number.isSafeInteger(normalized.update_time))
    return { status: 'skip', reason: 'unsafe_shape' };
  const oldFields = toFirestoreFields(source.document_json);
  const candidate = JSON.parse(normalized.document_json);
  if (oldFields?.id?.stringValue === source.doc_id) candidate.id = source.doc_id;
  const saved = JSON.stringify(candidate);
  const plainEqual = isDeepStrictEqual(toPlainDocument(source.document_json, source.doc_id), toPlainDocument(saved, source.doc_id));
  let representation = false;
  if (!plainEqual) {
    if (!representationOnly(source.document_json, saved, source.doc_id, representationFields))
      return { status: 'skip', reason: 'plain_projection_diff', fields: differingFields(source.document_json, saved) };
    representation = true;
  }
  if (!isDeepStrictEqual(oldFields, toFirestoreFields(saved))) {
    const withoutId = { ...oldFields };
    delete withoutId.id;
    if (!(oldFields?.id?.stringValue === source.doc_id && isDeepStrictEqual(withoutId, toFirestoreFields(saved)))) {
      // The plain document is unchanged (checked above when plainEqual), so
      // this difference is only how the value is encoded. New writes already
      // use the canonical encoding, so promoting is explicitly opt-in.
      if (!((plainEqual || representation) && allowEncodingOnly))
        return { status: 'skip', reason: 'auth_projection_diff', fields: differingFields(source.document_json, saved) };
      representation = true;
    }
  }
  if (source.collection === 'users' && ['followers', 'following'].some(key => Array.isArray(candidate[key]) && candidate[key].length > 20))
    return { status: 'skip', reason: 'fanout_limit' };
  if (source.collection === 'chats' && Array.isArray(candidate.participants) && candidate.participants.length > 20)
    return { status: 'skip', reason: 'fanout_limit' };
  return {
    status: 'promote',
    row: { ...normalized, document_json: saved },
    source,
    facadeChanged: !isDeepStrictEqual(JSON.parse(normalized.document_json), candidate),
    representation,
  };
}

// Field names and JSON type labels only: enough to explain a skip without
// exposing any value.
function typeLabel(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (typeof value === 'object') return typeof value.__type === 'string' ? `typed:${value.__type}` : 'object';
  return typeof value;
}
function differingFields(fromJson, toJson) {
  const plain = value => {
    try { return JSON.parse(JSON.stringify(value ?? null)); } catch { return null; }
  };
  const left = plain(toPlainDocument(fromJson, ''));
  const right = plain(toPlainDocument(toJson, ''));
  const names = new Map();
  const walk = (a, b, prefix) => {
    if (isDeepStrictEqual(a, b)) return;
    if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
      for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) walk(a[key], b[key], prefix ? `${prefix}.${key}` : key);
      return;
    }
    names.set(prefix || '(document)', `${typeLabel(a)}->${typeLabel(b)}`);
  };
  walk(left, right, '');
  return [...names].slice(0, 8);
}

// One guarded INSERT .. SELECT per row. D1 caps a statement at 100 bound
// variables and rejects long compound SELECTs, so a per-row statement is the
// portable shape: it binds 10 values and keeps both guards (source unchanged,
// no canonical row yet) inside the same statement as the write.
export function rowStatement({ row, source }) {
  if (!row || !source || typeof source.document_json !== 'string' || !Number.isSafeInteger(source.updated_at))
    throw Error('unsafe plan length');
  return {
    sql: `INSERT INTO friendbook_cf_firestore_documents(${FIELDS.join(',')})
      SELECT ?1,?2,?3,?4,?5,?6,?7,?8 FROM friendbook_cf_documents l
      WHERE l.path=?1 AND l.collection=?3 AND l.doc_id=?4 AND l.owner_uid=?5
        AND l.document_json=?9 AND l.updated_at=?10
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=?1)
      ON CONFLICT(path) DO NOTHING RETURNING path`,
    params: [...FIELDS.map(key => row[key]), source.document_json, source.updated_at],
  };
}

// The INSERT SELECT is the verification: a returned path can only exist when
// the source row still matched and this exact document_json was written. A
// small sample is still read back to catch a schema-level surprise.
export async function verifySample(query, planned, limit = 2) {
  for (const { row } of planned.slice(0, limit)) {
    const got = (await query(`SELECT ${FIELDS.join(',')} FROM friendbook_cf_firestore_documents WHERE path=?`, [row.path])).results;
    if (got.length !== 1 || !FIELDS.every(key => isDeepStrictEqual(got[0][key], row[key])))
      throw Error('post-insert verification failed');
  }
}

async function runPool(tasks, concurrency) {
  let index = 0;
  const results = new Array(tasks.length);
  await Promise.all(Array.from({ length: Math.min(concurrency, tasks.length) }, async () => {
    while (index < tasks.length) {
      const mine = index++;
      results[mine] = await tasks[mine]();
    }
  }));
  return results;
}

/**
 * Promote every provably-equivalent legacy-only row of one collection.
 * Returns counts and the categories of rows that were deliberately skipped.
 */
export async function promoteCollection(query, collection, {
  batch = 100, maxRows = 6000, maxReads = 400000, maxBatches = 200, representationFields = [], write = true,
  allowEncodingOnly = false,
} = {}) {
  const representationSet = new Set(representationFields);
  if (!BULK_COLLECTIONS.has(collection)) throw Error('unsupported collection');
  if (!Number.isInteger(batch) || batch < 1 || batch > 100) throw Error('invalid batch size');
  const before = await legacyOnly(query, collection);
  let lastUpdated = -1, lastPath = "";
  let scanned = 0, inserted = 0, reads = 0, batches = 0;
  const skipped = {};
  let facades = 0, representations = 0;
  while (batches < maxBatches && scanned < maxRows) {
    const page = await query(PAGE, [collection, lastUpdated, lastUpdated, lastPath, Math.min(batch, maxRows - scanned)]);
    reads += Number.isSafeInteger(page.meta?.rows_read) ? page.meta.rows_read : 0;
    if (reads > maxReads) throw Error('read cap exceeded');
    const rows = page.results || [];
    if (!rows.length) break;
    batches++;
    const prepared = rows.map(row => prepare(row, { representationFields: representationSet, allowEncodingOnly }));
    rows.forEach((row, index) => {
      scanned++;
      lastUpdated = row.updated_at;
      lastPath = row.path;
      if (prepared[index].status !== 'promote') {
        const key = `${prepared[index].reason}${prepared[index].fields?.length
          ? ':' + prepared[index].fields.map(([path, types]) => `${path}[${types}]`).join(',') : ''}`;
        skipped[key] = (skipped[key] || 0) + 1;
      } else if (prepared[index].facadeChanged) facades++;
      if (prepared[index].representation) representations++;
    });
    const planned = prepared.filter(entry => entry.status === 'promote');
    if (!write) { inserted += planned.length; continue; }
    const written = await runPool(planned.map(entry => async () => {
      const action = rowStatement(entry);
      const done = (await query(action.sql, action.params)).results || [];
      return done.length === 1 && done[0].path === entry.row.path;
    }), 6);
    const confirmed = planned.filter((_, position) => written[position]);
    inserted += confirmed.length;
    if (confirmed.length) await verifySample(query, confirmed);
    if (rows.length < batch) break;
  }
  const after = await legacyOnly(query, collection);
  return { collection, before, inserted, after, scanned, skipped, facades, representations, reads, truncated: scanned >= maxRows };
}

export async function legacyOnly(query, collection) {
  const row = (await query(INVENTORY, [collection])).results?.[0];
  if (!row || !Number.isInteger(row.sampled) || !Number.isInteger(row.legacy_only) || row.sampled > 25001)
    throw Error('inventory unavailable');
  return row.legacy_only;
}

async function remote(sql, params) {
  const token = process.env.CLOUDFLARE_API_TOKEN;
  if (!token) throw Error('missing credential');
  const response = await fetch('https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query', {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify({ sql, params }),
    signal: AbortSignal.timeout(45000),
  });
  const payload = await response.json();
  if (!response.ok || !payload?.success || payload.result?.length !== 1 || payload.result[0]?.success !== true
      || !Array.isArray(payload.result[0].results)) {
    // Diagnostics never include SQL text, parameters or row values.
    const detail = { http: response.status, success: payload?.success === true,
      results: Array.isArray(payload?.result) ? payload.result.length : 0,
      statement: payload?.result?.[0]?.success === true,
      code: payload?.errors?.[0]?.code ?? payload?.result?.[0]?.error?.code ?? 'none',
      // SQLite/SQL API messages name a column, index or token; they never carry row values.
      message: String(payload?.errors?.[0]?.message ?? '').slice(0, 120) };
    throw Error('database request failed ' + JSON.stringify(detail));
  }
  return { results: payload.result[0].results, meta: payload.result[0].meta };
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const collection = process.argv.find(arg => arg.startsWith('--collection='))?.split('=')[1];
  try {
    if (!collection || !BULK_COLLECTIONS.has(collection)) throw Error('unsupported collection');
    const representations = (process.argv.find(arg => arg.startsWith('--representation='))?.split('=')[1] || '')
      .split(',').filter(Boolean);
    const result = await promoteCollection(remote, collection, { batch: 100, maxRows: 6000, maxReads: 400000,
      representationFields: representations, write: !process.argv.includes('--dry-run'),
      allowEncodingOnly: process.argv.includes('--allow-encoding-only') });
    console.log('::notice:: Root backfill ' + JSON.stringify(result));
    if (!process.argv.includes('--dry-run') && (result.after !== 0 || Object.keys(result.skipped).length))
      console.log('::warning:: Rows remain in the legacy fallback for this collection; the fallback stays.')
  } catch (error) {
    const known = ['unsupported collection', 'invalid batch size', 'inventory unavailable', 'read cap exceeded',
      'post-insert verification failed', 'unsafe plan length', 'missing credential'];
    const message = known.includes(error.message) ? error.message
      : error.message.startsWith('database request failed') ? error.message : 'preflight unavailable';
    console.error(`::error:: Safe root backfill stopped: ${message}`);
    process.exitCode = 1;
  }
}

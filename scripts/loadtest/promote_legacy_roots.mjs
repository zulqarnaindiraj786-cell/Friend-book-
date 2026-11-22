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

export function prepare(source) {
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
  if (!isDeepStrictEqual(toPlainDocument(source.document_json, source.doc_id), toPlainDocument(saved, source.doc_id)))
    return { status: 'skip', reason: 'plain_projection_diff', fields: differingFields(source.document_json, saved) };
  if (!isDeepStrictEqual(oldFields, toFirestoreFields(saved))) {
    const withoutId = { ...oldFields };
    delete withoutId.id;
    if (!(oldFields?.id?.stringValue === source.doc_id && isDeepStrictEqual(withoutId, toFirestoreFields(saved))))
      return { status: 'skip', reason: 'auth_projection_diff', fields: differingFields(source.document_json, saved) };
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
  };
}

// Field names only: enough to explain a skip without exposing a value.
function differingFields(fromJson, toJson) {
  const plain = value => {
    try { return JSON.parse(JSON.stringify(value ?? null)); } catch { return null; }
  };
  const left = plain(toPlainDocument(fromJson, ''));
  const right = plain(toPlainDocument(toJson, ''));
  const names = new Set();
  const walk = (a, b, prefix) => {
    if (isDeepStrictEqual(a, b)) return;
    if (a && b && typeof a === 'object' && typeof b === 'object' && Array.isArray(a) === Array.isArray(b)) {
      for (const key of new Set([...Object.keys(a), ...Object.keys(b)])) walk(a[key], b[key], prefix ? `${prefix}.${key}` : key);
      return;
    }
    names.add(prefix || '(document)');
  };
  walk(left, right, '');
  return [...names].slice(0, 8);
}

// One guarded statement per batch. Each row keeps its own source-race guard and
// its own NOT EXISTS guard, so a concurrent edit or a concurrent canonical
// write can only skip that single row, never write a stale copy.
export function batchStatement(planned) {
  if (!Array.isArray(planned) || !planned.length || planned.length > 100
      || planned.some(entry => entry?.status !== 'promote' || entry.row.collection_id !== planned[0].row.collection_id))
    throw Error('unsafe plan length');
  const values = [], selections = [], guards = [];
  for (const { row, source } of planned) {
    const slots = FIELDS.map(key => { values.push(row[key]); return `?${values.length}`; });
    const sourceSlots = ['owner_uid', 'document_json', 'updated_at', 'collection', 'doc_id'].map(key => {
      values.push(source[key]); return `?${values.length}`;
    });
    guards.push(`EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=${slots[0]} AND owner_uid=${sourceSlots[0]}`
      + ` AND document_json=${sourceSlots[1]} AND updated_at=${sourceSlots[2]} AND collection=${sourceSlots[3]}`
      + ` AND doc_id=${sourceSlots[4]}) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=${slots[0]})`);
    selections.push(`SELECT ${slots.join(',')} WHERE (${guards.at(-1)})`);
  }
  return {
    sql: `INSERT INTO friendbook_cf_firestore_documents(${FIELDS.join(',')}) ${selections.join(' UNION ALL ')}`
      + ' ON CONFLICT(path) DO NOTHING RETURNING path',
    params: values,
  };
}

export async function verifyBatch(query, planned) {
  for (const { row } of planned) {
    const got = (await query(`SELECT ${FIELDS.join(',')} FROM friendbook_cf_firestore_documents WHERE path=?`, [row.path])).results;
    if (got.length !== 1 || !FIELDS.every(key => isDeepStrictEqual(got[0][key], row[key])))
      throw Error('post-insert verification failed');
  }
}

/**
 * Promote every provably-equivalent legacy-only row of one collection.
 * Returns counts and the categories of rows that were deliberately skipped.
 */
export async function promoteCollection(query, collection, {
  batch = 100, maxRows = 6000, maxReads = 400000, maxBatches = 200,
} = {}) {
  if (!BULK_COLLECTIONS.has(collection)) throw Error('unsupported collection');
  if (!Number.isInteger(batch) || batch < 1 || batch > 100) throw Error('invalid batch size');
  const before = await legacyOnly(query, collection);
  let lastUpdated = -1, lastPath = "";
  let scanned = 0, inserted = 0, reads = 0, batches = 0;
  const skipped = {};
  let facades = 0;
  while (batches < maxBatches && scanned < maxRows) {
    const page = await query(PAGE, [collection, lastUpdated, lastUpdated, lastPath, Math.min(batch, maxRows - scanned)]);
    reads += Number.isSafeInteger(page.meta?.rows_read) ? page.meta.rows_read : 0;
    if (reads > maxReads) throw Error('read cap exceeded');
    const rows = page.results || [];
    if (!rows.length) break;
    batches++;
    const prepared = rows.map(prepare);
    rows.forEach((row, index) => {
      scanned++;
      lastUpdated = row.updated_at;
      lastPath = row.path;
      if (prepared[index].status !== 'promote') {
        const key = `${prepared[index].reason}${prepared[index].fields?.length ? ':' + prepared[index].fields.join(',') : ''}`;
        skipped[key] = (skipped[key] || 0) + 1;
      } else if (prepared[index].facadeChanged) facades++;
    });
    const planned = prepared.filter(entry => entry.status === 'promote');
    for (let start = 0; start < planned.length; start += 100) {
      const chunk = planned.slice(start, start + 100);
      const action = batchStatement(chunk);
      const done = (await query(action.sql, action.params)).results || [];
      await verifyBatch(query, chunk.filter(entry => done.some(hit => hit.path === entry.row.path)));
      inserted += done.length;
    }
    if (rows.length < batch) break;
  }
  const after = await legacyOnly(query, collection);
  return { collection, before, inserted, after, scanned, skipped, facades, reads, truncated: scanned >= maxRows };
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
      || !Array.isArray(payload.result[0].results)) throw Error('database request failed');
  return { results: payload.result[0].results, meta: payload.result[0].meta };
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const collection = process.argv.find(arg => arg.startsWith('--collection='))?.split('=')[1];
  try {
    if (!collection || !BULK_COLLECTIONS.has(collection)) throw Error('unsupported collection');
    const result = await promoteCollection(remote, collection, { batch: 100, maxRows: 6000, maxReads: 400000 });
    console.log('::notice:: Root backfill ' + JSON.stringify(result));
    if (result.after !== 0 || Object.keys(result.skipped).length)
      console.log('::warning:: Rows remain in the legacy fallback for this collection; the fallback stays.')
  } catch (error) {
    console.error(`::error:: Safe root backfill stopped: ${['unsupported collection', 'invalid batch size', 'inventory unavailable',
      'read cap exceeded', 'post-insert verification failed', 'unsafe plan length',
      'missing credential', 'database request failed'].includes(error.message) ? error.message : 'preflight unavailable'}`);
    process.exitCode = 1;
  }
}

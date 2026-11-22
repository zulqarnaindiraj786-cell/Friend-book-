// Strict, bounded and race-guarded promotion of at most two legacy rows from
// an explicitly allowlisted root collection. No nested-message cutover.
// Never logs paths, profile contents, authorization headers, or SQL parameters.
import { isDeepStrictEqual } from 'node:util';
import { appDocumentToFirestoreRow } from '../../cloudflare/firestore-documents.js';
import { toFirestoreFields, toPlainDocument } from '../../cloudflare/app-documents.js';

const INVENTORY = `WITH sample AS MATERIALIZED (
 SELECT path FROM friendbook_cf_documents INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE collection=? LIMIT 1001
) SELECT COUNT(*) AS sampled, COALESCE(SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END),0) AS legacy_only
 FROM sample LEFT JOIN friendbook_cf_firestore_documents f ON f.path=sample.path`;
const CANDIDATES = `SELECT l.path,l.collection,l.doc_id,l.owner_uid,l.document_json,l.updated_at
 FROM friendbook_cf_documents l INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE l.collection=? AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)
 LIMIT 3`;
const FIELDS = ['path','parent_path','collection_id','doc_id','owner_uid','document_json','create_time','update_time'];

const PROMOTION_COLLECTIONS = new Set(['users','friend_requests']);
export function planCandidate(source, collection='users') {
  if (!PROMOTION_COLLECTIONS.has(collection) || !source || source.collection !== collection || typeof source.path !== 'string'
      || source.path !== `${collection}/${source.doc_id}` || !source.doc_id || source.doc_id.includes('/')
      || typeof source.owner_uid !== 'string' || typeof source.document_json !== 'string'
      || source.document_json.length > 100_000 || !Number.isSafeInteger(source.updated_at))
    throw Error('unsafe source shape');
  const row = appDocumentToFirestoreRow(source);
  if (!row || !Number.isSafeInteger(row.create_time) || !Number.isSafeInteger(row.update_time))
    throw Error('unsafe normalized metadata');
  // The legacy document API can expose a redundant id field matching the path.
  // Preserve it in canonical storage instead of silently deleting source data.
  // The Firestore facade previously stripped this redundant field for legacy
  // rows, so callers may now see it in data in addition to the document ID.
  const oldFields=toFirestoreFields(source.document_json);
  if (oldFields?.id?.stringValue === source.doc_id) {
    const data=JSON.parse(row.document_json); data.id=source.doc_id;
    row.document_json=JSON.stringify(data);
  }
  // Require equivalent values for the old document and authorization APIs.
  const newFields=toFirestoreFields(row.document_json);
  const plainEqual=isDeepStrictEqual(toPlainDocument(source.document_json, source.doc_id), toPlainDocument(row.document_json, source.doc_id));
  if (!isDeepStrictEqual(oldFields, newFields) || !plainEqual) {
    const oldWithoutId={...oldFields}; delete oldWithoutId.id;
    if (plainEqual && oldFields?.id?.stringValue===source.doc_id && isDeepStrictEqual(oldWithoutId,newFields))
      throw Error('legacy id projection differs');
    throw Error(plainEqual ? 'typed field projection differs' : 'plain document projection differs');
  }
  const data=JSON.parse(row.document_json);
  if (collection==='users') for (const key of ['followers','following'])
    if (Array.isArray(data[key]) && data[key].length > 20) throw Error('follow-index fanout cap');
  return {source,row};
}

export async function readPlan(query, collection='users') {
  if (!PROMOTION_COLLECTIONS.has(collection)) throw Error('unsupported collection');
  const inventory = (await query(INVENTORY, [collection])).results;
  if (inventory.length!==1 || !Number.isInteger(inventory[0].sampled) || inventory[0].sampled>=1001
      || !Number.isInteger(inventory[0].legacy_only) || inventory[0].legacy_only<0 || inventory[0].legacy_only>2)
    throw Error('user inventory unsafe');
  const {sampled,legacy_only} = inventory[0];
  const sources = (await query(CANDIDATES, [collection])).results;
  if (sources.length!==legacy_only) throw Error('inventory changed');
  const planned = sources.map(source=>planCandidate(source,collection));
  return {sampled,legacy_only,planned,collection};
}

// All candidates share a guard evaluated in the same SQL statement as insert.
// Any changed source or newly created canonical row prevents *all* inserts.
export function statement(planned) {
  if (!planned.length || planned.length > 2 || !PROMOTION_COLLECTIONS.has(planned[0]?.source?.collection)
      || planned.some(p=>p.source.collection!==planned[0].source.collection)) throw Error('unsafe plan length');
  const values=[], selections=[], guards=[];
  for (const {source,row} of planned) {
    const slots=FIELDS.map(k => { values.push(row[k]); return `?${values.length}`; });
    const sourceSlots=['owner_uid','document_json','updated_at','collection'].map(k=>{values.push(source[k]); return `?${values.length}`;});
    guards.push(`EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=${slots[0]} AND collection=${sourceSlots[3]} AND doc_id=${slots[3]} AND owner_uid=${sourceSlots[0]} AND document_json=${sourceSlots[1]} AND updated_at=${sourceSlots[2]}) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=${slots[0]})`);
    selections.push(slots.join(','));
  }
  const condition=guards.map(g=>`(${g})`).join(' AND ');
  return {sql:`INSERT INTO friendbook_cf_firestore_documents(${FIELDS.join(',')}) ` +
    selections.map(values=>`SELECT ${values} WHERE ${condition}`).join(' UNION ALL ') +
    ' ON CONFLICT(path) DO NOTHING RETURNING path', params:values};
}

export async function promote(query, plan) {
  if (!plan.planned.length) return {before:0, inserted:0, after:0};
  const {sql,params}=statement(plan.planned);
  const inserted=(await query(sql,params)).results;
  if (inserted.length!==plan.planned.length) throw Error('atomic guard changed; verification required');
  for (const {row} of plan.planned) {
    const got=(await query(`SELECT ${FIELDS.join(',')} FROM friendbook_cf_firestore_documents WHERE path=?`,[row.path])).results;
    if (got.length!==1 || !FIELDS.every(key=>isDeepStrictEqual(got[0][key],row[key])))
      throw Error('post-insert verification failed');
  }
  const after=await readPlan(query,plan.collection);
  if (after.legacy_only!==0) throw Error('coverage changed during verification');
  return {before:plan.legacy_only, inserted:inserted.length, after:after.legacy_only};
}

// Read-only, bounded classification of root rows. This is a moving scan, not
// a snapshot or authorization to write. In particular, root messages are also
// projected into nested chat paths by a separate compatibility bridge.
const ROOT_NAMES = new Set(['users','posts','reels','stories','chats','messages','friends','friend_requests',
  'follows','groups','pages','notifications','official_broadcasts','officialBroadcasts','lives','pokes',
  'payments','pushDevices','reports','feedback','config','events','gifts','referrals','subscriptions',
  'comments','likes','followers','following','saves','reactions','crashReports','sessions','chat']);
const PREVIEW_SQL = `SELECT l.path,l.collection,l.doc_id,l.owner_uid,l.document_json,l.updated_at
 FROM friendbook_cf_documents l WHERE l.path>? AND NOT EXISTS
 (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)
 ORDER BY l.path LIMIT ?`;

export function classifyRoot(source) {
  if (!source || typeof source.collection!=='string' || !source.collection || source.collection.includes('/')
      || typeof source.doc_id!=='string' || !source.doc_id || source.doc_id.includes('/')
      || source.path!==`${source.collection}/${source.doc_id}` || typeof source.owner_uid!=='string'
      || typeof source.document_json!=='string' || source.document_json.length>100000
      || !Number.isSafeInteger(source.updated_at)) return 'unsafe_shape';
  let normalized;
  try { normalized=appDocumentToFirestoreRow(source); } catch { return 'unsafe_shape'; }
  if (!normalized || !Number.isSafeInteger(normalized.create_time) || !Number.isSafeInteger(normalized.update_time)) return 'unsafe_shape';
  const oldFields=toFirestoreFields(source.document_json);
  const candidate=JSON.parse(normalized.document_json);
  if (oldFields?.id?.stringValue===source.doc_id) candidate.id=source.doc_id;
  const saved=JSON.stringify(candidate);
  if (!isDeepStrictEqual(toPlainDocument(source.document_json,source.doc_id),toPlainDocument(saved,source.doc_id))) return 'plain_projection_diff';
  if (!isDeepStrictEqual(oldFields,toFirestoreFields(saved))) return 'auth_projection_diff';
  // Check fanout before facade differences so an id field cannot mask a risky
  // membership trigger. These limits are risk controls, not billing estimates.
  if (source.collection==='users' && ['followers','following'].some(key=>Array.isArray(candidate[key]) && candidate[key].length>20)) return 'fanout_limit';
  if (source.collection==='chats' && Array.isArray(candidate.participants) && candidate.participants.length>20) return 'fanout_limit';
  if (!isDeepStrictEqual(JSON.parse(normalized.document_json),candidate)) return 'facade_projection_diff';
  return 'equivalent_root_only';
}

export async function previewRoots(query, max=10000, batch=30) {
  if (!Number.isInteger(max) || max<1 || max>10000 || !Number.isInteger(batch) || batch<1 || batch>30) throw Error('invalid preview bounds');
  let after='', sampled=0, reads=0, exhausted=false;
  const collections={};
  while (sampled<max) {
    const result=await query(PREVIEW_SQL,[after,Math.min(batch,max-sampled)]);
    if (!Array.isArray(result.results) || !Number.isSafeInteger(result.meta?.rows_read) || result.meta.rows_read<0) throw Error('preview accounting unavailable');
    reads+=result.meta.rows_read;
    if (reads>150000) throw Error('preview read cap exceeded');
    if (!result.results.length) { exhausted=true; break; }
    for (const source of result.results) {
      if (typeof source.path!=='string' || source.path<=after) throw Error('preview order invalid');
      after=source.path; sampled++;
      const collection=ROOT_NAMES.has(source.collection)?source.collection:'other';
      const summary=collections[collection] ||= {};
      const category=classifyRoot(source);
      summary[category]=(summary[category]||0)+1;
    }
    if (result.results.length<Math.min(batch,max-(sampled-result.results.length))) { exhausted=true; break; }
  }
  return {collections,sampled,truncated:!exhausted,rows_read:reads};
}

async function remote(sql,params) {
  const token=process.env.CLOUDFLARE_API_TOKEN;
  if (!token) throw Error('missing credential');
  const response=await fetch('https://api.cloudflare.com/client/v4/accounts/92f3d04598822de2e49e14561218d090/d1/database/e1c605ca-a50b-46c6-9deb-56dd1f70d163/query', {
    method:'POST',headers:{authorization:`Bearer ${token}`,'content-type':'application/json'},body:JSON.stringify({sql,params}),signal:AbortSignal.timeout(45000)});
  const payload=await response.json();
  if (!response.ok || !payload?.success || payload.result?.length!==1 || payload.result[0]?.success!==true || !Array.isArray(payload.result[0].results))
    throw Error('database request failed');
  return payload.result[0];
}

if (process.argv[1] && import.meta.url===new URL(`file://${process.argv[1]}`).href) {
  try {
    if (process.argv.includes('--preview-roots')) {
      if (process.argv.includes('--apply')) throw Error('invalid preview bounds');
      const result=await previewRoots(remote);
      console.log('::notice:: Moving read-only root preview '+JSON.stringify(result));
      console.log('::warning:: Root preview does not verify nested-chat message coverage or enable cutover.');
    } else {
      const collection=process.argv.includes('--friend-requests')?'friend_requests':'users';
      const plan=await readPlan(remote,collection);
      console.log(`::notice:: Verified ${collection} candidate plan: sampled=${plan.sampled}, legacy_only_before=${plan.legacy_only}`);
      if (process.argv.includes('--apply')) {
        const result=await promote(remote,plan);
        console.log(`::notice:: ${collection} backfill verification: legacy_only_before=${result.before}, inserted=${result.inserted}, legacy_only_after=${result.after}`);
      } else console.log('::notice:: Read-only preview; no documents changed.');
    }
  } catch (error) {
    // Do not include raw database/provider errors: they may contain profile data.
    console.error(`::error:: Safe backfill stopped: ${['unsafe source shape','unsafe normalized metadata','legacy id projection differs','typed field projection differs','plain document projection differs','follow-index fanout cap','user inventory unsafe','inventory changed','atomic guard changed; verification required','post-insert verification failed','coverage changed during verification','invalid preview bounds','preview accounting unavailable','preview read cap exceeded','preview order invalid'].includes(error.message) ? error.message : 'preflight unavailable'}`);
    process.exitCode=1;
  }
}

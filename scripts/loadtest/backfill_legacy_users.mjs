// Strict, bounded and race-guarded promotion of at most two legacy user rows.
// Never logs paths, profile contents, authorization headers, or SQL parameters.
import { isDeepStrictEqual } from 'node:util';
import { appDocumentToFirestoreRow } from '../../cloudflare/firestore-documents.js';
import { toFirestoreFields, toPlainDocument } from '../../cloudflare/app-documents.js';

const INVENTORY = `WITH sample AS MATERIALIZED (
 SELECT path FROM friendbook_cf_documents INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE collection='users' LIMIT 1001
) SELECT COUNT(*) AS sampled, COALESCE(SUM(CASE WHEN f.path IS NULL THEN 1 ELSE 0 END),0) AS legacy_only
 FROM sample LEFT JOIN friendbook_cf_firestore_documents f ON f.path=sample.path`;
const CANDIDATES = `SELECT l.path,l.collection,l.doc_id,l.owner_uid,l.document_json,l.updated_at
 FROM friendbook_cf_documents l INDEXED BY idx_friendbook_cf_documents_collection_updated
 WHERE l.collection='users' AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=l.path)
 LIMIT 3`;
const FIELDS = ['path','parent_path','collection_id','doc_id','owner_uid','document_json','create_time','update_time'];

export function planCandidate(source) {
  if (!source || source.collection !== 'users' || typeof source.path !== 'string'
      || source.path !== `users/${source.doc_id}` || !source.doc_id || source.doc_id.includes('/')
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
  for (const key of ['followers','following'])
    if (Array.isArray(data[key]) && data[key].length > 20) throw Error('follow-index fanout cap');
  return {source,row};
}

export async function readPlan(query) {
  const inventory = (await query(INVENTORY, [])).results;
  if (inventory.length!==1 || !Number.isInteger(inventory[0].sampled) || inventory[0].sampled>=1001
      || !Number.isInteger(inventory[0].legacy_only) || inventory[0].legacy_only<0 || inventory[0].legacy_only>2)
    throw Error('user inventory unsafe');
  const {sampled,legacy_only} = inventory[0];
  const sources = (await query(CANDIDATES, [])).results;
  if (sources.length!==legacy_only) throw Error('inventory changed');
  const planned = sources.map(planCandidate);
  return {sampled,legacy_only,planned};
}

// All candidates share a guard evaluated in the same SQL statement as insert.
// Any changed source or newly created canonical row prevents *all* inserts.
export function statement(planned) {
  if (!planned.length || planned.length > 2) throw Error('unsafe plan length');
  const values=[], selections=[], guards=[];
  for (const {source,row} of planned) {
    const slots=FIELDS.map(k => { values.push(row[k]); return `?${values.length}`; });
    const sourceSlots=['owner_uid','document_json','updated_at'].map(k=>{values.push(source[k]); return `?${values.length}`;});
    guards.push(`EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=${slots[0]} AND collection='users' AND doc_id=${slots[3]} AND owner_uid=${sourceSlots[0]} AND document_json=${sourceSlots[1]} AND updated_at=${sourceSlots[2]}) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=${slots[0]})`);
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
  const after=await readPlan(query);
  if (after.legacy_only!==0) throw Error('coverage changed during verification');
  return {before:plan.legacy_only, inserted:inserted.length, after:after.legacy_only};
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
    const plan=await readPlan(remote);
    console.log(`::notice:: Verified candidate plan: sampled=${plan.sampled}, legacy_only_before=${plan.legacy_only}`);
    if (process.argv.includes('--apply')) {
      const result=await promote(remote,plan);
      console.log(`::notice:: User backfill verification: legacy_only_before=${result.before}, inserted=${result.inserted}, legacy_only_after=${result.after}`);
    } else console.log('::notice:: Read-only preview; no documents changed.');
  } catch (error) {
    // Do not include raw database/provider errors: they may contain profile data.
    console.error(`::error:: Safe backfill stopped: ${['unsafe source shape','unsafe normalized metadata','legacy id projection differs','typed field projection differs','plain document projection differs','follow-index fanout cap','user inventory unsafe','inventory changed','atomic guard changed; verification required','post-insert verification failed','coverage changed during verification'].includes(error.message) ? error.message : 'preflight unavailable'}`);
    process.exitCode=1;
  }
}

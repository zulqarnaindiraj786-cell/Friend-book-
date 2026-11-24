import { inboxSqlActivity } from './inbox-activity-sql.js';
import { recordInboxSortCost, inboxSeekSize, suspendInboxSeek } from './inbox-plan-hints.js';
import { writeAttribution } from './write-attribution.js';
import { accountStatus } from './account-access.js';
import { pushCommittedChat } from './fcm-push.js';
import { profileAudience } from './profile-audience.js';
import { mapBounded } from './map-bounded.js';
import { engagementSummary, batchEngagementReads, MAX_FEED_ENGAGEMENT_PAGE } from './interactions.js';
import { requestAccountReads, readD1DocumentFields, toFirestoreFields, fromFirestoreValue } from './app-documents.js';
import { callAccountsAllowed, readCallProfile } from './call-access.js';
import { authorizeLiveMedia, authorizeLiveRoom } from './live-access.js';
import { canReadPayment, canCreateFreeReview, isPaymentAdmin } from './payment-access.js';
import { canReadFriendRequest, canCreateFriendRequest, canDeleteFriendRequest, friendshipReadView, canCreateGroup, canUpdateGroup, canManageGroup, canSelfManageGroup, isSelfArrayChange, groupReadView, trustedGroupOwner } from './social-access.js';
import { supportedDocumentPath, supportedCollectionPath, contentReadAllowed, contentWriteAllowed } from './document-policy.js';
import { friendshipCandidates, friendshipDuplicateGuard } from './friendship-candidates.js';
import { chatReadAllowed, chatWriteAllowed, storedChatMember } from './chat-access.js';
import { profileReadView, profileWriteAllowed } from './profile-access.js';
import { canReadNotification, notificationWriteAllowed } from './notification-access.js';
import { canReadReports, reportForCreate, feedbackForCreate } from './report-access.js';
// Cloudflare D1 document API (/v1/d1/*). Android uses a D1-backed document adapter
// authenticated with Firebase Auth tokens; Firebase stores Auth + FCM only.

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

function validSegment(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= 512
    && !/[\\/\x00-\x1f]/.test(v) && !['.', '..'].includes(v);
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
function isRootDocument(path) {
  const parts = splitPath(path);
  return Boolean(parts && parts.length === 2);
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
  const v = d.authorId || d.uid || d.senderId || d.from || d.toUserId || d.toUid || d.userId || d.creatorId || d.hostId || d.ownerId;
  return typeof v === 'string' && v ? v : (fallback || '');
}

// ---------- D1 helpers ----------
export function appDocumentToFirestoreRow(row) {
  if (!row) return null;
  let doc;
  try { doc = JSON.parse(row.document_json); } catch { return null; }
  // Historical app-document rows can contain Firestore REST value envelopes. Normalize those
  // into the same plain JSON shape used by current D1DocumentStore writes.
  const decodeStored = value => {
    if (value === null || value === undefined) return null;
    if (Array.isArray(value)) return value.map(decodeStored);
    if (typeof value !== 'object') return value;
    if ('stringValue' in value) return String(value.stringValue);
    if ('booleanValue' in value) return Boolean(value.booleanValue);
    if ('integerValue' in value) return Number(value.integerValue);
    if ('doubleValue' in value) return Number(value.doubleValue);
    if ('timestampValue' in value) return { __type: 'timestamp', millis: Date.parse(value.timestampValue) || 0 };
    if ('nullValue' in value) return null;
    if ('arrayValue' in value) return (value.arrayValue?.values || []).map(decodeStored);
    if ('mapValue' in value) return decodeFields(value.mapValue?.fields || {});
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, decodeStored(item)]));
  };
  const decodeFields = fields => Object.fromEntries(Object.entries(fields || {}).map(([key, value]) => [key, decodeStored(value)]));
  const sourceCreatedAt=typeof doc?.createTime==='string' ? Date.parse(doc.createTime) : NaN;
  if (doc?.fields && typeof doc.fields === 'object' && !Array.isArray(doc.fields)) doc = decodeFields(doc.fields);
  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) return null;
  const parts = String(row.path || '').split('/');
  if (parts.length !== 2) return null;
  if (parts[0]==='posts' && (doc.createdAt===undefined || doc.createdAt===null) && Number.isSafeInteger(sourceCreatedAt)) {
    doc.createdAt={__type:'timestamp',millis:sourceCreatedAt};
  }
  delete doc.id; // Firestore document identity is its path/ID, not an extra payload field.
  return {
    path: row.path, parent_path: parts[0], collection_id: row.collection,
    doc_id: row.doc_id, owner_uid: row.owner_uid || '', document_json: JSON.stringify(doc),
    create_time: Number(doc.createdAt) || 0, update_time: Number(row.updated_at) || 0,
  };
}

// Internal source marker; not serialized in any document response.
const canonicalRootSnapshot=Symbol('canonicalRootSnapshot');
const canonicalContentSnapshot=Symbol('canonicalContentSnapshot');
// Only call with rows directly selected from canonical storage, before projection.
function canonicalContentDocument(row) {
  const doc=rowToDocument(row);
  const parts=doc && splitPath(doc.path);
  if(doc && parts?.length===2 && ['posts','reels','stories'].includes(parts[0])
      && doc.parent===parts[0] && doc.path===`${doc.parent}/${doc.id}`)
    doc[canonicalContentSnapshot]=toFirestoreFields(row.document_json);
  return doc;
}
async function readRootStorageRow(db,path) {
  // One indexed canonical lookup; a mirror-only row is not a document.
  return db.prepare(`SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time
    FROM friendbook_cf_firestore_documents WHERE path = ?1
    LIMIT 1`).bind(path).first();
}
function normalizeRootStorageRow(row) {
  if (!row) return null;
  return {...row,[canonicalRootSnapshot]:true};
}

export async function readRow(db, path) {
  if (!isRootDocument(path)) {
    return db.prepare('SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).first();
  }
  return normalizeRootStorageRow(await readRootStorageRow(db,path));
}

// Only the trusted Worker entry point creates this request-local snapshot.
export async function readProfileMutationSnapshot(db,uid) {
  const accountRow=await readRootStorageRow(db,`users/${uid}`);
  return {accountRow, profileRow:normalizeRootStorageRow(accountRow)};
}

// Match decodeValue + the historical inbox projection exactly (including zero
// falling through to the next timestamp representation).
function inboxActivity(data) {
  const millis=value=>typeof value==='number' && Number.isFinite(value) ? value
    : value?.__type==='timestamp' && Number.isFinite(value.millis) ? value.millis : 0;
  return millis(data.lastMessageTime)||millis(data.lastMessageTimeMs)||millis(data.lastMessageAt);
}


// Choose a bounded shared-index head only after measured sort cost justifies
// it; otherwise retain membership-first paging. No per-member activity table
// or message-write fanout is introduced. The compatibility plan still sorts.
async function boundedInboxPage(db,uid,spec,requestUserReads) {
  if(spec?.parent!=='chats' || !Number.isInteger(spec.limit) || spec.limit<1 || spec.limit>100
    || spec.limitToLast || spec.orderBy?.length!==1 || spec.orderBy[0]?.field!=='inboxActivityAt'
    || !['ASCENDING','DESCENDING'].includes(spec.orderBy[0].direction)
    || (spec.filters||[]).some(f=>f.field!=='participants' || f.op!=='array-contains' || f.value!==uid))return null;
  for(const key of ['startAfter','startAt','endBefore','endAt']) {
    const c=spec[key];
    if(c!==undefined && (!Array.isArray(c) || c.length>2
      || (c.length && (typeof c[0]!=='number' || !Number.isFinite(c[0]) || Math.abs(c[0])>Number.MAX_SAFE_INTEGER))
      || (c.length===2 && (typeof c[1]!=='string' || /[^\x20-\x7e]/.test(c[1])))))return null;
  }
  const desc=spec.orderBy[0].direction==='DESCENDING', direction=desc?'DESC':'ASC';
  const predicates=[],bindings=[uid];
  function bound(cursor,start,inclusive) {
    if(!cursor?.length)return;
    const op=(start!==desc)?'>':'<';
    if(cursor.length===1){predicates.push(`inbox_activity ${op}${inclusive?'=':''} ?`);bindings.push(cursor[0]);}
    else {predicates.push(`(inbox_activity ${op}= ? AND (inbox_activity ${op} ? OR (inbox_activity=? AND doc_id ${op}${inclusive?'=':''} ?)))`);bindings.push(cursor[0],cursor[0],cursor[0],cursor[1]);}
  }
  bound(spec.startAfter,true,false);bound(spec.startAt,true,true);
  bound(spec.endBefore,false,false);bound(spec.endAt,false,true);
  // Only try the shared ordered stream after actual D1 metadata has shown
  // this actor's membership sort to be expensive. No extra count/probe query
  // for small inboxes. The hint never substitutes for current document ACLs.
  const seekSize=inboxSeekSize(db,uid,spec.limit);
  if(seekSize) {
    try {
      // Do not materialize another actor's chat JSON merely to inspect the
      // shared ordered head. The same source-1 membership check gates JSON;
      // authoritative canonical membership is still rechecked below.
      const memberSql=`EXISTS(SELECT 1 FROM friendbook_cf_chat_participants m
        WHERE m.participant_uid=?1 AND m.chat_path=f.path AND m.source=1)`;
      const head=await db.prepare(`SELECT f.path,f.parent_path,f.collection_id,f.doc_id,
        f.owner_uid,f.create_time,f.update_time,
        CASE WHEN ${memberSql} THEN f.document_json ELSE '{}' END AS document_json,
        CAST((${inboxSqlActivity}) AS REAL) AS inbox_activity,
        ${memberSql} AS inbox_member
        FROM friendbook_cf_firestore_documents f INDEXED BY idx_cf_chat_activity_seek
        WHERE f.path='chats/'||f.doc_id
          ${predicates.map(p=>' AND '+p.replaceAll('inbox_activity',`(${inboxSqlActivity})`)).join('')}
        ORDER BY (${inboxSqlActivity}) ${direction},doc_id ${direction} LIMIT ?`)
        .bind(uid,...bindings.slice(1),seekSize).all();
      const rows=head.results||[];
      if(rows.every(row=>Math.abs(row.inbox_activity)<=Number.MAX_SAFE_INTEGER && /^[\x20-\x7e]+$/.test(row.doc_id) && row.path===`chats/${row.doc_id}` && isDocumentPath(row.path))) {
        const memberRows=rows.filter(row=>row.inbox_member===1);
        const docs=memberRows.map(row=>rowToDocument({...row,[canonicalRootSnapshot]:true}));
        if(docs.every((doc,i)=>inboxActivity(doc.data)===memberRows[i].inbox_activity)) {
          const visible=await visibleDocuments(db,uid,'chats',prefilterRaw(docs,'chats',spec.filters),requestUserReads);
          if(visible.length>=spec.limit || rows.length<seekSize)
            return applyQuery(visible.map(doc=>({...doc,data:{...doc.data,inboxActivityAt:inboxActivity(doc.data)}})),spec);
        }
      }
      // A sparse user's index attempt must not add a bounded unrelated head to
      // every request. Retain the exact membership query during cooldown.
      suspendInboxSeek(db,uid);
    } catch(error) {
      if(!/no such index: idx_cf_chat_activity_seek/.test(String(error)))throw error;
      suspendInboxSeek(db,uid);
    }
  }
  const allowed=[];let after=null,batches=0;
  while(allowed.length<spec.limit) {
    // A badly stale membership index must not cause repeated whole-inbox sorts.
    if(batches++===4)return null;
    const extra=after?` AND (inbox_activity ${desc?'<':'>'} ? OR (inbox_activity=? AND doc_id ${desc?'<':'>'} ?))`:'';
    const size=after?Math.max(20,spec.limit-allowed.length):spec.limit;
    let result;
    try {
      result=await db.prepare(`WITH quota_inbox_candidates AS (
        SELECT f.*, CAST((${inboxSqlActivity}) AS REAL) AS inbox_activity
        FROM friendbook_cf_chat_participants m INDEXED BY idx_cf_chat_participant_source
        JOIN friendbook_cf_firestore_documents f ON f.path=m.chat_path
        WHERE m.participant_uid=? AND m.source=1)
        SELECT * FROM quota_inbox_candidates WHERE 1=1 ${predicates.map(p=>' AND '+p).join('')}${extra}
        ORDER BY inbox_activity ${direction},doc_id ${direction} LIMIT ?`)
        .bind(...bindings,...(after?[after.time,after.time,after.id]:[]),size).all();
    } catch(error) {
      if(/no such (?:index: idx_cf_chat_participant_source|table: friendbook_cf_chat_participants)/.test(String(error)))return null;
      throw error;
    }
    recordInboxSortCost(db,uid,result.meta);
    const rows=result.results||[];
    // SQLite UTF-8 and JS UTF-16 can disagree amongst non-ASCII ID ties.
    // ASCII candidates cannot be displaced by those ties; otherwise preserve
    // the full compatibility evaluator rather than silently skipping a room.
    if(rows.some(row=>Math.abs(row.inbox_activity)>Number.MAX_SAFE_INTEGER || /[^\x20-\x7e]/.test(row.doc_id) || row.parent_path!=='chats'
      || row.path!==`chats/${row.doc_id}` || !isDocumentPath(row.path)))return null;
    const docs=rows.map(row=>rowToDocument({...row,[canonicalRootSnapshot]:true}));
    if(docs.some((doc,i)=>inboxActivity(doc.data)!==rows[i].inbox_activity))return null;
    const visible=await visibleDocuments(db,uid,'chats',prefilterRaw(docs,'chats',spec.filters),requestUserReads);
    allowed.push(...visible.map(doc=>({...doc,data:{...doc.data,inboxActivityAt:inboxActivity(doc.data)}})));
    if(rows.length<size)break;
    const tail=rows.at(-1);after={time:tail.inbox_activity,id:tail.doc_id};
  }
  return applyQuery(allowed,spec);
}

// A bounded fast path for ordinary canonical chat pages. Unusual historical timestamp
// formats, root-message bridges, filters and complex cursors use the full evaluator.
const chatRank = `CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END`;
const chatTime = `CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END`;
async function boundedChatRows(db,parent,spec) {
  const parts=splitPath(parent), orders=spec?.orderBy;
  if (parts?.length!==3 || parts[0]!=='chats' || parts[2]!=='messages'
      || !Array.isArray(orders) || orders.length!==1 || orders[0]?.field!=='createdAt'
      || !['ASCENDING','DESCENDING'].includes(orders[0]?.direction)
      || (Array.isArray(spec?.filters) && spec.filters.length) || spec?.startAt || spec?.endAt
      || !Number.isInteger(spec?.limit) || spec.limit<=0 || spec.limit>1000
      || (spec.limitToLast!==undefined && spec.limitToLast!==true && spec.limitToLast!==false)) return null;
  const parseCursor=c=>{
    if (!Array.isArray(c) || c.length!==2 || typeof c[1]!=='string' || !/^[\x20-\x7e]+$/.test(c[1])) return null;
    const value=c[0], rank=typeof value==='number'?2:value?.__type==='timestamp'?4:0;
    const time=rank===2?value:value?.millis;
    if (!rank || !Number.isFinite(time) || Math.abs(time)>Number.MAX_SAFE_INTEGER || rank===4 && !Number.isInteger(time)) return null;
    return [rank,time,c[1]];
  };
  const after=spec.startAfter ? parseCursor(spec.startAfter) : null;
  const before=spec.endBefore ? parseCursor(spec.endBefore) : null;
  if ((spec.startAfter && (!after || spec.limitToLast)) || (spec.endBefore && !before))return null;
  const prefix=`${parts[1]}_`, upper=`${parts[1]}\``;
  // Both compatibility probes are canonical-only; wait once before the page read.
  const [rootMessages,bad]=await Promise.all([
    db.prepare(`SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path='messages' AND doc_id>=? AND doc_id<?
    UNION ALL SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path='messages' AND json_extract(document_json,'$.chatId')=? LIMIT 1`).bind(prefix,upper,parts[1]).first(),
  // Ensure SQLite's ordering matches the existing JS comparator exactly. Older or
  // malformed records must never be dropped simply to make the page smaller.
    db.prepare(`SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path=? AND parent_path GLOB 'chats/*/messages' AND (
    doc_id GLOB '*[^ -~]*' OR COALESCE((
      (json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
      OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
        AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)
    ),0)=0) LIMIT 1`).bind(parent).first(),
  ]);
  if (rootMessages || bad) return null;
  const descending=orders[0].direction==='DESCENDING';
  const takeLast=spec.limitToLast===true;
  // Ordinary older-message pages have a single descending cursor. SQLite may
  // otherwise walk from the newest message even with the expression index.
  // Seek the cursor rank/time and include a bounded lower numeric-rank branch
  // in the same statement; mixed/legacy messages already used the fallback.
  if (after && !before && descending && !takeLast) {
    const base=`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time,
      ${chatRank} AS quota_chat_rank,${chatTime} AS quota_chat_time
      FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_firestore_chat_created_page WHERE parent_path=?`;
    const same=`${base} AND (${chatRank})=? AND (${chatTime})<=? AND (${chatTime},doc_id)<(?,?)
      ORDER BY ${chatTime} DESC,doc_id DESC LIMIT ?`;
    try {
      if (after[0]===2) return (await db.prepare(same).bind(parent,2,after[1],after[1],after[2],spec.limit).all())?.results || [];
      const lower=`${base} AND (${chatRank})=? ORDER BY ${chatTime} DESC,doc_id DESC LIMIT ?`;
      return (await db.prepare(`SELECT * FROM (${same}) UNION ALL SELECT * FROM (${lower})
        ORDER BY quota_chat_rank DESC,quota_chat_time DESC,doc_id DESC LIMIT ?`)
        .bind(parent,4,after[1],after[1],after[2],spec.limit,parent,2,spec.limit,spec.limit).all())?.results || [];
    } catch(error) {
      if (!String(error).includes('no such index: idx_cf_firestore_chat_created_page')) throw error;
      // Old schemas retain the existing non-hinted page query below.
    }
  }
  const direction=(descending !== takeLast)?'DESC':'ASC';
  const args=[parent];
  let boundary='';
  if(after && before && after[0]===before[0]) {
    // Both edges constrain the same sort rank. Expose the timestamp range
    // explicitly so SQLite seeks to the gap instead of walking newer rows.
    boundary+=` AND (${chatRank})=? AND (${chatTime}) BETWEEN ? AND ?`;
    args.push(after[0],Math.min(after[1],before[1]),Math.max(after[1],before[1]));
  }
  if(after){boundary+=` AND (${chatRank},${chatTime},doc_id) ${descending?'<':'>'} (?,?,?)`;args.push(...after);}
  if(before){boundary+=` AND (${chatRank},${chatTime},doc_id) ${descending?'>':'<'} (?,?,?)`;args.push(...before);}
  const statement=db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
    FROM friendbook_cf_firestore_documents WHERE parent_path=?${boundary}
    ORDER BY ${chatRank} ${direction}, ${chatTime} ${direction}, doc_id ${direction} LIMIT ?`);
  const result=await statement.bind(...args,spec.limit).all();
  return result?.results || [];
}

// Participant and status filters must run before loading historical request payloads.
async function filteredRequestRows(db,uid,filters) {
  if (!Array.isArray(filters)) return null;
  const eq=field=>filters.find(f=>f.field===field && f.op==='==' && typeof f.value==='string');
  const status=eq('status');
  const own=['toUid','fromUid'].find(field=>eq(field)?.value===uid);
  if (!own || !status) return null;
  const other=own==='toUid'?'fromUid':'toUid';
  const peer=eq(other);
  const candidates=filters.find(f=>f.field===other && f.op==='in' && Array.isArray(f.value)
      && f.value.length>0 && f.value.length<=20 && f.value.every(v=>typeof v==='string'));
  const ids=peer?[peer.value]:candidates?.value || [];
  const expr=field=>`json_extract(document_json,'$.${field}')`;
  const predicate=`${expr(own)}=? AND ${expr('status')}=?`
    +(ids.length ? ` AND ${expr(other)} IN (${ids.map(()=>'?').join(',')})` : '');
  const values=[uid,status.value,...ids];
  const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND ${predicate}`).bind(...values).all();
  return [...(canonical.results||[])];
}

// Live chat polling normally asks for the last 100 messages. The compatibility
// probe keeps old timestamp encodings and non-ASCII tie ordering on the old path.
async function boundedLiveChatRows(db,parent,spec) {
  const order=spec.orderBy?.[0];
  if (!/^lives\/[^/]+\/chat$/.test(parent) || spec.orderBy?.length!==1 || order?.field!=='createdAtMs'
      || !['ASCENDING','DESCENDING'].includes(order.direction) || spec.filters?.length
      || ['startAt','startAfter','endAt','endBefore'].some(key=>spec[key])
      || !Number.isInteger(spec.limit) || spec.limit<=0 || spec.limit>1000
      || (spec.limitToLast!==undefined && typeof spec.limitToLast!=='boolean')) return null;
  try {
    const bad=await db.prepare(`SELECT 1 AS hit FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_live_chat_page_exceptions
      WHERE parent_path=? AND parent_path GLOB 'lives/*/chat' AND (
        doc_id GLOB '*[^ -~]*' OR COALESCE((
          json_type(document_json,'$.createdAtMs') IN ('integer','real')
          AND json_extract(document_json,'$.createdAtMs') BETWEEN -9007199254740991 AND 9007199254740991
        ),0)=0
      ) LIMIT 1`).bind(parent).first();
    if (bad) return null;
    const direction=((order.direction==='DESCENDING') !== (spec.limitToLast===true))?'DESC':'ASC';
    const rows=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_live_chat_page
      WHERE parent_path=? AND parent_path GLOB 'lives/*/chat'
      ORDER BY json_extract(document_json,'$.createdAtMs') ${direction}, doc_id ${direction} LIMIT ?`)
      .bind(parent,spec.limit).all();
    return rows.results || [];
  } catch (error) {
    if (!/no such index: idx_cf_live_chat_page(?:_exceptions)?/.test(String(error))) throw error;
    return null;
  }
}

// SQLite's binary UTF-8 order and JS's UTF-16 order differ for supplementary
// characters. Do not use a Unicode upper cursor directly in SQL. Instead seek
// the entire exact-prefix range in UTF-8, then let the JS evaluator apply all
// cursors, including the original prefix + U+F8FF boundary. A well-formed
// prefix has the same UTF-8 byte prefix for every matching stored string.
function unicodePrefixUpper(prefix) {
  if (typeof prefix!=='string' || !prefix.length || prefix.length>60) return null;
  const points=Array.from(prefix,part=>part.codePointAt(0));
  if (points.some(cp=>cp<0x20 || (cp>=0xd800 && cp<=0xdfff))) return null;
  for(let i=points.length-1;i>=0;i--) {
    if(points[i]>=0x10ffff)continue;
    let next=points[i]+1;
    if(next===0xd800)next=0xe000;
    return String.fromCodePoint(...points.slice(0,i),next);
  }
  return null;
}

async function readCollectionRows(db, parent, uid, filters, spec, env) {
  const bounded=spec ? await boundedChatRows(db,parent,spec) ?? await boundedLiveChatRows(db,parent,spec) : null;
  if (bounded!==null) return bounded;
  const membership=parent==='users' && Array.isArray(filters) && filters.find(f=>
    ['followers','following'].includes(f?.field) && f.op==='array-contains' && typeof f.value==='string'
      && /^[\x20-\x7e]*$/.test(f.value));
  if (membership && typeof db.batch==='function') {
    try {
      // Derived candidates are only a prefilter. The same JS privacy projection,
      // filters, ordering and paging still run below. Read index readiness and
      // canonical candidates together in one D1 transaction.
      const [ready,current,unusualCurrent]=await db.batch([
        db.prepare("SELECT ready FROM friendbook_cf_user_follow_index_state WHERE id=1").bind(),
        db.prepare(`SELECT f.* FROM friendbook_cf_user_follow_membership m
          JOIN friendbook_cf_firestore_documents f ON f.path=m.path
          WHERE m.member=? AND m.kind=? AND m.source=1 AND f.parent_path='users'`).bind(membership.value,membership.field),
        db.prepare(`SELECT f.* FROM friendbook_cf_user_follow_membership m JOIN friendbook_cf_firestore_documents f ON f.path=m.path
          WHERE m.member='' AND m.kind='*' AND m.source=1 AND f.parent_path='users' LIMIT 65`).bind(),
      ]);
      if ([ready,current,unusualCurrent].every(r=>r && r.success!==false && Array.isArray(r.results))
          && ready.results.length===1 && ready.results[0].ready===1 && unusualCurrent.results.length<65) {
        const candidates=[...current.results,...unusualCurrent.results];
        // Noncanonical paths can share IDs and rely on the original stable
        // source ordering; do not attempt to infer that ordering from an index.
        if (candidates.every(row=>typeof row.doc_id==='string' && row.path===`users/${row.doc_id}`)) {
          return [...current.results,...unusualCurrent.results];
        }
      }
    } catch(error) {
      if (!/no such table: friendbook_cf_user_follow_(?:index_state|membership)/.test(String(error))) throw error;
    }
  }
  if (parent==='payments') {
    // Ordinary readers can only see their own payments; an administrator may
    // see all records unless the query itself asks for one particular owner.
    const requested=filters?.find(f=>f.field==='userId' && f.op==='==' && typeof f.value==='string')?.value;
    const owner=isPaymentAdmin(env,uid)?requested:uid;
    if (typeof owner==='string') {
      try {
        const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_payments_user
            WHERE parent_path='payments' AND json_extract(document_json,'$.userId')=?`).bind(owner).all();
        const rows=[...(canonical.results||[])];
        if(new Set(rows.map(row=>row.doc_id)).size===rows.length)return rows;
      } catch(error) {
        if (!/no such index: idx_cf_(?:legacy_)?payments_user/.test(String(error))) throw error;
      }
    }
  }
  if (parent === 'users') {
    const idFilter=filters?.find(f=>f.field==='__name__' && (f.op==='==' || f.op==='in'));
    const requested=idFilter?.op==='==' ? [idFilter.value] : idFilter?.value;
    if (Array.isArray(requested) && requested.length<=30 && requested.every(id=>typeof id==='string')) {
      const ids=[...new Set(requested)];
      if (!ids.length) return [];
      const slots=ids.map(()=>'?').join(',');
      try {
        const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_people_default_page
            WHERE parent_path='users' AND doc_id IN (${slots})`).bind(...ids).all();
        const candidates=[...(canonical.results||[])];
        // Same path precedence and normal profile projection. For inconsistent
        // historical IDs, retain the old tie ordering via the generic path.
        if (new Set(candidates.map(row=>row.doc_id)).size===candidates.length) return candidates;
      } catch(error) {
        if (!/no such index: idx_cf_(?:legacy_)?people_default_page/.test(String(error))) throw error;
      }
    }
    const order=Array.isArray(spec?.orderBy) && spec.orderBy.length===1 ? spec.orderBy[0] : null;
    const lower=Array.isArray(spec?.startAt) && spec.startAt.length===1 ? spec.startAt[0] : null;
    const upper=unicodePrefixUpper(lower);
    // Additional cursor bounds can only narrow this prefix. The common query
    // evaluator still applies them after profile projection and shadow handling.
    if (order?.field==='displayName' && order.direction!=='DESCENDING'
        && upper!==null && Array.isArray(spec.endAt) && spec.endAt.length===1 && spec.endAt[0]===lower+'\uf8ff') {
      try {
        const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_users_display_name
            WHERE parent_path='users' AND json_extract(document_json,'$.displayName')>=?
            AND json_extract(document_json,'$.displayName')<?`).bind(lower,upper).all();
        const rows=[...(canonical.results||[])];
        if (new Set(rows.map(row=>row.doc_id)).size===rows.length) return rows;
      } catch(error) {
        if (!/no such index: idx_cf_(?:users_display_name|legacy_people_cursor_prefix)/.test(String(error))) throw error;
      }
    }
    const lo=filters?.find(f=>f.field==='displayName' && f.op==='>=')?.value;
    const hi=filters?.find(f=>f.field==='displayName' && f.op==='<=')?.value;
    const end=unicodePrefixUpper(lo);
    if (end!==null && hi===lo+'\uf8ff') {
      // Both primitive and Firestore-envelope names (including coerced
      // stringValue variants) must enter the same projection and filter path.
      try {
        const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_users_display_name
            WHERE parent_path='users' AND json_extract(document_json,'$.displayName')>=? AND json_extract(document_json,'$.displayName')<?`).bind(lo,end).all();
        const rows=[...(canonical.results||[])];
        if(new Set(rows.map(row=>row.doc_id)).size===rows.length)return rows;
      } catch(error) {
        if (!/no such index: idx_cf_(?:users_display_name|legacy_people_cursor_prefix)/.test(String(error))) throw error;
      }
    }
  }
  if (parent==='lives' && filters?.some(f=>f.field==='status' && ((f.op==='==' && f.value==='live')
      || (f.op==='in' && Array.isArray(f.value) && f.value.length===1 && f.value[0]==='live')))) {
    try {
      const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_lives_status
          WHERE parent_path='lives' AND json_extract(document_json,'$.status')='live'`).bind().all();
      const rows=[...(canonical.results||[])];
      // Apply the raw predicate before authorizing the uncommon encoding bucket.
      return rows.filter(row=>rowToDocument(row).data.status==='live');
    } catch(error) {
      if (!/no such index: idx_cf_(?:legacy_)?lives_status/.test(String(error))) throw error;
    }
  }
  if (parent === 'chats') {
    // Canonical membership wins over stale legacy mirrors. The normal document
    // ACL still runs before results are returned to the caller.
    try {
      const queryCanonical=hint=>db.prepare(`SELECT f.* FROM friendbook_cf_chat_participants m ${hint}
        JOIN friendbook_cf_firestore_documents f ON f.path=m.chat_path
        WHERE m.participant_uid=? AND m.source=1`).bind(uid).all();
      let canonical,hint='INDEXED BY idx_cf_chat_participant_source';
      try { canonical=await queryCanonical(hint); }
      catch(error) {
        // Safe rolling deployment or old local fixtures retain the same ACL.
        if (!String(error).includes('no such index: idx_cf_chat_participant_source')) throw error;
        hint=''; canonical=await queryCanonical(hint);
      }
      return (canonical.results||[]).map(row=>({...row,[canonicalRootSnapshot]:true}));
    } catch (error) {
      if (!String(error).includes('no such table: friendbook_cf_chat_participants')) throw error;
    }
  }
  if (parent === 'friends') {
    // Exact two-person candidate probes must not materialize the entire graph.
    // Feed a bounded, deduplicated set into pair-index seeks. The ordinary
    // projection/filter/ACL pipeline still decides the returned documents.
    const pairFilter=filters?.find(f=>f.field==='participants' && (f.op==='in' || f.op==='=='));
    const pairs=pairFilter ? (pairFilter.op==='in'?pairFilter.value:[pairFilter.value]) : null;
    if(Array.isArray(pairs) && pairs.length>0 && pairs.length<=30
        && pairs.every(pair=>Array.isArray(pair) && pair.length===2 && pair.every(value=>typeof value==='string'))) {
      const authorized=[...new Map(pairs.filter(pair=>pair.includes(uid)).map(pair=>[JSON.stringify(pair),pair])).values()];
      if(!authorized.length)return [];
      const exact=await db.prepare(`SELECT f.path,f.parent_path,f.collection_id,f.doc_id,f.owner_uid,f.document_json,f.create_time,f.update_time
        FROM json_each(?1) pair CROSS JOIN friendbook_cf_firestore_documents f
        WHERE f.parent_path='friends'
          AND json_extract(f.document_json,'$.participants[0]')=json_extract(pair.value,'$[0]')
          AND json_extract(f.document_json,'$.participants[1]')=json_extract(pair.value,'$[1]')`).bind(JSON.stringify(authorized)).all();
      return exact.results||[];
    }
    // Only a participant can see a friendship. Index the two positions instead
    // of loading every user's relationships and projecting them in JavaScript.
    const canonical=await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?
      UNION ALL SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=?
        AND json_extract(document_json,'$.participants[0]') IS NOT ?`).bind(uid,uid,uid).all();
    // Reuse canonical participant results to shadow their usual mirrors; a
    // nonmatching canonical row can still shadow a stale matching mirror.

    return [...(canonical?.results || [])];
  }
  if (parent === 'friend_requests') {
    const filtered=await filteredRequestRows(db,uid,filters);
    if (filtered!==null) return filtered;
    // Only participants can read requests, regardless of query filter supplied by the client.
    const canonical=await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.fromUid')=?
      UNION ALL
      SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=? AND json_extract(document_json,'$.fromUid') IS NOT ?`).bind(uid,uid,uid).all();
    return [...(canonical?.results || [])];
  }
  if (parent==='stories' && !filters?.some(f=>f.field==='authorId' && f.op==='==')) {
    const expiry=filters?.find(f=>f.field==='expiresAt' && f.op==='>' && f.value?.__type==='timestamp' && Number.isSafeInteger(f.value.millis));
    if (expiry) {
      const modernExpiry=`CASE WHEN json_type(document_json,'$.expiresAt') IN ('object','array') THEN
      CASE WHEN json_extract(document_json,'$.expiresAt.__type')='timestamp'
        AND json_type(document_json,'$.expiresAt.millis') IN ('integer','real')
        THEN json_extract(document_json,'$.expiresAt.millis') ELSE 'compat' END END`;
      try {
        const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_story_timestamp_expiry_lookup
            WHERE parent_path='stories' AND (${modernExpiry})>?`).bind(expiry.value.millis).all();
        const rows=[...(canonical.results||[])];
        // SQLite sorts the text compatibility bucket after numeric timestamps.
        // Recheck it with the existing decoder and filter, before story ACL work.
        if (new Set(rows.map(row=>row.doc_id)).size===rows.length) return rows.filter(row=>{
          const doc=rowToDocument(row);
          return matchesFilter(doc.data,expiry,doc.id);
        });
      } catch(error) {
        if (!/no such index: idx_cf_(?:legacy_)?story_timestamp_expiry_lookup/.test(String(error))) throw error;
      }
    }
  }
  // Only hard-coded root/field pairs can be interpolated into SQL. Privacy
  // filtering still happens after candidate selection, before the page limit.
  const indexedFields={posts:['groupId','authorId'],reels:['authorId'],stories:['authorId'],pages:['ownerId'],referrals:['refBy']};
  const indexedField=(Object.hasOwn(indexedFields,parent)?indexedFields[parent]:[]).find(field=>
    Array.isArray(filters) && filters.some(f=>f?.field===field && f.op==='==' && typeof f.value==='string'));
  if (indexedField) {
    const value=filters.find(f=>f.field===indexedField && f.op==='==' && typeof f.value==='string').value;
    const canonical=await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time FROM friendbook_cf_firestore_documents
      WHERE parent_path='${parent}' AND json_extract(document_json,'$.${indexedField}')=?`).bind(value).all();
    return [...(canonical?.results || [])];
  }
  if (parent === 'notifications') {
    const unread = Array.isArray(filters) && filters.some(f=>f.field==='isRead' && f.op==='==' && f.value===false);
    const canonicalUnread = unread ? " AND json_type(document_json,'$.isRead')='false'" : '';
    // Authorization itself narrows the inbox before loading rows. Keep canonical
    // path precedence even when its recipient differs from a stale legacy mirror.
    // Both ordered indexes start with the same recipient key as the older
    // recipient indexes. Pin the compatibility path so an eventual removal of
    // a redundant index cannot make SQLite scan unrelated inboxes. Older
    // schemas without the relevant page index retain their original plan.
    const canonicalQuery=index=>db.prepare(`SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time
      FROM friendbook_cf_firestore_documents ${index} WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?${canonicalUnread}`).bind(uid).all();
    const index=unread?'idx_cf_notif_unread':'idx_cf_notif_page';
    const canonicalRead=(async()=>{
      try { return await canonicalQuery(`INDEXED BY ${index}`); }
      catch(error) {
        if(!String(error).includes(`no such index: ${index}`))throw error;
        return canonicalQuery('');
      }
    })();
    const canonical=await canonicalRead;
    return [...(canonical?.results || [])];
  }
  const parts = splitPath(parent);
  // The alias is built only from fixed labels. Unlike bound values and numeric
  // projections, it identifies the query shape in normalized D1 analytics.
  const scanRoots=['users','posts','reels','stories','pages','groups','chats','friend_requests','comments','likes','followers','following','saves','payments','subscriptions','reports','feedback','gifts','lives','messages'];
  const scanRoot=parts?.length===1 && scanRoots.includes(parent) ? parent : parts?.length===1 ? 'other' : 'nested';
  const usersShape=scanRoot!=='users'?'':!spec?'_count'
    : spec.orderBy?.[0]?.field==='createdAt'?'_recent'
    : spec.orderBy?.[0]?.field==='displayName'?'_name'
    : spec.orderBy?.[0]?.field==='stars'?'_stars'
    : filters?.some(f=>f.op==='array-contains')?'_membership'
    : filters?.length?'_filtered':Number.isInteger(spec.limit)?'_page':'_list';
  const scanAlias=`quota_root_${scanRoot}${usersShape}`;
  const canonicalRead = db.prepare(
    `SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents AS ${scanAlias} WHERE parent_path = ?`
  ).bind(parent).all();
  const firestoreRows = await canonicalRead;
  const byPath = new Map((firestoreRows?.results || []).map(row => [row.path, row]));
  if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages') {
    // Legacy CloudflareStore messages use either messages/{chatId}_{messageId} IDs or a chatId
    // field, while Android reads chats/{chatId}/messages/{messageId}. Restrict this bridge to
    // exact ID-prefix/chatId matches and a participant-checked chat query; native nested rows win.
    const prefix = `${parts[1]}_`, upper = `${parts[1]}\``;
    // Two indexable branches in one D1 request. '_' and '`' are adjacent ASCII
    // characters, so this range exactly matches prefix + any valid message ID.
    // Exclude prefix rows from branch two to avoid duplicating a legacy message.
    const appRows = await db.prepare(
      `SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time
       FROM friendbook_cf_firestore_documents
       WHERE parent_path='messages' AND doc_id>=? AND doc_id<?
       UNION ALL
       SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time
       FROM friendbook_cf_firestore_documents
       WHERE parent_path='messages' AND json_extract(document_json,'$.chatId')=?
         AND NOT (doc_id>=? AND doc_id<?)`
    ).bind(prefix, upper, parts[1], prefix, upper).all();
    for (const appRow of appRows?.results || []) {
      const messageId = appRow.doc_id.startsWith(prefix) ? appRow.doc_id.slice(prefix.length) : appRow.doc_id;
      if (!validSegment(messageId)) continue;
      const nestedPath = `${parent}/${messageId}`;
      if (byPath.has(nestedPath)) continue;
      const row = appRow;
      if (row) byPath.set(nestedPath, { ...row, path:nestedPath, parent_path:parent, collection_id:'messages', doc_id:messageId });
    }
  }
  return [...byPath.values()];
}

async function canReadChatMessages(db, chatId, uid) {
  const room = await readRow(db, `chats/${chatId}`);
  if (!room) return false;
  const data = decodeValue(JSON.parse(room.document_json));
  return Array.isArray(data?.participants) && data.participants.includes(uid);
}

// An identical operation may skip writing only if the authorized canonical
// snapshot is current AND the historical mirror already has the exact data and
// owner that writeRow would write. A stale/missing mirror must still be repaired.
async function unchangedRootState(db,path,row,doc) {
  const coll=collectionIdOf(path),id=docIdOf(path);
  const owner=ownerOf(doc,row.owner_uid || (coll==='users'?id:''));
  const appOwner=owner || (coll==='users'?id:'');
  return db.prepare(`SELECT
    EXISTS(SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?1 AND document_json=?2 AND update_time=?3 AND owner_uid IS ?4) AS current,
    EXISTS(SELECT 1 FROM friendbook_cf_documents WHERE path=?1 AND document_json=?5 AND owner_uid IS ?6) AS mirror`)
    .bind(path,row.document_json,row.update_time,row.owner_uid??null,JSON.stringify({...doc,id}),appOwner).first()
    .then(result=>({current:result?.current===1,mirror:result?.mirror===1 && row.owner_uid===owner}));
}
function literalValue(value) {
  if(value===null || ['boolean','number','string'].includes(typeof value))return true;
  if(!value || typeof value!=='object' || isFieldValue(value))return false;
  return Object.values(value).every(literalValue);
}
function literalFields(fields) {
  return !!fields && typeof fields==='object' && !Array.isArray(fields) && Object.keys(fields).length>0
    && Object.values(fields).every(literalValue);
}

function unchangedNotificationRead(path,row,fields) {
  if (splitPath(path)?.[0]!=='notifications' || !row?.[canonicalRootSnapshot]
      || !fields || Object.keys(fields).length!==1 || fields.isRead!==true) return false;
  return decodeValue(JSON.parse(row.document_json)).isRead===true;
}

// Only literal acknowledgement patches: transforms and real changes still commit.
function unchangedChatReceipt(path,uid,row,next,fields) {
  if (!row || !fields || row.document_json!==JSON.stringify(next)) return false;
  const parts=splitPath(path),keys=Object.keys(fields);
  if(parts?.[0]!=='chats' || !keys.length)return false;
  if(parts.length===4 && parts[2]==='messages')return keys.every(key=>
    ['isSeen','isDelivered'].includes(key) && fields[key]===true);
  return parts.length===2 && row[canonicalRootSnapshot] && keys.length===1
    && keys[0]===`unreadCount.${uid}` && fields[keys[0]]===0;
}

function isLiveHeartbeatPatch(path,row,before,fields) {
  return /^lives\/[^/]+$/.test(path) && row[canonicalRootSnapshot] && before?.status==='live'
    && Object.keys(fields).length===3 && Number.isSafeInteger(fields.lastHeartbeatMs)
    && Array.isArray(fields.viewerIds) && fields.viewerIds.every(id=>typeof id==='string')
    && Number.isSafeInteger(fields.peakViewers) && fields.peakViewers>=0;
}
// The app's live-discovery expiry is 150 seconds. Limit quiet heartbeats to a
// 24-second persisted age, leaving more than five intervals of safety. Never
// coalesce changed telemetry, unusual clocks, or backwards client timestamps.
function quietLiveHeartbeat(before,fields,now) {
  const previous=before.lastHeartbeatMs, age=now-previous;
  return Number.isSafeInteger(previous) && previous>0 && age>=0 && age<24_000
    && fields.lastHeartbeatMs>=previous && fields.lastHeartbeatMs<=now+30_000
    && fields.peakViewers===before.peakViewers && Array.isArray(before.viewerIds)
    && JSON.stringify(fields.viewerIds)===JSON.stringify(before.viewerIds);
}

async function writeRow(db, path, doc, now, existing, guarded = false, inlineGuard = false) {
  const parent = parentPath(path);
  const coll = collectionIdOf(path);
  const id = docIdOf(path);
  const owner = ownerOf(doc, existing?.owner_uid || (coll === 'users' ? id : ''));
  const createTime = existing?.create_time || now;
  const serialized = JSON.stringify(doc);
  if (serialized.length > 1_800_000) throw new Error('Document too large');
  const inline=guarded && inlineGuard && existing?.[canonicalRootSnapshot] && isRootDocument(path);
  // The exact authorized root snapshot can be checked by the upsert itself.
  // A changed snapshot makes path NULL and aborts the whole mirror transaction.
  // Legacy/missing documents retain the original absence/conversion guards.
  const writes = [inline ? db.prepare(
    `INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('document',path)}(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
     VALUES (CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?1)=?2 THEN ?1 ELSE NULL END,?3,?4,?5,?6,?7,?8,?9)
     ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
  ).bind(path,existing.document_json,parent,coll,id,owner,serialized,createTime,now) : db.prepare(
    `INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('document',path)}(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
  ).bind(path, parent, coll, id, owner, serialized, createTime, now)];

  // Keep both root representations in one D1 transaction: a failed mirror must not
  // leave a successful-looking profile/post in only one of the two tables.
  if (isRootDocument(path) && id && coll) {
    const appDoc = JSON.stringify({ ...doc, id });
    const appOwner = owner || (coll === 'users' ? id : '');
    writes.push(db.prepare(
      `INSERT INTO friendbook_cf_documents AS ${writeAttribution('document',path)}(path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid, document_json=excluded.document_json, updated_at=excluded.updated_at`
    ).bind(path, coll, id, appOwner, appDoc, now));
  }
  if (guarded && !inline) {
    writes.unshift(await chatDocumentGuard(db,path,existing));
    writes.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  }
  if (writes.length > 1) {
    if (typeof db.batch !== 'function') throw new Error('Atomic storage unavailable');
    await db.batch(writes);
  } else await writes[0].run();
  return { path, parent_path: parent, collection_id: coll, doc_id: id, owner_uid: owner, document_json: serialized, create_time: createTime, update_time: now };
}
function rowToDocument(row) {
  if (!row) return null;
  let data = null;
  try { data = JSON.parse(row.document_json); } catch { data = null; }
  if (!data || typeof data !== 'object') data = {};
  return {
    exists: true,
    id: row.doc_id,
    path: row.path,
    parent: row.parent_path,
    collection: row.collection_id,
    data: decodeValue(data),
    createTime: row.create_time || 0,
    updateTime: row.update_time || 0,
    ...(row[canonicalRootSnapshot] && row.parent_path==='chats' && isRootDocument(row.path) && splitPath(row.path)?.[0]==='chats'
      ? {[canonicalRootSnapshot]:row.document_json} : {}),
  };
}

// Recompute a profile transform after a conflicting friend/follow/profile write.
// The snapshot guard and both copies commit together, so concurrent edits cannot
// silently erase relationship counters or another device's profile changes.
async function mutateProfile(db, uid, path, body, method, now, profileRow) {
  if (splitPath(path)[1] !== uid) return json({error:'Forbidden'},403);
  for (let attempt=0; attempt<4; attempt++) {
    try {
      const retrySnapshot=attempt>0 && profileRow!==undefined ? await readProfileMutationSnapshot(db,uid) : null;
      if (retrySnapshot && (await accountStatus(db,uid,retrySnapshot.accountRow)).disabled)
        return json({error:'Account disabled'},403);
      const existing=retrySnapshot ? retrySnapshot.profileRow
        : attempt===0 && profileRow!==undefined ? profileRow : await readRow(db,path);
      if (method==='PATCH' && !existing) return json({error:'NOT_FOUND'},404);
      if (body.createOnly===true && existing) return json({error:'ALREADY_EXISTS'},409);
      const before=existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const fields=body?.fields || body?.data || body?.updates || {};
      let next=method==='PATCH' ? applyUpdate(before,fields,now)
        : applySet(before,body?.data || {},Boolean(body?.merge),body?.mergeFields || null,now);
      if (!profileWriteAllowed(path,uid,before,next)) return json({error:'Forbidden'},403);
      // Legacy Android clients may send these exact five status fields together.
      // Ignore only that shape; preserve an actual visibility preference change
      // and every mixed edit. The current app sends none of them.
      const legacyStatusFields=['isOnline','isActive','showActiveStatus','lastSeenMs','lastSeen'];
      if (method==='PATCH' && existing && Object.keys(fields).length===legacyStatusFields.length
          && Object.keys(fields).every(key=>legacyStatusFields.includes(key))
          && ['isOnline','isActive','showActiveStatus'].every(key=>typeof fields[key]==='boolean')
          && Number.isSafeInteger(fields.lastSeenMs) && fields.lastSeen?.__fieldValue==='serverTimestamp') {
        if (before.showActiveStatus===fields.showActiveStatus) return json(rowToDocument(existing));
        next=applyUpdate(before,{showActiveStatus:fields.showActiveStatus},now);
      }
      // A literal, unchanged PATCH does not need to rewrite canonical, mirror,
      // and guard rows. Keep transforms on the guarded write path.
      if(method==='PATCH' && existing?.[canonicalRootSnapshot] && literalFields(fields)
          && existing.document_json===JSON.stringify(next)) return json(rowToDocument(existing));
      // Login/settings often repeat the same literal merge. Check the snapshot
      // again before acknowledging so a racing profile edit is retried rather
      // than overwritten or reported as if the old value still held.
      if(method==='PUT' && body.merge===true && body.mergeFields==null && existing?.[canonicalRootSnapshot]
          && literalFields(body.data) && existing.document_json===JSON.stringify(next)) {
        const state=await unchangedRootState(db,path,existing,next);
        if(!state.current)throw new Error('Document changed');
        if(state.mirror)return json(rowToDocument(existing));
      }
      return json(rowToDocument(await writeRow(db,path,next,now,existing,true,true)));
    } catch (error) {
      const message=String(error?.message || '');
      if (message==='Document too large') return json({error:message},413);
      if (/Document changed|CHECK constraint failed: ok\s*=\s*1|NOT NULL constraint failed: friendbook_cf_firestore_documents\.path/i.test(message)) {
        if (attempt<3) continue;
        return json({error:'Profile changed. Please retry.'},409);
      }
      return json({error:'Profile write unavailable'},503);
    }
  }
  return json({error:'Profile changed. Please retry.'},409);
}

// A save may only add/remove the caller's own UID, and only for content they can
// currently read. Owner edits share the same optimistic guard so saves are not
// overwritten by a concurrent title/privacy update.
async function mutatePost(db,uid,path,body,method,now) {
  for (let attempt=0; attempt<4; attempt++) {
    try {
      const existing=await readRow(db,path);
      if (method==='PATCH' && !existing) return json({error:'NOT_FOUND'},404);
      if (body.createOnly===true && existing) return json({error:'ALREADY_EXISTS'},409);
      const before=existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const fields=body?.fields || body?.data || body?.updates || {};
      const next=method==='PATCH' ? applyUpdate(before,fields,now)
        : applySet(before,body?.data || {},Boolean(body?.merge),body?.mergeFields || null,now);
      if (!before && next.createdAt == null) next.createdAt=now;
      if (before?.groupId || next?.groupId) return await writeGroupPost(db,uid,path,existing,next,now);
      let allowed=contentWriteAllowed(path,uid,before,next);
      const transform=fields?.savedBy;
      const ownSave=method==='PATCH' && before && Object.keys(fields).length===1
        && transform && typeof transform==='object' && !Array.isArray(transform)
        && Object.keys(transform).every(k=>['__fieldValue','values'].includes(k))
        && ['arrayUnion','arrayRemove'].includes(transform.__fieldValue)
        && Array.isArray(transform.values) && transform.values.length===1 && transform.values[0]===uid;
      if (!allowed && ownSave) allowed=await contentReadAllowed(db,uid,path);
      if (!allowed) return json({error:'Forbidden'},403);
      const literalEdit=(method==='PATCH' && literalFields(fields)) ||
        (method==='PUT' && body.merge===true && body.mergeFields==null && literalFields(body.data));
      if(existing?.[canonicalRootSnapshot] && (literalEdit || ownSave) && existing.document_json===JSON.stringify(next)) {
        const state=await unchangedRootState(db,path,existing,next);
        if(!state.current)throw new Error('Document changed');
        if(state.mirror)return json(rowToDocument(existing));
      }
      return json(rowToDocument(await writeRow(db,path,next,now,existing,true,true)));
    } catch (error) {
      const message=String(error?.message || '');
      if (message==='Document too large') return json({error:message},413);
      if (/Document changed|CHECK constraint failed: ok\s*=\s*1|NOT NULL constraint failed: friendbook_cf_firestore_documents\.path/i.test(message)) {
        if (attempt<3) continue;
        return json({error:'Post changed. Please retry.'},409);
      }
      return json({error:'Post write unavailable'},503);
    }
  }
  return json({error:'Post changed. Please retry.'},409);
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
const FILTER_OPERATORS = new Set(['==','!=','<','<=','>','>=','in','array-contains','array-contains-any']);
function validQueryFilters(filters) {
  return Array.isArray(filters) && filters.every(filter => filter && typeof filter.field === 'string'
    && filter.field.length > 0 && FILTER_OPERATORS.has(filter.op) && Object.hasOwn(filter,'value'));
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
    const tie = a.id < b.id ? -1 : a.id > b.id ? 1 : 0;
    return tie && orders.length && orders[orders.length - 1].direction === 'DESCENDING' ? -tie : tie;
  });
}
function cursorIndex(sorted, cursor, orderBy) {
  if (Array.isArray(cursor)) {
    const orders = Array.isArray(orderBy) && orderBy.length ? orderBy : [{field:'__name__',direction:'ASCENDING'}];
    const values = cursor.map(decodeValue);
    const hasIdTieBreaker = values.length === orders.length + 1;
    const id = hasIdTieBreaker ? String(values.pop()) : null;
    if (id) {
      const index = sorted.findIndex(d => d.id === id);
      if (index >= 0) return index;
    }
    return sorted.findIndex(doc => values.every((value,index) => {
      const field = orders[index]?.field || '__name__';
      return valuesEqual(field === '__name__' ? doc.id : getField(doc.data,field),value);
    }));
  }
  if (!cursor || typeof cursor !== 'object') return -1;
  const id = cursor.docId || cursor.id || (cursor.path ? docIdOf(cursor.path) : null);
  if (!id) return -1;
  return sorted.findIndex(d => d.id === id);
}
// Array cursors describe ordered values, not necessarily an existing document. Prefix
// searches and deleted/moved pagination anchors must still apply the boundary.
function compareDocumentToCursor(doc, cursor, orderBy) {
  const orders = Array.isArray(orderBy) && orderBy.length ? orderBy : [{field:'__name__',direction:'ASCENDING'}];
  const effective = [...orders];
  if (!orders.some(o => o.field === '__name__')) {
    effective.push({field:'__name__',direction:orders[orders.length - 1].direction});
  }
  for (let i=0; i<cursor.length && i<effective.length; i++) {
    const order=effective[i];
    const value=order.field === '__name__' ? doc.id : getField(doc.data,order.field);
    const cmp=compareValues(value,decodeValue(cursor[i]));
    if (cmp) return order.direction === 'DESCENDING' ? -cmp : cmp;
  }
  return 0;
}
function applyQuery(docs, spec) {
  const filters = Array.isArray(spec?.filters) ? spec.filters : [];
  let filtered = docs.filter(d => filters.every(f => matchesFilter(d.data, f, d.id)));
  let sorted = sortDocuments(filtered, spec?.orderBy);
  let start = 0;
  let end = sorted.length;
  for (const [name, side, inclusive] of [
    ['startAfter','start',false], ['startAt','start',true],
    ['endBefore','end',false], ['endAt','end',true],
  ]) {
    const cursor=spec?.[name];
    if (!cursor) continue;
    if (Array.isArray(cursor)) {
      if (!cursor.length) continue;
      sorted=sorted.filter(doc => {
        const cmp=compareDocumentToCursor(doc,cursor,spec?.orderBy);
        return side === 'start' ? (inclusive ? cmp >= 0 : cmp > 0)
          : (inclusive ? cmp <= 0 : cmp < 0);
      });
    } else {
      // Retain legacy object cursors for older clients.
      const idx=cursorIndex(sorted,cursor,spec?.orderBy);
      if (idx >= 0) sorted=side === 'start' ? sorted.slice(idx+(inclusive?0:1)) : sorted.slice(0,idx+(inclusive?1:0));
    }
  }
  start=0; end=sorted.length;
  let sliced = sorted.slice(start, end);
  const limit = Number.isFinite(spec?.limit) ? Math.trunc(spec.limit) : null;
  const limitToLast = spec?.limitToLast === true ? limit
    : Number.isFinite(spec?.limitToLast) ? Math.trunc(spec.limitToLast) : null;
  if (limitToLast !== null && limitToLast > 0) {
    sliced = sliced.slice(-limitToLast);
  } else if (limit !== null && limit > 0) {
    sliced = sliced.slice(0, limit);
  }
  return sliced;
}

// A caller may discover their own followers/following even when one of those
// profiles hides its full relationship arrays. Never apply this exception to a
// third party's list: that would reveal a locked profile's private connections.
function ownFollowFilter(parent,uid,filter) {
  return parent==='users' && ['followers','following'].includes(filter?.field)
    && filter.op==='array-contains' && filter.value===uid;
}
// Apply unchanged-data filters before expensive authorization; projected user,
// friend and group fields must still be filtered only after projection.
function prefilterRaw(docs,parent,filters) {
  const root=splitPath(parent)?.[0];
  if (!['posts','reels','chats','messages','notifications','friend_requests'].includes(root) || !Array.isArray(filters)) return docs;
  return docs.filter(d=>filters.every(f=>matchesFilter(d.data,f,d.id)));
}
async function visibleDocuments(db,uid,parent,docs,requestUserReads = new Map(),includeArchivedOwner = false) {
  const parts=splitPath(parent), nestedChat=parts?.length===3 && parts[0]==='chats' && parts[2]==='messages';
  // All comments/reactions in a nested collection share the same parent post.
  // Authorize that parent once per request, not once per child row.
  if (parts?.length===3 && ['posts','reels'].includes(parts[0]) && ['comments','reactions'].includes(parts[2])) {
    return await contentReadAllowed(db,uid,`${parts[0]}/${parts[1]}`,requestUserReads) ? docs : [];
  }
  // Query/count authorize the live room before accessing its chat collection.
  if (parts?.length===3 && parts[0]==='lives' && parts[2]==='chat') return docs;
  const allowed=new Array(docs.length).fill(false);
  const requestRoomReads=new Map();
  let next=0;
  await Promise.all(Array.from({length:Math.min(8,docs.length)},async()=>{
    for (;;) {
      const i=next++; if (i>=docs.length) return;
      const doc=docs[i];
      allowed[i]=(nestedChat || (parent==='chats' && doc[canonicalRootSnapshot]!==undefined
        ? storedChatMember(doc[canonicalRootSnapshot],uid) : await chatReadAllowed(db,doc.path,uid,doc.data,requestRoomReads))) && await contentReadAllowed(db,uid,doc.path,requestUserReads,includeArchivedOwner,doc[canonicalContentSnapshot]);
    }
  }));
  return docs.filter((_,i)=>allowed[i]);
}

// Enrich only a bounded page that already passed the authoritative post ACL.
async function feedEngagement(db, env, uid, body, docs, requestUserReads) {
  if (body?.feedEngagement!==true || body.parent!=='posts' || !Number.isInteger(body.limit)
      || body.limit<1 || body.limit>MAX_FEED_ENGAGEMENT_PAGE || env.D1_INTERACTIONS_ENABLED!=='true' || !docs.length) return docs;
  try {
    const resources=docs.map(d=>`posts/${d.id}`);
    const [viewer,states]=await Promise.all([
      requestUserReads.has(`users/${uid}`)
        ? requestUserReads.get(`users/${uid}`) : readD1DocumentFields(db,`users/${uid}`),
      db.prepare(`SELECT resource,state FROM friendbook_cf_interaction_resources WHERE resource IN (${resources.map(()=>'?').join(',')})`).bind(...resources).all(),
    ]);
    const stateByResource=new Map((states.results||[]).map(row=>[row.resource,row.state]));
    const blocked=[...(viewer?.blocked?.arrayValue?.values||[]),...(viewer?.blockedUsers?.arrayValue?.values||[])].map(v=>v.stringValue).filter(v=>typeof v==='string');
    const preloaded=await batchEngagementReads(db,resources.filter(resource=>!stateByResource.get(resource) || stateByResource.get(resource)==='verified'),uid,blocked);
    return await mapBounded(docs,3,async doc=>{
      const resource=`posts/${doc.id}`;
      const state=stateByResource.get(resource);
      if (state && state!=='verified') return doc;
      try {
        const engagement=await engagementSummary(db,resource,uid,doc.data.authorId,blocked,preloaded.get(resource));
        // Missing import metadata is not proof that a historical post had zero likes.
        if (!state && !engagement.types.length && (Number(doc.data.likes)>0 || Object.keys(doc.data.reactionsMap||{}).length)) {
          engagement.reactionStateUnknown=true;
        }
        // Home already has the verified viewer-specific summary and preview.
        // Keep legacy fallbacks intact on unverified or failed reads and leave
        // ordinary document/detail responses and stored documents untouched.
        if (state!=='verified') return {...doc,engagement};
        const {commentsMap,commentsList,lastComment,reactionsMap,reactions,likedBy,...compact}=doc.data;
        return {...doc,data:compact,engagement};
      }
      catch { return doc; } // A summary failure must not hide an authorized post.
    });
  } catch { return docs; }
}

// Mention-prefix pages can stop at bounded index heads when a full ASCII page
// is provably equivalent to the complete JS evaluator. Empty and printable
// ASCII prefixes share this proof; ambiguous pages use the compatibility path.
async function boundedNamePage(db,env,uid,spec) {
  const prefix=Array.isArray(spec.startAt) && spec.startAt.length===1 ? spec.startAt[0] : null;
  if (spec.parent!=='users' || spec.filters?.length || spec.orderBy?.length!==1
      || spec.orderBy[0].field!=='displayName' || spec.orderBy[0].direction==='DESCENDING'
      || typeof prefix!=='string' || !/^[\x20-\x7e]{0,60}$/.test(prefix)
      || !Array.isArray(spec.endAt) || spec.endAt.length!==1 || spec.endAt[0]!==prefix+'\uf8ff'
      || spec.startAfter || spec.endBefore || spec.limitToLast
      || !Number.isInteger(spec.limit) || spec.limit<1 || spec.limit>50) return null;
  const upper=prefix ? unicodePrefixUpper(prefix) : null;
  const bound=upper===null?'':' AND %NAME%<?';
  try {
    const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_users_display_name
        WHERE parent_path='users' AND json_extract(document_json,'$.displayName')>=?${bound.replace('%NAME%',"json_extract(document_json,'$.displayName')")}
          AND json_type(document_json,'$.displayName')='text'
        ORDER BY json_extract(document_json,'$.displayName'),doc_id LIMIT ?`).bind(prefix,...(upper===null?[]:[upper]),spec.limit).all();
    const all=canonical.results||[];
    if (all.some(row=>row.path!==`users/${row.doc_id}`)) return null;

    const rows=canonical.results||[];
    if(new Set(rows.map(row=>row.doc_id)).size!==rows.length)return null;
    const admin=isPaymentAdmin(env,uid), moderator=!admin && await verifiedModerator(db,uid);
    const docs=rows.map(rowToDocument).filter(Boolean).map(doc=>({...doc,
      data:profileReadView(doc.path,uid,doc.data,admin,moderator)})).filter(doc=>doc.data!==null);
    const page=applyQuery(docs,spec);
    // ASCII strings and IDs compare identically in SQLite and the JS query
    // engine against every other Unicode string. If a full result isn't ASCII,
    // or hidden/shadowed rows exhaust the heads, use the complete fallback.
    if(page.length!==spec.limit || page.some(doc=>typeof doc.data.displayName!=='string'
        || !/^[\x20-\x7e]*$/.test(doc.data.displayName) || !/^[-_a-zA-Z0-9]+$/.test(doc.id)))return null;
    return page;
  } catch(error) {
    if (/no such index: idx_cf_(?:users_display_name|legacy_people_cursor_prefix)/.test(String(error))) return null;
    throw error;
  }
}

// Read a bounded canonical head; return only when its boundary proves no
// omitted ID can enter the visible page.
async function boundedPeopleHead(db,uid,spec,admin,moderator) {
  const size=spec.limit+1;
  try {
    const canonical=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents AS quota_people_head INDEXED BY idx_cf_people_default_page
        WHERE parent_path='users' ORDER BY doc_id,path LIMIT ?`).bind(size).all();
    const groups=[canonical.results||[]];
    if(groups.some(rows=>rows.some(row=>!/^[-_a-zA-Z0-9]+$/.test(row.doc_id) || row.path!==`users/${row.doc_id}`)
        || new Set(rows.map(row=>row.doc_id)).size!==rows.length)) return null;

    const rows=groups[0];
    if(new Set(rows.map(row=>row.doc_id)).size!==rows.length)return null;
    const docs=rows.map(rowToDocument).filter(Boolean).map(doc=>({...doc,
      data:profileReadView(doc.path,uid,doc.data,admin,moderator)})).filter(doc=>doc.data!==null);
    const page=applyQuery(docs,spec);
    if(page.length!==spec.limit)return null;
    const last=page.at(-1).id;
    // The extra boundary row proves every omitted row sorts strictly later.
    // ASCII IDs compare identically against all Unicode strings in JS/SQLite.
    if(groups.some(rows=>rows.length===size && rows.at(-1).doc_id<=last))return null;
    return page;
  } catch(error) {
    if(/no such index: idx_cf_(?:legacy_)?people_default_page/.test(String(error)))return null;
    throw error;
  }
}

// Feed fast path: fetch small indexed canonical batches, then authorize each
// candidate before filling the visible page. Never LIMIT before privacy filtering.
// Anything not provably equivalent falls back to the compatibility query engine.
// The Home and conversation pickers request a small, unfiltered user page.
// Stop as soon as enough *visible* profiles have been found, not at a raw SQL limit.
async function boundedPeoplePage(db,env,uid,spec) {
  if (spec.parent!=='users' || spec.filters?.length || spec.orderBy?.length
      || spec.limitToLast || spec.startAfter || spec.startAt || spec.endBefore || spec.endAt
      || !Number.isInteger(spec.limit) || spec.limit<1 || spec.limit>50) return null;
  const admin=isPaymentAdmin(env,uid), moderator=!admin && await verifiedModerator(db,uid);
  const head=await boundedPeopleHead(db,uid,spec,admin,moderator);
  if(head!==null)return head;
  const page=[];
  let after=null;
  for (let batch=0;batch<4;batch++) {
    // Only overfetch once hidden rows prove a refill is needed.
    const size=batch===0 ? spec.limit : Math.max(16,spec.limit);
    const seek=after?' AND (doc_id,path)>(?,?)':'';
    const bindings=after?[...after,size+1]:[size+1];
    let results;
    try { ({results=[]}=await db.prepare(`WITH candidates AS (
      SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_people_default_page WHERE parent_path='users'${seek}
      ORDER BY doc_id,path LIMIT ?
    ) SELECT c.* FROM candidates c ORDER BY doc_id,path`).bind(...bindings).all()); }
    catch (error) {
      if (/no such index: idx_cf_(?:legacy_)?people_default_page/.test(String(error))) return null;
      throw error;
    }
    // SQL and JS agree on ASCII order. Use the original path for unusual IDs or
    // inconsistent historical rows (including equal IDs crossing a batch boundary).
    if (results.some(row=>!/^[-_a-zA-Z0-9]+$/.test(row.doc_id) || row.path!==`users/${row.doc_id}`)) return null;
    const take=size;
    for (const row of results.slice(0,take)) {
      const doc=rowToDocument(row);
      if (!doc) continue;
      const data=profileReadView(doc.path,uid,doc.data,admin,moderator);
      if (data===null) continue;
      page.push({...doc,data});
      if (page.length===spec.limit) return page;
    }
    if (results.length<=size) return page;
    const last=results[take-1];
    after=[last.doc_id,last.path];
  }
  return null;
}

// Friends suggestions sort profiles by account creation date, not document ID.
async function boundedRecentPeoplePage(db,env,uid,spec) {
  if(spec.parent!=='users' || spec.filters?.length || spec.orderBy?.length!==1
      || spec.orderBy[0]?.field!=='createdAt' || spec.orderBy[0]?.direction!=='DESCENDING'
      || spec.startAt || spec.endAt || spec.endBefore || spec.limitToLast
      || !Number.isInteger(spec.limit) || spec.limit<1 || spec.limit>30) return null;
  const admin=isPaymentAdmin(env,uid);
  // Moderator projections can remove createdAt, so their ordering stays on the
  // generic path which applies projection before ordering.
  if(!admin && await verifiedModerator(db,uid)) return null;
  let cursor;
  if(spec.startAfter){
    const c=spec.startAfter;
    if(!Array.isArray(c) || c.length!==2 || typeof c[1]!=='string' || !/^[ -~]+$/.test(c[1])) return null;
    const rank=c[0]===null?0:typeof c[0]==='number'?2:c[0]?.__type==='timestamp'?4:-1;
    const time=rank===0?0:rank===2?c[0]:c[0]?.millis;
    if(rank<0 || !Number.isFinite(time) || Math.abs(time)>Number.MAX_SAFE_INTEGER) return null;
    cursor=[rank,rank===4?Math.trunc(time):time,c[1]];
  }
  const modern=`CASE WHEN doc_id GLOB '*[^ -~]*' OR path<>'users/'||doc_id THEN -1
    WHEN json_type(document_json,'$.createdAt') IS NULL OR json_type(document_json,'$.createdAt')='null' THEN 0
    WHEN json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991 THEN 2
    WHEN json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991 THEN 4
    ELSE -1 END`;
  const time=`COALESCE(CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt')
    ELSE json_extract(document_json,'$.createdAt.millis') END,0)`;
  // Each standard stream is visibility-filtered before LIMIT. Its first N
  // candidates are sufficient for an N-item merged page; exceptions can only
  // displace those candidates, not require more lookahead.
  const size=spec.limit;
  const table='friendbook_cf_firestore_documents',index='idx_cf_recent_people',where="parent_path='users'",rank=modern;
  try {
    const oddPromise=(async()=>{
      const {results=[]}=await db.prepare(`SELECT a.* FROM ${table} a INDEXED BY ${index}
        WHERE ${where} AND (${rank})=-1  LIMIT ?`).bind(65).all();
      return results;
    })();
    // Split rank equality ranges so SQLite seeks the time expression index.
    async function readStandard() {
      const rows=[];
      for(const group of [4,2,0]) {
        if((cursor && group>cursor[0]) || rows.length>=size)continue;
        const args=[group];
        const bounds=[];
        if(cursor && group===cursor[0]) {
          bounds.push(`(${time})<=? AND ((${time})<? OR doc_id<?)`);
          args.push(cursor[1],cursor[1],cursor[2]);
        }
        const visibility=!admin ? `AND (a.path=? OR (
          json_type(document_json,'$.banned') IS NOT 'true'
          AND json_type(document_json,'$.deactivated') IS NOT 'true'))` : '';
        if(visibility)args.push(`users/${uid}`);
        const {results=[]}=await db.prepare(`SELECT a.* FROM ${table} a INDEXED BY ${index}
          WHERE ${where} AND (${rank})=? ${bounds.map(b=>'AND '+b).join(' ')}  ${visibility}
          ORDER BY (${time}) DESC,doc_id DESC LIMIT ?`).bind(...args,size-rows.length).all();
        rows.push(...results);
      }
      return rows;
    }
    const [odd,canonical]=await Promise.all([oddPromise,readStandard()]);
    const unusual=odd;
    if(unusual.length>64 || unusual.some(row=>row.path!==`users/${row.doc_id}`)) return null;
    const standard=sortDocuments(canonical.map(rowToDocument),spec.orderBy).slice(0,size);
    const anchor=standard.at(-1);
    const anchorCursor=anchor && [anchor.data.createdAt??null,anchor.id];
    const extra=unusual.map(rowToDocument).filter(doc=>!anchorCursor || standard.length<size
      || compareDocumentToCursor(doc,anchorCursor,spec.orderBy)<=0);
    const candidates=[...standard,...extra];
    if(new Set(candidates.map(row=>row.id)).size!==candidates.length) return null;
    const projected=candidates.map(doc=>({...doc,data:profileReadView(doc.path,uid,doc.data,admin,false)})).filter(doc=>doc.data!==null);
    const page=applyQuery(projected,spec);
    return page.length===spec.limit || standard.length<size ? page : null;
  } catch(error) {
    if(/no such index: idx_cf_(?:legacy_)?recent_people/.test(String(error))) return null;
    throw error;
  }
}

// Home story rail: seek the existing expiry index instead of materializing all
// stories newer than the anchor. Rare encodings are merged before fresh ACLs.
async function boundedStoryPage(db,uid,spec,requestUserReads) {
  const filters=Array.isArray(spec?.filters)?spec.filters:[];
  const f=filters[0], anchor=f?.value;
  if(spec.parent!=='stories' || filters.length!==1 || f.field!=='expiresAt' || f.op!=='>'
      || anchor?.__type!=='timestamp' || !Number.isSafeInteger(anchor.millis)
      || spec.orderBy?.length!==1 || spec.orderBy[0]?.field!=='expiresAt' || spec.orderBy[0]?.direction!=='ASCENDING'
      || spec.limitToLast || spec.startAt || spec.endAt || spec.endBefore
      || !Number.isInteger(spec.limit) || spec.limit<1 || spec.limit>10) return null;
  let cursor=null;
  if(spec.startAfter) {
    const c=spec.startAfter;
    if(!Array.isArray(c) || c.length!==2 || c[0]?.__type!=='timestamp' || !Number.isSafeInteger(c[0].millis)
        || typeof c[1]!=='string' || !/^[-_a-zA-Z0-9]{1,512}$/.test(c[1])) return null;
    cursor=[c[0].millis,c[1]];
  }
  const time=`CASE WHEN json_type(document_json,'$.expiresAt') IN ('object','array') THEN
      CASE WHEN json_extract(document_json,'$.expiresAt.__type')='timestamp'
        AND json_type(document_json,'$.expiresAt.millis') IN ('integer','real')
        THEN json_extract(document_json,'$.expiresAt.millis') ELSE 'compat' END END`;
  const standard=`json_type(document_json,'$.expiresAt')='object'
    AND json_extract(document_json,'$.expiresAt.__type')='timestamp'
    AND json_type(document_json,'$.expiresAt.millis')='integer'
    AND json_extract(document_json,'$.expiresAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
    AND doc_id<>'' AND length(doc_id)<=512 AND instr(doc_id,char(0))=0 AND doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND path='stories/'||doc_id`;
  const exception=`json_type(document_json,'$.expiresAt') IN ('object','array') AND COALESCE((${standard}),0)=0`;
  try {
    const unusual=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_story_expiry_exceptions
      WHERE parent_path='stories' AND ${exception} LIMIT 65`).all();
    if((unusual.results||[]).length>64 || (unusual.results||[]).some(row=>row.path!==`stories/${row.doc_id}` || !isRootDocument(row.path))
        || new Set((unusual.results||[]).map(row=>row.doc_id)).size!==(unusual.results||[]).length)return null;
    let odd=(unusual.results||[]).map(canonicalContentDocument).filter(Boolean)
      .map(doc=>({...doc,data:profileReadView(doc.path,uid,doc.data)}));
    odd=applyQuery(odd,{...spec,limit:null});
    const visible=[];
    for(let pass=0;;pass++) {
      const chunk=pass===0?spec.limit:Math.min(100,Math.max(20,spec.limit*2));
      // SQLite does not seek the second component of a row comparison when
      // the first is an expression index. Use an equality seek within the tie,
      // then a separate strict time range, preserving the anchor's strict >.
      const rows=[];
      if(cursor && cursor[0]>anchor.millis) {
        const equal=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_story_expiry_page
          WHERE parent_path='stories' AND (${time})=? AND doc_id>? AND ${standard}
          ORDER BY doc_id ASC LIMIT ?`).bind(cursor[0],cursor[1],chunk+1).all();
        rows.push(...(equal.results||[]));
      }
      if(rows.length<chunk+1) {
        const later=await db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_story_expiry_page
          WHERE parent_path='stories' AND (${time})>? AND ${standard}
          ORDER BY (${time}) ASC,doc_id ASC LIMIT ?`).bind(Math.max(anchor.millis,cursor?.[0]??anchor.millis),chunk+1-rows.length).all();
        rows.push(...(later.results||[]));
      }
      const docs=rows.slice(0,chunk).map(canonicalContentDocument).filter(Boolean)
        .map(doc=>({...doc,data:profileReadView(doc.path,uid,doc.data)}));
      if(docs.length!==Math.min(rows.length,chunk) || new Set(docs.map(d=>d.id)).size!==docs.length)return null;
      const last=docs.at(-1);
      const nextCursor=last?[last.data.expiresAt.millis,last.id]:null;
      const split=rows.length<=chunk ? odd.length : odd.findIndex(doc=>
        compareDocumentToCursor(doc,[last.data.expiresAt,last.id],spec.orderBy)>0);
      const merged=sortDocuments([...docs,...odd.splice(0,split<0?odd.length:split)],spec.orderBy);
      // Authorize before applying the limit. Refill only as needed.
      for(let i=0;i<merged.length && visible.length<spec.limit;) {
        const count=Math.min(merged.length-i,spec.limit-visible.length);
        visible.push(...await visibleDocuments(db,uid,'stories',merged.slice(i,i+count),requestUserReads));
        i+=count;
      }
      if(visible.length>=spec.limit)return visible.slice(0,spec.limit);
      cursor=nextCursor;
      if(rows.length<=chunk)break;
    }
    return visible;
  } catch(error) {
    if(/no such index: idx_cf_story_(?:expiry_exceptions|expiry_page)/.test(String(error)))return null;
    throw error;
  }
}

// Reel discovery's video-post query has no explicit order: Firestore's default
// is ascending document ID. Seek only video rows and authorize before LIMIT.
// Non-ASCII/malformed IDs use the existing evaluator because SQLite binary and
// JavaScript UTF-16 order are not generally equivalent.
async function boundedVideoPostPage(db,uid,spec,requestUserReads) {
  const filters=spec?.filters;
  if(spec?.parent!=='posts' || !Array.isArray(filters) || filters.length!==1
      || filters[0]?.field!=='mediaType' || filters[0]?.op!=='==' || filters[0]?.value!=='video'
      || !Array.isArray(spec.orderBy) || spec.orderBy.length || spec.limitToLast
      || spec.startAt || spec.endAt || spec.endBefore || !Number.isInteger(spec.limit)
      || spec.limit<1 || spec.limit>100) return null;
  let cursor=null;
  if(spec.startAfter) {
    if(!Array.isArray(spec.startAfter) || spec.startAfter.length!==1
        || typeof spec.startAfter[0]!=='string' || !/^[\x20-\x7e]+$/.test(spec.startAfter[0])) return null;
    cursor=spec.startAfter[0];
  }
  const exception=`(doc_id='' OR doc_id GLOB '*[^ -~]*' OR instr(doc_id,char(0))>0 OR instr(doc_id,'/')>0 OR path<>'posts/'||doc_id)`;
  try {
    const unusual=await db.prepare(`SELECT doc_id FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_video_post_exceptions
      WHERE parent_path='posts' AND json_extract(document_json,'$.mediaType')='video' AND ${exception} LIMIT 1`).bind().all();
    if((unusual.results||[]).length) return null;
    const visible=[];
    let deniedBurst=1;
    for(;;) {
      const chunk=Math.min(100,Math.max(deniedBurst,spec.limit-visible.length));
      const bound=cursor===null?'':' AND doc_id>?';
      const args=cursor===null?[chunk]:[cursor,chunk];
      const page=await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
        FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_video_post_page
        WHERE parent_path='posts' AND json_extract(document_json,'$.mediaType')='video'${bound}
        ORDER BY doc_id ASC LIMIT ?`).bind(...args).all();
      const rows=page.results||[];
      const docs=rows.map(canonicalContentDocument).filter(Boolean);
      // Loaded rows are canonical; maintain the same request-local, fresh ACL.
      const allowed=await visibleDocuments(db,uid,'posts',docs,requestUserReads);
      visible.push(...allowed);
      deniedBurst=allowed.length?1:Math.min(100,chunk*2);
      if(visible.length>=spec.limit)return visible.slice(0,spec.limit);
      if(rows.length<chunk)break;
      cursor=rows.at(-1).doc_id;
    }
    return visible;
  } catch(error) {
    // Rolling deploys without the new indexes retain the exact old evaluator.
    if(/no such index: idx_cf_video_post_(?:page|exceptions)/.test(String(error)))return null;
    throw error;
  }
}

async function boundedFeedPage(db,uid,spec,requestUserReads) {
  const root=spec?.parent;
  if (root!=='posts' && root!=='reels') return null;
  const filters=Array.isArray(spec?.filters)?spec.filters:[];
  const author=filters.length===1 && filters[0]?.field==='authorId' && filters[0]?.op==='==' && typeof filters[0]?.value==='string' ? filters[0].value : null;
  const groupFilter=filters.find(f=>f?.field==='groupId' && f.op==='==' && typeof f.value==='string');
  // Group feed and its explicit pending tab only. Other query shapes retain the
  // generic evaluator; client-provided group IDs never grant access.
  const group=groupFilter && filters.filter(f=>f?.field==='groupId').length===1
      && filters.every(f=>f===groupFilter || (f?.field==='groupApprovalStatus' && f.op==='==' && f.value==='pending'))
      && filters.length<=2 ? groupFilter.value : null;
  const pending=group!==null && filters.some(f=>f?.field==='groupApprovalStatus');
  const groupIndex=pending?'idx_cf_group_pending_page':'idx_cf_group_posts_page';
  const scoped=author!==null?author:group;
  const scopedArgs=scoped===null?[]:[scoped];
  const byScoped=author!==null ? " AND json_extract(document_json,'$.authorId')=?"
      : group!==null ? " AND json_type(document_json,'$.groupId')='text' AND json_extract(document_json,'$.groupId')=?"
          +(pending?" AND json_extract(document_json,'$.groupApprovalStatus')='pending'":'') : '';
  if ((root==='reels' && filters.length) || !Array.isArray(spec.orderBy) || spec.orderBy.length!==1
      || spec.orderBy[0]?.field!=='createdAt' || spec.orderBy[0]?.direction!=='DESCENDING'
      || (filters.length && author===null && group===null) || spec.limitToLast || spec.startAt || spec.endAt || spec.endBefore
      || !Number.isInteger(spec.limit) || spec.limit<=0 || spec.limit>100) return null;
  let cursor=null;
  if (spec.startAfter) {
    const c=spec.startAfter;
    if (!Array.isArray(c) || c.length!==2 || typeof c[1]!=='string' || !/^[\x20-\x7e]+$/.test(c[1])) return null;
    const rank=typeof c[0]==='number'?2:c[0]?.__type==='timestamp'?4:0;
    const time=rank===2?c[0]:c[0]?.millis;
    if (!rank || !Number.isFinite(time) || Math.abs(time)>Number.MAX_SAFE_INTEGER) return null;
    cursor=[rank,rank===4?Math.trunc(time):time,c[1]];
  }
  const standard=`COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0`;
  const canonicalException=`(doc_id GLOB '*[^ -~]*' OR path<>'${root}/'||doc_id OR ${standard})`;
  const exceptionIndex=group!==null?'group_posts_exceptions':root==='posts'?'feed_exceptions':'reels_feed_exceptions';
  // A few unusual timestamps (including on author pages) must not force
  // the fallback. Merge only matching exceptions into the normal keyset stream.
  let unusual;
  try {
    unusual=await (
      db.prepare(`SELECT * FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_${exceptionIndex}
        WHERE parent_path='${root}'${byScoped} AND ${canonicalException} LIMIT 129`).bind(...scopedArgs).all()
    );
  } catch(error) {
    if (/no such index: idx_cf_(?:(?:legacy_)?(?:reels_)?feed_exceptions|group_posts_exceptions)/.test(String(error))) return null;
    throw error;
  }
  const odd=unusual.results||[];
  if (odd.length>128 || odd.some(row=>row.path!==`${root}/${row.doc_id}`)) return null;
  const exceptions=applyQuery(odd.map(canonicalContentDocument).filter(Boolean),{...spec,limit:null});
  const rank=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END`;
  const time=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN json_extract(a.document_json,'$.createdAt') ELSE json_extract(a.document_json,'$.createdAt.millis') END`;
  const visible=[];
  // Normal pages need no speculative double-size fetch. Refill in larger
  // batches only when privacy filtering leaves gaps.
  for (let pass=0;;pass++) {
    const chunk=pass===0 ? spec.limit : Math.min(100,Math.max(20,spec.limit*2));
    const bound=cursor?` AND (${rank},${time},a.doc_id)<(?,?,?)`:'';
    const args=[...scopedArgs,...(cursor||[]),chunk];
    let batches;
    try {
      // SQLite's tuple comparison on the expression index can scan from the
      // newest row on every subsequent page. Seek the cursor's rank and time
      // explicitly, then include the lower numeric rank when a timestamp
      // cursor crosses that boundary. Each bounded branch keeps index ordering;
      // the outer merge is limited to at most two page-sized windows.
      const seekWindow=(columns,table,index,condition,prefix=[])=>{
        const base=`SELECT ${columns},${rank} AS quota_feed_rank,${time} AS quota_feed_time FROM ${table} a INDEXED BY ${index} WHERE ${condition}`;
        const same=`${base} AND ${rank}=? AND ${time}<=? AND (${time},a.doc_id)<(?,?) ORDER BY ${time} DESC,a.doc_id DESC LIMIT ?`;
        if(cursor[0]===2) return db.prepare(same).bind(...prefix,2,cursor[1],cursor[1],cursor[2],chunk).all();
        const lower=`${base} AND ${rank}=? ORDER BY ${time} DESC,a.doc_id DESC LIMIT ?`;
        return db.prepare(`SELECT * FROM (${same}) UNION ALL SELECT * FROM (${lower})
          ORDER BY quota_feed_rank DESC,quota_feed_time DESC,doc_id DESC LIMIT ?`)
          .bind(...prefix,4,cursor[1],cursor[1],cursor[2],chunk,...prefix,2,chunk,chunk).all();
      };
      if (cursor && group!==null) {
        batches=await Promise.all([
          seekWindow('a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time',
            'friendbook_cf_firestore_documents',groupIndex,`a.parent_path='posts'${byScoped} AND NOT ${canonicalException}`,[group]),
        ]);
      } else if (cursor && author===null) {
        batches=await Promise.all([
          seekWindow('a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time',
            'friendbook_cf_firestore_documents',`idx_cf_${root}_feed_page`,`a.parent_path='${root}' AND NOT ${canonicalException}`),
        ]);
      } else if (cursor && root==='posts' && author!==null) {
        // Profile pages use the existing author-prefixed indexes. Without an
        // explicit rank/time seek SQLite walks every newer post by that author
        // on each older page. Keep the old bounded query if an older schema has
        // not yet installed the index; never bypass ACL.
        try {
          batches=await Promise.all([
            seekWindow('a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time',
              'friendbook_cf_firestore_documents','idx_cf_posts_author_page',`a.parent_path='posts'${byScoped} AND NOT ${canonicalException}`,[author]),
          ]);
        } catch (error) {
          if (!/no such index: idx_cf_(?:legacy_)?posts_author_page/.test(String(error))) throw error;
          batches=await Promise.all([
            db.prepare(`SELECT a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time
              FROM friendbook_cf_firestore_documents a WHERE a.parent_path='posts'${byScoped}${bound} AND NOT ${canonicalException}
              ORDER BY ${rank} DESC,${time} DESC,a.doc_id DESC LIMIT ?`).bind(...args).all(),
          ]);
        }
      } else {
        batches=await Promise.all([
          db.prepare(`SELECT a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time
            FROM friendbook_cf_firestore_documents a ${group!==null?`INDEXED BY ${groupIndex}`:author===null?`INDEXED BY idx_cf_${root}_feed_page`:''} WHERE a.parent_path='${root}'${byScoped}${bound} AND NOT ${canonicalException}
            ORDER BY ${rank} DESC,${time} DESC,a.doc_id DESC LIMIT ?`).bind(...args).all(),
        ]);
      }
    } catch(error) {
      if (/no such index: idx_cf_(?:(?:legacy_)?(?:posts|reels)_feed_page|group_posts_page|group_pending_page)/.test(String(error))) return null;
      throw error;
    }
    const [canonical]=batches;

    const docs=sortDocuments((canonical.results||[]).map(canonicalContentDocument).filter(Boolean),spec.orderBy).slice(0,chunk);
    const last=docs.at(-1), value=last?.data.createdAt;
    const nextCursor=last?[typeof value==='number'?2:4,typeof value==='number'?value:value.millis,last.id]:null;
    const split=docs.length<chunk ? exceptions.length : exceptions.findIndex(doc=>
      compareDocumentToCursor(doc,[value,last.id],spec.orderBy)>0);
    const merged=sortDocuments([...docs,...exceptions.splice(0,split<0?exceptions.length:split)],spec.orderBy)
      .filter(doc=>filters.every(filter=>matchesFilter(doc.data,filter,doc.id)));
    // The same checks used by the normal document query; privacy is never inferred
    // from the client-supplied author, a stale legacy mirror, or the SQL sort key.
    let deniedBurst=1;
    for(let i=0;i<merged.length && visible.length<spec.limit;) {
      // Do not authorize the entire SQL lookahead when a short page is nearly
      // full. If a candidate is hidden, advance and fill the vacant slot.
      const count=Math.min(chunk,Math.max(deniedBurst,spec.limit-visible.length));
      const allowed=await visibleDocuments(db,uid,root,merged.slice(i,i+count),requestUserReads);
      visible.push(...allowed);
      deniedBurst=allowed.length ? 1 : Math.min(chunk,count*2);
      i+=count;
    }
    if (visible.length>=spec.limit) return visible.slice(0,spec.limit);
    cursor=nextCursor;
    if (docs.length<chunk) break;
  }
  return visible;
}

async function boundedNotificationPage(db,uid,spec) {
  const filters=Array.isArray(spec?.filters)?spec.filters:[];
  const recipient=filters.filter(f=>f?.field==='toUserId' && f.op==='==' && f.value===uid);
  const unread=filters.filter(f=>f?.field==='isRead' && f.op==='==' && f.value===false);
  if (recipient.length!==1 || unread.length>1 || filters.length!==recipient.length+unread.length) return null;
  const recipientArgs=[uid];
  const field="json_extract(document_json,'$.toUserId')";
  const byRecipient=` AND ${field}=?`;
  const unreadOnly=unread.length?" AND json_type(document_json,'$.isRead')='false'":'';
  const delta=spec?.limitToLast===true && !!spec.endBefore && !spec.startAfter;
  if (spec?.parent!=='notifications' || !Array.isArray(spec.orderBy) || spec.orderBy.length!==1
      || spec.orderBy[0]?.field!=='createdAt' || spec.orderBy[0]?.direction!=='DESCENDING'  || (!delta && (spec.limitToLast || spec.endBefore)) || spec.startAt || spec.endAt
      || (spec.orderBy[0].fieldPath!==undefined) || !Number.isInteger(spec.limit) || spec.limit<=0 || spec.limit>100) return null;
  let cursor=null;
  if (spec.startAfter || delta) {
    const c=delta?spec.endBefore:spec.startAfter;
    if (!Array.isArray(c) || c.length!==2 || typeof c[1]!=='string' || !/^[\x20-\x7e]+$/.test(c[1])) return null;
    const rank=typeof c[0]==='number'?2:c[0]?.__type==='timestamp'?4:0;
    const time=rank===2?c[0]:c[0]?.millis;
    if (!rank || !Number.isFinite(time) || Math.abs(time)>Number.MAX_SAFE_INTEGER || (rank===4 && !Number.isInteger(time))) return null;
    cursor=[rank,rank===4?Math.trunc(time):time,c[1]];
  }
  const standard=`COALESCE(((json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
    OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
      AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)),0)=0`;
  const probe=async (table,index,where,args)=>{
    try { return await db.prepare(`SELECT 1 AS hit FROM ${table} a INDEXED BY ${index} WHERE ${where} LIMIT 1`).bind(...args).first(); }
    catch(error) {
      if (!String(error).includes(`no such index: ${index}`)) throw error;
      return db.prepare(`SELECT 1 AS hit FROM ${table} a WHERE ${where} LIMIT 1`).bind(...args).first();
    }
  };
  const canonicalBad=await probe('friendbook_cf_firestore_documents','idx_cf_notif_exceptions',`parent_path='notifications'${byRecipient}${unreadOnly}
      AND (doc_id GLOB '*[^ -~]*' OR path<>'notifications/'||doc_id OR ${standard})`,recipientArgs);
  if (canonicalBad) return null;
  const rank=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END`;
  const time=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN json_extract(a.document_json,'$.createdAt') ELSE json_extract(a.document_json,'$.createdAt.millis') END`;
  const chunk=spec.limit, direction=delta?'ASC':'DESC';
  const seekSpec=delta?{...spec,orderBy:[{field:'createdAt',direction:'ASCENDING'}]}:spec;
  const visible=[];
  const ordered=`ORDER BY ${rank} ${direction},${time} ${direction},a.doc_id ${direction} LIMIT ?`;
  async function readStore(bound,args) {
    return db.prepare(`SELECT a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time
      FROM friendbook_cf_firestore_documents a WHERE a.parent_path='notifications'${byRecipient}${unreadOnly}${bound}
      ${ordered}`).bind(...args).all();

  }
  async function readWindow() {
    if(!delta) {
      const bound=cursor?` AND (${rank},${time},a.doc_id)<(?,?,?)`:'';
      if (cursor) {
        // Expose rank and time as independent index bounds. Timestamp pages
        // must also include older numeric rows; limit both branches before
        // merging so deep inbox pages do not walk the newer notifications.
        const index=unread.length?'idx_cf_notif_unread':'idx_cf_notif_page';
        const columns='a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time';
        const table='friendbook_cf_firestore_documents';
        const condition=`a.parent_path='notifications'${byRecipient}${unreadOnly}`;
        try {
          const base=`SELECT ${columns},${rank} AS quota_notif_rank,${time} AS quota_notif_time FROM ${table} a INDEXED BY ${index} WHERE ${condition}`;
          const same=`${base} AND (${rank})=? AND (${time})<=? AND (${time},a.doc_id)<(?,?) ORDER BY ${time} DESC,a.doc_id DESC LIMIT ?`;
          if(cursor[0]===2)return await db.prepare(same).bind(uid,2,cursor[1],cursor[1],cursor[2],chunk).all();
          const lower=`${base} AND (${rank})=2 ORDER BY ${time} DESC,a.doc_id DESC LIMIT ?`;
          return await db.prepare(`SELECT * FROM (${same}) UNION ALL SELECT * FROM (${lower})
            ORDER BY quota_notif_rank DESC,quota_notif_time DESC,doc_id DESC LIMIT ?`)
            .bind(uid,4,cursor[1],cursor[1],cursor[2],chunk,uid,chunk,chunk).all();
        } catch(error) {
          if (!String(error).includes(`no such index: ${index}`)) throw error;
          // Safe with older schemas: retain the original unhinted query.
        }
      }
      return readStore(bound,[...recipientArgs,...(cursor||[]),chunk]);
    }
    // The cache requests the first page after its anchor, not the newest
    // page in the burst. Explicit rank/time seeks avoid reading old inbox rows.
    const results=[];
    for(const group of [2,4]) {
      if(group<cursor[0] || results.length>=chunk)continue;
      const atAnchorRank=group===cursor[0];
      const bound=` AND (${rank})=?`+(atAnchorRank?` AND (${time})>=? AND ((${time})>? OR a.doc_id>?)`:'');
      const args=[...recipientArgs,group,...(atAnchorRank?[cursor[1],cursor[1],cursor[2]]:[]),chunk-results.length];
      results.push(...((await readStore(bound,args)).results||[]));
    }
    return {results};
  }
  for (;;) {
    const canonical=await readWindow();
    const docs=sortDocuments((canonical.results||[]).map(rowToDocument).filter(Boolean),seekSpec.orderBy).slice(0,chunk);
    if (!docs.length) break;
    const allowed=docs.filter(doc=>canReadNotification(doc.path,uid,doc.data));
    visible.push(...allowed);
    if (visible.length>=spec.limit) return sortDocuments(visible.slice(0,spec.limit),spec.orderBy);
    const last=docs[docs.length-1], value=last.data.createdAt;
    cursor=[typeof value==='number'?2:4,typeof value==='number'?value:value.millis,last.id];
    if (docs.length<chunk) break;
  }
  return delta?sortDocuments(visible,spec.orderBy):visible;
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
    if (isRootDocument(path)) await db.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(path).run();
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

// The legacy Android acceptance batch is recognized as one strictly defined operation.
// All authorization snapshots, duplicate checks and writes run in a single D1 batch.
async function acceptFriendBatch(db, uid, ops, now, indexedCandidates=false) {
  const forbidden = () => json({error:'Forbidden'},403);
  if (ops.length !== 4 || typeof db.batch !== 'function') return forbidden();
  const requests=ops.filter(o=>splitPath(o.path)?.length===2 && splitPath(o.path)[0]==='friend_requests');
  const friends=ops.filter(o=>splitPath(o.path)?.length===2 && splitPath(o.path)[0]==='friends');
  if (requests.length!==1 || friends.length!==1) return forbidden();
  const reqOp=requests[0], friendOp=friends[0];
  const type=o=>o.op || o.type || 'set';
  const fields=o=>o.fields || o.updates || o.data || {};
  if (type(reqOp)!=='update' || JSON.stringify(fields(reqOp))!==JSON.stringify({status:'accepted'})
      || type(friendOp)!=='set' || friendOp.merge || friendOp.mergeFields || !isDocumentPath(friendOp.path)) return forbidden();
  const reqRow=await readRow(db,reqOp.path);
  if (!reqRow || await readRow(db,friendOp.path)) return forbidden();
  const request=decodeValue(JSON.parse(reqRow.document_json));
  const other=request.fromUid;
  if (!validSegment(other) || other.length>128 || other===uid || request.toUid!==uid || request.status!=='pending') return forbidden();
  const userOps=ops.filter(o=>splitPath(o.path)?.length===2 && splitPath(o.path)[0]==='users');
  if (userOps.length!==2 || new Set(userOps.map(o=>o.path)).size!==2
      || !userOps.some(o=>o.path===`users/${uid}`) || !userOps.some(o=>o.path===`users/${other}`)) return forbidden();
  for (const o of userOps) {
    const f=fields(o), v=f.friendsCount;
    if (type(o)!=='update' || Object.keys(f).length!==1 || !v || v.__fieldValue!=='increment' || v.value!==1 || Object.keys(v).some(k=>!['__fieldValue','value'].includes(k))) return forbidden();
  }
  const input=friendOp.data;
  if (!input || Object.keys(input).some(k=>!['participants','createdAt'].includes(k))
      || !Array.isArray(input.participants) || input.participants.length!==2
      || !input.participants.includes(uid) || !input.participants.includes(other)
      || input.createdAt?.__fieldValue!=='serverTimestamp' || Object.keys(input.createdAt).length!==1) return forbidden();
  const userRows=await Promise.all([readRow(db,`users/${uid}`),readRow(db,`users/${other}`)]);
  if (userRows.some(r=>!r)) return forbidden();
  const users=userRows.map(r=>decodeValue(JSON.parse(r.document_json)));
  for (let i=0;i<2;i++) {
    const u=users[i], peer=i===0?other:uid;
    if (u.banned===true || u.deactivated===true) return forbidden();
    for (const key of ['blocked','blockedUsers']) {
      if (u[key] !== undefined && !Array.isArray(u[key])) return forbidden();
      if (u[key]?.includes(peer)) return forbidden();
    }
    if (u.friendsCount !== undefined && (!Number.isSafeInteger(u.friendsCount) || u.friendsCount < 0 || u.friendsCount >= Number.MAX_SAFE_INTEGER)) return forbidden();
  }
  // A fresh, recipient-accepted request can safely reconfirm a legacy friendship.
  // Reuse the existing row and counters: neither legacy fields nor a one-sided
  // action alone grants access to friends-only content.
  const candidates=await friendshipCandidates(db,uid,indexedCandidates);
  const existingPair = await db.prepare(`SELECT path FROM (${candidates.sql}) d WHERE (json_array_length(document_json,'$.participants')=2 AND
    ((json_extract(document_json,'$.participants[0]')=? AND json_extract(document_json,'$.participants[1]')=?) OR
     (json_extract(document_json,'$.participants[0]')=? AND json_extract(document_json,'$.participants[1]')=?))) OR
    (json_array_length(document_json,'$.fields.participants.arrayValue.values')=2 AND
    ((json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?) OR
     (json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?))) ORDER BY path LIMIT 1`).bind(...candidates.args,uid,other,other,uid,uid,other,other,uid).first();
  const prior=existingPair ? await readRow(db,existingPair.path) : null;
  if (existingPair && (!prior || !friendshipReadView(uid,decodeValue(JSON.parse(prior.document_json)))?.participants.includes(other))) return json({error:'Friendship changed'},409);
  const snapshots=[reqRow,...userRows,...(prior?[prior]:[])];
  // Keep all snapshot, duplicate and absence checks in the same D1 batch,
  // but store their combined assertion in one temporary guard row.
  const guardPredicates=[],guardArgs=[];
  for (const row of snapshots) {
    // Canonical root reads already contain the exact JSON checked in the batch.
    const canonical=row[canonicalRootSnapshot] ? row : await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();

    if (!canonical || canonical.document_json!==row.document_json) return json({error:'Request changed'},409);
    guardPredicates.push(`((SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?)`);
    guardArgs.push(row.path,row.document_json);
  }
  // Check both native and historical array envelopes, with canonical rows winning over mirrors.
  if (!prior) {
    const duplicateGuard=friendshipDuplicateGuard(candidates,uid,other);
    guardPredicates.push(duplicateGuard.sql);
    guardArgs.push(...duplicateGuard.args);
  }
  // A create must not overwrite a canonical row, even if its ID is guessed.
  guardPredicates.push(`NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?)
   `);
  guardArgs.push(friendOp.path);
  const guards=[db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN
    ${guardPredicates.map(p=>`(${p})`).join(' AND ')} THEN 1 ELSE 0 END`).bind(...guardArgs)];
  const next=[{...request,status:'accepted'},...users.map(u=>({...u,friendsCount:(u.friendsCount || 0)+(prior?0:1)})),...(prior?[]:[{participants:[uid,other],createdAt:{__type:'timestamp',millis:now}}])];
  const paths=[reqOp.path,`users/${uid}`,`users/${other}`,...(prior?[]:[friendOp.path])];
  const writes=[];
  for (let i=0;i<paths.length;i++) {
    const p=paths[i], coll=collectionIdOf(p), id=docIdOf(p), data=JSON.stringify(next[i]);
    const owner=ownerOf(next[i],i===3?uid:(snapshots[i]?.owner_uid || uid));
    writes.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(p,coll,coll,id,owner,data,snapshots[i]?.create_time||now,now));
    writes.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(p,coll,id,owner,JSON.stringify({...next[i],id}),now));
  }
  const [low,high]=[uid,other].sort();
  const ledger=db.prepare('INSERT INTO friendbook_cf_trusted_friendships(uid_low,uid_high,friend_path) VALUES (?,?,?)').bind(low,high,prior?.path||friendOp.path);
  // Older clients could create several identical pending requests. Accepting one
  // is a recipient-authorized decision for the same sender/recipient pair, so
  // resolve those duplicates in the same transaction without deleting history.
  const duplicateCanonical=db.prepare(`UPDATE friendbook_cf_firestore_documents SET
    document_json=json_set(document_json,'$.status','accepted'),update_time=?
    WHERE parent_path='friend_requests' AND json_extract(document_json,'$.fromUid')=?
      AND json_extract(document_json,'$.toUid')=? AND json_extract(document_json,'$.status')='pending'`).bind(now,other,uid);
  const duplicateLegacy=db.prepare(`UPDATE friendbook_cf_documents AS a SET
    document_json=CASE WHEN json_type(document_json,'$.fields')='object'
      THEN json_set(document_json,'$.fields.status.stringValue','accepted')
      ELSE json_set(document_json,'$.status','accepted') END, updated_at=?
    WHERE collection='friend_requests'
      AND COALESCE(json_extract(document_json,'$.fields.fromUid.stringValue'),json_extract(document_json,'$.fromUid'))=?
      AND COALESCE(json_extract(document_json,'$.fields.toUid.stringValue'),json_extract(document_json,'$.toUid'))=?
      AND COALESCE(json_extract(document_json,'$.fields.status.stringValue'),json_extract(document_json,'$.status'))='pending'
      AND (NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
        OR EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path
          AND json_extract(f.document_json,'$.fromUid')=? AND json_extract(f.document_json,'$.toUid')=?
          AND json_extract(f.document_json,'$.status')='accepted'))`).bind(now,other,uid,other,uid);
  await db.batch([...guards,ledger,...writes,duplicateCanonical,duplicateLegacy,db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
  return json({ok:true,operations:ops.length});
}

// Remove all records for one pair and adjust both counters together. A caller may
// never supply another pair, an arbitrary counter delta, or a partial duplicate set.
async function unfriendBatch(db,uid,ops,now,indexedCandidates=false) {
  const forbidden=()=>json({error:'Forbidden'},403);
  if (typeof db.batch!=='function' || ops.length<3 || ops.length>21) return forbidden();
  const type=o=>o.op || o.type || 'set';
  const friendOps=ops.filter(o=>splitPath(o.path)?.length===2 && splitPath(o.path)[0]==='friends');
  const userOps=ops.filter(o=>splitPath(o.path)?.length===2 && splitPath(o.path)[0]==='users');
  if (!friendOps.length || friendOps.length+2!==ops.length || userOps.length!==2
      || new Set(friendOps.map(o=>o.path)).size!==friendOps.length
      || friendOps.some(o=>type(o)!=='delete') || new Set(userOps.map(o=>o.path)).size!==2) return forbidden();
  const friendRows=await Promise.all(friendOps.map(o=>readRow(db,o.path)));
  if (friendRows.some(r=>!r)) return forbidden();
  const views=friendRows.map(r=>friendshipReadView(uid,decodeValue(JSON.parse(r.document_json))));
  if (views.some(v=>!v)) return forbidden();
  const other=views[0].participants.find(x=>x!==uid);
  if (!validSegment(other) || views.some(v=>!v.participants.includes(other))) return forbidden();
  if (!userOps.some(o=>o.path===`users/${uid}`) || !userOps.some(o=>o.path===`users/${other}`)) return forbidden();
  for (const o of userOps) {
    const f=o.fields||o.updates||o.data||{}, v=f.friendsCount;
    if (type(o)!=='update' || Object.keys(f).length!==1 || v?.__fieldValue!=='increment'
        || v.value!==-friendOps.length || Object.keys(v).some(k=>!['__fieldValue','value'].includes(k))) return forbidden();
  }
  const userRows=await Promise.all([readRow(db,`users/${uid}`),readRow(db,`users/${other}`)]);
  if (userRows.some(r=>!r)) return forbidden();
  const users=userRows.map(r=>decodeValue(JSON.parse(r.document_json)));
  if (users.some(u=>u.friendsCount!==undefined && (!Number.isSafeInteger(u.friendsCount) || u.friendsCount<0))) return forbidden();
  // The common one-friend removal can assert its three snapshots and exact
  // duplicate count in one transactional guard row. Larger legacy duplicate
  // batches retain the old guards and avoid exceeding SQL parameter limits.
  const coalesce=friendRows.length===1,guards=[],guardPredicates=[],guardArgs=[];
  for (const row of [...friendRows,...userRows]) {
    // Keep the transaction guard; a second read of an already-canonical
    // snapshot before the batch cannot improve its race protection.
    const canonical=row[canonicalRootSnapshot] ? row : await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();

    if (!canonical || canonical.document_json!==row.document_json) return json({error:'Friendship changed'},409);
    const predicate=`((SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?)`;
    const args=[row.path,row.document_json];
    if(coalesce){guardPredicates.push(predicate);guardArgs.push(...args);}
    else guards.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ${predicate} THEN 1 ELSE 0 END`).bind(...args));
  }
  // Refuse a partial removal if duplicate friendship rows exist. Test both native
  // JSON and historical Firestore envelopes, ignoring stale legacy mirrors.
  const candidates=await friendshipCandidates(db,uid,indexedCandidates);
  const countPredicate=`(SELECT count(*) FROM (${candidates.sql}) d WHERE (
    (json_array_length(d.document_json,'$.participants')=2 AND
     ((json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?) OR
      (json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?))) OR
    (json_array_length(d.document_json,'$.fields.participants.arrayValue.values')=2 AND
     ((json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?) OR
      (json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?)))
  ))=?`;
  const countArgs=[...candidates.args,uid,other,other,uid,uid,other,other,uid,friendRows.length];
  if(coalesce){
    guardPredicates.push(countPredicate);guardArgs.push(...countArgs);
    guards.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN
      ${guardPredicates.map(p=>`(${p})`).join(' AND ')} THEN 1 ELSE 0 END`).bind(...guardArgs));
  } else guards.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ${countPredicate} THEN 1 ELSE 0 END`).bind(...countArgs));
  const statements=[...guards];
  for (const row of friendRows) {
    statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path));
    statements.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(row.path));
  }
  const [low,high]=[uid,other].sort();
  statements.push(db.prepare('DELETE FROM friendbook_cf_trusted_friendships WHERE uid_low=? AND uid_high=?').bind(low,high));
  for (let i=0;i<2;i++) {
    const row=userRows[i], id=docIdOf(row.path), doc={...users[i],friendsCount:Math.max(0,(users[i].friendsCount||0)-friendRows.length)}, data=JSON.stringify(doc);
    statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(row.path,'users','users',id,row.owner_uid||id,data,row.create_time||now,now));
    statements.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(row.path,'users',id,row.owner_uid||id,JSON.stringify({...doc,id}),now));
  }
  statements.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  await db.batch(statements);
  return json({ok:true,operations:ops.length});
}

// Reviews are restricted to a server-configured Firebase UID and bind the
// pending request and target profile to the same conditional D1 transaction.
const canReadSubscription=(env,uid,data)=>isPaymentAdmin(env,uid) || data?.subscriberId===uid || data?.creatorId===uid;
async function reviewPayment(db,env,uid,paymentPath,update,profileOp,now) {
  const forbidden=()=>json({error:'Forbidden'},403);
  if (!isPaymentAdmin(env,uid) || typeof db.batch!=='function' || !isDocumentPath(paymentPath) || splitPath(paymentPath)[0]!=='payments') return forbidden();
  const action=update?.status, prefix=action==='approved'?'approved':action==='rejected'?'rejected':'';
  if (!prefix || Object.keys(update).length!==3 || update[`${prefix}By`]!==uid
      || update[`${prefix}At`]?.__fieldValue!=='serverTimestamp' || Object.keys(update[`${prefix}At`]).length!==1) return forbidden();
  const payRow=await readRow(db,paymentPath);
  if (!payRow) return forbidden();
  const payment=decodeValue(JSON.parse(payRow.document_json));
  // Existing paid subscriptions are reviewed manually by the configured admin;
  // this does not create payments or claim that an external charge was verified.
  const subscription=payment.type==='subscription';
  if (payment.status!=='pending' || !validSegment(payment.userId)) return forbidden();
  if (subscription) {
    if (!validSegment(payment.subscriptionId) || !Number.isFinite(payment.amount) || payment.amount<=0
        || typeof payment.currency!=='string' || !/^[A-Z]{3}$/.test(payment.currency) || payment.currency==='FREE') return forbidden();
  } else if (!canCreateFreeReview(payment.userId,{...payment,status:'pending'})) return forbidden();
  let profileRow=null, profile=null;
  if (action==='approved' && subscription) {
    const target=`subscriptions/${payment.subscriptionId}`, fields=profileOp?.fields||profileOp?.updates||profileOp?.data;
    if (profileOp?.path!==target || (profileOp.op||profileOp.type)!=='update' || !fields
        || Object.keys(fields).length!==1 || fields.status!=='active') return forbidden();
    profileRow=await readRow(db,target);
    if (!profileRow) return forbidden();
    const before=decodeValue(JSON.parse(profileRow.document_json));
    if (before.status!=='pending' || before.subscriberId!==payment.userId || !validSegment(before.creatorId)
        || before.creatorId===payment.userId || (before.paymentId!==undefined && before.paymentId!==docIdOf(paymentPath))
        || (payment.creatorId!==undefined && payment.creatorId!==before.creatorId)) return forbidden();
    profile={...before,status:'active',expiry:{__type:'timestamp',millis:now+30*86400000}};
  } else if (action==='approved') {
    const level=payment.type.slice(5), value=profileOp?.data;
    if (!profileOp || (profileOp.op||profileOp.type)!=='set' || profileOp.merge!==true
        || profileOp.mergeFields || profileOp.path!==`users/${payment.userId}` || !value
        || Object.keys(value).length!==2 || value.tickLevel!==level || value.isVerified!==true) return forbidden();
    profileRow=await readRow(db,profileOp.path);
    if (!profileRow) return forbidden();
    profile=decodeValue(JSON.parse(profileRow.document_json));
    const rank={none:0,blue:1,gold:2,diamond:3};
    if (profile.tickLevel!==undefined && !Object.hasOwn(rank,profile.tickLevel)) return forbidden();
    profile={...profile,isVerified:true,tickLevel:rank[profile.tickLevel||'none']>rank[level]?profile.tickLevel:level};
  } else if (profileOp) return forbidden();
  const rows=profileRow?[payRow,profileRow]:[payRow], guards=[];
  for (const row of rows) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();

    if (!canonical || canonical.document_json!==row.document_json) return json({error:'Review changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ? THEN 1 ELSE 0 END').bind(row.path,row.document_json));
  }
  const next=[{...payment,status:action,[`${prefix}By`]:uid,[`${prefix}At`]:{__type:'timestamp',millis:now}},...(profile?[profile]:[])];
  const writes=[];
  for (let i=0;i<rows.length;i++) {
    const row=rows[i], id=docIdOf(row.path), coll=collectionIdOf(row.path), data=JSON.stringify(next[i]), owner=row.owner_uid||payment.userId;
    writes.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(row.path,coll,coll,id,owner,data,row.create_time||now,now));
    writes.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(row.path,coll,id,owner,JSON.stringify({...next[i],id}),now));
  }
  await db.batch([...guards,...writes,db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
  return json({ok:true});
}

// Only groups created through this endpoint receive a server-owned authority record.
async function writeGroup(db,uid,path,beforeRow,after,now) {
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  const id=docIdOf(path), before=beforeRow?decodeValue(JSON.parse(beforeRow.document_json)):null;
  const owner=await trustedGroupOwner(db,path);
  if (!before) {
    if (owner || !after || !canCreateGroup(uid,after)) return json({error:'Forbidden'},403);
  } else if (!owner) {
    // Historical ownership and invites are untrusted. Only self-service array
    // changes are allowed; this does not grant access to private group content.
    if (!after) return json({error:'Forbidden'},403);
    const wasMember=Array.isArray(before.members) && before.members.includes(uid);
    const isMember=Array.isArray(after.members) && after.members.includes(uid);
    if (!wasMember && isMember && (String(before.privacy||'').toLowerCase()!=='public' || before.requireJoinApproval===true)) return json({error:'Forbidden'},403);
    const roles=['adminIds','moderatorIds'];
    for (const key of roles) {
      const a=before[key]||[], b=after[key]||[];
      if (!isSelfArrayChange(a,b,uid) || (!a.includes(uid) && b.includes(uid))) return json({error:'Forbidden'},403);
    }
    if (!canSelfManageGroup(uid,{...before,adminIds:after.adminIds,moderatorIds:after.moderatorIds},after)) return json({error:'Forbidden'},403);
  } else {
    if (!after ? !canManageGroup(uid,owner,before) || uid!==owner : !canUpdateGroup(uid,owner,before,after)) return json({error:'Forbidden'},403);
  }
  if (after && JSON.stringify(after).length>95000) return json({error:'Document too large'},413);
  if (after && beforeRow?.[canonicalRootSnapshot] && JSON.stringify(after)===beforeRow.document_json)
    return json({...rowToDocument(beforeRow),data:groupReadView(uid,after,owner)});
  const stmts=[];
  if (beforeRow) {
    const canonical=beforeRow[canonicalRootSnapshot] ? beforeRow : await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();

    if (!canonical || canonical.document_json!==beforeRow.document_json) return json({error:'Group changed'},409);
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ((SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) AND ((? IS NULL AND NOT EXISTS (SELECT 1 FROM friendbook_cf_trusted_groups WHERE group_path=?)) OR (? IS NOT NULL AND (SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?) = ?)) THEN 1 ELSE 0 END').bind(path,beforeRow.document_json,owner,path,owner,path,owner));
  } else {
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path));
    stmts.push(db.prepare('INSERT INTO friendbook_cf_trusted_groups(group_path,owner_uid) VALUES (?,?)').bind(path,uid));
  }
  if (!after) {
    stmts.push(db.prepare('DELETE FROM friendbook_cf_trusted_groups WHERE group_path=?').bind(path));
    stmts.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
    stmts.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
  } else {
    const data=JSON.stringify(after), ownerUid=owner||beforeRow?.owner_uid||uid;
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,'groups','groups',id,ownerUid,data,beforeRow?.create_time||now,now));
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,'groups',id,ownerUid,JSON.stringify({...after,id}),now));
  }
  stmts.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  await db.batch(stmts);
  if (!after) return json({deleted:true,path});
  return json({exists:true,id,path,parent:'groups',collection:'groups',data:groupReadView(uid,after,beforeRow ? owner : uid),createTime:beforeRow?.create_time||now,updateTime:now},beforeRow?200:201);
}

// Group posts depend on current group membership and approval settings. The
// membership snapshot is checked inside the same transaction as the post write.
async function writeGroupPost(db,uid,path,beforeRow,after,now) {
  const forbidden=()=>json({error:'Forbidden'},403);
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  const before=beforeRow?decodeValue(JSON.parse(beforeRow.document_json)):null;
  const groupId=before?.groupId || after?.groupId;
  if (!validSegment(groupId) || (before && after && before.groupId!==after.groupId)) return forbidden();
  const groupPath=`groups/${groupId}`, groupRow=await readRow(db,groupPath);
  if (!groupRow) return forbidden();
  const group=decodeValue(JSON.parse(groupRow.document_json)), owner=await trustedGroupOwner(db,groupPath);
  if (!owner || group.adminId!==owner || !Array.isArray(group.members) || !group.members.includes(uid)
      || !Array.isArray(group.bannedMemberIds) || group.bannedMemberIds.includes(uid)) return forbidden();
  const manager=canManageGroup(uid,owner,group) || (Array.isArray(group.moderatorIds) && group.moderatorIds.includes(uid));
  if (!before) {
    if (!after || after.authorId!==uid || (group.postPermission==='Admins & Moderators' && !manager)) return forbidden();
    after={...after,groupApprovalStatus:group.requirePostApproval===true && !manager ? 'pending' : 'approved'};
  } else if (!after) {
    if (before.authorId!==uid && !manager) return forbidden();
  } else {
    if (after.authorId!==before.authorId) return forbidden();
    const keys=new Set([...Object.keys(before),...Object.keys(after)]);
    const onlyApproval=[...keys].every(k=>k==='groupApprovalStatus' || JSON.stringify(before[k])===JSON.stringify(after[k]));
    if (onlyApproval && before.groupApprovalStatus==='pending' && ['approved','declined'].includes(after.groupApprovalStatus) && manager) {
      // Group management can only perform the review transition, not edit content.
    } else if (before.authorId!==uid || JSON.stringify(before.groupApprovalStatus)!==JSON.stringify(after.groupApprovalStatus)) return forbidden();
  }
  if (after && JSON.stringify(after).length>1_800_000) return json({error:'Document too large'},413);
  if (after && beforeRow?.[canonicalRootSnapshot] && groupRow[canonicalRootSnapshot]) {
    // For existing canonical posts, check all authorization snapshots in the
    // upsert itself. NULL path aborts the entire batch and triggers a retry.
    // Creates, deletes and historical rows keep the original guards below.
    const coll=collectionIdOf(path),id=docIdOf(path),data=JSON.stringify(after);
    await db.batch([
      db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
        VALUES (CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?1)=?2
          AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?3)=?4
          AND (SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?3)=?5
          THEN ?1 ELSE NULL END,?6,?6,?7,?8,?9,?10,?11)
        ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
        .bind(path,beforeRow.document_json,groupPath,groupRow.document_json,owner,coll,id,after.authorId,data,beforeRow.create_time||now,now),
      db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`)
        .bind(path,coll,id,after.authorId,JSON.stringify({...after,id}),now),
      db.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state='verified' WHERE friendbook_cf_interaction_resources.state IS NOT 'verified'").bind(path),
    ]);
    return json({exists:true,id,path,parent:coll,collection:coll,data:after,createTime:beforeRow.create_time||now,updateTime:now});
  }
  const guards=[];
  for (const row of [groupRow,...(beforeRow?[beforeRow]:[])]) {
    // readRow already identified canonical snapshots. The transaction below
    // checks each exact snapshot and the trusted owner, so a second point read
    // cannot improve race safety.
    const canonical=row[canonicalRootSnapshot] ? row : await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();

    if (!canonical || canonical.document_json!==row.document_json) return json({error:'Group or post changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ? THEN 1 ELSE 0 END').bind(row.path,row.document_json));
  }
  guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?)=? THEN 1 ELSE 0 END').bind(groupPath,owner));
  if (!beforeRow) guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path));
  const stmts=[...guards],coll=collectionIdOf(path),id=docIdOf(path);
  if (!after) {
    stmts.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
    stmts.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
  } else {
    const data=JSON.stringify(after), author=after.authorId;
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,coll,coll,id,author,data,beforeRow?.create_time||now,now));
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,coll,id,author,JSON.stringify({...after,id}),now));
    // Do not rewrite the verified marker on every edit; staging and missing
    // resources still transition to verified exactly as before.
    stmts.push(db.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state='verified' WHERE friendbook_cf_interaction_resources.state IS NOT 'verified'").bind(path));
  }
  stmts.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  await db.batch(stmts);
  return after ? json({exists:true,id,path,parent:coll,collection:coll,data:after,createTime:beforeRow?.create_time||now,updateTime:now},beforeRow?200:201) : json({deleted:true,path});
}

// A follow is a two-profile transition, authorized only for the actor and
// committed conditionally against both current profiles.
async function followBatch(db,uid,ops,now) {
  const forbidden=()=>json({error:'Forbidden'},403);
  if (ops.length!==2 || typeof db.batch!=='function') return forbidden();
  const own=ops.find(o=>o.path===`users/${uid}`), targetOp=ops.find(o=>o!==own);
  if (!own || !targetOp || !isDocumentPath(targetOp.path) || splitPath(targetOp.path).length!==2 || splitPath(targetOp.path)[0]!=='users') return forbidden();
  const peer=splitPath(targetOp.path)[1];
  if (!validSegment(peer) || peer.length>128 || peer===uid) return forbidden();
  for (const o of ops) if ((o.op||o.type)!=='set' || o.merge!==true || o.mergeFields || !o.data || Object.keys(o.data).length!==1) return forbidden();
  const a=own.data.following, b=targetOp.data.followers;
  if (!a || !b || !['arrayUnion','arrayRemove'].includes(a.__fieldValue) || b.__fieldValue!==a.__fieldValue
      || Object.keys(a).some(k=>!['__fieldValue','values'].includes(k)) || Object.keys(b).some(k=>!['__fieldValue','values'].includes(k))
      || !Array.isArray(a.values) || a.values.length!==1 || a.values[0]!==peer
      || !Array.isArray(b.values) || b.values.length!==1 || b.values[0]!==uid) return forbidden();
  const rows=await Promise.all([readRow(db,own.path),readRow(db,targetOp.path)]);
  if (rows.some(r=>!r)) return forbidden();
  const docs=rows.map(row=>decodeValue(JSON.parse(row.document_json)));
  const follow=a.__fieldValue==='arrayUnion';
  if (follow) for (let i=0;i<2;i++) {
    const doc=docs[i], other=i===0?peer:uid;
    if (doc.banned===true || doc.deactivated===true) return forbidden();
    for(const k of ['blocked','blockedUsers']) if ((doc[k]!==undefined && !Array.isArray(doc[k])) || doc[k]?.includes(other)) return forbidden();
  }
  for (const [i,key] of [[0,'following'],[1,'followers']]) {
    const list=docs[i][key];
    if (list!==undefined && (!Array.isArray(list) || list.some(v=>!validSegment(v)) || new Set(list).size!==list.length)) return forbidden();
    const value=i===0?peer:uid, next=(list||[]).filter(v=>v!==value);
    if (follow) next.push(value);
    docs[i]={...docs[i],[key]:next};
  }
  if (rows.every(row=>row[canonicalRootSnapshot])) {
    // Both profiles were read and authorized above. Check both exact snapshots
    // inside the first write, so a conflict rolls back both profiles and mirrors
    // without inserting and deleting temporary social-guard rows.
    const stmts=[];
    for(let i=0;i<2;i++) {
      const row=rows[i],id=docIdOf(row.path),doc=docs[i],owner=row.owner_uid||id;
      stmts.push(i===0 ? db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
        VALUES (CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?1)=?2
          AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?3)=?4 THEN ?1 ELSE NULL END,
          'users','users',?5,?6,?7,?8,?9)
        ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
        .bind(row.path,row.document_json,rows[1].path,rows[1].document_json,id,owner,JSON.stringify(doc),row.create_time||now,now)
        : db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
        VALUES (?,'users','users',?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
        .bind(row.path,id,owner,JSON.stringify(doc),row.create_time||now,now));
      stmts.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(row.path,'users',id,owner,JSON.stringify({...doc,id}),now));
    }
    await db.batch(stmts);
    return json({ok:true,operations:2});
  }
  const stmts=[];
  for (const row of rows) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();

    if (!canonical || canonical.document_json!==row.document_json) return json({error:'Follow state changed'},409);
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ? THEN 1 ELSE 0 END').bind(row.path,row.document_json));
  }
  for(let i=0;i<2;i++) {
    const row=rows[i], id=docIdOf(row.path), doc=docs[i], owner=row.owner_uid||id;
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(row.path,'users','users',id,owner,JSON.stringify(doc),row.create_time||now,now));
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(row.path,'users',id,owner,JSON.stringify({...doc,id}),now));
  }
  stmts.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  await db.batch(stmts);
  return json({ok:true,operations:2});
}

function chatMessageRoom(path, before, after) {
  const parts=splitPath(path);
  if (parts[0]==='chats' && parts.length>2) return parts[1];
  if (parts[0]==='messages' && parts.length===2) return (before||after)?.chatId || '';
  return null;
}
async function chatDocumentGuard(db,path,row) {
  // Canonical root reads already carry the exact authorized JSON. The batch
  // itself must still compare it so a concurrent edit aborts the mutation.
  if (row?.[canonicalRootSnapshot]) return db.prepare(`INSERT INTO friendbook_cf_social_guards(ok)
    SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?)=? THEN 1 ELSE 0 END`)
    .bind(path,row.document_json);
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();

  if (!row) {
    if (canonical) throw new Error('Document changed');
    return db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path);
  }
  if (!canonical || canonical.document_json!==row.document_json) throw new Error('Document changed');
  return db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ? THEN 1 ELSE 0 END').bind(path,row.document_json);
}
// Validate a group of independent path snapshots in one atomic guard row.
// Absence and exact JSON are checked against the same authoritative table.
function chatSnapshotGroupGuard(db, snapshots) {
  return db.prepare(`INSERT INTO friendbook_cf_social_guards(ok)
    SELECT CASE WHEN NOT EXISTS (
      SELECT 1 FROM json_each(?) j WHERE
        (SELECT f.document_json FROM friendbook_cf_firestore_documents f WHERE f.path=json_extract(j.value,'$[0]'))
          IS NOT json_extract(j.value,'$[1]')
    ) THEN 1 ELSE 0 END`).bind(`[${snapshots.join(',')}]`);
}
// Moderation authority comes exclusively from the configured verified UID list.
// Preserve the original document in a private audit record before changing it.
async function verifiedModerator(db,uid) {
  try {
    const grant=await db.prepare('SELECT uid FROM friendbook_cf_admin_moderators WHERE uid=?').bind(uid).first();
    const user=grant && await readRow(db,`users/${uid}`);
    const profile=user && decodeValue(JSON.parse(user.document_json));
    return !!profile && profile.banned!==true && profile.deactivated!==true;
  } catch { return false; }
}
async function adminModeration(request,env,uid,now) {
  const db=env.DB;
  const admin=isPaymentAdmin(env,uid);
  if (!admin && !await verifiedModerator(db,uid)) return json({error:'Forbidden'},403);
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  let input; try { input=await request.json(); } catch { return json({error:'Invalid JSON'},400); }
  if (!input || typeof input!=='object' || Array.isArray(input)
      || Object.keys(input).some(k=>!['action','collection','id','value'].includes(k))
      || !validSegment(input.id) || input.id.length>128) return json({error:'Invalid request'},400);
  const isBan=['ban_user','unban_user'].includes(input.action);
  const control=['set_tick','set_suspension','set_moderator','deactivate_user','reactivate_user'].includes(input.action);
  const endLive=input.action==='end_live' && input.collection==='lives';
  if (!admin && !(isBan || (input.action==='delete_content' && ['posts','reels','stories'].includes(input.collection)))) return json({error:'Forbidden'},403);
  if (control && (input.collection!=='users' || (input.action==='set_tick' && !['none','blue','gold','diamond'].includes(input.value))
      || (input.action==='set_suspension' && ![0,1,7,30,60,'lifetime'].includes(input.value))
      || (input.action==='set_moderator' && typeof input.value!=='boolean'))) return json({error:'Invalid account control'},400);
  if (!control && Object.hasOwn(input,'value')) return json({error:'Invalid request'},400);
  if (!control && ((isBan && input.collection!=='users') || (!isBan && !endLive && (input.action!=='delete_content'
      || !['posts','reels','stories','reports','feedback'].includes(input.collection))))) return json({error:'Invalid request'},400);
  if ((isBan || (control && input.action!=='set_tick')) && (uid===input.id || isPaymentAdmin(env,input.id))) return json({error:'Protected account'},403);
  const path=`${input.collection}/${input.id}`;
  try {
    const row=await readRow(db,path);
    if (!row) return json({error:'NOT_FOUND'},404);
    const before=decodeValue(JSON.parse(row.document_json));
    if (!admin && input.action==='delete_content' && (!before.authorId || isPaymentAdmin(env,before.authorId))) return json({error:'Protected content'},403);
    const guard=await chatDocumentGuard(db,path,row);
    const auditTable=control ? 'friendbook_cf_admin_account_audit' : endLive ? 'friendbook_cf_live_moderation_audit' : 'friendbook_cf_moderation_audit';
    const audit=db.prepare(`INSERT INTO ${auditTable}(id,admin_uid,action,target_path,original_json,created_at) VALUES (?,?,?,?,?,?)`)
      .bind(crypto.randomUUID(),uid,input.action,path,row.document_json,now);
    const statements=[guard,audit];
    if (!admin) {
      const actor=await readRow(db,`users/${uid}`);
      if (!actor || !await verifiedModerator(db,uid)) return json({error:'Forbidden'},403);
      statements.unshift(await chatDocumentGuard(db,actor.path,actor),db.prepare(`INSERT INTO friendbook_cf_social_guards(ok)
        SELECT EXISTS(SELECT 1 FROM friendbook_cf_admin_moderators WHERE uid=?)`).bind(uid));
    }

    if (endLive && before.status==='ended') return json({ok:true,action:input.action,path});
    if (control) {
      const v=input.value;
      const next={...before};
      if (input.action==='set_tick') Object.assign(next,{tickLevel:v,isVerified:v!=='none'});
      if (input.action==='set_suspension') Object.assign(next,{suspendedLifetime:v==='lifetime',suspendedUntil:v==='lifetime'?9999999999999:v?now+v*86400000:0});
      if (input.action==='reactivate_user') next.deactivated=false;
      if (input.action==='deactivate_user') Object.assign(next,{deactivated:true,isModerator:false});
      if (input.action==='set_moderator') next.isModerator=v;
      if (input.action==='set_moderator' && v) statements.push(db.prepare(`INSERT INTO friendbook_cf_admin_moderators(uid,granted_by,granted_at) VALUES (?,?,?)
        ON CONFLICT(uid) DO UPDATE SET granted_by=excluded.granted_by,granted_at=excluded.granted_at`).bind(input.id,uid,now));
      else if (input.action==='set_moderator' || input.action==='deactivate_user') statements.push(db.prepare('DELETE FROM friendbook_cf_admin_moderators WHERE uid=?').bind(input.id));
      statements.push(...broadcastWrites(db,path,next,input.id,row,now));
    } else if (isBan || endLive) {
      const next=endLive ? {...before,status:'ended',endedAt:{__type:'timestamp',millis:now},pkActive:false,pkBattleId:''} : {...before,banned:input.action==='ban_user'};
      const serialized=JSON.stringify(next), collection=input.collection, owner=endLive ? before.hostId : input.id;
      statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
        .bind(path,collection,collection,input.id,owner,serialized,row.create_time||now,now));
      statements.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`)
        .bind(path,collection,input.id,owner,JSON.stringify({...next,id:input.id}),now));
    } else {
      statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
      statements.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
    }
    statements.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
    await db.batch(statements);
    return json({ok:true,action:input.action,path});
  } catch (error) {
    if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||''))) return json({error:'Content changed. Please retry.'},409);
    return json({error:'Moderation unavailable'},503);
  }
}

const broadcastTeam='friendbook_team';
const broadcastAvatar='friendbook_team_logo';
function broadcastPayload(env, data) {
  if (typeof data.text!=='string' || data.text.length>4000 || typeof data.mediaUrl!=='string' || data.mediaUrl.length>2048
      || !['','text','image','video'].includes(data.mediaType)) return null;
  const text=data.text.trim(), mediaUrl=data.mediaUrl, mediaType=data.mediaType||'text';
  if (!text && !mediaUrl) return null;
  if (mediaUrl) {
    let url;
    try { url=new URL(mediaUrl); } catch { return null; }
    if (url.protocol!=='https:' || url.username || url.password || url.port || !['image','video'].includes(mediaType)) return null;
    const cloudinary=url.hostname==='res.cloudinary.com' && env.CLOUDINARY_CLOUD_NAME
      && url.pathname.startsWith(`/${env.CLOUDINARY_CLOUD_NAME}/${mediaType}/upload/`);
    const r2=mediaType==='video' && url.hostname==='pub-88ab0a56178742af95cbbf62df152a67.r2.dev' && url.pathname.startsWith('/videos/');
    if (!cloudinary && !r2) return null;
  } else if (mediaType!=='text') return null;
  return {text,mediaUrl,mediaType};
}
function broadcastWrites(db,path,data,owner,row,now) {
  const parent=parentPath(path), collection=collectionIdOf(path), id=docIdOf(path);
  const writes=[db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('broadcast',path)}(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
    .bind(path,parent,collection,id,owner,JSON.stringify(data),row?.create_time||now,now)];
  if (isRootDocument(path)) writes.push(db.prepare(`INSERT INTO friendbook_cf_documents AS ${writeAttribution('broadcast',path)}(path,collection,doc_id,owner_uid,document_json,updated_at)
    VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`)
    .bind(path,collection,id,owner,JSON.stringify({...data,id}),now));
  return writes;
}
// A page needs only yes/no membership, never the actor's full friend graph or
// every duplicate historical friendship document. All candidates include the
// authenticated actor, and the partial pair index excludes malformed arrays.
async function friendshipFacts(request,db,uid) {
  if(request.method!=='POST')return json({error:'Method not allowed'},405);
  let input;try{input=await request.json();}catch{return json({error:'Invalid JSON'},400);}
  const validUid=value=>typeof value==='string' && value.length>0 && value.length<=128 && !/[\/\x00-\x1f]/.test(value);
  if(!validUid(uid) || !input || Object.keys(input).some(k=>k!=='candidates')
      || !Array.isArray(input.candidates) || input.candidates.length<1 || input.candidates.length>10
      || input.candidates.some(candidate=>!validUid(candidate) || candidate===uid)
      || new Set(input.candidates).size!==input.candidates.length) return json({error:'Invalid candidates'},400);
  try {
    const rows=await db.prepare(`SELECT candidate.value AS uid,
      (EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f INDEXED BY idx_cf_friend_valid_pair
        WHERE f.parent_path='friends' AND json_type(f.document_json,'$.participants')='array'
          AND json_array_length(f.document_json,'$.participants')=2
          AND json_extract(f.document_json,'$.participants[0]')=?1
          AND json_extract(f.document_json,'$.participants[1]')=candidate.value)
      OR EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f INDEXED BY idx_cf_friend_valid_pair
        WHERE f.parent_path='friends' AND json_type(f.document_json,'$.participants')='array'
          AND json_array_length(f.document_json,'$.participants')=2
          AND json_extract(f.document_json,'$.participants[0]')=candidate.value
          AND json_extract(f.document_json,'$.participants[1]')=?1)) AS friend
      FROM json_each(?2) candidate`).bind(uid,JSON.stringify(input.candidates)).all();
    return json({facts:(rows.results||[]).map(row=>({uid:row.uid,friend:!!row.friend}))});
  } catch {return json({error:'Friendship facts unavailable'},503);}
}

// Referral rows are server-owned; historical canonical rows use the same
// refBy count as the previous client. Count at most 50 (the highest threshold).
// The rank comparison happens inside the transaction against the CURRENT
// profile, so concurrent paid/admin upgrades cannot be downgraded or lost.
function referralAwardStatements(db,uid,now) {
  const profile=`users/${uid}`;
  const award=db.prepare(`WITH referral(n) AS (
      SELECT COUNT(*) FROM (SELECT path FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_referrals_from
        WHERE parent_path='referrals' AND json_extract(document_json,'$.refBy')=? LIMIT 50)
    ), earned(rank) AS (SELECT CASE WHEN n>=50 THEN 3 WHEN n>=20 THEN 2 WHEN n>=5 THEN 1 ELSE 0 END FROM referral)
    UPDATE friendbook_cf_firestore_documents SET
      document_json=json_set(document_json,'$.tickLevel',
        (SELECT CASE rank WHEN 3 THEN 'diamond' WHEN 2 THEN 'gold' ELSE 'blue' END FROM earned),
        '$.isVerified',json('true')), update_time=?
    WHERE path=? AND json_type(document_json,'$.fields') IS NOT 'object'
      AND (json_type(document_json,'$.tickLevel') IS NULL
        OR json_type(document_json,'$.tickLevel')='null'
        OR json_extract(document_json,'$.tickLevel') IN ('none','blue','gold','diamond'))
      AND (SELECT rank FROM earned) > CASE json_extract(document_json,'$.tickLevel')
        WHEN 'diamond' THEN 3 WHEN 'gold' THEN 2 WHEN 'blue' THEN 1
        ELSE CASE WHEN json_type(document_json,'$.isVerified')='true' THEN 1 ELSE 0 END END`)
    .bind(uid,now,profile);
  // db.batch executes these sequentially in one transaction. Mirror only if
  // the immediately preceding canonical UPDATE changed a profile.
  const mirror=db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at)
    SELECT path,collection_id,doc_id,owner_uid,json_set(document_json,'$.id',doc_id),update_time
    FROM friendbook_cf_firestore_documents WHERE path=? AND changes()=1
    ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,
      document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(profile);
  return [award,mirror];
}
async function referralStatus(request,db,uid,now) {
  if(request.method!=='POST')return json({error:'Method not allowed'},405);
  if(typeof db.batch!=='function')return json({error:'Atomic storage unavailable'},503);
  try {
    const result=await db.batch(referralAwardStatements(db,uid,now));
    const awarded=Number(result?.[0]?.meta?.changes ?? result?.[0]?.changes ?? 0)>0;
    const [count,row]=await Promise.all([
      db.prepare(`SELECT COUNT(*) AS n FROM (SELECT path FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_referrals_from
        WHERE parent_path='referrals' AND json_extract(document_json,'$.refBy')=? LIMIT 50)`).bind(uid).first(),
      readRow(db,`users/${uid}`)
    ]);
    if(!row)return json({error:'Profile unavailable'},404);
    const profile=decodeValue(JSON.parse(row.document_json));
    return json({count:Number(count?.n||0),tickLevel:typeof profile.tickLevel==='string'?profile.tickLevel:(profile.isVerified?'blue':'none'),isVerified:profile.isVerified===true,awarded});
  } catch {return json({error:'Referral status unavailable'},503);}
}

async function claimReferral(request,db,uid,now) {
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  let body; try { body=await request.json(); } catch { return json({error:'Invalid JSON'},400); }
  const inviter=body?.inviter;
  if (!validSegment(inviter) || inviter.length>128 || inviter===uid) return json({error:'Invalid inviter'},400);
  try {
    const claimed=await db.prepare('SELECT inviter_uid FROM friendbook_cf_referral_claims WHERE referred_uid=?').bind(uid).first();
    if (claimed) return claimed.inviter_uid===inviter ? json({ok:true}) : json({error:'Referral already recorded'},409);
    const [signup,referrer]=await Promise.all([readRow(db,`users/${uid}`),readRow(db,`users/${inviter}`)]);
    const profile=referrer && decodeValue(JSON.parse(referrer.document_json));
    // Canonical create_time is assigned by the Worker, unlike the client-visible createdAt field.
    const signupRow=await db.prepare('SELECT create_time FROM friendbook_cf_firestore_documents WHERE path=?').bind(`users/${uid}`).first();
    if (!signup || !signupRow || signupRow.create_time<now-600000 || signupRow.create_time>now
        || !profile || profile.banned===true || profile.deactivated===true) return json({error:'Referral unavailable'},403);
    const date={__type:'timestamp',millis:now},path=`referrals/claim_${uid}`;
    const guard=await chatDocumentGuard(db,path,null);
    await db.batch([guard,await chatDocumentGuard(db,signup.path,signup),await chatDocumentGuard(db,referrer.path,referrer),
      db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT NOT EXISTS(
        SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='referrals' AND json_extract(document_json,'$.refTo')=?
      )`).bind(uid),
      db.prepare('INSERT INTO friendbook_cf_referral_claims(referred_uid,inviter_uid,created_at) VALUES (?,?,?)').bind(uid,inviter,now),
      ...broadcastWrites(db,path,{refBy:inviter,refTo:uid,status:'completed',date,createdAt:date},uid,null,now),
      ...referralAwardStatements(db,inviter,now),
      db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
    return json({ok:true},201);
  } catch (error) {
    if (/constraint failed|Document changed/i.test(String(error?.message||''))) return json({error:'Referral already recorded or changed'},409);
    return json({error:'Referral unavailable'},503);
  }
}

async function adminBroadcast(request,env,uid,now) {
  if (!isPaymentAdmin(env,uid)) return json({error:'Forbidden'},403);
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  const db=env.DB;
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  let input;
  try {input=await request.json();} catch {return json({error:'Invalid JSON'},400);}
  if (!input || typeof input.id!=='string' || !/^[A-Za-z0-9-]{1,64}$/.test(input.id) || !['queue','advance'].includes(input.action)) return json({error:'Invalid broadcast'},400);
  const path=`officialBroadcasts/${input.id}`;
  try {
    const jobRow=await readRow(db,path), job=jobRow?decodeValue(JSON.parse(jobRow.document_json)):null;
    if (job && job.adminId!==uid) return json({error:'Forbidden'},403);
    if (input.action==='queue') {
      const payload=broadcastPayload(env,input);
      if (!payload) return json({error:'Invalid broadcast content'},400);
      if (job) return ['text','mediaUrl','mediaType'].every(key=>job[key]===payload[key]) ? json({ok:true}) : json({error:'Broadcast already exists'},409);
      const next={...payload,adminId:uid,cursor:'',status:'queued',processed:0,createdAt:{__type:'timestamp',millis:now}};
      await db.batch([await chatDocumentGuard(db,path,null),...broadcastWrites(db,path,next,uid,null,now),db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
      return json({ok:true},201);
    }
    if (!job || !broadcastPayload(env,job)) return json({error:'Broadcast unavailable'},404);
    if (job.status==='complete') return json({complete:true,processed:job.processed});
    const cursor=typeof job.cursor==='string'?job.cursor:'';
    const size=6;
    const recipientPage=async (indexed)=>db.prepare(`SELECT doc_id FROM (
      SELECT doc_id FROM (SELECT doc_id FROM friendbook_cf_firestore_documents ${indexed?'INDEXED BY idx_cf_people_default_page':''} WHERE parent_path='users' AND doc_id>? ORDER BY doc_id LIMIT ?)
    ) ORDER BY doc_id LIMIT ?`).bind(cursor,size+1,size+1).all();
    let recipients;
    try { ({results: recipients=[]}=await recipientPage(true)); }
    catch (error) {
      if (!/no such index: idx_cf_(?:legacy_)?people_default_page/.test(String(error))) throw error;
      ({results: recipients=[]}=await recipientPage(false));
    }
    const statements=[await chatDocumentGuard(db,path,jobRow)];
    const next={...job,status:recipients.length>size?'sending':'complete',processed:Number.isSafeInteger(job.processed)?job.processed:0};
    for (const {doc_id:to} of recipients.slice(0,size)) {
      next.cursor=to;
      const account=validSegment(to)&&to!==broadcastTeam ? await readRow(db,`users/${to}`):null;
      const user=account?decodeValue(JSON.parse(account.document_json)):null;
      if (user && user.banned!==true && user.deactivated!==true) {
        const note=`notifications/broadcast_${input.id}_${to}`, chat=`chats/team_${to}`, message=`${chat}/messages/broadcast_${input.id}`;
        const [noteRow,chatRow,msgRow]=await Promise.all([readRow(db,note),readRow(db,chat),readRow(db,message)]);
        if (!noteRow && !msgRow) {
          const room=chatRow?decodeValue(JSON.parse(chatRow.document_json)):{};
          const conflict=chatRow && (room.isGroup===true || !Array.isArray(room.participants) || room.participants.length!==2
              || !room.participants.includes(to) || !room.participants.includes(broadcastTeam));
          if (conflict) next.skipped=(Number.isSafeInteger(next.skipped)?next.skipped:0)+1;
          else {
            const time={__type:'timestamp',millis:now}, title='friendbook Team';
            const unread=room.unreadCount && typeof room.unreadCount==='object' && !Array.isArray(room.unreadCount)?room.unreadCount:{};
            const roomNext={...room,participants:[broadcastTeam,to],participantNames:{...room.participantNames,[broadcastTeam]:title},
              participantPhotos:{...room.participantPhotos,[broadcastTeam]:broadcastAvatar},lastMessage:job.text||(job.mediaType==='video'?'Video':'Photo'),
              lastMessageTime:time,lastMessageTimeMs:now,unreadCount:{...unread,[to]:(Number.isSafeInteger(unread[to])?unread[to]:0)+1}};
            statements.push(await chatDocumentGuard(db,`users/${to}`,account),await chatDocumentGuard(db,note,null),
              await chatDocumentGuard(db,chat,chatRow),await chatDocumentGuard(db,message,null));
            statements.push(...broadcastWrites(db,note,{toUserId:to,fromUserId:broadcastTeam,fromUserName:title,fromUserPhotoUrl:broadcastAvatar,
              type:'announcement',text:job.text,isRead:false,broadcastId:input.id,mediaUrl:job.mediaUrl,mediaType:job.mediaType,createdAt:time},to,null,now));
            statements.push(db.prepare(`INSERT INTO friendbook_cf_broadcast_push
              (notification_id,recipient_uid,created_at,next_attempt_at) VALUES (?,?,?,?)`).bind(docIdOf(note),to,now,now));
            statements.push(...broadcastWrites(db,chat,roomNext,to,chatRow,now));
            statements.push(...broadcastWrites(db,message,{senderId:broadcastTeam,senderName:title,senderPhoto:broadcastAvatar,receiverId:to,
              text:job.text,type:job.mediaType,imageUrl:job.mediaType==='image'?job.mediaUrl:'',videoUrl:job.mediaType==='video'?job.mediaUrl:'',
              isDelivered:true,isSeen:false,broadcastId:input.id,createdAt:time,createdAtMs:now},broadcastTeam,null,now));
          }
        }
        next.processed++;
      }
    }
    statements.push(...broadcastWrites(db,path,next,uid,jobRow,now),db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
    await db.batch(statements);
    return json({complete:next.status==='complete',processed:next.processed});
  } catch (error) {
    if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||''))) return json({error:'Broadcast changed. Please retry.'},409);
    return json({error:'Broadcast unavailable'},503);
  }
}

// Bounded scheduled recovery keeps queued jobs moving even when the admin phone is offline.
export async function resumeOfficialBroadcasts(env,now=Date.now()) {
  const active=index=>env.DB.prepare(`SELECT doc_id,owner_uid FROM friendbook_cf_firestore_documents ${index}
    WHERE parent_path='officialBroadcasts' AND json_extract(document_json,'$.status') IN ('queued','sending')
    ORDER BY update_time,doc_id LIMIT 4`).bind().all();
  let results;
  try { ({results=[]}=await active('INDEXED BY idx_cf_broadcast_active_jobs')); }
  catch(error) {
    if(!String(error).includes('no such index: idx_cf_broadcast_active_jobs'))throw error;
    ({results=[]}=await active(''));
  }
  await Promise.all(results.map(async job=>{
    if (!isPaymentAdmin(env,job.owner_uid)) return;
    for (let round=0;round<4;round++) {
      const response=await adminBroadcast(new Request('https://internal.invalid/v1/admin/broadcast',{
        method:'POST',body:JSON.stringify({action:'advance',id:job.doc_id}),
      }),env,job.owner_uid,now);
      if (!response.ok || (await response.json()).complete) break;
    }
  }));
}

const adsFields=new Set(['appId','feed_native_unit_v3','reels_native_unit_v3','ads_enabled_v3','websiteUrl','appAdsTxtUrl','updatedBy','updatedAt']);
function adsView(doc) { return Object.fromEntries(Object.entries(doc||{}).filter(([key])=>adsFields.has(key))); }
async function writeAdsConfig(db,env,uid,path,body,now) {
  if (!isPaymentAdmin(env,uid)) return json({error:'Forbidden'},403);
  const data=body?.data;
  if(!data || typeof data!=='object' || Array.isArray(data) || Object.keys(data).some(key=>!adsFields.has(key))
    || data.updatedBy!==uid || typeof data.ads_enabled_v3!=='boolean'
    || ['appId','feed_native_unit_v3','reels_native_unit_v3','websiteUrl','appAdsTxtUrl'].some(k=>typeof data[k]!=='string' || data[k].length>256)) return json({error:'Invalid config'},400);
  try {
    const existing=await readRow(db,path),old=existing?decodeValue(JSON.parse(existing.document_json)):null;
    const next=applySet(old,data,Boolean(body.merge),body.mergeFields||null,now);
    next.updatedBy=uid;next.updatedAt={__type:'timestamp',millis:now};
    const row=await writeRow(db,path,next,now,existing,true);
    const response=rowToDocument(row);response.data=adsView(response.data);return json(response);
  } catch(error) {
    if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||'')))return json({error:'Config changed. Please retry.'},409);
    return json({error:'Config unavailable'},503);
  }
}

// Story viewers may only record their own view/reaction. Guard every document
// used by authorization and the friendship ledger within the same D1 batch.
async function storyEngagement(request,env,uid,now) {
  if(request.method!=='POST') return json({error:'Method not allowed'},405);
  let input;try{input=await request.json();}catch{return json({error:'Invalid JSON'},400);}
  if(!input || Array.isArray(input) || typeof input!=='object' || Object.keys(input).some(k=>!['id','op','emoji'].includes(k))
    || !validSegment(input.id) || input.id.length>128 || !['view','react'].includes(input.op)
    || (input.op==='view' && input.emoji!==undefined)
    || (input.op==='react' && (typeof input.emoji!=='string' || !input.emoji.trim() || input.emoji.length>32))) return json({error:'Invalid request'},400);
  const db=env.DB,path=`stories/${input.id}`;
  if(typeof db.batch!=='function')return json({error:'Atomic storage unavailable'},503);
  for(let attempt=0;attempt<3;attempt++) {
    try {
      const row=await readRow(db,path);if(!row)return json({error:'NOT_FOUND'},404);
      const doc=decodeValue(JSON.parse(row.document_json)),owner=doc.authorId;
      if(!validSegment(owner) || owner===uid || doc.groupId) return json({error:'Forbidden'},403);
      const [low,high]=[uid,owner].sort();
      const [viewer,author,friend]=await Promise.all([
        readRow(db,`users/${uid}`),readRow(db,`users/${owner}`),
        db.prepare('SELECT friend_path FROM friendbook_cf_trusted_friendships WHERE uid_low=? AND uid_high=?').bind(low,high).first(),
      ]);
      const validState=(doc.viewedBy===undefined || Array.isArray(doc.viewedBy))
        && (doc.reactions===undefined || (doc.reactions && typeof doc.reactions==='object' && !Array.isArray(doc.reactions)));
      let next,encoded;
      if(validState) {
        next={...doc,viewedBy:[...new Set([...(doc.viewedBy||[]),uid])]};
        if(input.op==='react')next.reactions={...(doc.reactions||{}),[uid]:input.emoji};
        encoded=JSON.stringify(next);
      }
      const expiration=typeof doc.expiresAt==='number'?doc.expiresAt:doc.expiresAt?.millis;
      let authReads;
      if(viewer?.[canonicalRootSnapshot] && author?.[canonicalRootSnapshot] && validState
          && encoded.length<=1_800_000 && (!Number.isFinite(expiration) || expiration>now)
          && (!row[canonicalRootSnapshot] || encoded!==row.document_json)) {
        // A changing write atomically guards these exact profile snapshots at
        // commit and retries if either changes. Reuse them for this request's
        // authorization instead of fetching both profiles twice. No-op requests
        // keep the fresh authorization reads because they do not commit guards.
        const viewerFields=toFirestoreFields(viewer.document_json),authorFields=toFirestoreFields(author.document_json);
        if(!viewerFields || !authorFields)throw new Error('Invalid stored document');
        authReads=new Map([[`users/${uid}`,viewerFields],[`users/${owner}`,authorFields]]);
      }
      if(!viewer || !author || !await contentReadAllowed(db,uid,path,authReads))return json({error:'Forbidden'},403);
      if(Number.isFinite(expiration) && expiration<=now)return json({error:'Story expired'},403);
      if(!validState)return json({error:'Invalid story'},409);
      if(encoded.length>1_800_000)return json({error:'Document too large'},413);
      // Reopening a story or retrying an identical reaction must not rewrite
      // two indexed documents and temporary guards. Authorization and expiry
      // checks above still run for every canonical snapshot.
      if(row[canonicalRootSnapshot] && encoded===row.document_json)return json({ok:true});
      const mirror=db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,'stories',input.id,owner,JSON.stringify({...next,id:input.id}),now);
      if(row[canonicalRootSnapshot] && viewer[canonicalRootSnapshot] && author[canonicalRootSnapshot]) {
        // The canonical upsert checks the exact story, profiles and friendship
        // snapshot inside the transaction. A conflict forces a NOT NULL error,
        // rolling back both stores and retrying the full authorization without
        // writing four temporary guards and deleting them again.
        const canonical=db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
          VALUES (CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?1)=?2
            AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?3)=?4
            AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?5)=?6
            AND COALESCE((SELECT friend_path FROM friendbook_cf_trusted_friendships WHERE uid_low=?7 AND uid_high=?8),'')=?9
            THEN ?1 ELSE NULL END,'stories','stories',?10,?11,?12,?13,?14)
          ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
          .bind(path,row.document_json,`users/${uid}`,viewer.document_json,`users/${owner}`,author.document_json,low,high,friend?.friend_path||'',input.id,owner,encoded,row.create_time||now,now);
        await db.batch([canonical,mirror]);
      } else {
        // Historical-only records still require the original conversion guards.
        const guards=await Promise.all([chatDocumentGuard(db,path,row),chatDocumentGuard(db,`users/${uid}`,viewer),chatDocumentGuard(db,`users/${owner}`,author)]);
        guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN COALESCE((SELECT friend_path FROM friendbook_cf_trusted_friendships WHERE uid_low=? AND uid_high=?),\'\') = ? THEN 1 ELSE 0 END').bind(low,high,friend?.friend_path||''));
        await db.batch([...guards,
          db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,'stories','stories',input.id,owner,encoded,row.create_time||now,now),
          mirror,db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
      }
      return json({ok:true});
    } catch(error) {
      if(/Document changed|CHECK constraint failed: ok\s*=\s*1|NOT NULL constraint failed: friendbook_cf_firestore_documents\.path/i.test(String(error?.message||''))){if(attempt<2)continue;return json({error:'Story changed. Please retry.'},409);}
      return json({error:'Story engagement unavailable'},503);
    }
  }
}

async function authorizeChat(db,path,uid,before,after,pendingRooms=new Map(),roomReads=null) {
  const id=chatMessageRoom(path,before,after);
  if (id===null) return {allowed:await chatWriteAllowed(db,path,uid,before,after,pendingRooms), room:null};
  if (!id) return {allowed:false,room:null};
  const roomPath=`chats/${id}`;
  if (roomReads && !roomReads.has(roomPath)) roomReads.set(roomPath,await readRow(db,roomPath));
  const room=roomReads ? roomReads.get(roomPath) : await readRow(db,roomPath);
  const snapshot=room ? decodeValue(JSON.parse(room.document_json)) : pendingRooms.get(id);
  return {allowed:await chatWriteAllowed(db,path,uid,before,after,pendingRooms,snapshot || null),room,roomPath};
}
async function writeChatMessage(db,path,doc,now,existing,auth,uid,deleted=false) {
  if (!auth.room) return json({error:'Forbidden'},403);
  const id=docIdOf(path), parent=parentPath(path), coll=collectionIdOf(path);
  if (!existing && !deleted && auth.room[canonicalRootSnapshot] && !isRootDocument(path)) {
    const serialized=JSON.stringify(doc);
    if (serialized.length>1_800_000) return json({error:'Document too large'},413);
    // The INSERT itself checks room membership's snapshot and both possible
    // message stores. No guard rows are needed for a single new nested message.
    const inserted=await db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('chat_message',path)}
      (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
      SELECT ?1,?2,?3,?4,?5,?6,?7,?7
      WHERE EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?8 AND document_json=?9)
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?1)
      RETURNING path`).bind(path,parent,coll,id,ownerOf(doc,uid),serialized,now,auth.roomPath,auth.room.document_json).first();
    if (!inserted) throw new Error('Document changed');
    return json({exists:true,id,path,parent,collection:coll,data:doc,createTime:now,updateTime:now});
  }
  if (existing && !deleted && auth.room[canonicalRootSnapshot] && /^chats\/[^/]+\/messages\/[^/]+$/.test(path)) {
    const serialized=JSON.stringify(doc),owner=ownerOf(doc,existing.owner_uid||uid);
    if (serialized.length>1_800_000) return json({error:'Document too large'},413);
    // A single conditional update replaces the temporary guard row only for a
    // canonical nested message and its canonical authorized room. Both JSON
    // snapshots must still match at commit, including membership and reactions.
    const updated=await db.prepare(`UPDATE friendbook_cf_firestore_documents
      SET owner_uid=?,document_json=?,update_time=? WHERE path=? AND document_json=?
      AND EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=? AND document_json=?) RETURNING path`)
      .bind(owner,serialized,now,path,existing.document_json,auth.roomPath,auth.room.document_json).first();
    if (!updated) throw new Error('Document changed');
    return json({exists:true,id,path,parent,collection:coll,data:doc,createTime:existing.create_time||now,updateTime:now});
  }
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  // The nested message and canonical room were both read for authorization.
  // Compare both snapshots at commit time using one guard row, not two writes
  // and two preflight rereads. Legacy roots still need their source checks.
  const statements=auth.room[canonicalRootSnapshot] && !isRootDocument(path)
    ? [chatSnapshotGroupGuard(db,[JSON.stringify([auth.roomPath,auth.room.document_json]),
      JSON.stringify([path,existing?.document_json ?? null])])]
    : [await chatDocumentGuard(db,auth.roomPath,auth.room),await chatDocumentGuard(db,path,existing)];
  if (deleted) {
    statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
    if (isRootDocument(path)) statements.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
  } else {
    const serialized=JSON.stringify(doc), owner=ownerOf(doc,existing?.owner_uid||uid), created=existing?.create_time||now;
    if (serialized.length>1_800_000) return json({error:'Document too large'},413);
    statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('chat_message',path)}(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,parent,coll,id,owner,serialized,created,now));
    if (isRootDocument(path)) statements.push(db.prepare(`INSERT INTO friendbook_cf_documents AS ${writeAttribution('chat_message',path)}(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,coll,id,owner,JSON.stringify({...doc,id}),now));
  }
  statements.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  await db.batch(statements);
  return deleted ? json({deleted:true,path}) : json({exists:true,id,path,parent,collection:coll,data:doc,createTime:existing?.create_time||now,updateTime:now});
}

async function deleteChatRoot(db,path,row) {
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  const before=decodeValue(JSON.parse(row.document_json));
  const guards=await chatRootGuards(db,path,row), ledger=await groupChatLedgerChanges(db,path,before,null);
  // The authorized owner and the ledger snapshot must still agree.
  const owner=await db.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').bind(path).first();
  if (!owner || owner.owner_uid!==before.createdBy) return json({error:'Group changed'},409);
  await db.batch([...guards,...ledger.guards,...ledger.writes,
    db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path),
    db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path),
    db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
  return json({deleted:true,path});
}

// Protect room ownership against concurrent creation/updates and prevent an
// attacker from adopting orphaned message history under a newly created room.
async function chatRootGuards(db,path,row) {
  const id=docIdOf(path), statements=[];
  if (!row) {
    statements.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok)
      SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?)

      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path=?)
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='messages'
        AND doc_id>=? AND doc_id<? AND substr(doc_id,1,?)=?)
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='messages' AND json_extract(document_json,'$.chatId')=?)
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='messages' AND json_extract(document_json,'$.fields.chatId.stringValue')=?)
      THEN 1 ELSE 0 END`).bind(path,`${path}/messages`,
        id+'_',id+'`',id.length+1,id+'_',id,id));
    return statements;
  }
  if (row[canonicalRootSnapshot]) return [chatSnapshotGroupGuard(db,[JSON.stringify([path,row.document_json])])];
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();

  if (!canonical || canonical.document_json!==row.document_json) throw new Error('Room changed');
  statements.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ? THEN 1 ELSE 0 END').bind(path,row.document_json));
  return statements;
}
async function groupChatLedgerChanges(db,path,before,after) {
  if (!before && after?.isGroup!==true) return {guards:[],writes:[]};
  if (before && before.isGroup!==true) return {guards:[],writes:[]};
  const row=await db.prepare('SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').bind(path).first();
  if (before && !after && (!row || row.owner_uid!==before.createdBy)) throw new Error('Group chat authority changed');
  const authorityChanged=before && after && ['participants','adminIds','creatorId','createdBy','onlyAdminsCanSend'].some(k=>JSON.stringify(before[k])!==JSON.stringify(after[k]));
  if (authorityChanged && (!row || row.owner_uid!==before.createdBy)) throw new Error('Group chat authority changed');
  const guard=db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? IS NULL AND NOT EXISTS (SELECT 1 FROM friendbook_cf_trusted_group_chats WHERE chat_path=?)) OR (? IS NOT NULL AND (SELECT owner_uid FROM friendbook_cf_trusted_group_chats WHERE chat_path=?)=?) THEN 1 ELSE 0 END').bind(row?.owner_uid||null,path,row?.owner_uid||null,path,row?.owner_uid||null);
  const writes=[];
  if (!before && after?.isGroup===true) writes.push(db.prepare('INSERT INTO friendbook_cf_trusted_group_chats(chat_path,owner_uid) VALUES (?,?)').bind(path,after.createdBy));
  if (before && row && !after) writes.push(db.prepare('DELETE FROM friendbook_cf_trusted_group_chats WHERE chat_path=?').bind(path));
  if (before && row && after && before.createdBy!==after.createdBy) writes.push(db.prepare('UPDATE friendbook_cf_trusted_group_chats SET owner_uid=? WHERE chat_path=?').bind(after.createdBy,path));
  return {guards:[guard],writes};
}

async function writeChatRoot(db,path,doc,now,row,uid,{canonicalOnly=false,literalNoop=false}={}) {
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  const before=row?decodeValue(JSON.parse(row.document_json)):null;
  if(literalNoop && row?.[canonicalRootSnapshot] && before?.isGroup!==true
    && doc.isGroup!==true && JSON.stringify(doc)===row.document_json
    && ownerOf(doc,row.owner_uid||uid)===row.owner_uid) {
    const state=await unchangedRootState(db,path,row,doc);
    if(!state.current)throw new Error('Room changed');
    if(state.mirror)return json(rowToDocument(row));
    // Missing/stale mirrors still repair through the atomic write below.
  }
  // Existing canonical direct rooms have no group authority ledger. Enforce
  // their authorized snapshot in the canonical upsert itself, so its mirror
  // remains atomic without inserting/deleting a temporary guard row.
  const inline=Boolean(row?.[canonicalRootSnapshot] && before?.isGroup!==true && doc.isGroup!==true);
  const guards=inline?[]:await chatRootGuards(db,path,row);
  const ledger=await groupChatLedgerChanges(db,path,before,doc);
  const id=docIdOf(path), data=JSON.stringify(doc);
  if (data.length>1_800_000) return json({error:'Document too large'},413);
  const owner=ownerOf(doc,row?.owner_uid||uid), createTime=row?.create_time||now;
  const canonical=inline ? db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('chat_root',path)}
    (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (CASE WHEN (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?1)=?2
      AND (SELECT update_time FROM friendbook_cf_firestore_documents WHERE path=?1)=?3
      THEN ?1 ELSE NULL END,'chats','chats',?4,?5,?6,?7,?8)
    ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
    .bind(path,row.document_json,row.update_time,id,owner,data,createTime,now)
    : db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('chat_root',path)}(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,'chats','chats',id,owner,data,createTime,now);
  await db.batch([...guards,...ledger.guards,...ledger.writes,canonical,
    ...(canonicalOnly ? [] : [db.prepare(`INSERT INTO friendbook_cf_documents AS ${writeAttribution('chat_root',path)}(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,'chats',id,owner,JSON.stringify({...doc,id}),now)]),
    ...(!inline || ledger.guards.length ? [db.prepare('DELETE FROM friendbook_cf_social_guards').bind()] : [])]);
  return json({exists:true,id,path,parent:'chats',collection:'chats',data:doc,createTime,updateTime:now});
}

// Owner-operated review: no legacy role or roster is trusted automatically.
// The preview and the submitted digest identify the exact record the administrator reviewed.
async function recoverLegacySocial(request,env,uid,now) {
  const db=env.DB, deny=()=>json({error:'Forbidden'},403);
  if (!isPaymentAdmin(env,uid)) return deny();
  if (!['GET','POST'].includes(request.method)) return json({error:'Method not allowed'},405);
  let input;
  try { input=request.method==='GET' ? Object.fromEntries(new URL(request.url).searchParams) : await request.json(); }
  catch { return json({error:'Invalid request'},400); }
  if (!input || !['group','chat'].includes(input.kind) || !validSegment(input.id) || input.id.length>128) return json({error:'Invalid request'},400);
  const coll=input.kind==='group'?'groups':'chats', path=`${coll}/${input.id}`;
  const ledgerTable=input.kind==='group'?'friendbook_cf_trusted_groups':'friendbook_cf_trusted_group_chats';
  const ledgerPath=input.kind==='group'?'group_path':'chat_path';
  try {
    const row=await readRow(db,path);
    if (!row) return json({error:'Not found'},404);
    const old=decodeValue(JSON.parse(row.document_json));
    const owner=input.kind==='group'?old.adminId:old.createdBy;
    const participants=input.kind==='group'?old.members:old.participants;
    if (!validSegment(owner) || owner.length>128 || !Array.isArray(participants) || !participants.length || participants.length>500
        || participants.some(x=>!validSegment(x) || x.length>128) || new Set(participants).size!==participants.length || !participants.includes(owner)
        || (input.kind==='chat' && old.isGroup!==true)) return json({error:'Record needs manual repair'},409);
    if (await db.prepare(`SELECT owner_uid FROM ${ledgerTable} WHERE ${ledgerPath}=?`).bind(path).first()) return json({error:'Already trusted'},409);
    const bytes=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(row.document_json));
    const revision=Array.from(new Uint8Array(bytes),v=>v.toString(16).padStart(2,'0')).join('');
    if (request.method==='GET') return json({kind:input.kind,id:input.id,name:input.kind==='group'?(old.name||''):(old.groupName||''),owner,participants,revision});
    if (input.confirm!==true || input.revision!==revision || input.owner!==owner) return json({error:'Review changed or not confirmed'},409);
    if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
    const next=input.kind==='group' ? {...old,adminId:owner,adminIds:[owner],moderatorIds:[],pendingMemberIds:[],invitedUids:[],bannedMemberIds:[],privacy:['public','private'].includes(String(old.privacy||'').toLowerCase())?old.privacy:'Private'}
      : {...old,createdBy:owner,adminIds:[owner],onlyAdminsCanSend:old.onlyAdminsCanSend===true};
    const guard=await chatDocumentGuard(db,path,row), id=docIdOf(path), data=JSON.stringify(next);
    await db.batch([guard,
      db.prepare(`INSERT INTO ${ledgerTable}(${ledgerPath},owner_uid) VALUES (?,?)`).bind(path,owner),
      db.prepare('INSERT INTO friendbook_cf_social_recovery_audit(path,kind,admin_uid,owner_uid,original_json,recovered_at) VALUES (?,?,?,?,?,?)').bind(path,input.kind,uid,owner,row.document_json,now),
      db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,coll,coll,id,owner,data,row.create_time||now,now),
      db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,coll,id,owner,JSON.stringify({...next,id}),now),
      db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
    return json({ok:true,kind:input.kind,id:input.id,owner});
  } catch { return json({error:'Recovery changed or unavailable'},409); }
}

// Social gifts are not wallet credits or earnings. Only the server determines points.
const LIVE_GIFTS=new Map(Object.entries({rose:['Rose','rose',1],heart:['Finger Heart','heart',5],
  donut:['Doughnut','donut',30],crown:['Gold Crown','crown',99],confetti:['Party Pop','confetti',199],
  airplane:['Private Jet','jet',299],lion:['Golden Lion','lion',500],universe:['Universe','rocket',1000]}));

// PK invitations and transitions are separate from chat messages and client-owned fields.
async function livePk(request,db,uid,now,identity) {
  let input;
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  try { input=await request.json(); } catch { return json({error:'Invalid JSON'},400); }
  const action=input?.action;
  if (!['invite','accept','decline','end','status','room'].includes(action)) return json({error:'Invalid PK action'},400);
  const validRoom=x=>typeof x==='string' && /^[A-Za-z0-9_-]{1,128}$/.test(x);
  if (action==='invite' && (!validRoom(input.roomA) || !validRoom(input.roomB) || (input.roomA===input.roomB && !validSegment(input.guestUid))
      || !Number.isInteger(input.durationSec) || input.durationSec<60 || input.durationSec>600)) return json({error:'Invalid PK rooms'},400);
  if (action!=='invite' && !validRoom(action==='room'?input.room:input.id)) return json({error:'Invalid PK ID'},400);
  try {
    const reads=requestAccountReads(identity);
    if (action==='room') {
      const row=await db.prepare(`SELECT * FROM friendbook_cf_live_pk WHERE status='active' AND expires_at>? AND (room_a=? OR room_b=?) LIMIT 1`).bind(now,input.room,input.room).first();
      // The four checks belong to one request. Share in-flight room/account
      // reads only here; the next poll rechecks fresh privacy and ban state.
      if (!row || !await authorizeLiveMedia(db,row.room_a,uid,'watch',reads) || !await authorizeLiveMedia(db,row.room_b,uid,'watch',reads)
          || !await authorizeLiveMedia(db,row.room_a,row.host_a,'publish',reads) || !await authorizeLiveMedia(db,row.room_b,row.host_b,row.room_a===row.room_b?'watch':'publish',reads)) return json({status:'idle'});
      // Publish/watch ACLs already loaded both current host profiles. Reuse
      // those same-request fields rather than fetching each host again for a name.
      const [a,b]=await Promise.all([reads.get(`users/${row.host_a}`),reads.get(`users/${row.host_b}`)]);
      const name=fields=>String(fromFirestoreValue(fields?.displayName)||'').slice(0,200);
      return json({...row,name_a:name(a)||'Host A',name_b:name(b)||'Host B'});
    }
    const load=id=>db.prepare('SELECT * FROM friendbook_cf_live_pk WHERE id=?').bind(id).first();
    const battle=action==='invite'?null:await load(input.id);
    if (action!=='invite' && !battle) return json({error:'PK match unavailable'},404);
    const roomA=battle?.room_a ?? input.roomA,roomB=battle?.room_b ?? input.roomB;
    if (action==='status') {
      if (![battle.host_a,battle.host_b].includes(uid) && (battle.status!=='active' || battle.expires_at<=now
          || !await authorizeLiveMedia(db,roomA,uid,'watch',reads) || !await authorizeLiveMedia(db,roomB,uid,'watch',reads))) return json({error:'Forbidden'},403);
      return json({...battle,status:['invited','active'].includes(battle.status) && battle.expires_at<=now?'ended':battle.status});
    }
    const [a,b]=await Promise.all([readRow(db,`lives/${roomA}`),readRow(db,`lives/${roomB}`)]);
    if (!a || !b) return json({error:'PK rooms unavailable'},404);
    const aa=decodeValue(JSON.parse(a.document_json)),bb=decodeValue(JSON.parse(b.document_json));
    reads.set(`lives/${roomA}`,toFirestoreFields(a.document_json));
    reads.set(`lives/${roomB}`,toFirestoreFields(b.document_json));
    const guest=roomA===roomB;
    const guards=[await chatDocumentGuard(db,a.path,a),...(guest?[]:[await chatDocumentGuard(db,b.path,b)])];
    const clear=db.prepare('DELETE FROM friendbook_cf_social_guards').bind();
    if (action==='invite') {
      const target=guest ? input.guestUid : bb.hostId;
      if (aa.hostId!==uid || uid===target || (guest && aa.coHostUid) || bb.pkInvitesEnabled===false
          || !await authorizeLiveMedia(db,roomA,uid,'publish',reads) || !await authorizeLiveMedia(db,roomB,target,guest?'watch':'publish',reads)
          || !await authorizeLiveMedia(db,roomB,uid,'watch',reads) || !await authorizeLiveMedia(db,roomA,target,'watch',reads)) return json({error:'Forbidden'},403);
      const id=autoId();
      const inviteId=autoId();
      const actor=await reads.get(`users/${uid}`);
      const msg={type:'pk_invite',uid,name:String(fromFirestoreValue(actor?.displayName)||'').slice(0,200),targetUid:target,pkBattleId:id,
        text:'sent a PK battle invitation',durationSec:input.durationSec,createdAt:now,createdAtMs:now};
      await db.batch([...guards,db.prepare(`INSERT INTO friendbook_cf_live_pk(id,room_a,room_b,host_a,host_b,status,duration_sec,created_at,expires_at)
        SELECT ?,?,?,?,?,'invited',?,?,? WHERE NOT EXISTS (
          SELECT 1 FROM friendbook_cf_live_pk WHERE status IN ('invited','active') AND expires_at>? AND (room_a IN (?,?) OR room_b IN (?,?))
        )`).bind(id,roomA,roomB,uid,target,input.durationSec,now,now+90000,now,roomA,roomB,roomA,roomB),
        db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT EXISTS(SELECT 1 FROM friendbook_cf_live_pk WHERE id=?)`).bind(id),
        ...broadcastWrites(db,`lives/${roomB}/chat/${inviteId}`,msg,uid,null,now),clear]);
      return json({id,status:'invited'},201);
    }
    if (aa.hostId!==battle.host_a || (!guest && bb.hostId!==battle.host_b) || (action==='end'?![battle.host_a,battle.host_b].includes(uid):uid!==battle.host_b)) return json({error:'Forbidden'},403);
    const accepting=action==='accept';
    if (accepting && ((guest && aa.coHostUid) || bb.pkInvitesEnabled===false || !await authorizeLiveMedia(db,roomA,battle.host_a,'publish',reads)
        || !await authorizeLiveMedia(db,roomB,battle.host_b,guest?'watch':'publish',reads) || !await authorizeLiveMedia(db,roomA,battle.host_b,'watch',reads)
        || !await authorizeLiveMedia(db,roomB,battle.host_a,'watch',reads))) return json({error:'Forbidden'},403);
    const status=accepting?'active':action==='decline'?'declined':'ended';
    const expected=action==='end'?'active':'invited';
    const expires=accepting?now+battle.duration_sec*1000:now;
    await db.batch([...guards,db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT EXISTS(
      SELECT 1 FROM friendbook_cf_live_pk WHERE id=? AND status=? AND expires_at>?)`).bind(battle.id,expected,now),
      db.prepare(`UPDATE friendbook_cf_live_pk SET status=?,expires_at=? WHERE id=?`).bind(status,expires,battle.id),clear]);
    return json({id:battle.id,status,expiresAt:expires});
  } catch (error) {
    if (/Document changed|CHECK constraint failed: ok/i.test(String(error?.message||''))) return json({error:'PK match changed or busy'},409);
    return json({error:'PK match unavailable'},503);
  }
}

async function signalChatCall(request,db,uid,now,identity) {
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  let input; try { input=await request.json(); } catch { return json({error:'Invalid JSON'},400); }
  const {chatId,callId,action}=input||{};
  if (typeof chatId!=='string' || !/^[A-Za-z0-9_-]{1,200}$/.test(chatId) || chatId.startsWith('team_')
      || typeof callId!=='string' || !/^[A-Za-z0-9_-]{1,80}$/.test(callId)
      || !['start','accept','decline','end','missed','cancelled'].includes(action)) return json({error:'Invalid call'},400);
  try {
    const path=`chats/${chatId}`, row=await readRow(db,path), current=row?decodeValue(JSON.parse(row.document_json)):null;
    let room=current;
    if (!room && action==='start' && validSegment(input.peerId) && input.peerId!==uid && [uid,input.peerId].sort().join('_')===chatId) {
      room={participants:[uid,input.peerId],isGroup:false};
    }
    if (!room || room.isGroup===true || !Array.isArray(room.participants) || room.participants.length!==2 || !room.participants.includes(uid)) return json({error:'Forbidden'},403);
    const peer=room.participants.find(id=>id!==uid);
    const reads=requestAccountReads(identity);
    if (!validSegment(peer) || !await callAccountsAllowed(db,uid,peer,reads)) return json({error:'Forbidden'},403);
    const stored=await db.prepare('SELECT * FROM friendbook_cf_chat_calls WHERE chat_id=?').bind(chatId).first();
    let next, expires;
    if (action==='start') {
      if (!['voice','video'].includes(input.callType) || (stored && ['ringing','connected'].includes(stored.status) && stored.expires_at>now)) return json({error:'Call already active or invalid'},409);
      const profile=await readCallProfile(db,uid,reads);
      next={callId,chatId,callerId:uid,calleeId:peer,callerName:String(fromFirestoreValue(profile?.displayName)||'').slice(0,200),callerPhoto:String(fromFirestoreValue(profile?.photoUrl)||'').slice(0,2048),callType:input.callType,status:'ringing',startedAt:now};
      expires=now+45000;
    } else {
      const call=room.activeCall;
      if (!stored || stored.call_id!==callId || call?.callId!==callId || !['ringing','connected'].includes(stored.status)) return json({error:'Call is no longer active'},409);
      if (['accept','decline'].includes(action) && (uid!==call.calleeId || stored.status!=='ringing' || stored.expires_at<=now)) return json({error:'Forbidden'},403);
      if (['missed','cancelled'].includes(action) && (uid!==call.callerId || stored.status!=='ringing')) return json({error:'Call changed'},409);
      next={...call,status:action==='accept'?'connected':action==='end'?'ended':action==='decline'?'declined':action};
      if (action==='accept') next.connectedAt=now;
      else next.endedAt=now;
      expires=action==='accept'?now+3600000:now;
    }
    const guards=row?[await chatDocumentGuard(db,path,row)]:await chatRootGuards(db,path,null);
    await db.batch([...guards,db.prepare(`INSERT INTO friendbook_cf_chat_calls(chat_id,call_id,status,expires_at) VALUES (?,?,?,?)
      ON CONFLICT(chat_id) DO UPDATE SET call_id=excluded.call_id,status=excluded.status,expires_at=excluded.expires_at`).bind(chatId,callId,next.status,expires),
      ...broadcastWrites(db,path,{...room,activeCall:next},row?.owner_uid||uid,row,now),db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
    return json(next);
  } catch (error) {
    if (/Document changed|CHECK constraint failed: ok/i.test(String(error?.message||''))) return json({error:'Call changed'},409);
    return json({error:'Call unavailable'},503);
  }
}

async function createLiveChat(db, roomId, uid, input, now) {
  const path=`lives/${roomId}`;
  const roomRow=await readRow(db,path);
  if (!roomRow || !await authorizeLiveMedia(db,roomId,uid,'watch')) return json({error:'Forbidden'},403);
  const room=decodeValue(JSON.parse(roomRow.document_json));
  const type=input?.type;
  if (type==='cohost_accept' || type==='cohost_remove') {
    const host=room.hostId===uid;
    const accepting=type==='cohost_accept';
    if ((!host && (accepting || room.coHostUid!==uid)) || (!accepting && input.targetUid!==room.coHostUid)) return json({error:'Forbidden'},403);
    let target='', name='', requestRow=null;
    if (accepting) {
      if (room.coHostAllowed===false || room.coHostUid || !validSegment(input.requestId)) return json({error:'Co-host unavailable'},409);
      requestRow=await readRow(db,`${path}/chat/${input.requestId}`);
      const req=requestRow && decodeValue(JSON.parse(requestRow.document_json));
      if (!req || req.type!=='cohost_req' || req.uid===uid || req.uid!==input.targetUid || req.createdAtMs<now-300000
          || !await authorizeLiveMedia(db,roomId,req.uid,'watch')) return json({error:'Invalid co-host request'},403);
      target=req.uid;name=req.name;
    }
    const next={...room,coHostUid:target,coHostName:name,coHostGrant:target?autoId():''};
    const writes=[await chatDocumentGuard(db,path,roomRow)];
    if (requestRow) {
      writes.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT NOT EXISTS(
        SELECT 1 FROM friendbook_cf_live_pk WHERE room_a=? AND room_b=? AND status='active' AND expires_at>?)`).bind(roomId,roomId,now));
      writes.push(await chatDocumentGuard(db,requestRow.path,requestRow),db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(requestRow.path));
    }
    try {
      await db.batch([...writes,...broadcastWrites(db,path,next,room.hostId,roomRow,now),db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
    } catch { return json({error:'Co-host room changed'},409); }
    return json({exists:true,id:roomId,path,parent:'lives',collection:'lives',data:next,updateTime:now});
  }
  const gift=type==='gift' ? LIVE_GIFTS.get(input.giftId) : null;
  const text=gift ? `sent ${gift[0]} (${gift[2]} points)!` : typeof input?.text==='string' ? input.text.trim() : '';
  const host=room.hostId===uid;
  const mod=Array.isArray(room.moderatorIds) && room.moderatorIds.includes(uid);
  if (!['chat','qa','reaction','system','gift','cohost_req','pk_req'].includes(type) || !text || text.length>500 || input.uid!==uid
      || (type==='gift' && (!gift || !validSegment(input.requestId) || !/^[0-9a-f-]{36}$/.test(input.requestId)))) return json({error:'Invalid live message'},400);
  if (room.status!=='live' || (type==='pk_req' && (host || room.pkInvitesEnabled===false)) || (type==='cohost_req' && (host || room.coHostAllowed===false || room.coHostUid)) || (type==='gift' && (host || room.giftsEnabled===false)) || (type==='system' && !host && !mod) || (!host && !mod && (
      (type==='chat' && room.commentsEnabled===false) || (type==='qa' && room.qaEnabled===false)
      || (Array.isArray(room.mutedUserIds) && room.mutedUserIds.includes(uid))))) return json({error:'Forbidden'},403);
  if (type==='reaction' && text.length>16) return json({error:'Invalid reaction'},400);
  const blocked=typeof room.blockedKeywords==='string' ? room.blockedKeywords.split(',').map(v=>v.trim().toLowerCase()).filter(Boolean) : [];
  if (['chat','qa'].includes(type) && blocked.some(word=>text.toLowerCase().includes(word))) return json({error:'Message blocked'},403);
  const profile=await readRow(db,`users/${uid}`);
  if (!profile) return json({error:'Forbidden'},403);
  const actor=decodeValue(JSON.parse(profile.document_json));
  const message={type,text,uid,name:typeof actor.displayName==='string'?actor.displayName.slice(0,200):'',
    photo:typeof actor.photoUrl==='string'?actor.photoUrl.slice(0,2048):'',isHost:host,isMod:mod,createdAt:now,createdAtMs:now};
  const parent=`${path}/chat`;
  const id=gift ? `gift_${input.requestId}` : autoId(), docPath=`${parent}/${id}`;
  const statements=[await chatDocumentGuard(db,path,roomRow)];
  if (gift) {
    const existing=await readRow(db,docPath);
    if (existing) {
      const saved=rowToDocument(existing);
      return saved?.data?.uid===uid && saved.data.type==='gift' && saved.data.giftId===input.giftId && (saved.data.pkBattleId??null)===(input.pkId??null) && (input.pkSide===undefined || saved.data.pkSide===input.pkSide)
        ? json(saved) : json({error:'Gift request already used'},409);
    }
    if (input.pkId!==undefined) {
      if (!validSegment(input.pkId)) return json({error:'Invalid PK match'},400);
      const battle=await db.prepare(`SELECT * FROM friendbook_cf_live_pk WHERE id=? AND status='active' AND expires_at>?`).bind(input.pkId,now).first();
      if (!battle || ![battle.room_a,battle.room_b].includes(roomId) || [battle.host_a,battle.host_b].includes(uid)
          || !await authorizeLiveMedia(db,battle.room_a,uid,'watch') || !await authorizeLiveMedia(db,battle.room_b,uid,'watch')
          || !await authorizeLiveMedia(db,battle.room_a,battle.host_a,'publish') || !await authorizeLiveMedia(db,battle.room_b,battle.host_b,battle.room_a===battle.room_b?'watch':'publish')) return json({error:'PK gift unavailable'},403);
      const score=battle.room_a===battle.room_b ? (input.pkSide==='B'?'score_b':'score_a') : battle.room_a===roomId?'score_a':'score_b';
      statements.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT EXISTS(
        SELECT 1 FROM friendbook_cf_live_pk WHERE id=? AND status='active' AND expires_at>?)`).bind(battle.id,now),
        db.prepare(`UPDATE friendbook_cf_live_pk SET ${score}=${score}+? WHERE id=?`).bind(gift[2],battle.id));
      message.pkBattleId=battle.id;
      message.pkSide=score==='score_b'?'B':'A';
    }
    Object.assign(message,{giftId:input.giftId,giftName:gift[0],giftEmoji:gift[1],stars:gift[2],nonCash:true});
    statements.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (
      SELECT 1 FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>?
        AND json_extract(document_json,'$.type')='gift'
    ) THEN 1 ELSE 0 END`).bind(parent,uid,now-2000));
  }
  if (type==='cohost_req' || type==='pk_req') {
    statements.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (
      SELECT 1 FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>?
        AND json_extract(document_json,'$.type')=?
    ) THEN 1 ELSE 0 END`).bind(parent,uid,now-30000,type));
  }
  if (room.slowMode===true && !host && !mod && ['chat','qa'].includes(type)) {
    statements.push(db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (
      SELECT 1 FROM friendbook_cf_firestore_documents WHERE collection_id='chat' AND parent_path=? AND owner_uid=? AND create_time>?
        AND json_extract(document_json,'$.type') IN ('chat','qa')
    ) THEN 1 ELSE 0 END`).bind(parent,uid,now-5000));
  }
  if (type==='reaction' || gift) {
    const likes=(Number.isSafeInteger(room.likesCount) && room.likesCount>=0 ? room.likesCount : 0)+1;
    const points=Number.isSafeInteger(room.socialGiftPoints) && room.socialGiftPoints>=0 ? room.socialGiftPoints : 0;
    const next=gift ? {...room,socialGiftPoints:points+gift[2]} : {...room,likesCount:likes};
    const canonical=JSON.stringify(next);
    const mirror=JSON.stringify({...next,id:roomId});
    statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
      VALUES (?,'lives','lives',?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json,update_time=excluded.update_time`)
      .bind(path,roomId,room.hostId,canonical,roomRow.create_time||now,now),db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at)
      VALUES (?,'lives',?,?,?,?) ON CONFLICT(path) DO UPDATE SET document_json=excluded.document_json,updated_at=excluded.updated_at`)
      .bind(path,roomId,room.hostId,mirror,now));
  }
  statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?)`).bind(docPath,parent,'chat',id,uid,JSON.stringify(message),now,now),db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
  try { await db.batch(statements); }
  catch (error) {
    if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||''))) return json({error:'Room changed or slow mode is active'},409);
    throw error;
  }
  return json({exists:true,id,path:docPath,parent,collection:'chat',data:message,createTime:now,updateTime:now},201);
}

// A bounded listener can reuse unchanged authorized rows. The server still
// evaluates visibility and ordering; removed IDs are omitted from the new order.
async function queryResponse(parent, documents, known) {
  if (!known || typeof known!=='object' || Array.isArray(known) || documents.length>40
      || Object.keys(known).length>40) return json({parent,documents});
  const encoder=new TextEncoder();
  const entries=await Promise.all(documents.map(async doc=>{
    const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',encoder.encode(JSON.stringify(doc))));
    return [doc.id,btoa(String.fromCharCode(...digest)),doc];
  }));
  return json({parent,order:entries.map(([id])=>id),revisions:Object.fromEntries(entries.map(([id,rev])=>[id,rev])),
    documents:entries.filter(([id,rev])=>!Object.hasOwn(known,id)||known[id]!==rev).map(([, ,doc])=>doc)});
}

export async function firestoreDocuments(request, env, identity, chatRetry=0, ctx=null) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = identity?.uid;
  if (!validSegment(uid)) return json({ error: 'Unauthorized' }, 401);
  const url = new URL(request.url);
  const path = url.pathname;
  const db = env.DB;
  const now = Date.now();
  const writingContent=value=>typeof value==='string' && /^(posts|reels|stories|lives)(?:\/|$)/.test(value);
  if (identity.suspended && (path==='/v1/chat/call' || path==='/v1/live/pk' || (path==='/v1/firestore/document'
      && ['POST','PUT','PATCH'].includes(request.method)
      && writingContent(url.searchParams.get('path') || url.searchParams.get('parent'))))) return json({error:'Account suspended'},403);


  if (path === '/v1/firestore/friendship-facts') return friendshipFacts(request,db,uid);
  if (path === '/v1/firestore/referral-status') return referralStatus(request,db,uid,now);
  if (path === '/v1/firestore/claim-referral') return claimReferral(request,db,uid,now);
  if (path === '/v1/chat/call') return signalChatCall(request,db,uid,now,identity);
  if (path === '/v1/live/pk') return livePk(request,db,uid,now,identity);
  if (path === '/v1/admin/recover-social') return recoverLegacySocial(request,env,uid,now);
  if (path === '/v1/admin/broadcast') return adminBroadcast(request,env,uid,now);
  if (path === '/v1/admin/moderation') return adminModeration(request,env,uid,now);
  if (path === '/v1/firestore/story-engagement') return storyEngagement(request,env,uid,now);

  // One authenticated round trip for the profile header and its first post page.
  // Delegate to the same endpoints/policies so this cannot bypass projections or post privacy.
  if (path === '/v1/firestore/profile-bootstrap' && request.method === 'GET') {
    const target=url.searchParams.get('uid') || '';
    if (!validSegment(target)) return json({error:'Invalid profile'},400);
    const profileRequest=new Request(`${url.origin}/v1/firestore/document?path=${encodeURIComponent(`users/${target}`)}`);
    const postRequest=new Request(`${url.origin}/v1/firestore/query`,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({parent:'posts',filters:[{field:'authorId',op:'==',value:target}],orderBy:[{field:'createdAt',direction:'DESCENDING'}],limit:7})});
    try {
      const [profile,posts]=await Promise.all([firestoreDocuments(profileRequest,env,identity),firestoreDocuments(postRequest,env,identity)]);
      return json({profile:{status:profile.status,body:await profile.json()},posts:{status:posts.status,body:await posts.json()}});
    } catch { return json({error:'Profile bootstrap unavailable'},503); }
  }

  // GET /v1/firestore/document?path=...
  if (path === '/v1/firestore/document' && request.method === 'GET') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    const parts = splitPath(docPath);
    if (['reports','feedback','gifts','referrals','officialBroadcasts'].includes(parts[0]) && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    let checkedCanonicalRoom=false;
    if (parts?.length === 4 && parts[0] === 'chats' && parts[2] === 'messages') {
      try {
        const room=await readRow(db,`chats/${parts[1]}`);
        const data=room && decodeValue(JSON.parse(room.document_json));
        if (!Array.isArray(data?.participants) || !data.participants.includes(uid)) return json({error:'Forbidden'},403);
        // Apply both existing membership interpretations to the same raw
        // canonical snapshot. Legacy conversion can change the raw payload.
        if (room[canonicalRootSnapshot]) {
          if (!storedChatMember(room.document_json,uid)) return json({error:'Forbidden'},403);
          checkedCanonicalRoom=true;
        }
      } catch { return json({error:'Chat access unavailable'},503); }
    }
    let row;
    try { row = await readRow(db, docPath); }
    catch { return json({ error: 'Read unavailable' }, 503); }
    if (!row) return json({ exists: false, id: docIdOf(docPath), path: docPath, data: null });
    const document = rowToDocument(row);
    // Only the just-read canonical root can seed content authorization. Keep
    // nested resources and every profile/group/friendship dependency fresh.
    const canonicalContentFields=row[canonicalRootSnapshot] && parts.length===2
      && ['posts','reels','stories'].includes(parts[0]) ? toFirestoreFields(row.document_json) : undefined;
    try { if (!(await contentReadAllowed(db,uid,docPath,requestAccountReads(identity),true,canonicalContentFields))) return json({error:'Forbidden'},403); }
    catch { return json({error:'Content access unavailable'},503); }
    if (parts[0] === 'friend_requests' && !canReadFriendRequest(uid, document.data)) return json({ error:'Forbidden' }, 403);
    if (parts[0]==='officialBroadcasts' && document.data?.adminId!==uid) return json({error:'Forbidden'},403);
    if (parts[0]==='subscriptions' && !canReadSubscription(env,uid,document.data)) return json({error:'Forbidden'},403);
    if (parts[0] === 'payments' && !canReadPayment(env,uid,document.data)) return json({error:'Forbidden'},403);
    if (!canReadNotification(docPath, uid, document.data)) return json({ error: 'Forbidden' }, 403);
    try {
      if (!checkedCanonicalRoom && !(parts[0]==='chats' && parts.length===2 && row[canonicalRootSnapshot] ? storedChatMember(row.document_json,uid) : await chatReadAllowed(db, docPath, uid, document.data))) return json({ error: 'Forbidden' }, 403);
    } catch { return json({error:'Chat access unavailable'},503); }
    let view;
    try { view = parts[0] === 'groups' ? groupReadView(uid,document.data,await trustedGroupOwner(db,docPath)) : parts[0] === 'friends' ? friendshipReadView(uid, document.data) : profileReadView(docPath, uid, document.data, isPaymentAdmin(env,uid)); }
    catch { return json({error:'Read unavailable'},503); }
    if (view === null) return json({ error: 'Forbidden' }, 403);
    if (parts[0]==='users' && parts.length===2 && url.searchParams.get('profileAudience')==='1') {
      // Counting is opt-in and a failed aggregate must not fail the profile.
      try { view={...view,...await profileAudience(db,parts[1],uid,document.data.followers)}; }
      catch { /* The client can show the ordinary follower count. */ }
    }
    document.data = parts[0]==='config' ? adsView(view) : view;
    return json(document);
  }

  // PUT /v1/firestore/document?path=...  {data, merge, mergeFields}
  if (path === '/v1/firestore/document' && request.method === 'PUT') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friend_requests','friends','payments','reports','feedback'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return json({ error: 'Invalid body' }, 400);
    if (splitPath(docPath)[0]==='config') return await writeAdsConfig(db,env,uid,docPath,body,now);
    if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='users')
      return await mutateProfile(db,uid,docPath,body,'PUT',now);
    if (splitPath(docPath).length===2 && ['posts','reels'].includes(splitPath(docPath)[0]))
      return await mutatePost(db,uid,docPath,body,'PUT',now);
    try {
      const existing = await readRow(db, docPath);
      if (body.createOnly === true && existing) return json({ error: 'ALREADY_EXISTS' }, 409);
      const existingDoc = existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const next = applySet(existingDoc, body.data || {}, Boolean(body.merge), body.mergeFields || null, now);
      if (splitPath(docPath)[0] === 'groups') return await writeGroup(db,uid,docPath,existing,next,now);
      if (['posts','reels'].includes(splitPath(docPath)[0]) && (existingDoc?.groupId || next?.groupId)) return await writeGroupPost(db,uid,docPath,existing,next,now);
      const chatAuth=await authorizeChat(db,docPath,uid,existingDoc,next);
      if (!notificationWriteAllowed(docPath, uid, existingDoc, next) || !profileWriteAllowed(docPath, uid, existingDoc, next) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, existingDoc, next)) return json({ error: 'Forbidden' }, 403);
      if (body.createOnly===true && splitPath(docPath)[0]==='notifications') {
        if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
        const id=docIdOf(docPath), serialized=JSON.stringify(next);
        if (serialized.length>1_800_000) return json({error:'Document too large'},413);
        try {
          // Validate canonical absence inside the insert itself. A race
          // forces a NOT NULL failure and rolls back the entire batch, without
          // inserting and deleting a temporary guard row for every notification.
          await db.batch([
            db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
              VALUES (CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?1) THEN ?1 ELSE NULL END,
                'notifications','notifications',?2,?3,?4,?5,?5)`).bind(docPath,id,uid,serialized,now),
            db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,'notifications',id,uid,JSON.stringify({...next,id}),now)
          ]);
          return json({exists:true,id,path:docPath,parent:'notifications',collection:'notifications',data:next,createTime:now,updateTime:now});
        } catch(e) {
          if (/constraint|document changed/i.test(String(e?.message||''))) return json({error:'ALREADY_EXISTS'},409);
          return json({error:'Write unavailable'},503);
        }
      }
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,existing,chatAuth,uid);
      if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='chats') return await writeChatRoot(db,docPath,next,now,existing,uid,{
        literalNoop:body.merge===true && body.mergeFields==null && literalFields(body.data),
      });
      const row = await writeRow(db, docPath, next, now, existing, ['stories','events','pages','lives'].includes(splitPath(docPath)[0]));
      return json(rowToDocument(row));
    } catch (e) {
      return json({ error: e.message === 'Document too large' ? 'Document too large' : 'Write unavailable' }, e.message === 'Document too large' ? 413 : 503);
    }
  }

  // PATCH /v1/firestore/document?path=...  {fields}
  if (path === '/v1/firestore/document' && request.method === 'PATCH') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friend_requests','friends','reports','feedback','config'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return json({error:'Invalid body'},400);
    if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='users')
      return await mutateProfile(db,uid,docPath,body,'PATCH',now,identity.profileRow);
    if (splitPath(docPath).length===2 && ['posts','reels'].includes(splitPath(docPath)[0]))
      return await mutatePost(db,uid,docPath,body,'PATCH',now);
    if (splitPath(docPath)[0] === 'payments') {
      try { return await reviewPayment(db,env,uid,docPath,body?.fields || body?.data || body?.updates || {},null,now); }
      catch { return json({error:'Review changed or unavailable'},409); }
    }
    try {
      const existing = await readRow(db, docPath);
      if (!existing) return json({ error: 'NOT_FOUND' }, 404);
      const existingDoc = decodeValue(JSON.parse(existing.document_json));
      const next = applyUpdate(existingDoc, body?.fields || body?.data || body?.updates || {}, now);
      if (splitPath(docPath)[0] === 'groups') return await writeGroup(db,uid,docPath,existing,next,now);
      if (['posts','reels'].includes(splitPath(docPath)[0]) && (existingDoc?.groupId || next?.groupId)) return await writeGroupPost(db,uid,docPath,existing,next,now);
      const chatAuth=await authorizeChat(db,docPath,uid,existingDoc,next);
      if (!notificationWriteAllowed(docPath, uid, existingDoc, next) || !profileWriteAllowed(docPath, uid, existingDoc, next) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, existingDoc, next)) return json({ error: 'Forbidden' }, 403);
      if (unchangedNotificationRead(docPath,existing,body.fields||body.data||body.updates)
          || unchangedChatReceipt(docPath,uid,existing,next,body.fields||body.data||body.updates)) return json(rowToDocument(existing));
      const receiptFields=body.fields||body.data||body.updates||{};
      const receiptKeys=Object.keys(receiptFields);
      const ownReactionKeys=['reactions','reactorNames','reactorPhotos'].map(key=>`${key}.${uid}`);
      if (chatAuth.room?.[canonicalRootSnapshot] && /^chats\/[^/]+\/messages\/[^/]+$/.test(docPath)
          && receiptKeys.length && receiptKeys.every(key=>ownReactionKeys.includes(key))
          && existing.document_json===JSON.stringify(next)) {
        // Only an already-applied own reaction can skip the write. Recheck both
        // authorized snapshots together; a concurrent removal or membership
        // change must never be acknowledged as a successful stale operation.
        const current=await db.prepare(`SELECT 1 AS reaction_noop_snapshot FROM friendbook_cf_firestore_documents
          WHERE path=? AND document_json=? AND update_time=?
          AND EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=? AND document_json=?)`)
          .bind(docPath,existing.document_json,existing.update_time,chatAuth.roomPath,chatAuth.room.document_json).first();
        if (!current) return json({error:'Update unavailable'},503);
        return json(rowToDocument(existing));
      }
      if (chatAuth.room?.[canonicalRootSnapshot] && /^chats\/[^/]+\/messages\/[^/]+$/.test(docPath)
          && receiptKeys.length && receiptKeys.every(key=>['isSeen','isDelivered'].includes(key) && receiptFields[key]===true)) {
        // A single UPDATE can enforce both authorized snapshots without
        // inserting and deleting temporary guard rows.
        const serialized=JSON.stringify(next);
        if (serialized.length>1_800_000) return json({error:'Document too large'},413);
        const updated=await db.prepare(`UPDATE friendbook_cf_firestore_documents
          SET document_json=?, owner_uid=?, update_time=? WHERE path=? AND document_json=? AND update_time=?
          AND EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=? AND document_json=?) RETURNING path`)
          .bind(serialized,ownerOf(next,existing.owner_uid||uid),now,docPath,existing.document_json,existing.update_time,
            chatAuth.roomPath,chatAuth.room.document_json).first();
        if (!updated) return json({error:'Update unavailable'},503);
        return json(rowToDocument({...existing,document_json:serialized,update_time:now}));
      }
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,existing,chatAuth,uid);
      if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='chats') {
        const fields=body.fields||body.data||body.updates||{};
        const canonicalOnly=existing[canonicalRootSnapshot] && Object.keys(fields).length===1
          && fields[`unreadCount.${uid}`]===0;
        if (canonicalOnly && existingDoc.isGroup!==true) {
          // Direct rooms have no group authority ledger to update. The row
          // comparison itself keeps membership and unread changes atomic.
          const serialized=JSON.stringify(next);
          if (serialized.length>1_800_000) return json({error:'Document too large'},413);
          const updated=await db.prepare(`UPDATE friendbook_cf_firestore_documents
            SET document_json=?,owner_uid=?,update_time=? WHERE path=? AND document_json=? AND update_time=? RETURNING path`)
            .bind(serialized,ownerOf(next,existing.owner_uid||uid),now,docPath,existing.document_json,existing.update_time).first();
          if (!updated) return json({error:'Update unavailable'},503);
          return json(rowToDocument({...existing,document_json:serialized,update_time:now}));
        }
        return await writeChatRoot(db,docPath,next,now,existing,uid,{canonicalOnly,literalNoop:literalFields(fields)});
      }
      // Active hosts send this exact telemetry patch every eight seconds.
      // Changed telemetry still persists immediately. A quiet renewal can skip
      // the write only while its authorized canonical snapshot remains current.
      if (isLiveHeartbeatPatch(docPath,existing,existingDoc,receiptFields)) {
        if (quietLiveHeartbeat(existingDoc,receiptFields,now)) {
          const current=await db.prepare(`SELECT 1 AS current FROM friendbook_cf_firestore_documents
            WHERE path=? AND document_json=? AND update_time=?`).bind(docPath,existing.document_json,existing.update_time).first();
          if (current?.current===1) return json(rowToDocument(existing));
          return json({error:'Update unavailable'},503);
        }
        const serialized=JSON.stringify(next);
        if (serialized.length>1_800_000) return json({error:'Document too large'},413);
        const updated=await db.prepare(`UPDATE friendbook_cf_firestore_documents
          SET document_json=?,update_time=? WHERE path=? AND document_json=? AND update_time=? RETURNING path`)
          .bind(serialized,now,docPath,existing.document_json,existing.update_time).first();
        if (!updated) return json({error:'Update unavailable'},503);
        return json(rowToDocument({...existing,document_json:serialized,update_time:now}));
      }
      const row = await writeRow(db, docPath, next, now, existing, ['stories','events','pages','lives'].includes(splitPath(docPath)[0]));
      return json(rowToDocument(row));
    } catch {
      return json({ error: 'Update unavailable' }, 503);
    }
  }

  // DELETE /v1/firestore/document?path=...
  if (path === '/v1/firestore/document' && request.method === 'DELETE') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friends','payments','config'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    if (['gifts','referrals','subscriptions','officialBroadcasts'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    if (['reports','feedback'].includes(splitPath(docPath)[0])) {
      if (!canReadReports(env,uid)) return json({error:'Forbidden'},403);
      if (typeof db.batch !== 'function') return json({error:'Atomic storage unavailable'},503);
      try {
        await db.batch([
          db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(docPath),
          db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(docPath),
        ]);
        return json({deleted:true,path:docPath});
      } catch { return json({error:'Report deletion unavailable'},503); }
    }
    if (splitPath(docPath)[0] === 'groups') {
      try { const row=await readRow(db,docPath); if (!row) return json({error:'Forbidden'},403); return await writeGroup(db,uid,docPath,row,null,now); }
      catch { return json({error:'Group changed or unavailable'},409); }
    }
    if (splitPath(docPath)[0] === 'friend_requests') {
      try {
        const row = await readRow(db, docPath);
        if (!row || !canDeleteFriendRequest(uid, decodeValue(JSON.parse(row.document_json)))) return json({error:'Forbidden'},403);
        // A conditional assertion and deletion are committed together. Never delete a request
        // which changed after authorization, even across concurrent Worker isolates.
        if (typeof db.batch !== 'function') return json({error:'Atomic storage unavailable'},503);
        await db.batch([
          await chatDocumentGuard(db,docPath,row),
          db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(docPath),
          db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(docPath),
          db.prepare('DELETE FROM friendbook_cf_social_guards').bind(),
        ]);
        return json({deleted:true,path:docPath});
      } catch { return json({error:'Request changed or deletion unavailable'},409); }
    }
    if (['posts','reels'].includes(splitPath(docPath)[0])) {
      try { const row=await readRow(db,docPath); if (row && decodeValue(JSON.parse(row.document_json))?.groupId) return await writeGroupPost(db,uid,docPath,row,null,now); }
      catch { return json({error:'Post changed or unavailable'},409); }
    }
    if (['notifications','users','pushDevices','chats','messages','posts','reels','stories','events','pages','lives'].includes(splitPath(docPath)?.[0])) {
      let existing;
      try { existing = await readRow(db, docPath); }
      catch { return json({ error: 'Read unavailable' }, 503); }
      const before=existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const chatAuth=await authorizeChat(db,docPath,uid,before,null);
      if (!existing || !notificationWriteAllowed(docPath, uid, before, null) || !profileWriteAllowed(docPath, uid, before, null) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, before, null)) return json({ error: 'Forbidden' }, 403);
      if (chatAuth.roomPath) { try { return await writeChatMessage(db,docPath,null,now,existing,chatAuth,uid,true); } catch { return json({error:'Message changed or unavailable'},503); } }
      if (splitPath(docPath)[0]==='chats' && splitPath(docPath).length===2) { try { return await deleteChatRoot(db,docPath,existing); } catch { return json({error:'Group changed or unavailable'},503); } }
      if (['posts','reels','stories','events','pages','lives'].includes(splitPath(docPath)[0])) {
        if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
        try {
          const guard=await chatDocumentGuard(db,docPath,existing);
          await db.batch([guard,db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(docPath),
            db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(docPath),
            db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
          return json({deleted:true,path:docPath});
        } catch (error) {
          if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||''))) return json({error:'Content changed. Please retry.'},409);
          return json({error:'Deletion unavailable'},503);
        }
      }
    }
    try {
      const canonicalDelete=db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path = ?').bind(docPath);
      if (isRootDocument(docPath)) {
        if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
        await db.batch([canonicalDelete,db.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(docPath)]);
      } else await canonicalDelete.run();
      return json({ deleted: true, path: docPath });
    } catch { return json({error:'Deletion unavailable'},503); }
  }

  // POST /v1/firestore/document?parent=...  {data}  -> add() with auto id
  if (path === '/v1/firestore/document' && request.method === 'POST') {
    const parent = url.searchParams.get('parent') || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    if (!supportedCollectionPath(parent)) return json({error:'Forbidden'},403);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const liveChat=/^lives\/([^/]+)\/chat$/.exec(parent);
    if (liveChat) {
      try { return await createLiveChat(db,liveChat[1],uid,body?.data,now); }
      catch { return json({error:'Live chat unavailable'},503); }
    }
    if (parent === 'friends') return json({error:'Forbidden'},403);
    const id = autoId();
    const docPath = `${parent}/${id}`;
    try {
      const next = applySet(null, body?.data || {}, false, null, now);
      if (['reports','feedback'].includes(parent)) {
        const report=parent==='feedback'?feedbackForCreate(uid,next,now):reportForCreate(uid,next,now);
        if (!report) return json({error:'Invalid report'},400);
        if (typeof db.batch !== 'function') return json({error:'Atomic storage unavailable'},503);
        await db.batch([
          db.prepare('INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)').bind(docPath,parent,parent,id,uid,JSON.stringify(report),now,now),
          db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,parent,id,uid,JSON.stringify({...report,id}),now),
        ]);
        return json({exists:true,id,path:docPath,parent,collection:parent,data:report,createTime:now,updateTime:now},201);
      }
      if (parent === 'groups') return await writeGroup(db,uid,docPath,null,next,now);
      if (['posts','reels'].includes(parent) && next.groupId) return await writeGroupPost(db,uid,docPath,null,next,now);
      if (parent === 'payments') {
        if (!canCreateFreeReview(uid,next)) return json({error:'Forbidden'},403);
        if (typeof db.batch !== 'function') return json({error:'Atomic storage unavailable'},503);
        const serialized=JSON.stringify(next);
        await db.batch([
          db.prepare('INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)').bind(docPath,parent,parent,id,uid,serialized,now,now),
          db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,parent,id,uid,JSON.stringify({...next,id}),now),
        ]);
        return json({exists:true,id,path:docPath,parent,collection:parent,data:next,createTime:now,updateTime:now},201);
      }
      if (parent === 'friend_requests') {
        if (!canCreateFriendRequest(uid, next)) return json({error:'Forbidden'},403);
        if (typeof db.batch !== 'function') return json({error:'Atomic storage unavailable'},503);
        const serialized=JSON.stringify(next);
        // Check pending canonical requests inside the insert transaction.
        // A duplicate forces path NULL and rolls the mirror batch back;
        // no temporary guard row is needed.
        const create=db.prepare(`INSERT INTO friendbook_cf_firestore_documents
          (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
          VALUES (CASE WHEN
          NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests'
            AND COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))='pending')
          THEN ? ELSE NULL END,?,?,?,?,?,?,?)`)
          .bind(uid,next.toUid,docPath,parent,parent,id,uid,serialized,now,now);
        try {
          await db.batch([
            create,
            db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,parent,id,uid,JSON.stringify({...next,id}),now),
          ]);
        } catch (error) {
          if (/NOT NULL constraint failed: friendbook_cf_firestore_documents\.path/i.test(String(error?.message||''))) return json({error:'ALREADY_EXISTS'},409);
          throw error;
        }
        return json({exists:true,id,path:docPath,parent,collection:parent,data:next,createTime:now,updateTime:now},201);
      }

      const chatAuth=await authorizeChat(db,docPath,uid,null,next);
      if (!notificationWriteAllowed(docPath, uid, null, next) || !profileWriteAllowed(docPath, uid, null, next) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, null, next)) return json({ error: 'Forbidden' }, 403);
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,null,chatAuth,uid);
      if (parent === 'chats') return await writeChatRoot(db,docPath,next,now,null,uid);
      const row = await writeRow(db, docPath, next, now, null, ['stories','events','pages','lives'].includes(parent));
      return json(rowToDocument(row), 201);
    } catch {
      return json({ error: 'Write unavailable' }, 503);
    }
  }

  // POST /v1/firestore/query  {parent, filters, orderBy, limit, limitToLast, startAfter, startAt, endBefore, endAt}
  if (path === '/v1/firestore/query' && request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (body?.filters !== undefined && !validQueryFilters(body.filters)) return json({error:'Invalid filters'},400);
    const parent = body?.parent || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    if (!supportedCollectionPath(parent)) return json({error:'Forbidden'},403);
    if (['reports','feedback','gifts'].includes(parent) && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    const parts = splitPath(parent);
    if (parts?.length===3 && parts[0]==='lives') {
      try { if (!await authorizeLiveRoom(db,parts[1],uid)) return json({error:'Forbidden'},403); }
      catch { return json({error:'Live chat unavailable'},503); }
    }
    if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages') {
      try { if (!(await canReadChatMessages(db,parts[1],uid))) return json({error:'Forbidden'},403); }
      catch { return json({error:'Chat access unavailable'},503); }
    }
    if (parent==='referrals' && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    let rows;
    const requestUserReads=requestAccountReads(identity);
    try {
      const tag=body?.postTag;
      if (tag!==undefined && (parent!=='posts' || typeof tag!=='string' || !/^[#@][\p{L}\p{N}_]{1,80}$/u.test(tag))) return json({error:'Invalid post tag'},400);
      if (tag!==undefined) {
        const match=`"${tag}"`;
        const canonical=await db.prepare(`SELECT f.* FROM friendbook_cf_post_search s JOIN friendbook_cf_firestore_documents f ON f.path=s.path
          WHERE friendbook_cf_post_search MATCH ? AND s.source=1`).bind(match).all();
        rows=[...(canonical.results || [])];
      } else {
        const inbox=await boundedInboxPage(db,uid,body,requestUserReads);
        if(inbox!==null)return queryResponse(parent,inbox,body.knownRevisions);
        const people=await boundedNamePage(db,env,uid,body) ?? await boundedPeoplePage(db,env,uid,body) ?? await boundedRecentPeoplePage(db,env,uid,body);
        if (people!==null) return queryResponse(parent,people,body.knownRevisions);
        const page=parent==='notifications'?await boundedNotificationPage(db,uid,body):parent==='stories'?await boundedStoryPage(db,uid,body,requestUserReads):await boundedVideoPostPage(db,uid,body,requestUserReads) ?? await boundedFeedPage(db,uid,body,requestUserReads);
        if (page!==null) return queryResponse(parent,await feedEngagement(db,env,uid,body,page,requestUserReads),body.knownRevisions);
        rows = await readCollectionRows(db, parent, uid, body?.filters, body, env);
      }
    }
    catch { return json({ error: 'Read unavailable' }, 503); }
    const moderator=parent==='users' && !isPaymentAdmin(env,uid) && await verifiedModerator(db,uid);
    let docs;
    const ownFollowFilters=Array.isArray(body?.filters) ? body.filters.filter(f=>ownFollowFilter(parent,uid,f)) : [];
    try {
      const source=rows.map(row=>['posts','reels','stories'].includes(parent)?canonicalContentDocument(row):rowToDocument(row)).filter(doc => doc && canReadNotification(doc.path, uid, doc.data) && (parent !== 'friend_requests' || canReadFriendRequest(uid,doc.data)) && (parent !== 'payments' || canReadPayment(env,uid,doc.data)) && (parent !== 'subscriptions' || canReadSubscription(env,uid,doc.data)) && ownFollowFilters.every(f=>matchesFilter(doc.data,f,doc.id)));
      docs = (await Promise.all(prefilterRaw(source,parent,body?.filters).map(async doc => {
        const view = parent === 'groups' ? groupReadView(uid,doc.data,await trustedGroupOwner(db,doc.path)) : parent === 'friends' ? friendshipReadView(uid,doc.data) : profileReadView(doc.path, uid, doc.data, isPaymentAdmin(env,uid), moderator);
        return view === null ? null : { ...doc, data: view };
      }))).filter(Boolean);
    } catch { return json({error:'Read unavailable'},503); }
    let visible;
    // Archived content is included only for an explicit owner-scoped archive
    // query. Feed, profile counts and ordinary queries retain hidden filtering.
    const filters=Array.isArray(body?.filters)?body.filters:[];
    const archiveQuery=parent==='posts' && filters.some(f=>f.field==='authorId' && f.op==='==' && f.value===uid)
      && filters.some(f=>(f.field==='archived' && f.op==='==' && f.value===true)
        || (f.field==='trashedAt' && f.op==='>' && f.value===0));
    // Some historical posts have nonstandard timestamps, so the indexed feed
    // path above intentionally falls back to the compatible JS ordering. Sort
    // and apply value cursors first, then authorize in bounded chunks until a
    // page is full. This keeps privacy checks before LIMIT without reading each
    // of hundreds of unrelated posts on every feed request.
    const valueCursors=['startAfter','startAt','endBefore','endAt'].every(k=>!body[k] || Array.isArray(body[k]));
    if(parent==='posts' && Number.isInteger(body.limit) && body.limit>0 && body.limit<=100 && !body.limitToLast && valueCursors){
      const ordered=applyQuery(docs,{...body,limit:null});
      const allowed=[];
      try {
        for(let i=0;i<ordered.length && allowed.length<body.limit;i+=20)
          allowed.push(...await visibleDocuments(db,uid,parent,ordered.slice(i,i+20),requestUserReads,archiveQuery));
      } catch { return json({error:'Read unavailable'},503); }
      return queryResponse(parent,await feedEngagement(db,env,uid,body,allowed.slice(0,body.limit),requestUserReads),body.knownRevisions);
    }
    try { visible=await visibleDocuments(db,uid,parent,docs,requestUserReads,archiveQuery); }
    catch { return json({error:'Read unavailable'},503); }
    const spec=ownFollowFilters.length ? {...body,filters:body.filters.filter(f=>!ownFollowFilter(parent,uid,f))} : (body || {});
    // Inbox pagination must order all authorized rooms before taking ten, not
    // alphabetically page first and sort only the rows the client happened to receive.
    if(parent==='chats' && spec.orderBy?.some?.(order=>order?.field==='inboxActivityAt')) {
      visible=visible.map(doc=>({...doc,data:{...doc.data,inboxActivityAt:inboxActivity(doc.data)}}));
    }
    const result = applyQuery(visible, spec);
    return queryResponse(parent,await feedEngagement(db,env,uid,body,result,requestUserReads),body.knownRevisions);
  }

  // GET /v1/firestore/count?parent=...&filters=...
  if (path === '/v1/firestore/count' && request.method === 'GET') {
    const parent = url.searchParams.get('parent') || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    if (!supportedCollectionPath(parent)) return json({error:'Forbidden'},403);
    if (['reports','feedback','gifts'].includes(parent) && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    const parts = splitPath(parent);
    if (parts?.length===3 && parts[0]==='lives') {
      try { if (!await authorizeLiveRoom(db,parts[1],uid)) return json({error:'Forbidden'},403); }
      catch { return json({error:'Live chat unavailable'},503); }
    }
    if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages') {
      try { if (!(await canReadChatMessages(db,parts[1],uid))) return json({error:'Forbidden'},403); }
      catch { return json({error:'Chat access unavailable'},503); }
    }
    let filters = [];
    try { filters = JSON.parse(url.searchParams.get('filters') ?? '[]'); }
    catch { return json({error:'Invalid filters'},400); }
    if (!validQueryFilters(filters)) return json({error:'Invalid filters'},400);
    if (parent==='referrals' && !canReadReports(env,uid) && (filters.length!==1
        || filters[0].field!=='refBy' || filters[0].op!=='==' || filters[0].value!==uid)) return json({error:'Forbidden'},403);
    let rows;
    try { rows = await readCollectionRows(db, parent, uid, filters, undefined, env); }
    catch { return json({ error: 'Read unavailable' }, 503); }
    let docs;
    const ownFollowFilters=Array.isArray(filters) ? filters.filter(f=>ownFollowFilter(parent,uid,f)) : [];
    try {
      const source=rows.map(row=>['posts','reels','stories'].includes(parent)?canonicalContentDocument(row):rowToDocument(row)).filter(doc => doc && canReadNotification(doc.path, uid, doc.data) && (parent !== 'friend_requests' || canReadFriendRequest(uid,doc.data)) && (parent !== 'payments' || canReadPayment(env,uid,doc.data)) && (parent !== 'subscriptions' || canReadSubscription(env,uid,doc.data)) && ownFollowFilters.every(f=>matchesFilter(doc.data,f,doc.id)));
      docs = (await Promise.all(prefilterRaw(source,parent,filters).map(async doc => {
        const view = parent === 'groups' ? groupReadView(uid,doc.data,await trustedGroupOwner(db,doc.path)) : parent === 'friends' ? friendshipReadView(uid,doc.data) : profileReadView(doc.path, uid, doc.data, isPaymentAdmin(env,uid));
        return view === null ? null : { ...doc, data: view };
      }))).filter(Boolean);
    } catch { return json({error:'Read unavailable'},503); }
    let visible;
    try { visible=await visibleDocuments(db,uid,parent,docs,requestAccountReads(identity)); }
    catch { return json({error:'Read unavailable'},503); }
    const filtered = visible.filter(d => (Array.isArray(filters) ? filters : []).filter(f=>!ownFollowFilter(parent,uid,f)).every(f => matchesFilter(d.data, f, d.id)));
    return json({ parent, count: filtered.length });
  }

  // POST /v1/firestore/batch  {operations:[...]}  (also used for transactions)
  if ((path === '/v1/firestore/batch' || path === '/v1/firestore/transaction') && request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const ops = Array.isArray(body?.operations) ? body.operations : [];
    if (ops.length === 0 || ops.length > 500) return json({ error: 'Invalid operations' }, 400);
    if (identity.suspended && ops.some(op=>writingContent(op?.path) && (op.op||op.type)!=='delete')) return json({error:'Account suspended'},403);
    if (ops.some(op=>splitPath(op?.path)?.[0]==='users' && ['followers','following'].some(k=>Object.prototype.hasOwnProperty.call(op.data||op.fields||op.updates||{},k)))) {
      try { return await followBatch(db,uid,ops,now); }
      catch { return json({error:'Follow state changed or unavailable'},409); }
    }

    if (ops.some(op=>splitPath(op?.path)?.[0]==='payments')) {
      const payments=ops.filter(op=>splitPath(op?.path)?.[0]==='payments');
      if (ops.length!==2 || payments.length!==1 || (payments[0].op||payments[0].type)!=='update') return json({error:'Forbidden'},403);
      const profile=ops.find(op=>op!==payments[0]);
      try { return await reviewPayment(db,env,uid,payments[0].path,payments[0].updates||payments[0].fields||payments[0].data||{},profile,now); }
      catch { return json({error:'Review changed or unavailable'},409); }
    }

    if (ops.some(op=>splitPath(op?.path)?.[0]==='friends')) {
      try {
        const friendOp=ops.find(op=>splitPath(op?.path)?.[0]==='friends');
        const indexedCandidates=env.FRIEND_GUARD_INDEXED==='true';
        return (friendOp.op || friendOp.type) === 'delete' ? await unfriendBatch(db,uid,ops,now,indexedCandidates) : await acceptFriendBatch(db,uid,ops,now,indexedCandidates);
      }
      catch { return json({error:'Friendship changed or unavailable'},409); }
    }
    // Pre-read existing docs so FieldValue transforms resolve against current state.
    const cache = new Map();
    // Chat sends carry exactly two independent pre-reads (message and room).
    // Keep large generic batches sequential to avoid flooding D1.
    const paths=[...new Set(ops.map(op=>op?.path).filter(Boolean))];
    const room=paths.find(p=>typeof p==='string' && /^chats\/[^/]+$/.test(p));
    try {
      if (ops.length===2 && paths.length===2 && room && paths.some(p=>typeof p==='string'
          && p.startsWith(`${room}/messages/`) && splitPath(p)?.length===4)) {
        const rows=await Promise.all(paths.map(p=>readRow(db,p)));
        paths.forEach((p,i)=>cache.set(p,rows[i]));
      } else {
        for (const p of paths) cache.set(p,await readRow(db,p));
      }
    } catch { return json({ error: 'Read unavailable' }, 503); }
    // Compute final docs in memory, then write atomically via D1 batch.
    const finalDocs = new Map(); // path -> {doc|null(deleted), existingRow}
    const order = [];
    for (const op of ops) {
      const p = op.path;
      if (!isDocumentPath(p)) return json({ error: 'Invalid path' }, 400);
      if (!supportedDocumentPath(p) || ['friend_requests','friends','payments','groups','reports','feedback','config'].includes(splitPath(p)[0])) return json({error:'Forbidden'},403);
      if (!finalDocs.has(p)) {
        const existingRow = cache.get(p) || null;
        const existingDoc = existingRow ? decodeValue(JSON.parse(existingRow.document_json)) : null;
        finalDocs.set(p, { doc: existingDoc, existingRow, deleted: false });
        order.push(p);
      }
      const entry = finalDocs.get(p);
      const type = op.type || op.op || 'set';
      if (type === 'delete') {
        entry.deleted = true;
        entry.doc = null;
        continue;
      }
      if (type === 'update') {
        if (!entry.existingRow && !entry.doc) return json({ error: 'NOT_FOUND' }, 404);
        entry.doc = applyUpdate(entry.doc, op.fields || op.updates || op.data || {}, now);
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
    // Rooms created in this batch can authorize a first message, but only after the room's
    // own write has passed membership validation; existing membership always wins.
    const pendingRooms = new Map();
    for (const p of order) {
      const parts = splitPath(p), entry = finalDocs.get(p);
      if (parts.length === 2 && parts[0] === 'chats' && !entry.existingRow && !entry.deleted) {
        if (!(await chatWriteAllowed(db,p,uid,null,entry.doc))) return json({error:'Forbidden'},403);
        pendingRooms.set(parts[1],entry.doc);
      }
    }
    // Authorize the complete batch before issuing any database writes.
    const chatAuths=new Map();
    for (const p of order) {
      const entry = finalDocs.get(p);
      const before = entry.existingRow ? decodeValue(JSON.parse(entry.existingRow.document_json)) : null;
      const chatAuth=await authorizeChat(db,p,uid,before,entry.deleted ? null : entry.doc,pendingRooms,cache);
      chatAuths.set(p,chatAuth);
      if (!notificationWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc) || !profileWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc) || !chatAuth.allowed || !contentWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc)) return json({ error: 'Forbidden' }, 403);
    }
    // An authorized batch may mix already-read notifications with replayed
    // message receipts and an already-zero unread counter. No guard or data
    // writes are needed if every operation is a literal acknowledgement no-op.
    if (path==='/v1/firestore/batch' && ops.every(op=>{
      const entry=finalDocs.get(op.path),fields=op.fields||op.updates||op.data;
      return (op.type||op.op)==='update' && !entry?.deleted && (
        unchangedNotificationRead(op.path,entry?.existingRow,fields) ||
        unchangedChatReceipt(op.path,uid,entry?.existingRow,entry?.doc,fields));
    })) return json({ok:true,operations:ops.length});
    // Keep the original authorization and optimistic snapshots, but don't
    // rewrite identical receipt rows alongside a genuinely changed receipt.
    const unchangedBatchRows=new Set();
    if(path==='/v1/firestore/batch') {
      const writesPerPath=new Map();
      for(const op of ops)writesPerPath.set(op.path,(writesPerPath.get(op.path)||0)+1);
      for(const op of ops) {
        if(writesPerPath.get(op.path)!==1 || (op.type||op.op)!=='update')continue;
        const fields=op.fields||op.updates||op.data||{},entry=finalDocs.get(op.path);
        if(!entry?.deleted && (unchangedNotificationRead(op.path,entry?.existingRow,fields)
            || unchangedChatReceipt(op.path,uid,entry?.existingRow,entry?.doc,fields))) unchangedBatchRows.add(op.path);
      }
    }
    const unreadOnlyRooms=new Set();
    if (path==='/v1/firestore/batch') {
      const byPath=new Map();
      for (const op of ops) {
        const fields=op.fields||op.updates||op.data||{};
        const onlyReset=(op.type||op.op)==='update' && Object.keys(fields).length===1 && fields[`unreadCount.${uid}`]===0;
        byPath.set(op.path,(byPath.get(op.path)??true) && onlyReset);
      }
      for (const [p,onlyReset] of byPath) if (onlyReset && /^chats\/[^/]+$/.test(p)) unreadOnlyRooms.add(p);
    }
    const messageRoomPaths=new Set(order.filter(p=>splitPath(p)?.length===4).map(p=>chatAuths.get(p)?.roomPath).filter(Boolean));
    // Chat preview fields only. Typing state no longer exists in any write path.
    const previewKeys=new Set(['lastMessage','lastMessageTime','lastMessageTimeMs','unreadCount','participantNames','participantPhotos']);
    const canonicalOnlyRoom=(p,entry)=>{
      if (!entry.existingRow?.[canonicalRootSnapshot]) return false;
      if (unreadOnlyRooms.has(p)) return true;
      if (!messageRoomPaths.has(p)) return false;
      const before=decodeValue(JSON.parse(entry.existingRow.document_json));
      return [...new Set([...Object.keys(before),...Object.keys(entry.doc)])].every(key=>
        previewKeys.has(key) || JSON.stringify(before[key])===JSON.stringify(entry.doc[key]));
    };
    try {
      const statements = [];
      let snapshotGroup=[], snapshotSize=0;
      const flushSnapshots=()=>{
        if(snapshotGroup.length) statements.push(chatSnapshotGroupGuard(db,snapshotGroup));
        snapshotGroup=[];snapshotSize=0;
      };
      const addSnapshot=(p,row)=>{
        const item=JSON.stringify([p,row?.document_json??null]);
        // Bound each JSON binding; very large historical messages stay on the
        // old guard path rather than growing an unbounded combined binding.
        if (item.length>20_000) return false;
        if(snapshotSize+item.length+1>20_000) flushSnapshots();
        snapshotGroup.push(item);snapshotSize+=item.length+1;
        return true;
      };
      const roomPaths=order.filter(p=>splitPath(p).length===2 && splitPath(p)[0]==='chats');
      const messagePaths=order.filter(p=>chatAuths.get(p).roomPath);
      const guardedPaths=order.filter(p=>splitPath(p).length===2 && ['users','posts','reels','stories','events','pages','lives'].includes(splitPath(p)[0]));
      if ((roomPaths.length || messagePaths.length || guardedPaths.length) && typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
      const roomPath=roomPaths[0], messagePath=messagePaths[0];
      const roomEntry=finalDocs.get(roomPath), messageEntry=finalDocs.get(messagePath);
      const roomBefore=roomEntry?.existingRow ? decodeValue(JSON.parse(roomEntry.existingRow.document_json)) : null;
      const fastDirectSend=path==='/v1/firestore/batch' && ops.length===2 && order.length===2
        && ops.every(op=>(op.type||op.op)==='set') && roomPaths.length===1 && messagePaths.length===1
        && /^chats\/[^/]+\/messages\/[^/]+$/.test(messagePath) && chatAuths.get(messagePath)?.roomPath===roomPath
        && !messageEntry?.existingRow && !messageEntry?.deleted
        && roomEntry?.existingRow?.[canonicalRootSnapshot] && !roomEntry.deleted
        && roomBefore?.isGroup!==true && roomEntry.doc?.isGroup!==true;
      const receiptOp=ops.find(op=>op.path===messagePath), resetOp=ops.find(op=>op.path===roomPath);
      const receiptFields=receiptOp?.fields||receiptOp?.updates||receiptOp?.data||{};
      const resetFields=resetOp?.fields||resetOp?.updates||resetOp?.data||{};
      const fastDirectReceipt=path==='/v1/firestore/batch' && ops.length===2 && order.length===2
        && roomPaths.length===1 && messagePaths.length===1 && /^chats\/[^/]+\/messages\/[^/]+$/.test(messagePath)
        && chatAuths.get(messagePath)?.roomPath===roomPath
        && (receiptOp?.op||receiptOp?.type)==='update' && (resetOp?.op||resetOp?.type)==='update'
        && Object.keys(receiptFields).length>0 && Object.keys(receiptFields).every(key=>
          ['isSeen','isDelivered'].includes(key) && receiptFields[key]===true)
        && Object.keys(resetFields).length===1 && resetFields[`unreadCount.${uid}`]===0
        && messageEntry?.existingRow && !messageEntry.deleted && roomEntry?.existingRow?.[canonicalRootSnapshot]
        && !roomEntry.deleted && roomBefore?.isGroup!==true && roomEntry.doc?.isGroup!==true
        && !unchangedBatchRows.has(messagePath) && !unchangedBatchRows.has(roomPath);
      if (fastDirectReceipt) {
        const messageJson=JSON.stringify(messageEntry.doc),roomJson=JSON.stringify(roomEntry.doc);
        if (messageJson.length>1_800_000 || roomJson.length>1_800_000) return json({error:'Document too large'},413);
        // The first write checks both authorized snapshots inside the same D1
        // transaction. A missing/changed message or room forces a constraint
        // failure and rolls back the whole batch, including the unread reset.
        statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS quota_writer_batch_chats
          (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
          VALUES (CASE WHEN EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?1 AND document_json=?2)
            AND EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?3 AND document_json=?4)
            THEN ?3 ELSE NULL END,?5,?6,?7,?8,?9,?10,?11)
          ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
          .bind(roomPath,roomEntry.existingRow.document_json,messagePath,messageEntry.existingRow.document_json,
            parentPath(messagePath),collectionIdOf(messagePath),docIdOf(messagePath),
            ownerOf(messageEntry.doc,messageEntry.existingRow.owner_uid||uid),messageJson,messageEntry.existingRow.create_time||now,now));
        statements.push(db.prepare(`UPDATE friendbook_cf_firestore_documents SET owner_uid=?,document_json=?,update_time=? WHERE path=?`)
          .bind(ownerOf(roomEntry.doc,roomEntry.existingRow.owner_uid||uid),roomJson,now,roomPath));
      } else if (fastDirectSend) {
        const messageJson=JSON.stringify(messageEntry.doc),roomJson=JSON.stringify(roomEntry.doc);
        if (messageJson.length>1_800_000 || roomJson.length>1_800_000) return json({error:'Document too large'},413);
        // The insert itself checks the exact authorized room and both message
        // stores. A failed check violates path's NOT NULL constraint, rolling
        // back the whole D1 batch including the room update. No guard row is
        // inserted or deleted, and a retry redoes reads, transforms and ACLs.
        statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents AS quota_writer_batch_chats
          (path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
          VALUES (CASE WHEN EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?1 AND document_json=?2)
            AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?3) THEN ?3 ELSE NULL END,?4,?5,?6,?7,?8,?9,?9)`)
          .bind(roomPath,roomEntry.existingRow.document_json,messagePath,parentPath(messagePath),collectionIdOf(messagePath),docIdOf(messagePath),ownerOf(messageEntry.doc,uid),messageJson,now));
        const roomOwner=ownerOf(roomEntry.doc,roomEntry.existingRow.owner_uid||uid);
        statements.push(db.prepare(`UPDATE friendbook_cf_firestore_documents SET owner_uid=?,document_json=?,update_time=? WHERE path=?`)
          .bind(roomOwner,roomJson,now,roomPath));
        if (!canonicalOnlyRoom(roomPath,roomEntry)) statements.push(db.prepare(`INSERT INTO friendbook_cf_documents AS quota_writer_batch_chats(path,collection,doc_id,owner_uid,document_json,updated_at)
          VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`)
          .bind(roomPath,'chats',docIdOf(roomPath),roomOwner,JSON.stringify({...roomEntry.doc,id:docIdOf(roomPath)}),now));
      } else {
        for (const p of guardedPaths) statements.push(await chatDocumentGuard(db,p,finalDocs.get(p).existingRow));
        for (const roomPath of roomPaths) {
          const entry=finalDocs.get(roomPath), before=entry.existingRow?decodeValue(JSON.parse(entry.existingRow.document_json)):null;
          if (!entry.existingRow?.[canonicalRootSnapshot] || !addSnapshot(roomPath,entry.existingRow)) {
            statements.push(...await chatRootGuards(db,roomPath,entry.existingRow));
          }
          const ledger=await groupChatLedgerChanges(db,roomPath,before,entry.deleted?null:entry.doc);
          statements.push(...ledger.guards,...ledger.writes);
        }
        // A chat batch authorizes every message, but all messages in a room can
        // share the same snapshot and atomic check. Root writes already add one.
        const checkedRooms=new Set(roomPaths);
        for (const p of messagePaths) {
          const auth=chatAuths.get(p);
          if (!auth.room && !pendingRooms.has(splitPath(auth.roomPath)[1])) return json({error:'Forbidden'},403);
          if (auth.room && !checkedRooms.has(auth.roomPath)) {
            if (!auth.room[canonicalRootSnapshot] || !addSnapshot(auth.roomPath,auth.room)) {
              statements.push(await chatDocumentGuard(db,auth.roomPath,auth.room));
            }
            checkedRooms.add(auth.roomPath);
          }
          const snapshot=finalDocs.get(p).existingRow;
          if (isRootDocument(p) || !addSnapshot(p,snapshot)) statements.push(await chatDocumentGuard(db,p,snapshot));
        }
        flushSnapshots();
        for (const p of order) {
          if(unchangedBatchRows.has(p))continue;
          const entry = finalDocs.get(p);
        if (entry.deleted) {
          statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path = ?').bind(p));
          if (isRootDocument(p)) statements.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path = ?').bind(p));
        } else {
            const parent = parentPath(p);
            const coll = collectionIdOf(p);
            const id = docIdOf(p);
            const owner = ownerOf(entry.doc, entry.existingRow?.owner_uid || uid);
            const createTime = entry.existingRow?.create_time || now;
            const serialized = JSON.stringify(entry.doc);
            if (serialized.length > 1_800_000) return json({ error: 'Document too large' }, 413);
            statements.push(db.prepare(
              `INSERT INTO friendbook_cf_firestore_documents AS ${writeAttribution('batch',p)}(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
            ).bind(p, parent, coll, id, owner, serialized, createTime, now));
            if (isRootDocument(p) && id && coll && !canonicalOnlyRoom(p,entry)) {
              const appDoc = JSON.stringify({ ...entry.doc, id });
              const appOwner = owner || (coll === 'users' ? id : '');
              statements.push(db.prepare(
                `INSERT INTO friendbook_cf_documents AS ${writeAttribution('batch',p)}(path, collection, doc_id, owner_uid, document_json, updated_at)
                 VALUES (?, ?, ?, ?, ?, ?)
                 ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid, document_json=excluded.document_json, updated_at=excluded.updated_at`
              ).bind(p, coll, id, appOwner, appDoc, now));
            }
          }
        }
        if (roomPaths.length || messagePaths.length || guardedPaths.length) statements.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
      }
      if (typeof db.batch === 'function' && statements.length) {
        await db.batch(statements);
      } else {
        for (const s of statements) await s.run();
      }
      let pushScheduled=false;
      if(ctx?.waitUntil && env.FIREBASE_ADMIN_SERVICE_ACCOUNT && body.chatPush===true && path==='/v1/firestore/batch' && order.length===2 && ops.length===2) {
        const roomPath=order.find(p=>/^chats\/[^/]+$/.test(p));
        const msgPath=order.find(p=>/^chats\/[^/]+\/messages\/[^/]+$/.test(p));
        const chat=finalDocs.get(roomPath)?.doc, entry=finalDocs.get(msgPath);
        if(roomPath && msgPath && parentPath(parentPath(msgPath))===roomPath && chat && entry?.doc?.senderId===uid
            && !entry.existingRow && !entry.doc.isDeleted && Array.isArray(chat.participants)
            && chat.participants.includes(uid) && chat.participants.length>=2 && chat.participants.length<=8) {
          const [,chatId,,messageId]=splitPath(msgPath);
          try {
            ctx.waitUntil(pushCommittedChat(env,identity,chatId,messageId,chat,entry.doc).catch(()=>{}));
            pushScheduled=true;
          } catch { /* Caller retains its original push fallback. */ }
        }
      }
      return json({ ok: true, operations: ops.length, ...(pushScheduled?{pushScheduled}:{}) });
    } catch (error) {
      // Retry only a definitely rolled-back optimistic guard conflict, and only the
      // two-operation chat send shape. Re-run all reads, transforms and ACL checks;
      // never retry ambiguous network/storage failures or a potentially committed batch.
      const messages=ops.filter(op => splitPath(op.path)?.length===4 && splitPath(op.path)?.[0]==='chats' && splitPath(op.path)?.[2]==='messages' && (op.op||op.type)==='set');
      const rooms=ops.filter(op => splitPath(op.path)?.length===2 && splitPath(op.path)?.[0]==='chats' && (op.op||op.type)==='set');
      const guardConflict=/CHECK constraint failed: ok\s*=\s*1|NOT NULL constraint failed: friendbook_cf_firestore_documents\.path|^Room changed$|^Document changed$/i.test(String(error?.message||''));
      if (chatRetry<2 && guardConflict && ops.length===2 && messages.length===1 && rooms.length===1
          && parentPath(parentPath(messages[0].path))===rooms[0].path) {
        const replay=new Request(request.url,{method:'POST',headers:request.headers,body:JSON.stringify(body)});
        return firestoreDocuments(replay,env,identity,chatRetry+1,ctx);
      }
      return json({ error: 'Batch unavailable' }, 503);
    }
  }

  return json({ error: 'Not found' }, 404);
}

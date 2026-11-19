import { canReadPayment, canCreateFreeReview, isPaymentAdmin } from './payment-access.js';
import { canReadFriendRequest, canCreateFriendRequest, canDeleteFriendRequest, friendshipReadView, canCreateGroup, canUpdateGroup, canManageGroup, canSelfManageGroup, isSelfArrayChange, groupReadView, trustedGroupOwner } from './social-access.js';
import { supportedDocumentPath, supportedCollectionPath, contentReadAllowed, contentWriteAllowed } from './document-policy.js';
import { chatReadAllowed, chatWriteAllowed } from './chat-access.js';
import { profileReadView, profileWriteAllowed } from './profile-access.js';
import { canReadNotification, notificationWriteAllowed } from './notification-access.js';
import { canReadReports, reportForCreate } from './report-access.js';
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
  const v = d.authorId || d.uid || d.senderId || d.from || d.toUserId || d.toUid || d.userId || d.creatorId || d.hostId;
  return typeof v === 'string' && v ? v : (fallback || '');
}

// ---------- D1 helpers ----------
function appDocumentToFirestoreRow(row) {
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
  if (doc?.fields && typeof doc.fields === 'object' && !Array.isArray(doc.fields)) doc = decodeFields(doc.fields);
  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) return null;
  const parts = String(row.path || '').split('/');
  if (parts.length !== 2) return null;
  delete doc.id; // Firestore document identity is its path/ID, not an extra payload field.
  return {
    path: row.path, parent_path: parts[0], collection_id: row.collection,
    doc_id: row.doc_id, owner_uid: row.owner_uid || '', document_json: JSON.stringify(doc),
    create_time: Number(doc.createdAt) || 0, update_time: Number(row.updated_at) || 0,
  };
}

async function readRow(db, path) {
  const row = await db.prepare(
    'SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE path = ?'
  ).bind(path).first();
  if (row) return row;
  // Read-through compatibility for root records written by the app-document API or CloudflareStore.
  // Both stores are D1, but older app versions wrote only friendbook_cf_documents. Without this,
  // ProfileActivity and PostDetailActivity report "User/Post not found" despite a valid D1 record.
  if (!isRootDocument(path)) return null;
  const appRow = await db.prepare(
    'SELECT path, collection, doc_id, owner_uid, document_json, updated_at FROM friendbook_cf_documents WHERE path = ?'
  ).bind(path).first();
  return appDocumentToFirestoreRow(appRow);
}

// A bounded fast path for ordinary canonical chat pages. Unusual historical timestamp
// formats, legacy mirrors, filters and complex cursors deliberately use the old merge.
const chatRank = `CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END`;
const chatTime = `CASE WHEN json_type(document_json,'$.createdAt') IN ('integer','real') THEN json_extract(document_json,'$.createdAt') ELSE json_extract(document_json,'$.createdAt.millis') END`;
async function boundedChatRows(db,parent,spec) {
  const parts=splitPath(parent), orders=spec?.orderBy;
  if (parts?.length!==3 || parts[0]!=='chats' || parts[2]!=='messages'
      || !Array.isArray(orders) || orders.length!==1 || orders[0]?.field!=='createdAt'
      || !['ASCENDING','DESCENDING'].includes(orders[0]?.direction)
      || (Array.isArray(spec?.filters) && spec.filters.length) || spec?.startAt || spec?.endAt || spec?.endBefore
      || !Number.isInteger(spec?.limit) || spec.limit<=0 || spec.limit>1000
      || (spec.limitToLast!==undefined && spec.limitToLast!==true && spec.limitToLast!==false)) return null;
  let cursor=null;
  if (spec.startAfter) {
    const c=spec.startAfter;
    if (spec.limitToLast || !Array.isArray(c) || c.length!==2 || typeof c[1]!=='string' || !/^[\x20-\x7e]+$/.test(c[1])) return null;
    const value=c[0], rank=typeof value==='number'?2:value?.__type==='timestamp'?4:0;
    const time=rank===2?value:value?.millis;
    if (!rank || !Number.isFinite(time) || Math.abs(time)>Number.MAX_SAFE_INTEGER) return null;
    cursor=[rank,rank===4?Math.trunc(time):time,c[1]];
  }
  const prefix=`${parts[1]}_`, upper=`${parts[1]}\``;
  const legacy=await db.prepare(`SELECT 1 AS hit FROM friendbook_cf_documents WHERE collection='messages' AND doc_id>=? AND doc_id<?
    UNION ALL SELECT 1 AS hit FROM friendbook_cf_documents WHERE collection='messages' AND json_extract(document_json,'$.chatId')=? LIMIT 1`).bind(prefix,upper,parts[1]).first();
  if (legacy) return null;
  // Ensure SQLite's ordering matches the existing JS comparator exactly. Older or
  // malformed records must never be dropped simply to make the page smaller.
  const bad=await db.prepare(`SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path=? AND (
    doc_id GLOB '*[^ -~]*' OR COALESCE((
      (json_type(document_json,'$.createdAt') IN ('integer','real') AND json_extract(document_json,'$.createdAt') BETWEEN -9007199254740991 AND 9007199254740991)
      OR (json_type(document_json,'$.createdAt')='object' AND json_extract(document_json,'$.createdAt.__type')='timestamp'
        AND json_type(document_json,'$.createdAt.millis')='integer' AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991)
    ),0)=0) LIMIT 1`).bind(parent).first();
  if (bad) return null;
  const descending=orders[0].direction==='DESCENDING';
  const takeLast=spec.limitToLast===true;
  const direction=(descending !== takeLast)?'DESC':'ASC';
  const boundary=cursor?` AND (${chatRank},${chatTime},doc_id) ${descending?'<':'>'} (?,?,?)`:'';
  const statement=db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
    FROM friendbook_cf_firestore_documents WHERE parent_path=?${boundary}
    ORDER BY ${chatRank} ${direction}, ${chatTime} ${direction}, doc_id ${direction} LIMIT ?`);
  const result=await statement.bind(parent,...(cursor||[]),spec.limit).all();
  return result?.results || [];
}

async function readCollectionRows(db, parent, uid, filters, spec) {
  const bounded=spec ? await boundedChatRows(db,parent,spec) : null;
  if (bounded!==null) return bounded;
  if (parent === 'friends') {
    // Only a participant can see a friendship. Index the two positions instead
    // of loading every user's relationships and projecting them in JavaScript.
    const canonical = await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[0]')=?
      UNION ALL SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friends' AND json_extract(document_json,'$.participants[1]')=?
        AND json_extract(document_json,'$.participants[0]') IS NOT ?`).bind(uid,uid,uid).all();
    const legacy = await db.prepare(`SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at FROM friendbook_cf_documents a
      WHERE a.collection='friends' AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.participants.arrayValue.values[0].stringValue') ELSE json_extract(a.document_json,'$.participants[0]') END)=?
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
      UNION ALL SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at FROM friendbook_cf_documents a
      WHERE a.collection='friends' AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.participants.arrayValue.values[1].stringValue') ELSE json_extract(a.document_json,'$.participants[1]') END)=?
      AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.participants.arrayValue.values[0].stringValue') ELSE json_extract(a.document_json,'$.participants[0]') END) IS NOT ?
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`).bind(uid,uid,uid).all();
    return [...(canonical?.results || []), ...(legacy?.results || []).map(appDocumentToFirestoreRow).filter(Boolean)];
  }
  if (parent === 'friend_requests') {
    // Only participants can read requests, regardless of query filter supplied by the client.
    const canonical = await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.fromUid')=?
      UNION ALL
      SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests' AND json_extract(document_json,'$.toUid')=? AND json_extract(document_json,'$.fromUid') IS NOT ?`).bind(uid,uid,uid).all();
    const legacy = await db.prepare(`SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at FROM friendbook_cf_documents a
      WHERE a.collection='friend_requests' AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.fromUid.stringValue') ELSE json_extract(a.document_json,'$.fromUid') END)=?
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
      UNION ALL
      SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at FROM friendbook_cf_documents a
      WHERE a.collection='friend_requests' AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.toUid.stringValue') ELSE json_extract(a.document_json,'$.toUid') END)=?
        AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.fromUid.stringValue') ELSE json_extract(a.document_json,'$.fromUid') END) IS NOT ?
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`).bind(uid,uid,uid).all();
    return [...(canonical?.results || []), ...(legacy?.results || []).map(appDocumentToFirestoreRow).filter(Boolean)];
  }
  const authorFilter=Array.isArray(filters) && filters.find(f=>f?.field==='authorId' && f.op==='==' && typeof f.value==='string');
  if (authorFilter && (parent==='posts' || parent==='reels')) {
    // Only known constant SQL collection names are interpolated; values stay bound.
    // Apply remaining filters, authorization and paging in the existing query engine.
    const canonical = await db.prepare(`SELECT path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time FROM friendbook_cf_firestore_documents
      WHERE parent_path='${parent}' AND json_extract(document_json,'$.authorId')=?`).bind(authorFilter.value).all();
    const legacy = await db.prepare(`SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at FROM friendbook_cf_documents a
      WHERE a.collection='${parent}' AND (CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.authorId.stringValue') ELSE json_extract(a.document_json,'$.authorId') END)=?
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`).bind(authorFilter.value).all();
    return [...(canonical?.results || []), ...(legacy?.results || []).map(appDocumentToFirestoreRow).filter(Boolean)];
  }
  if (parent === 'notifications') {
    // Authorization itself narrows the inbox before loading rows. Keep canonical
    // path precedence even when its recipient differs from a stale legacy mirror.
    const canonical = await db.prepare(`SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time
      FROM friendbook_cf_firestore_documents WHERE parent_path='notifications' AND json_extract(document_json,'$.toUserId')=?`).bind(uid).all();
    const legacy = await db.prepare(`SELECT a.path, a.collection, a.doc_id, a.owner_uid, a.document_json, a.updated_at
      FROM friendbook_cf_documents a WHERE a.collection='notifications'
        AND (CASE WHEN json_type(a.document_json,'$.fields')='object'
          THEN json_extract(a.document_json,'$.fields.toUserId.stringValue')
          ELSE json_extract(a.document_json,'$.toUserId') END)=?
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)`).bind(uid).all();
    return [...(canonical?.results || []), ...(legacy?.results || []).map(appDocumentToFirestoreRow).filter(Boolean)];
  }
  const firestoreRows = await db.prepare(
    'SELECT path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time FROM friendbook_cf_firestore_documents WHERE parent_path = ?'
  ).bind(parent).all();
  const byPath = new Map((firestoreRows?.results || []).map(row => [row.path, row]));
  // The older app-document API owns most historical top-level posts/chats/friends. Firestore
  // compatibility queries previously ignored those rows, so feed and list screens appeared empty
  // even though production D1 had hundreds of valid documents. Merge only root collections, and
  // let the Firestore-compatible row win where both stores contain the same path.
  const parts = splitPath(parent);
  if (parts?.length === 1) {
    const appRows = await db.prepare(
      'SELECT path, collection, doc_id, owner_uid, document_json, updated_at FROM friendbook_cf_documents WHERE collection = ?'
    ).bind(parent).all();
    for (const appRow of appRows?.results || []) {
      if (byPath.has(appRow.path)) continue;
      const row = appDocumentToFirestoreRow(appRow);
      if (row) byPath.set(row.path, row);
    }
  } else if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages') {
    // Legacy CloudflareStore messages use either messages/{chatId}_{messageId} IDs or a chatId
    // field, while Android reads chats/{chatId}/messages/{messageId}. Restrict this bridge to
    // exact ID-prefix/chatId matches and a participant-checked chat query; native nested rows win.
    const prefix = `${parts[1]}_`, upper = `${parts[1]}\``;
    // Two indexable branches in one D1 request. '_' and '`' are adjacent ASCII
    // characters, so this range exactly matches prefix + any valid message ID.
    // Exclude prefix rows from branch two to avoid duplicating a legacy message.
    const appRows = await db.prepare(
      `SELECT path, collection, doc_id, owner_uid, document_json, updated_at
       FROM friendbook_cf_documents
       WHERE collection='messages' AND doc_id>=? AND doc_id<?
       UNION ALL
       SELECT path, collection, doc_id, owner_uid, document_json, updated_at
       FROM friendbook_cf_documents
       WHERE collection='messages' AND json_extract(document_json,'$.chatId')=?
         AND NOT (doc_id>=? AND doc_id<?)`
    ).bind(prefix, upper, parts[1], prefix, upper).all();
    for (const appRow of appRows?.results || []) {
      const messageId = appRow.doc_id.startsWith(prefix) ? appRow.doc_id.slice(prefix.length) : appRow.doc_id;
      if (!validSegment(messageId)) continue;
      const nestedPath = `${parent}/${messageId}`;
      if (byPath.has(nestedPath)) continue;
      const row = appDocumentToFirestoreRow(appRow);
      if (row) byPath.set(nestedPath, { ...row, path:nestedPath, parent_path:parent, collection_id:'messages', doc_id:messageId });
    }
  }
  return [...byPath.values()];
}

async function canReadChatMessages(db, chatId, uid) {
  const room = await readRow(db, `chats/${chatId}`).catch(() => null);
  if (!room) return false;
  try {
    const data = decodeValue(JSON.parse(room.document_json));
    return Array.isArray(data?.participants) && data.participants.includes(uid);
  } catch { return false; }
}

async function writeRow(db, path, doc, now, existing, guarded = false) {
  const parent = parentPath(path);
  const coll = collectionIdOf(path);
  const id = docIdOf(path);
  const owner = ownerOf(doc, existing?.owner_uid || (coll === 'users' ? id : ''));
  const createTime = existing?.create_time || now;
  const serialized = JSON.stringify(doc);
  if (serialized.length > 1_800_000) throw new Error('Document too large');
  const writes = [db.prepare(
    `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
  ).bind(path, parent, coll, id, owner, serialized, createTime, now)];

  // Keep both root representations in one D1 transaction: a failed mirror must not
  // leave a successful-looking profile/post in only one of the two tables.
  if (isRootDocument(path) && id && coll) {
    const appDoc = JSON.stringify({ ...doc, id });
    const appOwner = owner || (coll === 'users' ? id : '');
    writes.push(db.prepare(
      `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid, document_json=excluded.document_json, updated_at=excluded.updated_at`
    ).bind(path, coll, id, appOwner, appDoc, now));
  }
  if (guarded) {
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
  };
}

// Recompute a profile transform after a conflicting friend/follow/profile write.
// The snapshot guard and both copies commit together, so concurrent edits cannot
// silently erase relationship counters or another device's profile changes.
async function mutateProfile(db, uid, path, body, method, now) {
  if (splitPath(path)[1] !== uid) return json({error:'Forbidden'},403);
  for (let attempt=0; attempt<4; attempt++) {
    try {
      const existing=await readRow(db,path);
      if (method==='PATCH' && !existing) return json({error:'NOT_FOUND'},404);
      if (body.createOnly===true && existing) return json({error:'ALREADY_EXISTS'},409);
      const before=existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const next=method==='PATCH' ? applyUpdate(before,body?.fields || body?.data || body?.updates || {},now)
        : applySet(before,body?.data || {},Boolean(body?.merge),body?.mergeFields || null,now);
      if (!profileWriteAllowed(path,uid,before,next)) return json({error:'Forbidden'},403);
      return json(rowToDocument(await writeRow(db,path,next,now,existing,true)));
    } catch (error) {
      const message=String(error?.message || '');
      if (message==='Document too large') return json({error:message},413);
      if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(message)) {
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
      return json(rowToDocument(await writeRow(db,path,next,now,existing,true)));
    } catch (error) {
      const message=String(error?.message || '');
      if (message==='Document too large') return json({error:message},413);
      if (/Document changed|CHECK constraint failed: ok\s*=\s*1/i.test(message)) {
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

// Apply unchanged-data filters before expensive authorization; projected user,
// friend and group fields must still be filtered only after projection.
function prefilterRaw(docs,parent,filters) {
  const root=splitPath(parent)?.[0];
  if (!['posts','reels','chats','messages','notifications','friend_requests'].includes(root) || !Array.isArray(filters)) return docs;
  return docs.filter(d=>filters.every(f=>matchesFilter(d.data,f,d.id)));
}
async function visibleDocuments(db,uid,parent,docs,requestUserReads = new Map()) {
  const parts=splitPath(parent), nestedChat=parts?.length===3 && parts[0]==='chats' && parts[2]==='messages';
  // All comments/reactions in a nested collection share the same parent post.
  // Authorize that parent once per request, not once per child row.
  if (parts?.length===3 && ['posts','reels'].includes(parts[0]) && ['comments','reactions'].includes(parts[2])) {
    return await contentReadAllowed(db,uid,`${parts[0]}/${parts[1]}`) ? docs : [];
  }
  const allowed=new Array(docs.length).fill(false);
  let next=0;
  await Promise.all(Array.from({length:Math.min(8,docs.length)},async()=>{
    for (;;) {
      const i=next++; if (i>=docs.length) return;
      const doc=docs[i];
      allowed[i]=(nestedChat || await chatReadAllowed(db,doc.path,uid,doc.data)) && await contentReadAllowed(db,uid,doc.path,requestUserReads);
    }
  }));
  return docs.filter((_,i)=>allowed[i]);
}

// Feed fast path: fetch small indexed batches from each store, then authorize each
// candidate before filling the visible page. Never LIMIT before privacy filtering.
// Anything not provably equivalent falls back to the compatibility query engine.
async function boundedFeedPage(db,uid,spec) {
  if (spec?.parent!=='posts' || !Array.isArray(spec.orderBy) || spec.orderBy.length!==1
      || spec.orderBy[0]?.field!=='createdAt' || spec.orderBy[0]?.direction!=='DESCENDING'
      || (Array.isArray(spec.filters) && spec.filters.length) || spec.limitToLast || spec.startAt || spec.endAt || spec.endBefore
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
  const canonicalBad=await db.prepare(`SELECT 1 AS hit FROM friendbook_cf_firestore_documents WHERE parent_path='posts'
    AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR ${standard}) LIMIT 1`).bind().first();
  const legacyBad=await db.prepare(`SELECT 1 AS hit FROM friendbook_cf_documents WHERE collection='posts'
    AND (doc_id GLOB '*[^ -~]*' OR path<>'posts/'||doc_id OR json_type(document_json,'$.fields')='object' OR ${standard}) LIMIT 1`).bind().first();
  if (canonicalBad || legacyBad) return null;
  const rank=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN 2 ELSE 4 END`;
  const time=`CASE WHEN json_type(a.document_json,'$.createdAt') IN ('integer','real') THEN json_extract(a.document_json,'$.createdAt') ELSE json_extract(a.document_json,'$.createdAt.millis') END`;
  const chunk=Math.min(100,Math.max(20,spec.limit*2));
  const visible=[], requestUserReads=new Map();
  for (;;) {
    const bound=cursor?` AND (${rank},${time},a.doc_id)<(?,?,?)`:'';
    const args=[...(cursor||[]),chunk];
    const [canonical,legacy]=await Promise.all([
      db.prepare(`SELECT a.path,a.parent_path,a.collection_id,a.doc_id,a.owner_uid,a.document_json,a.create_time,a.update_time
        FROM friendbook_cf_firestore_documents a WHERE a.parent_path='posts'${bound}
        ORDER BY ${rank} DESC,${time} DESC,a.doc_id DESC LIMIT ?`).bind(...args).all(),
      db.prepare(`SELECT a.path,a.collection,a.doc_id,a.owner_uid,a.document_json,a.updated_at
        FROM friendbook_cf_documents a WHERE a.collection='posts'${bound}
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
        ORDER BY ${rank} DESC,${time} DESC,a.doc_id DESC LIMIT ?`).bind(...args).all(),
    ]);
    const rows=[...(canonical?.results || []),...(legacy?.results || []).map(appDocumentToFirestoreRow).filter(Boolean)];
    const docs=sortDocuments(rows.map(rowToDocument).filter(Boolean),spec.orderBy).slice(0,chunk);
    if (!docs.length) break;
    // The same checks used by the normal document query; privacy is never inferred
    // from the client-supplied author, a stale legacy mirror, or the SQL sort key.
    const allowed=await visibleDocuments(db,uid,'posts',docs,requestUserReads);
    visible.push(...allowed);
    if (visible.length>=spec.limit) return visible.slice(0,spec.limit);
    const last=docs[docs.length-1], value=last.data.createdAt;
    cursor=[typeof value==='number'?2:4,typeof value==='number'?value:value.millis,last.id];
    if (docs.length<chunk) break;
  }
  return visible;
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
async function acceptFriendBatch(db, uid, ops, now) {
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
  const existingPair = await db.prepare(`SELECT path FROM (
    SELECT path,document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'
    UNION ALL SELECT a.path,a.document_json FROM friendbook_cf_documents a WHERE a.collection='friends'
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
  ) d WHERE (json_array_length(document_json,'$.participants')=2 AND
    ((json_extract(document_json,'$.participants[0]')=? AND json_extract(document_json,'$.participants[1]')=?) OR
     (json_extract(document_json,'$.participants[0]')=? AND json_extract(document_json,'$.participants[1]')=?))) OR
    (json_array_length(document_json,'$.fields.participants.arrayValue.values')=2 AND
    ((json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?) OR
     (json_extract(document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?))) ORDER BY path LIMIT 1`).bind(uid,other,other,uid,uid,other,other,uid).first();
  const prior=existingPair ? await readRow(db,existingPair.path) : null;
  if (existingPair && (!prior || !friendshipReadView(uid,decodeValue(JSON.parse(prior.document_json)))?.participants.includes(other))) return json({error:'Friendship changed'},409);
  const snapshots=[reqRow,...userRows,...(prior?[prior]:[])];
  const guards=[];
  for (const row of snapshots) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();
    const legacy=canonical ? null : await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(row.path).first();
    if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:row.path,collection:row.collection_id,doc_id:row.doc_id,document_json:legacy.document_json})?.document_json!==row.document_json))) return json({error:'Request changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,row.path,row.document_json,canonical?1:0,row.path,row.path,legacy?.document_json || ''));
  }
  // Check both native and historical array envelopes, with canonical rows winning over mirrors.
  const duplicateSql=`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (
    SELECT 1 FROM (
      SELECT document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'
      UNION ALL SELECT a.document_json FROM friendbook_cf_documents a WHERE a.collection='friends'
        AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
    ) d WHERE (
      (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?)
       AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.participants') WHERE value=?))
      OR (EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?)
       AND EXISTS (SELECT 1 FROM json_each(d.document_json,'$.fields.participants.arrayValue.values') WHERE json_extract(value,'$.stringValue')=?))
    )) THEN 1 ELSE 0 END`;
  if (!prior) guards.push(db.prepare(duplicateSql).bind(uid,other,uid,other));
  // A create must not overwrite either table, even if the caller guesses an ID.
  guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=?) THEN 1 ELSE 0 END').bind(friendOp.path,friendOp.path));
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
  await db.batch([...guards,ledger,...writes,db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
  return json({ok:true,operations:ops.length});
}

// Remove all records for one pair and adjust both counters together. A caller may
// never supply another pair, an arbitrary counter delta, or a partial duplicate set.
async function unfriendBatch(db,uid,ops,now) {
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
  const guards=[];
  for (const row of [...friendRows,...userRows]) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();
    const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(row.path).first();
    if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:row.path,collection:row.collection_id,doc_id:row.doc_id,document_json:legacy.document_json})?.document_json!==row.document_json))) return json({error:'Friendship changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,row.path,row.document_json,canonical?1:0,row.path,row.path,legacy?.document_json||''));
  }
  // Refuse a partial removal if duplicate friendship rows exist. Test both native
  // JSON and historical Firestore envelopes, ignoring stale legacy mirrors.
  const countSql=`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT count(*) FROM (
    SELECT document_json FROM friendbook_cf_firestore_documents WHERE parent_path='friends'
    UNION ALL SELECT a.document_json FROM friendbook_cf_documents a WHERE a.collection='friends'
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents f WHERE f.path=a.path)
  ) d WHERE (
    (json_array_length(d.document_json,'$.participants')=2 AND
     ((json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?) OR
      (json_extract(d.document_json,'$.participants[0]')=? AND json_extract(d.document_json,'$.participants[1]')=?))) OR
    (json_array_length(d.document_json,'$.fields.participants.arrayValue.values')=2 AND
     ((json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?) OR
      (json_extract(d.document_json,'$.fields.participants.arrayValue.values[0].stringValue')=? AND json_extract(d.document_json,'$.fields.participants.arrayValue.values[1].stringValue')=?)))
  ))=? THEN 1 ELSE 0 END`;
  guards.push(db.prepare(countSql).bind(uid,other,other,uid,uid,other,other,uid,friendRows.length));
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
async function reviewPayment(db,env,uid,paymentPath,update,profileOp,now) {
  const forbidden=()=>json({error:'Forbidden'},403);
  if (!isPaymentAdmin(env,uid) || typeof db.batch!=='function' || !isDocumentPath(paymentPath) || splitPath(paymentPath)[0]!=='payments') return forbidden();
  const action=update?.status, prefix=action==='approved'?'approved':action==='rejected'?'rejected':'';
  if (!prefix || Object.keys(update).length!==3 || update[`${prefix}By`]!==uid
      || update[`${prefix}At`]?.__fieldValue!=='serverTimestamp' || Object.keys(update[`${prefix}At`]).length!==1) return forbidden();
  const payRow=await readRow(db,paymentPath);
  if (!payRow) return forbidden();
  const payment=decodeValue(JSON.parse(payRow.document_json));
  // Only free verification requests are eligible; no proof of paid transactions is inferred.
  if (!canCreateFreeReview(payment.userId,{...payment,status:'pending'}) || payment.status!=='pending' || !validSegment(payment.userId)) return forbidden();
  let profileRow=null, profile=null;
  if (action==='approved') {
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
    const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(row.path).first();
    if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:row.path,collection:row.collection_id,doc_id:row.doc_id,document_json:legacy.document_json})?.document_json!==row.document_json))) return json({error:'Review changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,row.path,row.document_json,canonical?1:0,row.path,row.path,legacy?.document_json||''));
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
  const stmts=[];
  if (beforeRow) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();
    const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(path).first();
    if ((canonical && canonical.document_json!==beforeRow.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path,collection:'groups',doc_id:id,document_json:legacy.document_json})?.document_json!==beforeRow.document_json))) return json({error:'Group changed'},409);
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ((? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?)) AND ((? IS NULL AND NOT EXISTS (SELECT 1 FROM friendbook_cf_trusted_groups WHERE group_path=?)) OR (? IS NOT NULL AND (SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?) = ?)) THEN 1 ELSE 0 END').bind(canonical?1:0,path,beforeRow.document_json,canonical?1:0,path,path,legacy?.document_json||'',owner,path,owner,path,owner));
  } else {
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path,path));
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
  const guards=[];
  for (const row of [groupRow,...(beforeRow?[beforeRow]:[])]) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();
    const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(row.path).first();
    if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:row.path,collection:row.collection_id,doc_id:row.doc_id,document_json:legacy.document_json})?.document_json!==row.document_json))) return json({error:'Group or post changed'},409);
    guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,row.path,row.document_json,canonical?1:0,row.path,row.path,legacy?.document_json||''));
  }
  guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (SELECT owner_uid FROM friendbook_cf_trusted_groups WHERE group_path=?)=? THEN 1 ELSE 0 END').bind(groupPath,owner));
  if (!beforeRow) guards.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path,path));
  const stmts=[...guards],coll=collectionIdOf(path),id=docIdOf(path);
  if (!after) {
    stmts.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
    stmts.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
  } else {
    const data=JSON.stringify(after), author=after.authorId;
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,coll,coll,id,author,data,beforeRow?.create_time||now,now));
    stmts.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,coll,id,author,JSON.stringify({...after,id}),now));
    stmts.push(db.prepare("INSERT INTO friendbook_cf_interaction_resources(resource,state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state='verified'").bind(path));
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
  const stmts=[];
  for (const row of rows) {
    const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(row.path).first();
    const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(row.path).first();
    if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:row.path,collection:row.collection_id,doc_id:row.doc_id,document_json:legacy.document_json})?.document_json!==row.document_json))) return json({error:'Follow state changed'},409);
    stmts.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,row.path,row.document_json,canonical?1:0,row.path,row.path,legacy?.document_json||''));
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
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();
  const legacy=!canonical && isRootDocument(path) ? await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(path).first() : null;
  if (!row) {
    if (canonical || legacy) throw new Error('Document changed');
    return db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=?) THEN 1 ELSE 0 END').bind(path,path);
  }
  if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path,collection:collectionIdOf(path),doc_id:docIdOf(path),document_json:legacy.document_json})?.document_json!==row.document_json))) throw new Error('Document changed');
  return db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,path,row.document_json,canonical?1:0,path,path,legacy?.document_json||'');
}
// Moderation authority comes exclusively from the configured verified UID list.
// Preserve the original document in a private audit record before changing it.
async function adminModeration(request,env,uid,now) {
  const db=env.DB;
  if (!isPaymentAdmin(env,uid)) return json({error:'Forbidden'},403);
  if (request.method!=='POST') return json({error:'Method not allowed'},405);
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  let input; try { input=await request.json(); } catch { return json({error:'Invalid JSON'},400); }
  if (!input || typeof input!=='object' || Array.isArray(input)
      || Object.keys(input).some(k=>!['action','collection','id'].includes(k))
      || !validSegment(input.id) || input.id.length>128) return json({error:'Invalid request'},400);
  const isBan=['ban_user','unban_user'].includes(input.action);
  if ((isBan && input.collection!=='users') || (!isBan && (input.action!=='delete_content'
      || !['posts','reels','stories'].includes(input.collection)))) return json({error:'Invalid request'},400);
  if (isBan && (uid===input.id || isPaymentAdmin(env,input.id))) return json({error:'Protected account'},403);
  const path=`${input.collection}/${input.id}`;
  try {
    const row=await readRow(db,path);
    if (!row) return json({error:'NOT_FOUND'},404);
    const before=decodeValue(JSON.parse(row.document_json));
    const guard=await chatDocumentGuard(db,path,row);
    const audit=db.prepare('INSERT INTO friendbook_cf_moderation_audit(id,admin_uid,action,target_path,original_json,created_at) VALUES (?,?,?,?,?,?)')
      .bind(crypto.randomUUID(),uid,input.action,path,row.document_json,now);
    const statements=[guard,audit];
    if (isBan) {
      const next={...before,banned:input.action==='ban_user'},serialized=JSON.stringify(next);
      statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`)
        .bind(path,'users','users',input.id,input.id,serialized,row.create_time||now,now));
      statements.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`)
        .bind(path,'users',input.id,input.id,JSON.stringify({...next,id:input.id}),now));
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

async function authorizeChat(db,path,uid,before,after,pendingRooms=new Map()) {
  const id=chatMessageRoom(path,before,after);
  if (id===null) return {allowed:await chatWriteAllowed(db,path,uid,before,after,pendingRooms), room:null};
  if (!id) return {allowed:false,room:null};
  const room=await readRow(db,`chats/${id}`);
  const snapshot=room ? decodeValue(JSON.parse(room.document_json)) : pendingRooms.get(id);
  return {allowed:await chatWriteAllowed(db,path,uid,before,after,pendingRooms,snapshot || null),room,roomPath:`chats/${id}`};
}
async function writeChatMessage(db,path,doc,now,existing,auth,uid,deleted=false) {
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  if (!auth.room) return json({error:'Forbidden'},403);
  const statements=[await chatDocumentGuard(db,auth.roomPath,auth.room),await chatDocumentGuard(db,path,existing)];
  const id=docIdOf(path), parent=parentPath(path), coll=collectionIdOf(path);
  if (deleted) {
    statements.push(db.prepare('DELETE FROM friendbook_cf_firestore_documents WHERE path=?').bind(path));
    if (isRootDocument(path)) statements.push(db.prepare('DELETE FROM friendbook_cf_documents WHERE path=?').bind(path));
  } else {
    const serialized=JSON.stringify(doc), owner=ownerOf(doc,existing?.owner_uid||uid), created=existing?.create_time||now;
    if (serialized.length>1_800_000) return json({error:'Document too large'},413);
    statements.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,parent,coll,id,owner,serialized,created,now));
    if (isRootDocument(path)) statements.push(db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,coll,id,owner,JSON.stringify({...doc,id}),now));
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
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE path=?)
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path=?)
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='messages' AND
        (substr(doc_id,1,?)=? OR json_extract(document_json,'$.chatId')=? OR json_extract(document_json,'$.fields.chatId.stringValue')=?))
      AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE collection='messages' AND
        (substr(doc_id,1,?)=? OR json_extract(document_json,'$.chatId')=? OR json_extract(document_json,'$.fields.chatId.stringValue')=?))
      THEN 1 ELSE 0 END`).bind(path,path,`${path}/messages`,id.length+1,id+'_',id,id,id.length+1,id+'_',id,id));
    return statements;
  }
  const canonical=await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(path).first();
  const legacy=canonical?null:await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(path).first();
  if ((canonical && canonical.document_json!==row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path,collection:'chats',doc_id:id,document_json:legacy.document_json})?.document_json!==row.document_json))) throw new Error('Room changed');
  statements.push(db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical?1:0,path,row.document_json,canonical?1:0,path,path,legacy?.document_json||''));
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

async function writeChatRoot(db,path,doc,now,row,uid) {
  if (typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
  const guards=await chatRootGuards(db,path,row), before=row?decodeValue(JSON.parse(row.document_json)):null;
  const ledger=await groupChatLedgerChanges(db,path,before,doc);
  const id=docIdOf(path), data=JSON.stringify(doc);
  if (data.length>1_800_000) return json({error:'Document too large'},413);
  const owner=ownerOf(doc,row?.owner_uid||uid), createTime=row?.create_time||now;
  await db.batch([...guards,...ledger.guards,...ledger.writes,
    db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,update_time=excluded.update_time`).bind(path,'chats','chats',id,owner,data,createTime,now),
    db.prepare(`INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid,document_json=excluded.document_json,updated_at=excluded.updated_at`).bind(path,'chats',id,owner,JSON.stringify({...doc,id}),now),
    db.prepare('DELETE FROM friendbook_cf_social_guards').bind()]);
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

export async function firestoreDocuments(request, env, identity, chatRetry=0) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const uid = identity?.uid;
  if (!validSegment(uid)) return json({ error: 'Unauthorized' }, 401);
  const url = new URL(request.url);
  const path = url.pathname;
  const db = env.DB;
  const now = Date.now();

  if (path === '/v1/admin/recover-social') return recoverLegacySocial(request,env,uid,now);
  if (path === '/v1/admin/moderation') return adminModeration(request,env,uid,now);

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
    if (parts[0] === 'reports' && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    if (parts?.length === 4 && parts[0] === 'chats' && parts[2] === 'messages'
        && !(await canReadChatMessages(db, parts[1], uid))) return json({ error: 'Forbidden' }, 403);
    let row;
    try { row = await readRow(db, docPath); }
    catch { return json({ error: 'Read unavailable' }, 503); }
    if (!row) return json({ exists: false, id: docIdOf(docPath), path: docPath, data: null });
    const document = rowToDocument(row);
    if (!(await contentReadAllowed(db, uid, docPath))) return json({error:'Forbidden'},403);
    if (parts[0] === 'friend_requests' && !canReadFriendRequest(uid, document.data)) return json({ error:'Forbidden' }, 403);
    if (parts[0] === 'payments' && !canReadPayment(env,uid,document.data)) return json({error:'Forbidden'},403);
    if (!canReadNotification(docPath, uid, document.data)) return json({ error: 'Forbidden' }, 403);
    if (!(await chatReadAllowed(db, docPath, uid, document.data))) return json({ error: 'Forbidden' }, 403);
    let view;
    try { view = parts[0] === 'groups' ? groupReadView(uid,document.data,await trustedGroupOwner(db,docPath)) : parts[0] === 'friends' ? friendshipReadView(uid, document.data) : profileReadView(docPath, uid, document.data, isPaymentAdmin(env,uid)); }
    catch { return json({error:'Read unavailable'},503); }
    if (view === null) return json({ error: 'Forbidden' }, 403);
    document.data = view;
    return json(document);
  }

  // PUT /v1/firestore/document?path=...  {data, merge, mergeFields}
  if (path === '/v1/firestore/document' && request.method === 'PUT') {
    const docPath = url.searchParams.get('path') || '';
    if (!isDocumentPath(docPath)) return json({ error: 'Invalid path' }, 400);
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friend_requests','friends','payments','reports'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return json({ error: 'Invalid body' }, 400);
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
          await db.batch([
            await chatDocumentGuard(db,docPath,null),
            db.prepare('INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)').bind(docPath,'notifications','notifications',id,uid,serialized,now,now),
            db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,'notifications',id,uid,JSON.stringify({...next,id}),now),
            db.prepare('DELETE FROM friendbook_cf_social_guards').bind()
          ]);
          return json({exists:true,id,path:docPath,parent:'notifications',collection:'notifications',data:next,createTime:now,updateTime:now});
        } catch(e) {
          if (/constraint|document changed/i.test(String(e?.message||''))) return json({error:'ALREADY_EXISTS'},409);
          return json({error:'Write unavailable'},503);
        }
      }
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,existing,chatAuth,uid);
      if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='chats') return await writeChatRoot(db,docPath,next,now,existing,uid);
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
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friend_requests','friends','reports'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return json({error:'Invalid body'},400);
    if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='users')
      return await mutateProfile(db,uid,docPath,body,'PATCH',now);
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
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,existing,chatAuth,uid);
      if (splitPath(docPath).length===2 && splitPath(docPath)[0]==='chats') return await writeChatRoot(db,docPath,next,now,existing,uid);
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
    if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);
    if (['friends','payments'].includes(splitPath(docPath)[0])) return json({error:'Forbidden'},403);
    if (splitPath(docPath)[0] === 'reports') {
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
        const canonical = await db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?').bind(docPath).first();
        const legacy = canonical ? null : await db.prepare('SELECT document_json FROM friendbook_cf_documents WHERE path=?').bind(docPath).first();
        // Verify the exact authorized snapshot, including the absence of a canonical row
        // when authorization used a historical mirror.
        if ((canonical && canonical.document_json !== row.document_json) || (!canonical && (!legacy || appDocumentToFirestoreRow({path:docPath,collection:'friend_requests',doc_id:docIdOf(docPath),document_json:legacy.document_json})?.document_json !== row.document_json))) return json({error:'Request changed'},409);
        await db.batch([
          db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN (? = 1 AND (SELECT document_json FROM friendbook_cf_firestore_documents WHERE path=?) = ?) OR (? = 0 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?) AND (SELECT document_json FROM friendbook_cf_documents WHERE path=?) = ?) THEN 1 ELSE 0 END').bind(canonical ? 1:0,docPath,row.document_json,canonical ? 1:0,docPath,docPath,legacy?.document_json || ''),
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
    if (['notifications','users','pushDevices','chats','messages','posts','reels'].includes(splitPath(docPath)?.[0])) {
      let existing;
      try { existing = await readRow(db, docPath); }
      catch { return json({ error: 'Read unavailable' }, 503); }
      const before=existing ? decodeValue(JSON.parse(existing.document_json)) : null;
      const chatAuth=await authorizeChat(db,docPath,uid,before,null);
      if (!existing || !notificationWriteAllowed(docPath, uid, before, null) || !profileWriteAllowed(docPath, uid, before, null) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, before, null)) return json({ error: 'Forbidden' }, 403);
      if (chatAuth.roomPath) { try { return await writeChatMessage(db,docPath,null,now,existing,chatAuth,uid,true); } catch { return json({error:'Message changed or unavailable'},503); } }
      if (splitPath(docPath)[0]==='chats' && splitPath(docPath).length===2) { try { return await deleteChatRoot(db,docPath,existing); } catch { return json({error:'Group changed or unavailable'},503); } }
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
    if (parent === 'friends') return json({error:'Forbidden'},403);
    const id = autoId();
    const docPath = `${parent}/${id}`;
    try {
      const next = applySet(null, body?.data || {}, false, null, now);
      if (parent === 'reports') {
        const report=reportForCreate(uid,next,now);
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
        // The existence check and writes must share a D1 transaction. A client-side
        // read-before-create alone allows two devices to create the same request.
        const guard=db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN
          NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE parent_path='friend_requests'
            AND COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))='pending')
          AND NOT EXISTS (SELECT 1 FROM friendbook_cf_documents WHERE collection='friend_requests'
            AND COALESCE(json_extract(document_json,'$.fromUid'),json_extract(document_json,'$.fields.fromUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.toUid'),json_extract(document_json,'$.fields.toUid.stringValue'))=?
            AND COALESCE(json_extract(document_json,'$.status'),json_extract(document_json,'$.fields.status.stringValue'))='pending')
          THEN 1 ELSE 0 END`).bind(uid,next.toUid,uid,next.toUid);
        try {
          await db.batch([
            guard,
            db.prepare('INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time) VALUES (?,?,?,?,?,?,?,?)').bind(docPath,parent,parent,id,uid,serialized,now,now),
            db.prepare('INSERT INTO friendbook_cf_documents(path,collection,doc_id,owner_uid,document_json,updated_at) VALUES (?,?,?,?,?,?)').bind(docPath,parent,id,uid,JSON.stringify({...next,id}),now),
            db.prepare('DELETE FROM friendbook_cf_social_guards').bind(),
          ]);
        } catch (error) {
          if (/CHECK constraint failed: ok\s*=\s*1/i.test(String(error?.message||''))) return json({error:'Request already pending'},409);
          throw error;
        }
        return json({exists:true,id,path:docPath,parent,collection:parent,data:next,createTime:now,updateTime:now},201);
      }

      const chatAuth=await authorizeChat(db,docPath,uid,null,next);
      if (!notificationWriteAllowed(docPath, uid, null, next) || !profileWriteAllowed(docPath, uid, null, next) || !chatAuth.allowed || !contentWriteAllowed(docPath, uid, null, next)) return json({ error: 'Forbidden' }, 403);
      if (chatAuth.roomPath) return await writeChatMessage(db,docPath,next,now,null,chatAuth,uid);
      if (parent === 'chats') return await writeChatRoot(db,docPath,next,now,null,uid);
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
    if (!supportedCollectionPath(parent)) return json({error:'Forbidden'},403);
    if (parent === 'reports' && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    const parts = splitPath(parent);
    if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages'
        && !(await canReadChatMessages(db, parts[1], uid))) return json({ error: 'Forbidden' }, 403);
    let rows;
    try {
      const page=await boundedFeedPage(db,uid,body);
      if (page!==null) return json({parent,documents:page});
      rows = await readCollectionRows(db, parent, uid, body?.filters, body);
    }
    catch { return json({ error: 'Read unavailable' }, 503); }
    let docs;
    try {
      docs = (await Promise.all(prefilterRaw(rows.map(rowToDocument).filter(doc => doc && canReadNotification(doc.path, uid, doc.data) && (parent !== 'friend_requests' || canReadFriendRequest(uid,doc.data)) && (parent !== 'payments' || canReadPayment(env,uid,doc.data))),parent,body?.filters).map(async doc => {
        const view = parent === 'groups' ? groupReadView(uid,doc.data,await trustedGroupOwner(db,doc.path)) : parent === 'friends' ? friendshipReadView(uid,doc.data) : profileReadView(doc.path, uid, doc.data, isPaymentAdmin(env,uid));
        return view === null ? null : { ...doc, data: view };
      }))).filter(Boolean);
    } catch { return json({error:'Read unavailable'},503); }
    let visible;
    try { visible=await visibleDocuments(db,uid,parent,docs); }
    catch { return json({error:'Read unavailable'},503); }
    const result = applyQuery(visible, body || {});
    return json({ parent, documents: result });
  }

  // GET /v1/firestore/count?parent=...&filters=...
  if (path === '/v1/firestore/count' && request.method === 'GET') {
    const parent = url.searchParams.get('parent') || '';
    if (!isCollectionPath(parent)) return json({ error: 'Invalid parent' }, 400);
    if (!supportedCollectionPath(parent)) return json({error:'Forbidden'},403);
    if (parent === 'reports' && !canReadReports(env,uid)) return json({error:'Forbidden'},403);
    const parts = splitPath(parent);
    if (parts?.length === 3 && parts[0] === 'chats' && parts[2] === 'messages'
        && !(await canReadChatMessages(db, parts[1], uid))) return json({ error: 'Forbidden' }, 403);
    let filters = [];
    try { filters = JSON.parse(url.searchParams.get('filters') || '[]'); } catch { filters = []; }
    let rows;
    try { rows = await readCollectionRows(db, parent, uid, filters); }
    catch { return json({ error: 'Read unavailable' }, 503); }
    let docs;
    try {
      docs = (await Promise.all(prefilterRaw(rows.map(rowToDocument).filter(doc => doc && canReadNotification(doc.path, uid, doc.data) && (parent !== 'friend_requests' || canReadFriendRequest(uid,doc.data)) && (parent !== 'payments' || canReadPayment(env,uid,doc.data))),parent,filters).map(async doc => {
        const view = parent === 'groups' ? groupReadView(uid,doc.data,await trustedGroupOwner(db,doc.path)) : parent === 'friends' ? friendshipReadView(uid,doc.data) : profileReadView(doc.path, uid, doc.data, isPaymentAdmin(env,uid));
        return view === null ? null : { ...doc, data: view };
      }))).filter(Boolean);
    } catch { return json({error:'Read unavailable'},503); }
    let visible;
    try { visible=await visibleDocuments(db,uid,parent,docs); }
    catch { return json({error:'Read unavailable'},503); }
    const filtered = visible.filter(d => (Array.isArray(filters) ? filters : []).every(f => matchesFilter(d.data, f, d.id)));
    return json({ parent, count: filtered.length });
  }

  // POST /v1/firestore/batch  {operations:[...]}  (also used for transactions)
  if ((path === '/v1/firestore/batch' || path === '/v1/firestore/transaction') && request.method === 'POST') {
    let body;
    try { body = await request.json(); } catch { return json({ error: 'Invalid JSON' }, 400); }
    const ops = Array.isArray(body?.operations) ? body.operations : [];
    if (ops.length === 0 || ops.length > 500) return json({ error: 'Invalid operations' }, 400);
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
        return (friendOp.op || friendOp.type) === 'delete' ? await unfriendBatch(db,uid,ops,now) : await acceptFriendBatch(db,uid,ops,now);
      }
      catch { return json({error:'Friendship changed or unavailable'},409); }
    }
    // Pre-read existing docs so FieldValue transforms resolve against current state.
    const cache = new Map();
    for (const op of ops) {
      if (op?.path && !cache.has(op.path)) {
        try { cache.set(op.path, await readRow(db, op.path)); }
        catch { return json({ error: 'Read unavailable' }, 503); }
      }
    }
    // Compute final docs in memory, then write atomically via D1 batch.
    const finalDocs = new Map(); // path -> {doc|null(deleted), existingRow}
    const order = [];
    for (const op of ops) {
      const p = op.path;
      if (!isDocumentPath(p)) return json({ error: 'Invalid path' }, 400);
      if (!supportedDocumentPath(p) || ['friend_requests','friends','payments','groups','reports'].includes(splitPath(p)[0])) return json({error:'Forbidden'},403);
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
      const chatAuth=await authorizeChat(db,p,uid,before,entry.deleted ? null : entry.doc,pendingRooms);
      chatAuths.set(p,chatAuth);
      if (!notificationWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc) || !profileWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc) || !chatAuth.allowed || !contentWriteAllowed(p, uid, before, entry.deleted ? null : entry.doc)) return json({ error: 'Forbidden' }, 403);
    }
    try {
      const statements = [];
      const roomPaths=order.filter(p=>splitPath(p).length===2 && splitPath(p)[0]==='chats');
      const messagePaths=order.filter(p=>chatAuths.get(p).roomPath);
      const guardedPaths=order.filter(p=>splitPath(p).length===2 && ['users','posts','reels'].includes(splitPath(p)[0]));
      if ((roomPaths.length || messagePaths.length || guardedPaths.length) && typeof db.batch!=='function') return json({error:'Atomic storage unavailable'},503);
      for (const p of guardedPaths) statements.push(await chatDocumentGuard(db,p,finalDocs.get(p).existingRow));
      for (const roomPath of roomPaths) {
        const entry=finalDocs.get(roomPath), before=entry.existingRow?decodeValue(JSON.parse(entry.existingRow.document_json)):null;
        statements.push(...await chatRootGuards(db,roomPath,entry.existingRow));
        const ledger=await groupChatLedgerChanges(db,roomPath,before,entry.deleted?null:entry.doc);
        statements.push(...ledger.guards,...ledger.writes);
      }
      for (const p of messagePaths) {
        const auth=chatAuths.get(p);
        if (auth.room) statements.push(await chatDocumentGuard(db,auth.roomPath,auth.room));
        else if (!pendingRooms.has(splitPath(auth.roomPath)[1])) return json({error:'Forbidden'},403);
        statements.push(await chatDocumentGuard(db,p,finalDocs.get(p).existingRow));
      }
      for (const p of order) {
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
            `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT(path) DO UPDATE SET owner_uid = excluded.owner_uid, document_json = excluded.document_json, update_time = excluded.update_time`
          ).bind(p, parent, coll, id, owner, serialized, createTime, now));
          if (isRootDocument(p) && id && coll) {
            const appDoc = JSON.stringify({ ...entry.doc, id });
            const appOwner = owner || (coll === 'users' ? id : '');
            statements.push(db.prepare(
              `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
               VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid, document_json=excluded.document_json, updated_at=excluded.updated_at`
            ).bind(p, coll, id, appOwner, appDoc, now));
          }
        }
      }
      if (roomPaths.length || messagePaths.length || guardedPaths.length) statements.push(db.prepare('DELETE FROM friendbook_cf_social_guards').bind());
      if (typeof db.batch === 'function' && statements.length) {
        await db.batch(statements);
      } else {
        for (const s of statements) await s.run();
      }
      return json({ ok: true, operations: ops.length });
    } catch (error) {
      // Retry only a definitely rolled-back optimistic guard conflict, and only the
      // two-operation chat send shape. Re-run all reads, transforms and ACL checks;
      // never retry ambiguous network/storage failures or a potentially committed batch.
      const messages=ops.filter(op => splitPath(op.path)?.length===4 && splitPath(op.path)?.[0]==='chats' && splitPath(op.path)?.[2]==='messages' && (op.op||op.type)==='set');
      const rooms=ops.filter(op => splitPath(op.path)?.length===2 && splitPath(op.path)?.[0]==='chats' && (op.op||op.type)==='set');
      const guardConflict=/CHECK constraint failed: ok\s*=\s*1|^Room changed$|^Document changed$/i.test(String(error?.message||''));
      if (chatRetry<2 && guardConflict && ops.length===2 && messages.length===1 && rooms.length===1
          && parentPath(parentPath(messages[0].path))===rooms[0].path) {
        const replay=new Request(request.url,{method:'POST',headers:request.headers,body:JSON.stringify(body)});
        return firestoreDocuments(replay,env,identity,chatRetry+1);
      }
      return json({ error: 'Batch unavailable' }, 503);
    }
  }

  return json({ error: 'Not found' }, 404);
}

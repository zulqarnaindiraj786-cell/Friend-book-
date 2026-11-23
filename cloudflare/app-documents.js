import { canReadPayment, isPaymentAdmin } from './payment-access.js';
import { canReadFriendRequest, friendshipReadView, groupReadView, trustedGroupOwner } from './social-access.js';
import { supportedDocumentPath, supportedCollectionPath, contentReadAllowed, contentWriteAllowed } from './document-policy.js';
import { chatReadAllowed, chatWriteAllowed, storedChatMember } from './chat-access.js';
import { profileReadView, profileWriteAllowed } from './profile-access.js';
// Cloudflare D1 App Document Store (/v1/docs) + D1-first ACL helpers.
import { canReadNotification, notificationWriteAllowed } from './notification-access.js';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const ALLOWED_COLLECTIONS = new Set([
  'users', 'posts', 'reels', 'stories', 'chats', 'messages',
  'friends', 'friend_requests', 'follows', 'groups', 'pages',
  'notifications', 'official_broadcasts', 'lives', 'pokes', 'payments',
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

// Request-local only: never keep authorization state between HTTP requests.
export function requestAccountReads(identity) {
  const reads=new Map();
  if(identity?.viewerProfile!==undefined) reads.set(`users/${identity.uid}`,toFirestoreFields(identity.viewerProfile));
  return reads;
}

export function readPreferredDocumentJson(db, path) {
  // Historical records were backfilled: every lookup uses one canonical index.
  return db.prepare('SELECT document_json FROM friendbook_cf_firestore_documents WHERE path = ?1 LIMIT 1').bind(path).first();
}

export async function readD1DocumentFields(db, path) {
  if (!db) return null;
  // Authorization and privacy fields must reflect the current D1 row, not a
  // stale per-isolate cache after another device changes a block or ban.
  // Only canonical records are readable. A failed read is not a
  // missing record: treating it as missing can turn a transient outage into a
  // successful empty feed, overwriting good cached data on every client.
  const row = await readPreferredDocumentJson(db,path);
  if (!row) return null;
  const fields=toFirestoreFields(row.document_json);
  if (!fields) throw new Error('Invalid stored document');
  return fields;
}

// Only server-verified friendship transitions can authorize private content.
// Legacy records are not trusted merely because their document ID looks correct.
export async function hasD1Friendship(db, uidA, uidB) {
  if (!db || !validSegment(uidA) || !validSegment(uidB) || uidA===uidB) return null;
  const [low,high]=[uidA,uidB].sort();
  const row=await db.prepare('SELECT friend_path FROM friendbook_cf_trusted_friendships WHERE uid_low=? AND uid_high=?').bind(low,high).first();
  return row ? true : null;
}

async function canonicalRoot(db, path, fallback) {
  const row = await db.prepare('SELECT owner_uid, document_json FROM friendbook_cf_firestore_documents WHERE path = ?').bind(path).first();
  return row || fallback;
}

export function saveProfileProjection(db, uid, displayName, avatarUrl) {
  return db.prepare(`INSERT INTO friendbook_cf_profiles(uid,display_name,cloudinary_avatar_url) VALUES (?,?,?)
    ON CONFLICT(uid) DO UPDATE SET display_name=excluded.display_name, cloudinary_avatar_url=excluded.cloudinary_avatar_url
    WHERE friendbook_cf_profiles.display_name IS NOT excluded.display_name
       OR friendbook_cf_profiles.cloudinary_avatar_url IS NOT excluded.cloudinary_avatar_url`).bind(uid,displayName,avatarUrl).run();
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
    if (!supportedCollectionPath(collection)) return json({error:'Forbidden'},403);
    const rawLimit = Number(url.searchParams.get('limit') || '20');
    const limit = Number.isFinite(rawLimit) ? Math.max(1, Math.min(50, Math.trunc(rawLimit))) : 20;
    // Membership and privacy may change through another isolate or API: reauthorize every read.
    try {
      // Chat previews and unread counters can advance without rewriting the
      // historical room mirror. Sort current rooms by their canonical timestamp.
      const joinedRoot=['users','groups','friends','friend_requests','notifications'].includes(collection);
      const rows = collection==='chats' ? await env.DB.prepare(`
        SELECT path,doc_id,document_json,update_time AS updated_at,1 AS canonical FROM friendbook_cf_firestore_documents
          WHERE collection_id='chats' AND parent_path='chats'
        ORDER BY updated_at DESC LIMIT ?`).bind(limit).all() : await env.DB.prepare(`
        SELECT path,doc_id,document_json,update_time AS updated_at,1 AS canonical FROM friendbook_cf_firestore_documents
          WHERE parent_path=?1 ORDER BY updated_at DESC LIMIT ?2`).bind(collection,limit).all();
      let documents = [];
      const requestRoomReads = new Map();
      for (const row of rows?.results || []) {
        const docPath = `${collection}/${row.doc_id}`;
        const canonicalSnapshot = row.canonical===1 && row.path===docPath && validSegment(row.doc_id);
        const currentChat = collection==='chats' && canonicalSnapshot;
        // The list query already loaded this exact canonical root. Avoid an
        // additional lookup per row; content ACLs below still run unchanged.
        const current = canonicalSnapshot || joinedRoot ? row : await canonicalRoot(env.DB, docPath, row);
        const doc = toPlainDocument(current.document_json, row.doc_id);
        if (doc && (collection !== 'payments' || canReadPayment(env,uid,doc)) && (collection !== 'friend_requests' || canReadFriendRequest(uid,doc)) && canReadNotification(`${collection}/${row.doc_id}`, uid, doc)) {
          const view = collection === 'groups' ? groupReadView(uid,doc,await trustedGroupOwner(env.DB,`${collection}/${row.doc_id}`)) : collection === 'friends' ? friendshipReadView(uid,doc) : profileReadView(`${collection}/${row.doc_id}`, uid, doc, isPaymentAdmin(env,uid));
          if (view !== null && (currentChat ? storedChatMember(current.document_json,uid) : await chatReadAllowed(env.DB,docPath,uid,doc,requestRoomReads))) documents.push(view);
        }
      }
      if (['posts','reels','stories','lives'].includes(collection)) {
        const userReads = requestAccountReads(identity);
        const allowed = await Promise.all(documents.map(doc => contentReadAllowed(env.DB,uid,`${collection}/${doc.id}`,userReads)));
        documents = documents.filter((_doc, index) => allowed[index]);
      }
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
  if (!supportedDocumentPath(docPath)) return json({error:'Forbidden'},403);

  if (request.method === 'GET') {
    try {
      const current = await readPreferredDocumentJson(env.DB,docPath);
      if (!current) return json({ error: 'Not found' }, 404);
      const document = toPlainDocument(current.document_json, docId);
      if (!(await contentReadAllowed(env.DB, uid, docPath,requestAccountReads(identity)))) return json({error:'Forbidden'},403);
      if (collection === 'payments' && !canReadPayment(env,uid,document)) return json({error:'Forbidden'},403);
      if (collection === 'friend_requests' && !canReadFriendRequest(uid,document)) return json({error:'Forbidden'},403);
      if (!canReadNotification(docPath, uid, document)) return json({ error: 'Forbidden' }, 403);
      if (!(collection==='chats' ? storedChatMember(current.document_json,uid) : await chatReadAllowed(env.DB, docPath, uid, document))) return json({ error: 'Forbidden' }, 403);
      const view = collection === 'groups' ? groupReadView(uid,document,await trustedGroupOwner(env.DB,docPath)) : collection === 'friends' ? friendshipReadView(uid,document) : profileReadView(docPath, uid, document, isPaymentAdmin(env,uid));
      if (view === null) return json({ error: 'Forbidden' }, 403);
      return json({ id: docId, document: view });
    } catch {
      return json({ error: 'Document read unavailable' }, 503);
    }
  }

  if (request.method === 'PUT') {
    // Chat root writes use the transactional Firestore-compatible route; this
    // older merge API cannot provide the necessary concurrent-creation guard.
    if (['friend_requests','friends','payments','groups','chats','messages','stories'].includes(collection)) return json({error:'Forbidden'},403);
    let incoming;
    try { incoming = await request.json(); }
    catch { return json({ error: 'Invalid JSON payload' }, 400); }
    if (!incoming || typeof incoming !== 'object' || Array.isArray(incoming)) {
      return json({ error: 'Document object required' }, 400);
    }
    const now = Date.now();
    if (['users','posts','reels','events','pages','lives'].includes(collection)) {
      // Older clients use /v1/docs. Share the guarded canonical mutation with
      // newer clients so concurrent edits and relationship changes are not lost.
      // The guarded canonical route owns update_time. Injecting a new JSON
      // updatedAt here turned identical legacy-client saves into real writes,
      // defeating its existing no-op/mirror-repair checks.
      const data={...incoming};
      delete data.id;
      const {firestoreDocuments}=await import('./firestore-documents.js');
      const forwarded=new Request(`${url.origin}/v1/firestore/document?path=${encodeURIComponent(docPath)}`,{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({data,merge:true}),
      });
      const result=await firestoreDocuments(forwarded,env,identity);
      if (!result.ok) return result;
      const saved=await result.json();
      const profile=saved.data || {};
      // This separate projection is only a convenience for /v1/me; failure must
      // never turn an already committed canonical profile update into a retry.
      if (collection==='users') try {
        await saveProfileProjection(env.DB,uid,typeof profile.displayName==='string' ? profile.displayName.slice(0,200) : '',
          typeof profile.photoUrl==='string' ? profile.photoUrl.slice(0,2048) : null);
      } catch { /* Canonical profile remains authoritative. */ }
      return json({id:docId,path:docPath,document:{...profile,id:docId}});
    }
    try {
      const existingRow = await env.DB.prepare(
        'SELECT owner_uid, document_json FROM friendbook_cf_firestore_documents WHERE path = ?'
      ).bind(docPath).first();
      const currentRow = await canonicalRoot(env.DB, docPath, existingRow);
      const existingPlain = currentRow ? (toPlainDocument(currentRow.document_json, docId) || {}) : {};
      const merged = { ...existingPlain, ...incoming, id: docId };
      if (!notificationWriteAllowed(docPath, uid, currentRow ? existingPlain : null, merged) || !profileWriteAllowed(docPath, uid, currentRow ? existingPlain : null, merged) || !(await chatWriteAllowed(env.DB, docPath, uid, currentRow ? existingPlain : null, merged)) || !contentWriteAllowed(docPath, uid, currentRow ? existingPlain : null, merged)) return json({ error: 'Forbidden' }, 403);
      if (!merged.createdAt) merged.createdAt = now;
      merged.updatedAt = now;
      const ownerUid = String(merged.authorId || merged.uid || merged.senderId || currentRow?.owner_uid || uid);
      const serialized = JSON.stringify(merged);
      if (serialized.length > 95_000) return json({ error: 'Document too large' }, 413);
      const appWrite = env.DB.prepare(
        `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?)
         ON CONFLICT(path) DO UPDATE SET
           owner_uid = excluded.owner_uid,
           document_json = excluded.document_json,
           updated_at = excluded.updated_at`
      ).bind(docPath, collection, docId, ownerUid, serialized, now);
      const firestoreDoc = { ...merged };
      delete firestoreDoc.id;
      const firestoreWrite = env.DB.prepare(
        `INSERT INTO friendbook_cf_firestore_documents(path, parent_path, collection_id, doc_id, owner_uid, document_json, create_time, update_time)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(path) DO UPDATE SET owner_uid=excluded.owner_uid, document_json=excluded.document_json, update_time=excluded.update_time`
      ).bind(docPath, collection, collection, docId, ownerUid, JSON.stringify(firestoreDoc),
        Number(firestoreDoc.createdAt) || now, now);
      if (typeof env.DB.batch === 'function') await env.DB.batch([appWrite, firestoreWrite]);
      else { await appWrite.run(); await firestoreWrite.run(); }
      if (collection === 'posts' || collection === 'reels') {
        await env.DB.prepare(
          "INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified') ON CONFLICT(resource) DO UPDATE SET state = 'verified'"
        ).bind(docPath).run();
      }
      if (collection === 'users' && docId === uid) {
        const displayName = typeof merged.displayName === 'string' ? merged.displayName.slice(0, 200) : '';
        const avatarUrl = typeof merged.photoUrl === 'string' ? merged.photoUrl.slice(0, 2048) : null;
        await saveProfileProjection(env.DB,uid,displayName,avatarUrl);
      }
      return json({ id: docId, path: docPath, document: merged });
    } catch {
      return json({ error: 'Document write unavailable' }, 503);
    }
  }

  return json({ error: 'Method not allowed' }, 405);
}

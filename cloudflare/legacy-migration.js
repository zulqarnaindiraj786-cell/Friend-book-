import { fromFirestoreFields } from './app-documents.js';

/**
 * The update bridge, run where the data lives.
 *
 * A user who installs a newer build has years of their own documents in the legacy Firebase store.
 * The phone no longer ships that store's SDK - Firebase is authentication and nothing else - so the
 * carry-over cannot happen on the device. It happens here instead: the app posts one request, the
 * Worker reads that user's own slices from the legacy database *with that user's own identity token*,
 * and writes them into D1.
 *
 * Using the caller's token is the point. The Worker holds no service account and no admin key, so it
 * can only ever read what this person was already allowed to read under the legacy rules. A migration
 * that could see somebody else's documents would be a bigger hole than the one it closes.
 *
 * Three rules hold the whole thing in place:
 *  - ADDITIVE ONLY. Every write is `ON CONFLICT(path) DO NOTHING`. A document that is already in D1 -
 *    because the user has been writing to it since they updated - is left exactly as it is. Nothing
 *    here overwrites newer data with older data, and nothing here deletes: not in D1, and not in the
 *    legacy store, which is never sent a write of any kind.
 *  - BOUNDED. Page size, pages per slice, chats, messages per chat and a total document budget are all
 *    fixed below, so one request can never turn into an unbounded crawl on somebody's free tier.
 *  - HONEST. A slice the legacy store refuses is reported in `errors` and the answer says `ok:false`,
 *    rather than claiming a migration that only half happened.
 */

const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

export const MIGRATION_LIMITS = {
  pageSize: 100,        // one legacy page
  maxPages: 3,          // 300 documents is the most any single slice carries over
  chats: 50,            // conversations, newest first
  messagesPerChat: 50,  // the tail of each conversation, which is what a thread shows on open
  maxDocuments: 1000,   // total budget for one run
};

/**
 * The user's own slices. Each is a single-field filter, which the legacy database indexes on its own,
 * so no composite index has to exist for this to work. Ordering is deliberately not requested: an
 * orderBy would demand an index the project may never have created, and a migration does not care
 * what order the rows arrive in.
 */
export const SLICES = [
  { collection: 'posts', field: 'authorId', op: 'EQUAL' },
  { collection: 'reels', field: 'authorId', op: 'EQUAL' },
  { collection: 'stories', field: 'authorId', op: 'EQUAL' },
  { collection: 'comments', field: 'authorId', op: 'EQUAL' },
  { collection: 'notifications', field: 'toUid', op: 'EQUAL' },
  { collection: 'friend_requests', field: 'fromUid', op: 'EQUAL' },
  { collection: 'friend_requests', field: 'toUid', op: 'EQUAL' },
  { collection: 'friends', field: 'participants', op: 'ARRAY_CONTAINS' },
  { collection: 'chats', field: 'participants', op: 'ARRAY_CONTAINS', limit: 'chats', nested: 'messages' },
];

function validSegment(value) {
  return typeof value === 'string' && /^[A-Za-z0-9_.:@+~-]{1,128}$/.test(value);
}

/** Whose document this is, for the owner column the access rules read. */
function ownerOf(doc, uid) {
  for (const key of ['ownerUid', 'ownerId', 'authorId', 'userId', 'uid', 'senderId', 'fromUid', 'hostId', 'adminId']) {
    if (typeof doc[key] === 'string' && doc[key]) return doc[key];
  }
  return uid;
}

/** Legacy timestamps arrive as RFC 3339 text; D1 stores milliseconds. */
function millis(value) {
  if (!value || typeof value !== 'string') return 0;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? ms : 0;
}

export async function legacyMigration(request, env, identity, fetcher = fetch, now = Date.now) {
  if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  if (env.LEGACY_MIGRATION_ENABLED === 'false') return json({ error: 'Migration disabled' }, 403);
  const uid = identity?.uid;
  const token = identity?.token;
  if (!validSegment(uid) || typeof token !== 'string' || !token.length) {
    return json({ error: 'Unauthorized' }, 401);
  }
  const project = env.FIREBASE_PROJECT_ID;
  if (!project) return json({ error: 'Service unavailable' }, 503);

  const root = `https://firestore.googleapis.com/v1/projects/${encodeURIComponent(project)}/databases/(default)/documents`;
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };

  const migrated = {};
  const present = {};
  const scanned = {};
  const errors = [];
  let budget = MIGRATION_LIMITS.maxDocuments;

  /** One page of one slice. Reads only - the legacy store is never written to or deleted from. */
  async function runQuery(parent, slice, pageToken) {
    const structuredQuery = { from: [{ collectionId: slice.collection }] };
    if (slice.field) {
      // Every filter this migration runs compares against the caller's own uid, which is the only
      // value the legacy rules let this token read at scale.
      structuredQuery.where = {
        fieldFilter: { field: { fieldPath: slice.field }, op: slice.op, value: { stringValue: uid } },
      };
    }
    const limit = slice.limit ? MIGRATION_LIMITS[slice.limit] : MIGRATION_LIMITS.pageSize * MIGRATION_LIMITS.maxPages;
    structuredQuery.limit = Math.min(limit, budget);
    const body = { structuredQuery, pageSize: MIGRATION_LIMITS.pageSize };
    if (pageToken) body.pageToken = pageToken;
    const endpoint = `${root}/${parent ? `${parent}/` : ''}${slice.collection}:runQuery`;
    const answer = await fetcher(endpoint, { method: 'POST', headers, body: JSON.stringify(body) });
    if (!answer.ok) {
      const text = (await answer.text().catch(() => '')) || '';
      throw new Error(`legacy query refused (${answer.status})${text ? `: ${text.slice(0, 160)}` : ''}`);
    }
    const payload = await answer.json().catch(() => []);
    return Array.isArray(payload) ? payload : [];
  }

  async function readDocument(path) {
    const answer = await fetcher(`${root}/${path}`, { method: 'GET', headers });
    if (!answer.ok) {
      if (answer.status === 404 || answer.status === 403) return null;
      throw new Error(`legacy read refused (${answer.status})`);
    }
    return answer.json().catch(() => null);
  }

  /**
   * Stores one carried-over document. `DO NOTHING` is the whole safety story: a row that already
   * exists - written by the app since the user updated, or by an earlier migration run - wins, and the
   * caller is told it was already present rather than silently replaced.
   */
  async function store(collection, docId, doc) {
    if (!validSegment(docId) || budget <= 0) return 'skipped';
    const path = `${collection}/${docId}`;
    const createdAt = doc.createdAt || doc.date || doc.dateMs || now();
    const stored = { ...doc, id: docId, createdAt, updatedAt: doc.updatedAt || createdAt };
    const serialized = JSON.stringify(stored);
    if (serialized.length > 95_000) return 'skipped';
    const result = await env.DB.prepare(
      `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(path) DO NOTHING`
    ).bind(path, collection, docId, ownerOf(stored, uid), serialized, Number(stored.updatedAt) || now()).run();
    const inserted = Number(result?.meta?.changes ?? result?.changes ?? 0);
    budget -= 1;
    return inserted > 0 ? 'migrated' : 'present';
  }

  function bump(bucket, collection) {
    bucket[collection] = (bucket[collection] || 0) + 1;
  }

  /** One slice, page by page, until the pages run out or the budget does. */
  async function carry(slice, parent = '') {
    const key = parent ? `${parent}/${slice.collection}` : slice.collection;
    let pageToken = null;
    let pages = 0;
    const rows = [];
    do {
      const payload = await runQuery(parent, slice, pageToken);
      pageToken = null;
      for (const entry of payload) {
        const document = entry?.document;
        if (!document || !document.fields) continue;
        const name = String(document.name || '');
        const docId = name.slice(name.lastIndexOf('/') + 1);
        if (!docId) continue;
        const doc = fromFirestoreFields(document.fields);
        doc.id = docId;
        const created = millis(document.createTime);
        if (created && !doc.createdAt) doc.createdAt = created;
        const updated = millis(document.updateTime);
        if (updated && !doc.updatedAt) doc.updatedAt = updated;
        rows.push({ docId, doc });
      }
      // runQuery answers a pageToken when there is more; a migration stops at maxPages regardless.
      const withToken = payload.find((entry) => entry && typeof entry.pageToken === 'string');
      pageToken = withToken ? withToken.pageToken : null;
      pages += 1;
    } while (pageToken && pages < MIGRATION_LIMITS.maxPages && budget > 0);

    scanned[key] = (scanned[key] || 0) + rows.length;
    for (const row of rows) {
      const collection = parent ? `${parent}/${slice.collection}` : slice.collection;
      if (parent) row.doc.chatId = parent.slice(parent.indexOf('/') + 1);
      const outcome = await store(collection, row.docId, row.doc);
      if (outcome === 'migrated') bump(migrated, collection);
      else if (outcome === 'present') bump(present, collection);
    }
    return rows;
  }

  // The profile first: every other slice hangs off this uid, and a screen that opens before the rest
  // arrives still shows the user's own name and photo.
  try {
    const profile = await readDocument(`users/${encodeURIComponent(uid)}`);
    if (profile && profile.fields) {
      const doc = fromFirestoreFields(profile.fields);
      const created = millis(profile.createTime);
      if (created && !doc.createdAt) doc.createdAt = created;
      const updated = millis(profile.updateTime);
      if (updated && !doc.updatedAt) doc.updatedAt = updated;
      const outcome = await store('users', uid, doc);
      scanned.users = 1;
      if (outcome === 'migrated') bump(migrated, 'users');
      else if (outcome === 'present') bump(present, 'users');
    } else {
      // No profile in the legacy store is not a failure - a brand-new user has nothing to carry over.
      scanned.users = 0;
    }
  } catch (error) {
    errors.push({ slice: 'users', error: String(error?.message || error) });
  }

  for (const slice of SLICES) {
    if (budget <= 0) {
      errors.push({ slice: slice.collection, error: 'document budget reached' });
      continue;
    }
    try {
      const rows = await carry(slice);
      if (slice.nested) {
        // A conversation without its recent messages is a list of names; the tail is what a thread
        // shows the moment it opens. Messages are stored under their chat, exactly as the app reads
        // them (chats/<chatId>/messages), and each one carries its chatId.
        for (const chat of rows.slice(0, MIGRATION_LIMITS.chats)) {
          if (budget <= MIGRATION_LIMITS.messagesPerChat) break;
          try {
            await carry({ collection: slice.nested, limit: 'messagesPerChat' }, `chats/${chat.docId}`);
          } catch (error) {
            errors.push({ slice: `chats/${chat.docId}/messages`, error: String(error?.message || error) });
          }
        }
      }
    } catch (error) {
      errors.push({ slice: slice.collection, error: String(error?.message || error) });
    }
  }

  return json({
    ok: errors.length === 0,
    uid,
    at: now(),
    migrated,
    present,
    scanned,
    errors,
    budgetLeft: budget,
    additive: true,
    legacyWrites: 0,
  });
}

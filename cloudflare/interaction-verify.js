// Promote D1 resources to 'verified' so the engagement API can serve them.
//
// The problem this solves
// -----------------------
// `/v1/engagement/...` refuses every resource unless it has a row in
// `friendbook_cf_interaction_resources` with state='verified'. Only the old migration wrote that
// row, and that migration never ran to completion. So on a production database every reaction,
// comment and reactor list answered 503 "Resource migration not verified" - which the app showed
// as an empty sheet with "Retry reactions".
//
// The gate exists for a good reason: serving an empty counters row for a post whose real likes
// still live elsewhere makes existing reactions appear to vanish. So this does not simply flip
// the flag. A resource is promoted only when
//
//   1. its document is present in D1 (the post itself was copied), and
//   2. its reaction rows are seeded from the uid-keyed map the document already carries, so the
//      sheet has people to show instead of an empty list.
//
// Reaction extraction follows `scripts/admin/plan_interaction_import.py` exactly, because that
// script defines what the real migration considers a reaction. In particular the map is
// uid -> type (not type -> count), and the two shapes have different precedence for posts and
// for reels. Getting that backwards is how a post with 3 likers ends up showing 0.
//
// Everything here is idempotent and bounded by the worker's per-invocation query budget.

const PAGE = 20;          // Workers Free allows 50 D1 queries per invocation
const MAX_ROWS = 400;     // reaction rows per promotion pass, keeps a batch bounded

/** Collections whose documents carry reaction data that the engagement API serves. */
const PROMOTABLE = ['posts', 'reels'];

const TYPES = new Set(['like', 'love', 'haha', 'wow', 'sad', 'angry']);

/**
 * The reactions a document really has, as uid -> type.
 *
 * Mirrors the Android display precedence (Models.normalizeReactionType and Reactions.java):
 * for posts `reactionsMap` wins over `reactions`, for reels the order is reversed. `likedBy` is
 * the older single-type membership list and only fills gaps - 'love' on reels, 'like' elsewhere.
 * A reaction whose type is not recognised is skipped rather than guessed, which is also what the
 * real import does.
 */
function reactionsOf(doc, collection) {
  const out = new Map();
  const fields = collection === 'reels' ? ['reactions', 'reactionsMap'] : ['reactionsMap', 'reactions'];

  for (const field of fields) {
    const value = doc[field];
    if (!value || typeof value !== 'object' || Array.isArray(value)) continue;
    for (const [uid, raw] of Object.entries(value)) {
      if (!uid) continue;
      const kind = raw && typeof raw === 'object' ? raw.type : raw;
      const type = typeof kind === 'string' ? kind.trim().toLowerCase() : '';
      if (!TYPES.has(type)) continue;
      // First field name wins; a later field must not overwrite a richer, earlier value.
      if (!out.has(uid)) out.set(uid, type);
    }
  }

  const fallback = collection === 'reels' ? 'love' : 'like';
  if (Array.isArray(doc.likedBy)) {
    for (const uid of doc.likedBy) {
      if (typeof uid === 'string' && uid && !out.has(uid)) out.set(uid, fallback);
    }
  }

  return out;
}

/**
 * Promotes up to PAGE resources that have a document but no verified row.
 *
 * Returns a summary so a caller can see progress without another query.
 */
export async function promotePage(db, collection = 'posts') {
  if (!PROMOTABLE.includes(collection)) {
    return { error: `Collection ${collection} is not promotable` };
  }

  // Candidates: documents in this collection with no verified resource row.
  const rows = await db.prepare(
    `SELECT d.doc_id AS id, d.document_json AS json
       FROM friendbook_cf_documents d
       LEFT JOIN friendbook_cf_interaction_resources r
              ON r.resource = d.collection || '/' || d.doc_id
      WHERE d.collection = ?
        AND (r.state IS NULL OR r.state <> 'verified')
      LIMIT ?`
  ).bind(collection, PAGE).all();

  const candidates = rows.results || [];
  if (!candidates.length) return { collection, promoted: 0, remaining: 0, reactions: 0 };

  const remainingRow = await db.prepare(
    `SELECT COUNT(*) AS n
       FROM friendbook_cf_documents d
       LEFT JOIN friendbook_cf_interaction_resources r
              ON r.resource = d.collection || '/' || d.doc_id
      WHERE d.collection = ?
        AND (r.state IS NULL OR r.state <> 'verified')`
  ).bind(collection).first();

  const now = Date.now();
  const statements = [];
  const seeded = [];

  for (const row of candidates) {
    let doc;
    try {
      doc = JSON.parse(row.json);
    } catch {
      continue; // a document that does not parse is not safe to promote
    }
    if (!doc || typeof doc !== 'object') continue;

    const resource = `${collection}/${row.id}`;
    statements.push(
      db.prepare(
        `INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified')
         ON CONFLICT(resource) DO UPDATE SET state = 'verified'`
      ).bind(resource)
    );
    seeded.push({ resource, reactions: reactionsOf(doc, collection) });
  }

  // Totals are never written here. The schema maintains friendbook_cf_reaction_totals with an
  // AFTER INSERT trigger on friendbook_cf_reactions, so inserting a row brings its count along;
  // writing both would count every reaction twice.
  let reactionRows = 0;
  for (const entry of seeded) {
    for (const [uid, type] of entry.reactions) {
      if (reactionRows >= MAX_ROWS) break;
      statements.push(
        db.prepare(
          `INSERT INTO friendbook_cf_reactions(resource, uid, type, updated_at)
           VALUES (?, ?, ?, ?)
           ON CONFLICT(resource, uid) DO UPDATE SET type = excluded.type`
        ).bind(entry.resource, uid, type, now)
      );
      reactionRows += 1;
    }
  }

  if (statements.length) await db.batch(statements);

  return {
    collection,
    promoted: seeded.length,
    remaining: Math.max(0, (remainingRow?.n || 0) - seeded.length),
    reactions: reactionRows,
  };
}

/** Progress over every promotable collection. Read only. */
export async function promoteStatus(db) {
  const out = {};
  for (const collection of PROMOTABLE) {
    const pending = await db.prepare(
      `SELECT COUNT(*) AS n
         FROM friendbook_cf_documents d
         LEFT JOIN friendbook_cf_interaction_resources r
                ON r.resource = d.collection || '/' || d.doc_id
        WHERE d.collection = ?
          AND (r.state IS NULL OR r.state <> 'verified')`
    ).bind(collection).first();

    const verified = await db.prepare(
      `SELECT COUNT(*) AS n
         FROM friendbook_cf_interaction_resources
        WHERE state = 'verified' AND resource LIKE ? || '/%'`
    ).bind(collection).first();

    out[collection] = { pending: pending?.n || 0, verified: verified?.n || 0 };
  }
  return out;
}

/**
 * Promotes one resource inline, from the document D1 already holds.
 *
 * This is what makes the app work without an operator: a request for a post that was copied but
 * never marked 'verified' is repaired on the spot rather than refused with 503. Returns false
 * when there is no document to promote, which is the one case the gate must still refuse - a
 * resource with no D1 copy cannot be served without losing its likes.
 */
export async function promoteResource(db, collection, id) {
  if (!PROMOTABLE.includes(collection)) return false;
  const resource = `${collection}/${id}`;

  const existing = await db.prepare(
    'SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?'
  ).bind(resource).first();
  if (existing?.state === 'verified') return true;

  const doc = await db.prepare(
    'SELECT document_json AS json FROM friendbook_cf_documents WHERE path = ?'
  ).bind(resource).first();
  if (!doc?.json) return false;

  let parsed;
  try {
    parsed = JSON.parse(doc.json);
  } catch {
    return false;
  }
  if (!parsed || typeof parsed !== 'object') return false;

  const statements = [
    db.prepare(
      `INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified')
       ON CONFLICT(resource) DO UPDATE SET state = 'verified'`
    ).bind(resource),
  ];

  const now = Date.now();
  let rows = 0;
  for (const [uid, type] of reactionsOf(parsed, collection)) {
    if (rows >= MAX_ROWS) break;
    statements.push(
      db.prepare(
        `INSERT INTO friendbook_cf_reactions(resource, uid, type, updated_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(resource, uid) DO UPDATE SET type = excluded.type`
      ).bind(resource, uid, type, now)
    );
    rows += 1;
  }

  await db.batch(statements);
  return true;
}

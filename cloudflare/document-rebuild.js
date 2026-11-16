// D1 normalised tables -> app document store rebuild, entirely inside Cloudflare.
//
// The migration tables from cloudflare/migrations/0014_additive_core_schema.sql hold the
// app data. The app reads Firestore-shaped documents from friendbook_cf_documents through
// /v1/docs. Earlier migration runs wrote only one of those two shapes at a time, so rows
// could sit in the tables while the app showed nothing. This module converts one
// collection at a time, server side, so no data leaves Cloudflare.
//
// It is routed by cloudflare/migration-api.js under /v1/admin/migrate/documents/* and uses
// that endpoint's existing MIGRATION_ADMIN_TOKEN authentication. It never touches Firebase,
// never deletes a row or a document and never writes the source tables.
//
// Safety:
// - DRY RUN by default. Nothing is written unless the caller sends {"apply": true}.
// - INSERT ... ON CONFLICT(path) DO NOTHING, so an existing (live or preserved staging)
//   document is never replaced unless {"overwrite": true} is sent explicitly.
// - `posts` is the agreed test collection. Every other collection answers 409 until the
//   live posts gate passes: every posts row has a document and the first rows round-trip.

import { invalidateDocumentCaches } from './app-documents.js';

// Workers Free allows 50 D1 queries per invocation. One page stays inside that budget:
// page read + 2 counts + the gate check + one INSERT per row. Paid plans may raise `limit`.
const DEFAULT_LIMIT = 30;
const MAX_LIMIT = 100;
const MAX_VERIFY_SAMPLE = 40;
const BATCH_STATEMENTS = 50;
const MAX_DOCUMENT_BYTES = 95_000;

// snake_case -> camelCase, the field names the app already reads.
export function camel(column) {
  return String(column).replace(/_+([a-zA-Z0-9])/g, (_, c) => c.toUpperCase());
}

// json: column -> value used when the stored text is NULL or malformed.
// boolean: 0/1 INTEGER columns that must become real booleans (the D1-first ACL checks
//          Firestore `booleanValue`, so leaving them numeric denies/permits the wrong user).
export const COLLECTION_SPECS = [
  {
    collection: 'posts',
    table: 'posts',
    pk: 'id',
    ownerColumns: ['author_id'],
    timeColumn: 'created_at',
    testCollection: true,
    json: {
      mentions: '[]', saved_by: '[]', subscribed_uids: '[]', subscribed_by: '[]',
      media_urls: '[]', tagged_uids: '[]', tagged_names: '[]', poll_options: '[]',
      poll_votes: '{}', inline_comments: '[]', reactions_map: '{}',
    },
    boolean: ['archived', 'market_sold', 'author_verified'],
  },
  {
    collection: 'users',
    table: 'users',
    pk: 'uid',
    ownerColumns: ['uid'],
    timeColumn: 'created_at',
    json: {
      favorite_uids: '[]', restricted_uids: '[]', close_friends_uids: '[]',
      highlight_story_ids: '[]', saved_reel_ids: '[]', hobbies: '[]',
      followers: '[]', following: '[]', blocked: '[]',
    },
    boolean: [
      'is_admin', 'is_moderator', 'banned', 'is_verified', 'suspended_lifetime',
      'deactivated', 'login_alerts', 'tag_review_enabled', 'professional_mode',
      'hide_reaction_counts', 'show_active_status', 'subscriptions_enabled',
      'profile_locked', 'profile_pic_guard',
    ],
  },
  {
    collection: 'comments',
    table: 'comments',
    pk: 'id',
    ownerColumns: ['author_id'],
    timeColumn: 'created_at',
    json: { liked_by: '[]', reactions: '{}' },
    boolean: ['edited', 'pinned'],
  },
  {
    collection: 'reels',
    table: 'reels',
    pk: 'id',
    ownerColumns: ['author_id'],
    timeColumn: 'created_at',
    json: { liked_by: '[]', saved_by: '[]', reactions: '{}', inline_comments: '[]' },
    boolean: ['is_from_post', 'mirror_horizontal', 'mute_original_audio'],
  },
  {
    collection: 'stories',
    table: 'stories',
    pk: 'id',
    ownerColumns: ['author_id'],
    timeColumn: 'created_at',
    json: { viewed_by: '[]', reactions: '{}' },
    boolean: [],
  },
  {
    collection: 'chats',
    table: 'chats',
    pk: 'id',
    ownerColumns: ['created_by'],
    timeColumn: 'last_message_time',
    json: {
      admin_ids: '[]', participants: '[]', typing: '{}',
      participant_names: '{}', participant_photos: '{}',
    },
    boolean: ['is_group', 'only_admins_can_send'],
  },
  {
    collection: 'messages',
    table: 'messages',
    pk: 'id',
    ownerColumns: ['sender_id'],
    timeColumn: 'created_at',
    json: { reactions: '{}', reactor_names: '{}', reactor_photos: '{}' },
    boolean: ['forwarded', 'is_seen', 'is_delivered', 'is_deleted'],
  },
  {
    collection: 'groups',
    table: 'groups',
    pk: 'id',
    ownerColumns: ['admin_id'],
    timeColumn: 'created_at',
    json: {
      members: '[]', admin_ids: '[]', moderator_ids: '[]',
      pending_member_ids: '[]', banned_member_ids: '[]', invited_uids: '[]',
    },
    boolean: ['require_post_approval', 'require_join_approval'],
  },
  {
    collection: 'friend_requests',
    table: 'friend_requests',
    pk: 'id',
    ownerColumns: ['from_uid'],
    timeColumn: 'created_at',
    json: {},
    boolean: [],
  },
  {
    collection: 'notifications',
    table: 'notifications',
    pk: 'id',
    ownerColumns: ['to_user_id'],
    timeColumn: 'created_at',
    json: {},
    boolean: ['is_read'],
  },
];

export const SPEC_BY_COLLECTION = new Map(COLLECTION_SPECS.map(spec => [spec.collection, spec]));

function truthyInteger(value) {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  const text = String(value).trim().toLowerCase();
  return text === '1' || text === 'true';
}

export function validSegment(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[/\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
}

/**
 * Map one relational row to a Firestore-shaped plain document.
 * Every column is preserved (future/unknown columns included) under its camelCase name.
 * `id` is always the document id and `updatedAt` is the row's own timestamp, so the
 * /v1/docs ordering is deterministic and a repeated run produces the same bytes.
 */
export function mapRow(spec, row) {
  const doc = {};
  const warnings = [];
  for (const [column, raw] of Object.entries(row || {})) {
    const key = camel(column);
    if (Object.prototype.hasOwnProperty.call(spec.json, column)) {
      const fallback = spec.json[column];
      if (raw === null || raw === undefined) {
        doc[key] = JSON.parse(fallback);
        continue;
      }
      if (typeof raw !== 'string') {
        doc[key] = raw;
        continue;
      }
      try {
        doc[key] = JSON.parse(raw);
      } catch {
        doc[key] = JSON.parse(fallback);
        warnings.push({ column, reason: 'invalid-json' });
      }
      continue;
    }
    if (raw === null || raw === undefined) continue;
    if (spec.boolean.includes(column)) {
      doc[key] = truthyInteger(raw);
      continue;
    }
    doc[key] = raw;
  }

  const docId = row?.[spec.pk];
  if (!validSegment(docId)) return { doc: null, warnings: [], error: `invalid ${spec.pk}` };
  doc.id = docId;
  if (spec.collection === 'users') doc.uid = docId;

  const time = Number(row?.[spec.timeColumn]);
  doc.updatedAt = Number.isFinite(time) && time > 0 ? Math.trunc(time) : 0;
  return { doc, warnings, error: null };
}

export function ownerUidFor(spec, row) {
  for (const column of spec.ownerColumns) {
    const value = row?.[column];
    if (typeof value === 'string' && value) return value.slice(0, 128);
  }
  return '';
}

function parseLimit(raw, fallback = DEFAULT_LIMIT) {
  const value = Number(raw);
  if (!Number.isFinite(value)) return fallback;
  return Math.max(1, Math.min(MAX_LIMIT, Math.trunc(value)));
}

// wrangler reports result.meta.changes, node:sqlite reports result.changes.
function changesOf(result) {
  return Number(result?.meta?.changes ?? result?.changes ?? 0);
}

async function count(db, sql, ...params) {
  const row = await db.prepare(sql).bind(...params).first();
  return Number(row?.count || 0);
}

export async function sourceRowCount(db, spec) {
  return count(db, `SELECT COUNT(*) AS count FROM ${spec.table}`);
}

export async function documentCount(db, collection) {
  return count(db, 'SELECT COUNT(*) AS count FROM friendbook_cf_documents WHERE collection = ?', collection);
}

// Exact (not sampled) check that every source row has a document at its path.
async function missingDocumentCount(db, spec) {
  try {
    return await count(
      db,
      `SELECT COUNT(*) AS count FROM ${spec.table} t WHERE NOT EXISTS (`
      + `SELECT 1 FROM friendbook_cf_documents d WHERE d.path = ? || t.${spec.pk})`,
      `${spec.collection}/`,
    );
  } catch {
    return null;
  }
}

async function documentsWithoutSourceRows(db, spec) {
  try {
    return await count(
      db,
      `SELECT COUNT(*) AS count FROM friendbook_cf_documents WHERE collection = ? `
      + `AND doc_id NOT IN (SELECT ${spec.pk} FROM ${spec.table})`,
      spec.collection,
    );
  } catch {
    return null;
  }
}

function deepEqual(a, b) {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (a === null || b === null || typeof a !== 'object') return false;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) {
    if (a.length !== b.length) return false;
    return a.every((item, index) => deepEqual(item, b[index]));
  }
  const aKeys = Object.keys(a);
  const bKeys = Object.keys(b);
  if (aKeys.length !== bKeys.length) return false;
  return aKeys.every(key => Object.prototype.hasOwnProperty.call(b, key) && deepEqual(a[key], b[key]));
}

/** Field-by-field round trip for up to `sample` source rows. */
export async function verifyCollection(db, spec, sample = 25) {
  const limit = Math.min(MAX_VERIFY_SAMPLE, parseLimit(sample));
  const rows = await db.prepare(
    `SELECT * FROM ${spec.table} WHERE ${spec.pk} IS NOT NULL ORDER BY ${spec.pk} LIMIT ?`,
  ).bind(limit).all();

  const mismatches = [];
  let checked = 0;
  for (const row of rows?.results || []) {
    const mapped = mapRow(spec, row);
    if (mapped.error) {
      mismatches.push({ id: String(row?.[spec.pk] ?? ''), reason: mapped.error });
      continue;
    }
    const path = `${spec.collection}/${mapped.doc.id}`;
    const stored = await db.prepare(
      'SELECT document_json FROM friendbook_cf_documents WHERE path = ?',
    ).bind(path).first();
    if (!stored?.document_json) {
      mismatches.push({ id: mapped.doc.id, reason: 'missing-document' });
      continue;
    }
    let actual;
    try {
      actual = JSON.parse(stored.document_json);
    } catch {
      mismatches.push({ id: mapped.doc.id, reason: 'unreadable-document' });
      continue;
    }
    const keys = [];
    for (const [key, value] of Object.entries(mapped.doc)) {
      if (!deepEqual(actual?.[key], value)) keys.push(key);
    }
    if (keys.length) mismatches.push({ id: mapped.doc.id, reason: 'field-mismatch', keys: keys.slice(0, 12) });
    checked += 1;
  }

  const [sourceRows, documentRows, orphans, missing] = await Promise.all([
    sourceRowCount(db, spec),
    documentCount(db, spec.collection),
    documentsWithoutSourceRows(db, spec),
    missingDocumentCount(db, spec),
  ]);
  return {
    collection: spec.collection,
    checked,
    mismatches: mismatches.slice(0, 20),
    mismatchCount: mismatches.length,
    sourceRows,
    documentRows,
    sourceRowsWithoutDocument: missing,
    documentsWithoutSourceRow: orphans,
  };
}

/** The agreed test gate: posts must be complete and round-trip clean. */
export async function postsGate(db) {
  const spec = SPEC_BY_COLLECTION.get('posts');
  const [sourceRows, documentRows, missing] = await Promise.all([
    sourceRowCount(db, spec),
    documentCount(db, 'posts'),
    missingDocumentCount(db, spec),
  ]);
  let verification = { checked: 0, mismatchCount: 0, mismatches: [] };
  if (sourceRows > 0 && documentRows >= sourceRows) {
    // Exact source-row coverage above, plus a real field round trip on the first rows.
    // A deeper sample is available from GET /v1/admin/migrate/documents/verify?sample=40.
    verification = await verifyCollection(db, spec, 3);
  }
  const passed = sourceRows > 0
    && documentRows >= sourceRows
    && missing === 0
    && verification.checked > 0
    && verification.mismatchCount === 0;
  return {
    collection: 'posts',
    passed,
    sourceRows,
    documentRows,
    sourceRowsWithoutDocument: missing,
    sampleChecked: verification.checked,
    sampleMismatches: verification.mismatches,
  };
}

async function collectionState(db, spec, gate) {
  const [sourceRows, documentRows] = await Promise.all([
    sourceRowCount(db, spec),
    documentCount(db, spec.collection),
  ]);
  return {
    collection: spec.collection,
    sourceTable: spec.table,
    sourceRows,
    documentRows,
    pending: Math.max(0, sourceRows - documentRows),
    ready: !spec.testCollection && gate.passed,
    testCollection: Boolean(spec.testCollection),
  };
}

/** Counts, pending work and the gate for every collection. Read-only. */
export async function documentPlan(db) {
  const gate = await postsGate(db);
  const collections = [];
  for (const spec of COLLECTION_SPECS) collections.push(await collectionState(db, spec, gate));
  return {
    ok: true,
    testCollection: 'posts',
    gate,
    collections,
    totals: {
      sourceRows: collections.reduce((sum, item) => sum + item.sourceRows, 0),
      documentRows: collections.reduce((sum, item) => sum + item.documentRows, 0),
      pending: collections.reduce((sum, item) => sum + item.pending, 0),
    },
  };
}

/**
 * Convert one page of rows. Dry run unless apply=true.
 * Reads with a keyset cursor (`after`) so repeated calls finish a large collection
 * without OFFSET scans.
 */
export async function rebuildPage(db, spec, options = {}) {
  const limit = parseLimit(options.limit);
  const after = typeof options.after === 'string' ? options.after : '';
  const apply = options.apply === true;
  const overwrite = options.overwrite === true;
  const rows = await db.prepare(
    `SELECT * FROM ${spec.table} WHERE ${spec.pk} > ? ORDER BY ${spec.pk} LIMIT ?`,
  ).bind(after, limit).all();
  const page = rows?.results || [];

  const result = {
    collection: spec.collection,
    sourceTable: spec.table,
    apply,
    overwrite,
    dryRun: !apply,
    scanned: page.length,
    converted: 0,
    written: 0,
    skippedExisting: 0,
    oversized: 0,
    invalid: 0,
    warnings: {},
    errors: [],
    lastId: page.length ? String(page[page.length - 1][spec.pk]) : null,
    nextCursor: page.length === limit ? String(page[page.length - 1][spec.pk]) : null,
    existingAtDestination: await documentCount(db, spec.collection),
  };
  if (!page.length) return result;

  const statements = [];
  for (const row of page) {
    const { doc, warnings, error } = mapRow(spec, row);
    if (error || !doc) {
      result.invalid += 1;
      if (result.errors.length < 10) result.errors.push({ id: String(row?.[spec.pk] ?? ''), error: error || 'unmapped' });
      continue;
    }
    for (const warning of warnings) {
      const key = `${warning.column}:${warning.reason}`;
      result.warnings[key] = (result.warnings[key] || 0) + 1;
    }
    const bytes = JSON.stringify(doc).length;
    if (bytes > MAX_DOCUMENT_BYTES) {
      result.oversized += 1;
      if (result.errors.length < 10) result.errors.push({ id: doc.id, error: 'document-too-large', bytes });
      continue;
    }
    result.converted += 1;
    if (!apply) continue;
    const path = `${spec.collection}/${doc.id}`;
    // Keep an existing document (live or preserved staging) unless overwrite was asked for.
    const conflict = overwrite
      ? `ON CONFLICT(path) DO UPDATE SET
           owner_uid = excluded.owner_uid,
           document_json = excluded.document_json,
           updated_at = excluded.updated_at`
      : 'ON CONFLICT(path) DO NOTHING';
    statements.push(
      db.prepare(
        `INSERT INTO friendbook_cf_documents(path, collection, doc_id, owner_uid, document_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?) ${conflict}`,
      ).bind(path, spec.collection, doc.id, ownerUidFor(spec, row), JSON.stringify(doc), doc.updatedAt),
    );
  }

  if (!apply || !statements.length) return result;

  for (let offset = 0; offset < statements.length; offset += BATCH_STATEMENTS) {
    const chunk = statements.slice(offset, offset + BATCH_STATEMENTS);
    try {
      if (typeof db.batch === 'function') {
        const written = await db.batch(chunk);
        for (const item of written || []) {
          if (changesOf(item) > 0) result.written += 1;
          else result.skippedExisting += 1;
        }
      } else {
        for (const statement of chunk) {
          if (changesOf(await statement.run()) > 0) result.written += 1;
          else result.skippedExisting += 1;
        }
      }
    } catch {
      // A failed batch is reported, never retried blindly; no partial SQL is guessed.
      result.errors.push({ batchStart: offset, error: 'batch-write-failed' });
    }
  }
  return result;
}

/**
 * Handle one rebuild action for the admin route. Returns { status, body } so the caller
 * (migration-api.js) keeps its own CORS/auth behaviour.
 */
export async function documentRebuildAction(db, url, request) {
  const path = url.pathname;

  if (path === '/v1/admin/migrate/documents/plan') {
    if (request.method !== 'GET') return { status: 405, body: { error: 'Method not allowed' } };
    return { status: 200, body: await documentPlan(db) };
  }

  if (path === '/v1/admin/migrate/documents/verify') {
    if (request.method !== 'GET') return { status: 405, body: { error: 'Method not allowed' } };
    const collection = url.searchParams.get('collection') || 'posts';
    const spec = SPEC_BY_COLLECTION.get(collection);
    if (!spec) return { status: 400, body: { error: 'Unsupported collection' } };
    const sample = url.searchParams.get('sample') || 25;
    return { status: 200, body: { ok: true, ...await verifyCollection(db, spec, sample) } };
  }

  if (path === '/v1/admin/migrate/documents/rebuild') {
    if (request.method !== 'POST') return { status: 405, body: { error: 'Method not allowed' } };
    let body = {};
    try { body = await request.json(); } catch { return { status: 400, body: { error: 'Invalid JSON payload' } }; }
    const spec = SPEC_BY_COLLECTION.get(typeof body?.collection === 'string' ? body.collection : '');
    if (!spec) return { status: 400, body: { error: 'Unsupported collection' } };

    const gate = await postsGate(db);
    if (!spec.testCollection && !gate.passed && body?.force !== true) {
      return {
        status: 409,
        body: { error: 'posts test gate has not passed; rebuild posts first and verify it', gate },
      };
    }
    const result = await rebuildPage(db, spec, {
      apply: body?.apply === true,
      overwrite: body?.overwrite === true,
      limit: body?.limit,
      after: body?.after,
    });
    if (result.written > 0) invalidateDocumentCaches(db);
    return { status: 200, body: { ok: result.errors.length === 0, gate, ...result } };
  }

  return null;
}

// Real view counters in Cloudflare D1 (/v1/views).
// Firebase is used for authentication ONLY — no view count is ever written to Firestore.
//
// Routes (all require a verified Firebase ID token, resolved to `uid` by worker.js):
//   POST /v1/views/{posts|reels}/{id}            -> { resource, views, counted, gapMs }
//   GET  /v1/views/{posts|reels}/{id}            -> { resource, views }
//   GET  /v1/views?collection=reels&ids=a,b,c    -> { collection, views: { id: n } }
//   GET  /v1/views?collection=reels&top=25       -> { collection, views: { id: n } } (most viewed first)
//
// Counting rule: watching a resource for one second is ONE real view, and the same user watching
// it again counts again. The only floor is one counted view per (resource, uid) per second, so a
// double-fired client callback or a runaway loop can never write more than one row per second.
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const COLLECTIONS = new Set(['posts', 'reels']);
/** Minimum gap between two counted views of the same resource by the same user. */
const MIN_VIEW_GAP_MS = 1000;
const MAX_BATCH_IDS = 50;
const MAX_TOP = 50;
const DEFAULT_TOP = 25;
const ensuredSchemas = new WeakSet();

function validId(value) {
  // A comma is rejected too: it is the separator of the batched `ids` query parameter.
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[\/,\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
}

function changesOf(result) {
  const changes = result?.meta?.changes ?? result?.changes ?? 0;
  return Number(changes) || 0;
}

async function ensureSchema(db) {
  if (ensuredSchemas.has(db)) return;
  // Idempotent, additive and safe on a database that already holds production data. The Worker
  // creates the tables itself so the counters work even before a migration run is applied.
  await db.batch([
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_view_counters (
        resource TEXT PRIMARY KEY NOT NULL,
        views INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL DEFAULT 0)`),
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_view_dedupe (
        resource TEXT NOT NULL,
        uid TEXT NOT NULL,
        bucket TEXT NOT NULL,
        PRIMARY KEY (resource, uid, bucket))`),
    db.prepare(`CREATE TABLE IF NOT EXISTS friendbook_cf_view_last (
        resource TEXT NOT NULL,
        uid TEXT NOT NULL,
        last_at INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (resource, uid))`),
  ]);
  // Prepared after the tables exist: an eagerly compiled statement cannot reference a table that
  // has not been created yet. Runs once per Worker isolate (guarded by ensuredSchemas).
  await db.prepare(`CREATE INDEX IF NOT EXISTS idx_friendbook_cf_view_counters_views
      ON friendbook_cf_view_counters(views DESC)`).run();
  ensuredSchemas.add(db);
}

async function readViews(db, resource) {
  const row = await db.prepare(
    'SELECT views FROM friendbook_cf_view_counters WHERE resource = ?',
  ).bind(resource).first();
  return row ? Number(row.views) || 0 : 0;
}

export async function viewCounters(request, env, identity, match, url, nowMs = Date.now) {
  if (!env.DB) return json({ error: 'Database unavailable' }, 503);
  const db = env.DB;
  try { await ensureSchema(db); } catch { return json({ error: 'Database unavailable' }, 503); }

  const uid = typeof identity?.uid === 'string' ? identity.uid : '';
  if (!uid || uid.length > 128) return json({ error: 'Unauthorized' }, 401);

  // ---- Collection level reads: /v1/views?collection=...&ids=... (or &top=...) ----
  if (!match) {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    const collection = url?.searchParams?.get('collection') || '';
    if (!COLLECTIONS.has(collection)) return json({ error: 'Invalid collection' }, 400);
    const prefix = collection + '/';

    const idsParam = url.searchParams.get('ids');
    if (idsParam !== null) {
      const ids = [...new Set(String(idsParam).split(',').map((s) => s.trim()).filter(validId))];
      if (ids.length > MAX_BATCH_IDS) return json({ error: 'Too many ids' }, 400);
      if (ids.length === 0) return json({ collection, views: {} });
      const placeholders = ids.map(() => '?').join(',');
      const rows = await db.prepare(
        `SELECT resource, views FROM friendbook_cf_view_counters WHERE resource IN (${placeholders})`,
      ).bind(...ids.map((id) => prefix + id)).all();
      const views = {};
      for (const row of rows?.results ?? []) {
        const resource = String(row.resource);
        if (!resource.startsWith(prefix)) continue;
        views[resource.slice(prefix.length)] = Number(row.views) || 0;
      }
      return json({ collection, views });
    }

    const requestedTop = Number(url.searchParams.get('top') ?? DEFAULT_TOP);
    const top = Number.isFinite(requestedTop)
      ? Math.min(MAX_TOP, Math.max(1, Math.trunc(requestedTop))) : DEFAULT_TOP;
    const rows = await db.prepare(
      `SELECT resource, views FROM friendbook_cf_view_counters
       WHERE resource >= ? AND resource < ?
       ORDER BY views DESC, updated_at DESC LIMIT ?`,
    ).bind(prefix, prefix + '\uFFFF', top).all();
    const views = {};
    for (const row of rows?.results ?? []) {
      views[String(row.resource).slice(prefix.length)] = Number(row.views) || 0;
    }
    return json({ collection, views });
  }

  // ---- Single resource: /v1/views/{collection}/{id} ----
  const [, collection, rawId] = match;
  if (!COLLECTIONS.has(collection)) return json({ error: 'Invalid collection' }, 400);
  let id;
  try { id = decodeURIComponent(rawId); } catch { return json({ error: 'Invalid resource' }, 400); }
  if (!validId(id)) return json({ error: 'Invalid resource' }, 400);
  const resource = `${collection}/${id}`;

  if (request.method === 'GET') {
    return json({ resource, views: await readViews(db, resource) });
  }
  if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

  const now = Number(nowMs()) || Date.now();
  let counted = false;
  try {
    // Upsert the per-user stamp, but only when the previous counted view is at least one second
    // old. `changes` is 0 when the WHERE filters the update out, so nothing is counted twice.
    const stamp = await db.prepare(
      `INSERT INTO friendbook_cf_view_last(resource, uid, last_at) VALUES (?, ?, ?)
       ON CONFLICT(resource, uid) DO UPDATE SET last_at = excluded.last_at
       WHERE last_at <= ?`,
    ).bind(resource, uid, now, now - MIN_VIEW_GAP_MS).run();
    counted = changesOf(stamp) > 0;
    if (counted) {
      // One upsert = one row written: views = views + 1 stays race-free for concurrent viewers.
      await db.prepare(
        `INSERT INTO friendbook_cf_view_counters(resource, views, updated_at) VALUES (?, 1, ?)
         ON CONFLICT(resource) DO UPDATE SET views = views + 1, updated_at = excluded.updated_at`,
      ).bind(resource, now).run();
    }
  } catch {
    return json({ error: 'Database unavailable; no view was counted' }, 503);
  }
  return json({ resource, views: await readViews(db, resource), counted, gapMs: MIN_VIEW_GAP_MS });
}

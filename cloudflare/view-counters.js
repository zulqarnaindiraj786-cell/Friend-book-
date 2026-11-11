// Real view counters with ZERO D1 row reads on the hot path.
//
// Counting happens in Worker memory and is aggregated into Workers KV (binding `VIEWS`). D1 is
// written at most ONCE PER HOUR per resource, with a single upsert that stores the live total
// (`views = MAX(views, excluded.views)`) and hands the stored value back through RETURNING — so
// counting and serving a view never issues a SELECT to D1, and D1 can never drift below the live
// counter even if an isolate or KV snapshot is lost.
//
// Routes (all require a verified Firebase ID token, resolved to `uid` by worker.js):
//   POST /v1/views/{posts|reels}/{id}          -> { resource, views, counted, gapMs, storage }
//   GET  /v1/views/{posts|reels}/{id}          -> { resource, views, storage }
//   GET  /v1/views?collection=reels&ids=a,b,c  -> { collection, views: { id: n }, storage }
//   GET  /v1/views?collection=reels&top=25     -> { collection, views: { id: n }, storage }
//
// Counting rule (product decision): one second of watching = one view, and the same user watching
// again counts again. The only floor is one counted view per (resource, uid) per second, kept in
// memory, so a double-fired client callback can never count twice.
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const COLLECTIONS = new Set(['posts', 'reels']);
const MAX_BATCH_IDS = 50;
const MAX_TOP = 50;
const DEFAULT_TOP = 25;
/** Minimum gap between two counted views of the same resource by the same user. */
const MIN_VIEW_GAP_MS = 1000;
/**
 * One KV write of the aggregate key at most every 5 minutes -> <= 288 writes/day per isolate, so
 * even three concurrent isolates stay inside the Workers Free limit of 1,000 KV writes/day. The
 * throttle is enforced inside flushKv, so no other code path can add an unbudgeted KV write.
 */
const KV_FLUSH_MS = 300_000;
/** One D1 write per resource per hour, exactly as requested. */
const D1_FLUSH_MS = 3_600_000;
const KV_KEY = 'views:live:v1';
/** Upper bound of the aggregate KV value; anything beyond it re-seeds from D1 on a cold isolate. */
const KV_SNAPSHOT_LIMIT = 1000;
/** A cold isolate with no KV data reads D1 exactly once, bounded, and never again. */
const SEED_LIMIT = 500;
const MAX_SEEN_ENTRIES = 8000;

const ensuredSchemas = new WeakSet();

// Isolate-lifetime state. One Worker isolate serves many requests, so these maps are what makes
// "count in memory/KV, write to D1 hourly" possible. resetViewState() exists for tests only.
const state = {
  loaded: false,
  loading: null,
  kvUsable: false,
  seedReads: 0,
  lastKvFlushAt: 0,
  totals: new Map(),      // resource -> live total (what every read is served from)
  dirty: new Set(),       // resources counted since their last D1 write
  lastD1Flush: new Map(), // resource -> ms of the last D1 write (or of the seed load)
  lastSeen: new Map(),    // `${resource}|${uid}` -> ms of the last counted view
};

export function resetViewState() {
  state.loaded = false;
  state.loading = null;
  state.kvUsable = false;
  state.seedReads = 0;
  state.lastKvFlushAt = 0;
  state.totals.clear();
  state.dirty.clear();
  state.lastD1Flush.clear();
  state.lastSeen.clear();
}

/** Live counter state, exposed for tests and diagnostics. */
export function viewCounterState() {
  return {
    totals: new Map(state.totals),
    dirty: new Set(state.dirty),
    kvUsable: state.kvUsable,
    seedReads: state.seedReads,
  };
}

function validId(value) {
  // A comma is rejected too: it is the separator of the batched `ids` query parameter.
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[\/,\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
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

const storageMode = () => (state.kvUsable ? 'kv+memory' : 'memory');

function trimSeen(now) {
  if (state.lastSeen.size <= MAX_SEEN_ENTRIES) return;
  for (const [key, at] of state.lastSeen) {
    if (now - at > 60_000) state.lastSeen.delete(key);
  }
  if (state.lastSeen.size > MAX_SEEN_ENTRIES) state.lastSeen.clear();
}

function absorb(resource, value) {
  const total = Number(value);
  if (!Number.isFinite(total)) return;
  state.totals.set(resource, Math.max(0, Math.trunc(total)));
}

/**
 * Writes the whole aggregate snapshot to KV under one key, keeping the value bounded. Throttled to
 * one write per KV_FLUSH_MS unless `force` is set (the one-time seed write after a D1 restore).
 */
async function flushKv(env, now = Date.now(), force = false) {
  if (!env?.VIEWS) return false;
  if (!force && now - state.lastKvFlushAt < KV_FLUSH_MS) return false;
  try {
    const snapshot = {};
    for (const [resource, total] of [...state.totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, KV_SNAPSHOT_LIMIT)) {
      snapshot[resource] = total;
    }
    await env.VIEWS.put(KV_KEY, JSON.stringify(snapshot));
    state.lastKvFlushAt = now;
    state.kvUsable = true;
    return true;
  } catch {
    state.kvUsable = false;
    return false;
  }
}

/**
 * Persists ONE resource to D1 with a single statement and no SELECT: the stored total can only go
 * up (`MAX`), and RETURNING reports what D1 now holds, which becomes the live value when another
 * isolate had already counted more.
 */
async function flushD1(env, resource, now) {
  const live = state.totals.get(resource) ?? 0;
  try {
    const row = await env.DB.prepare(
      `INSERT INTO friendbook_cf_view_counters(resource, views, updated_at) VALUES (?, ?, ?)
       ON CONFLICT(resource) DO UPDATE SET views = MAX(views, excluded.views), updated_at = excluded.updated_at
       RETURNING views`,
    ).bind(resource, live, now).first();
    state.lastD1Flush.set(resource, now);
    state.dirty.delete(resource);
    const stored = Number(row?.views);
    if (Number.isFinite(stored) && stored > live) state.totals.set(resource, Math.trunc(stored));
    await flushKv(env, now); // throttled: an hourly D1 write must not add a KV write of its own
    return true;
  } catch {
    // Write refused: stay dirty so the next window persists it. Nothing is invented, nothing lost.
    return false;
  }
}

async function maybeFlush(env, resource, now, ctx) {
  const tasks = [];
  if (env?.VIEWS && now - state.lastKvFlushAt >= KV_FLUSH_MS) tasks.push(flushKv(env, now));
  if (state.dirty.has(resource) && env?.DB && now - (state.lastD1Flush.get(resource) ?? 0) >= D1_FLUSH_MS) {
    tasks.push(flushD1(env, resource, now));
  }
  if (tasks.length === 0) return;
  const all = Promise.allSettled(tasks);
  // The response never waits on storage: the runtime keeps the flushes alive after we return.
  if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(all);
  else await all;
}

/**
 * Loads the live totals once per isolate: KV first (zero D1 reads). Only when nothing is known at
 * all — a first deploy or an empty/evicted KV — one bounded D1 SELECT seeds the maps, and the seed
 * is written straight back to KV so it never has to happen again.
 */
async function ensureLoaded(env, nowMs = Date.now) {
  if (state.loaded) return;
  if (!state.loading) {
    state.loading = (async () => {
      const seededAt = Number(nowMs()) || Date.now();
      if (env?.VIEWS) {
        try {
          const stored = await env.VIEWS.get(KV_KEY, { type: 'json', cacheTtl: 60 });
          if (stored && typeof stored === 'object') {
            for (const [resource, total] of Object.entries(stored)) {
              if (typeof resource !== 'string') continue;
              absorb(resource, total);
              // Seeded totals are treated as recently persisted, so D1 is not written again until
              // the next hourly window — the seed itself costs zero D1 writes.
              state.lastD1Flush.set(resource, seededAt);
            }
            state.kvUsable = true;
            // The snapshot was just read: start the two minute KV write window here, so a busy
            // isolate does not rewrite the very value it just loaded.
            state.lastKvFlushAt = seededAt;
          }
        } catch { state.kvUsable = false; }
      }
      if (state.totals.size === 0 && env?.DB) {
        try {
          const rows = await env.DB.prepare(
            'SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT ?',
          ).bind(SEED_LIMIT).all();
          for (const row of rows?.results ?? []) {
            if (typeof row.resource !== 'string') continue;
            absorb(row.resource, row.views);
            state.lastD1Flush.set(row.resource, seededAt);
          }
          state.seedReads += 1;
        } catch { /* no D1 here: memory-only counting still works */ }
        if (env?.VIEWS) await flushKv(env, seededAt, true);
      }
      state.loaded = true;
    })();
  }
  try { await state.loading; } catch { state.loaded = true; }
}

export async function viewCounters(request, env, identity, match, url, nowMs = Date.now, ctx) {
  const uid = typeof identity?.uid === 'string' ? identity.uid : '';
  if (!uid || uid.length > 128) return json({ error: 'Unauthorized' }, 401);
  if (env?.DB) {
    try { await ensureSchema(env.DB); } catch { return json({ error: 'Database unavailable' }, 503); }
  }
  await ensureLoaded(env, nowMs);

  // ---- Collection level reads: /v1/views?collection=...&ids=... (or &top=...) ----
  if (!match) {
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    const collection = url?.searchParams?.get('collection') || '';
    if (!COLLECTIONS.has(collection)) return json({ error: 'Invalid collection' }, 400);
    const prefix = collection + '/';
    const storage = storageMode();

    const idsParam = url.searchParams.get('ids');
    if (idsParam !== null) {
      const ids = [...new Set(String(idsParam).split(',').map((s) => s.trim()).filter(validId))];
      if (ids.length > MAX_BATCH_IDS) return json({ error: 'Too many ids' }, 400);
      const views = {};
      for (const id of ids) views[id] = state.totals.get(prefix + id) ?? 0;
      return json({ collection, views, storage });
    }

    const requestedTop = Number(url.searchParams.get('top') ?? DEFAULT_TOP);
    const top = Number.isFinite(requestedTop)
      ? Math.min(MAX_TOP, Math.max(1, Math.trunc(requestedTop))) : DEFAULT_TOP;
    const views = {};
    for (const [resource, total] of [...state.totals.entries()]
      .filter(([key]) => key.startsWith(prefix))
      .sort((a, b) => b[1] - a[1])
      .slice(0, top)) {
      views[resource.slice(prefix.length)] = total;
    }
    return json({ collection, views, storage });
  }

  // ---- Single resource: /v1/views/{collection}/{id} ----
  const [, collection, rawId] = match;
  if (!COLLECTIONS.has(collection)) return json({ error: 'Invalid collection' }, 400);
  let id;
  try { id = decodeURIComponent(rawId); } catch { return json({ error: 'Invalid resource' }, 400); }
  if (!validId(id)) return json({ error: 'Invalid resource' }, 400);
  const resource = `${collection}/${id}`;

  if (request.method === 'GET') {
    return json({ resource, views: state.totals.get(resource) ?? 0, storage: storageMode() });
  }
  if (request.method !== 'POST') return json({ error: 'Method not allowed' }, 405);

  const now = Number(nowMs()) || Date.now();
  const seenKey = `${resource}|${uid}`;
  const counted = now - (state.lastSeen.get(seenKey) ?? 0) >= MIN_VIEW_GAP_MS;
  if (counted) {
    state.lastSeen.set(seenKey, now);
    trimSeen(now);
    state.totals.set(resource, (state.totals.get(resource) ?? 0) + 1);
    state.dirty.add(resource);
  }
  await maybeFlush(env, resource, now, ctx);
  return json({
    resource,
    views: state.totals.get(resource) ?? 0,
    counted,
    gapMs: MIN_VIEW_GAP_MS,
    storage: storageMode(),
    // True while this resource has counted views that D1 has not received yet (hourly window).
    d1Pending: state.dirty.has(resource),
  });
}

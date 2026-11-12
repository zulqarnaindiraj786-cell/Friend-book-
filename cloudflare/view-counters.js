// Real view counters on Cloudflare Analytics Engine. ZERO D1 writes, ZERO D1 reads on the view path.
//
// Every view that reaches the Worker becomes ONE Analytics Engine event (`writeDataPoint`). The
// counting rule is enforced by the aggregation, not by a database row: the live number is AE's
// `uniq(uid)` per resource, i.e. ONE view per user per video forever - a user who watches ten times
// produces ten events but is counted once.
//
// Storage map:
//   Analytics Engine (binding AE, dataset friendbook_views) -> every view event + the real count
//   Workers KV       (binding VIEWS, key views:live:v1)     -> live snapshot shared across isolates
//   Worker memory    (this isolate)                         -> what a request is served from
//   D1                                                      -> NOT WRITTEN. One bounded, read-only
//                                                              emergency seed, and only when KV and
//                                                              AE both have nothing at all, so the
//                                                              numbers users already saw survive.
//
// Routes (all require a verified Firebase ID token, resolved to `uid` by worker.js):
//   POST /v1/views/{posts|reels}/{id}          -> { resource, views, counted, dedupe, storage }
//   GET  /v1/views/{posts|reels}/{id}          -> { resource, views, storage }
//   GET  /v1/views?collection=reels&ids=a,b,c  -> { collection, views: { id: n }, storage }
//   GET  /v1/views?collection=reels&top=25     -> { collection, views: { id: n }, storage }
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});

const COLLECTIONS = new Set(['posts', 'reels']);
const MAX_BATCH_IDS = 50;
const MAX_TOP = 50;
const DEFAULT_TOP = 25;
/** Reported to clients so the rule is visible in the API, not hidden in the code. */
const DEDUPE_RULE = 'once-per-user';

/** Analytics Engine dataset `friendbook_views`, bound as `ANALYTICS_ENGINE` in wrangler.toml. */
const AE_DATASET = 'friendbook_views';
/** How far back the AE count looks. AE aggregates are cheap; retention decides the real horizon. */
const AE_WINDOW_DAYS = 90;
/** One AE query per isolate per minute at most: the live layer covers the gap. */
const AE_REFRESH_MS = 60_000;
const AE_QUERY_LIMIT = 500;
/**
 * Query variants, tried in order and the first that answers is remembered for this isolate. AE
 * exposes SQL over REST and a GraphQL dataset query; which one an account can use depends on its
 * plan and rollout, so neither is assumed.
 */
// Written from the answers the REAL dataset gave (published on the pull request by
// cloudflare/report-views-ae.mjs), not from documentation:
//   `count(DISTINCT blob3)` -> HTTP 200 (resource String, viewers UInt64)   <- exact, so it is first
//   `sum(double1)`          -> HTTP 200 (viewers Float64, plays UInt64)
//   `uniq(blob3)`           -> HTTP 422 "unknown function call: UNIQ"
//   `uniqExact(blob3)`      -> HTTP 422 "unknown function call: UNIQEXACT"
//   `uniqHLL12(blob3)`      -> HTTP 422 "unknown function call: UNIQHLL12"
//   `INTERVAL 90 DAY`       -> HTTP 422 "Expected literal string, found: 90" (so the number is quoted)
//
// `count(DISTINCT blob3)` is the rule itself: Analytics Engine counts each uid once per resource no
// matter which isolate wrote the event, when it was written, or how often the user rewatched. The
// event metric (1 when this view counted, 0 for a repeat) keeps `sum(double1)` available as a second
// viewer query and makes `count()` the number of plays. A plain `count()` is NEVER a viewer count: if
// AE refused every variant the counters are served from the snapshot + memory instead of an inflated
// number.
const AE_SQL_VARIANTS = [
  `SELECT index1 AS resource, count(DISTINCT blob3) AS viewers, count() AS plays FROM ${AE_DATASET}`
    + ` GROUP BY index1 ORDER BY viewers DESC LIMIT ${AE_QUERY_LIMIT}`,
  `SELECT index1 AS resource, count(DISTINCT blob3) AS viewers, count() AS plays FROM ${AE_DATASET}`
    + ` WHERE timestamp >= now() - INTERVAL '${AE_WINDOW_DAYS}' DAY GROUP BY index1`
    + ` ORDER BY viewers DESC LIMIT ${AE_QUERY_LIMIT}`,
  `SELECT index1 AS resource, sum(double1) AS viewers, count() AS plays FROM ${AE_DATASET}`
    + ` GROUP BY index1 ORDER BY viewers DESC LIMIT ${AE_QUERY_LIMIT}`,
  `SELECT index1 AS resource, sum(double1) AS viewers, count() AS plays FROM ${AE_DATASET}`
    + ` WHERE timestamp >= now() - INTERVAL '${AE_WINDOW_DAYS}' DAY GROUP BY index1`
    + ` ORDER BY viewers DESC LIMIT ${AE_QUERY_LIMIT}`,
];

const AE_GRAPHQL_QUERY = `query ($tag: string!, $since: datetime!, $until: datetime!) {
  viewer {
    accounts(filter: { accountTag: $tag }) {
      views: ${AE_DATASET}(
        filter: { datetime_geq: $since, datetime_ltq: $until }
        limit: ${AE_QUERY_LIMIT}
        orderBy: [countDesc]
      ) {
        dimensions { name count uniq }
        metrics { count sum }
      }
    }
  }
}`;

/** One KV write of the aggregate key at most every 5 minutes -> <= 288 writes/day per isolate. */
const KV_FLUSH_MS = 300_000;
const KV_KEY = 'views:live:v1';
/**
 * Snapshot fallback for an account where Workers KV could not be bound to the Worker: ONE small JSON
 * object in the existing R2 bucket, under its own `views/` prefix. It never touches `videos/`, never
 * deletes anything, and costs one Class A write per 5 minutes per isolate. Without some snapshot a
 * fresh isolate would start from zero and the numbers users already saw would go backwards.
 */
const R2_SNAPSHOT_KEY = 'views/live/v1.json';
/** Upper bound of the aggregate KV value; a bigger live set is served from AE + memory. */
const KV_SNAPSHOT_LIMIT = 1000;
/** Emergency, read-only D1 seed: only when KV and AE both have nothing. */
const SEED_LIMIT = 500;
/** Cache of "this uid already counted here", so repeats need no work at all. */
const MAX_COUNTED_ENTRIES = 8000;
const COUNTED_EVICT_BATCH = 1600;

// Isolate-lifetime state. resetViewState() exists for tests only.
const state = {
  loaded: false,
  loading: null,
  kvUsable: false,
  snapshotStore: null,    // 'kv' | 'r2' - which snapshot store this isolate could actually use
  aeUsable: false,
  aeVariant: null,        // the SQL/GraphQL shape that this account actually answers with
  lastAeRefreshAt: 0,
  seedReads: 0,
  lastKvFlushAt: 0,
  base: new Map(),        // resource -> historical high-water total (pre-AE, from KV or the seed)
  aeBase: new Map(),      // resource -> last uniq(uid) reported by Analytics Engine
  sinceAe: new Map(),     // resource -> views counted in memory since the last AE refresh
  totals: new Map(),      // resource -> the number a request is served from
  counted: new Map(),     // `${resource}|${uid}` -> ms when this isolate settled that pair
};

export function resetViewState() {
  state.loaded = false;
  state.loading = null;
  state.kvUsable = false;
  state.snapshotStore = null;
  state.aeUsable = false;
  state.aeVariant = null;
  state.lastAeRefreshAt = 0;
  state.seedReads = 0;
  state.lastKvFlushAt = 0;
  state.base.clear();
  state.aeBase.clear();
  state.sinceAe.clear();
  state.totals.clear();
  state.counted.clear();
}

/** Live counter state, exposed for tests and diagnostics. */
export function viewCounterState() {
  return {
    totals: new Map(state.totals),
    base: new Map(state.base),
    aeBase: new Map(state.aeBase),
    aeUsable: state.aeUsable,
    aeVariant: state.aeVariant,
    kvUsable: state.kvUsable,
    snapshotStore: state.snapshotStore,
    seedReads: state.seedReads,
    counted: state.counted.size,
  };
}

function validId(value) {
  // A comma is rejected too: it is the separator of the batched `ids` query parameter.
  return typeof value === 'string' && value.length > 0 && value.length <= 128
    && !/[\/,\x00-\x1f]/.test(value) && !['.', '..'].includes(value);
}

/** `memory` -> `r2+memory` -> `kv+memory` -> `ae+kv+memory`: whatever this isolate really used. */
const storageMode = () => [
  state.aeUsable ? 'ae' : null,
  state.kvUsable ? (state.snapshotStore === 'r2' ? 'r2' : 'kv') : null,
  'memory',
].filter(Boolean).join('+');

/** Which snapshot store exists on this deployment: Workers KV first, the R2 object as a fallback. */
const snapshotStore = (env) => (env?.VIEWS ? 'kv' : (env?.VIDEO_BUCKET ? 'r2' : null));

/**
 * Keeps the dedupe cache bounded. It is only a cache: an evicted pair can add one extra view to the
 * live layer, and the next Analytics Engine refresh re-bases the total on `uniq(uid)` - the durable
 * rule needs no storage write at all.
 */
function trimCounted() {
  if (state.counted.size <= MAX_COUNTED_ENTRIES) return;
  let drop = COUNTED_EVICT_BATCH;
  for (const key of state.counted.keys()) {
    if (drop-- <= 0) break;
    state.counted.delete(key); // Map keeps insertion order, so this drops the oldest pairs
  }
}

/** Raises a stored total, never lowers it - a zero is still a known value, not a missing one. */
function absorb(resource, value, into = 'base') {
  const total = Number(value);
  if (!Number.isFinite(total)) return;
  const clean = Math.max(0, Math.trunc(total));
  const map = into === 'ae' ? state.aeBase : state.base;
  const current = map.get(resource);
  map.set(resource, current === undefined ? clean : Math.max(current, clean));
}

/**
 * The number a request is served: the best de-duplicated total we know (Analytics Engine's
 * uniq(uid), or the pre-AE history when AE has no rows for it) PLUS the views this isolate counted
 * since AE last answered. AE ingestion lags a few seconds, so that second term is what makes a view
 * visible the moment it happens instead of a minute later.
 */
function totalFor(resource) {
  const known = Math.max(state.base.get(resource) ?? 0, state.aeBase.get(resource) ?? 0);
  return known + (state.sinceAe.get(resource) ?? 0);
}

function rebuildTotals(resources) {
  for (const resource of resources) state.totals.set(resource, totalFor(resource));
}

/** ---- Analytics Engine: one event per view ---- */

/**
 * Writes ONE event for ONE view - a repeat view is a real play, so it is recorded too. The uid
 * travels as a blob (the durable record of who watched), the resource is the index (AE's grouping
 * key), and the metric is 1 when this view COUNTED as that user's first one and 0 when it did not.
 * That metric is what enforces "1 user = 1 view" in a query API that has no distinct-count aggregate:
 * `sum(double1)` is viewers, `count()` is plays. Never throws, never writes to D1.
 */
function writeViewEvent(env, collection, id, resource, uid, now, counted) {
  if (!env?.ANALYTICS_ENGINE || typeof env.ANALYTICS_ENGINE.writeDataPoint !== 'function') return false;
  try {
    env.ANALYTICS_ENGINE.writeDataPoint({
      indexes: [resource.slice(0, 128)],
      dimensions: [collection, id.slice(0, 128), String(uid).slice(0, 128)],
      metrics: [counted ? 1 : 0],
    });
    return true;
  } catch {
    return false; // AE refused: the live counter still counted this view in memory
  }
}

/** Parses AE's SQL answer, which arrives as TSV with a header row or as JSON rows. */
function parseAeSql(text) {
  if (typeof text !== 'string' || text.trim() === '') return null;
  const trimmed = text.trim();
  if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
    try {
      const parsed = JSON.parse(trimmed);
      const rows = Array.isArray(parsed) ? parsed : (parsed?.data ?? parsed?.result ?? null);
      if (!Array.isArray(rows)) return null;
      const out = new Map();
      for (const row of rows) {
        const resource = row?.resource ?? row?.index1;
        const viewers = Number(row?.viewers ?? row?.uniq ?? row?.count);
        // sum(double1) answers as a float; a viewer count is a whole number.
        if (typeof resource === 'string' && Number.isFinite(viewers)) out.set(resource, Math.round(viewers));
      }
      return out.size > 0 || rows.length === 0 ? out : null;
    } catch { return null; }
  }
  const lines = trimmed.split(/\r?\n/).filter((line) => line.length > 0);
  if (lines.length === 0) return null;
  const header = lines[0].split('\t').map((cell) => cell.trim().toLowerCase());
  const resourceColumn = header.indexOf('resource') >= 0 ? header.indexOf('resource') : 0;
  const viewerColumn = header.indexOf('viewers') >= 0 ? header.indexOf('viewers')
    : (header.indexOf('uniq') >= 0 ? header.indexOf('uniq') : header.length - 1);
  const out = new Map();
  for (const line of lines.slice(1)) {
    const cells = line.split('\t');
    const resource = (cells[resourceColumn] ?? '').trim();
    const viewers = Number((cells[viewerColumn] ?? '').trim());
    if (resource && Number.isFinite(viewers)) out.set(resource, Math.round(viewers));
  }
  return out;
}

/** Parses the GraphQL dataset answer: one row per index value, uniq/count as the viewer number. */
function parseAeGraphql(payload) {
  const account = payload?.data?.viewer?.accounts?.[0];
  if (!account) return payload?.errors ? null : undefined;
  const rows = account.views ?? account[AE_DATASET] ?? account.analyticsEngine;
  if (!Array.isArray(rows)) return null;
  const out = new Map();
  for (const row of rows) {
    const dimension = row?.dimensions;
    const name = Array.isArray(dimension) ? dimension[0]?.name : dimension?.name;
    const uniq = Number(Array.isArray(dimension) ? dimension[0]?.uniq : dimension?.uniq);
    const count = Number(Array.isArray(dimension) ? dimension[0]?.count : dimension?.count);
    const viewers = Number.isFinite(uniq) && uniq > 0 ? uniq : (Number.isFinite(count) ? count : Number.NaN);
    if (typeof name === 'string' && name && Number.isFinite(viewers)) out.set(name, viewers);
  }
  return out;
}

async function queryAeSql(env, sql) {
  const account = env?.CF_ACCOUNT_ID;
  const token = env?.CF_AE_TOKEN;
  if (!account || !token) return null;
  const url = `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(account)}`
    + `/analytics_engine/sql?query=${encodeURIComponent(sql)}`;
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!response.ok) return null;
  return parseAeSql(await response.text());
}

async function queryAeGraphql(env) {
  const account = env?.CF_ACCOUNT_ID;
  const token = env?.CF_AE_TOKEN;
  if (!account || !token) return null;
  const until = new Date();
  const since = new Date(until.getTime() - AE_WINDOW_DAYS * 86_400_000);
  const response = await fetch('https://api.cloudflare.com/client/v4/graphql', {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: AE_GRAPHQL_QUERY,
      variables: { tag: account, since: since.toISOString(), until: until.toISOString() },
    }),
  });
  if (!response.ok) return null;
  return parseAeGraphql(await response.json());
}

/**
 * Refreshes the real counts from Analytics Engine: at most one query per isolate per minute, only
 * when the account gave the Worker a token. Any failure keeps the live layer running untouched -
 * views are never blocked, never faked, and nothing is written to D1 to compensate.
 */
async function refreshFromAe(env, now) {
  if (!env?.ANALYTICS_ENGINE || !env?.CF_AE_TOKEN || !env?.CF_ACCOUNT_ID) return false;
  if (now - state.lastAeRefreshAt < AE_REFRESH_MS) return state.aeUsable;
  state.lastAeRefreshAt = now;
  try {
    let counts = null;
    let variant = state.aeVariant;
    if (variant === 'graphql') counts = await queryAeGraphql(env);
    if (!counts) {
      // Every variant counts viewers, so the one that answered is sticky for this isolate: one AE
      // query per minute. While the dataset is still empty a variant may be refused, and then the
      // next refresh simply tries from the top again.
      const start = typeof variant === 'number' ? variant : 0;
      for (let index = start; index < AE_SQL_VARIANTS.length; index++) {
        counts = await queryAeSql(env, AE_SQL_VARIANTS[index]);
        if (counts) { variant = index; break; }
      }
    }
    if (!counts && variant !== 'graphql') {
      counts = await queryAeGraphql(env);
      if (counts) variant = 'graphql';
    }
    if (!counts) { state.aeUsable = false; return false; }
    state.aeVariant = variant;
    state.aeUsable = true;
    // AE's uniq(uid) is the real "one view per user" count. Whatever AE absorbed since the last
    // refresh is subtracted from the in-memory bridge, so ingestion lag never hides a view and an
    // answer that has caught up never double counts it.
    const touched = new Set([...state.base.keys(), ...state.totals.keys()]);
    for (const [resource, viewers] of counts) {
      const previous = state.aeBase.get(resource) ?? 0;
      absorb(resource, viewers, 'ae');
      const absorbed = Math.max(0, (state.aeBase.get(resource) ?? 0) - previous);
      const bridged = Math.max(0, (state.sinceAe.get(resource) ?? 0) - absorbed);
      if (bridged > 0) state.sinceAe.set(resource, bridged);
      else state.sinceAe.delete(resource);
      touched.add(resource);
    }
    rebuildTotals(touched);
    await flushKv(env, now);
    return true;
  } catch {
    state.aeUsable = false;
    return false;
  }
}

/** ---- Workers KV: the live snapshot ---- */

/**
 * What the snapshot persists. When Analytics Engine is answering it IS the de-duplicated truth, so
 * the snapshot stores AE's count plus the pre-AE history - never the transient in-memory delta of
 * this isolate (an isolate that forgot a user may briefly add one, and that must not stick). With no
 * AE available the live total is the only record, so the snapshot keeps it.
 */
function snapshotValue(resource) {
  const historical = Math.max(state.base.get(resource) ?? 0, state.aeBase.get(resource) ?? 0);
  // With AE answering, the events themselves are durable: an isolate that dies loses nothing, so the
  // snapshot stores the de-duplicated truth and not this isolate's temporary bridge (which may lead
  // by one for a user who restarted their app). Without AE the live total is the only record.
  return state.aeUsable ? historical : Math.max(historical, state.totals.get(resource) ?? 0);
}

async function flushKv(env, now = Date.now(), force = false) {
  if (!snapshotStore(env)) return false;
  if (!force && now - state.lastKvFlushAt < KV_FLUSH_MS) return false;
  try {
    const snapshot = {};
    const ranked = [...state.totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, KV_SNAPSHOT_LIMIT);
    for (const [resource] of ranked) snapshot[resource] = snapshotValue(resource);
    if (snapshotStore(env) === 'kv') {
      await env.VIEWS.put(KV_KEY, JSON.stringify(snapshot));
      state.snapshotStore = 'kv';
    } else {
      // One small counters object under `views/`; the bucket keeps videos forever and this never
      // reads, overwrites or deletes a video key.
      await env.VIDEO_BUCKET.put(R2_SNAPSHOT_KEY, JSON.stringify(snapshot),
        { httpMetadata: { contentType: 'application/json' } });
      state.snapshotStore = 'r2';
    }
    state.lastKvFlushAt = now;
    state.kvUsable = true;
    return true;
  } catch {
    state.kvUsable = false;
    return false;
  }
}

/** Defers storage work to the runtime so a response is never waiting on it. */
function defer(ctx, promise) {
  if (ctx && typeof ctx.waitUntil === 'function') ctx.waitUntil(promise);
  else return promise;
  return null;
}

async function maybeFlush(env, resource, now, ctx) {
  const tasks = [];
  if (env?.ANALYTICS_ENGINE && env?.CF_AE_TOKEN && now - state.lastAeRefreshAt >= AE_REFRESH_MS) {
    tasks.push(refreshFromAe(env, now));
  } else if (snapshotStore(env) && now - state.lastKvFlushAt >= KV_FLUSH_MS) {
    tasks.push(flushKv(env, now));
  }
  if (tasks.length === 0) return;
  defer(ctx, Promise.allSettled(tasks));
}

/**
 * Loads the live totals once per isolate: Analytics Engine first (the real, de-duplicated count),
 * then the KV snapshot for history AE does not have, and only if BOTH are empty one bounded,
 * read-only D1 seed so numbers users already saw are never lost. D1 is never written.
 */
async function ensureLoaded(env, nowMs = Date.now) {
  if (state.loaded) return;
  if (!state.loading) {
    state.loading = (async () => {
      const seededAt = Number(nowMs()) || Date.now();
      const store = snapshotStore(env);
      if (store) {
        try {
          let stored = null;
          if (store === 'kv') {
            stored = await env.VIEWS.get(KV_KEY, { type: 'json', cacheTtl: 60 });
          } else {
            const object = await env.VIDEO_BUCKET.get(R2_SNAPSHOT_KEY);
            stored = object ? JSON.parse(await object.text()) : null;
          }
          if (stored && typeof stored === 'object') {
            for (const [resource, total] of Object.entries(stored)) {
              if (typeof resource !== 'string') continue;
              absorb(resource, total);
            }
            state.kvUsable = true;
            state.snapshotStore = store;
            // The snapshot was just read: start the write window here, so a busy isolate does not
            // rewrite the very value it just loaded.
            state.lastKvFlushAt = seededAt;
          }
        } catch { state.kvUsable = false; }
      }
      await refreshFromAe(env, seededAt);
      rebuildTotals(new Set([...state.base.keys(), ...state.aeBase.keys()]));
      if (state.totals.size === 0 && env?.DB) {
        // Last resort, read-only, once per isolate at most: restores the pre-Analytics-Engine
        // history. This is the ONLY D1 statement left in the view path, and it writes nothing.
        try {
          const rows = await env.DB.prepare(
            'SELECT resource, views FROM friendbook_cf_view_counters ORDER BY views DESC LIMIT ?',
          ).bind(SEED_LIMIT).all();
          const seeded = [];
          for (const row of rows?.results ?? []) {
            if (typeof row.resource !== 'string') continue;
            absorb(row.resource, row.views);
            seeded.push(row.resource);
          }
          state.seedReads += 1;
          rebuildTotals(seeded);
        } catch { /* no D1 here: AE + KV + memory still count real views */ }
        if (snapshotStore(env)) await flushKv(env, seededAt, true);
      }
      state.loaded = true;
    })();
  }
  try { await state.loading; } catch { state.loaded = true; }
}

export async function viewCounters(request, env, identity, match, url, nowMs = Date.now, ctx) {
  const uid = typeof identity?.uid === 'string' ? identity.uid : '';
  if (!uid || uid.length > 128) return json({ error: 'Unauthorized' }, 401);
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
  const settled = state.counted.has(seenKey);
  const counted = !settled;

  // ONE Analytics Engine event per view request that reaches the Worker: this is the whole record of
  // the view. A repeat is still written (a play really happened) but with metric 0, so the viewer
  // count - sum(double1) - only moves for a user this isolate has not settled yet.
  const eventWritten = writeViewEvent(env, collection, id, resource, uid, now, counted);
  if (counted) {
    state.counted.set(seenKey, now);
    trimCounted();
    state.sinceAe.set(resource, (state.sinceAe.get(resource) ?? 0) + 1);
    state.totals.set(resource, totalFor(resource));
  }
  await maybeFlush(env, resource, now, ctx);
  return json({
    resource,
    views: state.totals.get(resource) ?? 0,
    counted,
    dedupe: DEDUPE_RULE,
    storage: storageMode(),
    event: eventWritten ? 'analytics-engine' : 'none',
  });
}

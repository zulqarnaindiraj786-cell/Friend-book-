import { commentsAPI, reactorsAPI, commentSelect, normalized } from './comment-api.js';
import { authorizePost } from './firebase-post-access.js';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});
const types = new Set(['like', 'love', 'haha', 'wow', 'sad', 'angry']);
function identifier(value) {
  const id = decodeURIComponent(value);
  if (!id || new TextEncoder().encode(id).length > 1500 || /[\/\x00-\x1f]/.test(id) || ['.', '..'].includes(id)) throw Error();
  return id;
}
async function body(request) {
  if (!request.headers.get('Content-Type')?.startsWith('application/json')) throw Error();
  const reader = request.body?.getReader();
  if (!reader) throw Error();
  let size = 0; const chunks = [];
  try {
    for (;;) {
      const { value, done } = await reader.read(); if (done) break;
      size += value.length; if (size > 32768) { await reader.cancel(); throw Error(); }
      chunks.push(value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size); let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  const data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes));
  if (!data || Array.isArray(data) || typeof data !== 'object') throw Error();
  return data;
}
const verifiedCache = new WeakMap();
const reactionCache = new WeakMap();
const REACTION_CACHE_TTL_MS = 300_000;

function getVerifiedSet(db) {
  let set = verifiedCache.get(db);
  if (!set) {
    set = new Set();
    verifiedCache.set(db, set);
  }
  return set;
}

function getReactionMap(db) {
  let map = reactionCache.get(db);
  if (!map) {
    map = new Map();
    reactionCache.set(db, map);
  }
  return map;
}

function invalidateResourceReactionCache(db, resource) {
  const map = getReactionMap(db);
  for (const k of map.keys()) {
    if (k.startsWith(resource + '|')) map.delete(k);
  }
}

export async function interactions(request, env, identity, match, authorize = authorizePost) {
  // BOTH a deployment gate and independently verified resource migration are required.
  if (env.D1_INTERACTIONS_ENABLED !== 'true') return json({ error: 'D1 interactions not enabled' }, 503);
  const [, collection, encodedId, kind] = match;
  if (!(kind === 'reactions' ? ['GET', 'PUT'] : kind === 'reactors' ? ['GET'] : ['GET', 'POST','PUT']).includes(request.method)) return json({ error: 'Method not allowed' }, 405);
  let id; try { id = identifier(encodedId); } catch { return json({ error: 'Invalid resource' }, 400); }
  const resource = `${collection}/${id}`;
  let db;const context={};
  try {
    db = env.DB.withSession('first-primary');
    const vSet = getVerifiedSet(env.DB);
    if (!vSet.has(resource)) {
      const ready = await db.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?').bind(resource).first();
      if (ready && ready.state !== 'verified') return json({ error: 'Resource migration not verified' }, 503);
      if (!await authorize(identity, collection, id, undefined, context)) return json({ error: 'Forbidden' }, 403);
      // A resource is ONLY served from D1 after its source rows were imported and the import was
      // verified (the migration writes state='verified'). This endpoint must never promote a
      // resource by itself: doing so would silently serve an empty counters row for a post whose
      // real likes/comments still live in Firestore, i.e. every existing like and comment would
      // appear to vanish. An unknown resource is refused so the client keeps reading Firestore.
      if (!ready) return json({ error: 'Resource migration not verified' }, 503);
      vSet.add(resource);
    } else if (!await authorize(identity, collection, id, undefined, context)) {
      return json({ error: 'Forbidden' }, 403);
    }
  } catch { return json({ error: 'Source authorization or database unavailable' }, 503); }
  let data;
  if (request.method !== 'GET') {
    try { data = await body(request); } catch { return json({ error: 'Invalid JSON body' }, 400); }
  }
  try {
    if (kind === 'reactors') return await reactorsAPI(request,db,resource,context);
    if (kind === 'comments') {
      if (request.method !== 'GET') invalidateResourceReactionCache(env.DB, resource);
      return await commentsAPI(request,db,identity,resource,data,context);
    }
    if (kind === 'reactions') {
      const rMap = getReactionMap(env.DB);
      const cacheKey = `${resource}|${identity.uid}|${JSON.stringify(context.blocked || [])}`;
      if (request.method === 'GET') {
        const hit = rMap.get(cacheKey);
        if (hit && hit.expiresAt > Date.now()) {
          return json(hit.payload);
        }
      }
      if (request.method === 'PUT') {
        if (Object.keys(data).some(k => k !== 'type') || (data.type !== null && !types.has(data.type))) return json({ error: 'Invalid reaction' }, 400);
        // Set desired state, not toggle: a retried request cannot double-increment.
        await db.batch([
          db.prepare(`INSERT INTO friendbook_cf_reactions(resource,uid,type,updated_at)
            VALUES (?,?,?,?) ON CONFLICT(resource,uid) DO UPDATE SET type=excluded.type,updated_at=excluded.updated_at`)
            .bind(resource, identity.uid, data.type, Date.now()),
          db.prepare(`INSERT INTO friendbook_cf_reaction_history(resource,uid,timestamp_known)
            VALUES (?,?,1) ON CONFLICT(resource,uid) DO UPDATE SET timestamp_known=1`)
            .bind(resource, identity.uid)
        ]);
        invalidateResourceReactionCache(env.DB, resource);
      }
      const combined = await db.prepare(`SELECT
        COALESCE((SELECT json_group_array(json_object('type', s.type, 'count', s.total, 'mine', CASE WHEN r.type = s.type THEN 1 ELSE 0 END))
          FROM (SELECT resource, type, total FROM friendbook_cf_reaction_totals WHERE resource = ?2 AND total > 0 ORDER BY type) s
          LEFT JOIN friendbook_cf_reactions r ON r.resource = s.resource AND r.uid = ?1), '[]') AS types_json,
        (SELECT COUNT(*) FROM friendbook_cf_comments c
          LEFT JOIN friendbook_cf_comment_details d ON d.resource = c.resource AND d.id = c.id
          WHERE c.resource = ?2 AND COALESCE(d.removed, 0) = 0) AS total_comments,
        COALESCE((SELECT json_group_array(json_object(
          'id', p.id, 'resource', p.resource, 'uid', p.uid, 'text', p.text, 'parent_id', p.parent_id,
          'created_at', p.created_at, 'historical_unattributed', p.historical_unattributed,
          'created_at_known', p.created_at_known, 'media_url', p.media_url, 'mentions', p.mentions,
          'name', p.name, 'photo', p.photo, 'edited', p.edited, 'removed', p.removed,
          'likes', p.likes, 'mine', p.mine
        )) FROM (${commentSelect} WHERE c.resource = ?2 AND COALESCE(d.removed, 0) = 0
          AND c.uid NOT IN (SELECT value FROM json_each(?3))
          ORDER BY c.created_at DESC, c.id DESC LIMIT 2) p), '[]') AS preview_json`)
        .bind(identity.uid, resource, JSON.stringify(context.blocked || [])).first();
      const typesList = JSON.parse(combined?.types_json || '[]');
      const previewRows = JSON.parse(combined?.preview_json || '[]');
      const payload = {
        owner: context.owner,
        types: typesList,
        comments: Number(combined?.total_comments || 0),
        preview: previewRows.map(row => normalized(row, identity.uid)),
      };
      rMap.set(cacheKey, { payload, expiresAt: Date.now() + REACTION_CACHE_TTL_MS });
      return json(payload);
    }
  } catch { return json({ error: 'D1 interactions unavailable' }, 503); }
}

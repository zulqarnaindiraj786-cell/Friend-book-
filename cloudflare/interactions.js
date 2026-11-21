import { commentsAPI, reactorsAPI, commentSelect, normalized, legacyCommentCount, legacyCommentCountStatement, legacyPage } from './comment-api.js';
import { requestAccountReads } from './app-documents.js';
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
const reactionTypesSql = `COALESCE((SELECT json_group_array(json_object('type', s.type, 'count', s.total, 'mine', CASE WHEN r.type = s.type THEN 1 ELSE 0 END))
      FROM (SELECT resource, type, total FROM friendbook_cf_reaction_totals WHERE resource = ?2 AND total > 0 ORDER BY type) s
      LEFT JOIN friendbook_cf_reactions r ON r.resource = s.resource AND r.uid = ?1), '[]')`;

// Shared by explicit post details and the bounded Home response; no writes here.
function engagementStatement(db, resource, uid, blocked, useTotals = true) {
  // Seek each blocked author instead of scanning the resource's entire comment
  // history to subtract a usually tiny subset. DISTINCT prevents double counts.
  const blockedCount = `(SELECT COUNT(*) FROM (SELECT DISTINCT value FROM json_each(?3)) blocked
      CROSS JOIN friendbook_cf_comments c INDEXED BY idx_cf_comment_resource_author
        ON c.resource=?2 AND c.uid=blocked.value
      LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
      WHERE COALESCE(d.removed,0)=0)`;
  const count = useTotals ? `COALESCE((SELECT total FROM friendbook_cf_visible_comment_totals WHERE resource=?2),0)
        ${blocked.length ? `- ${blockedCount}` : ''}` : `(SELECT COUNT(*) FROM friendbook_cf_comments c
      LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
      WHERE c.resource=?2 AND COALESCE(d.removed,0)=0
        AND c.uid NOT IN (SELECT value FROM json_each(?3)))`;
  return db.prepare(`SELECT
    ${reactionTypesSql} AS types_json,
    ${count} AS total_comments,
    COALESCE((SELECT json_group_array(json_object(
      'id', p.id, 'resource', p.resource, 'uid', p.uid, 'text', p.text, 'parent_id', p.parent_id,
      'created_at', p.created_at, 'historical_unattributed', p.historical_unattributed,
      'created_at_known', p.created_at_known, 'media_url', p.media_url, 'mentions', p.mentions,
      'name', p.name, 'photo', p.photo, 'edited', p.edited, 'removed', p.removed,
      'likes', p.likes, 'mine', p.mine
    )) FROM (${commentSelect.replace('r.uid=?', 'r.uid=?1')} WHERE c.resource = ?2 AND COALESCE(d.removed, 0) = 0
      AND c.uid NOT IN (SELECT value FROM json_each(?3))
      ORDER BY c.created_at DESC, c.id DESC LIMIT 2) p), '[]') AS preview_json`)
    .bind(uid, resource, JSON.stringify(blocked || []));
}

export async function engagementSummary(db, resource, uid, owner, blocked = [], preloaded = null) {
  const canonicalSummary = async () => {
    try {
      return await engagementStatement(db,resource,uid,blocked).first();
    } catch (error) {
      if (!/no such (table: friendbook_cf_visible_comment_totals|index: idx_cf_comment_resource_author)/.test(String(error))) throw error;
      return engagementStatement(db,resource,uid,blocked,false).first();
    }
  };
  const legacySummary = async () => {
    const count = preloaded ? preloaded.count : await legacyCommentCount(db,resource,blocked);
    return {count,preview:count ? await legacyPage(db,resource,uid,blocked,[Number.MAX_SAFE_INTEGER,'']) : []};
  };
  const [combined,legacy] = await Promise.all([preloaded ? preloaded.canonical : canonicalSummary(),legacySummary()]);
  const typesList = JSON.parse(combined?.types_json || '[]');
  const previewRows = JSON.parse(combined?.preview_json || '[]');
  const preview = [...previewRows.map(row => normalized(row, uid)), ...legacy.preview]
    .sort((a,b)=>(b.createdAt||0)-(a.createdAt||0) || (a.id<b.id?1:a.id>b.id?-1:0)).slice(0,2);
  return {
    owner: owner,
    types: typesList,
    comments: Number(combined?.total_comments || 0) + legacy.count,
    preview,
  };
}

export const MAX_FEED_ENGAGEMENT_PAGE = 20;

// A small authorized Home page can fetch summary rows in one D1 round trip.
// Preserve the existing individual read path for incomplete schemas and failures.
export async function batchEngagementReads(db, resources, uid, blocked) {
  if (resources.length < 2 || resources.length > MAX_FEED_ENGAGEMENT_PAGE || typeof db.batch !== 'function') return new Map();
  try {
    const rows=await db.batch(resources.flatMap(resource=>[
      engagementStatement(db,resource,uid,blocked),legacyCommentCountStatement(db,resource,blocked),
    ]));
    if(rows.length!==resources.length*2 || rows.some(row=>row.success===false || !row.results?.[0]))return new Map();
    return new Map(resources.map((resource,i)=>[resource,{canonical:rows[i*2].results[0],count:Number(rows[i*2+1].results[0].n)||0}]));
  } catch { return new Map(); }
}

export async function interactions(request, env, identity, match, authorize = authorizePost) {
  // BOTH a deployment gate and independently verified resource migration are required.
  if (env.D1_INTERACTIONS_ENABLED !== 'true') return json({ error: 'D1 interactions not enabled' }, 503);
  const [, collection, encodedId, kind] = match;
  if (!(kind === 'reactions' ? ['GET', 'PUT'] : kind === 'reactors' ? ['GET'] : ['GET', 'POST','PUT']).includes(request.method)) return json({ error: 'Method not allowed' }, 405);
  let id; try { id = identifier(encodedId); } catch { return json({ error: 'Invalid resource' }, 400); }
  const resource = `${collection}/${id}`;
  let db;const context={};
  const accountReads=requestAccountReads(identity);
  try {
    db = env.DB.withSession('first-primary');
    // Keep a primary-first session, but overlap the independent readiness and access reads.
    const [ready,allowed] = await Promise.all([
      db.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?').bind(resource).first(),
      authorize({...identity,db}, collection, id, undefined, context, accountReads),
    ]);
    if (ready && ready.state !== 'verified') return json({ error: 'Resource migration not verified' }, 503);
    if (!allowed) return json({ error: 'Forbidden' }, 403);
    if (!ready) {
      // Keep the insert-or-observe check atomic and in one D1 round trip.
      const [, observed] = await db.batch([
        db.prepare("INSERT INTO friendbook_cf_interaction_resources(resource, state) VALUES (?, 'verified') ON CONFLICT(resource) DO NOTHING").bind(resource),
        db.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?').bind(resource),
      ]);
      const current = observed.results?.[0];
      if (!current || current.state !== 'verified') return json({ error: 'Resource migration not verified' }, 503);
    }
  } catch { return json({ error: 'Source authorization or database unavailable' }, 503); }
  let data;
  if (request.method !== 'GET') {
    try { data = await body(request); } catch { return json({ error: 'Invalid JSON body' }, 400); }
  }
  try {
    if (kind === 'reactors') return await reactorsAPI(request,db,resource,context);
    if (kind === 'comments') {
      context.reactionTotals=async()=>JSON.parse((await db.prepare(`SELECT ${reactionTypesSql} AS types_json`).bind(identity.uid,resource).first()).types_json);
      return await commentsAPI(request,db,identity,resource,data,context);
    }
    if (kind === 'reactions') {
      if (request.method === 'PUT') {
        if (Object.keys(data).some(k => k !== 'type') || (data.type !== null && !types.has(data.type))) return json({ error: 'Invalid reaction' }, 400);
        // Set desired state, not toggle: a retried request cannot double-increment.
        const writes = [
          db.prepare(`INSERT INTO friendbook_cf_reactions(resource,uid,type,updated_at)
            VALUES (?,?,?,?) ON CONFLICT(resource,uid) DO UPDATE SET type=excluded.type,updated_at=excluded.updated_at
            WHERE friendbook_cf_reactions.type IS NOT excluded.type OR NOT EXISTS (
              SELECT 1 FROM friendbook_cf_reaction_history h WHERE h.resource=excluded.resource AND h.uid=excluded.uid AND h.timestamp_known=1
            )`)
            .bind(resource, identity.uid, data.type, Date.now()),
          db.prepare(`INSERT INTO friendbook_cf_reaction_history(resource,uid,timestamp_known)
            VALUES (?,?,1) ON CONFLICT(resource,uid) DO UPDATE SET timestamp_known=1 WHERE friendbook_cf_reaction_history.timestamp_known IS NOT 1`)
            .bind(resource, identity.uid)
        ];
        if (new URL(request.url).searchParams.get('response') === 'reaction') {
          // One D1 batch: the acknowledgement cannot fail in a separate comment read
          // after committing the reaction. Older clients still receive the full summary.
          const results = await db.batch([...writes, db.prepare(`SELECT ${reactionTypesSql} AS types_json`)
            .bind(identity.uid, resource)]);
          return json({ reactionOnly: true, owner: context.owner,
            types: JSON.parse(results[2].results[0].types_json) });
        }
        await db.batch(writes);
      }
      return json(await engagementSummary(db, resource, identity.uid, context.owner, context.blocked || []));
    }
  } catch { return json({ error: 'D1 interactions unavailable' }, 503); }
}

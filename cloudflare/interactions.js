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
export async function interactions(request, env, identity, match, authorize = authorizePost) {
  // BOTH a deployment gate and independently verified resource migration are required.
  if (env.D1_INTERACTIONS_ENABLED !== 'true') return json({ error: 'D1 interactions not enabled' }, 503);
  const [, collection, encodedId, kind] = match;
  if (!(kind === 'reactions' ? ['GET', 'PUT'] : kind === 'reactors' ? ['GET'] : ['GET', 'POST','PUT']).includes(request.method)) return json({ error: 'Method not allowed' }, 405);
  let id; try { id = identifier(encodedId); } catch { return json({ error: 'Invalid resource' }, 400); }
  let db;const context={};
  try {
    db = env.DB.withSession('first-primary');
    const ready = await db.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?').bind(`${collection}/${id}`).first();
    if (ready?.state !== 'verified') return json({ error: 'Resource migration not verified' }, 503);
    if (!await authorize(identity, collection, id, undefined, context)) return json({ error: 'Forbidden' }, 403);
  } catch { return json({ error: 'Source authorization or database unavailable' }, 503); }
  const resource = `${collection}/${id}`;
  let data;
  if (request.method !== 'GET') {
    try { data = await body(request); } catch { return json({ error: 'Invalid JSON body' }, 400); }
  }
  try {
    if (kind === 'reactors') return await reactorsAPI(request,db,resource);
    if (kind === 'comments') return await commentsAPI(request,db,identity,resource,data,context);
    if (kind === 'reactions') {
      if (request.method === 'PUT') {
        if (Object.keys(data).some(k => k !== 'type') || (data.type !== null && !types.has(data.type))) return json({ error: 'Invalid reaction' }, 400);
        // Set desired state, not toggle: a retried request cannot double-increment.
        await db.prepare(`INSERT INTO friendbook_cf_reactions(resource,uid,type,updated_at)
          VALUES (?,?,?,?) ON CONFLICT(resource,uid) DO UPDATE SET type=excluded.type,updated_at=excluded.updated_at`)
          .bind(resource, identity.uid, data.type, Date.now()).run();
      }
      const summary = await db.prepare(`SELECT s.type,s.total AS count,CASE WHEN r.type=s.type THEN 1 ELSE 0 END AS mine
        FROM friendbook_cf_reaction_totals s LEFT JOIN friendbook_cf_reactions r ON r.resource=s.resource AND r.uid=?
        WHERE s.resource=? AND s.total>0 ORDER BY s.type`).bind(identity.uid, resource).all();
      const count=await db.prepare(`SELECT COUNT(*) AS total FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id WHERE c.resource=? AND COALESCE(d.removed,0)=0`).bind(resource).first();
      const preview=await db.prepare(commentSelect+` WHERE c.resource=? AND COALESCE(d.removed,0)=0 AND c.uid NOT IN (SELECT value FROM json_each(?)) ORDER BY c.created_at DESC,c.id DESC LIMIT 2`).bind(identity.uid,resource,JSON.stringify(context.blocked||[])).all();
      return json({ owner:context.owner,types: summary.results,comments:count.total,preview:preview.results.map(row=>normalized(row,identity.uid)) });
    }
  } catch { return json({ error: 'D1 interactions unavailable' }, 503); }
}

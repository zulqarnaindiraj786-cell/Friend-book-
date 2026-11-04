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
  if (!(kind === 'reactions' ? ['GET', 'PUT'] : ['GET', 'POST']).includes(request.method)) return json({ error: 'Method not allowed' }, 405);
  let id; try { id = identifier(encodedId); } catch { return json({ error: 'Invalid resource' }, 400); }
  let db;
  try {
    db = env.DB.withSession('first-primary');
    const ready = await db.prepare('SELECT state FROM friendbook_cf_interaction_resources WHERE resource = ?').bind(`${collection}/${id}`).first();
    if (ready?.state !== 'verified') return json({ error: 'Resource migration not verified' }, 503);
    if (!await authorize(identity, collection, id)) return json({ error: 'Forbidden' }, 403);
  } catch { return json({ error: 'Source authorization or database unavailable' }, 503); }
  const resource = `${collection}/${id}`;
  let data;
  if (request.method !== 'GET') {
    try { data = await body(request); } catch { return json({ error: 'Invalid JSON body' }, 400); }
  }
  try {
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
      return json({ types: summary.results });
    }
    if (request.method === 'POST') {
      if (Object.keys(data).some(k => !['id', 'text', 'parentId'].includes(k)) ||
          !/^[a-zA-Z0-9_-]{1,128}$/.test(data.id || '') || typeof data.text !== 'string' || !data.text.trim() || data.text.length > 5000 ||
          (data.parentId != null && !/^[a-zA-Z0-9_-]{1,128}$/.test(data.parentId))) return json({ error: 'Invalid comment' }, 400);
      const parent = data.parentId || null;
      if (parent && !await db.prepare('SELECT id FROM friendbook_cf_comments WHERE resource=? AND id=?').bind(resource, parent).first()) return json({ error: 'Parent not found' }, 400);
      // Immutable client ID makes duplicate delivery safe; conflicting payloads are rejected.
      await db.prepare(`INSERT INTO friendbook_cf_comments(resource,id,uid,text,parent_id,created_at)
        VALUES (?,?,?,?,?,?) ON CONFLICT(resource,id) DO NOTHING`).bind(resource, data.id, identity.uid, data.text, parent, Date.now()).run();
      const saved = await db.prepare('SELECT * FROM friendbook_cf_comments WHERE resource=? AND id=?').bind(resource, data.id).first();
      if (saved?.uid !== identity.uid || saved.text !== data.text || saved.parent_id !== parent) return json({ error: 'Comment ID conflict' }, 409);
      return json({ comment: saved });
    }
    let before = [Number.MAX_SAFE_INTEGER, ''];
    const cursor = new URL(request.url).searchParams.get('before');
    if (cursor !== null) {
      try {
        if (cursor.length > 512) throw Error();
        before = JSON.parse(atob(cursor));
        if (!Array.isArray(before) || before.length !== 2 || !Number.isSafeInteger(before[0]) || before[0] < 0 || !/^[a-zA-Z0-9_-]{1,128}$/.test(before[1])) throw Error();
      } catch { return json({ error: 'Invalid cursor' }, 400); }
    }
    const page = await db.prepare(`SELECT * FROM friendbook_cf_comments WHERE resource=?
      AND (created_at < ? OR (created_at = ? AND id < ?)) ORDER BY created_at DESC,id DESC LIMIT 50`)
      .bind(resource, before[0], before[0], before[1]).all();
    const last = page.results.at(-1);
    return json({ comments: page.results, next: page.results.length === 50 ? btoa(JSON.stringify([last.created_at, last.id])) : null });
  } catch { return json({ error: 'D1 interactions unavailable' }, 503); }
}

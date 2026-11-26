import { commentsAPI, reactorsAPI, commentSelect, normalized, legacyCommentCount, legacyCommentCountsStatement, legacyCommentCountsWithCandidatesStatement, legacyPage } from './comment-api.js';
import { legacyEpoch, legacyNoCandidates, rememberLegacyNoCandidates, legacyCachedCount, rememberLegacyCount } from './legacy-comment-negative-cache.js';
import { requestAccountReads } from './app-documents.js';
import { authorizePost } from './firebase-post-access.js';
const json = (body, status = 200) => Response.json(body, {
  status, headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
});
const types = new Set(['like', 'love', 'haha', 'wow', 'sad', 'angry']);
async function stableEventId(prefix,fields) {
  const encoder=new TextEncoder(),digest=new Uint8Array(await crypto.subtle.digest('SHA-256',
    new Uint8Array(fields.flatMap(value=>{const bytes=encoder.encode(value);return [bytes.length>>>24,(bytes.length>>>16)&255,(bytes.length>>>8)&255,bytes.length&255,...bytes];}))));
  return prefix+[...digest].map(byte=>byte.toString(16).padStart(2,'0')).join('');
}
const reactionNotificationId=(actor,recipient,type,resource)=>stableEventId('action_',[actor,recipient,type,resource]);
const commentNotificationId=(actor,recipient,type,resource,commentId)=>stableEventId('comment_',
  [actor,recipient,type==='reply'?'reply':'comment',resource,commentId]);
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
// The actor has at most one reaction per resource. An uncorrelated scalar
// lookup is evaluated once per statement, rather than joining the same actor
// row once for each nonzero type. Both reads stay in the same D1 snapshot.
const reactionTypesSql = `COALESCE((SELECT json_group_array(json_object('type', s.type, 'count', s.total, 'mine', CASE WHEN
      (SELECT type FROM friendbook_cf_reactions WHERE resource=?2 AND uid=?1) = s.type THEN 1 ELSE 0 END))
      FROM (SELECT type, total FROM friendbook_cf_reaction_totals WHERE resource = ?2 AND total > 0 ORDER BY type) s), '[]')`;

// Shared by explicit post details and the bounded Home response; no writes here.
// Evaluate the visible count once in a non-flattened one-row subquery. If no
// visible canonical comments exist, do not scan their removed/blocked history
// merely to discover an empty two-comment preview. Legacy previews stay separate.
function engagementStatement(db, resource, uid, blocked, useTotals = true) {
  // Seek each blocked author instead of scanning the resource's entire comment
  // history to subtract a usually tiny subset. DISTINCT prevents double counts.
  const blockedCount = `(SELECT COUNT(*) FROM (SELECT DISTINCT value FROM json_each(?3)) blocked
      CROSS JOIN friendbook_cf_comments c INDEXED BY idx_cf_comment_resource_author
        ON c.resource=?2 AND c.uid=blocked.value
      LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
      WHERE COALESCE(d.removed,0)=0)`;
  const storedTotal = `COALESCE((SELECT total FROM friendbook_cf_visible_comment_totals WHERE resource=?2),0)`;
  // A known zero visible total cannot contain a visible blocked comment.
  // Skip scanning removed history for every blocked author in that case.
  // LIMIT prevents flattening/re-evaluating the totals lookup.
  const count = useTotals ? (blocked.length ? `(SELECT CASE WHEN visible_total=0 THEN 0
        ELSE visible_total - ${blockedCount} END FROM (SELECT ${storedTotal} AS visible_total LIMIT 1))`
        : storedTotal) : `(SELECT COUNT(*) FROM friendbook_cf_comments c
      LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
      WHERE c.resource=?2 AND COALESCE(d.removed,0)=0
        AND c.uid NOT IN (SELECT value FROM json_each(?3)))`;
  return db.prepare(`SELECT
    ${reactionTypesSql} AS types_json,
    engagement_count.total_comments AS total_comments,
    CASE WHEN engagement_count.total_comments > 0 THEN COALESCE((SELECT json_group_array(json_object(
      'id', p.id, 'resource', p.resource, 'uid', p.uid, 'text', p.text, 'parent_id', p.parent_id,
      'created_at', p.created_at, 'historical_unattributed', p.historical_unattributed,
      'created_at_known', p.created_at_known, 'media_url', p.media_url, 'mentions', p.mentions,
      'name', p.name, 'photo', p.photo, 'edited', p.edited, 'removed', p.removed,
      'likes', COALESCE(json_extract(p.reaction_summary,'$.likes'),0)+COALESCE(p.historical_unattributed,0),
      'mine', json_extract(p.reaction_summary,'$.mine')
    )) FROM (${commentSelect} WHERE c.resource = ?2 AND COALESCE(d.removed, 0) = 0
      AND c.uid NOT IN (SELECT value FROM json_each(?3))
      ORDER BY c.created_at DESC, c.id DESC LIMIT 2) p), '[]') ELSE '[]' END AS preview_json
    FROM (SELECT ${count} AS total_comments LIMIT 1) engagement_count`)
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
    const count=preloaded?preloaded.count:await legacyCommentCount(db,resource,blocked);
    return {count,preview:count ? await legacyPage(db,resource,uid,blocked,[Number.MAX_SAFE_INTEGER,''],null,2) : []};
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
// Production D1 rejects ten UNION ALL terms; five were verified against the
// live engine. Keep all chunks in the same D1 batch/snapshot.
const LEGACY_COUNT_CHUNK_SIZE = 5;

// A small authorized Home page can fetch summary rows in one D1 round trip.
// Preserve the existing individual read path for incomplete schemas and failures.
export async function batchEngagementReads(db, resources, uid, blocked, cacheScope = null) {
  if (resources.length < 2 || resources.length > MAX_FEED_ENGAGEMENT_PAGE || typeof db.batch !== 'function') return new Map();
  // Each validation uses a *new* primary-first session: after a concurrent
  // external write, a later read on the original session could otherwise hit
  // a replica that has only caught up to the first epoch's bookmark.
  const freshEpoch=async()=>{
    if(!cacheScope || typeof cacheScope.withSession!=='function')return null;
    try {return await legacyEpoch(cacheScope.withSession('first-primary'));}
    catch {return null;}
  };
  const read=async(useHints)=>{
    const epoch=useHints?await freshEpoch():null;
    const countsByResource=new Map(resources.map(resource=>[
      resource,legacyCachedCount(cacheScope,resource,blocked,epoch)??0
    ]));
    const toProbe=resources.filter(resource=>!legacyNoCandidates(cacheScope,resource,epoch)
      && legacyCachedCount(cacheScope,resource,blocked,epoch)===undefined);
    const withCandidates=Boolean(epoch) && toProbe.length>0;
    const statements=[...resources.map(resource=>engagementStatement(db,resource,uid,blocked))];
    const chunks=[];
    for(let offset=0;offset<toProbe.length;offset+=LEGACY_COUNT_CHUNK_SIZE){
      const chunk=toProbe.slice(offset,offset+LEGACY_COUNT_CHUNK_SIZE);chunks.push(chunk);
      statements.push(withCandidates?legacyCommentCountsWithCandidatesStatement(db,chunk,blocked):legacyCommentCountsStatement(db,chunk,blocked));
    }
    const rows=await db.batch(statements);
    // A concurrent root-comment write between the epoch read and this batch
    // invalidates every cached or skipped count. Retry the complete count batch.
    if(epoch && toProbe.length<resources.length && await freshEpoch()!==epoch)
      throw Error('Legacy comment epoch changed during batch');
    if(rows.length!==resources.length+chunks.length || rows.some(row=>row.success===false)
        || rows.slice(0,resources.length).some(row=>!row.results?.[0]))return new Map();
    const negatives=[];
    for(let ci=0;ci<chunks.length;ci++){
      const chunk=chunks[ci],counts=rows[resources.length+ci]?.results;
      if(!counts || counts.length!==chunk.length)return new Map();
      const bySlot=new Map(counts.map(row=>[row.slot,row]));
      if(bySlot.size!==chunk.length || chunk.some((_,i)=>!bySlot.has(i)))return new Map();
      for(let i=0;i<chunk.length;i++){
        const row=bySlot.get(i);
        if(!Number.isSafeInteger(row.n) || row.n<0)return new Map();
        countsByResource.set(chunk[i],row.n);
        if(withCandidates){
          rememberLegacyCount(cacheScope,chunk[i],blocked,epoch,row.n);
          if(row.has_candidates===0 && row.n===0)negatives.push(chunk[i]);
        }
      }
    }
    for(const resource of negatives)rememberLegacyNoCandidates(cacheScope,resource,epoch);
    return new Map(resources.map((resource,i)=>[resource,{canonical:rows[i].results[0],count:countsByResource.get(resource)}]));
  };
  try { return await read(!!cacheScope); }
  catch {
    // Older schema or an unavailable optional hint must keep the exact original
    // grouped-count path, without masking failure of its actual reads.
    if(cacheScope)try {return await read(false);} catch { /* existing fallback below */ }
    return new Map();
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
      context.commentNotifications=async(commentId,replyTo)=>{
        if(!data.notify)return [];
        const recipients=[];
        if(context.owner && context.owner!==identity.uid)recipients.push({to:context.owner,type:collection==='posts'?'comment':'reel_comment',text:`${context.name||''} commented on your ${collection==='posts'?'post':'reel'}`});
        if(replyTo && replyTo!==identity.uid && replyTo!==context.owner)recipients.push({to:replyTo,type:collection==='posts'?'reply':'reel_comment_reply',text:`${context.name||''} replied to your comment`});
        const createdAt=Date.now(),events=[];
        for(const recipient of recipients){
          const eventType=recipient.type.includes('reply')?'reply':'comment';
          const eventId=await commentNotificationId(identity.uid,recipient.to,eventType,resource,commentId);
          events.push({id:eventId,toUserId:recipient.to,fromUserId:identity.uid,fromUserName:context.name||'',
            fromUserPhotoUrl:context.photo||'',type:recipient.type,...(collection==='reels'?{contentType:'reel'}:{}),
            text:recipient.text,postId:id,isRead:false,createdAt});
        }
        return events;
      };
      return await commentsAPI(request,db,identity,resource,data,context);
    }
    if (kind === 'reactions') {
      if (request.method === 'PUT') {
        if (Object.keys(data).some(k => !['type','notify'].includes(k))
            || (data.type !== null && !types.has(data.type))
            || (data.notify !== undefined && typeof data.notify !== 'boolean')) return json({ error: 'Invalid reaction' }, 400);
        // Set desired state, not toggle: a retried request cannot double-increment.
        // Missing history already means a known timestamp to reactorsAPI. Keep
        // only legacy explicit unknown markers, and transition them atomically
        // with the reaction when the user performs a fresh action.
        const writes = [
          db.prepare(`INSERT INTO friendbook_cf_reactions(resource,uid,type,updated_at)
            SELECT ?1,?2,?3,?4 WHERE ?3 IS NOT NULL
              OR EXISTS (SELECT 1 FROM friendbook_cf_reactions WHERE resource=?1 AND uid=?2)
              OR EXISTS (SELECT 1 FROM friendbook_cf_reaction_history WHERE resource=?1 AND uid=?2 AND timestamp_known=0)
            ON CONFLICT(resource,uid) DO UPDATE SET type=excluded.type,updated_at=excluded.updated_at
            WHERE friendbook_cf_reactions.type IS NOT excluded.type OR EXISTS (
              SELECT 1 FROM friendbook_cf_reaction_history h WHERE h.resource=excluded.resource AND h.uid=excluded.uid AND h.timestamp_known=0
            )`)
            .bind(resource, identity.uid, data.type, Date.now()),
          db.prepare(`UPDATE friendbook_cf_reaction_history SET timestamp_known=1
            WHERE resource=? AND uid=? AND timestamp_known=0`)
            .bind(resource, identity.uid)
        ];
        if (new URL(request.url).searchParams.get('response') === 'reaction') {
          // The optional inbox event is committed in this same D1 batch as the
          // absolute reaction. The server derives its recipient and sender from
          // the authorized resource/request, never from client-supplied identity.
          const notificationId=(data.notify===true || new URL(request.url).searchParams.get('notify')==='1')
            && context.owner && context.owner!==identity.uid
            ? await reactionNotificationId(identity.uid,context.owner,
                collection==='posts'?'like':'reel_like',id) : null;
          const notificationTime=Date.now();
          const notification=notificationId?{
            toUserId:context.owner,fromUserId:identity.uid,fromUserName:context.name||'',
            fromUserPhotoUrl:context.photo||'',type:collection==='posts'?'like':'reel_like',
            ...(collection==='reels'?{contentType:'reel'}:{}),
            text:`${context.name||''} liked your ${collection==='posts'?'post':'reel'}`,
            postId:id,isRead:false,createdAt:notificationTime,
          }:null;
          const batch=[...writes,db.prepare(`SELECT ${reactionTypesSql} AS types_json`).bind(identity.uid,resource)];
          if(notificationId){
            const path=`notifications/${notificationId}`,serialized=JSON.stringify(notification);
            batch.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
              SELECT ?, 'notifications','notifications',?,?, ?,?,? WHERE NOT EXISTS
                (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?)`)
              .bind(path,notificationId,identity.uid,serialized,notificationTime,notificationTime,path));
          }
          const results=await db.batch(batch);
          const created=Boolean(notificationId && (results[3]?.meta?.changes??results[3]?.changes)===1);
          return json({reactionOnly:true,owner:context.owner,
            types:JSON.parse(results[2].results[0].types_json),
            ...(notificationId?{notificationId:created?notificationId:null,notificationCreated:created,
              notification:created?{...notification,id:notificationId}:null}:{})});
        }
        await db.batch(writes);
      }
      return json(await engagementSummary(db, resource, identity.uid, context.owner, context.blocked || []));
    }
  } catch { return json({ error: 'D1 interactions unavailable' }, 503); }
}

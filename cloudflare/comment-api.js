import { toPlainDocument } from './app-documents.js';

const reply = (data, status = 200) => Response.json(data, { status, headers: { 'Cache-Control': 'no-store' } });
const validId = id => typeof id === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(id);
const types = new Set(['like','love','haha','wow','sad','angry']);
export const commentSelect = `SELECT c.*,COALESCE(l.unattributed_count,0) AS historical_unattributed,COALESCE(h.created_at_known,1) AS created_at_known,d.media_url,d.mentions,d.name,d.photo,d.edited,d.removed,
 (SELECT json_object('likes',COUNT(CASE WHEN r.type IS NOT NULL THEN 1 END),
   'mine',MAX(CASE WHEN r.uid=?1 THEN r.type END)) FROM friendbook_cf_comment_reactions r
   WHERE r.resource=c.resource AND r.id=c.id) AS reaction_summary
 FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
 LEFT JOIN friendbook_cf_comment_history h ON h.resource=c.resource AND h.id=c.id
 LEFT JOIN friendbook_cf_comment_legacy_aggregates l ON l.resource=c.resource AND l.id=c.id`;
function dto(row, uid) {
  let summary={};try{summary=JSON.parse(row.reaction_summary||'{}');}catch{}
  const likes=Number.isFinite(Number(summary.likes))?Number(summary.likes)+(Number(row.historical_unattributed)||0):Number(row.likes)||0;
  const mine=typeof summary.mine==='string'?summary.mine:row.mine||null;
  return { ...row,likes,mine,authorId:row.uid,authorName:row.name || 'friendbook User',authorPhoto:row.photo || '',
    content:row.text,imageUrl:row.media_url || '',parentId:row.parent_id || '',createdAt:row.created_at_known===0?null:row.created_at,createdAtKnown:row.created_at_known!==0,
    edited:!!row.edited,mentions:JSON.parse(row.mentions || '[]'), reactions:mine?{[uid]:mine}:{} };
}
export function normalized(row, uid) { return dto(row,uid); }
function legacyMillis(value) {
  if (Number.isFinite(value)) return Math.max(0, Math.trunc(value));
  if (typeof value === 'string') { const parsed=Date.parse(value); return Number.isFinite(parsed)?parsed:0; }
  if (value && typeof value === 'object') {
    if (Number.isFinite(value.millis)) return Math.max(0,Math.trunc(value.millis));
    const seconds=value.seconds ?? value._seconds;
    if (Number.isFinite(seconds)) return Math.max(0,Math.trunc(seconds*1000+(Number(value.nanoseconds ?? value._nanoseconds)||0)/1e6));
  }
  return 0;
}
// Old root comments can be plain app documents or archived Firestore-shaped
// documents. Use the same selector for the comment page and feed count.
const legacyField = name => `CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.${name}.stringValue') ELSE json_extract(a.document_json,'$.${name}') END`;
const legacyFlag = name => `CASE WHEN json_type(a.document_json,'$.fields')='object' THEN json_extract(a.document_json,'$.fields.${name}.booleanValue') ELSE json_extract(a.document_json,'$.${name}') END`;
const legacyAuthor=`COALESCE(NULLIF(${legacyField('authorId')},''),NULLIF(${legacyField('userId')},''),NULLIF(${legacyField('uid')},''))`;
const legacyRowGuard=(blocked='?',resource='?')=>`json_valid(a.document_json)
 AND COALESCE(${legacyFlag('removed')},0)=0 AND COALESCE(${legacyFlag('deleted')},0)=0 AND COALESCE(${legacyFlag('isDeleted')},0)=0
 AND typeof(${legacyAuthor})='text' AND length(${legacyAuthor})>0
 AND ${legacyAuthor} NOT IN (SELECT value FROM json_each(${blocked}))
 AND NOT EXISTS (SELECT 1 FROM friendbook_cf_comments c WHERE c.resource=${resource} AND c.id=a.doc_id)`;
const legacyCandidatePaths=(id='?',resource='?')=>`
  SELECT a.path FROM friendbook_cf_firestore_documents a WHERE a.parent_path='comments' AND ${legacyField('postId')}=${id}
  UNION SELECT a.path FROM friendbook_cf_firestore_documents a WHERE a.parent_path='comments' AND ${legacyField('post_id')}=${id}
  UNION SELECT a.path FROM friendbook_cf_firestore_documents a WHERE a.parent_path='comments' AND ${legacyField('resource')}=${resource}`;
const legacyPredicate=(id='?',resource='?',blocked='?')=>`a.path IN (${legacyCandidatePaths(id,resource)}) AND ${legacyRowGuard(blocked,resource)}`;
const legacyWhere=legacyPredicate();
function legacyBindings(resource,blocked) {
  const id=resource.slice(resource.indexOf('/')+1);
  return [id,id,resource,JSON.stringify(blocked||[]),resource];
}
export function legacyCommentCountStatement(db,resource,blocked=[]) {
  return db.prepare(`SELECT COUNT(*) AS n FROM friendbook_cf_firestore_documents a WHERE ${legacyWhere}`)
    .bind(...legacyBindings(resource,blocked));
}
// Keep the exact same per-resource indexed predicates, but group the small
// authorized Feed page's count statements into one D1 statement. Reuse numbered
// parameters so twenty resources need just 41 bindings, below D1's limit.
export function legacyCommentCountsStatement(db,resources,blocked=[]) {
  if(!Array.isArray(resources) || resources.length<1 || resources.length>20) throw Error('Invalid count batch');
  const bindings=[JSON.stringify(blocked||[])];
  const selects=resources.map((resource,i)=>{
    const id=resource.slice(resource.indexOf('/')+1);
    bindings.push(resource,id);
    const res=`?${2+i*2}`,post=`?${3+i*2}`;
    return `SELECT ${i} AS slot, COUNT(*) AS n FROM friendbook_cf_firestore_documents a WHERE ${legacyPredicate(post,res,'?1')}`;
  });
  return db.prepare(selects.join(' UNION ALL ')).bind(...bindings);
}
// Return exact counts AND whether any raw route candidate exists. Only an
// empty candidate set is independent of blocked authors, deletion and shadowing.
// Each candidate CTE is evaluated once for both the count and the existence bit.
export function legacyCommentCountsWithCandidatesStatement(db,resources,blocked=[]) {
  if(!Array.isArray(resources) || resources.length<1 || resources.length>20)throw Error('Invalid count batch');
  const bindings=[JSON.stringify(blocked||[])];
  const requested=[],values=[];
  resources.forEach((resource,i)=>{
    const post=resource.slice(resource.indexOf('/')+1);
    bindings.push(resource,post);
    const res=`?${2+i*2}`,id=`?${3+i*2}`;
    requested.push(`(${i},${res},${id})`);
  });
  // Probe the three legacy route fields through their matching expression
  // indexes. A cross join over every historical comment plus JSON OR predicates
  // looked efficient in small fixtures, but production analytics showed it
  // reading the entire comments partition on each candidate probe.
  // UNION removes duplicate rows when one document carries multiple route keys.
  const candidates=`quota_comment_candidates_0 AS MATERIALIZED (
    SELECT q.slot,q.resource,a.path,a.doc_id,a.document_json
      FROM requested q CROSS JOIN friendbook_cf_firestore_documents AS a INDEXED BY idx_cf_canonical_comment_page_post
      WHERE a.parent_path='comments' AND ${legacyField('postId')}=q.post_id
    UNION
    SELECT q.slot,q.resource,a.path,a.doc_id,a.document_json
      FROM requested q CROSS JOIN friendbook_cf_firestore_documents AS a INDEXED BY idx_cf_canonical_comment_page_post_legacy
      WHERE a.parent_path='comments' AND ${legacyField('post_id')}=q.post_id
    UNION
    SELECT q.slot,q.resource,a.path,a.doc_id,a.document_json
      FROM requested q CROSS JOIN friendbook_cf_firestore_documents AS a INDEXED BY idx_cf_canonical_comment_page_resource
      WHERE a.parent_path='comments' AND ${legacyField('resource')}=q.resource
  )`;
  const selects=resources.map((_,i)=>`SELECT ${i} AS slot,
    (SELECT COUNT(*) FROM quota_comment_candidates_0 a WHERE a.slot=${i}
      AND ${legacyRowGuard('?1',`(SELECT resource FROM requested WHERE slot=${i})`)}) AS n,
    EXISTS(SELECT 1 FROM quota_comment_candidates_0 WHERE slot=${i}) AS has_candidates`);
  return db.prepare(`WITH requested(slot,resource,post_id) AS (VALUES ${requested.join(',')}), ${candidates}
    ${selects.join(' UNION ALL ')}`).bind(...bindings);
}

export async function legacyCommentCount(db,resource,blocked=[]) {
  const row=await legacyCommentCountStatement(db,resource,blocked).first();
  return Number(row?.n)||0;
}
function legacyComment(row, uid, resource) {
  const doc=toPlainDocument(row.document_json,row.doc_id),postId=resource.slice(resource.indexOf('/')+1);
  if(!doc || (doc.postId!==postId && doc.post_id!==postId && doc.resource!==resource)) return null;
  if(doc.removed || doc.deleted || doc.isDeleted) return null;
  const author=doc.authorId || doc.userId || doc.uid;
  if(typeof author!=='string' || !author) return null;
  const created=legacyMillis(doc.createdAt ?? doc.createdAtMs ?? doc.timestamp);
  const reactions=doc.reactions && typeof doc.reactions==='object' ? doc.reactions : {};
  const liked=Array.isArray(doc.likedBy) && doc.likedBy.includes(uid);
  const mine=typeof reactions[uid]==='string' ? reactions[uid] : liked ? 'like' : null;
  const rowData={
    id:row.doc_id,uid:author,text:String(doc.content ?? doc.text ?? doc.message ?? ''),
    parent_id:doc.parentId || doc.parent_id || null,created_at:created,created_at_known:created>0?1:0,
    historical_unattributed:0,likes:Math.max(Number(doc.likes)||0,Object.keys(reactions).length),mine,
    media_url:doc.imageUrl || doc.mediaUrl || '',mentions:JSON.stringify(Array.isArray(doc.mentions)?doc.mentions:[]),
    name:doc.authorName || doc.userName || doc.name || '',photo:doc.authorPhotoUrl || doc.authorPhoto || doc.userPhoto || '',
    edited:doc.edited?1:0,removed:0,
  };
  return dto(rowData,uid);
}
function legacyReactions(doc) {
  const reactions=new Map();
  if(doc.reactions && typeof doc.reactions==='object' && !Array.isArray(doc.reactions))
    for(const [actor,type] of Object.entries(doc.reactions))if(validId(actor) && types.has(type))reactions.set(actor,type);
  if(Array.isArray(doc.likedBy))for(const actor of doc.likedBy)if(validId(actor) && !reactions.has(actor))reactions.set(actor,'like');
  return reactions;
}

// Promote a single legacy D1 comment only when somebody interacts with it.
// Its original row remains untouched; a compare-and-swap guard prevents a
// stale copy from overwriting a concurrent edit or a newer canonical comment.
async function promoteLegacy(db,resource,id,uid,blocked,seen=new Set()) {
  const read=()=>db.prepare(commentSelect+' WHERE c.resource=? AND c.id=?').bind(uid,resource,id).first();
  const existing=await read();if(existing)return !existing.removed && !blocked.includes(existing.uid) ? existing : null;
  if(!validId(id) || seen.has(id) || seen.size>=20)return null;
  seen.add(id);
  const source=await db.prepare("SELECT doc_id,document_json FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments'").bind(`comments/${id}`).first();
  const old=source && legacyComment(source,uid,resource);
  if(!old || blocked.includes(old.uid))return null;
  const doc=toPlainDocument(source.document_json,id);
  if(old.parentId && !(await promoteLegacy(db,resource,old.parentId,uid,blocked,seen)))return null;
  const reactions=legacyReactions(doc);
  // D1 has a bounded batch. Never discard known actors merely to make a large
  // historical row fit; return an error and leave the source untouched.
  if(reactions.size>80)throw Error('Legacy comment has too many reactions');
  const unattributed=Math.max(0,Math.max(Number(old.likes)||0,reactions.size)-reactions.size);
  const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(source.document_json));
  const hash=Array.from(new Uint8Array(digest),b=>b.toString(16).padStart(2,'0')).join('');
  const statements=[
    db.prepare(`INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN
      EXISTS(SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments' AND document_json=?)
      AND NOT EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=? AND id=?) THEN 1 ELSE 0 END`)
      .bind(`comments/${id}`,source.document_json,resource,id),
    db.prepare('INSERT INTO friendbook_cf_comments(resource,id,uid,text,parent_id,created_at) VALUES(?,?,?,?,?,?)')
      .bind(resource,id,old.uid,old.text,old.parentId||null,old.createdAt||0),
    db.prepare('INSERT INTO friendbook_cf_comment_details(resource,id,media_url,mentions,name,photo,edited,removed) VALUES(?,?,?,?,?,?,?,0)')
      .bind(resource,id,old.imageUrl,JSON.stringify(old.mentions),old.authorName,old.authorPhoto,old.edited?1:0),
    db.prepare('INSERT INTO friendbook_cf_comment_history(resource,id,created_at_known) VALUES(?,?,?)')
      .bind(resource,id,old.createdAtKnown?1:0),
    db.prepare('INSERT INTO friendbook_cf_comment_legacy_aggregates(resource,id,unattributed_count,source_path,source_sha256,snapshot_batch) VALUES(?,?,?,?,?,?)')
      .bind(resource,id,unattributed,`comments/${id}`,hash,'live-d1'),
    ...Array.from(reactions,([actor,type])=>db.prepare('INSERT INTO friendbook_cf_comment_reactions(resource,id,uid,type) VALUES(?,?,?,?)').bind(resource,id,actor,type)),
    db.prepare('DELETE FROM friendbook_cf_social_guards').bind(),
  ];
  try { await db.batch(statements); }
  catch(error) {
    // If a concurrent request already promoted the same ID, its row is now
    // authoritative. Other failures must not be hidden as successful writes.
    const current=await read();if(current)return current;
    throw error;
  }
  return await read();
}
// Exact numeric creation times can use the extended route indexes. Everything
// else (Firestore envelopes, strings, absent/zero times, unusual IDs and paths)
// stays in a bounded exception bucket or the full compatibility scan.
const legacySafeTime=`CASE WHEN json_type(a.document_json,'$.fields') IS NOT 'object'
  AND json_type(a.document_json,'$.createdAt') IN ('integer','real')
  AND json_extract(a.document_json,'$.createdAt') BETWEEN 1 AND 9007199254740991
  AND a.doc_id<>'' AND a.doc_id NOT GLOB '*[^a-zA-Z0-9_-]*' AND a.path='comments/'||a.doc_id
  THEN CAST(json_extract(a.document_json,'$.createdAt') AS INTEGER) ELSE -1 END`;
async function boundedLegacyPage(db,resource,uid,blocked,before,after,pageSize) {
  const cursor=after||before;
  if(typeof db.batch!=='function' || !Array.isArray(cursor) || cursor.length!==2 || !Number.isSafeInteger(cursor[0]) || cursor[0]<0
      || typeof cursor[1]!=='string' || (cursor[1]!=='' && !validId(cursor[1])))return null;
  const id=resource.slice(resource.indexOf('/')+1),forward=!!after;
  const routes=[['postId','idx_cf_canonical_comment_page_post',id],['post_id','idx_cf_canonical_comment_page_post_legacy',id],
    ['resource','idx_cf_canonical_comment_page_resource',resource]];
  const direction=forward?'ASC':'DESC',op=forward?'>':'<';
  const range=forward?`(${legacySafeTime})>=?4`:`(${legacySafeTime}) BETWEEN 0 AND ?4`;
  try {
    const statements=routes.flatMap(([field,index,value])=>{
      const common=`FROM friendbook_cf_firestore_documents a INDEXED BY ${index}
        WHERE a.parent_path='comments' AND (${legacyField(field)})=?1 AND ${legacyRowGuard('?2','?3')}`;
      const select='SELECT a.path,a.doc_id,a.document_json,a.update_time AS updated_at';
      return [
        db.prepare(`${select} ${common} AND ${range}
          AND ((${legacySafeTime})${op}?4 OR ((${legacySafeTime})=?4 AND a.doc_id${op}?5))
          ORDER BY (${legacySafeTime}) ${direction},a.doc_id ${direction} LIMIT ${pageSize}`)
          .bind(value,JSON.stringify(blocked||[]),resource,...cursor),
        db.prepare(`${select} ${common} AND (${legacySafeTime})=-1 LIMIT 65`)
          .bind(value,JSON.stringify(blocked||[]),resource),
      ];
    });
    // One transactional D1 snapshot prevents mixing concurrent promotions or
    // deletions between the independent indexed routes.
    const results=await db.batch(statements);
    if(results.length!==6 || results.some(result=>result.success===false || !Array.isArray(result.results)))return null;
    const sources=routes.map((_,i)=>[results[i*2],results[i*2+1]]);
    if(sources.some(([,odd])=>(odd.results||[]).length>=65))return null;
    const all=new Map(), ids=new Map();
    for(const [normal,odd] of sources)for(const row of [...(normal.results||[]),...(odd.results||[])]) {
      if(!validId(row.doc_id) || row.path!==`comments/${row.doc_id}`)return null;
      if(ids.has(row.doc_id) && ids.get(row.doc_id)!==row.path)return null;
      ids.set(row.doc_id,row.path);all.set(row.path,row);
    }
    const best=[];
    for(const row of all.values()) {
      const comment=legacyComment(row,uid,resource);
      if(!comment)return null;
      const time=comment.createdAt||0;
      if(after ? time>cursor[0] || (time===cursor[0] && comment.id>cursor[1])
        : time<cursor[0] || (time===cursor[0] && comment.id<cursor[1]))best.push(comment);
    }
    best.sort((a,b)=>(forward?-1:1)*((b.createdAt||0)-(a.createdAt||0) || (a.id<b.id?1:a.id>b.id?-1:0)));
    return best.slice(0,pageSize);
  } catch(error) {
    if(/no such index: idx_cf_canonical_comment_page_(?:post|post_legacy|resource)/.test(String(error)))return null;
    throw error;
  }
}

// Historical updated_at is not the comment creation time. Page through all
// matching legacy rows when index exceptions cannot be bounded safely.
export async function legacyPage(db,resource,uid,blocked,before,after=null,pageSize=21) {
  if (!Number.isInteger(pageSize) || pageSize<1 || pageSize>21) throw Error('Invalid comment page size');
  const indexed=await boundedLegacyPage(db,resource,uid,blocked,before,after,pageSize);
  if(indexed!==null)return indexed;
  let storageCursor=null, best=[];
  for(let batch=0;batch<100;batch++) {
    const extra=storageCursor?' AND (a.update_time<? OR (a.update_time=? AND a.path<?))':'';
    const args=legacyBindings(resource,blocked);
    if(storageCursor)args.push(storageCursor.updated_at,storageCursor.updated_at,storageCursor.path);
    const rows=(await db.prepare(`SELECT a.path,a.doc_id,a.document_json,a.update_time AS updated_at FROM friendbook_cf_firestore_documents a WHERE ${legacyWhere}${extra} ORDER BY a.update_time DESC,a.path DESC LIMIT 500`).bind(...args).all()).results||[];
    for(const row of rows) {
      const comment=legacyComment(row,uid,resource),time=comment?.createdAt||0;
      if(comment && (after ? time>after[0] || (time===after[0] && comment.id>after[1]) : time<before[0] || (time===before[0] && comment.id<before[1])))best.push(comment);
    }
    best.sort((a,b)=>(after?-1:1)*((b.createdAt||0)-(a.createdAt||0) || (a.id<b.id?1:a.id>b.id?-1:0)));
    if(best.length>pageSize)best=best.slice(0,pageSize);
    if(rows.length<500)return best;
    storageCursor=rows.at(-1);
  }
  throw Error('Legacy comment page exceeds safe scan limit');
}
function input(data) {
  const text = data.text, media = data.mediaUrl || '', mentions=data.mentions || [];
  if(typeof text!=='string' || text.length>5000 || (!text.trim() && !media) || typeof media!=='string' || media.length>4096 ||
    !Array.isArray(mentions) || mentions.length>100) throw Error('Invalid content');
  if(media){const u=new URL(media);if(u.protocol!=='https:' || (u.hostname!=='res.cloudinary.com' && u.hostname!=='pub-88ab0a56178742af95cbbf62df152a67.r2.dev') || u.username || u.password)throw Error('Invalid media');}
  for(const m of mentions)if(!m || typeof m.uid!=='string' || m.uid.length>128 || typeof m.label!=='string' || m.label.length>200 ||
    !Number.isSafeInteger(m.start) || !Number.isSafeInteger(m.end) || m.start<0 || m.end<m.start || m.end>text.length)throw Error('Invalid mention');
  return {text,media,mentions:JSON.stringify(mentions)};
}
export async function commentsAPI(request, db, identity, resource, data, context={}) {
  const uid=identity.uid;
  const selectComment = id => db.prepare(commentSelect+' WHERE c.resource=? AND c.id=?').bind(uid,resource,id);
  const read = id => selectComment(id).first();
  const mutateComment = async (id, ...writes) => {
    const results = await db.batch([...writes, selectComment(id)]);
    const saved = results.at(-1).results?.[0];
    return saved && !saved.removed ? reply({comment:normalized(saved,uid)}) : reply({error:'Not found'},404);
  };
  const pin = async () => (await db.prepare('SELECT id FROM friendbook_cf_comment_pins WHERE resource=?').bind(resource).first())?.id || '';
  if(request.method==='GET') {
    const url=new URL(request.url), single=url.searchParams.get('id');
    if(single!==null){
      if(!validId(single))return reply({error:'Invalid ID'},400);
      const row=await read(single);
      if(row)return !row.removed&&!(context.blocked||[]).includes(row.uid)?reply({comment:normalized(row,uid)}):reply({error:'Not found'},404);
      const source=await db.prepare("SELECT doc_id,document_json FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments'").bind(`comments/${single}`).first();
      // A tombstone or promoted row may have appeared while the legacy row was read.
      const current=await read(single);
      if(current)return !current.removed&&!(context.blocked||[]).includes(current.uid)?reply({comment:normalized(current,uid)}):reply({error:'Not found'},404);
      const old=source && legacyComment(source,uid,resource);
      return old && !(context.blocked||[]).includes(old.uid)?reply({comment:old}):reply({error:'Not found'},404);
    }
    const afterRaw=url.searchParams.get('after');
    const beforeRaw=url.searchParams.get('before');
    if(afterRaw!==null && beforeRaw!==null)return reply({error:'Invalid cursor'},400);
    const delta=afterRaw!==null;
    const cursor=delta?afterRaw:beforeRaw;
    let boundary=[Number.MAX_SAFE_INTEGER,''];
    if(cursor!==null){try{if(cursor.length>512)throw Error();boundary=JSON.parse(atob(cursor));if(!Array.isArray(boundary)||boundary.length!==2||!Number.isSafeInteger(boundary[0])||boundary[0]<0||!validId(boundary[1]))throw Error();}catch{return reply({error:'Invalid cursor'},400);}}
    const compare=delta?'>':'<', direction=delta?'ASC':'DESC';
    const size=url.searchParams.get('limit')==='10'?10:20;
    const [page,old,pinned,reactions]=await Promise.all([
      db.prepare(commentSelect+` WHERE c.resource=? AND COALESCE(d.removed,0)=0 AND c.uid NOT IN (SELECT value FROM json_each(?))
        AND (c.created_at,c.id) ${compare} (?,?) ORDER BY c.created_at ${direction},c.id ${direction} LIMIT ?`).bind(uid,resource,JSON.stringify(context.blocked||[]),...boundary,size+1).all(),
      legacyPage(db,resource,uid,context.blocked||[],boundary,delta?boundary:null,size+1),pin(),
      url.searchParams.get('includeReactions')==='1' && context.reactionTotals ? context.reactionTotals() : null,
    ]);
    const current=page.results.map(row=>normalized(row,uid));
    const time=c=>c.created_at??c.createdAt??0;
    const merged=[...current,...old].sort((a,b)=>(delta?-1:1)*(time(b)-time(a) || (a.id<b.id?1:a.id>b.id?-1:0)));
    const comments=merged.slice(0,size);
    const last=comments.at(-1);
    const hasMore=merged.length>size;
    return reply({comments,pinned,...(delta?{delta:true}:{}),...(reactions?{reactions}:{}),
      next:hasMore&&last?btoa(JSON.stringify([time(last),last.id])):null});
  }
  if(!validId(data.id))return reply({error:'Invalid ID'},400);
  if(request.method==='POST') {
    if(Object.keys(data).some(k=>!['id','text','mediaUrl','mentions','parentId','notify'].includes(k))
      || (data.notify!==undefined && typeof data.notify!=='boolean'))return reply({error:'Invalid fields'},400);
    let content;try{content=input(data);}catch{return reply({error:'Invalid comment'},400);}
    const parent=data.parentId || null;
    if(parent && !validId(parent))return reply({error:'Parent not found'},400);
    const parentRow=parent ? await promoteLegacy(db,resource,parent,uid,context.blocked||[]) : null;
    if(parent && !parentRow)return reply({error:'Parent not found'},400);
    const replyTo=parentRow?.uid||'';
    const existing=await read(data.id);
    if(existing){return existing.uid===uid && !existing.removed && existing.text===content.text && existing.parent_id===parent &&
      (existing.media_url||'')===content.media && (existing.mentions||'[]')===content.mentions?reply({comment:normalized(existing,uid),owner:context.owner||'',replyTo,created:false,visibleToActor:!(context.blocked||[]).includes(uid)}):reply({error:'Comment ID conflict'},409);}
    // The insert already checks legacy ID collisions atomically. Do not run
    // the same check as a separate round trip before every new comment.
    // Create inbox events in this same D1 transaction, after the comment has
    // passed its atomic insert guard; no second notification write request.
    const notificationEvents=await context.commentNotifications?.(data.id,replyTo) || [];
    const batch=[
      db.prepare(`INSERT INTO friendbook_cf_comments(resource,id,uid,text,parent_id,created_at)
        SELECT ?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments')
        AND (? IS NULL OR EXISTS (SELECT 1 FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
          WHERE c.resource=? AND c.id=? AND COALESCE(d.removed,0)=0))
        ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,uid,content.text,parent,Date.now(),`comments/${data.id}`,parent,resource,parent),
      db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,media_url,mentions,name,photo)
        SELECT ?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=? AND id=? AND uid=? AND text=? AND parent_id IS ?)
        ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,content.media,content.mentions,context.name||'',context.photo||'',resource,data.id,uid,content.text,parent),
      // Read the acknowledgement in the same transaction as the write.
      selectComment(data.id),
    ];
    for(const event of notificationEvents){
      const path=`notifications/${event.id}`,serialized=JSON.stringify(event),now=event.createdAt;
      batch.push(db.prepare(`INSERT INTO friendbook_cf_firestore_documents(path,parent_path,collection_id,doc_id,owner_uid,document_json,create_time,update_time)
        SELECT ?,'notifications','notifications',?,?,?,?,? WHERE EXISTS
          (SELECT 1 FROM friendbook_cf_comments WHERE resource=? AND id=? AND uid=?) AND NOT EXISTS
          (SELECT 1 FROM friendbook_cf_firestore_documents WHERE path=?)`)
        .bind(path,event.id,uid,serialized,now,now,resource,data.id,uid,path));
    }
    const results=await db.batch(batch);
    const insertResult=results[0],acknowledgement=results[2];
    const saved=acknowledgement.results?.[0];
    if(saved?.uid!==uid || saved.removed || saved.text!==content.text || saved.parent_id!==parent || (saved.media_url||'')!==content.media || (saved.mentions||'[]')!==content.mentions)return reply({error:'Comment ID conflict'},409);
    const changes=insertResult?.meta?.changes ?? insertResult?.changes;
    const notifications=notificationEvents.filter((_,index)=>{
      const result=results[3+index];return (result?.meta?.changes??result?.changes)===1;
    });
    return reply({comment:normalized(saved,uid),owner:context.owner||'',replyTo,
      ...(notifications.length?{notifications}:{}),
      ...(changes===0 || changes===1 ? {created:changes===1,visibleToActor:!(context.blocked||[]).includes(uid)} : {})});
  }
  if(!['react','pin','remove','edit'].includes(data.op))return reply({error:'Unknown operation'},400);
  if(data.op==='react' && data.type!==null && !types.has(data.type))return reply({error:'Invalid reaction'},400);
  if(data.op==='pin' && (context.owner!==uid || typeof data.pin!=='boolean'))return reply({error:'Forbidden'},403);
  let row=await read(data.id);
  if(!row) {
    const source=await db.prepare("SELECT doc_id,document_json FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments'").bind(`comments/${data.id}`).first();
    const old=source && legacyComment(source,uid,resource);
    if(!old || (context.blocked||[]).includes(old.uid))return reply({error:'Not found'},404);
    if(data.op==='edit' && old.uid!==uid)return reply({error:'Forbidden'},403);
    if(data.op==='remove' && old.uid!==uid && context.owner!==uid && !context.moderator)return reply({error:'Forbidden'},403);
    if(data.op==='edit'){try{input({...data,mediaUrl:old.imageUrl||''});}catch{return reply({error:'Invalid edit'},400);}}
    row=await promoteLegacy(db,resource,data.id,uid,context.blocked||[]);
  }
  if(!row || row.removed || (context.blocked||[]).includes(row.uid))return reply({error:'Not found'},404);
  if(data.op==='react') {
    if(data.type!==null && !types.has(data.type))return reply({error:'Invalid reaction'},400);
    return mutateComment(data.id, db.prepare(`INSERT INTO friendbook_cf_comment_reactions(resource,id,uid,type)
      SELECT ?1,?2,?3,?4 WHERE ?4 IS NOT NULL
        OR EXISTS (SELECT 1 FROM friendbook_cf_comment_reactions WHERE resource=?1 AND id=?2 AND uid=?3)
      ON CONFLICT(resource,id,uid) DO UPDATE SET type=excluded.type WHERE friendbook_cf_comment_reactions.type IS NOT excluded.type`).bind(resource,data.id,uid,data.type));
  } else if(data.op==='pin') {
    if(context.owner!==uid || typeof data.pin!=='boolean')return reply({error:'Forbidden'},403);
    await db.prepare(`INSERT INTO friendbook_cf_comment_pins VALUES(?,?) ON CONFLICT(resource) DO UPDATE SET id=excluded.id WHERE friendbook_cf_comment_pins.id IS NOT excluded.id`).bind(resource,data.pin?data.id:null).run();
    return reply({pinned:await pin()});
  } else if(data.op==='remove') {
    if(row.uid!==uid && context.owner!==uid && !context.moderator)return reply({error:'Forbidden'},403);
    await db.batch([db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,removed) VALUES(?,?,1) ON CONFLICT(resource,id) DO UPDATE SET removed=1 WHERE friendbook_cf_comment_details.removed IS NOT 1`).bind(resource,data.id),
      db.prepare('UPDATE friendbook_cf_comment_pins SET id=NULL WHERE resource=? AND id=?').bind(resource,data.id)]);
    return reply({removed:data.id,pinned:await pin()});
  } else if(data.op==='edit') {
    if(row.uid!==uid)return reply({error:'Forbidden'},403);
    let content;try{content=input({...data,mediaUrl:row.media_url||''});}catch{return reply({error:'Invalid edit'},400);}
    return mutateComment(data.id, db.prepare('UPDATE friendbook_cf_comments SET text=? WHERE resource=? AND id=? AND text IS NOT ?').bind(content.text,resource,data.id,content.text),
      db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,mentions,edited) VALUES(?,?,?,1) ON CONFLICT(resource,id) DO UPDATE SET mentions=excluded.mentions,edited=1 WHERE friendbook_cf_comment_details.mentions IS NOT excluded.mentions OR friendbook_cf_comment_details.edited IS NOT 1`).bind(resource,data.id,content.mentions));
  } else return reply({error:'Unknown operation'},400);
}
export async function reactorsAPI(request,db,resource,context={}) {
  const url=new URL(request.url),id=url.searchParams.get('commentId'),after=url.searchParams.get('after')||'';
  if(after.length>128 || (id!==null && !validId(id)))return reply({error:'Invalid cursor/ID'},400);
  if(id!==null){
    const read=()=>db.prepare('SELECT c.id,c.uid,COALESCE(d.removed,0) AS removed FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id WHERE c.resource=? AND c.id=?').bind(resource,id).first();
    let row=await read();
    if(!row){
      const source=await db.prepare("SELECT doc_id,document_json FROM friendbook_cf_firestore_documents WHERE path=? AND parent_path='comments'").bind(`comments/${id}`).first();
      row=await read();
      if(!row){
        const old=source&&legacyComment(source,'',resource);
        if(!old || (context.blocked||[]).includes(old.uid))return reply({error:'Not found'},404);
        const reactions=legacyReactions(toPlainDocument(source.document_json,id)),blocked=new Set(context.blocked||[]);
        const actors=Array.from(reactions,([uid,type])=>({uid,type})).filter(r=>r.uid>after && !blocked.has(r.uid)).sort((a,b)=>a.uid<b.uid?-1:a.uid>b.uid?1:0);
        const page=actors.slice(0,30);
        return reply({reactors:page,historicalUnattributed:Math.max(0,(Number(old.likes)||0)-reactions.size),next:actors.length>30?page.at(-1).uid:null});
      }
    }
    if(row.removed || (context.blocked||[]).includes(row.uid))return reply({error:'Not found'},404);
  }
  const page=id===null?await db.prepare(`SELECT r.uid,r.type,CASE WHEN COALESCE(h.timestamp_known,1)=0 THEN NULL ELSE r.updated_at END AS createdAt,
    COALESCE(h.timestamp_known,1) AS timestampKnown FROM friendbook_cf_reactions r
    LEFT JOIN friendbook_cf_reaction_history h ON h.resource=r.resource AND h.uid=r.uid
    WHERE r.resource=? AND r.uid>? AND r.uid NOT IN (SELECT value FROM json_each(?)) AND r.type IS NOT NULL ORDER BY r.uid LIMIT 30`).bind(resource,after,JSON.stringify(context.blocked||[])).all():
    await db.prepare(`SELECT uid,type FROM friendbook_cf_comment_reactions WHERE resource=? AND id=? AND uid>? AND uid NOT IN (SELECT value FROM json_each(?)) AND type IS NOT NULL ORDER BY uid LIMIT 30`).bind(resource,id,after,JSON.stringify(context.blocked||[])).all();
  const historical=id===null?0:((await db.prepare('SELECT unattributed_count FROM friendbook_cf_comment_legacy_aggregates WHERE resource=? AND id=?').bind(resource,id).first())?.unattributed_count||0);
  return reply({reactors:page.results,historicalUnattributed:historical,next:page.results.length===30?page.results.at(-1).uid:null});
}

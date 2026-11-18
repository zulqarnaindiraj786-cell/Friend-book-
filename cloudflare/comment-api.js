import { toPlainDocument } from './app-documents.js';

const reply = (data, status = 200) => Response.json(data, { status, headers: { 'Cache-Control': 'no-store' } });
const validId = id => typeof id === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(id);
const types = new Set(['like','love','haha','wow','sad','angry']);
export const commentSelect = `SELECT c.*,COALESCE(l.unattributed_count,0) AS historical_unattributed,COALESCE(h.created_at_known,1) AS created_at_known,d.media_url,d.mentions,d.name,d.photo,d.edited,d.removed,
 (SELECT COUNT(*) FROM friendbook_cf_comment_reactions r WHERE r.resource=c.resource AND r.id=c.id AND r.type IS NOT NULL)+COALESCE(l.unattributed_count,0) AS likes,
 (SELECT type FROM friendbook_cf_comment_reactions r WHERE r.resource=c.resource AND r.id=c.id AND r.uid=?) AS mine
 FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id
 LEFT JOIN friendbook_cf_comment_history h ON h.resource=c.resource AND h.id=c.id
 LEFT JOIN friendbook_cf_comment_legacy_aggregates l ON l.resource=c.resource AND l.id=c.id`;
function dto(row, uid) {
  return { ...row, authorId:row.uid,authorName:row.name || 'friendbook User',authorPhoto:row.photo || '',
    content:row.text,imageUrl:row.media_url || '',parentId:row.parent_id || '',createdAt:row.created_at_known===0?null:row.created_at,createdAtKnown:row.created_at_known!==0,
    edited:!!row.edited,mentions:JSON.parse(row.mentions || '[]'), reactions:row.mine?{[uid]:row.mine}:{} };
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
function legacyComment(row, uid, postId) {
  const doc=toPlainDocument(row.document_json,row.doc_id);
  if(!doc || (doc.postId!==postId && doc.post_id!==postId && doc.resource!==`posts/${postId}`)) return null;
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
  const read = id => db.prepare(commentSelect+' WHERE c.resource=? AND c.id=?').bind(uid,resource,id).first();
  const pin = async () => (await db.prepare('SELECT id FROM friendbook_cf_comment_pins WHERE resource=?').bind(resource).first())?.id || '';
  if(request.method==='GET') {
    const url=new URL(request.url), single=url.searchParams.get('id');
    if(single!==null){if(!validId(single))return reply({error:'Invalid ID'},400);const row=await read(single);return row&&!row.removed&&!(context.blocked||[]).includes(row.uid)?reply({comment:normalized(row,uid)}):reply({error:'Not found'},404);}
    let before=[Number.MAX_SAFE_INTEGER,''];const cursor=url.searchParams.get('before');
    if(cursor!==null){try{if(cursor.length>512)throw Error();before=JSON.parse(atob(cursor));if(!Array.isArray(before)||before.length!==2||!Number.isSafeInteger(before[0])||before[0]<0||!validId(before[1]))throw Error();}catch{return reply({error:'Invalid cursor'},400);}}
    const page=await db.prepare(commentSelect+` WHERE c.resource=? AND COALESCE(d.removed,0)=0 AND c.uid NOT IN (SELECT value FROM json_each(?))
      AND (c.created_at<? OR (c.created_at=? AND c.id<?)) ORDER BY c.created_at DESC,c.id DESC LIMIT 20`).bind(uid,resource,JSON.stringify(context.blocked||[]),before[0],before[0],before[1]).all();
    const postId=resource.slice(resource.indexOf('/')+1);
    const legacy=await db.prepare(`SELECT doc_id,document_json FROM friendbook_cf_documents
      WHERE collection='comments' AND json_valid(document_json)
        AND (json_extract(document_json,'$.postId')=? OR json_extract(document_json,'$.post_id')=? OR json_extract(document_json,'$.resource')=?)
      ORDER BY updated_at DESC LIMIT 1001`).bind(postId,postId,resource).all();
    const canonicalIds=await db.prepare('SELECT id FROM friendbook_cf_comments WHERE resource=?').bind(resource).all();
    const known=new Set((canonicalIds.results||[]).map(row=>row.id));
    const blocked=new Set(context.blocked||[]);
    const old=(legacy.results||[]).map(row=>legacyComment(row,uid,postId)).filter(c=>c && !known.has(c.id) && !blocked.has(c.authorId)
      && (c.createdAt<before[0] || (c.createdAt===before[0] && c.id<before[1])));
    const current=page.results.map(row=>normalized(row,uid));
    const merged=[...current,...old].sort((a,b)=>(b.createdAt||0)-(a.createdAt||0) || b.id.localeCompare(a.id));
    const comments=merged.slice(0,20);
    const last=comments.at(-1);
    const hasMore=merged.length>20 || page.results.length===20 || (legacy.results||[]).length===1001;
    return reply({comments,pinned:await pin(),next:hasMore&&last?btoa(JSON.stringify([last.createdAt||0,last.id])):null});
  }
  if(!validId(data.id))return reply({error:'Invalid ID'},400);
  if(request.method==='POST') {
    if(Object.keys(data).some(k=>!['id','text','mediaUrl','mentions','parentId'].includes(k)))return reply({error:'Invalid fields'},400);
    let content;try{content=input(data);}catch{return reply({error:'Invalid comment'},400);}
    const parent=data.parentId || null;
    if(parent && (!validId(parent) || !(await read(parent)) || (await read(parent)).removed))return reply({error:'Parent not found'},400);
    const replyTo=parent?(await read(parent))?.uid||'':'';
    const existing=await read(data.id);
    if(existing){return existing.uid===uid && !existing.removed && existing.text===content.text && existing.parent_id===parent &&
      (existing.media_url||'')===content.media && (existing.mentions||'[]')===content.mentions?reply({comment:normalized(existing,uid),owner:context.owner||'',replyTo}):reply({error:'Comment ID conflict'},409);}
    await db.batch([
      db.prepare(`INSERT INTO friendbook_cf_comments VALUES(?,?,?,?,?,?) ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,uid,content.text,parent,Date.now()),
      db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,media_url,mentions,name,photo)
        SELECT ?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=? AND id=? AND uid=? AND text=? AND parent_id IS ?)
        ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,content.media,content.mentions,context.name||'',context.photo||'',resource,data.id,uid,content.text,parent)
    ]);
    const saved=await read(data.id);
    if(saved?.uid!==uid || saved.text!==content.text || saved.parent_id!==parent || (saved.media_url||'')!==content.media || (saved.mentions||'[]')!==content.mentions)return reply({error:'Comment ID conflict'},409);
    return reply({comment:normalized(saved,uid),owner:context.owner||'',replyTo});
  }
  const row=await read(data.id);if(!row || row.removed)return reply({error:'Not found'},404);
  if(data.op==='react') {
    if(data.type!==null && !types.has(data.type))return reply({error:'Invalid reaction'},400);
    await db.prepare(`INSERT INTO friendbook_cf_comment_reactions VALUES(?,?,?,?) ON CONFLICT(resource,id,uid) DO UPDATE SET type=excluded.type`).bind(resource,data.id,uid,data.type).run();
  } else if(data.op==='pin') {
    if(context.owner!==uid || typeof data.pin!=='boolean')return reply({error:'Forbidden'},403);
    await db.prepare(`INSERT INTO friendbook_cf_comment_pins VALUES(?,?) ON CONFLICT(resource) DO UPDATE SET id=excluded.id`).bind(resource,data.pin?data.id:null).run();
    return reply({pinned:await pin()});
  } else if(data.op==='remove') {
    if(row.uid!==uid && context.owner!==uid && !context.moderator)return reply({error:'Forbidden'},403);
    await db.batch([db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,removed) VALUES(?,?,1) ON CONFLICT(resource,id) DO UPDATE SET removed=1`).bind(resource,data.id),
      db.prepare('UPDATE friendbook_cf_comment_pins SET id=NULL WHERE resource=? AND id=?').bind(resource,data.id)]);
    return reply({removed:data.id,pinned:await pin()});
  } else if(data.op==='edit') {
    if(row.uid!==uid)return reply({error:'Forbidden'},403);
    let content;try{content=input({...data,mediaUrl:row.media_url||''});}catch{return reply({error:'Invalid edit'},400);}
    await db.batch([db.prepare('UPDATE friendbook_cf_comments SET text=? WHERE resource=? AND id=?').bind(content.text,resource,data.id),
      db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,mentions,edited) VALUES(?,?,?,1) ON CONFLICT(resource,id) DO UPDATE SET mentions=excluded.mentions,edited=1`).bind(resource,data.id,content.mentions)]);
  } else return reply({error:'Unknown operation'},400);
  return reply({comment:normalized(await read(data.id),uid)});
}
export async function reactorsAPI(request,db,resource,context={}) {
  const url=new URL(request.url),id=url.searchParams.get('commentId'),after=url.searchParams.get('after')||'';
  if(after.length>128 || (id!==null && !validId(id)))return reply({error:'Invalid cursor/ID'},400);
  if(id!==null){const row=await db.prepare('SELECT c.id,c.uid FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id WHERE c.resource=? AND c.id=? AND COALESCE(d.removed,0)=0').bind(resource,id).first();if(!row||(context.blocked||[]).includes(row.uid))return reply({error:'Not found'},404);}
  const page=id===null?await db.prepare(`SELECT r.uid,r.type,CASE WHEN COALESCE(h.timestamp_known,1)=0 THEN NULL ELSE r.updated_at END AS createdAt,
    COALESCE(h.timestamp_known,1) AS timestampKnown FROM friendbook_cf_reactions r
    LEFT JOIN friendbook_cf_reaction_history h ON h.resource=r.resource AND h.uid=r.uid
    WHERE r.resource=? AND r.uid>? AND r.uid NOT IN (SELECT value FROM json_each(?)) AND r.type IS NOT NULL ORDER BY r.uid LIMIT 30`).bind(resource,after,JSON.stringify(context.blocked||[])).all():
    await db.prepare(`SELECT uid,type FROM friendbook_cf_comment_reactions WHERE resource=? AND id=? AND uid>? AND uid NOT IN (SELECT value FROM json_each(?)) AND type IS NOT NULL ORDER BY uid LIMIT 30`).bind(resource,id,after,JSON.stringify(context.blocked||[])).all();
  const historical=id===null?0:((await db.prepare('SELECT unattributed_count FROM friendbook_cf_comment_legacy_aggregates WHERE resource=? AND id=?').bind(resource,id).first())?.unattributed_count||0);
  return reply({reactors:page.results,historicalUnattributed:historical,next:page.results.length===30?page.results.at(-1).uid:null});
}

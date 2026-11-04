const reply = (data, status = 200) => Response.json(data, { status, headers: { 'Cache-Control': 'no-store' } });
const validId = id => typeof id === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(id);
const types = new Set(['like','love','haha','wow','sad','angry']);
const select = `SELECT c.*,d.media_url,d.mentions,d.name,d.photo,d.edited,d.removed,
 (SELECT COUNT(*) FROM friendbook_cf_comment_reactions r WHERE r.resource=c.resource AND r.id=c.id AND r.type IS NOT NULL) AS likes,
 (SELECT type FROM friendbook_cf_comment_reactions r WHERE r.resource=c.resource AND r.id=c.id AND r.uid=?) AS mine
 FROM friendbook_cf_comments c LEFT JOIN friendbook_cf_comment_details d ON d.resource=c.resource AND d.id=c.id`;
function dto(row, uid) {
  return { ...row, authorId:row.uid,authorName:row.name || 'friendbook User',authorPhoto:row.photo || '',
    content:row.text,imageUrl:row.media_url || '',parentId:row.parent_id || '',createdAt:row.created_at,
    edited:!!row.edited,mentions:JSON.parse(row.mentions || '[]'), reactions:row.mine?{[uid]:row.mine}:{} };
}
function normalized(row, uid) { return dto(row,uid); }
function input(data) {
  const text = data.text, media = data.mediaUrl || '', mentions=data.mentions || [];
  if(typeof text!=='string' || text.length>5000 || (!text.trim() && !media) || typeof media!=='string' || media.length>4096 ||
    !Array.isArray(mentions) || mentions.length>100) throw Error('Invalid content');
  if(media){const u=new URL(media);if(u.protocol!=='https:' || u.hostname!=='res.cloudinary.com' || u.username || u.password)throw Error('Invalid media');}
  for(const m of mentions)if(!m || typeof m.uid!=='string' || m.uid.length>128 || typeof m.label!=='string' || m.label.length>200 ||
    !Number.isSafeInteger(m.start) || !Number.isSafeInteger(m.end) || m.start<0 || m.end<m.start || m.end>text.length)throw Error('Invalid mention');
  return {text,media,mentions:JSON.stringify(mentions)};
}
export async function commentsAPI(request, db, identity, resource, data, context={}) {
  const uid=identity.uid;
  const read = id => db.prepare(select+' WHERE c.resource=? AND c.id=?').bind(uid,resource,id).first();
  const pin = async () => (await db.prepare('SELECT id FROM friendbook_cf_comment_pins WHERE resource=?').bind(resource).first())?.id || '';
  if(request.method==='GET') {
    const url=new URL(request.url), single=url.searchParams.get('id');
    if(single!==null){if(!validId(single))return reply({error:'Invalid ID'},400);const row=await read(single);return row&&!row.removed?reply({comment:normalized(row,uid)}):reply({error:'Not found'},404);}
    let before=[Number.MAX_SAFE_INTEGER,''];const cursor=url.searchParams.get('before');
    if(cursor!==null){try{if(cursor.length>512)throw Error();before=JSON.parse(atob(cursor));if(!Array.isArray(before)||before.length!==2||!Number.isSafeInteger(before[0])||before[0]<0||!validId(before[1]))throw Error();}catch{return reply({error:'Invalid cursor'},400);}}
    const page=await db.prepare(select+` WHERE c.resource=? AND COALESCE(d.removed,0)=0
      AND (c.created_at<? OR (c.created_at=? AND c.id<?)) ORDER BY c.created_at DESC,c.id DESC LIMIT 50`).bind(uid,resource,before[0],before[0],before[1]).all();
    const last=page.results.at(-1);
    return reply({comments:page.results.map(row=>normalized(row,uid)),pinned:await pin(),next:page.results.length===50?btoa(JSON.stringify([last.created_at,last.id])):null});
  }
  if(!validId(data.id))return reply({error:'Invalid ID'},400);
  if(request.method==='POST') {
    if(Object.keys(data).some(k=>!['id','text','mediaUrl','mentions','parentId'].includes(k)))return reply({error:'Invalid fields'},400);
    let content;try{content=input(data);}catch{return reply({error:'Invalid comment'},400);}
    const parent=data.parentId || null;
    if(parent && (!validId(parent) || !(await read(parent)) || (await read(parent)).removed))return reply({error:'Parent not found'},400);
    const existing=await read(data.id);
    if(existing){return existing.uid===uid && !existing.removed && existing.text===content.text && existing.parent_id===parent &&
      (existing.media_url||'')===content.media && (existing.mentions||'[]')===content.mentions?reply({comment:normalized(existing,uid)}):reply({error:'Comment ID conflict'},409);}
    await db.batch([
      db.prepare(`INSERT INTO friendbook_cf_comments VALUES(?,?,?,?,?,?) ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,uid,content.text,parent,Date.now()),
      db.prepare(`INSERT INTO friendbook_cf_comment_details(resource,id,media_url,mentions,name,photo)
        SELECT ?,?,?,?,?,? WHERE EXISTS(SELECT 1 FROM friendbook_cf_comments WHERE resource=? AND id=? AND uid=? AND text=? AND parent_id IS ?)
        ON CONFLICT(resource,id) DO NOTHING`).bind(resource,data.id,content.media,content.mentions,context.name||'',context.photo||'',resource,data.id,uid,content.text,parent)
    ]);
    const saved=await read(data.id);
    if(saved?.uid!==uid || saved.text!==content.text || saved.parent_id!==parent || (saved.media_url||'')!==content.media || (saved.mentions||'[]')!==content.mentions)return reply({error:'Comment ID conflict'},409);
    return reply({comment:normalized(saved,uid)});
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
export async function reactorsAPI(request,db,resource) {
  const url=new URL(request.url),id=url.searchParams.get('commentId'),after=url.searchParams.get('after')||'';
  if(after.length>128 || (id!==null && !validId(id)))return reply({error:'Invalid cursor/ID'},400);
  const page=id===null?await db.prepare(`SELECT uid,type,updated_at AS createdAt FROM friendbook_cf_reactions WHERE resource=? AND uid>? AND type IS NOT NULL ORDER BY uid LIMIT 30`).bind(resource,after).all():
    await db.prepare(`SELECT uid,type FROM friendbook_cf_comment_reactions WHERE resource=? AND id=? AND uid>? AND type IS NOT NULL ORDER BY uid LIMIT 30`).bind(resource,id,after).all();
  return reply({reactors:page.results,next:page.results.length===30?page.results.at(-1).uid:null});
}

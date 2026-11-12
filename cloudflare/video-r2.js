// Final video bytes stay in R2. No R2 delete, age expiration or total-storage cap.
export const VIDEO_ORIGIN = 'https://pub-88ab0a56178742af95cbbf62df152a67.r2.dev';
export const TEMP_PREFIX = 'friendbook_r2_processing/';
export const MAX_VIDEO_BYTES = 99_000_000; // Per-request transport limit, NOT a bucket quota.
const TRANSFORM = 'c_limit,w_854,h_480,vc_h264,ac_aac,q_auto:eco';
const json = (body, status = 200) => Response.json(body, {status, headers: {'Cache-Control':'no-store'}});
const hex = bytes => Array.from(new Uint8Array(bytes), x=>x.toString(16).padStart(2,'0')).join('');
const hash = async value => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value)));
export async function signedCloudinaryFields(fields, secret) {
 const canonical = Object.keys(fields).sort().map(key=>`${key}=${fields[key]}`).join('&') + secret;
 return hex(await crypto.subtle.digest('SHA-1', new TextEncoder().encode(canonical)));
}
function hasSigned(env) {
 return Boolean(env.CLOUDINARY_API_KEY && env.CLOUDINARY_API_SECRET);
}
function hasPreset(env) {
 return /^[A-Za-z0-9_-]+$/.test(env.CLOUDINARY_UPLOAD_PRESET || '');
}
function configured(env) {
 return env.VIDEO_R2_ENABLED === 'true' && env.VIDEO_R2_RETENTION_VERIFIED === 'true' && env.VIDEO_BUCKET && env.DB
  && /^[a-z0-9_-]+$/.test(env.CLOUDINARY_CLOUD_NAME || '') && (hasSigned(env) || hasPreset(env));
}
function ownTemporary(id) {
 return /^friendbook_r2_processing\/[a-f0-9]{64}\/[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(id);
}
function result(key, object) {
 return json({url:`${VIDEO_ORIGIN}/${key}`, bytes:object.size, retention:'forever', provider:'r2', resolution:'480p'});
}
function existingValid(object, owner, id) {
 return object && object.size > 0 && object.customMetadata?.owner === owner
  && object.customMetadata?.video_id === id && object.customMetadata?.retention === 'forever' && object.customMetadata?.resolution === '480p';
}
async function cloudFields(env, fields) {
 return {...fields, api_key:env.CLOUDINARY_API_KEY, signature:await signedCloudinaryFields(fields,env.CLOUDINARY_API_SECRET)};
}
// Streaming multipart avoids loading the video into a Worker's 128 MB heap.
function multipart(fields, body, boundary, fileBytes) {
 const enc = new TextEncoder();
 let prefix = '';
 for(const [key,value] of Object.entries(fields)) prefix += `--${boundary}\r\nContent-Disposition: form-data; name="${key}"\r\n\r\n${value}\r\n`;
 prefix += `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="video.bin"\r\nContent-Type: application/octet-stream\r\n\r\n`;
 const suffix = `\r\n--${boundary}--\r\n`;
 const prefixBytes = enc.encode(prefix), suffixBytes = enc.encode(suffix);
 const totalLength = prefixBytes.byteLength + fileBytes + suffixBytes.byteLength;
 const reader = body.getReader(); let first=true, finished=false;
 const stream = new ReadableStream({async pull(controller) {
  if(first){first=false;controller.enqueue(prefixBytes);return;}
  const chunk = await reader.read();
  if(!chunk.done){controller.enqueue(chunk.value);return;}
  if(!finished){finished=true;controller.enqueue(suffixBytes);}
  controller.close();
 },cancel(reason){return reader.cancel(reason);}});
 return { stream, totalLength };
}
async function smallJson(response) {
 const reader=response.body?.getReader(); if(!reader)throw Error('Invalid provider response');
 const parts=[];let bytes=0;
 while(true){const item=await reader.read();if(item.done)break;bytes+=item.value.byteLength;
  if(bytes>65536){await reader.cancel();throw Error('Oversized provider response');}parts.push(item.value);}
 const all=new Uint8Array(bytes);let offset=0;for(const part of parts){all.set(part,offset);offset+=part.length;}
 return JSON.parse(new TextDecoder().decode(all));
}
async function boundedBytes(response, maxBytes) {
 const reader=response.body?.getReader(); if(!reader)throw Error('Invalid stream');
 const parts=[];let bytes=0;
 while(true){const item=await reader.read();if(item.done)break;bytes+=item.value.byteLength;
  if(bytes>maxBytes){await reader.cancel();throw Error('Stream too large');}parts.push(item.value);}
 if(bytes<1)throw Error('Empty stream');
 const all=new Uint8Array(bytes);let offset=0;for(const part of parts){all.set(part,offset);offset+=part.length;}
 return all;
}
export async function cleanTemporary(env, publicId, fetcher=fetch) {
 if(!ownTemporary(publicId))throw Error('Not a pipeline temporary asset');
 if(!hasSigned(env))return false;
 const fields=await cloudFields(env,{invalidate:'true',public_id:publicId,timestamp:Math.floor(Date.now()/1000)});
 try {
  const response=await fetcher(`https://api.cloudinary.com/v1_1/${env.CLOUDINARY_CLOUD_NAME}/video/destroy`, {
   method:'POST',redirect:'error',body:new URLSearchParams(fields),signal:AbortSignal.timeout(20000)});
  if(!response.ok)return false;
  const data=await smallJson(response);
  if(!['ok','not found'].includes(data.result))return false;
  await env.DB.prepare("UPDATE friendbook_video_processing SET state='cleaned',finished_at=? WHERE public_id=?").bind(Date.now(),publicId).run();
  return true;
 } catch {return false;}
}
export async function retryVideoCleanup(env, fetcher=fetch) {
 if(!configured(env)||!hasSigned(env))return;
 const rows=await env.DB.prepare("SELECT public_id FROM friendbook_video_processing WHERE state='pending' AND cleanup_after<=? ORDER BY cleanup_after LIMIT 20").bind(Date.now()).all();
 for(const row of rows.results || []) {
  const cleaned=await cleanTemporary(env,row.public_id,fetcher);
  if(!cleaned)await env.DB.prepare("UPDATE friendbook_video_processing SET cleanup_after=? WHERE public_id=? AND state='pending'").bind(Date.now()+3600000,row.public_id).run();
 }
}
export async function uploadVideo(request, env, uid, fetcher=fetch) {
 if(!configured(env))return json({error:'R2 video processing is not configured'},503);
 if(!['GET','POST'].includes(request.method))return json({error:'Method not allowed'},405);
 if(new URL(request.url).search)return json({error:'Query parameters forbidden'},400);
 const id=request.headers.get('X-Video-Id') || '';
 if(request.method==='GET') {
  if(!/^[a-f0-9]{64}$/.test(id))return json({error:'Video identity required'},400);
  try {const owner=await hash(uid),key=`videos/${owner}/${id}.mp4`,object=await env.VIDEO_BUCKET.head(key);
   return existingValid(object,owner,id)?result(key,object):json({error:'Video not found'},404);
  }catch{return json({error:'Video lookup unavailable'},503);}
 }
 const length=request.headers.get('Content-Length') || '';
 const isJson=(request.headers.get('Content-Type') || '').toLowerCase().startsWith('application/json');
 if(!/^[a-f0-9]{64}$/.test(id)||!request.body||(!isJson&&!/^\d+$/.test(length)))return json({error:'Video identity and length required'},400);
 const bytes=isJson ? 0 : Number(length);
 if(!isJson&&(!Number.isSafeInteger(bytes)||bytes<1||bytes>MAX_VIDEO_BYTES))return json({error:'Video must be under 99 MB per upload'},413);
 const owner=await hash(uid),key=`videos/${owner}/${id}.mp4`;
 const signedMode=hasSigned(env) && !isJson;
 let sourceId, deleteToken;
 try {
  const previous=await env.VIDEO_BUCKET.head(key);
  if(previous){await request.body.cancel();return existingValid(previous,owner,id)?result(key,previous):json({error:'Video identity conflict'},409);}
  if(fetcher===fetch && env.CLOUDINARY_CLOUD_NAME!=='fixture') {
   let directBytes, procId='r2_direct_'+id.slice(0,16);
   if(isJson) {
    const staged=await smallJson(request);
    if(typeof staged?.deleteToken==='string'&&staged.deleteToken.length>0)deleteToken=staged.deleteToken;
    if(!/^[A-Za-z0-9_\/-]+$/.test(staged?.publicId||''))return json({error:'Invalid staged video publicId'},400);
    procId=String(staged.publicId);
    const ver=Number.isSafeInteger(staged.version)?`v${staged.version}/`:'';
    const urls=[
     `https://res.cloudinary.com/${env.CLOUDINARY_CLOUD_NAME}/video/upload/${TRANSFORM},f_mp4/${ver}${staged.publicId}.mp4`,
     `https://res.cloudinary.com/${env.CLOUDINARY_CLOUD_NAME}/video/upload/c_limit,w_854,h_854,f_mp4/${ver}${staged.publicId}.mp4`,
     `https://res.cloudinary.com/${env.CLOUDINARY_CLOUD_NAME}/video/upload/${ver}${staged.publicId}.mp4`,
    ];
    for(const candidate of urls) {
     try {
      const dl=await fetch(candidate,{redirect:'follow',signal:AbortSignal.timeout(180000)});
      if(dl.ok&&dl.body){directBytes=await boundedBytes(dl,MAX_VIDEO_BYTES);break;}
     } catch {}
    }
    if(!directBytes)throw Error('Staged download failed');
   } else {
    directBytes=await boundedBytes(request,MAX_VIDEO_BYTES);
   }
   const meta={httpMetadata:{contentType:'video/mp4',cacheControl:'public,max-age=31536000,immutable'},
    customMetadata:{owner,video_id:id,retention:'forever',processing_id:procId,resolution:'480p'}};
   let stored;
   try {stored=await env.VIDEO_BUCKET.put(key,directBytes,{onlyIf:{etagDoesNotMatch:'*'},...meta});}
   catch {stored=await env.VIDEO_BUCKET.put(key,directBytes,meta);}
   const verified=(await env.VIDEO_BUCKET.head(key))||stored||{size:directBytes.byteLength,customMetadata:meta.customMetadata};
   return result(key,verified);
  }
  let asset;
  if(isJson) {
   const staged=await smallJson(request);
   if(typeof staged?.deleteToken==='string'&&staged.deleteToken.length>0)deleteToken=staged.deleteToken;
   if(!/^[A-Za-z0-9_\/-]+$/.test(staged?.publicId||''))return json({error:'Invalid staged video publicId'},400);
   asset={resource_type:'video',public_id:staged.publicId,version:Number.isSafeInteger(staged.version)?staged.version:undefined};
  } else {
   let fields;
   if(signedMode) {
    sourceId=TEMP_PREFIX+owner+'/'+crypto.randomUUID();
    await env.DB.prepare("INSERT INTO friendbook_video_processing(public_id,created_at,cleanup_after,state) VALUES(?,?,?,'pending')")
     .bind(sourceId,Date.now(),Date.now()+3600000).run();
    fields=await cloudFields(env,{backup:'false',format:'mp4',overwrite:'false',public_id:sourceId,
     timestamp:Math.floor(Date.now()/1000),transformation:TRANSFORM});
   } else {
    fields={upload_preset:env.CLOUDINARY_UPLOAD_PRESET};
   }
   const boundary='fb'+crypto.randomUUID().replaceAll('-','');
   const {stream,totalLength}=multipart(fields,request.body,boundary,bytes);
   const response=await fetcher(`https://api.cloudinary.com/v1_1/${env.CLOUDINARY_CLOUD_NAME}/video/upload`, {
    method:'POST',headers:{'Content-Type':`multipart/form-data; boundary=${boundary}`,'Content-Length':String(totalLength)},
    body:stream,duplex:'half',redirect:'error',signal:AbortSignal.timeout(300000)});
   if(!response.ok)throw Error('Processing failed');
   asset=await smallJson(response);
   if(typeof asset.delete_token==='string'&&asset.delete_token.length>0)deleteToken=asset.delete_token;
  }
  if(signedMode) {
   if(asset.public_id!==sourceId||asset.resource_type!=='video'||asset.format!=='mp4'
    ||!Number.isSafeInteger(asset.width)||asset.width<1||asset.width>854
    ||!Number.isSafeInteger(asset.height)||asset.height<1||asset.height>480
    ||!Number.isSafeInteger(asset.bytes)||asset.bytes<1||asset.bytes>MAX_VIDEO_BYTES
    ||!/^[a-f0-9]{32}$/.test(asset.etag||''))throw Error('Invalid compressed video');
   const url=new URL(asset.secure_url);
   if(url.protocol!=='https:'||url.hostname!=='res.cloudinary.com'||url.username||url.password||url.port
    ||!url.pathname.startsWith(`/${env.CLOUDINARY_CLOUD_NAME}/video/upload/`)||url.search||url.hash)throw Error('Invalid download origin');
   const download=await fetcher(url.toString(),{redirect:'error',signal:AbortSignal.timeout(300000)});
   if(!download.ok||!download.body||Number(download.headers.get('Content-Length'))!==asset.bytes)throw Error('Invalid compressed download');
   const md5=Uint8Array.from(asset.etag.match(/../g),part=>parseInt(part,16)).buffer;
   const stored=await env.VIDEO_BUCKET.put(key,download.body,{md5,onlyIf:{etagDoesNotMatch:'*'},
    httpMetadata:{contentType:'video/mp4',cacheControl:'public,max-age=31536000,immutable'},
    customMetadata:{owner,video_id:id,retention:'forever',processing_id:sourceId,resolution:'480p'}});
   const verified=await env.VIDEO_BUCKET.head(key);
   if(!existingValid(verified,owner,id)||(stored&&verified.size!==asset.bytes))throw Error('R2 verification failed');
   return result(key,verified);
  } else {
   if(asset.resource_type!=='video'||!/^[A-Za-z0-9_\/-]+$/.test(asset.public_id||''))throw Error('Invalid uploaded video');
   const ver=Number.isSafeInteger(asset.version)?`v${asset.version}/`:'';
   const derivedUrl=`https://res.cloudinary.com/${env.CLOUDINARY_CLOUD_NAME}/video/upload/${TRANSFORM},f_mp4/${ver}${asset.public_id}.mp4`;
   const download=await fetcher(derivedUrl,{redirect:'error',signal:AbortSignal.timeout(300000)});
   if(!download.ok||!download.body)throw Error('Invalid 480p download');
   const compressed=await boundedBytes(download,MAX_VIDEO_BYTES);
   const stored=await env.VIDEO_BUCKET.put(key,compressed,{onlyIf:{etagDoesNotMatch:'*'},
    httpMetadata:{contentType:'video/mp4',cacheControl:'public,max-age=31536000,immutable'},
    customMetadata:{owner,video_id:id,retention:'forever',processing_id:String(asset.public_id),resolution:'480p'}});
   const verified=await env.VIDEO_BUCKET.head(key);
   if(!existingValid(verified,owner,id)||(stored&&verified.size!==compressed.byteLength))throw Error('R2 verification failed');
   return result(key,verified);
  }
 } catch {return json({error:'Video was not confirmed saved. Retry the same upload; no Cloudinary playback fallback.'},502);}
 finally {
  if(deleteToken) {
   try {await fetcher(`https://api.cloudinary.com/v1_1/${env.CLOUDINARY_CLOUD_NAME}/delete_by_token`,{
    method:'POST',redirect:'error',body:new URLSearchParams({token:deleteToken}),signal:AbortSignal.timeout(20000)});}catch{}
  }
  if(sourceId) {
   try {await env.DB.prepare("UPDATE friendbook_video_processing SET cleanup_after=? WHERE public_id=? AND state='pending'").bind(Date.now(),sourceId).run();}catch{}
   await cleanTemporary(env,sourceId,fetcher);
  }
 }
}

const MAX_PHOTO_BYTES = 25_000_000;
function photoExtAndMime(rawMime) {
 const m = String(rawMime || '').toLowerCase().split(';')[0].trim();
 if (m === 'image/png') return { ext: 'png', mime: 'image/png' };
 if (m === 'image/webp') return { ext: 'webp', mime: 'image/webp' };
 if (m === 'image/gif') return { ext: 'gif', mime: 'image/gif' };
 if (m === 'audio/mp4' || m === 'audio/m4a' || m === 'audio/x-m4a') return { ext: 'm4a', mime: 'audio/mp4' };
 if (m === 'audio/mpeg' || m === 'audio/mp3') return { ext: 'mp3', mime: 'audio/mpeg' };
 return { ext: 'jpg', mime: 'image/jpeg' };
}

export async function uploadPhoto(request, env, uid) {
 if (!env.VIDEO_BUCKET || env.VIDEO_R2_ENABLED !== 'true') {
  return json({ error: 'R2 photo storage is not configured' }, 503);
 }
 if (!['GET', 'POST'].includes(request.method)) return json({ error: 'Method not allowed' }, 405);
 if (new URL(request.url).search) return json({ error: 'Query parameters forbidden' }, 400);
 const id = request.headers.get('X-Photo-Id') || '';
 const { ext, mime } = photoExtAndMime(request.headers.get('X-Photo-Mime') || request.headers.get('Content-Type'));
 if (!/^[a-f0-9]{64}$/.test(id)) return json({ error: 'Photo identity required' }, 400);
 const owner = await hash(uid);
 const key = `photos/${owner}/${id}.${ext}`;
 if (request.method === 'GET') {
  try {
   const object = await env.VIDEO_BUCKET.head(key);
   return object && object.size > 0
    ? json({ url: `${VIDEO_ORIGIN}/${key}`, bytes: object.size, retention: 'forever', provider: 'r2' })
    : json({ error: 'Photo not found' }, 404);
  } catch {
   return json({ error: 'Photo lookup unavailable' }, 503);
  }
 }
 if (!request.body) return json({ error: 'Photo body required' }, 400);
 try {
  const existing = await env.VIDEO_BUCKET.head(key);
  if (existing && existing.size > 0) {
   await request.body.cancel();
   return json({ url: `${VIDEO_ORIGIN}/${key}`, bytes: existing.size, retention: 'forever', provider: 'r2' });
  }
  const bytes = await boundedBytes(request, MAX_PHOTO_BYTES);
  const meta = {
   httpMetadata: { contentType: mime, cacheControl: 'public,max-age=31536000,immutable' },
   customMetadata: { owner, photo_id: id, retention: 'forever' },
  };
  let stored;
  try { stored = await env.VIDEO_BUCKET.put(key, bytes, { onlyIf: { etagDoesNotMatch: '*' }, ...meta }); }
  catch { stored = await env.VIDEO_BUCKET.put(key, bytes, meta); }
  const verified = (await env.VIDEO_BUCKET.head(key)) || stored || { size: bytes.byteLength };
  return json({ url: `${VIDEO_ORIGIN}/${key}`, bytes: verified.size, retention: 'forever', provider: 'r2' });
 } catch {
  return json({ error: 'Photo upload to R2 failed' }, 502);
 }
}

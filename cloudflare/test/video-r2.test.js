import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {uploadVideo,retryVideoCleanup,cleanTemporary,VIDEO_ORIGIN} from '../video-r2.js';
import {createWorker} from '../../worker.js';
const id='a'.repeat(64), bytes=new TextEncoder().encode('synthetic compressed video fixture');
const md5=createHash('md5').update(bytes).digest('hex');
function setup(){
 const sql=new DatabaseSync(':memory:');sql.exec(readFileSync(new URL('../migrations/0009_additive_video_processing.sql',import.meta.url),'utf8'));
 const DB={prepare(query){const stmt=sql.prepare(query);return{bind(...args){return{run:async()=>stmt.run(...args),all:async()=>({results:stmt.all(...args)})}}}}};
 const objects=new Map(),calls=[];let malformed=false,cleanupFails=false;
 const env={VIDEO_R2_ENABLED:'true',VIDEO_R2_RETENTION_VERIFIED:'true',CLOUDINARY_CLOUD_NAME:'fixture',CLOUDINARY_API_KEY:'synthetic-key',CLOUDINARY_API_SECRET:'synthetic-secret',DB,
 VIDEO_BUCKET:{async head(key){return objects.get(key)||null;},async put(key,body,options){
  assert.deepEqual(options.onlyIf,{etagDoesNotMatch:'*'});assert.equal(options.customMetadata.retention,'forever');
  const data=new Uint8Array(await new Response(body).arrayBuffer());
  assert.equal(createHash('md5').update(data).digest('hex'),Buffer.from(options.md5).toString('hex'));
  if(objects.has(key))return null;
  const value={key,size:data.length,customMetadata:options.customMetadata};objects.set(key,value);return value;
 }}};
 async function fetcher(url,options){
  calls.push(String(url));assert.equal(options.redirect,'error');
  assert.equal(options.headers?.Authorization,undefined,'Firebase bearer must never reach Cloudinary');
  if(String(url).endsWith('/destroy'))return Response.json(cleanupFails?{error:'synthetic'}:{result:'ok'},{status:cleanupFails?503:200});
  if(String(url).endsWith('/upload')){
   const form=await new Response(options.body).text();
   assert.match(form,/c_limit,w_854,h_480/);assert.match(form,/name="backup"\r\n\r\nfalse/);
   assert.match(form,/name="overwrite"\r\n\r\nfalse/);
   const public_id=/name="public_id"\r\n\r\n([^\r]+)/.exec(form)[1];
   return Response.json({public_id,resource_type:'video',format:'mp4',width:854,height:malformed?720:480,
    bytes:bytes.length,etag:md5,secure_url:'https://res.cloudinary.com/fixture/video/upload/v1/fixture.mp4'});
  }
  return new Response(bytes,{headers:{'Content-Length':String(bytes.length)}});
 }
 const request=(method='POST')=>new Request('https://example.invalid/v1/videos',{method,headers:{'X-Video-Id':id,...(method==='POST'?{'Content-Length':'3'}:{})},...(method==='POST'?{body:'raw'}:{})});
 return {env,sql,objects,calls,request,fetcher,setMalformed(){malformed=true;},failCleanup(){cleanupFails=true;},recover(){cleanupFails=false;}};
}
test('video route requires verified login and disabled configuration has no provider traffic',async()=>{
 const f=setup();try{
  const app=createWorker(async()=>{throw Error('bad');});
  assert.equal((await app.fetch(f.request(),{FIREBASE_PROJECT_ID:'demo'})).status,401);
  assert.equal((await uploadVideo(f.request(),{},'actor',()=>assert.fail('network'))).status,503);
 }finally{f.sql.close();}
});
test('480p verified bytes persist in R2 forever and only R2 URL is returned',async()=>{
 const f=setup();try{
  const response=await uploadVideo(f.request(),f.env,'actor',f.fetcher),body=await response.json();
  assert.equal(response.status,200);assert.equal(body.provider,'r2');assert.equal(body.retention,'forever');
  assert.ok(body.url.startsWith(VIDEO_ORIGIN+'/videos/'));assert.equal(body.url.includes('cloudinary'),false);
  assert.equal(f.objects.size,1);assert.equal(f.sql.prepare("SELECT COUNT(*) n FROM friendbook_video_processing WHERE state='cleaned'").get().n,1);
  const count=f.calls.length;
  assert.equal((await uploadVideo(f.request('GET'),f.env,'actor',f.fetcher)).status,200);
  assert.equal((await uploadVideo(f.request(),f.env,'actor',f.fetcher)).status,200);assert.equal(f.calls.length,count);
  assert.equal((await uploadVideo(f.request('GET'),f.env,'different-actor',f.fetcher)).status,404);
 }finally{f.sql.close();}
});
test('malformed high-resolution output never publishes; provider failure cleanup is retried without deleting R2',async()=>{
 const f=setup();try{
  f.setMalformed();assert.equal((await uploadVideo(f.request(),f.env,'actor',f.fetcher)).status,502);assert.equal(f.objects.size,0);
 }finally{f.sql.close();}
 const g=setup();try{
  g.failCleanup();assert.equal((await uploadVideo(g.request(),g.env,'actor',g.fetcher)).status,200);
  assert.equal(g.sql.prepare("SELECT COUNT(*) n FROM friendbook_video_processing WHERE state='pending'").get().n,1);
  g.recover();await retryVideoCleanup(g.env,g.fetcher);assert.equal(g.objects.size,1);
  assert.equal(g.sql.prepare("SELECT COUNT(*) n FROM friendbook_video_processing WHERE state='cleaned'").get().n,1);
  await assert.rejects(cleanTemporary(g.env,'legacy-original',()=>assert.fail('network')));
 }finally{g.sql.close();}
});
test('no 10GB bucket stop or age deletion: existing large usage does not block new upload',async()=>{
 const f=setup();try{
  f.objects.set('legacy-large-video',{size:20_000_000_000,uploaded:new Date(0)});
  assert.equal((await uploadVideo(f.request(),f.env,'actor',f.fetcher)).status,200);
  assert.equal(f.objects.get('legacy-large-video').size,20_000_000_000);
  assert.equal(f.objects.size,2);
 }finally{f.sql.close();}
});

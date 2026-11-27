import test from 'node:test';
import assert from 'node:assert/strict';
import {scanUploadMedia} from '../content-safety.js';
const sample='data:image/jpeg;base64,/9j/4AAQSkZJRg==';
const request=body=>new Request('https://worker.test/v1/safety/scan',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});

test('Cloudflare AI only allows an exact ALLOW answer and inspects supplied samples',async()=>{
 let args;
 const response=await scanUploadMedia(request({images:[sample,sample]}),{AI:{async run(model,input){args={model,input};return {response:'ALLOW'};}}});
 assert.equal(response.status,200);assert.deepEqual(await response.json(),{blocked:false,category:''});
 assert.equal(args.model,'@cf/meta/llama-3.2-11b-vision-instruct');
 assert.equal(args.input.messages[0].content.filter(part=>part.type==='image_url').length,2);
 assert.equal(args.input.temperature,0);
});

test('Cloudflare AI blocks explicit content and fails closed on ambiguous responses',async()=>{
 for(const answer of ['BLOCK','Probably ALLOW','']){
  const response=await scanUploadMedia(request({images:[sample]}),{AI:{async run(){return {response:answer};}}});
  assert.equal(response.status,200);assert.equal((await response.json()).blocked,true);
 }
});

test('Cloudflare AI safety route rejects invalid payloads and fails closed when unconfigured',async()=>{
 const bad=await scanUploadMedia(request({images:['https://attacker.invalid/image.jpg']}),{AI:{async run(){throw Error('must not run');}}});
 assert.equal(bad.status,400);
 const missing=await scanUploadMedia(request({images:[sample]}),{});
 assert.equal(missing.status,503);
 const broken=await scanUploadMedia(request({images:[sample]}),{AI:{async run(){throw Error('unavailable');}}});
 assert.equal(broken.status,503);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {scanUploadMedia} from '../content-safety.js';
const sample='data:image/jpeg;base64,/9j/4AAQSkZJRg==';
const request=body=>new Request('https://worker.test/v1/safety/scan',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)});

test('Cloudflare AI only allows an exact ALLOW answer and inspects supplied samples',async()=>{
 let args;
 const response=await scanUploadMedia(request({images:[sample,sample]}),{AI:{async run(model,input){args={model,input};return {answers:{decision:{value:'ALLOW',confidence:0.95}}};}}});
 assert.equal(response.status,200);assert.deepEqual(await response.json(),{blocked:false,category:''});
 assert.equal(args.model,'@cf/cloudflare/clairic');
 assert.equal(args.input.model,'clairic');
 assert.equal(args.input.images.length,2);
 assert.equal(args.input.fields.decision.type,'enum');
});

test('Cloudflare AI blocks explicit content and fails closed on ambiguous responses',async()=>{
 for(const result of [
  {answers:{decision:{value:'BLOCK',confidence:0.97}}},
  {answers:{decision:{value:'ALLOW',confidence:0.42}}},
  {answers:{}},
 ]){
  const response=await scanUploadMedia(request({images:[sample]}),{AI:{async run(){return result;}}});
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
 const exhausted=await scanUploadMedia(request({images:[sample]}),{AI:{async run(){throw Error('Workers AI daily neuron quota exceeded');}}});
 assert.equal(exhausted.status,429);assert.equal((await exhausted.json()).fallback,'on_device');
});

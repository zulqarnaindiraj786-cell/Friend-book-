import { test } from 'node:test';
import assert from 'node:assert/strict';
globalThis.Deno = { env: { get: name => name === 'CLOUDFLARE_WORKER_URL' ? 'https://worker.example' : undefined } };
const { handleRequest } = await import('../.test-build/counter-api.mjs');

test('legacy counter route forwards to the Cloudflare Worker counter API', async () => {
  const original=globalThis.fetch; let call;
  globalThis.fetch=async(url,init)=>{call={url:String(url),init}; return Response.json({ready:true,counters:[]});};
  try {
    const body='{"action":"read"}';
    const response=await handleRequest(new Request('https://legacy.example/functions/v1/counter-state',{method:'POST',headers:{Authorization:'Bearer firebase-session','Content-Type':'application/json'},body}));
    assert.equal(response.status,200); assert.deepEqual(await response.json(),{ready:true,counters:[]});
    assert.equal(call.url,'https://worker.example/v1/views'); assert.equal(await new Response(call.init.body).text(),body);
  } finally { globalThis.fetch=original; }
});

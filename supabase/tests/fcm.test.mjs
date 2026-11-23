import { test } from 'node:test';
import assert from 'node:assert/strict';
globalThis.Deno = { env: { get: name => name === 'CLOUDFLARE_WORKER_URL' ? 'https://worker.example' : undefined } };
const { handleRequest } = await import('../.test-build/gateway.mjs');

test('FCM compatibility route forwards only to Cloudflare D1-backed gateway', async () => {
  const original=globalThis.fetch; let call;
  globalThis.fetch=async(url,init)=>{call={url:String(url),init}; return Response.json({accepted:1});};
  try {
    const body='{"action":"register","device_id":"device-1","token":"long-fcm-token"}';
    const response=await handleRequest(new Request('https://legacy.example/functions/v1/send-fcm-notification',{method:'POST',headers:{Authorization:'Bearer firebase-session','Content-Type':'application/json'},body}));
    assert.equal(response.status,200); assert.deepEqual(await response.json(),{accepted:1});
    assert.equal(call.url,'https://worker.example/functions/v1/send-fcm-notification');
    assert.equal(call.init.headers.get('authorization'),'Bearer firebase-session'); assert.equal(await new Response(call.init.body).text(),body);
  } finally { globalThis.fetch=original; }
});

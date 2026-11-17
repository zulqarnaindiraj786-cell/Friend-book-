import { test } from 'node:test';
import assert from 'node:assert/strict';
globalThis.Deno = { env: { get: name => name === 'CLOUDFLARE_WORKER_URL' ? 'https://worker.example' : undefined } };
const { handleRequest } = await import('../.test-build/bulk.mjs');

test('bulk compatibility route forwards authenticated request to Cloudflare D1 gateway', async () => {
  const original = globalThis.fetch;
  let call;
  globalThis.fetch = async (url, init) => {
    call = {url:String(url), init};
    return Response.json({inbox:2, accepted:1});
  };
  try {
    const request = new Request('https://legacy.example/functions/v1/bulk-mentions?source=app', {
      method:'POST', headers:{Authorization:'Bearer firebase-session','Content-Type':'application/json'}, body:'{"action":"bulk_mention"}'
    });
    const response = await handleRequest(request);
    assert.equal(response.status,200);
    assert.deepEqual(await response.json(),{inbox:2,accepted:1});
    assert.equal(call.url,'https://worker.example/functions/v1/bulk-mentions?source=app');
    assert.equal(call.init.headers.get('authorization'),'Bearer firebase-session');
    assert.equal(await new Response(call.init.body).text(),'{"action":"bulk_mention"}');
  } finally { globalThis.fetch = original; }
});

test('bulk compatibility returns bounded unavailable response when Worker cannot be reached', async () => {
  const original=globalThis.fetch; globalThis.fetch=async()=>{throw Error('offline');};
  try { const response=await handleRequest(new Request('https://legacy.example/',{method:'POST',body:'{}'})); assert.equal(response.status,503); }
  finally { globalThis.fetch=original; }
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import worker from '../../worker.js';
import { buildAssets, publicFiles } from '../build-assets.mjs';

await buildAssets();
const assetRoot = new URL('../../.worker-assets/', import.meta.url);
const call = (path, env, method = 'GET') => worker.fetch(new Request(`https://example.invalid${path}`, { method }), env);
const env = {
  DB: { prepare() { assert.fail('Frontend must not access D1'); } },
  ASSETS: { async fetch(request) {
    const name = new URL(request.url).pathname.slice(1);
    if (!publicFiles.includes(name)) return new Response('Not found', { status: 404 });
    const data = await readFile(new URL(name, assetRoot));
    return new Response(request.method === 'HEAD' ? null : data, {
      headers: { 'Content-Type': name.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream' },
    });
  } },
};
test('root/login/signup including deep links return real landing HTML without database calls', async () => {
  for (const path of ['/', '/login', '/signup', '/login/', '/signup/', '/login?next=/v1/me']) {
    const response = await call(path, env);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type'), /text\/html/);
    const html = await response.text();
    assert.match(html, /<!DOCTYPE html>/i);
    assert.match(html, /<base href="\/"/);
    assert.match(html, /https:\/\/play\.google\.com\/store\/apps\/details\?id=com\.friendbook\.social/);
    assert.doesNotMatch(html, /github\.com|friendbook\.apk/i);
  }
  const head = await call('/signup', env, 'HEAD');
  assert.equal(head.status, 200); assert.equal(await head.text(), '');
});
test('API failures stay JSON even with working frontend assets', async () => {
  for (const path of ['/v1', '/v1/unknown', '/health/unknown']) {
    const response = await call(path, env);
    assert.equal(response.status, 404); assert.match(response.headers.get('Content-Type'), /application\/json/);
  }
  assert.equal((await call('/v1/me', { ...env, FIREBASE_PROJECT_ID: 'friendbook-17551' })).status, 401);
  assert.equal((await call('/health', { ASSETS: env.ASSETS })).status, 503);
});
test('static files work; private paths, missing files and frontend writes do not fall back to index', async () => {
  assert.equal((await call('/logo.png', env)).status, 200);
  for (const path of ['/worker.js', '/wrangler.toml', '/testing/counter-core.md', '/missing.png']) {
    assert.equal((await call(path, env)).status, 404);
  }
  assert.equal((await call('/login', env, 'POST')).status, 405);
  assert.equal((await call('/', {})).status, 503);
  assert.deepEqual((await readdir(assetRoot)).sort(), [...publicFiles].sort());
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import worker, { createWorker } from '../../worker.js';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');

function sources(dir) {
  const out = [];
  for (const name of readdirSync(new URL(dir, root))) {
    const rel = `${dir}/${name}`;
    if (statSync(new URL(rel, root)).isDirectory()) out.push(...sources(rel));
    else out.push(rel);
  }
  return out;
}

test('neither the app nor the Worker runtime references removed status endpoints', () => {
  const files = ['worker.js', 'wrangler.toml',
    ...sources('app/src/main/java'), ...sources('app/src/main/res')]
    .filter(path => !/\.(png|jpg|jpeg|webp|gif|ttf|otf|xml\.bak)$/.test(path))
    .concat(sources('cloudflare').filter(path => !path.includes('/test/') && !path.includes('/migrations/')));
  for (const path of files) {
    const text = read(path);
    assert.doesNotMatch(text, /\/v1\/presence/i, path);
    assert.doesNotMatch(text, /\bpresence\b/i, path);
    assert.doesNotMatch(text, /\bonlineDot\b/, path);
  }
});

test('the Worker exports no scheduled handler and the config schedules no cron', () => {
  const config = read('wrangler.toml');
  assert.doesNotMatch(config, /^\s*\[triggers\]/m, 'no [triggers] block');
  assert.doesNotMatch(config, /^\s*crons\s*=/m, 'no cron expressions');
  assert.equal('scheduled' in worker, false);
  assert.doesNotMatch(read('worker.js'), /\bscheduled\s*\(/);
  assert.doesNotMatch(read('worker.js'), /maintenance\.js/);
});

test('chat, feed and presence-free requests still reach the Worker exactly as before', async () => {
  const app = createWorker(async () => 'verified-user');
  const calls = [];
  const env = { FIREBASE_PROJECT_ID: 'friendbook-17551', DB: { prepare(q) { calls.push(q); throw Error('database offline'); } } };
  for (const route of ['/v1/presence/me', '/v1/presence/contact/peer', '/v1/presence/socket']) {
    const response = await app.fetch(new Request(`https://local.invalid${route}`, {
      headers: { authorization: 'Bearer test' },
    }), env);
    assert.equal(response.status, 404, route);
  }
  assert.equal(calls.length, 0, 'removed routes never query D1');
});

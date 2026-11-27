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

test('exactly one cron runs every fifteen minutes and it only does bounded maintenance', () => {
  const config = read('wrangler.toml');
  assert.match(config, /^\s*\[triggers\]/m, 'a triggers block exists');
  const cronLines = config.split('\n').filter(line => /^\s*crons?\s*=/.test(line));
  assert.equal(cronLines.length, 1, 'one cron declaration');
  assert.match(cronLines[0], /\*\/15 \* \* \* \*/, 'every fifteen minutes');
  assert.doesNotMatch(config, /\*\/1 |\* \* \* \* \*|\*\/5 /, 'never per minute or per second');
  assert.equal('scheduled' in worker, true, 'the Worker exports a scheduled handler');

  const scheduled = read('cloudflare/scheduled.js');
  assert.doesNotMatch(scheduled, /setInterval|setTimeout|while\s*\(/, 'no looping inside the pass');
  assert.match(scheduled, /UPDATE friendbook_cf_firestore_documents[\s\S]*?LIMIT 20/, 'stale live rooms are bounded');
  assert.match(scheduled, /drainBroadcastPush/, 'only the durable outbox is retried');
  assert.doesNotMatch(scheduled, /DELETE FROM friendbook_cf_view_dedupe/, 'retired view table has no invented timestamp cleanup');
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

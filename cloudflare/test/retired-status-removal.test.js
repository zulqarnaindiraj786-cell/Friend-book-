import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {runMaintenance} from '../maintenance.js';

const removed = ['friendbook_cf_presence', 'friendbook_cf_presence_sessions',
  'friendbook_cf_presence_sessions_fullcopy', 'friendbook_cf_presence_sessions_copy',
  'friendbook_cf_typing', 'friendbook_cf_typing_sessions', 'friendbook_cf_typing_sessions_fullcopy'];

test('migration removes only the seven retired tables and is repeatable', t => {
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  for (const name of [...removed, 'friendbook_cf_firestore_documents', 'friendbook_cf_documents',
    'friendbook_cf_chat_participants', 'friendbook_cf_trusted_friendships']) {
    db.exec(`CREATE TABLE ${name}(value TEXT); INSERT INTO ${name} VALUES ('keep');`);
  }
  const sql = readFileSync(new URL('../migrations/0086_remove_unused_status_tables.sql', import.meta.url), 'utf8');
  db.exec(sql); db.exec(sql);
  for (const name of removed)
    assert.equal(db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name=?").get(name), undefined);
  for (const name of ['friendbook_cf_firestore_documents', 'friendbook_cf_documents',
    'friendbook_cf_chat_participants', 'friendbook_cf_trusted_friendships'])
    assert.equal(db.prepare(`SELECT value FROM ${name}`).get().value, 'keep');
});

test('maintenance still executes independent video and push jobs without querying retired tables', async () => {
  const ran = [], errors = [];
  const env = {DB: {prepare(){throw Error('unexpected DB');}}};
  await runMaintenance(env, {now: 123, videoCleanup: async () => {ran.push('video'); throw Error('private');},
    broadcastPush: async (_env, now) => {assert.equal(now,123); ran.push('push');},
    report: message => errors.push(JSON.parse(message))});
  assert.deepEqual(ran.sort(), ['push','video']);
  assert.deepEqual(errors, [{event:'friendbook.maintenance_failed',job:'temporary-video'}]);
});

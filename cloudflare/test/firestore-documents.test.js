import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import { createWorker } from '../../worker.js';

const schema = readFileSync(new URL('../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8');

test('Firestore-compatible D1 endpoint accepts Firebase UIDs containing x', async () => {
  const sql = new DatabaseSync(':memory:');
  sql.exec(schema);
  const db = {
    prepare(query) {
      const stmt = sql.prepare(query);
      return {
        bind(...args) {
          return {
            run: async () => stmt.run(...args),
            all: async () => ({results: stmt.all(...args)}),
            first: async () => stmt.get(...args) || null,
          };
        },
      };
    },
  };
  const uid = 'FF3hGwZJlTMp9r4q2eTAXbgsQMx2';
  const app = createWorker(async () => uid);
  const response = await app.fetch(new Request(`https://example.test/v1/firestore/document?path=users/${uid}`, {
    method: 'PUT',
    headers: {authorization: 'Bearer proof-token', 'content-type': 'application/json'},
    body: JSON.stringify({data: {uid, displayName: 'D1 proof', proofMarker: 'd1-only'}}),
  }), {DB: db, FIREBASE_PROJECT_ID: 'friendbook-test'});
  assert.equal(response.status, 200, await response.text());
  const row = sql.prepare('SELECT path, owner_uid, document_json FROM friendbook_cf_firestore_documents WHERE path = ?').get(`users/${uid}`);
  assert.equal(row.path, `users/${uid}`);
  assert.equal(row.owner_uid, uid);
  assert.match(row.document_json, /d1-only/);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';

const migration = name => readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8');

test('social guards validate without persisting or deleting successful assertion rows', () => {
  const db = new DatabaseSync(':memory:');
  db.exec(migration('0018_social_guards.sql'));
  db.exec(migration('0113_row_free_social_guards.sql'));

  assert.equal(db.prepare("SELECT type FROM sqlite_schema WHERE name='friendbook_cf_social_guards'").get().type, 'view');
  const count = () => db.prepare('SELECT count(*) AS n FROM friendbook_cf_social_guards').get().n;
  const writes = () => db.prepare('SELECT total_changes() AS n').get().n;

  const before = writes();
  db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ?=1 THEN 1 ELSE 0 END').run(1);
  db.prepare('DELETE FROM friendbook_cf_social_guards').run();
  assert.equal(count(), 0);
  assert.equal(writes(), before, 'successful assertions and compatibility cleanup are row-free');

  assert.throws(() => db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT CASE WHEN ?=1 THEN 1 ELSE 0 END').run(0), /CHECK constraint failed: ok = 1/);
  assert.throws(() => db.prepare('INSERT INTO friendbook_cf_social_guards(ok) SELECT NULL').run(), /CHECK constraint failed: ok = 1/);
  assert.equal(count(), 0);
  db.close();
});

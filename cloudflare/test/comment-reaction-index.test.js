import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fixture} from './fixtures/interaction-db.js';
import {commentSelect} from '../comment-api.js';

const migration=readFileSync(new URL('../migrations/0103_comment_reaction_covering_index.sql',import.meta.url),'utf8');

test('comment reaction summary reads count and viewer type through one covering index probe',t=>{
  const f=fixture();t.after(f.close);
  f.sql.exec(migration);f.sql.exec(migration); // repeatable migration
  const sql=commentSelect+' WHERE c.resource=?2 AND c.id=?3';
  const plan=f.sql.prepare('EXPLAIN QUERY PLAN '+sql).all('viewer','posts/p','comment').map(row=>row.detail).join('\n');
  assert.match(plan,/CORRELATED SCALAR SUBQUERY/);
  assert.match(plan,/SEARCH r USING COVERING INDEX idx_cf_comment_reaction_cover \(resource=\? AND id=\?\)/);
  assert.doesNotMatch(plan,/SEARCH r USING INDEX friendbook_cf_comment_rx/);
});

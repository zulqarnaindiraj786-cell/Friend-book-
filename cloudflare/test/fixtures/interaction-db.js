import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
export function fixture() {
  const sql = new DatabaseSync(':memory:');
  sql.exec('PRAGMA foreign_keys=ON');
  sql.exec(readFileSync(new URL('../../migrations/0003_additive_interactions.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0004_additive_comment_details.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0005_additive_reaction_history.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0006_additive_comment_history.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0008_additive_legacy_comment_totals.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0011_additive_app_documents.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0014_additive_firestore_documents.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0087_canonical_comment_page_indexes.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0018_social_guards.sql', import.meta.url), 'utf8'));
  sql.exec(readFileSync(new URL('../../migrations/0050_comment_totals.sql', import.meta.url), 'utf8'));
  sql.exec("INSERT INTO friendbook_cf_interaction_resources VALUES ('posts/p1','verified'),('posts/p2','staging')");
  const DB = { async batch(list){sql.exec('BEGIN');try{const result=[];for(const q of list)result.push(await q.run());sql.exec('COMMIT');return result;}catch(e){sql.exec('ROLLBACK');throw e;}}, withSession: () => DB, prepare(query) {
    const stmt = sql.prepare(query);
    const bound = args => ({ first: async () => stmt.get(...args) || null,
      all: async () => ({ results: stmt.all(...args) }), run: async () => /^SELECT\b/i.test(query.trim()) ? { results: stmt.all(...args) } : stmt.run(...args) });
    return { ...bound([]), bind: (...args) => bound(args) };
  } };
  const env = { DB, D1_INTERACTIONS_ENABLED: 'true' };
  return { sql, env, close: () => sql.close() };
}

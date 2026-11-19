import { drainBroadcastPush } from './fcm-push.js';
import { retryVideoCleanup } from './video-r2.js';

const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const BATCH_SIZE = 100;

export async function pruneExpiredRealtime(db, now) {
  if (!db) throw new Error('D1 binding missing');
  const cutoff = now - RETENTION_MS;
  // Keep one presence row per user: deleting the final row would re-enable the
  // historical presence fallback and could expose outdated visibility state.
  const results = await Promise.allSettled([
    db.prepare(`DELETE FROM friendbook_cf_presence_sessions WHERE rowid IN (
      SELECT s.rowid FROM friendbook_cf_presence_sessions s WHERE s.last_seen < ?
        AND EXISTS (SELECT 1 FROM friendbook_cf_presence_sessions newer WHERE newer.uid=s.uid
          AND (newer.last_seen>s.last_seen OR (newer.last_seen=s.last_seen AND newer.session_id>s.session_id)))
      ORDER BY s.last_seen LIMIT ?
    )`).bind(cutoff,BATCH_SIZE).run(),
    db.prepare(`DELETE FROM friendbook_cf_typing_sessions WHERE rowid IN (
      SELECT rowid FROM friendbook_cf_typing_sessions WHERE updated_at < ? ORDER BY updated_at LIMIT ?
    )`).bind(cutoff,BATCH_SIZE).run(),
  ]);
  if (results.some(result => result.status === 'rejected')) throw new Error('Realtime maintenance incomplete');
}

export async function runMaintenance(env, {now = Date.now(), videoCleanup = retryVideoCleanup, broadcastPush = drainBroadcastPush, report = console.error} = {}) {
  const jobs = [['realtime', () => pruneExpiredRealtime(env.DB, now)], ['temporary-video', () => videoCleanup(env)], ['broadcast-push', () => broadcastPush(env,now)]];
  await Promise.all(jobs.map(async ([job, run]) => {
    try { await run(); }
    catch { report(JSON.stringify({event:'friendbook.maintenance_failed',job})); }
  }));
}

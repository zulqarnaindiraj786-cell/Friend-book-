import { drainBroadcastPush } from './fcm-push.js';
import { retryVideoCleanup } from './video-r2.js';

const RETENTION_MS = 7 * 24 * 60 * 60 * 1000;
const BATCH_SIZE = 100;
// Single small maintenance checkpoint. Contains no user IDs or presence
// values and never touches videos or the view-counter snapshot. The bucket may
// also be publicly served, so the checkpoint must not hold sensitive data.
export const PRESENCE_CURSOR_KEY = 'maintenance/presence-cleanup-cursor-v1.json';

async function readCursor(bucket) {
  if (!bucket || typeof bucket.get !== 'function' || typeof bucket.put !== 'function')
    throw new Error('Presence maintenance checkpoint unavailable');
  const object=await bucket.get(PRESENCE_CURSOR_KEY);
  if (!object) return null;
  if (typeof object.size === 'number' && object.size > 256) throw new Error('Invalid presence checkpoint');
  const value=await object.json();
  if (!value || value.v!==1 || (value.after!==null &&
      (!Array.isArray(value.after) || value.after.length!==2 || !value.after.every(Number.isSafeInteger))))
    throw new Error('Invalid presence checkpoint');
  return value.after;
}

// One pass can examine at most 100 old sessions, including the last row per
// user that must be retained. Checkpointing advances past retained rows; when
// the end is reached it wraps, so late/out-of-order rows are revisited too.
// Failed R2/D1 operations never advance the checkpoint, and repeated or
// concurrent passes are safe: the DELETE independently rechecks expiry and
// the existence of a newer/tie-winning session at the time of deletion.
async function prunePresence(db, bucket, cutoff) {
  const cursor=await readCursor(bucket);
  let rows=[];
  if (cursor && cursor[0]<cutoff) {
    const same=await db.prepare(`SELECT last_seen,rowid FROM friendbook_cf_presence_sessions
      WHERE last_seen=? AND rowid>? AND last_seen<? ORDER BY rowid LIMIT ?`)
      .bind(cursor[0],cursor[1],cutoff,BATCH_SIZE).all();
    if (same?.success!==true || !Array.isArray(same.results)) throw new Error('Presence maintenance read failed');
    rows=same.results;
  }
  if (rows.length<BATCH_SIZE) {
    const later=cursor ? await db.prepare(`SELECT last_seen,rowid FROM friendbook_cf_presence_sessions
        WHERE last_seen>? AND last_seen<? ORDER BY last_seen,rowid LIMIT ?`)
        .bind(cursor[0],cutoff,BATCH_SIZE-rows.length).all()
      : await db.prepare(`SELECT last_seen,rowid FROM friendbook_cf_presence_sessions
        WHERE last_seen<? ORDER BY last_seen,rowid LIMIT ?`).bind(cutoff,BATCH_SIZE).all();
    if (later?.success!==true || !Array.isArray(later.results)) throw new Error('Presence maintenance read failed');
    rows.push(...later.results);
  }
  if (rows.length>BATCH_SIZE || rows.some(r=>!Number.isSafeInteger(r.rowid) || !Number.isSafeInteger(r.last_seen)))
    throw new Error('Invalid presence maintenance page');
  if (!rows.length) {
    if (cursor) await bucket.put(PRESENCE_CURSOR_KEY,JSON.stringify({v:1,after:null}));
    return;
  }
  const deletion=await db.prepare(`DELETE FROM friendbook_cf_presence_sessions WHERE rowid IN (SELECT value FROM json_each(?))
    AND last_seen < ? AND (EXISTS (SELECT 1 FROM friendbook_cf_presence_sessions newer
      WHERE newer.uid=friendbook_cf_presence_sessions.uid AND newer.last_seen>friendbook_cf_presence_sessions.last_seen)
      OR EXISTS (SELECT 1 FROM friendbook_cf_presence_sessions tied
        WHERE tied.uid=friendbook_cf_presence_sessions.uid AND tied.last_seen=friendbook_cf_presence_sessions.last_seen
          AND tied.session_id>friendbook_cf_presence_sessions.session_id))`)
    .bind(JSON.stringify(rows.map(row=>row.rowid)),cutoff).run();
  if (deletion?.success!==true) throw new Error('Presence maintenance delete failed');
  const last=rows[rows.length-1];
  await bucket.put(PRESENCE_CURSOR_KEY,JSON.stringify({v:1,after:[last.last_seen,last.rowid]}));
}

export async function pruneExpiredRealtime(db, now, bucket, onlineStatusDisabled = false, typingDisabled = false) {
  if (!db) throw new Error('D1 binding missing');
  const cutoff = now - RETENTION_MS;
  // Retain at least one row per uid so old legacy presence cannot reappear.
  // A missing checkpoint store defers only presence cleanup, never permission
  // checks or typing cleanup; it must not fall back to an unbounded scan.
  const results = await Promise.allSettled([
    ...(onlineStatusDisabled ? [] : [prunePresence(db,bucket,cutoff)]),
    ...(typingDisabled ? [] : [db.prepare(`DELETE FROM friendbook_cf_typing_sessions WHERE rowid IN (
      SELECT rowid FROM friendbook_cf_typing_sessions WHERE updated_at < ? ORDER BY updated_at LIMIT ?
    )`).bind(cutoff,BATCH_SIZE).run()]),
  ]);
  if (results.some(result => result.status === 'rejected')) throw new Error('Realtime maintenance incomplete');
}

export async function runMaintenance(env, {now = Date.now(), videoCleanup = retryVideoCleanup, broadcastPush = drainBroadcastPush, report = console.error} = {}) {
  const jobs = [['realtime', () => pruneExpiredRealtime(env.DB, now, env.VIDEO_BUCKET, env.ONLINE_STATUS_DISABLED === 'true', env.TYPING_DISABLED === 'true')], ['temporary-video', () => videoCleanup(env)]];
  if (broadcastPush) jobs.push(['broadcast-push', () => broadcastPush(env,now)]);;
  await Promise.all(jobs.map(async ([job, run]) => {
    try { await run(); }
    catch { report(JSON.stringify({event:'friendbook.maintenance_failed',job})); }
  }));
}

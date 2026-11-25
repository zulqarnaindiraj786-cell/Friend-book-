// Fifteen-minute maintenance only. These indexed/bounded tasks still have D1
// cost; ordinary app paths remain request/push driven.
import { drainBroadcastPush } from './fcm-push.js';

const STALE_LIVE_MS = 3 * 60 * 1000;

export async function scheduled(event, env) {
  if (!env.DB) return;
  const now = Date.now();
  // Isolate synchronous prepare failures as well as rejected D1 promises so
  // one maintenance task cannot prevent independent outbox retries.
  const run = task => Promise.resolve().then(task).catch(() => null);
  await Promise.all([
    run(() => env.DB.prepare(`UPDATE friendbook_cf_firestore_documents
      SET document_json = json_set(document_json,'$.status','ended','$.endedAtMs',?)
      WHERE path IN (SELECT path FROM friendbook_cf_firestore_documents
        WHERE parent_path='lives'
          AND json_extract(document_json,'$.status')='live'
          AND COALESCE(json_extract(document_json,'$.lastHeartbeatMs'),0) < ?
        LIMIT 20)`).bind(now, now - STALE_LIVE_MS).run()),
    run(() => drainBroadcastPush(env, now)),
  ]);
  // The retired view-dedupe table has (resource,uid,bucket), not created_at.
  // Views use Analytics Engine, not that table. Never repeatedly issue an
  // invalid DELETE or invent a destructive retention rule for historical rows.
}

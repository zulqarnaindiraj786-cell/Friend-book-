// The only scheduled work is a 15-minute maintenance pass. Everything else
// (chat, notifications, feed, calls) is driven by requests and push, so an idle
// app or Worker costs nothing.
import { drainBroadcastPush } from './fcm-push.js';

const STALE_LIVE_MS = 3 * 60 * 1000;
const STALE_DEDUPE_MS = 24 * 60 * 60 * 1000;

export async function scheduled(event, env) {
  if (!env.DB) return;
  const now = Date.now();
  const tasks = [];

  // 1. Close live rooms whose host disappeared without ending them. Bounded to a
  //    small window so a stale backlog cannot spend a whole run here.
  tasks.push(env.DB.prepare(`UPDATE friendbook_cf_firestore_documents
      SET document_json = json_set(document_json,'$.status','ended','$.endedAtMs',?)
      WHERE parent_path='lives'
        AND json_extract(document_json,'$.status')='live'
        AND COALESCE(json_extract(document_json,'$.lastHeartbeatMs'),0) < ?
      LIMIT 20`).bind(now, now - STALE_LIVE_MS).run().catch(() => null));

  // 2. The admin broadcast outbox is the only durable retry queue.
  tasks.push(drainBroadcastPush(env, now).catch(() => null));

  // 3. Expired view-dedupe rows are pure garbage; keeping them bounded keeps the
  //    read path's index small.
  tasks.push(env.DB.prepare('DELETE FROM friendbook_cf_view_dedupe WHERE created_at < ? LIMIT 200')
    .bind(now - STALE_DEDUPE_MS).run().catch(() => null));

  await Promise.all(tasks);
}

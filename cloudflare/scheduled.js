// Fifteen-minute maintenance only. These indexed/bounded tasks still have D1
// cost; ordinary app paths remain request/push driven.
import { drainBroadcastPush } from './fcm-push.js';

const STALE_LIVE_MS = 3 * 60 * 1000;
const READ_NOTIFICATION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const UNREAD_NOTIFICATION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const NOTIFICATION_RETENTION_BATCH = 200;
const NOTIFICATION_CREATED_AT_MS = `CASE
  WHEN json_type(document_json,'$.createdAt') IN ('integer','real')
    THEN json_extract(document_json,'$.createdAt')
  WHEN json_type(document_json,'$.createdAt')='object'
    AND json_extract(document_json,'$.createdAt.__type')='timestamp'
    AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
    AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
    THEN json_extract(document_json,'$.createdAt.millis')
END`;

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
    run(() => env.DB.prepare(`DELETE FROM friendbook_cf_firestore_documents
      WHERE path IN (
        SELECT path FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_notification_retention
        WHERE parent_path='notifications'
          AND (json_type(document_json,'$.createdAt') IN ('integer','real')
            OR (json_type(document_json,'$.createdAt')='object'
              AND json_extract(document_json,'$.createdAt.__type')='timestamp'
              AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
              AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991))
          AND (${NOTIFICATION_CREATED_AT_MS}) IS NOT NULL
          AND ((json_type(document_json,'$.isRead')='true'
                AND (${NOTIFICATION_CREATED_AT_MS}) < ?)
            OR (json_type(document_json,'$.isRead')='false'
                AND (${NOTIFICATION_CREATED_AT_MS}) < ?))
        ORDER BY (${NOTIFICATION_CREATED_AT_MS}) ASC,doc_id ASC
        LIMIT ?
      )`).bind(now - READ_NOTIFICATION_TTL_MS,
        now - UNREAD_NOTIFICATION_TTL_MS, NOTIFICATION_RETENTION_BATCH).run()),
  ]);
  // The retired view-dedupe table has (resource,uid,bucket), not created_at.
  // Views use Analytics Engine, not that table. Never repeatedly issue an
  // invalid DELETE or invent a destructive retention rule for historical rows.
}

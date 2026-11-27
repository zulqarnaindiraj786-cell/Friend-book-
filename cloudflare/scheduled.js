// Separate frequent live/outbox work from low-priority notification retention.
// The 7/30-day cleanup runs hourly instead of on every 15-minute pass.
import { drainBroadcastPush } from './fcm-push.js';

const STALE_LIVE_MS = 3 * 60 * 1000;
const READ_NOTIFICATION_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const UNREAD_NOTIFICATION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const NOTIFICATION_RETENTION_BATCH = 200;
const NOTIFICATION_RETENTION_PER_STATE = NOTIFICATION_RETENTION_BATCH / 2;
const NOTIFICATION_CREATED_AT_MS = `CASE
  WHEN json_type(document_json,'$.createdAt') IN ('integer','real')
    THEN json_extract(document_json,'$.createdAt')
  WHEN json_type(document_json,'$.createdAt')='object'
    AND json_extract(document_json,'$.createdAt.__type')='timestamp'
    AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
    AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991
    THEN json_extract(document_json,'$.createdAt.millis')
END`;
const NOTIFICATION_VALID_CREATED_AT = `(json_type(document_json,'$.createdAt') IN ('integer','real')
  OR (json_type(document_json,'$.createdAt')='object'
    AND json_extract(document_json,'$.createdAt.__type')='timestamp'
    AND json_type(document_json,'$.createdAt.millis') IN ('integer','real')
    AND json_extract(document_json,'$.createdAt.millis') BETWEEN -9007199254740991 AND 9007199254740991))`;

export async function scheduled(event, env) {
  if (!env.DB) return;
  const now = Date.now();
  // Keep 15-minute live-expiry and outbox retry freshness, but defer 7/30-day
  // notification retention to the separate hourly cron. Unknown/manual events
  // run both tasks for backward-compatible local verification.
  const retentionOnly=event?.cron==='0 * * * *';
  const frequentOnly=event?.cron==='*/15 * * * *';
  // Isolate synchronous prepare failures as well as rejected D1 promises so
  // one maintenance task cannot prevent independent outbox retries.
  const run = task => Promise.resolve().then(task).catch(() => null);
  const tasks=[];
  if (!retentionOnly) {
    tasks.push(run(() => env.DB.prepare(`UPDATE friendbook_cf_firestore_documents
      SET document_json = json_set(document_json,'$.status','ended','$.endedAtMs',?)
      WHERE path IN (SELECT path FROM friendbook_cf_firestore_documents
        WHERE parent_path='lives'
          AND json_extract(document_json,'$.status')='live'
          AND COALESCE(json_extract(document_json,'$.lastHeartbeatMs'),0) < ?
        LIMIT 20)`).bind(now, now - STALE_LIVE_MS).run()));
    tasks.push(run(() => drainBroadcastPush(env, now)));
  }
  if (!frequentOnly) {
    tasks.push(run(() => env.DB.prepare(`DELETE FROM friendbook_cf_firestore_documents
      WHERE path IN (
        WITH read_candidates AS (
          SELECT path,(${NOTIFICATION_CREATED_AT_MS}) AS retention_time,doc_id
          FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_notification_retention_state
          WHERE parent_path='notifications' AND json_type(document_json,'$.isRead') IN ('true','false')
            AND json_type(document_json,'$.isRead')='true' AND ${NOTIFICATION_VALID_CREATED_AT}
            AND (${NOTIFICATION_CREATED_AT_MS}) < ?
          ORDER BY (${NOTIFICATION_CREATED_AT_MS}) ASC,doc_id ASC LIMIT ?
        ), unread_candidates AS (
          SELECT path,(${NOTIFICATION_CREATED_AT_MS}) AS retention_time,doc_id
          FROM friendbook_cf_firestore_documents INDEXED BY idx_cf_notification_retention_state
          WHERE parent_path='notifications' AND json_type(document_json,'$.isRead') IN ('true','false')
            AND json_type(document_json,'$.isRead')='false' AND ${NOTIFICATION_VALID_CREATED_AT}
            AND (${NOTIFICATION_CREATED_AT_MS}) < ?
          ORDER BY (${NOTIFICATION_CREATED_AT_MS}) ASC,doc_id ASC LIMIT ?
        )
        SELECT path FROM read_candidates
        UNION ALL
        SELECT path FROM unread_candidates
      )`).bind(now - READ_NOTIFICATION_TTL_MS, NOTIFICATION_RETENTION_PER_STATE,
        now - UNREAD_NOTIFICATION_TTL_MS, NOTIFICATION_RETENTION_PER_STATE).run()));
  }
  await Promise.all(tasks);
  // The retired view-dedupe table has (resource,uid,bucket), not created_at.
  // Views use Analytics Engine, not that table. Never repeatedly issue an
  // invalid DELETE or invent a destructive retention rule for historical rows.
}

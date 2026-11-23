-- Find resumable broadcasts without scanning completed jobs.
CREATE INDEX IF NOT EXISTS idx_cf_broadcast_active_jobs
ON friendbook_cf_firestore_documents(update_time, doc_id)
WHERE parent_path='officialBroadcasts'
  AND json_extract(document_json,'$.status') IN ('queued','sending');

-- The acceptance cleanup intentionally prefers envelope fields over raw fields.
-- Existing lookup indexes use raw-first COALESCE or CASE, neither of which is
-- equivalent for mixed historical documents. Index the exact unchanged predicate.
-- Only pending requests are indexed, limiting steady-state write amplification.
CREATE INDEX IF NOT EXISTS idx_cf_legacy_pending_duplicate_pair
ON friendbook_cf_documents(
  COALESCE(json_extract(document_json,'$.fields.fromUid.stringValue'),json_extract(document_json,'$.fromUid')),
  COALESCE(json_extract(document_json,'$.fields.toUid.stringValue'),json_extract(document_json,'$.toUid'))
) WHERE collection='friend_requests'
  AND COALESCE(json_extract(document_json,'$.fields.status.stringValue'),json_extract(document_json,'$.status'))='pending';

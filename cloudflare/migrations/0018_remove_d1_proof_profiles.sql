-- Remove only synthetic profiles left by the explicit live D1 migration proof.
-- These throwaway records were visible in People You May Know and must never appear as users.
DELETE FROM friendbook_cf_documents
WHERE collection = 'users'
  AND json_valid(document_json)
  AND json_extract(document_json, '$.proofMarker') = 'd1-only'
  AND json_extract(document_json, '$.displayName') = 'Cloudflare D1 migration proof';

DELETE FROM friendbook_cf_firestore_documents
WHERE collection_id = 'users'
  AND parent_path = 'users'
  AND json_valid(document_json)
  AND json_extract(document_json, '$.proofMarker') = 'd1-only'
  AND json_extract(document_json, '$.displayName') = 'Cloudflare D1 migration proof';

DELETE FROM friendbook_cf_profiles
WHERE display_name = 'Cloudflare D1 migration proof';

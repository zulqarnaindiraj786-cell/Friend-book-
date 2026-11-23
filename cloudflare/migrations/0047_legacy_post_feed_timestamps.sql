-- Firestore's type ordering puts Timestamp wrappers ahead of every numeric
-- import, even when the numeric post is newer. Restore the original timestamp
-- type without changing the millisecond value, media, IDs, or privacy fields.
UPDATE friendbook_cf_firestore_documents
SET document_json=json_set(document_json,'$.createdAt',json_object('__type','timestamp','millis',json_extract(document_json,'$.createdAt')))
WHERE parent_path='posts' AND json_type(document_json,'$.createdAt')='integer'
  AND json_extract(document_json,'$.createdAt') BETWEEN 946684800000 AND 253402300799999;

UPDATE friendbook_cf_documents
SET document_json=json_set(document_json,'$.createdAt',json_object('__type','timestamp','millis',json_extract(document_json,'$.createdAt')))
WHERE collection='posts' AND json_type(document_json,'$.fields') IS NOT 'object'
  AND json_type(document_json,'$.createdAt')='integer'
  AND json_extract(document_json,'$.createdAt') BETWEEN 946684800000 AND 253402300799999;

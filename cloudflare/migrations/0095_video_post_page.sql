-- Reel discovery asks for video posts ordered by document ID.  Keep only video
-- entries in the seek index; exceptional IDs get a separate compatibility probe.
CREATE INDEX IF NOT EXISTS idx_cf_video_post_page
ON friendbook_cf_firestore_documents(doc_id)
WHERE parent_path='posts' AND json_extract(document_json,'$.mediaType')='video';

CREATE INDEX IF NOT EXISTS idx_cf_video_post_exceptions
ON friendbook_cf_firestore_documents(doc_id)
WHERE parent_path='posts' AND json_extract(document_json,'$.mediaType')='video'
  AND (doc_id='' OR doc_id GLOB '*[^ -~]*' OR instr(doc_id,char(0))>0 OR instr(doc_id,'/')>0 OR path<>'posts/'||doc_id);

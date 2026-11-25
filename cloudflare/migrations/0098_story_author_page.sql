-- StoryViewer orders each author's stories by document ID. The existing author
-- index orders by create_time first, so it cannot seek a document-ID page.
CREATE INDEX IF NOT EXISTS idx_cf_story_author_page
ON friendbook_cf_firestore_documents(json_extract(document_json,'$.authorId'),doc_id)
WHERE parent_path='stories';

-- Only unusual IDs/paths need the complete compatibility evaluator. Keep the
-- probe per author and sparse so ordinary stories do not add a second entry.
CREATE INDEX IF NOT EXISTS idx_cf_story_author_exceptions
ON friendbook_cf_firestore_documents(json_extract(document_json,'$.authorId'),doc_id)
WHERE parent_path='stories' AND
  (doc_id='' OR length(doc_id)>512 OR instr(doc_id,char(0))>0
   OR doc_id GLOB '*[^a-zA-Z0-9_-]*' OR path<>'stories/'||doc_id);

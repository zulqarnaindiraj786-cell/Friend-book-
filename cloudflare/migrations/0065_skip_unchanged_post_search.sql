-- Engagement and metadata changes do not alter the hashtag/mention projection.

DROP TRIGGER IF EXISTS cf_post_search_1_update;

CREATE TRIGGER IF NOT EXISTS cf_post_search_1_update AFTER UPDATE ON friendbook_cf_firestore_documents
WHEN (OLD.parent_path='posts' OR NEW.parent_path='posts')
  AND (OLD.parent_path IS NOT NEW.parent_path OR OLD.path IS NOT NEW.path
       OR json_extract(OLD.document_json,'$.content')
          IS NOT json_extract(NEW.document_json,'$.content')) BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=1;
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,1,json_extract(NEW.document_json,'$.content') WHERE NEW.parent_path='posts' AND typeof(json_extract(NEW.document_json,'$.content'))='text';
END;

DROP TRIGGER IF EXISTS cf_post_search_0_update;

CREATE TRIGGER IF NOT EXISTS cf_post_search_0_update AFTER UPDATE ON friendbook_cf_documents
WHEN (OLD.collection='posts' OR NEW.collection='posts')
  AND (OLD.collection IS NOT NEW.collection OR OLD.path IS NOT NEW.path
       OR (CASE WHEN json_type(OLD.document_json,'$.fields')='object' THEN json_extract(OLD.document_json,'$.fields.content.stringValue') ELSE json_extract(OLD.document_json,'$.content') END)
          IS NOT (CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END)) BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=0;
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,0,CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END WHERE NEW.collection='posts' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END)='text';
END;

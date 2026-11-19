-- Search projection only. Authorization uses the original D1 documents.
CREATE VIRTUAL TABLE IF NOT EXISTS friendbook_cf_post_search USING fts5(
  path UNINDEXED, source UNINDEXED, content,
  tokenize='unicode61 remove_diacritics 0 tokenchars ''#@_'''
);

INSERT INTO friendbook_cf_post_search(path,source,content) SELECT d.path,1,json_extract(d.document_json,'$.content') FROM friendbook_cf_firestore_documents d WHERE d.parent_path='posts' AND typeof(json_extract(d.document_json,'$.content'))='text';

CREATE TRIGGER IF NOT EXISTS cf_post_search_1_insert AFTER INSERT ON friendbook_cf_firestore_documents
WHEN NEW.parent_path='posts' BEGIN
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,1,json_extract(NEW.document_json,'$.content') WHERE NEW.parent_path='posts' AND typeof(json_extract(NEW.document_json,'$.content'))='text';
END;

CREATE TRIGGER IF NOT EXISTS cf_post_search_1_update AFTER UPDATE ON friendbook_cf_firestore_documents
WHEN OLD.parent_path='posts' OR NEW.parent_path='posts' BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=1;
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,1,json_extract(NEW.document_json,'$.content') WHERE NEW.parent_path='posts' AND typeof(json_extract(NEW.document_json,'$.content'))='text';
END;

CREATE TRIGGER IF NOT EXISTS cf_post_search_1_delete AFTER DELETE ON friendbook_cf_firestore_documents
WHEN OLD.parent_path='posts' BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=1;
END;

INSERT INTO friendbook_cf_post_search(path,source,content) SELECT d.path,0,CASE WHEN json_type(d.document_json,'$.fields')='object' THEN json_extract(d.document_json,'$.fields.content.stringValue') ELSE json_extract(d.document_json,'$.content') END FROM friendbook_cf_documents d WHERE d.collection='posts' AND typeof(CASE WHEN json_type(d.document_json,'$.fields')='object' THEN json_extract(d.document_json,'$.fields.content.stringValue') ELSE json_extract(d.document_json,'$.content') END)='text';

CREATE TRIGGER IF NOT EXISTS cf_post_search_0_insert AFTER INSERT ON friendbook_cf_documents
WHEN NEW.collection='posts' BEGIN
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,0,CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END WHERE NEW.collection='posts' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END)='text';
END;

CREATE TRIGGER IF NOT EXISTS cf_post_search_0_update AFTER UPDATE ON friendbook_cf_documents
WHEN OLD.collection='posts' OR NEW.collection='posts' BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=0;
  INSERT INTO friendbook_cf_post_search(path,source,content) SELECT NEW.path,0,CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END WHERE NEW.collection='posts' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.content.stringValue') ELSE json_extract(NEW.document_json,'$.content') END)='text';
END;

CREATE TRIGGER IF NOT EXISTS cf_post_search_0_delete AFTER DELETE ON friendbook_cf_documents
WHEN OLD.collection='posts' BEGIN
  DELETE FROM friendbook_cf_post_search WHERE path=OLD.path AND source=0;
END;

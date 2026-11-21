-- Only rebuild derived membership when the chat path, collection, or participants change.

DROP TRIGGER IF EXISTS cf_chat_members_1_update;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_1_update AFTER UPDATE ON friendbook_cf_firestore_documents
WHEN (OLD.parent_path='chats' OR NEW.parent_path='chats')
  AND (OLD.parent_path IS NOT NEW.parent_path OR OLD.path IS NOT NEW.path
       OR json_extract(OLD.document_json,'$.participants') IS NOT json_extract(NEW.document_json,'$.participants')) BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=1;
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT j.value, NEW.path, 1 FROM json_each(json_quote(json_extract(NEW.document_json,'$.participants'))) j WHERE NEW.parent_path='chats' AND json_type(json_quote(json_extract(NEW.document_json,'$.participants')))='array' AND typeof(j.value)='text' AND j.value<>'';
END;

DROP TRIGGER IF EXISTS cf_chat_members_0_update;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_0_update AFTER UPDATE ON friendbook_cf_documents
WHEN (OLD.collection='chats' OR NEW.collection='chats')
  AND (OLD.collection IS NOT NEW.collection OR OLD.path IS NOT NEW.path
       OR (CASE WHEN json_type(OLD.document_json,'$.fields')='object' THEN json_extract(OLD.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(OLD.document_json,'$.participants') END) IS NOT (CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END)) BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=0;
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END, NEW.path, 0 FROM json_each(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END)) j WHERE NEW.collection='chats' AND json_type(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END))='array' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END)='text' AND CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END<>'';
END;

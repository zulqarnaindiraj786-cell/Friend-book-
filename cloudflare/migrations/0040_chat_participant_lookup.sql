-- Derived membership only; authoritative chat documents and ACLs are unchanged.
CREATE TABLE IF NOT EXISTS friendbook_cf_chat_participants (
  participant_uid TEXT NOT NULL,
  chat_path TEXT NOT NULL,
  source INTEGER NOT NULL CHECK(source IN (0,1)),
  PRIMARY KEY(participant_uid, chat_path, source)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS idx_cf_chat_participants_path ON friendbook_cf_chat_participants(chat_path, source);

INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT j.value, d.path, 1 FROM friendbook_cf_firestore_documents d, json_each(json_quote(json_extract(d.document_json,'$.participants'))) j WHERE d.parent_path='chats' AND json_type(json_quote(json_extract(d.document_json,'$.participants')))='array' AND typeof(j.value)='text' AND j.value<>'';

CREATE TRIGGER IF NOT EXISTS cf_chat_members_1_insert AFTER INSERT ON friendbook_cf_firestore_documents
WHEN NEW.parent_path='chats' BEGIN
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT j.value, NEW.path, 1 FROM json_each(json_quote(json_extract(NEW.document_json,'$.participants'))) j WHERE NEW.parent_path='chats' AND json_type(json_quote(json_extract(NEW.document_json,'$.participants')))='array' AND typeof(j.value)='text' AND j.value<>'';
END;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_1_update AFTER UPDATE ON friendbook_cf_firestore_documents
WHEN OLD.parent_path='chats' OR NEW.parent_path='chats' BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=1;
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT j.value, NEW.path, 1 FROM json_each(json_quote(json_extract(NEW.document_json,'$.participants'))) j WHERE NEW.parent_path='chats' AND json_type(json_quote(json_extract(NEW.document_json,'$.participants')))='array' AND typeof(j.value)='text' AND j.value<>'';
END;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_1_delete AFTER DELETE ON friendbook_cf_firestore_documents
WHEN OLD.parent_path='chats' BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=1;
END;

INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT CASE WHEN json_type(d.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END, d.path, 0 FROM friendbook_cf_documents d, json_each(json_quote(CASE WHEN json_type(d.document_json,'$.fields')='object' THEN json_extract(d.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(d.document_json,'$.participants') END)) j WHERE d.collection='chats' AND json_type(json_quote(CASE WHEN json_type(d.document_json,'$.fields')='object' THEN json_extract(d.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(d.document_json,'$.participants') END))='array' AND typeof(CASE WHEN json_type(d.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END)='text' AND CASE WHEN json_type(d.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END<>'';

CREATE TRIGGER IF NOT EXISTS cf_chat_members_0_insert AFTER INSERT ON friendbook_cf_documents
WHEN NEW.collection='chats' BEGIN
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END, NEW.path, 0 FROM json_each(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END)) j WHERE NEW.collection='chats' AND json_type(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END))='array' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END)='text' AND CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END<>'';
END;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_0_update AFTER UPDATE ON friendbook_cf_documents
WHEN OLD.collection='chats' OR NEW.collection='chats' BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=0;
  INSERT OR IGNORE INTO friendbook_cf_chat_participants SELECT CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END, NEW.path, 0 FROM json_each(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END)) j WHERE NEW.collection='chats' AND json_type(json_quote(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN json_extract(NEW.document_json,'$.fields.participants.arrayValue.values') ELSE json_extract(NEW.document_json,'$.participants') END))='array' AND typeof(CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END)='text' AND CASE WHEN json_type(NEW.document_json,'$.fields')='object' THEN (CASE WHEN j.type='object' THEN json_extract(j.value,'$.stringValue') END) ELSE j.value END<>'';
END;

CREATE TRIGGER IF NOT EXISTS cf_chat_members_0_delete AFTER DELETE ON friendbook_cf_documents
WHEN OLD.collection='chats' BEGIN
  DELETE FROM friendbook_cf_chat_participants WHERE chat_path=OLD.path AND source=0;
END;

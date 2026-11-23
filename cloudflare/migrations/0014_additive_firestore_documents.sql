-- Additive Firestore-compatible document store (Cloudflare D1).
-- All FriendBook app data (posts, chats, friends, users, ...) lives here.
-- Firebase keeps ONLY Auth + FCM. Repeatable: IF NOT EXISTS only.
CREATE TABLE IF NOT EXISTS friendbook_cf_firestore_documents (
    path TEXT PRIMARY KEY NOT NULL,
    parent_path TEXT NOT NULL,
    collection_id TEXT NOT NULL,
    doc_id TEXT NOT NULL,
    owner_uid TEXT NOT NULL DEFAULT '',
    document_json TEXT NOT NULL CHECK (json_valid(document_json)),
    create_time INTEGER NOT NULL DEFAULT 0,
    update_time INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_firestore_parent
    ON friendbook_cf_firestore_documents(parent_path);
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_firestore_collection_updated
    ON friendbook_cf_firestore_documents(collection_id, update_time DESC);
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_firestore_owner
    ON friendbook_cf_firestore_documents(owner_uid);

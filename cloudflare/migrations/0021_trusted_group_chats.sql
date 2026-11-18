-- Ownership is recorded only during authorized creation or creator transfer.
-- Existing client-writable chat documents cannot prove historical ownership.
CREATE TABLE IF NOT EXISTS friendbook_cf_trusted_group_chats (
  chat_path TEXT PRIMARY KEY NOT NULL,
  owner_uid TEXT NOT NULL
);

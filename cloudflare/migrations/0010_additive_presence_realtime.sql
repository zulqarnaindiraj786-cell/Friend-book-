-- Additive Cloudflare Realtime Presence & Typing state (no destructive DDL).
CREATE TABLE IF NOT EXISTS friendbook_cf_presence (
    uid TEXT PRIMARY KEY NOT NULL,
    online INTEGER NOT NULL CHECK(online IN (0, 1)),
    visible INTEGER NOT NULL DEFAULT 1 CHECK(visible IN (0, 1)),
    last_seen INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS friendbook_cf_presence_online
ON friendbook_cf_presence(online, last_seen);

CREATE TABLE IF NOT EXISTS friendbook_cf_typing (
    chat_id TEXT NOT NULL,
    uid TEXT NOT NULL,
    typing INTEGER NOT NULL CHECK(typing IN (0, 1)),
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(chat_id, uid)
);

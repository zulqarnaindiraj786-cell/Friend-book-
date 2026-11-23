CREATE TABLE IF NOT EXISTS friendbook_cf_live_pk (
  id TEXT PRIMARY KEY NOT NULL,
  room_a TEXT NOT NULL,
  room_b TEXT NOT NULL,
  host_a TEXT NOT NULL,
  host_b TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('invited','active','declined','ended')),
  duration_sec INTEGER NOT NULL CHECK(duration_sec BETWEEN 60 AND 600),
  score_a INTEGER NOT NULL DEFAULT 0 CHECK(score_a>=0),
  score_b INTEGER NOT NULL DEFAULT 0 CHECK(score_b>=0),
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  CHECK(host_a<>host_b)
);
CREATE INDEX IF NOT EXISTS idx_cf_live_pk_room_a ON friendbook_cf_live_pk(room_a,status,expires_at);
CREATE INDEX IF NOT EXISTS idx_cf_live_pk_room_b ON friendbook_cf_live_pk(room_b,status,expires_at);

-- Optional future schema only. Not applied by deploy or this PR.
CREATE TABLE IF NOT EXISTS friendbook_cf_comment_details (
 resource TEXT NOT NULL, id TEXT NOT NULL, media_url TEXT NOT NULL DEFAULT '',
 mentions TEXT NOT NULL DEFAULT '[]' CHECK(json_valid(mentions)),
 name TEXT NOT NULL DEFAULT '', photo TEXT NOT NULL DEFAULT '', edited INTEGER NOT NULL DEFAULT 0,
 removed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(resource,id),
 FOREIGN KEY(resource,id) REFERENCES friendbook_cf_comments(resource,id)
);
CREATE TABLE IF NOT EXISTS friendbook_cf_comment_pins (resource TEXT PRIMARY KEY, id TEXT);
CREATE TABLE IF NOT EXISTS friendbook_cf_comment_reactions (
 resource TEXT NOT NULL, id TEXT NOT NULL, uid TEXT NOT NULL, type TEXT,
 PRIMARY KEY(resource,id,uid), FOREIGN KEY(resource,id) REFERENCES friendbook_cf_comments(resource,id)
);
CREATE INDEX IF NOT EXISTS friendbook_cf_comment_rx ON friendbook_cf_comment_reactions(resource,id,type);

-- Additive real view counters. Firebase is authentication only; view data lives in Cloudflare D1.
-- Repeatable: every statement is IF NOT EXISTS and no existing row is touched.
CREATE TABLE IF NOT EXISTS friendbook_cf_view_counters (
    resource TEXT PRIMARY KEY NOT NULL,
    views INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS friendbook_cf_view_dedupe (
    resource TEXT NOT NULL,
    uid TEXT NOT NULL,
    bucket TEXT NOT NULL,
    PRIMARY KEY (resource, uid, bucket)
);

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_view_counters_views
    ON friendbook_cf_view_counters(views DESC);

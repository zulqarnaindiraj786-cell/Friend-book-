-- Additive: per-user "last counted view" stamp so EVERY real re-watch counts, while a runaway
-- loop can never write more than one counted view per second per user per resource.
-- Repeatable: CREATE ... IF NOT EXISTS only. No existing row is dropped, reset or rewritten, and
-- the historical friendbook_cf_view_dedupe table is left untouched.
CREATE TABLE IF NOT EXISTS friendbook_cf_view_last (
    resource TEXT NOT NULL,
    uid TEXT NOT NULL,
    last_at INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (resource, uid)
);

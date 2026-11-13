-- Additive temporary PROCESSING audit only. No R2 object or source data deletion.
CREATE TABLE IF NOT EXISTS friendbook_video_processing (
    public_id TEXT PRIMARY KEY NOT NULL,
    created_at INTEGER NOT NULL,
    cleanup_after INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('pending','cleaned')),
    finished_at INTEGER
);
CREATE INDEX IF NOT EXISTS friendbook_video_cleanup_pending
ON friendbook_video_processing(state, cleanup_after);

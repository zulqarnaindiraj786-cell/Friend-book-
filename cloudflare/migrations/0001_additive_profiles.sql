-- Optional, additive foundation only. Not run by the deployment command.
-- No existing Firestore/D1 rows are copied, updated, deleted or replaced.
CREATE TABLE IF NOT EXISTS friendbook_cf_profiles (
    uid TEXT PRIMARY KEY NOT NULL,
    display_name TEXT NOT NULL DEFAULT '',
    cloudinary_avatar_url TEXT
);

-- Administrative observations only: NOT a realtime journal and NOT app state.
CREATE TABLE IF NOT EXISTS friendbook_cf_migration_observations (
 observation_id TEXT NOT NULL, source_path TEXT NOT NULL,
 operation TEXT NOT NULL CHECK(operation IN ('created','changed','absent')),
 before_sha256 TEXT, document_json TEXT NOT NULL CHECK(json_valid(document_json)), after_sha256 TEXT,
 PRIMARY KEY(observation_id,source_path)
);
CREATE TABLE IF NOT EXISTS friendbook_cf_migration_counter_ledger (
 observation_id TEXT NOT NULL, source_path TEXT NOT NULL, metric TEXT NOT NULL,
 original_json TEXT NOT NULL CHECK(json_valid(original_json)), identity_count INTEGER NOT NULL,
 status TEXT NOT NULL, source_document_sha256 TEXT NOT NULL,
 PRIMARY KEY(observation_id,source_path,metric)
);
CREATE TABLE IF NOT EXISTS friendbook_cf_migration_observation_manifests (
 observation_id TEXT PRIMARY KEY NOT NULL, baseline_batch TEXT NOT NULL, read_time TEXT NOT NULL,
 manifest_json TEXT NOT NULL CHECK(json_valid(manifest_json)), sha256 TEXT NOT NULL
);

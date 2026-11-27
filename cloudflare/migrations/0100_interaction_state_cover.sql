-- A visible feed page reads resource and state together. Cover both columns to
-- avoid a separate table-row lookup for every matching primary-key entry.
CREATE INDEX IF NOT EXISTS idx_cf_interaction_resource_state_cover
ON friendbook_cf_interaction_resources(resource,state);

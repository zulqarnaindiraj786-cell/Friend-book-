CREATE INDEX IF NOT EXISTS idx_cf_trusted_friendships_high_low
  ON friendbook_cf_trusted_friendships(uid_high, uid_low);

-- Only the Worker can write this ledger. Historical document rows are not
-- automatically trusted: they may have been written before authorization existed.
CREATE TABLE IF NOT EXISTS friendbook_cf_trusted_friendships (
  uid_low TEXT NOT NULL,
  uid_high TEXT NOT NULL,
  friend_path TEXT NOT NULL UNIQUE,
  PRIMARY KEY (uid_low, uid_high),
  CHECK (uid_low < uid_high)
);

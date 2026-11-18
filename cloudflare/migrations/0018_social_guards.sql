-- Used inside D1 batch transactions to abort a write if its authorized snapshot changed.
CREATE TABLE IF NOT EXISTS friendbook_cf_social_guards (
  ok INTEGER NOT NULL CHECK (ok = 1)
);

-- Keep the replacement query paths, without maintaining two indexes for each.
-- 0059 people default page indexes cover the ID-only broadcast indexes.
DROP INDEX IF EXISTS idx_cf_broadcast_users;
DROP INDEX IF EXISTS idx_cf_legacy_broadcast_users;

-- 0044 profile post indexes include the same collection/author prefix.
DROP INDEX IF EXISTS idx_cf_posts_author;
DROP INDEX IF EXISTS idx_cf_legacy_posts_author;
-- 0038 reel author index already seeks the same author before ordering.
DROP INDEX IF EXISTS idx_cf_reels_author;

-- 0051 exception indexes supersede both old post exception indexes.
DROP INDEX IF EXISTS idx_cf_posts_feed_nonstandard;
DROP INDEX IF EXISTS idx_cf_legacy_posts_feed_nonstandard;
-- 0057 confines the compatibility probe to nested chat messages; the previous
-- unscoped indexes also charged maintenance on unrelated document writes.
DROP INDEX IF EXISTS idx_cf_chat_nonstandard_order;
DROP INDEX IF EXISTS idx_cf_chat_nonstandard_order_v2;

-- Comment reads are scoped by resource, with a separate resource/author index
-- for blocked-author counts. This unscoped author index is not used by the
-- Worker and adds an index write to every canonical comment insert.
DROP INDEX IF EXISTS idx_cf_comment_author;

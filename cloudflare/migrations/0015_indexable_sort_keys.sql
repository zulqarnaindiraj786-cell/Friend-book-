-- 0015: indexable sort keys for the document store.
--
-- The app's hot queries all order by a millisecond value that lives *inside* document_json:
--   posts    ORDER BY createdAt  DESC   (the feed, the profile, Watch)
--   messages ORDER BY createdAtMs DESC  (a chat window)
--   stories  ORDER BY expiresAt         (the story strip)
--   live_chat/chats by createdAtMs / lastMessageTimeMs
--
-- doc-query.js could only sort by json_extract(document_json, '$.field'), and SQLite cannot
-- use an index for a function on a column. Every one of those queries therefore scanned the
-- whole friendbook_cf_documents table and sorted it in memory on every request - which is
-- exactly why the feed, a chat and a profile all felt slow no matter how small the page was.
--
-- The fix is a generated column: SQLite keeps it in sync with document_json automatically, so
-- no writer had to change and no data had to be backfilled. An index over
-- (collection, sort_ms, doc_id) lets the same query become an indexed range scan.

-- Both spellings are covered: posts store their time as createdAt (milliseconds) while chat
-- messages use createdAtMs. json_type() guards the cast, so a legacy row holding an ISO string
-- yields NULL instead of a meaningless integer, and ordering falls back to the doc_id tie-break.
-- VIRTUAL (not STORED) is required: SQLite only accepts a virtual generated column in ALTER TABLE.
ALTER TABLE friendbook_cf_documents ADD COLUMN sort_ms INTEGER
    GENERATED ALWAYS AS (
        CASE
            WHEN json_type(document_json, '$.createdAtMs') IN ('integer', 'real')
                THEN CAST(json_extract(document_json, '$.createdAtMs') AS INTEGER)
            WHEN json_type(document_json, '$.createdAt') IN ('integer', 'real')
                THEN CAST(json_extract(document_json, '$.createdAt') AS INTEGER)
        END
    ) VIRTUAL;

-- One composite index serves "newest N in this collection", which is the shape of every
-- hot query above. doc_id is included so keyset pagination can continue without a sort.
CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_collection_sort
    ON friendbook_cf_documents(collection, sort_ms DESC, doc_id DESC);

CREATE INDEX IF NOT EXISTS idx_friendbook_cf_documents_collection_docid
    ON friendbook_cf_documents(collection, doc_id);

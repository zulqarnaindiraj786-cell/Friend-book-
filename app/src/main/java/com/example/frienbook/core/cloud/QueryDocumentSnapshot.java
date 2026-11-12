package com.example.frienbook.core.cloud;

import java.util.Map;

/**
 * A document that came from a query rather than from a direct read. It is the same thing - the two
 * names exist because screens loop over both, and a list of query results has to satisfy either loop.
 */
public final class QueryDocumentSnapshot extends DocumentSnapshot {

    QueryDocumentSnapshot(String collectionPath, String id, Map<String, Object> data, boolean exists,
                          String cursor, long updatedAt) {
        super(collectionPath, id, data, exists, cursor, updatedAt);
    }
}

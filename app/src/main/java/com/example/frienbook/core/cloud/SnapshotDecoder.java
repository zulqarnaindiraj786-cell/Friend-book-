package com.example.frienbook.core.cloud;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Turns the Worker's JSON into snapshots.
 *
 * Two shapes come back, and they differ on purpose. A list row is the stored document laid out flat,
 * with the three transport keys a paging client needs added beside it (id, updatedAt, cursor). A
 * single read wraps the document in an envelope, because it also has to say whether the document
 * exists at all. Documents imported from the old database arrive already unwrapped by the Worker, so
 * a profile or a post that lived there reads exactly like one written today.
 *
 * What ends up in getData() is the stored body only - the transport keys are stripped, because a
 * screen that copies a map and writes it back must not write bookkeeping into somebody's document.
 */
final class SnapshotDecoder {

    /** Keys the Worker adds for the client's benefit; they are not part of the document. */
    private static final String[] TRANSPORT = {
            "updatedAt", "cursor", "path", "collection", "doc_id", "deleted", "document", "exists", "cached"
    };

    private SnapshotDecoder() { }

    /** One row of a list answer. */
    static DocumentSnapshot decodeRow(String collectionPath, JSONObject row) {
        if (row == null) return null;
        String id = row.optString("id", "");
        if (id.isEmpty()) id = row.optString("doc_id", "");
        if (row.optBoolean("deleted", false)) return DocumentSnapshot.missing(collectionPath, id);
        return new DocumentSnapshot(collectionPath, id, body(row, id), true,
                optString(row, "cursor"), row.optLong("updatedAt", 0L));
    }

    /** The envelope a direct read answers with. */
    static DocumentSnapshot decodeDocument(String collectionPath, String id, JSONObject answer) {
        if (answer == null) return DocumentSnapshot.missing(collectionPath, id);
        JSONObject document = answer.optJSONObject("document");
        boolean deleted = answer.optBoolean("deleted", false);
        if (deleted || document == null) {
            return DocumentSnapshot.missing(collectionPath, answer.optString("id", id));
        }
        String docId = answer.optString("id", id);
        if (docId.isEmpty()) docId = document.optString("id", id);
        return new DocumentSnapshot(collectionPath, docId, body(document, docId), true, null,
                answer.optLong("updatedAt", 0L));
    }

    /** A whole page, decoded. */
    static void decodeRows(String collectionPath, JSONArray rows, java.util.List<DocumentSnapshot> into) {
        if (rows == null) return;
        for (int i = 0; i < rows.length(); i++) {
            DocumentSnapshot row = decodeRow(collectionPath, rows.optJSONObject(i));
            if (row != null) into.add(row);
        }
    }

    private static Map<String, Object> body(JSONObject object, String id) {
        // Defensive: if a row ever arrives still wrapped in the exported shape, unwrap it here rather
        // than showing the user an empty document.
        JSONObject wrapped = object.optJSONObject("fields");
        JSONObject source = wrapped != null ? wrapped : object;
        Map<String, Object> values = Cloud.toMap(source);
        for (String key : TRANSPORT) values.remove(key);
        if (!values.containsKey("id") && id != null && !id.isEmpty()) values.put("id", id);
        return values;
    }

    private static String optString(JSONObject object, String key) {
        String value = object.optString(key, null);
        return value == null || value.isEmpty() ? null : value;
    }
}

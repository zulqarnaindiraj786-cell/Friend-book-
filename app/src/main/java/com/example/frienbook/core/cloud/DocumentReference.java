package com.example.frienbook.core.cloud;

import com.google.android.gms.tasks.Task;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * One document in Cloudflare D1, addressed the way the app has always addressed it:
 * {@code collection("posts").document(postId)}.
 *
 * Every read and write goes through the Worker, which verifies the Firebase ID token and then applies
 * the same access rules the old database enforced - so a stranger still cannot open somebody else's
 * chat and a member still cannot approve their own boost. Access is never decided on the phone.
 *
 * A failure arrives as a failed Task, or as the error argument of a listener. Nothing here retries
 * behind the caller's back and nothing here invents a value to fill the gap: a screen that cannot
 * reach the database shows the error it has always shown.
 */
public class DocumentReference {

    private final String collectionPath;
    private final String id;
    private final String path;

    DocumentReference(String collectionPath, String id) {
        this.collectionPath = collectionPath;
        this.id = id;
        this.path = collectionPath + "/" + id;
    }

    public String getId() {
        return id;
    }

    public String getPath() {
        return path;
    }

    /** The collection this document lives in - a nested one keeps its parent in the path. */
    public CollectionReference getParent() {
        return new CollectionReference(collectionPath);
    }

    /** A collection under this document, e.g. posts/&lt;id&gt;/reactions or chats/&lt;id&gt;/messages. */
    public CollectionReference collection(String name) {
        return new CollectionReference(path + "/" + name);
    }

    String collectionPath() {
        return collectionPath;
    }

    private String url() {
        return "/v1/docs/" + collectionPath + "/" + Cloud.encode(id);
    }

    // ------------------------------------------------------------------ reads

    /**
     * Fetches the document once. The snapshot's exists() tells the truth: false when the row is not
     * there or has been deleted, so a screen never has to guess between "empty" and "gone".
     */
    public Task<DocumentSnapshot> get() {
        return Cloud.submit(new Cloud.Job<DocumentSnapshot>() {
            @Override
            public DocumentSnapshot run() throws Exception {
                return read();
            }
        });
    }

    /** The blocking form the poller and the batch reader use; already off the main thread. */
    DocumentSnapshot read() throws CloudException {
        try {
            return SnapshotDecoder.decodeDocument(collectionPath, id, Cloud.call(url(), "GET", null));
        } catch (CloudException failed) {
            if (isGone(failed)) return DocumentSnapshot.missing(collectionPath, id);
            throw failed;
        }
    }

    /**
     * A 404 means the document is not there, which is an answer a screen can act on - not an error to
     * report. Anything else (403, 503, no network at all) stays an error, because pretending otherwise
     * would show somebody an empty profile when the real problem is that they are offline.
     */
    private static boolean isGone(CloudException failed) {
        return failed.isMissing();
    }

    /**
     * Keeps a screen up to date: the listener is called once straight away, and again whenever the
     * document changes, until remove() is called on the returned registration.
     */
    public ListenerRegistration addSnapshotListener(EventListener<DocumentSnapshot> listener) {
        Watcher watcher = Watcher.forDocument(collectionPath, id, listener);
        Cloud.MAIN.post(watcher);
        return new ListenerRegistration(watcher);
    }

    /** Accepted for the call sites that pass an executor or metadata; delivery is on the main thread. */
    public ListenerRegistration addSnapshotListener(Executor ignored, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(Object ignored, EventListener<DocumentSnapshot> listener) {
        return addSnapshotListener(listener);
    }

    // ------------------------------------------------------------------ writes

    /** Replaces the document: fields you stop sending really disappear. createdAt is kept. */
    public Task<Void> set(Map<String, Object> data) {
        return write(data, true, false);
    }

    /** With merge(), the sent fields are laid over what is stored and the rest survives. */
    public Task<Void> set(Map<String, Object> data, SetOptions options) {
        boolean merge = options != null && options.isMerge();
        return write(data, !merge, merge);
    }

    /** Merges the sent fields into the stored document, creating it if it is not there. */
    public Task<Void> update(Map<String, Object> data) {
        return write(data, false, false);
    }

    /**
     * The varargs form used all over the app: {@code update("unread", 1, "lastMessage", text)}. An odd
     * trailing argument is dropped rather than paired with nothing. FieldValue markers are allowed in
     * the value positions - they are split out before the body leaves the phone, so a counter is
     * incremented by the Worker and cannot be clobbered by a second writer.
     */
    public Task<Void> update(String field, Object value, Object... more) {
        return write(pairs(field, value, more), false, false);
    }

    /** FieldPath.documentId() writes the id itself; anything else is treated as a field name. */
    public Task<Void> update(FieldPath field, Object value, Object... more) {
        if (field != null && field.isDocumentId()) return write(pairs("id", value, more), false, false);
        return write(pairs(String.valueOf(field), value, more), false, false);
    }

    private static Map<String, Object> pairs(String field, Object value, Object... more) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (field != null) data.put(field, value);
        if (more != null) {
            for (int i = 0; i + 1 < more.length; i += 2) {
                Object key = more[i];
                if (key instanceof FieldPath) {
                    data.put(((FieldPath) key).isDocumentId() ? "id" : String.valueOf(key), more[i + 1]);
                } else if (key != null) {
                    data.put(String.valueOf(key), more[i + 1]);
                }
            }
        }
        return data;
    }

    /**
     * Deletes the document. On the server this is a tombstone: the row stops being readable and stops
     * appearing in every list and every feed, but it is kept - nothing a user ever created is
     * destroyed, which is also what makes the migration from the old database reversible in spirit.
     */
    public Task<Void> delete() {
        return Cloud.submit(new Cloud.Job<Void>() {
            @Override
            public Void run() throws Exception {
                Cloud.call(url(), "DELETE", null);
                return null;
            }
        });
    }

    private Task<Void> write(final Map<String, Object> data, final boolean replace, final boolean createOnly) {
        return Cloud.submit(new Cloud.Job<Void>() {
            @Override
            public Void run() throws Exception {
                JSONObject body = Cloud.writeBody(data, replace);
                if (createOnly) body.put("ifMissing", true);
                Cloud.call(url(), "PUT", body);
                return null;
            }
        });
    }
}

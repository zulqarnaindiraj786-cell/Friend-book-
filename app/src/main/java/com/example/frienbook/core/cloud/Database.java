package com.example.frienbook.core.cloud;

/**
 * The app's database, which lives in the owner's Cloudflare D1 and is reached through the owner's
 * Cloudflare Worker.
 *
 * Firebase is authentication and nothing else: it proves who is holding the phone. Every document -
 * posts, reels, comments, chats and their messages, stories, friends and requests, notifications,
 * payments, config - is read and written here, and the Worker decides what this person may do, from
 * the verified uid, using the same rules the old database enforced.
 *
 * The shape is the one 280 call sites were written against, so a screen migrated by changing its
 * imports keeps the behaviour somebody already tested:
 *
 *   Fb.db().collection("posts").whereEqualTo("authorId", uid)
 *       .orderBy("createdAt", Query.Direction.DESCENDING).limit(20).get()
 *       .addOnSuccessListener(snap -> { ... });
 *
 * What is genuinely different, and worth knowing when reading a screen:
 *  - There is no client SDK and no socket. A snapshot listener is a poller on the Worker's change
 *    feed, which is why chat, typing, reactions and notifications all still feel live.
 *  - Server timestamps are the Worker's clock, so a wrong phone time cannot reorder a feed.
 *  - Deletes are tombstones: the row stays on the server and stops being readable.
 *  - A page is at most 50 rows, and this layer fetches further pages itself when a limit asks for more.
 */
public final class Database {

    private static final Database INSTANCE = new Database();

    private Database() { }

    public static Database getInstance() {
        return INSTANCE;
    }

    /** A top-level collection ("posts") or a nested one addressed by path ("chats/c1/messages"). */
    public CollectionReference collection(String path) {
        return new CollectionReference(path);
    }

    /** A document by its full path, e.g. "users/&lt;uid&gt;". */
    public DocumentReference document(String path) {
        int slash = path == null ? -1 : path.lastIndexOf('/');
        if (slash <= 0) return new DocumentReference(path == null ? "" : path, "");
        return new DocumentReference(path.substring(0, slash), path.substring(slash + 1));
    }

    /** Queues several writes and sends them together; see WriteBatch for what that guarantees. */
    public WriteBatch batch() {
        return new WriteBatch();
    }

    /**
     * Runs your function against a Transaction: the reads it asks for happen first, then the writes it
     * buffered are sent as one all-or-nothing batch. Runs off the main thread and answers as a Task.
     */
    public <T> com.google.android.gms.tasks.Task<T> runTransaction(final Transaction.Function<T> function) {
        return Cloud.submit(new Cloud.Job<T>() {
            @Override
            public T run() throws Exception {
                Transaction transaction = new Transaction();
                T result = function.apply(transaction);
                java.util.List<org.json.JSONObject> ops = transaction.ops();
                for (int start = 0; start < ops.size(); start += 20) {
                    org.json.JSONArray slice = new org.json.JSONArray();
                    for (int i = start; i < Math.min(start + 20, ops.size()); i++) slice.put(ops.get(i));
                    if (slice.length() == 0) break;
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("ops", slice);
                    Cloud.call("/v1/docs/batch", "POST", body);
                }
                return result;
            }
        });
    }
}

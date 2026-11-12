package com.example.frienbook.core.cloud;

import com.google.android.gms.tasks.Task;

import java.util.Map;
import java.util.UUID;

/**
 * A collection: the top-level kind ("posts", "friends", "notifications") or one nested under a
 * document ("chats/&lt;chatId&gt;/messages"). Nesting is addressed by path and stored flat, so a
 * thread's messages still live with their chat and still cost one indexed read.
 */
public class CollectionReference extends Query {

    private final String path;

    CollectionReference(String path) {
        super(path);
        this.path = path;
    }

    public String getPath() {
        return path;
    }

    /** The collection name on its own, without a parent path. */
    public String getName() {
        return leaf();
    }

    public DocumentReference document(String id) {
        return new DocumentReference(path, id);
    }

    /** A new document with an id nobody has to choose. */
    public DocumentReference document() {
        return new DocumentReference(path, newId());
    }

    /**
     * Creates a document with a new id and answers with its reference, so the caller can use the id
     * straight away - the way a new comment, reaction or message has always been written.
     */
    public Task<DocumentReference> add(final Map<String, Object> data) {
        final DocumentReference created = document();
        return Cloud.submit(new Cloud.Job<DocumentReference>() {
            @Override
            public DocumentReference run() throws Exception {
                Cloud.call("/v1/docs/" + created.collectionPath() + "/" + Cloud.encode(created.getId()),
                        "PUT", Cloud.writeBody(data, true));
                return created;
            }
        });
    }

    /** 32 characters of hex: unique, safe in a URL, and stable once written. */
    static String newId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}

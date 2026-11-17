package com.friendbook.social.d1.documents;

import com.google.android.gms.tasks.Task;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Reference to a collection or subcollection in Cloudflare D1. */
public class CollectionReference extends Query {
    private final String id;

    CollectionReference(D1DocumentStore firestore, String path) {
        super(firestore, trimSlashes(path));
        int idx = this.parentPath.lastIndexOf('/');
        this.id = idx >= 0 ? this.parentPath.substring(idx + 1) : this.parentPath;
    }

    private static String trimSlashes(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && s.charAt(a) == '/') a++;
        while (b > a && s.charAt(b - 1) == '/') b--;
        return s.substring(a, b);
    }

    public String getId() { return id; }

    public String getPath() { return parentPath; }

    public DocumentReference getParent() {
        int idx = parentPath.lastIndexOf('/');
        return idx > 0 ? new DocumentReference(firestore, parentPath.substring(0, idx)) : null;
    }

    public DocumentReference document() {
        String autoId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        return document(autoId);
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(firestore, parentPath + "/" + trimSlashes(documentPath));
    }

    @SuppressWarnings("unchecked")
    public Task<DocumentReference> add(Object data) {
        Map<String, Object> map = data instanceof Map ? (Map<String, Object>) data : new HashMap<>();
        JSONObject body = new JSONObject();
        try { body.put("data", D1DocumentJson.encodeMap(map)); } catch (Exception ignored) { }
        return firestore.runAsync(() -> {
            JSONObject res = D1DocumentTransport.addDocument(parentPath, body);
            String createdPath = res.optString("path", parentPath + "/" + res.optString("id", ""));
            firestore.notifyCollectionListeners(parentPath);
            return new DocumentReference(firestore, createdPath);
        });
    }
}

package com.google.firebase.firestore;

import android.app.Activity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

public class DocumentReference {
    private final FirebaseFirestore firestore;
    private final String path;

    public DocumentReference(FirebaseFirestore firestore, String path) {
        this.firestore = firestore;
        this.path = CfFirestoreTransport.cleanPath(path);
    }

    @NonNull
    public FirebaseFirestore getFirestore() {
        return firestore != null ? firestore : FirebaseFirestore.getInstance();
    }

    @NonNull
    public String getId() {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    @NonNull
    public String getPath() {
        return path;
    }

    @NonNull
    public CollectionReference getParent() {
        int slash = path.lastIndexOf('/');
        String parent = slash > 0 ? path.substring(0, slash) : "";
        return new CollectionReference(getFirestore(), parent);
    }

    @NonNull
    public CollectionReference collection(@NonNull String collectionPath) {
        String clean = CfFirestoreTransport.cleanPath(collectionPath);
        return new CollectionReference(getFirestore(), path + "/" + clean);
    }

    @NonNull
    public Task<DocumentSnapshot> get() {
        return get(Source.DEFAULT);
    }

    @NonNull
    public Task<DocumentSnapshot> get(@NonNull Source source) {
        return CfFirestoreTransport.executeGet(getFirestore(), path, source == null ? Source.DEFAULT : source);
    }

    @NonNull
    public Task<Void> set(@NonNull Object data) {
        return CfFirestoreTransport.executeWrites(Collections.singletonList(
                new CfFirestoreTransport.WriteOp("set", path, CfFirestoreTransport.objectToMap(data), false)));
    }

    @NonNull
    public Task<Void> set(@NonNull Object data, @NonNull SetOptions options) {
        boolean merge = options != null && options.isMerge();
        return CfFirestoreTransport.executeWrites(Collections.singletonList(
                new CfFirestoreTransport.WriteOp("set", path, CfFirestoreTransport.objectToMap(data), merge)));
    }

    @NonNull
    public Task<Void> update(@NonNull Map<String, Object> data) {
        return CfFirestoreTransport.executeWrites(Collections.singletonList(
                new CfFirestoreTransport.WriteOp("update", path, data == null ? Collections.emptyMap() : new LinkedHashMap<>(data), true)));
    }

    @NonNull
    public Task<Void> update(@NonNull String field, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(CfFirestoreTransport.varargsToMap(field, value, moreFieldsAndValues));
    }

    @NonNull
    public Task<Void> update(@NonNull FieldPath fieldPath, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(CfFirestoreTransport.varargsToMap(fieldPath.getCanonicalString(), value, moreFieldsAndValues));
    }

    @NonNull
    public Task<Void> delete() {
        return CfFirestoreTransport.executeWrites(Collections.singletonList(
                new CfFirestoreTransport.WriteOp("delete", path, null, false)));
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull EventListener<DocumentSnapshot> listener) {
        return CfFirestoreTransport.listenDocument(getFirestore(), path, null, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull MetadataChanges metadataChanges, @NonNull EventListener<DocumentSnapshot> listener) {
        return CfFirestoreTransport.listenDocument(getFirestore(), path, null, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull Activity activity, @NonNull EventListener<DocumentSnapshot> listener) {
        return CfFirestoreTransport.listenDocument(getFirestore(), path, activity, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull Executor executor, @NonNull EventListener<DocumentSnapshot> listener) {
        return CfFirestoreTransport.listenDocument(getFirestore(), path, null, listener);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentReference)) return false;
        DocumentReference that = (DocumentReference) o;
        return Objects.equals(path, that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}

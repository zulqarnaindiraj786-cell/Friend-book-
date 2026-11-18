package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Transaction {

    public interface Function<TResult> {
        @Nullable
        TResult apply(@NonNull Transaction transaction) throws FirebaseFirestoreException;
    }

    private final FirebaseFirestore firestore;
    private final List<CfFirestoreTransport.WriteOp> pendingOps = new ArrayList<>();

    public Transaction(FirebaseFirestore firestore) {
        this.firestore = firestore;
    }

    @NonNull
    public DocumentSnapshot get(@NonNull DocumentReference documentRef) throws FirebaseFirestoreException {
        return CfFirestoreTransport.getSync(firestore, documentRef.getPath());
    }

    @NonNull
    public Transaction set(@NonNull DocumentReference documentRef, @NonNull Object data) {
        pendingOps.add(new CfFirestoreTransport.WriteOp("set", documentRef.getPath(), CfFirestoreTransport.objectToMap(data), false));
        return this;
    }

    @NonNull
    public Transaction set(@NonNull DocumentReference documentRef, @NonNull Object data, @NonNull SetOptions options) {
        boolean merge = options != null && options.isMerge();
        pendingOps.add(new CfFirestoreTransport.WriteOp("set", documentRef.getPath(), CfFirestoreTransport.objectToMap(data), merge));
        return this;
    }

    @NonNull
    public Transaction update(@NonNull DocumentReference documentRef, @NonNull Map<String, Object> data) {
        pendingOps.add(new CfFirestoreTransport.WriteOp("update", documentRef.getPath(), data == null ? Collections.emptyMap() : new LinkedHashMap<>(data), true));
        return this;
    }

    @NonNull
    public Transaction update(@NonNull DocumentReference documentRef, @NonNull String field, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(documentRef, CfFirestoreTransport.varargsToMap(field, value, moreFieldsAndValues));
    }

    @NonNull
    public Transaction update(@NonNull DocumentReference documentRef, @NonNull FieldPath fieldPath, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(documentRef, CfFirestoreTransport.varargsToMap(fieldPath.getCanonicalString(), value, moreFieldsAndValues));
    }

    @NonNull
    public Transaction delete(@NonNull DocumentReference documentRef) {
        pendingOps.add(new CfFirestoreTransport.WriteOp("delete", documentRef.getPath(), null, false));
        return this;
    }

    List<CfFirestoreTransport.WriteOp> ops() {
        return pendingOps;
    }
}

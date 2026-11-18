package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WriteBatch {
    private final List<CfFirestoreTransport.WriteOp> ops = new ArrayList<>();

    public WriteBatch() {}

    @NonNull
    public WriteBatch set(@NonNull DocumentReference documentRef, @NonNull Object data) {
        ops.add(new CfFirestoreTransport.WriteOp("set", documentRef.getPath(), CfFirestoreTransport.objectToMap(data), false));
        return this;
    }

    @NonNull
    public WriteBatch set(@NonNull DocumentReference documentRef, @NonNull Object data, @NonNull SetOptions options) {
        boolean merge = options != null && options.isMerge();
        ops.add(new CfFirestoreTransport.WriteOp("set", documentRef.getPath(), CfFirestoreTransport.objectToMap(data), merge));
        return this;
    }

    @NonNull
    public WriteBatch update(@NonNull DocumentReference documentRef, @NonNull Map<String, Object> data) {
        ops.add(new CfFirestoreTransport.WriteOp("update", documentRef.getPath(), data == null ? Collections.emptyMap() : new LinkedHashMap<>(data), true));
        return this;
    }

    @NonNull
    public WriteBatch update(@NonNull DocumentReference documentRef, @NonNull String field, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(documentRef, CfFirestoreTransport.varargsToMap(field, value, moreFieldsAndValues));
    }

    @NonNull
    public WriteBatch update(@NonNull DocumentReference documentRef, @NonNull FieldPath fieldPath, @Nullable Object value, Object... moreFieldsAndValues) {
        return update(documentRef, CfFirestoreTransport.varargsToMap(fieldPath.getCanonicalString(), value, moreFieldsAndValues));
    }

    @NonNull
    public WriteBatch delete(@NonNull DocumentReference documentRef) {
        ops.add(new CfFirestoreTransport.WriteOp("delete", documentRef.getPath(), null, false));
        return this;
    }

    @NonNull
    public Task<Void> commit() {
        return CfFirestoreTransport.executeWrites(new ArrayList<>(ops));
    }
}

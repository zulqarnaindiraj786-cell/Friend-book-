package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

public class CollectionReference extends Query {

    public CollectionReference(FirebaseFirestore firestore, String collectionPath) {
        super(firestore, collectionPath);
    }

    @NonNull
    public String getId() {
        int slash = collectionPath.lastIndexOf('/');
        return slash >= 0 ? collectionPath.substring(slash + 1) : collectionPath;
    }

    @NonNull
    public String getPath() {
        return collectionPath;
    }

    @Nullable
    public DocumentReference getParent() {
        int slash = collectionPath.lastIndexOf('/');
        if (slash <= 0) return null;
        return new DocumentReference(getFirestore(), collectionPath.substring(0, slash));
    }

    @NonNull
    public DocumentReference document() {
        return document(CfFirestoreTransport.autoId());
    }

    @NonNull
    public DocumentReference document(@NonNull String childPath) {
        String clean = CfFirestoreTransport.cleanPath(childPath);
        if (clean.isEmpty()) clean = CfFirestoreTransport.autoId();
        return new DocumentReference(getFirestore(), collectionPath + "/" + clean);
    }

    @NonNull
    public Task<DocumentReference> add(@NonNull Object data) {
        final DocumentReference ref = document();
        TaskCompletionSource<DocumentReference> tcs = new TaskCompletionSource<>();
        ref.set(data)
                .addOnSuccessListener(v -> tcs.trySetResult(ref))
                .addOnFailureListener(tcs::trySetException);
        return tcs.getTask();
    }
}

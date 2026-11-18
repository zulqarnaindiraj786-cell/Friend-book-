package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;

public class FirebaseFirestore {
    private static final FirebaseFirestore INSTANCE = new FirebaseFirestore();
    private FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder().build();

    public FirebaseFirestore() {}

    @NonNull
    public static FirebaseFirestore getInstance() {
        return INSTANCE;
    }

    public void setFirestoreSettings(@NonNull FirebaseFirestoreSettings settings) {
        if (settings != null) {
            this.settings = settings;
        }
    }

    @NonNull
    public FirebaseFirestoreSettings getFirestoreSettings() {
        return settings;
    }

    @NonNull
    public CollectionReference collection(@NonNull String collectionPath) {
        return new CollectionReference(this, collectionPath);
    }

    @NonNull
    public DocumentReference document(@NonNull String documentPath) {
        return new DocumentReference(this, documentPath);
    }

    @NonNull
    public WriteBatch batch() {
        return new WriteBatch();
    }

    @NonNull
    public <TResult> Task<TResult> runTransaction(@NonNull Transaction.Function<TResult> updateFunction) {
        return CfFirestoreTransport.executeTransaction(this, updateFunction);
    }

    public Task<Void> clearPersistence() {
        CfFirestoreTransport.clearLocalCache();
        return com.google.android.gms.tasks.Tasks.forResult(null);
    }
}

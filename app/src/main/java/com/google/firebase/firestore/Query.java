package com.google.firebase.firestore;

import android.app.Activity;
import androidx.annotation.NonNull;
import com.google.android.gms.tasks.Task;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

public class Query {

    public enum Direction {
        ASCENDING,
        DESCENDING
    }

    static final class FilterSpec {
        final String field;
        final String op;
        final Object value;

        FilterSpec(String field, String op, Object value) {
            this.field = field;
            this.op = op;
            this.value = value;
        }
    }

    static final class OrderSpec {
        final String field;
        final Direction direction;

        OrderSpec(String field, Direction direction) {
            this.field = field;
            this.direction = direction == null ? Direction.ASCENDING : direction;
        }
    }

    static final class BoundSpec {
        final String docId;
        final List<Object> values;

        BoundSpec(String docId, List<Object> values) {
            this.docId = docId;
            this.values = values == null ? Collections.emptyList() : new ArrayList<>(values);
        }
    }

    protected final FirebaseFirestore firestore;
    protected final String collectionPath;
    final List<FilterSpec> filters;
    final List<OrderSpec> orders;
    final long limitCount;
    final long limitToLastCount;
    final BoundSpec startAtBound;
    final BoundSpec startAfterBound;
    final BoundSpec endBeforeBound;
    final BoundSpec endAtBound;

    public Query(FirebaseFirestore firestore, String collectionPath) {
        this(firestore, collectionPath, Collections.emptyList(), Collections.emptyList(), 0L, 0L, null, null, null, null);
    }

    Query(
            FirebaseFirestore firestore,
            String collectionPath,
            List<FilterSpec> filters,
            List<OrderSpec> orders,
            long limitCount,
            long limitToLastCount,
            BoundSpec startAtBound,
            BoundSpec startAfterBound,
            BoundSpec endBeforeBound,
            BoundSpec endAtBound) {
        this.firestore = firestore;
        this.collectionPath = CfFirestoreTransport.cleanPath(collectionPath);
        this.filters = filters;
        this.orders = orders;
        this.limitCount = limitCount;
        this.limitToLastCount = limitToLastCount;
        this.startAtBound = startAtBound;
        this.startAfterBound = startAfterBound;
        this.endBeforeBound = endBeforeBound;
        this.endAtBound = endAtBound;
    }

    @NonNull
    public FirebaseFirestore getFirestore() {
        return firestore != null ? firestore : FirebaseFirestore.getInstance();
    }

    private Query withFilter(String field, String op, Object val) {
        List<FilterSpec> next = new ArrayList<>(filters.size() + 1);
        next.addAll(filters);
        next.add(new FilterSpec(field, op, val));
        return new Query(firestore, collectionPath, next, orders, limitCount, limitToLastCount, startAtBound, startAfterBound, endBeforeBound, endAtBound);
    }

    private Query withOrder(String field, Direction dir) {
        List<OrderSpec> next = new ArrayList<>(orders.size() + 1);
        next.addAll(orders);
        next.add(new OrderSpec(field, dir));
        return new Query(firestore, collectionPath, filters, next, limitCount, limitToLastCount, startAtBound, startAfterBound, endBeforeBound, endAtBound);
    }

    @NonNull
    public Query whereEqualTo(@NonNull String field, Object value) {
        return withFilter(field, "==", value);
    }

    @NonNull
    public Query whereEqualTo(@NonNull FieldPath fieldPath, Object value) {
        return withFilter(fieldPath.getCanonicalString(), "==", value);
    }

    @NonNull
    public Query whereNotEqualTo(@NonNull String field, Object value) {
        return withFilter(field, "!=", value);
    }

    @NonNull
    public Query whereNotEqualTo(@NonNull FieldPath fieldPath, Object value) {
        return withFilter(fieldPath.getCanonicalString(), "!=", value);
    }

    @NonNull
    public Query whereLessThan(@NonNull String field, @NonNull Object value) {
        return withFilter(field, "<", value);
    }

    @NonNull
    public Query whereLessThan(@NonNull FieldPath fieldPath, @NonNull Object value) {
        return withFilter(fieldPath.getCanonicalString(), "<", value);
    }

    @NonNull
    public Query whereLessThanOrEqualTo(@NonNull String field, @NonNull Object value) {
        return withFilter(field, "<=", value);
    }

    @NonNull
    public Query whereLessThanOrEqualTo(@NonNull FieldPath fieldPath, @NonNull Object value) {
        return withFilter(fieldPath.getCanonicalString(), "<=", value);
    }

    @NonNull
    public Query whereGreaterThan(@NonNull String field, @NonNull Object value) {
        return withFilter(field, ">", value);
    }

    @NonNull
    public Query whereGreaterThan(@NonNull FieldPath fieldPath, @NonNull Object value) {
        return withFilter(fieldPath.getCanonicalString(), ">", value);
    }

    @NonNull
    public Query whereGreaterThanOrEqualTo(@NonNull String field, @NonNull Object value) {
        return withFilter(field, ">=", value);
    }

    @NonNull
    public Query whereGreaterThanOrEqualTo(@NonNull FieldPath fieldPath, @NonNull Object value) {
        return withFilter(fieldPath.getCanonicalString(), ">=", value);
    }

    @NonNull
    public Query whereArrayContains(@NonNull String field, @NonNull Object value) {
        return withFilter(field, "array-contains", value);
    }

    @NonNull
    public Query whereArrayContains(@NonNull FieldPath fieldPath, @NonNull Object value) {
        return withFilter(fieldPath.getCanonicalString(), "array-contains", value);
    }

    @NonNull
    public Query whereArrayContainsAny(@NonNull String field, @NonNull List<?> values) {
        return withFilter(field, "array-contains-any", values);
    }

    @NonNull
    public Query whereArrayContainsAny(@NonNull FieldPath fieldPath, @NonNull List<?> values) {
        return withFilter(fieldPath.getCanonicalString(), "array-contains-any", values);
    }

    @NonNull
    public Query whereIn(@NonNull String field, @NonNull List<?> values) {
        return withFilter(field, "in", values);
    }

    @NonNull
    public Query whereIn(@NonNull FieldPath fieldPath, @NonNull List<?> values) {
        return withFilter(fieldPath.getCanonicalString(), "in", values);
    }

    @NonNull
    public Query whereNotIn(@NonNull String field, @NonNull List<?> values) {
        return withFilter(field, "not-in", values);
    }

    @NonNull
    public Query whereNotIn(@NonNull FieldPath fieldPath, @NonNull List<?> values) {
        return withFilter(fieldPath.getCanonicalString(), "not-in", values);
    }

    @NonNull
    public Query orderBy(@NonNull String field) {
        return withOrder(field, Direction.ASCENDING);
    }

    @NonNull
    public Query orderBy(@NonNull String field, @NonNull Direction direction) {
        return withOrder(field, direction);
    }

    @NonNull
    public Query orderBy(@NonNull FieldPath fieldPath) {
        return withOrder(fieldPath.getCanonicalString(), Direction.ASCENDING);
    }

    @NonNull
    public Query orderBy(@NonNull FieldPath fieldPath, @NonNull Direction direction) {
        return withOrder(fieldPath.getCanonicalString(), direction);
    }

    @NonNull
    public Query limit(long limit) {
        return new Query(firestore, collectionPath, filters, orders, Math.max(0L, limit), 0L, startAtBound, startAfterBound, endBeforeBound, endAtBound);
    }

    @NonNull
    public Query limitToLast(long limit) {
        return new Query(firestore, collectionPath, filters, orders, 0L, Math.max(0L, limit), startAtBound, startAfterBound, endBeforeBound, endAtBound);
    }

    private BoundSpec boundFromSnapshot(DocumentSnapshot snapshot) {
        if (snapshot == null) return null;
        List<Object> vals = new ArrayList<>();
        for (OrderSpec o : orders) {
            vals.add("__name__".equals(o.field) ? snapshot.getId() : snapshot.get(o.field));
        }
        return new BoundSpec(snapshot.getId(), vals);
    }

    @NonNull
    public Query startAt(@NonNull DocumentSnapshot snapshot) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, boundFromSnapshot(snapshot), startAfterBound, endBeforeBound, endAtBound);
    }

    @NonNull
    public Query startAt(Object... fieldValues) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, new BoundSpec(null, fieldValues == null ? Collections.emptyList() : Arrays.asList(fieldValues)), startAfterBound, endBeforeBound, endAtBound);
    }

    @NonNull
    public Query startAfter(@NonNull DocumentSnapshot snapshot) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, boundFromSnapshot(snapshot), endBeforeBound, endAtBound);
    }

    @NonNull
    public Query startAfter(Object... fieldValues) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, new BoundSpec(null, fieldValues == null ? Collections.emptyList() : Arrays.asList(fieldValues)), endBeforeBound, endAtBound);
    }

    @NonNull
    public Query endBefore(@NonNull DocumentSnapshot snapshot) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, startAfterBound, boundFromSnapshot(snapshot), endAtBound);
    }

    @NonNull
    public Query endBefore(Object... fieldValues) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, startAfterBound, new BoundSpec(null, fieldValues == null ? Collections.emptyList() : Arrays.asList(fieldValues)), endAtBound);
    }

    @NonNull
    public Query endAt(@NonNull DocumentSnapshot snapshot) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, startAfterBound, endBeforeBound, boundFromSnapshot(snapshot));
    }

    @NonNull
    public Query endAt(Object... fieldValues) {
        return new Query(firestore, collectionPath, filters, orders, limitCount, limitToLastCount, startAtBound, startAfterBound, endBeforeBound, new BoundSpec(null, fieldValues == null ? Collections.emptyList() : Arrays.asList(fieldValues)));
    }

    @NonNull
    public AggregateQuery count() {
        return new AggregateQuery(this);
    }

    @NonNull
    public Task<QuerySnapshot> get() {
        return get(Source.DEFAULT);
    }

    @NonNull
    public Task<QuerySnapshot> get(@NonNull Source source) {
        return CfFirestoreTransport.executeQuery(this, source == null ? Source.DEFAULT : source);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull EventListener<QuerySnapshot> listener) {
        return CfFirestoreTransport.listenQuery(this, null, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull MetadataChanges metadataChanges, @NonNull EventListener<QuerySnapshot> listener) {
        return CfFirestoreTransport.listenQuery(this, null, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull Activity activity, @NonNull EventListener<QuerySnapshot> listener) {
        return CfFirestoreTransport.listenQuery(this, activity, listener);
    }

    @NonNull
    public ListenerRegistration addSnapshotListener(@NonNull Executor executor, @NonNull EventListener<QuerySnapshot> listener) {
        return CfFirestoreTransport.listenQuery(this, null, listener);
    }
}

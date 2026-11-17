package com.google.firebase.firestore;

import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Query over a Cloudflare D1 collection or subcollection. */
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
        final Direction dir;

        OrderSpec(String field, Direction dir) {
            this.field = field;
            this.dir = dir;
        }
    }

    final FirebaseFirestore firestore;
    final String parentPath;
    final List<FilterSpec> filters;
    final List<OrderSpec> orders;
    final Integer limitValue;
    final boolean limitToLastFlag;
    final Object[] startAfterValues;
    final Object[] startAtValues;
    final Object[] endBeforeValues;
    final Object[] endAtValues;

    Query(FirebaseFirestore firestore, String parentPath) {
        this(firestore, parentPath, new ArrayList<>(), new ArrayList<>(), null, false, null, null, null, null);
    }

    Query(FirebaseFirestore firestore, String parentPath, List<FilterSpec> filters, List<OrderSpec> orders,
          Integer limitValue, boolean limitToLastFlag,
          Object[] startAfterValues, Object[] startAtValues, Object[] endBeforeValues, Object[] endAtValues) {
        this.firestore = firestore;
        this.parentPath = parentPath;
        this.filters = filters;
        this.orders = orders;
        this.limitValue = limitValue;
        this.limitToLastFlag = limitToLastFlag;
        this.startAfterValues = startAfterValues;
        this.startAtValues = startAtValues;
        this.endBeforeValues = endBeforeValues;
        this.endAtValues = endAtValues;
    }

    private Query copy(List<FilterSpec> f, List<OrderSpec> o, Integer lim, boolean ltl,
                       Object[] sa, Object[] sat, Object[] eb, Object[] eat) {
        return new Query(firestore, parentPath, f, o, lim, ltl, sa, sat, eb, eat);
    }

    private Query addFilter(String field, String op, Object value) {
        List<FilterSpec> next = new ArrayList<>(filters);
        next.add(new FilterSpec(field, op, value));
        return copy(next, orders, limitValue, limitToLastFlag, startAfterValues, startAtValues, endBeforeValues, endAtValues);
    }

    public Query whereEqualTo(String field, Object value) { return addFilter(field, "==", value); }
    public Query whereEqualTo(FieldPath field, Object value) { return addFilter(field.getFieldPathString(), "==", value); }
    public Query whereNotEqualTo(String field, Object value) { return addFilter(field, "!=", value); }
    public Query whereGreaterThan(String field, Object value) { return addFilter(field, ">", value); }
    public Query whereGreaterThanOrEqualTo(String field, Object value) { return addFilter(field, ">=", value); }
    public Query whereLessThan(String field, Object value) { return addFilter(field, "<", value); }
    public Query whereLessThanOrEqualTo(String field, Object value) { return addFilter(field, "<=", value); }
    public Query whereArrayContains(String field, Object value) { return addFilter(field, "array-contains", value); }
    public Query whereArrayContainsAny(String field, List<?> values) { return addFilter(field, "array-contains-any", values); }
    public Query whereIn(String field, List<?> values) { return addFilter(field, "in", values); }
    public Query whereIn(FieldPath field, List<?> values) { return addFilter(field.getFieldPathString(), "in", values); }

    public Query orderBy(String field) { return orderBy(field, Direction.ASCENDING); }
    public Query orderBy(FieldPath field) { return orderBy(field.getFieldPathString(), Direction.ASCENDING); }
    public Query orderBy(FieldPath field, Direction direction) { return orderBy(field.getFieldPathString(), direction); }

    public Query orderBy(String field, Direction direction) {
        List<OrderSpec> next = new ArrayList<>(orders);
        next.add(new OrderSpec(field, direction == null ? Direction.ASCENDING : direction));
        return copy(filters, next, limitValue, limitToLastFlag, startAfterValues, startAtValues, endBeforeValues, endAtValues);
    }

    public Query limit(long limit) {
        return copy(filters, orders, (int) Math.max(0, limit), false, startAfterValues, startAtValues, endBeforeValues, endAtValues);
    }

    public Query limitToLast(long limit) {
        return copy(filters, orders, (int) Math.max(0, limit), true, startAfterValues, startAtValues, endBeforeValues, endAtValues);
    }

    private Object[] extractCursor(DocumentSnapshot snapshot) {
        if (snapshot == null) return new Object[0];
        if (orders.isEmpty()) return new Object[]{snapshot.getId()};
        Object[] vals = new Object[orders.size()];
        for (int i = 0; i < orders.size(); i++) {
            String f = orders.get(i).field;
            vals[i] = "__name__".equals(f) ? snapshot.getId() : snapshot.get(f);
        }
        return vals;
    }

    public Query startAfter(DocumentSnapshot snapshot) {
        return copy(filters, orders, limitValue, limitToLastFlag, extractCursor(snapshot), startAtValues, endBeforeValues, endAtValues);
    }

    public Query startAfter(Object... fieldValues) {
        return copy(filters, orders, limitValue, limitToLastFlag, fieldValues, startAtValues, endBeforeValues, endAtValues);
    }

    public Query startAt(DocumentSnapshot snapshot) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, extractCursor(snapshot), endBeforeValues, endAtValues);
    }

    public Query startAt(Object... fieldValues) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, fieldValues, endBeforeValues, endAtValues);
    }

    public Query endBefore(DocumentSnapshot snapshot) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, startAtValues, extractCursor(snapshot), endAtValues);
    }

    public Query endBefore(Object... fieldValues) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, startAtValues, fieldValues, endAtValues);
    }

    public Query endAt(DocumentSnapshot snapshot) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, startAtValues, endBeforeValues, extractCursor(snapshot));
    }

    public Query endAt(Object... fieldValues) {
        return copy(filters, orders, limitValue, limitToLastFlag, startAfterValues, startAtValues, endBeforeValues, fieldValues);
    }

    public AggregateQuery count() {
        return new AggregateQuery(this);
    }

    JSONArray filtersJson() {
        JSONArray arr = new JSONArray();
        for (FilterSpec f : filters) {
            JSONObject o = new JSONObject();
            try {
                o.put("field", f.field);
                o.put("op", f.op);
                o.put("value", FirestoreJson.encode(f.value));
            } catch (Exception ignored) { }
            arr.put(o);
        }
        return arr;
    }

    private JSONObject toQueryBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("parent", parentPath);
            body.put("filters", filtersJson());
            JSONArray ord = new JSONArray();
            for (OrderSpec os : orders) {
                JSONObject o = new JSONObject();
                o.put("field", os.field);
                o.put("direction", os.dir == Direction.DESCENDING ? "DESCENDING" : "ASCENDING");
                ord.put(o);
            }
            body.put("orderBy", ord);
            if (limitValue != null) body.put("limit", limitValue);
            if (limitToLastFlag) body.put("limitToLast", true);
            if (startAfterValues != null) body.put("startAfter", encodeCursor(startAfterValues));
            if (startAtValues != null) body.put("startAt", encodeCursor(startAtValues));
            if (endBeforeValues != null) body.put("endBefore", encodeCursor(endBeforeValues));
            if (endAtValues != null) body.put("endAt", encodeCursor(endAtValues));
        } catch (Exception ignored) { }
        return body;
    }

    private static JSONArray encodeCursor(Object[] vals) {
        JSONArray a = new JSONArray();
        for (Object v : vals) a.put(FirestoreJson.encode(v));
        return a;
    }

    QuerySnapshot parseQuerySnapshot(JSONArray arr) {
        List<QueryDocumentSnapshot> docs = new ArrayList<>();
        SnapshotMetadata meta = new SnapshotMetadata(false, false);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String p = item.optString("path", "");
                String id = item.optString("id", "");
                Map<String, Object> data = FirestoreJson.decodeMap(item.optJSONObject("data"));
                long ct = item.optLong("createTime", 0L);
                long ut = item.optLong("updateTime", 0L);
                docs.add(new QueryDocumentSnapshot(firestore, p, id, data, meta, ct, ut));
            }
        }
        return new QuerySnapshot(docs, null, meta);
    }

    public Task<QuerySnapshot> get() {
        return get(Source.DEFAULT);
    }

    public Task<QuerySnapshot> get(Source source) {
        return firestore.runAsync(() -> {
            JSONArray arr = FirestoreTransport.query(toQueryBody());
            return parseQuerySnapshot(arr);
        });
    }

    public ListenerRegistration addSnapshotListener(EventListener<QuerySnapshot> listener) {
        return firestore.registerQueryListener(this, listener);
    }

    public ListenerRegistration addSnapshotListener(MetadataChanges metadataChanges, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(Executor executor, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }

    public ListenerRegistration addSnapshotListener(android.app.Activity activity, EventListener<QuerySnapshot> listener) {
        return addSnapshotListener(listener);
    }
}

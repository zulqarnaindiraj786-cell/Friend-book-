package com.friendbook.social.d1.documents;

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

    final D1DocumentStore firestore;
    final String parentPath;
    final String postTag;
    private final boolean feedEngagement;
    final List<FilterSpec> filters;
    final List<OrderSpec> orders;
    final Integer limitValue;
    final boolean limitToLastFlag;
    final Object[] startAfterValues;
    final Object[] startAtValues;
    final Object[] endBeforeValues;
    final Object[] endAtValues;

    Query(D1DocumentStore firestore, String parentPath) {
        this(firestore, parentPath, new ArrayList<>(), new ArrayList<>(), null, false, null, null, null, null, null, false);
    }

    Query(D1DocumentStore firestore, String parentPath, List<FilterSpec> filters, List<OrderSpec> orders,
          Integer limitValue, boolean limitToLastFlag,
          Object[] startAfterValues, Object[] startAtValues, Object[] endBeforeValues, Object[] endAtValues, String postTag, boolean feedEngagement) {
        this.firestore = firestore;
        this.parentPath = parentPath;
        this.postTag = postTag;
        this.feedEngagement = feedEngagement;
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
        return new Query(firestore, parentPath, f, o, lim, ltl, sa, sat, eb, eat, postTag, feedEngagement);
    }

    public Query matchingPostTag(String tag) {
        return new Query(firestore, parentPath, filters, orders, limitValue, limitToLastFlag,
                startAfterValues, startAtValues, endBeforeValues, endAtValues, tag, feedEngagement);
    }

    public Query withFeedEngagement() {
        return new Query(firestore, parentPath, filters, orders, limitValue, limitToLastFlag,
                startAfterValues, startAtValues, endBeforeValues, endAtValues, postTag, true);
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
        boolean hasDocumentId = orders.stream().anyMatch(order -> "__name__".equals(order.field));
        Object[] vals = new Object[orders.size() + (hasDocumentId ? 0 : 1)];
        for (int i = 0; i < orders.size(); i++) {
            String f = orders.get(i).field;
            vals[i] = "__name__".equals(f) ? snapshot.getId() : snapshot.get(f);
        }
        // Firestore implicitly adds document ID as the final stable tie-breaker. Carry it in
        // cursors so equal timestamps paginate without repeats or skipped rows.
        if (!hasDocumentId) vals[orders.size()] = snapshot.getId();
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
                o.put("value", D1DocumentJson.encode(f.value));
            } catch (Exception ignored) { }
            arr.put(o);
        }
        return arr;
    }

    JSONObject toQueryBody() {
        JSONObject body = new JSONObject();
        try {
            body.put("parent", parentPath);
            if (feedEngagement) body.put("feedEngagement", true);
            if (postTag != null) body.put("postTag", postTag);
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
        for (Object v : vals) a.put(D1DocumentJson.encode(v));
        return a;
    }

    QuerySnapshot parseQuerySnapshot(JSONArray arr) {
        return parseQuerySnapshot(arr, false);
    }

    QuerySnapshot parseQuerySnapshot(JSONArray arr, boolean fromCache) {
        List<QueryDocumentSnapshot> docs = new ArrayList<>();
        SnapshotMetadata meta = new SnapshotMetadata(fromCache, false);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String p = item.optString("path", "");
                String id = item.optString("id", "");
                Map<String, Object> data = D1DocumentJson.decodeMap(item.optJSONObject("data"));
                if (feedEngagement && data != null) {
                    data.remove("__feedEngagement");
                    JSONObject summary=item.optJSONObject("engagement");
                    if (summary != null) data.put("__feedEngagement",summary.toString());
                }
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

    /** Explicit on-demand lists: reuse even an empty cached result; never revalidate in background.
     * Cache keys are account-scoped. Pull-to-refresh must pass force=true.
     */
    public Task<QuerySnapshot> getCachedOrFetch(boolean force) {
        return getCachedOrFetch(force,Long.MAX_VALUE);
    }

    /** Search results can use a short reuse window instead of indefinite list reuse. */
    public Task<QuerySnapshot> getCachedOrFetch(boolean force,long maxAgeMs) {
        if (force) return get(Source.SERVER);
        // A non-forced cold/evicted page needs a network read, not an explicit
        // freshness override: overlapping callers can share that pending work.
        if (!hasFreshLocalPage(maxAgeMs)) return get(Source.DEFAULT);
        return get(Source.CACHE).continueWithTask(Runnable::run, task ->
                task.isSuccessful() ? com.google.android.gms.tasks.Tasks.forResult(task.getResult())
                        : get(Source.DEFAULT));
    }

    /** Automatic screen-owned reads wait for foreground and are cancelled when the screen stops. */
    public Task<QuerySnapshot> getAutomatic(ForegroundReadScope scope) {
        return getAutomatic(scope,D1DocumentStore.authenticatedUid());
    }

    private Task<QuerySnapshot> getAutomatic(ForegroundReadScope scope,String actor) {
        return scope.read(() -> get(Source.DEFAULT,true,actor));
    }

    public Task<QuerySnapshot> getCachedOrFetchAutomatic(ForegroundReadScope scope,long maxAgeMs) {
        String actor=D1DocumentStore.authenticatedUid();
        if (!hasFreshLocalPage(maxAgeMs)) return getAutomatic(scope,actor);
        return get(Source.CACHE,false,actor).continueWithTask(Runnable::run, task ->
                task.isSuccessful() ? com.google.android.gms.tasks.Tasks.forResult(task.getResult())
                        : getAutomatic(scope,actor));
    }

    public Task<QuerySnapshot> get(Source source) { return get(source,false,null); }

    private Task<QuerySnapshot> get(Source source,boolean automatic,String requestedActor) {
        final String actor = requestedActor == null ? D1DocumentStore.authenticatedUid() : requestedActor;
        if (!actor.equals(D1DocumentStore.authenticatedUid())) return com.google.android.gms.tasks.Tasks.forException(
                new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED,"Account changed before query"));
        final String key = D1DocumentStore.queryCacheKey(this, actor);
        if (source == Source.CACHE) {
            return firestore.runCacheRead(() -> {
                String cached = firestore.getCachedQuery(key);
                QuerySnapshot snapshot = null;
                if (cached != null) {
                    try { snapshot = parseQuerySnapshot(new JSONArray(cached), true); }
                    catch (Exception ignored) { /* Treat a corrupt cache as a miss. */ }
                }
                if (!actor.equals(D1DocumentStore.authenticatedUid()))
                    throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED, "Account changed during cache read");
                if (snapshot == null)
                    throw new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, "No cached query result");
                return snapshot;
            });
        }
        boolean firstFeedPage = "posts".equals(parentPath) && feedEngagement && limitValue != null
                && startAfterValues == null && startAtValues == null && endBeforeValues == null && endAtValues == null;
        // An explicit DEFAULT read may promote an identical queued automatic
        // flight; SERVER remains independent and never joins.
        DocumentReadFlights.Access<QuerySnapshot> access = firestore.queryReadFlights.readPromotable(
                actor,key,source == Source.DEFAULT,!automatic,allowBackground -> {
            java.util.concurrent.Callable<QuerySnapshot> fetch = () -> {
                if (!actor.equals(D1DocumentStore.authenticatedUid()))
                    throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED, "Account changed before queued query");
                if (!allowBackground.get() && !com.friendbook.social.App.isForeground())
                    throw new D1DocumentStore.BackgroundQueryDeferred();
                JSONArray arr = D1DocumentTransport.query(toQueryBody());
                if (!actor.equals(D1DocumentStore.authenticatedUid()))
                    throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED, "Account changed during query");
                firestore.cacheQuery(key, arr.toString());
                return parseQuerySnapshot(arr);
            };
            return firstFeedPage ? firestore.runFeedFirstPage(fetch) : firestore.runAsync(fetch);
        });
        if (!automatic && access.promotedAutomatic) {
            // Promotion can race the worker's foreground decision. A user read
            // must still run even if the automatic attempt was already deferred.
            return access.task.continueWithTask(Runnable::run, completed ->
                    completed.getException() instanceof D1DocumentStore.BackgroundQueryDeferred
                            ? get(source,false,actor) : completed);
        }
        return access.task;
    }

    public boolean hasFreshLocalPage(long maxAgeMs) {
        return firestore.isQueryCacheFresh(D1DocumentStore.queryCacheKey(this,D1DocumentStore.authenticatedUid()),parentPath,maxAgeMs);
    }

    /** Encode the exact merge input; partial/different cached heads must never share results. */
    JSONArray cachedHeadRows(QuerySnapshot cached) throws org.json.JSONException {
        JSONArray result=new JSONArray();
        for(DocumentSnapshot doc:cached.getDocuments()) {
            JSONObject row=new JSONObject();
            java.util.Map<String,Object> data=new java.util.HashMap<>(doc.getData());
            if(feedEngagement){
                Object summary=data.remove("__feedEngagement");
                if(summary instanceof String)try{row.put("engagement",new JSONObject((String)summary));}catch(org.json.JSONException ignored){}
            }
            row.put("path",doc.getPath()).put("id",doc.getId()).put("data",D1DocumentJson.encodeMap(data));
            if(doc.getCreateTime()!=null)row.put("createTime",doc.getCreateTime().toDate().getTime());
            if(doc.getUpdateTime()!=null)row.put("updateTime",doc.getUpdateTime().toDate().getTime());
            result.put(row);
        }
        return result;
    }

    static String deltaFlightKey(String headKey,Query delta,JSONArray cachedRows) {
        return new JSONArray().put("delta-head").put(headKey).put(delta.toQueryBody()).put(cachedRows).toString();
    }

    /** Refresh a descending head without skipping a burst larger than one page. */
    public Task<QuerySnapshot> refreshCachedHead(QuerySnapshot cached) {
        // This is an ordinary lifecycle load, not an explicit forced refresh.
        if (cached == null || cached.isEmpty() || limitValue == null || limitValue <= 0) return get(Source.DEFAULT);
        final String actor = D1DocumentStore.authenticatedUid();
        final Query delta = endBefore(cached.getDocuments().get(0)).limitToLast(limitValue);
        final String key = D1DocumentStore.queryCacheKey(this, actor);
        final JSONArray cachedRows;
        try { cachedRows=cachedHeadRows(cached); }
        catch(Exception error) { return com.google.android.gms.tasks.Tasks.forException(error); }
        // Include the complete cached merge input, not just the first cursor.
        // Mutations/pushes detach this registry along with ordinary queries.
        return firestore.queryReadFlights.read(actor,deltaFlightKey(key,delta,cachedRows),true,()->firestore.runAsync(() -> {
            if (!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED, "Account changed before queued delta read");
            JSONArray updated = D1DocumentTransport.query(delta.toQueryBody());
            if (!actor.equals(D1DocumentStore.authenticatedUid()))
                throw new D1DocumentStoreException(D1DocumentStoreException.Code.CANCELLED, "Account changed during delta read");
            java.util.LinkedHashMap<String,JSONObject> rows = new java.util.LinkedHashMap<>();
            for(int i=0;i<updated.length();i++) {
                JSONObject row=updated.getJSONObject(i);
                rows.put(row.getString("id"),row);
            }
            for(int i=0;i<cachedRows.length();i++) {
                if(rows.size()>=limitValue)break;
                JSONObject row=cachedRows.getJSONObject(i);
                if(!rows.containsKey(row.getString("id")))rows.put(row.getString("id"),row);
            }
            JSONArray merged=new JSONArray(rows.values());
            firestore.cacheQuery(key,merged.toString());
            return parseQuerySnapshot(merged);
        }));
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

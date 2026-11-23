package com.friendbook.social.d1.documents;

import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cloudflare D1-backed implementation of D1DocumentStore.
 * Keeps the exact com.friendbook.social.d1.documents API so all ~80k lines of UI/feature
 * code work unchanged while 100% of app data lives in Cloudflare D1.
 */
public class D1DocumentStore {
    private static final D1DocumentStore INSTANCE = new D1DocumentStore();
    // Keep network work bounded: a cached pool can create one thread per slow request on flaky
    // mobile networks, which starves the UI and battery. Each listener has an
    // in-flight guard so slow polls cannot pile up every five seconds.
    // Separate bounded queues stop background polling from blocking foreground
    // page reads, friend actions and chat sends on a slow connection.
    private final ExecutorService executor = new java.util.concurrent.ThreadPoolExecutor(
            8, 8, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(256),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    // Disk cache lookup and JSON decoding must not block scrolling or wait behind network calls.
    private final ExecutorService cacheReadExecutor = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService listenerExecutor = new java.util.concurrent.ThreadPoolExecutor(
            4, 4, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(128),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService inboxExecutor = new java.util.concurrent.ThreadPoolExecutor(
            3, 3, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ExecutorService feedFirstPageExecutor = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    // One FIFO per stripe: a slow room does not block rooms assigned to other stripes.
    private final ExecutorService[] chatWriteExecutors = {
            newChatWriteLane(), newChatWriteLane(), newChatWriteLane(), newChatWriteLane()
    };
    private static ExecutorService newChatWriteLane() {
        return new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(6), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }
    private final ExecutorService chatReadExecutor = new java.util.concurrent.ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16),
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PendingPushActors pendingNotificationPushes = new PendingPushActors();
    private final ChatPushRefresh chatPushRefresh = new ChatPushRefresh(
            D1DocumentStore::authenticatedUid, this::notifyCollectionListeners, mainHandler::postDelayed);
    private static final long NOTIFICATION_PUSH_COALESCE_MS = 750L;
    private final Map<String, DocumentSnapshot> docCache = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, DocumentSnapshot>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, DocumentSnapshot> entry) {
                    return size() > 256;
                }
            });
    // Warm in-process cache for revisited screens. Query keys include the authenticated UID to
    // prevent one account's cached results being shown after an account switch.
    private final Map<String, String> queryCache = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<DocListenerEntry>> docListeners = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<QueryListenerEntry> queryListeners = new CopyOnWriteArrayList<>();

    private static final class DocListenerEntry {
        final DocumentReference ref;
        final String actor = authenticatedUid();
        final EventListener<DocumentSnapshot> listener;
        volatile boolean active = true;
        volatile long lastAttemptMs = -60_000;
        final QueryDeliveryState deliveryState = new QueryDeliveryState();
        final PollBudget budget = new PollBudget();
        final AtomicBoolean inFlight = new AtomicBoolean(false);

        DocListenerEntry(DocumentReference ref, EventListener<DocumentSnapshot> listener) {
            this.ref = ref;
            this.listener = listener;
        }
    }

    private static final class QueryListenerEntry {
        final Query query;
        final String actor = authenticatedUid();
        final EventListener<QuerySnapshot> listener;
        volatile boolean active = true;
        volatile boolean cacheReady;
        volatile long lastAttemptMs = -60_000;
        final QueryDeliveryState deliveryState = new QueryDeliveryState();
        final PollBudget budget = new PollBudget();
        final AtomicBoolean inFlight = new AtomicBoolean(false);
        final AtomicBoolean refreshPending = new AtomicBoolean(false);
        final AtomicBoolean forceDelivery = new AtomicBoolean(false);

        org.json.JSONObject revisions = new org.json.JSONObject();
        org.json.JSONArray priorRows = new org.json.JSONArray();

        QueryListenerEntry(Query query, EventListener<QuerySnapshot> listener) {
            this.query = query;
            this.listener = listener;
        }
    }

    private final java.util.concurrent.atomic.AtomicBoolean tickerScheduled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Nothing is scheduled until a screen that actually needs live signaling is open. */
    private D1DocumentStore() { }

    /** Arm the single signaling ticker only while something can use it. */
    private void armSignalingTicker() {
        if (!tickerScheduled.compareAndSet(false, true)) return;
        try {
            poller.schedule(this::signalingTick, 5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            tickerScheduled.set(false);
        }
    }

    private void signalingTick() {
        try { pollActiveListeners(); }
        finally {
            tickerScheduled.set(false);
            // Re-arm only when the app is still visible and a listener can still
            // use a periodic read. A closed or backgrounded app owns no timer and
            // therefore makes zero requests.
            if (hasPeriodicCandidate()) armSignalingTicker();
        }
    }

    private boolean hasPeriodicCandidate() {
        boolean foreground = com.friendbook.social.App.isForeground();
        if (!foreground) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!D1DocumentTransport.periodicAllowed(now)) return false;
        for (CopyOnWriteArrayList<DocListenerEntry> list : docListeners.values()) {
            for (DocListenerEntry e : list) {
                if (e.active && e.actor.equals(authenticatedUid())
                        && ListenerPollingPolicy.intervalMs(e.ref.getPath(), true) != Long.MAX_VALUE)
                    return true;
            }
        }
        for (QueryListenerEntry e : queryListeners) {
            if (e.active && e.actor.equals(authenticatedUid())
                    && ListenerPollingPolicy.intervalMs(e.query.parentPath, true) != Long.MAX_VALUE)
                return true;
        }
        return false;
    }

    /** A single authenticated refresh when the process actually returns to foreground. */
    public void refreshOnForeground() {
        long now=android.os.SystemClock.elapsedRealtime();
        for (CopyOnWriteArrayList<DocListenerEntry> entries : docListeners.values())
            for (DocListenerEntry e : entries) if (now-e.lastAttemptMs>5000) scheduleDocRead(e);
        for (QueryListenerEntry e : queryListeners) if (now-e.lastAttemptMs>5000) scheduleQueryRead(e);
    }

    public static D1DocumentStore getInstance() {
        return INSTANCE;
    }

    public void setFirestoreSettings(D1DocumentStoreSettings settings) {
        // No-op: D1 is the source of truth.
    }

    public CollectionReference collection(String collectionPath) {
        return new CollectionReference(this, collectionPath);
    }

    public DocumentReference document(String documentPath) {
        return new DocumentReference(this, documentPath);
    }

    public WriteBatch batch() {
        return new WriteBatch(this);
    }

    /** One authenticated profile header + first post page; individual listeners remain authoritative. */
    public Task<org.json.JSONObject> fetchProfileBootstrap(String uid) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            org.json.JSONObject result=D1DocumentTransport.request("GET", "/v1/firestore/profile-bootstrap?uid="
                    + D1DocumentTransport.enc(uid), null);
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed during profile read");
            return result;
        });
    }

    /** Seed account-scoped caches only while the same profile request is still active. */
    public void cacheProfileBootstrap(String uid, String actor, org.json.JSONObject result) {
        if (result==null || !actor.equals(authenticatedUid())) return;
        org.json.JSONObject profile=result.optJSONObject("profile"), posts=result.optJSONObject("posts");
        if (profile!=null && profile.optInt("status")==200 && profile.optJSONObject("body")!=null) {
            DocumentReference ref=collection("users").document(uid);
            cacheDoc(ref.getPath(),ref.parseSnapshot(profile.optJSONObject("body")),actor);
        }
        if (posts!=null && posts.optInt("status")==200 && posts.optJSONObject("body")!=null) {
            org.json.JSONArray rows=posts.optJSONObject("body").optJSONArray("documents");
            if (rows!=null) {
                Query query=collection("posts").whereEqualTo("authorId",uid)
                        .orderBy("createdAt", Query.Direction.DESCENDING).limit(7);
                cacheQuery(queryCacheKey(query,actor),rows.toString());
            }
        }
    }

    public Task<Void> claimReferral(String inviter) {
        return runAsync(() -> {
            D1DocumentTransport.request("POST", "/v1/firestore/claim-referral",
                    new org.json.JSONObject().put("inviter", inviter));
            return null;
        });
    }

    public Task<org.json.JSONObject> livePk(org.json.JSONObject body) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed");
            return D1DocumentTransport.request("POST", "/v1/live/pk", body);
        });
    }

    public Task<Void> queueOfficialBroadcast(String id, String text, String mediaUrl, String mediaType) {
        return runAsync(() -> {
            D1DocumentTransport.request("POST", "/v1/admin/broadcast", new org.json.JSONObject()
                    .put("action", "queue").put("id", id).put("text", text)
                    .put("mediaUrl", mediaUrl).put("mediaType", mediaType));
            notifyDocListeners("officialBroadcasts/" + id);
            return null;
        });
    }

    public Task<Boolean> advanceOfficialBroadcast(String id) {
        return runAsync(() -> D1DocumentTransport.request("POST", "/v1/admin/broadcast", new org.json.JSONObject()
                .put("action", "advance").put("id", id)).getBoolean("complete"));
    }

    /** Moderation is authorized by the Worker using the verified admin UID, not UI state. */
    public Task<Void> moderate(String action, String collection, String id) {
        return moderate(action, collection, id, null);
    }

    public Task<Void> moderate(String action, String collection, String id, Object value) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed before moderation");
            D1DocumentTransport.request("POST", "/v1/admin/moderation",
                    new org.json.JSONObject().put("action",action).put("collection",collection).put("id",id).put("value",value));
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed during moderation");
            invalidateDoc(collection + "/" + id);
            // notifyDocListeners also invalidates its parent collection.
            notifyDocListeners(collection + "/" + id);
            return null;
        });
    }

    /** Record only the current viewer's story interaction using the dedicated Worker policy. */
    public Task<Void> storyEngagement(String id, String op, String emoji) {
        final String actor=authenticatedUid();
        return runAsync(() -> {
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed before story interaction");
            org.json.JSONObject body=new org.json.JSONObject().put("id",id).put("op",op);
            if (emoji!=null) body.put("emoji",emoji);
            D1DocumentTransport.request("POST", "/v1/firestore/story-engagement",body);
            if (!actor.equals(authenticatedUid())) throw new D1DocumentStoreException(
                    D1DocumentStoreException.Code.CANCELLED, "Account changed during story interaction");
            invalidateDoc("stories/"+id);
            notifyDocListeners("stories/"+id);
            return null;
        });
    }

    /** Server verifies the signed-in administrator; the UI is not an authorization boundary. */
    public Task<org.json.JSONObject> previewSocialRecovery(String kind, String id) {
        return runAsync(() -> D1DocumentTransport.request("GET", "/v1/admin/recover-social?kind="
                + D1DocumentTransport.enc(kind) + "&id=" + D1DocumentTransport.enc(id), null));
    }

    public Task<org.json.JSONObject> confirmSocialRecovery(String kind, String id, String revision, String owner) {
        return runAsync(() -> D1DocumentTransport.request("POST", "/v1/admin/recover-social",
                new org.json.JSONObject().put("kind", kind).put("id", id).put("revision", revision)
                        .put("owner", owner).put("confirm", true)));
    }

    public <TResult> Task<TResult> runTransaction(Transaction.Function<TResult> updateFunction) {
        return runAsync(() -> {
            Transaction tx = new Transaction(this);
            TResult result = updateFunction.apply(tx);
            tx.commit();
            return result;
        });
    }

    <T> Task<T> runAsync(Callable<T> task) { return runAsync(executor, task); }

    <T> Task<T> runCacheRead(Callable<T> task) { return runAsync(cacheReadExecutor, task); }

    <T> Task<T> runFeedFirstPage(Callable<T> task) { return runAsync(feedFirstPageExecutor, task); }

    <T> Task<T> runChatSend(String roomPath, Callable<T> task) {
        return runAsync(chatWriteExecutors[Math.floorMod(roomPath.hashCode(), chatWriteExecutors.length)], task);
    }

    private <T> Task<T> runAsync(ExecutorService lane, Callable<T> task) {
        TaskCompletionSource<T> tcs = new TaskCompletionSource<>();
        // Capture the caller when work is queued, not when a worker eventually
        // starts it. Otherwise a sign-out/account switch can execute an old
        // screen's queued write with the new account's token.
        final String actor=authenticatedUid();
        try {
            lane.execute(() -> {
                try {
                    tcs.setResult(D1DocumentTransport.runForActor(actor, task));
                } catch (Exception e) {
                    tcs.setException(e);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            tcs.setException(new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE,
                    "Too many requests are already in progress", rejected));
        }
        return tcs.getTask();
    }

    static String authenticatedUid() {
        com.google.firebase.auth.FirebaseUser user = com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
        return user == null ? "signed-out" : user.getUid();
    }

    private static String scopedCacheKey(String uid, String path) { return uid + "|" + path; }

    private final java.util.Map<String,Long> docCachedAt=new java.util.concurrent.ConcurrentHashMap<>();

    boolean ownProfileCacheFresh(String path) {
        String actor=authenticatedUid();
        if(!path.equals("users/"+actor))return false;
        Long saved=docCachedAt.get(actor);
        long now=android.os.SystemClock.elapsedRealtime();
        return saved!=null && now>=saved && now-saved<24*60*60*1000L;
    }

    void cacheDoc(String path, DocumentSnapshot snap) {
        cacheDoc(path, snap, authenticatedUid());
    }
    void cacheDoc(String path, DocumentSnapshot snap, String actor) {
        if (path != null && snap != null) {
            docCache.put(scopedCacheKey(actor, path), snap);
            if(path.equals("users/"+actor) && snap.exists() && !snap.getMetadata().isFromCache())docCachedAt.put(actor,android.os.SystemClock.elapsedRealtime());
        }
    }

    DocumentSnapshot getCachedDoc(String path) {
        return path == null ? null : docCache.get(scopedCacheKey(authenticatedUid(), path));
    }

    private final java.util.Map<String,Long> docFetchedAt = new java.util.concurrent.ConcurrentHashMap<>();

    private void markDocFetched(String path) {
        if (path != null) docFetchedAt.put(scopedCacheKey(authenticatedUid(), path),
                android.os.SystemClock.elapsedRealtime());
    }

    /** True when a cached snapshot can be shown without touching the network. */
    private boolean serveFromCacheOnly(String path) {
        long freshMs = ListenerPollingPolicy.cacheFreshMs(path);
        if (freshMs <= 0 || getCachedDoc(path) == null) return false;
        Long fetchedAt = docFetchedAt.get(scopedCacheKey(authenticatedUid(), path));
        if (fetchedAt == null) return false;
        long now = android.os.SystemClock.elapsedRealtime();
        return now >= fetchedAt && now - fetchedAt < freshMs;
    }

    void invalidateDoc(String path) {
        if (path != null) {
            String actor=authenticatedUid();
            docCache.remove(scopedCacheKey(actor,path));
            docFetchedAt.remove(scopedCacheKey(actor,path));
            if(path.equals("users/"+actor))docCachedAt.remove(actor);
        }
    }

    ListenerRegistration registerDocListener(DocumentReference ref, EventListener<DocumentSnapshot> listener) {
        DocListenerEntry entry = new DocListenerEntry(ref, listener);
        docListeners.compute(ref.getPath(), (path, entries) -> {
            if (entries == null) entries = new CopyOnWriteArrayList<>();
            entries.add(entry);
            return entries;
        });
        DocumentSnapshot cached = getCachedDoc(ref.getPath());
        if (cached != null) mainHandler.post(() -> { if (entry.active && entry.actor.equals(authenticatedUid())) listener.onEvent(cached.asCached(), null); });
        // A recently fetched document is painted from cache; one authoritative read
        // happens only when the cache is stale, missing, or explicitly invalidated.
        if (!serveFromCacheOnly(ref.getPath())) scheduleDocRead(entry);
        if (hasPeriodicCandidate()) armSignalingTicker();
        return () -> {
            entry.active = false;
            docListeners.computeIfPresent(ref.getPath(), (path, entries) -> {
                entries.remove(entry);
                return entries.isEmpty() ? null : entries;
            });
        };
    }

    ListenerRegistration registerQueryListener(Query query, EventListener<QuerySnapshot> listener) {
        QueryListenerEntry entry = new QueryListenerEntry(query, listener);
        queryListeners.add(entry);
        // Decode saved inbox/requests on the cache lane, not the UI thread.
        // Publish cache before letting any authoritative read overtake it.
        try {
            cacheReadExecutor.execute(() -> {
                try {
                    if (!entry.active || !entry.actor.equals(authenticatedUid())) return;
                    String cachedJson = getCachedQuery(queryCacheKey(query, entry.actor));
                    if (cachedJson != null) {
                        org.json.JSONArray cached = new org.json.JSONArray(cachedJson);
                        if (ListenerPollingPolicy.isChatMessages(query.parentPath)) restoreChatRevisions(entry,cached);
                        QuerySnapshot snapshot = query.parseQuerySnapshot(cached, true);
                        entry.deliveryState.cached(cached.toString());
                        mainHandler.post(() -> {
                            if (entry.active && entry.actor.equals(authenticatedUid())) listener.onEvent(snapshot, null);
                        });
                    }
                } catch (Exception ignored) { /* Bad cache never prevents a fresh read. */ }
                finally {
                    entry.cacheReady = true;
                    scheduleQueryRead(entry);
                    if (hasPeriodicCandidate()) armSignalingTicker();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            entry.cacheReady = true;
            scheduleQueryRead(entry);
        }
        return () -> {
            entry.active = false;
            queryListeners.remove(entry);
        };
    }

    private void notifyOnlyDocListeners(String path) {
        CopyOnWriteArrayList<DocListenerEntry> list = docListeners.get(path);
        if (list != null) {
            for (DocListenerEntry e : list) {
                scheduleDocRead(e);
            }
        }
    }

    void notifyDocListeners(String path) {
        notifyOnlyDocListeners(path);
        int idx = path.lastIndexOf('/');
        if (idx > 0) notifyCollectionListeners(path.substring(0, idx));
    }

    /** One server-committed batch may update many documents in the same collection. */
    void notifyCommittedPaths(org.json.JSONArray operations) {
        CommittedInvalidations committed = new CommittedInvalidations();
        for (int i = 0; i < operations.length(); i++) {
            org.json.JSONObject op = operations.optJSONObject(i);
            if (op != null) committed.add(op.optString("path", ""));
        }
        // Invalidate every document before any listener can fetch the final,
        // already-committed state. Do not combine separate commits or pushes.
        for (String path : committed.documents()) invalidateDoc(path);
        for (String path : committed.documents()) notifyOnlyDocListeners(path);
        for (String parent : committed.collections()) notifyCollectionListeners(parent);
    }

    /** FCM is only an invalidation hint; the authenticated D1 query stays authoritative. */
    public Task<org.json.JSONObject> signalCall(String chat, String call, String action, String peer, boolean video) {
        return runAsync(() -> D1DocumentTransport.request("POST", "/v1/chat/call", new org.json.JSONObject()
                .put("chatId",chat).put("callId",call).put("action",action)
                .put("peerId",peer).put("callType",video ? "video" : "voice")));
    }

    public void refreshChatFromPush(String chatId) {
        // Only the global badge hint is coalesced. Messages and call signaling
        // remain immediate; each account has an independent fixed window.
        chatPushRefresh.hint(chatId);
    }

    /** Manual retry for an open conversation when a push is delayed or unavailable. */
    public void refreshChatMessages(String chatId) {
        if (chatId == null || chatId.isEmpty() || chatId.indexOf('/') >= 0) return;
        String path = "chats/" + chatId + "/messages";
        for (QueryListenerEntry entry : queryListeners) {
            if (entry.active && path.equals(entry.query.parentPath)) entry.forceDelivery.set(true);
        }
        notifyCollectionListeners(path);
    }

    public void refreshInboxFromPush(String type) {
        final String actor = authenticatedUid();
        // Combine a short burst of notification pushes without using their
        // untrusted content as data. The first push starts a fixed timer, so
        // continuous traffic cannot postpone refresh forever. Each account has
        // its own pending slot, and separate commits still invalidate immediately.
        if (pendingNotificationPushes.begin(actor)) {
            boolean queued = mainHandler.postDelayed(() -> {
                // Clear before scheduling: a push arriving during this refresh
                // starts another timer rather than losing an update.
                pendingNotificationPushes.finish(actor);
                if (actor.equals(authenticatedUid())) notifyCollectionListeners("notifications");
            }, NOTIFICATION_PUSH_COALESCE_MS);
            if (!queued) {
                pendingNotificationPushes.finish(actor);
                if (actor.equals(authenticatedUid())) notifyCollectionListeners("notifications");
            }
        }
        if ("friend_request".equals(type) || "friend_accept".equals(type) || "friend_accepted".equals(type)) {
            notifyCollectionListeners("friend_requests");
            notifyCollectionListeners("friends");
        }
    }

    private final java.util.concurrent.atomic.AtomicLong postWriteVersion = new java.util.concurrent.atomic.AtomicLong();
    public long postWriteVersion() { return postWriteVersion.get(); }

    void notifyCollectionListeners(String parentPath) {
        if ("posts".equals(parentPath)) postWriteVersion.incrementAndGet();
        if ("groups".equals(parentPath) || "friends".equals(parentPath)) {
            com.friendbook.social.App app=com.friendbook.social.App.instance;
            if(app!=null)app.getSharedPreferences("d1_query_cache_v2",0).edit()
                    .putLong("invalidated_"+cacheHash(authenticatedUid()+"|"+parentPath),System.currentTimeMillis()).apply();
        }
        for (QueryListenerEntry e : queryListeners) {
            if (e.active && parentPath.equals(e.query.parentPath)) {
                e.refreshPending.set(true);
                scheduleQueryRead(e);
            }
        }
    }

    private void pollActiveListeners() {
        // Never perform HTTP on the single scheduled executor. A slow request there used to block
        // every remaining listener (and the next poll cycle), making unrelated screens appear hung.
        long now = android.os.SystemClock.elapsedRealtime();
        if (!D1DocumentTransport.periodicAllowed(now)) return;
        boolean foreground = com.friendbook.social.App.isForeground();
        if (!foreground) return;
        for (CopyOnWriteArrayList<DocListenerEntry> list : docListeners.values()) {
            for (DocListenerEntry e : list) {
                if (e.budget.due(e.ref.getPath(), foreground, now - e.lastAttemptMs)) scheduleDocRead(e);
            }
        }
        for (QueryListenerEntry e : queryListeners) {
            String path = e.query.parentPath;
            boolean visibleList = Integer.valueOf(20).equals(e.query.limitValue)
                    && ("notifications".equals(path) || "friend_requests".equals(path));
            if (!ListenerPollingPolicy.isChatMessages(path) && e.budget.due(path, foreground, now - e.lastAttemptMs, visibleList)) scheduleQueryRead(e);
        }
    }

    private void scheduleDocRead(DocListenerEntry entry) {
        if (!entry.active || !entry.actor.equals(authenticatedUid()) || !entry.inFlight.compareAndSet(false, true)) return;
        try { listenerExecutor.execute(() -> {
            entry.lastAttemptMs = android.os.SystemClock.elapsedRealtime();
            try { D1DocumentTransport.runForActor(entry.actor, () -> { deliverDoc(entry); return null; }); }
            catch (Exception ignored) { entry.inFlight.set(false); }
        }); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void scheduleQueryRead(QueryListenerEntry entry) {
        if (!entry.cacheReady || !entry.active || !entry.actor.equals(authenticatedUid()) || !entry.inFlight.compareAndSet(false, true)) return;
        String path = entry.query.parentPath;
        boolean inbox = ListenerPollingPolicy.isChatMessages(path) || "chats".equals(path)
                || "notifications".equals(path) || "friend_requests".equals(path);
        ExecutorService lane = ListenerPollingPolicy.isChatMessages(path) ? chatReadExecutor
                : inbox ? inboxExecutor : listenerExecutor;
        try { lane.execute(() -> {
            // A push arriving after this point must get one follow-up read, even
            // when the current HTTP request returns the pre-message snapshot.
            entry.refreshPending.set(false);
            boolean forceDelivery = entry.forceDelivery.getAndSet(false);
            entry.lastAttemptMs = android.os.SystemClock.elapsedRealtime();
            try { D1DocumentTransport.runForActor(entry.actor, () -> { deliverQuery(entry, forceDelivery); return null; }); }
            catch (Exception ignored) { }
            finally {
                entry.inFlight.set(false);
                if (entry.refreshPending.get()) scheduleQueryRead(entry);
            }
        }); }
        catch (java.util.concurrent.RejectedExecutionException rejected) { entry.inFlight.set(false); }
    }

    private void deliverDoc(DocListenerEntry entry) {
        if (!entry.active) { entry.inFlight.set(false); return; }
        try {
            org.json.JSONObject response = D1DocumentTransport.getDocument(entry.ref.getPath(),entry.ref.includesProfileAudience());
            DocumentSnapshot snap = entry.ref.parseSnapshot(response);
            if (!entry.actor.equals(authenticatedUid())) return;
            cacheDoc(entry.ref.getPath(), snap, entry.actor);
            docFetchedAt.put(scopedCacheKey(entry.actor, entry.ref.getPath()),
                    android.os.SystemClock.elapsedRealtime());
            // Timestamp equality is not sufficient: concurrent writes may share a
            // millisecond, and an identical result must clear a prior read error.
            boolean changed = entry.deliveryState.server(response.toString());
            entry.budget.success(changed);
            if (!changed) return;
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            entry.budget.failure();
            entry.deliveryState.error();
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(null, ffe);
            });
        } finally {
            entry.inFlight.set(false);
        }
    }

    // Persist the exact server tokens alongside their message bodies in a single cache entry.
    // Do not guess tokens from timestamps: edits and receipts may share a timestamp.
    private static void restoreChatRevisions(QueryListenerEntry entry, org.json.JSONArray rows) throws org.json.JSONException {
        org.json.JSONObject known=new org.json.JSONObject();
        boolean complete=rows.length()<=40;
        for(int i=0;i<rows.length();i++) {
            org.json.JSONObject row=rows.getJSONObject(i);
            Object revision=row.remove("__cachedListenerRevision");
            if(revision instanceof String && !((String)revision).isEmpty())known.put(row.getString("id"),revision);
            else complete=false;
        }
        if(complete){entry.revisions=known;entry.priorRows=rows;}
    }

    private static String chatRowsForCache(org.json.JSONArray rows,org.json.JSONObject revisions) throws org.json.JSONException {
        org.json.JSONArray copy=new org.json.JSONArray(rows.toString());
        for(int i=0;i<copy.length();i++) {
            org.json.JSONObject row=copy.getJSONObject(i);
            String revision=revisions.optString(row.getString("id"));
            if(!revision.isEmpty())row.put("__cachedListenerRevision",revision);
        }
        return copy.toString();
    }

    private void deliverQuery(QueryListenerEntry entry, boolean forceDelivery) {
        if (!entry.active) return;
        try {
            org.json.JSONObject body=toQueryJson(entry.query);
            org.json.JSONArray arr;
            if(entry.query.limitValue!=null && entry.query.limitValue<=40) {
                body.put("knownRevisions",entry.revisions);
                org.json.JSONObject response=D1DocumentTransport.listenerQuery(body);
                arr=response.getJSONArray("documents");
                org.json.JSONArray order=response.optJSONArray("order");
                if(order!=null) {
                    java.util.Map<String,org.json.JSONObject> byId=new java.util.HashMap<>();
                    for(int i=0;i<entry.priorRows.length();i++) {
                        org.json.JSONObject row=entry.priorRows.getJSONObject(i);byId.put(row.getString("id"),row);
                    }
                    for(int i=0;i<arr.length();i++) {
                        org.json.JSONObject row=arr.getJSONObject(i);byId.put(row.getString("id"),row);
                    }
                    org.json.JSONArray merged=new org.json.JSONArray();
                    for(int i=0;i<order.length();i++) {
                        org.json.JSONObject row=byId.get(order.getString(i));
                        if(row==null) { entry.revisions=new org.json.JSONObject(); throw new org.json.JSONException("Incomplete listener delta"); }
                        merged.put(row);
                    }
                    arr=merged;
                }
                org.json.JSONObject versions=response.optJSONObject("revisions");
                entry.revisions=versions==null?new org.json.JSONObject():versions;
                entry.priorRows=arr;
            } else arr=D1DocumentTransport.query(body);
            if (!entry.actor.equals(authenticatedUid())) return;
            String fp = arr.toString();
            cacheQuery(queryCacheKey(entry.query, entry.actor), ListenerPollingPolicy.isChatMessages(entry.query.parentPath)
                    ? chatRowsForCache(arr,entry.revisions) : fp);
            // Cached rows are not server confirmation: the first server result
            // must be delivered even when its contents match the cached snapshot.
            boolean changed = entry.deliveryState.server(fp);
            entry.budget.success(changed);
            if (!changed && !forceDelivery) return;
            QuerySnapshot snap = entry.query.parseQuerySnapshot(arr);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(snap, null);
            });
        } catch (Exception ex) {
            entry.budget.failure();
            entry.deliveryState.error();
            D1DocumentStoreException ffe = ex instanceof D1DocumentStoreException
                    ? (D1DocumentStoreException) ex
                    : new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE, ex.getMessage(), ex);
            mainHandler.post(() -> {
                if (entry.active && entry.actor.equals(authenticatedUid())) entry.listener.onEvent(null, ffe);
            });
        }
    }

    private static boolean isChatCache(String key) {
        int split=key.indexOf('|');
        if (split<0) return false;
        String query=key.substring(split+1);
        return query.contains("\"parent\":\"chats\"") || query.contains("\"parent\":\"chats/");
    }

    private static android.content.SharedPreferences queryPreferences(com.friendbook.social.App app,String key) {
        return app.getSharedPreferences(isChatCache(key) ? "d1_chat_query_cache_v2" : "d1_query_cache_v2",0);
    }

    String getCachedQuery(String key) {
        String cached = queryCache.get(key);
        if (cached != null) return cached;
        com.friendbook.social.App app = com.friendbook.social.App.instance;
        if (app == null) return null;
        String prefKey = "q_" + cacheHash(key);
        cached = queryPreferences(app,key).getString(prefKey, null);
        if (cached == null) cached = app.getSharedPreferences("d1_query_cache_v1", 0).getString(prefKey, null);
        if (cached != null) queryCache.put(key, cached);
        return cached;
    }

    boolean isQueryCacheFresh(String key,String parent,long maxAgeMs) {
        com.friendbook.social.App app=com.friendbook.social.App.instance;
        if(app==null)return false;
        long saved=queryPreferences(app,key).getLong("t_"+cacheHash(key),0L);
        long now=System.currentTimeMillis();
        long invalidated=queryPreferences(app,key).getLong("invalidated_"+cacheHash(authenticatedUid()+"|"+parent),0L);
        return saved>0 && saved>invalidated && saved<=now && now-saved<maxAgeMs;
    }

    synchronized void cacheQuery(String key, String json) {
        queryCache.put(key, json);
        while (queryCache.size() > 32) {
            String evict = queryCache.keySet().iterator().next();
            queryCache.remove(evict);
        }
        // Avoid putting exceptionally large result pages into preferences.
        if (json.length() <= 256 * 1024) {
            com.friendbook.social.App app = com.friendbook.social.App.instance;
            if (app != null) {
                android.content.SharedPreferences prefs = queryPreferences(app,key);
                String prefKey = "q_" + cacheHash(key);
                if (json.equals(prefs.getString(prefKey,null))) { prefs.edit().putLong("t_"+cacheHash(key),System.currentTimeMillis()).apply(); return; }
                java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(java.util.Arrays.asList(prefs.getString("ordered_keys", "").split(",")));
                keys.remove("");keys.remove(prefKey);keys.add(prefKey);
                android.content.SharedPreferences.Editor editor = prefs.edit().putString(prefKey, json).putLong("t_"+cacheHash(key),System.currentTimeMillis());
                while (keys.size() > 32) {
                    String evict = keys.iterator().next(); keys.remove(evict); editor.remove(evict).remove("t_"+evict.substring(2));
                }
                editor.putString("ordered_keys", android.text.TextUtils.join(",",keys)).apply();
            }
        }
    }

    private static String cacheHash(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception impossible) { return Integer.toHexString(value.hashCode()); }
    }

    static String queryCacheKey(Query q, String actor) {
        return actor + "|" + toQueryJson(q).toString();
    }

    // Use the same serialization as one-shot reads: cursors must affect both the request
    // and cache key, otherwise a listener for a later page replays the first page.
    static org.json.JSONObject toQueryJson(Query q) {
        return q.toQueryBody();
    }
}

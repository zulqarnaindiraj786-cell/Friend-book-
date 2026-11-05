package com.friendbook.social.core;

/** Transport coordinator; production Presence/App routing is deliberately unchanged. */
public final class PresenceSocketController {
    public interface Cancel { void cancel(); }
    public interface Connection { boolean send(String text); void close(); }
    public interface TokenResult { void ready(String value, long remainingMillis); void failed(); }
    public interface Events { void opened(Connection socket); void failed(Connection socket, boolean retryable); }
    public interface Runtime {
        long elapsed(); boolean currentActor(String uid); double random();
        Cancel later(long delayMillis, Runnable task);
        void token(String uid, TokenResult result);
        Connection connect(String token, Events events);
    }
    private final Runtime runtime;
    private final PresenceSessionPolicy policy = new PresenceSessionPolicy();
    private String actor = "";
    private Connection connection;
    private Cancel authTimeout, heartbeat, refresh, retry;
    private boolean socketOpen;
    private long consumedToken;
    public PresenceSocketController(Runtime runtime) { this.runtime = runtime; }

    public synchronized void update(String uid, boolean foreground, boolean visible) {
        long before = policy.generation();
        policy.configure(uid, foreground, visible);
        actor = uid == null ? "" : uid;
        if (before != policy.generation()) { cancelWork(); closeConnection(true); }
        if (retry == null && policy.retryIsCurrent(policy.generation())) open();
    }
    public synchronized void stop() { update(null, false, false); }
    private void open() {
        if (!runtime.currentActor(actor)) { stop(); return; }
        final long ticket = policy.begin();
        if (ticket == 0) return;
        final String uid = actor;
        consumedToken = 0;
        authTimeout = runtime.later(15_000L, () -> fail(ticket, null, true));
        try {
            runtime.token(uid, new TokenResult() {
                @Override public void ready(String token, long lease) { received(ticket, uid, token, lease); }
                @Override public void failed() { fail(ticket, null, true); }
            });
        } catch (Exception ignored) { fail(ticket, null, true); }
    }
    private synchronized void received(long ticket, String uid, String token, long lease) {
        if (ticket != policy.generation() || consumedToken == ticket) return;
        if (!runtime.currentActor(uid)) { stop(); return; }
        consumedToken = ticket;
        if (authTimeout != null) authTimeout.cancel();
        if (token == null || token.isEmpty() || lease <= 30_000L || lease > 3_600_000L) { fail(ticket, null, true); return; }
        final long issuedAt = runtime.elapsed();
        if (issuedAt < 0 || issuedAt > Long.MAX_VALUE - lease) { fail(ticket, null, true); return; }
        final long deadline = issuedAt + lease;
        authTimeout = runtime.later(15_000L, () -> fail(ticket, null, true));
        try {
            Connection candidate = runtime.connect(token, new Events() {
                @Override public void opened(Connection socket) { accepted(ticket, uid, deadline, socket); }
                @Override public void failed(Connection socket, boolean retryable) { fail(ticket, socket, retryable); }
            });
            if (ticket != policy.generation()) candidate.close();
            else if (connection == null) connection = candidate;
            else if (connection != candidate) candidate.close();
        } catch (Exception ignored) { fail(ticket, null, true); }
    }
    private synchronized void accepted(long ticket, String uid, long deadline, Connection socket) {
        if (ticket != policy.generation()) { socket.close(); return; }
        if (!runtime.currentActor(uid)) { socket.close(); stop(); return; }
        if (socket == connection && socketOpen) return;
        long lease = deadline - runtime.elapsed();
        if (lease <= 30_000L || lease > 3_600_000L) { fail(ticket, socket, true); socket.close(); return; }
        if (!policy.opened(ticket, lease, runtime.elapsed())) { socket.close(); return; }
        if (authTimeout != null) authTimeout.cancel();
        connection = socket; socketOpen = true;
        if (!send(ticket, "{\"type\":\"active\"}")) return;
        heartbeat = runtime.later(180_000L, () -> beat(ticket));
        refresh = runtime.later(lease - 30_000L, () -> renew(ticket));
    }
    private synchronized void beat(long ticket) {
        if (!send(ticket, "{\"type\":\"active\"}")) return;
        heartbeat = runtime.later(180_000L, () -> beat(ticket));
    }
    private boolean send(long ticket, String message) {
        if (ticket != policy.generation()) return false;
        if (!runtime.currentActor(actor)) { stop(); return false; }
        if (!policy.canSend(ticket, runtime.elapsed())) { renew(ticket); return false; }
        try {
            if (connection == null || !connection.send(message)) { fail(ticket, connection, true); return false; }
        } catch (Exception ignored) { fail(ticket, connection, true); return false; }
        return true;
    }
    private synchronized void renew(long ticket) {
        if (ticket != policy.generation()) return;
        long delay = policy.failed(ticket);
        cancelWork(); closeConnection(false);
        if (delay >= 0 && runtime.currentActor(actor)) open();
        else stop();
    }
    private synchronized void fail(long ticket, Connection failedSocket, boolean retryable) {
        if (ticket != policy.generation()) { if (failedSocket != null && failedSocket != connection) failedSocket.close(); return; }
        long delay = policy.failed(ticket);
        cancelWork(); closeConnection(false);
        if (delay < 0 || !retryable) return;
        if (!runtime.currentActor(actor)) { stop(); return; }
        long retryTicket = policy.generation();
        double random = runtime.random();
        if (!Double.isFinite(random)) random = 1;
        long jittered = delay / 2 + (long)(delay / 2 * Math.max(0, Math.min(1, random)));
        retry = runtime.later(jittered, () -> retry(retryTicket));
    }
    private synchronized void retry(long ticket) {
        if (policy.retryIsCurrent(ticket)) { retry = null; open(); }
    }
    private void cancelWork() {
        for (Cancel task : new Cancel[]{authTimeout, heartbeat, refresh, retry}) if (task != null) task.cancel();
        authTimeout = heartbeat = refresh = retry = null;
    }
    private void closeConnection(boolean offline) {
        Connection old = connection; boolean wasOpen = socketOpen;
        connection = null; socketOpen = false;
        if (old != null) {
            if (offline && wasOpen) try { old.send("{\"type\":\"offline\"}"); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
    }
}

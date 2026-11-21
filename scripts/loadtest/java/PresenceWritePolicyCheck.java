import com.friendbook.social.core.PresenceWritePolicy;
import com.friendbook.social.core.PresencePolicy;

/** Standalone deterministic policy checks. No Android SDK/network/users involved. */
public final class PresenceWritePolicyCheck {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Presence write policy regression");
    }
    public static void main(String[] args) {
        check(2 * PresencePolicy.PROFILE_HEARTBEAT_MS < PresencePolicy.ONLINE_TTL_MS);
        check(3_600_000L / PresencePolicy.PROFILE_HEARTBEAT_MS == 30);
        check(PresencePolicy.typingPollMs(true) == 3_000L);
        check(PresencePolicy.typingPollMs(false) > PresencePolicy.typingPollMs(true));
        check(PresencePolicy.typingPollMs(false) < PresencePolicy.TYPING_IDLE_CLEAR_MS);
        PresenceWritePolicy p = new PresenceWritePolicy();
        long first = p.begin("a", true, 0);
        check(first > 0 && p.begin("a", true, 2500) == 0); // 2.5s < 5s interval -> suppressed
        p.complete(first, true, 0);
        check(p.begin("a", true, 4999) == 0); // 4.999s < 5s interval -> suppressed
        long next = p.begin("a", true, 5000); // 5s >= 5s interval -> new ticket
        check(next > first); p.complete(next, false, 5000);
        check(p.begin("a", true, 7500) > next); // 2.5s after next -> new ticket
        long offline = p.begin("a", false, 7501);
        p.complete(next, true, 7502); p.complete(offline, true, 7503);
        check(p.begin("a", true, 7504) > offline);
        long b = p.begin("b", true, 7505); check(b > 0);
        p.complete(b, true, 7505);
        check(p.begin("b", true, 7504) > 0);
        // Write interval (5s) + buffer (2.5s) < TTL (10s)
        check(PresenceWritePolicy.ONLINE_WRITE_INTERVAL_MS + 2500 < PresencePolicy.ONLINE_TTL_MS);
        p = new PresenceWritePolicy();
        int writes = 0;
        for (long now = 0; now < 3600000; now += 5000) {
            long ticket = p.begin("a", true, now);
            if (ticket != 0) { writes++; p.complete(ticket, true, now); }
        }
        // ~720 writes/hour at 5s interval
        check(writes == 720);
        System.out.println("PASS: duplicate/in-flight suppression, retry, immediate offline/foreground, stale callbacks, account switch, backwards clock, freshness, 720 writes/hour model (5s interval)");
    }
}

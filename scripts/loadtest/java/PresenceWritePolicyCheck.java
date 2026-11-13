import com.friendbook.social.core.PresenceWritePolicy;
import com.friendbook.social.core.PresencePolicy;

/** Standalone deterministic policy checks. No Android SDK/network/users involved. */
public final class PresenceWritePolicyCheck {
    private static void check(boolean value) {
        if (!value) throw new AssertionError("Presence write policy regression");
    }
    public static void main(String[] args) {
        PresenceWritePolicy p = new PresenceWritePolicy();
        long first = p.begin("a", true, 0);
        check(first > 0 && p.begin("a", true, 2500) == 0);
        p.complete(first, true, 0);
        check(p.begin("a", true, 9999) == 0);
        long next = p.begin("a", true, 10000);
        check(next > first); p.complete(next, false, 10000);
        check(p.begin("a", true, 12500) > next);
        long offline = p.begin("a", false, 12501);
        p.complete(next, true, 12502); p.complete(offline, true, 12503);
        check(p.begin("a", true, 12504) > offline);
        long b = p.begin("b", true, 12505); check(b > 0);
        p.complete(b, true, 12505);
        check(p.begin("b", true, 12504) > 0);
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

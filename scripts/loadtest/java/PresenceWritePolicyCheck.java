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
        check(first > 0 && p.begin("a", true, 25000) == 0);
        p.complete(first, true, 0);
        check(p.begin("a", true, 99999) == 0);
        long next = p.begin("a", true, 100000);
        check(next > first); p.complete(next, false, 100000);
        check(p.begin("a", true, 125000) > next);
        long offline = p.begin("a", false, 125001);
        p.complete(next, true, 125002); p.complete(offline, true, 125003);
        check(p.begin("a", true, 125004) > offline);
        long b = p.begin("b", true, 125005); check(b > 0);
        p.complete(b, true, 125005);
        check(p.begin("b", true, 125004) > 0);
        check(PresenceWritePolicy.ONLINE_WRITE_INTERVAL_MS + 25000 < PresencePolicy.ONLINE_TTL_MS);
        p = new PresenceWritePolicy();
        int writes = 0;
        for (long now = 0; now < 3600000; now += 25000) {
            long ticket = p.begin("a", true, now);
            if (ticket != 0) { writes++; p.complete(ticket, true, now); }
        }
        check(writes == 36);
        System.out.println("PASS: duplicate/in-flight suppression, retry, immediate offline/foreground, stale callbacks, account switch, backwards clock, freshness, 36 writes/hour model");
    }
}

package com.friendbook.social.core;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.function.LongConsumer;
/**
 * Counts matching documents for a D1 query.
 *
 * <p>This used to run a Firestore server-side aggregate. With the network off that can never
 * succeed, so it failed silently and returned no
 * count at all. It is now answered from D1, and for the same reason it is a real query rather
 * than a Firestore aggregate: the worker has no count endpoint, and adding one would duplicate
 * the filter logic that {@link DocQuery} already serialises.
 *
 * <p>The count is exact up to the page ceiling. Above that the caller is told the floor rather
 * than a number that looks exact and is not, because every count in this app is compared
 * against a small threshold (invite tiers, eligibility), never displayed as a total.
 */
public final class CountReads {
    private CountReads() {}

    public static ListenerRegistration once(DocQuery query, LongConsumer result) {
        String actor = Fb.uid();
        boolean[] closed = {false};
        // A real COUNT on the server, not a page whose length is taken. Listing to count reads
        // every matching document and reports the page ceiling as the answer once the real
        // number is larger - and these counts only ever get compared against a small threshold.
        CloudflareStore.count(query, total -> {
            if (!closed[0] && java.util.Objects.equals(actor, Fb.uid())) {
                result.accept(total);
            }
        }, () -> {
            // A refusal is "no answer", not zero. Calling back with zero here is exactly how a
            // failed read used to unlock or relock a badge by accident.
        });
        return () -> closed[0] = true;
    }
}

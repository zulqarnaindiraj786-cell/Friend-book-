package com.friendbook.social.core;
import com.friendbook.social.d1.documents.*;
import java.util.Objects;
import java.util.function.LongConsumer;
/** Exact server aggregation, not an unlimited download or listener. Refresh on screen re-entry. */
public final class CountReads {
    private CountReads() {}
    public static ListenerRegistration once(Query query,LongConsumer result) {
        String actor=Fb.uid();boolean[] closed={false};
        query.count().get(AggregateSource.SERVER).addOnSuccessListener(count->{
            if(!closed[0] && Objects.equals(actor,Fb.uid()))result.accept(count.getCount());
        });
        return ()->closed[0]=true;
    }
}

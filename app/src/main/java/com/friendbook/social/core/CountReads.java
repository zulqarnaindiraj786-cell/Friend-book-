package com.friendbook.social.core;
import com.friendbook.social.d1.documents.*;
import java.util.Objects;
import java.util.function.LongConsumer;
/** Exact server aggregation with at most three attempts, cancelled with its owning screen. */
public final class CountReads {
    private CountReads() {}
    public static ListenerRegistration once(Query query,LongConsumer result) {
        return once(query,result,error -> { });
    }
    public static ListenerRegistration once(Query query,LongConsumer result,
                                             java.util.function.Consumer<Exception> failed) {
        final String actor=Fb.uid();
        final boolean[] closed={false};
        final int[] attempts={0};
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable retry=new Runnable(){ @Override public void run() {
            if(closed[0] || actor==null || !Objects.equals(actor,Fb.uid())) return;
            attempts[0]++;
            query.count().get(AggregateSource.SERVER).addOnSuccessListener(count->{
                if(!closed[0] && Objects.equals(actor,Fb.uid())) {
                    closed[0]=true;result.accept(count.getCount());
                }
            }).addOnFailureListener(error->{
                if(closed[0] || actor==null || !Objects.equals(actor,Fb.uid())) return;
                long delay=CountReadRetryPolicy.delayAfterFailure(attempts[0],error);
                if(delay<0L) { closed[0]=true;failed.accept(error);return; }
                handler.postDelayed(this,delay);
            });
        }};
        retry.run();
        return ()->{closed[0]=true;handler.removeCallbacks(retry);};
    }
}

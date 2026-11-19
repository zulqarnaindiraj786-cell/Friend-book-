package com.friendbook.social.core;
import com.friendbook.social.d1.documents.*;
import java.util.Objects;
import java.util.function.LongConsumer;
/** Exact server aggregation, retried after temporary failures while the screen is active. */
public final class CountReads {
    private CountReads() {}
    public static ListenerRegistration once(Query query,LongConsumer result) {
        final String actor=Fb.uid();
        final boolean[] closed={false};
        final int[] failures={0};
        android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable retry=new Runnable(){ @Override public void run() {
            if(closed[0] || !Objects.equals(actor,Fb.uid())) return;
            query.count().get(AggregateSource.SERVER).addOnSuccessListener(count->{
                if(!closed[0] && Objects.equals(actor,Fb.uid()))result.accept(count.getCount());
            }).addOnFailureListener(error->{
                if(closed[0] || !Objects.equals(actor,Fb.uid())) return;
                if(error instanceof D1DocumentStoreException && ((D1DocumentStoreException)error).getCode()==D1DocumentStoreException.Code.PERMISSION_DENIED) return;
                long delay=Math.min(30000L,1000L << Math.min(failures[0]++,5));
                handler.postDelayed(this,delay);
            });
        }};
        retry.run();
        return ()->{closed[0]=true;handler.removeCallbacks(retry);};
    }
}

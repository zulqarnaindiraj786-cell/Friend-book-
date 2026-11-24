package com.friendbook.social.core;

import com.friendbook.social.d1.documents.*;
import java.util.function.*;

/** One cache-first feed-head contract for lifecycle entry and re-entry. No polling. */
public final class FeedHeadReads {
    private FeedHeadReads(){}
    public static void load(Query first,boolean forced,BooleanSupplier active,
            BiConsumer<QuerySnapshot,Boolean> cached,Consumer<QuerySnapshot> fresh,Consumer<Exception> failed){
        if(!active.getAsBoolean())return;
        if(forced){first.get(Source.SERVER).addOnSuccessListener(s->{if(active.getAsBoolean())fresh.accept(s);})
                .addOnFailureListener(e->{if(active.getAsBoolean())failed.accept(e);});return;}
        first.get(Source.CACHE).addOnCompleteListener(task->{
            if(!active.getAsBoolean())return;
            QuerySnapshot saved=task.isSuccessful()?task.getResult():null;
            boolean reusable=saved!=null && first.hasFreshLocalPage(FeedReadPolicy.REUSE_MS);
            if(saved!=null)cached.accept(saved,reusable);
            if(reusable || !active.getAsBoolean())return;
            first.refreshCachedHead(saved).addOnSuccessListener(s->{if(active.getAsBoolean())fresh.accept(s);})
                    .addOnFailureListener(e->{if(active.getAsBoolean())failed.accept(e);});
        });
    }
}

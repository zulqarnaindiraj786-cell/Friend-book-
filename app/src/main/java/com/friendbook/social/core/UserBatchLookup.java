package com.friendbook.social.core;

import com.friendbook.social.d1.documents.FieldPath;
import com.friendbook.social.d1.documents.QuerySnapshot;
import com.friendbook.social.models.Models;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Bounded profile hydration: one cached, account-scoped query per ten user IDs. */
public final class UserBatchLookup {
    private UserBatchLookup() { }

    public static void load(List<String> requested, Consumer<Map<String, Models.User>> callback) {
        final String actor=Fb.uid();
        if(actor==null || requested==null || callback==null)return;
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String uid:requested)if(uid!=null && !uid.isEmpty())unique.add(uid);
        Map<String,Models.User> found=new ConcurrentHashMap<>();
        List<String> missing=new ArrayList<>();
        for(String uid:unique) {
            Models.User cached=FastCache.getUser(uid);
            if(cached!=null)found.put(uid,cached);else missing.add(uid);
        }
        List<List<String>> chunks=new ArrayList<>();
        for(int i=0;i<missing.size();i+=10)chunks.add(new ArrayList<>(missing.subList(i,Math.min(i+10,missing.size()))));
        if(chunks.isEmpty()){if(actor.equals(Fb.uid()))callback.accept(found);return;}
        AtomicInteger remaining=new AtomicInteger(chunks.size());
        for(List<String> chunk:chunks) {
            Fb.db().collection("users").whereIn(FieldPath.documentId(),chunk).limit(10)
                    .getCachedOrFetch(false,300_000L).addOnCompleteListener(task->{
                if(!actor.equals(Fb.uid()))return;
                if(task.isSuccessful() && task.getResult()!=null) {
                    QuerySnapshot page=task.getResult();
                    for(com.friendbook.social.d1.documents.DocumentSnapshot doc:page) {
                        Models.User user=Models.User.fromDoc(doc);
                        if(user.uid==null || user.uid.isEmpty())user.uid=doc.getId();
                        found.put(user.uid,user);FastCache.putUser(user);
                    }
                }
                if(remaining.decrementAndGet()==0 && actor.equals(Fb.uid()))callback.accept(found);
            });
        }
    }
}

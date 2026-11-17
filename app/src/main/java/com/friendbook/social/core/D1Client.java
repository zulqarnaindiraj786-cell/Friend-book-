package com.friendbook.social.core;

import android.net.Uri;
import com.friendbook.social.BuildConfig;
import com.google.android.gms.tasks.*;
import com.google.firebase.auth.FirebaseUser;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Serialized authenticated transport. Never retries writes against Firebase. */
public final class D1Client {
    private D1Client(){}

    /**
     * Per-resource D1 routing.
     *
     * Marking a collection as "D1" is not enough: a post whose likes/comments were NOT imported
     * would answer from an empty D1 row and its real interactions would appear to vanish. So the
     * worker is the source of truth — it only serves a resource once that resource's import has
     * been verified, and answers 503 "not verified" for everything else. A 503 marks the resource
     * as firestore-backed for this process, so the app keeps using Firestore for it.
     */
    private static final Set<String> D1_UNAVAILABLE = Collections.synchronizedSet(new HashSet<>());

    /**
     * Collections the D1 data plane serves.
     *
     * <p>This was {@code posts} and {@code reels} only. Everything else - comments, chats,
     * messages, friend requests, notifications - therefore took the Firestore branch, and with
     * Firestore switched off those writes failed. A new account could create nothing: no like,
     * no comment, no message, no friend request. The list is the worker's own allowlist
     * (cloudflare/app-documents.js), so the app only asks for what the server serves.
     */
    private static final Set<String> D1_COLLECTIONS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "users", "posts", "reels", "stories", "chats", "messages",
                    "friends", "friend_requests", "follows", "groups", "pages",
                    "notifications", "official_broadcasts", "lives", "pokes",
                    "live_chat", "comments", "subscriptions",
                    // Kept in step with the worker's allow-list: a collection it refuses is a
                    // write the user made that never lands.
                    "reports", "payments", "gifts", "feedback", "events")));

    /** True when this collection is eligible for D1 at all (build flag + supported collection). */
    public static boolean enabled(String collection){
        return BuildConfig.D1_INTERACTIONS_ENABLED
                && collection != null
                && D1_COLLECTIONS.contains(collection);
    }

    /** True when this specific document should be served by D1 (eligible and not rejected yet). */
    public static boolean routed(String collection,String id){
        return enabled(collection) && (id == null || !D1_UNAVAILABLE.contains(key(collection,id)));
    }

    /** Worker said this resource was never migrated: keep it on Firestore for this process. */
    public static void markUnavailable(String collection,String id){
        if(collection==null||id==null)return;
        D1_UNAVAILABLE.add(key(collection,id));
    }

    public static boolean isUnavailable(String collection,String id){
        return id!=null&&D1_UNAVAILABLE.contains(key(collection,id));
    }

    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256));
    private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<String,List<Runnable>> OBSERVERS=new HashMap<>();
    public static String key(String collection,String id){return collection+"/"+id;}
    public static Runnable observe(String resource,Runnable callback){
        OBSERVERS.computeIfAbsent(resource,k->new ArrayList<>()).add(callback);
        return ()->{List<Runnable> list=OBSERVERS.get(resource);if(list!=null){list.remove(callback);if(list.isEmpty())OBSERVERS.remove(resource);}};
    }
    public static void changed(String resource){for(Runnable r:new ArrayList<>(OBSERVERS.getOrDefault(resource,Collections.emptyList())))r.run();}
    public static JSONObject object(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]==null?JSONObject.NULL:values[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    public static Task<JSONObject> request(String collection,String id,String kind,String method,JSONObject body,String query){
        TaskCompletionSource<JSONObject> result=new TaskCompletionSource<>();
        if(!enabled(collection)){result.setException(new IOException("D1 route is disabled"));return result.getTask();}
        FirebaseUser user=Fb.auth().getCurrentUser();final String actor=Fb.uid();
        if(!enabled(collection)||user==null||actor==null){result.setException(new IOException("D1 route is disabled or not signed in"));return result.getTask();}
        try{IO.execute(()->{
            try{
                if(!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                // Cached: this path runs on every like, comment and reaction, and the SDK round
                // trip through the main thread was pure latency on each one.
                String token=AuthToken.await(user,15_000L);
                if(token==null||!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                URL url=new URL("https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/engagement/"+Uri.encode(collection)+"/"+Uri.encode(id)+"/"+kind+(query==null?"":query));
                JSONObject data=D1HttpTransport.send(url,method,token,body);MAIN.post(()->{if(Objects.equals(actor,Fb.uid()))result.trySetResult(data);else result.trySetException(new IOException("Account changed"));});
            }catch(Exception error){
                // A 503 used to send this document back to Firestore for the rest of the process.
                // That is no longer a fallback: Firestore is switched off, so the write that
                // followed failed too and the user lost the action twice over. A refusal is now
                // reported as a refusal, and the client keeps retrying D1.
                MAIN.post(()->result.trySetException(new IOException("D1 unavailable. Retry.")));
            }
        });}catch(RejectedExecutionException error){result.setException(new IOException("Too many pending requests. Try again."));}
        return result.getTask();
    }
    public static List<Map<String,Object>> maps(JSONArray rows)throws JSONException{
        List<Map<String,Object>> list=new ArrayList<>();if(rows!=null)for(int i=0;i<rows.length();i++)list.add(map(rows.getJSONObject(i)));return list;
    }
    public static Map<String,Object> map(JSONObject row)throws JSONException{
        Map<String,Object> result=new HashMap<>();Iterator<String> keys=row.keys();while(keys.hasNext()){String k=keys.next();Object v=row.get(k);
            if(v==JSONObject.NULL)v=null;else if(v instanceof JSONObject)v=map((JSONObject)v);else if(v instanceof JSONArray){List<Object> values=new ArrayList<>();JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){Object x=a.get(i);values.add(x instanceof JSONObject?map((JSONObject)x):x==JSONObject.NULL?null:x);}v=values;}result.put(k,v);}return result;
    }
}

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

    /** True when this collection is eligible for D1 at all (build flag + supported collection). */
    public static boolean enabled(String collection){
        return BuildConfig.D1_INTERACTIONS_ENABLED && ("posts".equals(collection)||"reels".equals(collection));
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
                String token=Tasks.await(user.getIdToken(false),15,TimeUnit.SECONDS).getToken();
                if(token==null||!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                URL url=new URL("https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/engagement/"+Uri.encode(collection)+"/"+Uri.encode(id)+"/"+kind+(query==null?"":query));
                JSONObject data=D1HttpTransport.send(url,method,token,body);MAIN.post(()->{if(Objects.equals(actor,Fb.uid()))result.trySetResult(data);else result.trySetException(new IOException("Account changed"));});
            }catch(Exception error){
                // "Resource migration not verified" (503) means this document's interactions were
                // never imported into D1. Route it back to Firestore for the rest of this process
                // instead of showing the user an empty counter, and let callers fall back cleanly.
                String message = error.getMessage() == null ? "" : error.getMessage();
                if (message.contains("not verified") || message.contains("503")) markUnavailable(collection, id);
                MAIN.post(()->result.trySetException(new IOException("D1 unavailable. Retry; nothing was written to Firebase.")));
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

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
    public static boolean enabled(String collection){return BuildConfig.D1_INTERACTIONS_ENABLED && ("posts".equals(collection)||"reels".equals(collection));}
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(8,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256));
    // An opened comment thread should not queue behind feed reaction refreshes.
    private static final ThreadPoolExecutor COMMENT_READS=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private static final ThreadPoolExecutor COMMENT_WRITES=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private static final ThreadPoolExecutor INTERACTIVE=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24));
    private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Map<String,List<Runnable>> OBSERVERS=new HashMap<>();
    private static final EngagementReadCoalescer READS=new EngagementReadCoalescer();
    public static String key(String collection,String id){return collection+"/"+id;}
    public static Runnable observe(String resource,Runnable callback){
        OBSERVERS.computeIfAbsent(resource,k->new ArrayList<>()).add(callback);
        return ()->{List<Runnable> list=OBSERVERS.get(resource);if(list!=null){list.remove(callback);if(list.isEmpty())OBSERVERS.remove(resource);}};
    }
    public static void changed(String resource){for(Runnable r:new ArrayList<>(OBSERVERS.getOrDefault(resource,Collections.emptyList())))r.run();}
    public static JSONObject object(Object... values){JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put((String)values[i],values[i+1]==null?JSONObject.NULL:values[i+1]);}catch(JSONException e){throw new IllegalArgumentException(e);}return o;}
    public static Task<JSONObject> request(String collection,String id,String kind,String method,JSONObject body,String query){
        return request(collection,id,kind,method,body,query,false);
    }
    public static Task<JSONObject> requestInteractive(String collection,String id,String kind,String method,JSONObject body){
        boolean reactionWrite="reactions".equals(kind) && "PUT".equals(method);
        String query=reactionWrite
                ? (body!=null && body.optBoolean("notify",false) ? "?response=reaction&notify=1" : "?response=reaction") : null;
        // `notify` is a client-side hint for the query string, not part of the
        // reactions API body (which accepts only `type`). Sending it in JSON
        // made the Worker reject every like/reaction PUT as invalid.
        JSONObject payload=reactionWrite && body!=null && body.has("notify") ? withoutNotify(body) : body;
        return request(collection,id,kind,method,payload,query,true);
    }
    static JSONObject withoutNotify(JSONObject body){
        JSONObject copy=new JSONObject();
        try{
            java.util.Iterator<String> keys=body.keys();
            while(keys.hasNext()){
                String key=keys.next();
                if(!"notify".equals(key))copy.put(key,body.get(key));
            }
            return copy;
        }catch(JSONException error){throw new IllegalArgumentException("Invalid interactive request body",error);}
    }
    private static final ProfileReadFlights<List<String>,String> READ_OPERATIONS=new ProfileReadFlights<>();
    static void invalidateResourceReads(ProfileReadFlights<List<String>,String> reads,String collection,String id) {
        // A mutation can affect every engagement view of this resource, including
        // other actors/lanes, but not an unrelated post's in-flight operation.
        if(collection==null || id==null){reads.invalidateAll();return;}
        reads.invalidateMatching(key->key.size()<3 || (collection.equals(key.get(1)) && id.equals(key.get(2))));
    }
    private static Task<JSONObject> request(String collection,String id,String kind,String method,JSONObject body,String query,boolean interactive){
        // Feed cards need only the viewer's reaction state and aggregate
        // totals. A full engagement summary also scans comment history and is
        // fetched only by an explicit comments/detail flow.
        if("GET".equals(method) && "reactions".equals(kind) && query==null)query="?response=reaction";
        if(!"GET".equals(method) || body!=null){
            // A read started before or during this resource's write cannot satisfy a later read.
            invalidateResourceReads(READ_OPERATIONS,collection,id);
            Task<JSONObject> write=executeRequest(collection,id,kind,method,body,query,interactive);
            write.addOnCompleteListener(Runnable::run,ignored->invalidateResourceReads(READ_OPERATIONS,collection,id));
            return write;
        }
        final String actor=Fb.uid();
        // Share before queueing/token acquisition, not only while HTTP overlaps.
        // Interactive recovery reads retain their own priority/freshness lane.
        List<String> key=Arrays.asList(actor,collection,id,kind,query,interactive?"interactive":"normal");
        Task<String> shared=READ_OPERATIONS.read(key,()->executeRequest(collection,id,kind,method,body,query,interactive)
                .continueWith(Runnable::run,completed->completed.getResult().toString()));
        return shared.continueWith(Runnable::run,completed->{
            if(!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
            // Never share mutable JSON instances between consumers.
            return new JSONObject(completed.getResult());
        });
    }
    private static Task<JSONObject> executeRequest(String collection,String id,String kind,String method,JSONObject body,String query,boolean interactive){
        TaskCompletionSource<JSONObject> result=new TaskCompletionSource<>();
        if(!enabled(collection)){result.setException(new IOException("D1 route is disabled"));return result.getTask();}
        FirebaseUser user=Fb.auth().getCurrentUser();final String actor=Fb.uid();
        if(!enabled(collection)||user==null||actor==null){result.setException(new IOException("D1 route is disabled or not signed in"));return result.getTask();}
        ThreadPoolExecutor pool=interactive ? INTERACTIVE : "comments".equals(kind)
                ? ("GET".equals(method) ? COMMENT_READS : COMMENT_WRITES) : IO;
        try{pool.execute(()->{
            try{
                if(!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                String token=Tasks.await(user.getIdToken(false),15,TimeUnit.SECONDS).getToken();
                if(token==null||!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                URL url=new URL(BuildConfig.CLOUDFLARE_API_URL+"/v1/engagement/"+Uri.encode(collection)+"/"+Uri.encode(id)+"/"+kind+(query==null?"":query));
                String response=READS.execute("GET".equals(method) && body==null,actor,url.toString(),
                        ()->sendWithRefresh(url,method,token,body,user,actor).toString());
                // Each consumer owns its JSON; sharing a mutable result leaks UI edits.
                final JSONObject data=new JSONObject(response);
                MAIN.post(()->{if(Objects.equals(actor,Fb.uid()))result.trySetResult(data);else result.trySetException(new IOException("Account changed"));});
            }catch(Exception error){MAIN.post(()->result.trySetException(new IOException("D1 unavailable. Retry; nothing was written to Firebase.",error)));}
        });}catch(RejectedExecutionException error){result.setException(new IOException("Too many pending requests. Try again."));}
        return result.getTask();
    }
    private static JSONObject sendWithRefresh(URL url,String method,String token,JSONObject body,FirebaseUser user,String actor)throws Exception {
        if(!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
        try { return D1HttpTransport.send(url,method,token,body); }
        catch(D1HttpTransport.HttpStatusException failure) {
            if(failure.status!=401 || !Objects.equals(actor,Fb.uid()))throw failure;
            String refreshed=Tasks.await(user.getIdToken(true),15,TimeUnit.SECONDS).getToken();
            if(refreshed==null || !Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
            return D1HttpTransport.send(url,method,refreshed,body);
        }
    }
    public static boolean isRetryableReactionFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof D1HttpTransport.HttpStatusException) {
                int status=((D1HttpTransport.HttpStatusException)cause).status;
                return status==408 || status==429 || status>=500;
            }
            if(cause instanceof java.net.UnknownHostException || cause instanceof java.net.SocketException
                    || cause instanceof java.io.InterruptedIOException || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.json.JSONException)return true;
        }
        return false;
    }
    public static boolean isAccessDenied(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof D1HttpTransport.HttpStatusException) {
            int status=((D1HttpTransport.HttpStatusException)cause).status;
            return status==401 || status==403 || status==404;
        }
        return false;
    }
    public static List<Map<String,Object>> maps(JSONArray rows)throws JSONException{
        List<Map<String,Object>> list=new ArrayList<>();if(rows!=null)for(int i=0;i<rows.length();i++)list.add(map(rows.getJSONObject(i)));return list;
    }
    public static Map<String,Object> map(JSONObject row)throws JSONException{
        Map<String,Object> result=new HashMap<>();Iterator<String> keys=row.keys();while(keys.hasNext()){String k=keys.next();Object v=row.get(k);
            if(v==JSONObject.NULL)v=null;else if(v instanceof JSONObject)v=map((JSONObject)v);else if(v instanceof JSONArray){List<Object> values=new ArrayList<>();JSONArray a=(JSONArray)v;for(int i=0;i<a.length();i++){Object x=a.get(i);values.add(x instanceof JSONObject?map((JSONObject)x):x==JSONObject.NULL?null:x);}v=values;}result.put(k,v);}return result;
    }
}

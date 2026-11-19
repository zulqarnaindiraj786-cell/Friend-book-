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
    private static final ThreadPoolExecutor INTERACTIVE=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(24));
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
        return request(collection,id,kind,method,body,query,false);
    }
    public static Task<JSONObject> requestInteractive(String collection,String id,String kind,String method,JSONObject body){
        return request(collection,id,kind,method,body,null,true);
    }
    private static Task<JSONObject> request(String collection,String id,String kind,String method,JSONObject body,String query,boolean interactive){
        TaskCompletionSource<JSONObject> result=new TaskCompletionSource<>();
        if(!enabled(collection)){result.setException(new IOException("D1 route is disabled"));return result.getTask();}
        FirebaseUser user=Fb.auth().getCurrentUser();final String actor=Fb.uid();
        if(!enabled(collection)||user==null||actor==null){result.setException(new IOException("D1 route is disabled or not signed in"));return result.getTask();}
        ThreadPoolExecutor pool=interactive ? INTERACTIVE : "comments".equals(kind) && "GET".equals(method) ? COMMENT_READS : IO;
        try{pool.execute(()->{
            try{
                if(!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                String token=Tasks.await(user.getIdToken(false),15,TimeUnit.SECONDS).getToken();
                if(token==null||!Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                URL url=new URL("https://rough-firefly-dd7b.zulqarnaindiraj786.workers.dev/v1/engagement/"+Uri.encode(collection)+"/"+Uri.encode(id)+"/"+kind+(query==null?"":query));
                JSONObject response;
                try { response=D1HttpTransport.send(url,method,token,body); }
                catch(D1HttpTransport.HttpStatusException failure) {
                    if(failure.status!=401 || !Objects.equals(actor,Fb.uid()))throw failure;
                    String refreshed=Tasks.await(user.getIdToken(true),15,TimeUnit.SECONDS).getToken();
                    if(refreshed==null || !Objects.equals(actor,Fb.uid()))throw new IOException("Account changed");
                    response=D1HttpTransport.send(url,method,refreshed,body);
                }
                final JSONObject data=response;
                MAIN.post(()->{if(Objects.equals(actor,Fb.uid()))result.trySetResult(data);else result.trySetException(new IOException("Account changed"));});
            }catch(Exception error){MAIN.post(()->result.trySetException(new IOException("D1 unavailable. Retry; nothing was written to Firebase.")));}
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

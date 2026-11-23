package com.friendbook.social.post;
import android.app.*;
import android.content.*;
import android.widget.*;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import org.json.*;
import java.util.*;

/** Account-scoped D1 state. No Firestore fallback or unbounded background polling. */
public final class D1Reactions {
 private D1Reactions(){}
 private static String actor;
 private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
 private static final android.util.LruCache<String,JSONObject> CACHE=new android.util.LruCache<>(512);
 private static final android.util.LruCache<String,Long> FETCHED_AT=new android.util.LruCache<>(512);
 private static final long REFRESH_MS=8000;
 private static final android.util.LruCache<String,java.lang.ref.WeakReference<Models.Post>> POSTS=new android.util.LruCache<>(512);
 private static final Set<String> LOADING=new HashSet<>(),FAILED=new HashSet<>(),WRITING=new HashSet<>();
 private static final Set<String> UNCONFIRMED=new HashSet<>();
 private static final Map<String,PendingReaction> MUTATIONS=new HashMap<>();
 private static final Map<String,Integer> GENERATION=new HashMap<>();
 private static final Map<String,List<Runnable>> WAITERS=new HashMap<>();
 private static void scope(){if(!Objects.equals(actor,Fb.uid())){actor=Fb.uid();CACHE.evictAll();FETCHED_AT.evictAll();POSTS.evictAll();LOADING.clear();FAILED.clear();WRITING.clear();UNCONFIRMED.clear();MUTATIONS.clear();GENERATION.clear();WAITERS.clear();}}
 public static String mine(JSONObject summary){JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("mine")>0)return r.optString("type");}return null;}
 public static int total(JSONObject summary){int n=0;JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null)n+=r.optInt("count");}return n;}
 public static JSONObject cached(String collection,String id){scope();return CACHE.get(D1Client.key(collection,id));}
 public static String mine(String collection,String id){JSONObject data=cached(collection,id);return data==null?null:mine(data);}
 public static void invalidate(String collection,String id){scope();String key=D1Client.key(collection,id);GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);FETCHED_AT.remove(key);FAILED.remove(key);java.lang.ref.WeakReference<Models.Post> reference=POSTS.get(key);Models.Post post=reference==null?null:reference.get();if(post!=null)fetch(null,collection,id,false,()->hydrate(post));}
 private static void remember(Context context,String collection,String id,JSONObject data){
  String key=D1Client.key(collection,id);JSONObject previous=CACHE.put(key,data);
  FETCHED_AT.put(key,android.os.SystemClock.elapsedRealtime());
  if(previous!=null && previous.toString().equals(data.toString()))return;
  List<String> types=new ArrayList<>();JSONArray a=data.optJSONArray("types");
  if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("count")>0)types.add(r.optString("type"));}
  if("posts".equals(collection) && !data.optBoolean("reactionStateUnknown")){FastCache.setReactionTypes(id,types);FastCache.setReactionState(context,id,mine(data),total(data),true);}
 }
 private static void fetch(Context context,String collection,String id,Runnable ready){fetch(context,collection,id,true,ready);}
 private static void fetch(Context context,String collection,String id,boolean renderCached,Runnable ready){
  scope();String key=D1Client.key(collection,id);if(CACHE.get(key)!=null){
   if(renderCached)ready.run();Long fetched=FETCHED_AT.get(key);if(fetched!=null && android.os.SystemClock.elapsedRealtime()-fetched<REFRESH_MS)return;
  }if(FAILED.contains(key)||WRITING.contains(key))return;
  WAITERS.computeIfAbsent(key,k->new ArrayList<>()).add(ready);
  if(LOADING.contains(key))return;
  String uid=actor;int generation=GENERATION.getOrDefault(key,0);LOADING.add(key);D1Client.request(collection,id,"reactions","GET",null,null).addOnSuccessListener(data->{
   if(!Objects.equals(uid,Fb.uid()))return;LOADING.remove(key);
   if(WRITING.contains(key)){WAITERS.remove(key);return;}
   if(generation!=GENERATION.getOrDefault(key,0)){List<Runnable> pending=WAITERS.remove(key);if(pending!=null)for(Runnable callback:pending)fetch(context,collection,id,false,callback);return;}
   UNCONFIRMED.remove(key);
   remember(context,collection,id,data);List<Runnable> pending=WAITERS.remove(key);if(pending!=null)for(Runnable callback:pending)callback.run();
  }).addOnFailureListener(e->{if(!Objects.equals(uid,Fb.uid()))return;LOADING.remove(key);WAITERS.remove(key);FAILED.add(key);
   new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{if(Objects.equals(uid,Fb.uid()))FAILED.remove(key);},10000);});
 }
 public static void seedFeed(Context context,com.friendbook.social.d1.documents.DocumentSnapshot doc,long requestedAt){
  scope();String key=D1Client.key("posts",doc.getId());Long last=FETCHED_AT.get(key);
  if(WRITING.contains(key) || UNCONFIRMED.contains(key) || ((doc.getMetadata().isFromCache() && CACHE.get(key)!=null) || (last!=null && last>=requestedAt)))return;
  String raw=doc.getString("__feedEngagement");if(raw==null)return;
  try{JSONObject summary=new JSONObject(raw);if(summary.optJSONArray("types")==null)return;
   GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);
   remember(context,"posts",doc.getId(),summary);
   if(doc.getMetadata().isFromCache())FETCHED_AT.remove(key);
  }catch(JSONException ignored){}
 }
 public static void refreshDetailTotals(String id){fetch(null,"posts",id,false,()->{});}
 public static void acceptDetailTotals(String id,JSONArray types,long requestedAt){
  scope();String key=D1Client.key("posts",id);Long last=FETCHED_AT.get(key);
  if(types==null || WRITING.contains(key) || UNCONFIRMED.contains(key) || (last!=null && last>requestedAt))return;
  try{
   JSONObject old=CACHE.get(key);
   JSONObject next=old==null?new JSONObject():new JSONObject(old.toString());
   next.remove("reactionStateUnknown");next.put("types",types);
   remember(null,"posts",id,next);
  }catch(JSONException ignored){}
 }
 public static void loadPost(Context context,Models.Post post,Runnable render){
  if(post==null || post.id==null)return;
  hydrate(post);
  fetch(context.getApplicationContext(),"posts",post.id,()->{hydrate(post);render.run();});
 }
 public static void hydrate(Models.Post post){
  scope();POSTS.put(D1Client.key("posts",post.id),new java.lang.ref.WeakReference<>(post));
  JSONObject summary=cached("posts",post.id);if(summary!=null){post.comments=summary.optInt("comments",post.comments);
   if(!summary.optBoolean("reactionStateUnknown")){
    post.likes=total(summary);post.reactionsMap.clear();
    String type=mine(summary);if(type!=null){Map<String,Object> row=new HashMap<>();row.put("type",type);post.reactionsMap.put(Fb.uid(),row);}
   }
   if(summary.has("preview")){post.inlineComments.clear();try{for(Map<String,Object> row:D1Client.maps(summary.optJSONArray("preview")))post.inlineComments.add(Models.Comment.fromMap(Format.str(row,"id"),row));}catch(JSONException ignored){}}
  }
 }
 /** Cache-only paint: rendering a save/share/metadata change must not start HTTP. */
 public static void hydrateReel(Models.Reel reel){
  String collection=reel.isFromPost?"posts":"reels";JSONObject summary=cached(collection,reel.id);
  if(summary!=null){reel.likes=total(summary);reel.comments=summary.optInt("comments",reel.comments);reel.reactions.clear();reel.likedBy.clear();String type=mine(summary);if(type!=null){reel.reactions.put(Fb.uid(),type);reel.likedBy.add(Fb.uid());}}
 }
 public static void reel(Context context,Models.Reel reel,Runnable render){
  hydrateReel(reel);
  String collection=reel.isFromPost?"posts":"reels";
  fetch(context.getApplicationContext(),collection,reel.id,false,render);
 }
 public static void apply(Context context,String collection,String id,String requested,boolean onlyAdd,Runnable render){
  apply(context,collection,id,requested,onlyAdd,false,render);
 }
 static String nextReaction(String old,String requested,boolean toggleAny){
  return ReactionWriteQueue.nextReaction(old,requested,toggleAny);
 }
 public static void tapLike(Context context,String collection,String id,Runnable render){
  apply(context,collection,id,"like",false,true,render);
 }
 private static void apply(Context context,String collection,String id,String requested,boolean onlyAdd,boolean toggleAny,Runnable render){
  scope();String uid=actor;if(uid==null)return;
  String key=D1Client.key(collection,id);
  PendingReaction running=MUTATIONS.get(key);
  if(running!=null){running.add(requested,onlyAdd,toggleAny,render);return;}
  FAILED.remove(key);
  GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);
  PendingReaction mutation=new PendingReaction(context.getApplicationContext(),collection,id,key,uid);
  MUTATIONS.put(key,mutation);WRITING.add(key);
  mutation.add(requested,onlyAdd,toggleAny,render);
  JSONObject initial=CACHE.get(key);
  if(initial!=null && !UNCONFIRMED.contains(key) && !initial.optBoolean("reactionStateUnknown"))mutation.start(initial);
  else mutation.loadInitial(0);
 }

 private static final class PendingReaction {
  final Context context;
  final String collection,id,key,uid;
  final ReactionWriteQueue queue=new ReactionWriteQueue();
  JSONObject confirmed;
  String initialType, owner="";
  Runnable render;
  PendingReaction(Context context,String collection,String id,String key,String uid){
   this.context=context;this.collection=collection;this.id=id;this.key=key;this.uid=uid;
  }
  boolean current(){return Objects.equals(uid,Fb.uid()) && MUTATIONS.get(key)==this;}
  void add(String type,boolean onlyAdd,boolean toggleAny,Runnable callback){
   render=callback;
   queue.add(type,onlyAdd,toggleAny);
   if(confirmed!=null)publishOptimistic();
  }
  void loadInitial(int attempt){
   D1Client.requestInteractive(collection,id,"reactions","GET",null).addOnSuccessListener(data->{
    if(!current())return;
    if(data.optJSONArray("types")==null){if(attempt==0)MAIN.postDelayed(()->{if(current())loadInitial(1);},700L);else finish("Couldn't load reaction. Try again.");}
    else start(data);
   }).addOnFailureListener(error->{
    if(!current())return;
    if(attempt==0 && D1Client.isRetryableReactionFailure(error))MAIN.postDelayed(()->{if(current())loadInitial(1);},700L);
    else finish("Couldn't load reaction. Try again.");
   });
  }
  void start(JSONObject initial){
   UNCONFIRMED.remove(key);
   confirmed=initial;initialType=mine(initial);owner=initial.optString("owner");
   queue.initialize(initialType);publishOptimistic();sendNext();
  }
  void publishOptimistic(){
   String old=mine(confirmed), next=queue.desired();
   if(Objects.equals(old,next)){publish(confirmed);return;}
   try{
    JSONObject optimistic=new JSONObject(confirmed.toString());
    JSONArray adjusted=new JSONArray();
    for(String type:Reactions.TYPES){
     int count=0;JSONArray rows=confirmed.optJSONArray("types");
     if(rows!=null)for(int i=0;i<rows.length();i++){
      JSONObject row=rows.optJSONObject(i);if(row!=null&&type.equals(row.optString("type")))count=row.optInt("count");
     }
     if(type.equals(old))count--;
     if(type.equals(next))count++;
     if(count>0)adjusted.put(D1Client.object("type",type,"count",count,"mine",type.equals(next)?1:0));
    }
    optimistic.put("types",adjusted);publish(optimistic);
   }catch(JSONException ignored){}
  }
  void publish(JSONObject summary){remember(context,collection,id,summary);if(render!=null)render.run();}
  void sendNext(){
   ReactionWriteQueue.Target target=queue.begin();
   if(target==null){finish(null);return;}
   sendTarget(target,0);
  }
  void sendTarget(ReactionWriteQueue.Target target,int attempt){
   D1Client.requestInteractive(collection,id,"reactions","PUT",D1Client.object("type",target.type))
    .addOnSuccessListener(data->{
     if(!current())return;
     JSONArray types=data.optJSONArray("types");
     if(types==null){recover(target,attempt);return;}
     if(data.optBoolean("reactionOnly")){
      try{JSONObject merged=new JSONObject(confirmed.toString());merged.put("types",types);confirmed=merged;}
      catch(JSONException invalid){recover(target,attempt);return;}
     }else confirmed=data;
     completed(target,data);
    }).addOnFailureListener(e->{if(current()){
     if(D1Client.isRetryableReactionFailure(e))recover(target,attempt);
     else failed(target);
    }});
  }
  void completed(ReactionWriteQueue.Target target,JSONObject data){
   owner=data.optString("owner",owner);
   queue.complete(target);
   if(queue.hasPending())sendNext();else finish(null);
  }
  void recover(ReactionWriteQueue.Target target,int attempt){
   // Replay the same absolute state, never the tap. Keep later taps serialized.
   if(attempt==0){MAIN.postDelayed(()->{if(current())sendTarget(target,1);},700L);return;}
   D1Client.requestInteractive(collection,id,"reactions","GET",null).addOnSuccessListener(data->{
    if(!current())return;
    if(data.optJSONArray("types")!=null){
     confirmed=data;
     if(Objects.equals(target.type,mine(data))){completed(target,data);return;}
    }
    failed(target);
   }).addOnFailureListener(e->{if(current())failed(target);});
  }
  void failed(ReactionWriteQueue.Target target){
   queue.complete(target);
   if(queue.hasPending()){sendNext();return;}
   UNCONFIRMED.add(key);
   finish("Reaction couldn't be saved. Reconnect and try again.");
  }
  void finish(String error){
   if(!current())return;
   MUTATIONS.remove(key);WRITING.remove(key);
   if(confirmed!=null){publish(confirmed);D1Client.changed(key);}
   if(error!=null){FETCHED_AT.remove(key);Ui.toast(context,error);return;}
   if(confirmed!=null && initialType==null && mine(confirmed)!=null && !owner.isEmpty() && !owner.equals(uid))
    Notify.send(owner,"posts".equals(collection)?"like":"reel_like",Fb.myName()+" liked your "+("posts".equals(collection)?"post":"reel"),id);
  }
 }
 static void historicalNotice(Activity activity,TextView note,LinearLayout filter,LinearLayout rows,int historical,boolean identified){
  note.setVisibility(historical>0?android.view.View.VISIBLE:android.view.View.GONE);
  filter.setVisibility(historical>0&&!identified?android.view.View.GONE:android.view.View.VISIBLE);
  if(historical<=0)return;
  note.setText(historical+" historical reactions; individual details unavailable.");
  if(!identified){
   rows.removeAllViews();
   TextView message=Ui.tv(activity,"No actor identities were recorded for these historical reactions.",14,Ui.sub(activity));
   Ui.pad(message,8,20,8,20);rows.addView(message);
  }
 }
 public static void show(Activity activity,String collection,String id,String commentId){
  LinearLayout root=Ui.col(activity),rows=Ui.col(activity);Ui.pad(root,14,12,14,12);ScrollView scroll=new ScrollView(activity);scroll.addView(rows);root.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(activity,360)));
  LinearLayout filter=Ui.row(activity);root.addView(filter,0);TextView history=Ui.tv(activity,"",13,Ui.sub(activity));history.setVisibility(android.view.View.GONE);root.addView(history,1);List<Reactions.ReactorEntry> entries=new ArrayList<>();
  TextView more=Ui.tvBold(activity,"Load reactions",14,Ui.BLUE);root.addView(more);
  AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("People who reacted").setView(root).setNegativeButton("Close",null).create();
  final String[] after={null};final boolean[] busy={false},done={false};final String uid=Fb.uid();final Set<String> seen=new HashSet<>();
  Runnable load=()->{if(busy[0]||done[0]||!dialog.isShowing())return;busy[0]=true;more.setText("Loading…");
   String query="?"+(commentId==null?"":"commentId="+android.net.Uri.encode(commentId)+"&")+(after[0]==null?"":"after="+android.net.Uri.encode(after[0]));
   D1Client.request(collection,id,"reactors","GET",null,query).addOnSuccessListener(data->{if(!dialog.isShowing()||!Objects.equals(uid,Fb.uid()))return;busy[0]=false;
    JSONArray list=data.optJSONArray("reactors");if(list!=null)for(int i=0;i<list.length();i++){JSONObject r=list.optJSONObject(i);if(r==null||!seen.add(r.optString("uid")))continue;
     Reactions.ReactorEntry entry=new Reactions.ReactorEntry();entry.uid=r.optString("uid");entry.type=r.optString("type");entry.createdAt=r.optLong("createdAt");
     if(entry.uid.equals(uid)){entry.name=Fb.myName();entry.photo=Fb.myPhoto();entry.tickLevel=Fb.me!=null?Fb.me.tickLevel:"none";}
     entries.add(entry);
    }Map<String,Integer> counts=new LinkedHashMap<>();for(String type:Reactions.TYPES)counts.put(type,0);for(Reactions.ReactorEntry entry:entries)counts.put(entry.type,counts.getOrDefault(entry.type,0)+1);
    Reactions.renderReactorView(activity,dialog,filter,rows,entries,counts,"all");historicalNotice(activity,history,filter,rows,Math.max(0,data.optInt("historicalUnattributed")),!entries.isEmpty());after[0]=data.isNull("next")?null:data.optString("next",null);done[0]=after[0]==null;more.setText(done[0]?"All loaded":"Load 30 more reactions");
   }).addOnFailureListener(e->{busy[0]=false;if(dialog.isShowing()&&Objects.equals(uid,Fb.uid()))more.setText("Retry reactions");});
  };more.setOnClickListener(v->load.run());dialog.show();load.run();
 }
}

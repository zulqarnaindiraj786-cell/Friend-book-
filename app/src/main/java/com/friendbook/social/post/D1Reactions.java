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
 private static final android.util.LruCache<String,Long> REACTION_FETCHED_AT=new android.util.LruCache<>(512);
 // Reactions/comments summaries are read-only and cached by actor/resource.
 // Keep them warm longer than the document feed; local mutations invalidate
 // immediately, while remote totals may lag by at most five minutes.
 private static final long REFRESH_MS=300_000L;
 private static final android.util.LruCache<String,java.lang.ref.WeakReference<Models.Post>> POSTS=new android.util.LruCache<>(512);
 private static final Set<String> LOADING=new HashSet<>(),FAILED=new HashSet<>(),WRITING=new HashSet<>();
 private static final Set<String> UNCONFIRMED=new HashSet<>();
 private static final Map<String,PendingReaction> MUTATIONS=new HashMap<>();
 private static final Map<String,Integer> GENERATION=new HashMap<>();
 private static final Map<String,List<Runnable>> WAITERS=new HashMap<>();
 private static final Map<String,Object> READ_TOKENS=new HashMap<>();
 private static void scope(){if(!Objects.equals(actor,Fb.uid())){actor=Fb.uid();CACHE.evictAll();FETCHED_AT.evictAll();REACTION_FETCHED_AT.evictAll();POSTS.evictAll();LOADING.clear();FAILED.clear();WRITING.clear();UNCONFIRMED.clear();MUTATIONS.clear();GENERATION.clear();WAITERS.clear();READ_TOKENS.clear();}}
 public static String mine(JSONObject summary){JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("mine")>0)return r.optString("type");}return null;}
 public static int total(JSONObject summary){int n=0;JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null)n+=r.optInt("count");}return n;}
 public static JSONObject cached(String collection,String id){scope();return CACHE.get(D1Client.key(collection,id));}
 public static String mine(String collection,String id){JSONObject data=cached(collection,id);return data==null?null:mine(data);}
 public static void invalidate(String collection,String id){scope();String key=D1Client.key(collection,id);GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);FETCHED_AT.remove(key);REACTION_FETCHED_AT.remove(key);FAILED.remove(key);java.lang.ref.WeakReference<Models.Post> reference=POSTS.get(key);Models.Post post=reference==null?null:reference.get();if(post!=null)fetch(null,collection,id,false,()->hydrate(post));}
 /** Snapshot only a fresh, idle summary before sending a new comment. */
 static final class CommentBaseline {
  final String actor,key;final JSONObject summary;final int generation;
  CommentBaseline(String actor,String key,JSONObject summary,int generation){this.actor=actor;this.key=key;this.summary=summary;this.generation=generation;}
 }
 static CommentBaseline commentBaseline(String collection,String id){
  scope();String key=D1Client.key(collection,id);JSONObject summary=CACHE.get(key);Long age=FETCHED_AT.get(key);
  if(actor==null || summary==null || age==null || android.os.SystemClock.elapsedRealtime()-age>=REFRESH_MS
      || LOADING.contains(key)||WRITING.contains(key)||UNCONFIRMED.contains(key))return null;
  return new CommentBaseline(actor,key,summary,GENERATION.getOrDefault(key,0));
 }
 /** Apply only a provably new, visible comment to the unchanged pre-send summary. */
 static boolean commentCreated(String collection,String id,String commentId,JSONObject ack,CommentBaseline before){
  scope();String key=D1Client.key(collection,id);Long age=FETCHED_AT.get(key);
  if(before==null || !Objects.equals(actor,before.actor) || !key.equals(before.key)
      || CACHE.get(key)!=before.summary || GENERATION.getOrDefault(key,0)!=before.generation
      || age==null || android.os.SystemClock.elapsedRealtime()-age>=REFRESH_MS
      || LOADING.contains(key)||WRITING.contains(key)||UNCONFIRMED.contains(key)
      || !Boolean.TRUE.equals(ack.opt("created")) || !Boolean.TRUE.equals(ack.opt("visibleToActor")))return false;
  JSONObject row=ack.optJSONObject("comment");
  if(row==null || !commentId.equals(row.optString("id")) || !actor.equals(row.optString("authorId")))return false;
  Object count=before.summary.opt("comments");JSONArray preview=before.summary.optJSONArray("preview");
  if(!(count instanceof Integer) || (Integer)count<0 || (Integer)count==Integer.MAX_VALUE
      || preview==null || preview.length()!=Math.min(2,(Integer)count))return false;
  try{
   List<JSONObject> rows=new ArrayList<>();rows.add(new JSONObject(row.toString()));
   for(int i=0;i<preview.length();i++){
    JSONObject old=preview.optJSONObject(i);
    if(old==null || old.optString("id").isEmpty() || commentId.equals(old.optString("id")))return false;
    rows.add(new JSONObject(old.toString()));
   }
   for(JSONObject item:rows)if(!item.isNull("createdAt") && !(item.opt("createdAt") instanceof Number))return false;
   rows.sort((a,b)->{int order=Long.compare(b.optLong("createdAt",0),a.optLong("createdAt",0));
    return order!=0?order:b.optString("id").compareTo(a.optString("id"));});
   JSONArray nextPreview=new JSONArray();for(int i=0;i<Math.min(2,rows.size());i++)nextPreview.put(rows.get(i));
   JSONObject updated=new JSONObject(before.summary.toString()).put("comments",(Integer)count+1).put("preview",nextPreview);
   GENERATION.put(key,before.generation+1);CACHE.put(key,updated); // Do not extend reaction freshness.
   java.lang.ref.WeakReference<Models.Post> reference=POSTS.get(key);Models.Post post=reference==null?null:reference.get();
   if(post!=null)hydrate(post);
   return true;
  }catch(JSONException malformed){return false;}
 }
 /** Use the authenticated comment ACK for count-neutral edits; do not GET a full post summary. */
 static void commentChanged(String collection,String id,JSONObject acknowledgement,String operation){
  scope();
  if("pin".equals(operation) && acknowledgement.has("pinned"))return;
  if(!("edit".equals(operation)||"react".equals(operation))){invalidate(collection,id);return;}
  JSONObject row=acknowledgement.optJSONObject("comment");
  if(row==null || row.optString("id").isEmpty()){invalidate(collection,id);return;}
  String key=D1Client.key(collection,id);
  // Keep the existing uncertain/concurrent post-reaction handling intact.
  if(WRITING.contains(key)||UNCONFIRMED.contains(key)){invalidate(collection,id);return;}
  GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);
  JSONObject current=CACHE.get(key);if(current==null)return;
  try{
   JSONObject updated=new JSONObject(current.toString());JSONArray preview=updated.optJSONArray("preview");
   if(preview!=null)for(int i=0;i<preview.length();i++){
    JSONObject previous=preview.optJSONObject(i);
    if(previous!=null && row.optString("id").equals(previous.optString("id")))preview.put(i,new JSONObject(row.toString()));
   }
   // A comment ACK does not renew the age of post reaction totals.
   CACHE.put(key,updated);
   java.lang.ref.WeakReference<Models.Post> reference=POSTS.get(key);Models.Post post=reference==null?null:reference.get();
   if(post!=null)hydrate(post);
  }catch(JSONException malformed){invalidate(collection,id);}
 }
 private static void remember(Context context,String collection,String id,JSONObject data){
  remember(context,collection,id,data,true);
 }
 private static void remember(Context context,String collection,String id,JSONObject data,boolean fullSummary){
  String key=D1Client.key(collection,id);JSONObject previous=CACHE.get(key);
  if(!fullSummary && previous!=null && data.optJSONArray("types")!=null)try{
   JSONObject merged=new JSONObject(previous.toString()).put("types",data.optJSONArray("types"));
   merged.remove("reactionStateUnknown");data=merged;
  }catch(JSONException ignored){}
  previous=CACHE.put(key,data);
  // A reactions-only response says nothing about the age (or existence) of
  // the comment count and preview in this shared summary.
  long now=android.os.SystemClock.elapsedRealtime();
  if(data.optJSONArray("types")!=null)REACTION_FETCHED_AT.put(key,now);
  if(fullSummary && data.has("comments") && data.optJSONArray("preview")!=null)FETCHED_AT.put(key,now);
  else if(previous==null)FETCHED_AT.remove(key);
  if(previous!=null && previous.toString().equals(data.toString()))return;
  List<String> types=new ArrayList<>();JSONArray a=data.optJSONArray("types");
  if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("count")>0)types.add(r.optString("type"));}
  if("posts".equals(collection) && !data.optBoolean("reactionStateUnknown")){FastCache.setReactionTypes(id,types);FastCache.setReactionState(context,id,mine(data),total(data),true);}
 }
 private static void fetch(Context context,String collection,String id,Runnable ready){fetch(context,collection,id,true,ready);}
 private static void fetch(Context context,String collection,String id,boolean renderCached,Runnable ready){
  scope();String key=D1Client.key(collection,id);if(CACHE.get(key)!=null){
   if(renderCached)ready.run();Long fetched=REACTION_FETCHED_AT.get(key);if(fetched!=null && android.os.SystemClock.elapsedRealtime()-fetched<REFRESH_MS)return;
  }if(FAILED.contains(key)||WRITING.contains(key))return;
  WAITERS.computeIfAbsent(key,k->new ArrayList<>()).add(ready);
  if(LOADING.contains(key))return;
  Object token=new Object();READ_TOKENS.put(key,token);LOADING.add(key);
  startFetch(context,collection,id,actor,key,token,1);
 }
 private static void readFailed(String uid,String key,Object token){
  LOADING.remove(key);WAITERS.remove(key);FAILED.add(key);
  MAIN.postDelayed(()->{
   if(Objects.equals(uid,Fb.uid()) && READ_TOKENS.get(key)==token){FAILED.remove(key);READ_TOKENS.remove(key);}
  },10000);
 }
 private static void startFetch(Context context,String collection,String id,String uid,String key,Object token,int attempt){
  if(!Objects.equals(uid,Fb.uid()) || READ_TOKENS.get(key)!=token)return;
  if(WRITING.contains(key)){LOADING.remove(key);WAITERS.remove(key);READ_TOKENS.remove(key);return;}
  int generation=GENERATION.getOrDefault(key,0);
  D1Client.request(collection,id,"reactions","GET",null,null).addOnSuccessListener(data->{
   if(!Objects.equals(uid,Fb.uid()) || READ_TOKENS.get(key)!=token)return;
   if(WRITING.contains(key)){LOADING.remove(key);WAITERS.remove(key);READ_TOKENS.remove(key);return;}
   if(generation!=GENERATION.getOrDefault(key,0)){
    // Hold the flight lock across the delay. Repeated invalidation may queue
    // demand but cannot start a recursive response->GET chain or parallel read.
    if(attempt>=3){readFailed(uid,key,token);return;}
    MAIN.postDelayed(()->startFetch(context,collection,id,uid,key,token,attempt+1),2000L);
    return;
   }
   LOADING.remove(key);READ_TOKENS.remove(key);UNCONFIRMED.remove(key);
   if(data.optBoolean("reactionOnly"))acceptReactionState(collection,id,data.optJSONArray("types"),android.os.SystemClock.elapsedRealtime());
   else remember(context,collection,id,data);
   List<Runnable> pending=WAITERS.remove(key);if(pending!=null)for(Runnable callback:pending)callback.run();
  }).addOnFailureListener(e->{
   if(Objects.equals(uid,Fb.uid()) && READ_TOKENS.get(key)==token)readFailed(uid,key,token);
  });
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
  acceptReactionState("posts",id,types,requestedAt);
 }
 private static void acceptReactionState(String collection,String id,JSONArray types,long requestedAt){
  scope();String key=D1Client.key(collection,id);Long last=FETCHED_AT.get(key);
  if(types==null || WRITING.contains(key) || UNCONFIRMED.contains(key) || (last!=null && last>requestedAt))return;
  try{
   JSONObject old=CACHE.get(key);
   // CACHE and FETCHED_AT have independent LRU eviction order. A timestamp
   // left after the full summary was evicted cannot validate a partial one.
   if(old==null)FETCHED_AT.remove(key);
   JSONObject next=old==null?new JSONObject():new JSONObject(old.toString());
   next.remove("reactionStateUnknown");next.put("types",types);
   // In-flight full reads started before this partial response must not
   // overwrite its reaction state; they will retry under the existing limit.
   GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);
   remember(null,collection,id,next,false);
   REACTION_FETCHED_AT.put(key,android.os.SystemClock.elapsedRealtime());
  }catch(JSONException ignored){}
 }
 public static void loadPost(Context context,Models.Post post,Runnable render){
  if(post==null || post.id==null)return;
  hydrate(post);
  fetch(context.getApplicationContext(),"posts",post.id,()->{hydrate(post);render.run();});
 }
 public static void hydrate(Models.Post post){
  scope();POSTS.put(D1Client.key("posts",post.id),new java.lang.ref.WeakReference<>(post));
  JSONObject summary=cached("posts",post.id);if(summary!=null){if(summary.has("comments"))post.comments=summary.optInt("comments",post.comments);
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
  if(summary!=null){reel.likes=total(summary);if(summary.has("comments"))reel.comments=summary.optInt("comments",reel.comments);reel.reactions.clear();reel.likedBy.clear();String type=mine(summary);if(type!=null){reel.reactions.put(Fb.uid(),type);reel.likedBy.add(Fb.uid());}}
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
  if(!InteractionCooldown.acquire(uid,"reaction/"+key))return;
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
  JSONObject confirmed, committedNotification;
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
  // Only a complete response, unchanged by another local operation while it
  // was in flight, can renew the shared comment-summary freshness.
  void acceptFullSummary(JSONObject data,int generation){
   if(generation!=GENERATION.getOrDefault(key,0)){FETCHED_AT.remove(key);REACTION_FETCHED_AT.remove(key);return;}
   if(data.optBoolean("reactionOnly")){
    acceptReactionState(collection,id,data.optJSONArray("types"),android.os.SystemClock.elapsedRealtime());
   }else if(data.opt("comments") instanceof Number && data.optJSONArray("preview")!=null){
    remember(context,collection,id,data);
   }else if(data.optJSONArray("types")!=null){
    acceptReactionState(collection,id,data.optJSONArray("types"),android.os.SystemClock.elapsedRealtime());
   }
  }
  void loadInitial(int attempt){
   final int generation=GENERATION.getOrDefault(key,0);
   D1Client.requestInteractive(collection,id,"reactions","GET",null).addOnSuccessListener(data->{
    if(!current())return;
    if(data.optJSONArray("types")==null){if(attempt==0)MAIN.postDelayed(()->{if(current())loadInitial(1);},700L);else finish("Couldn't load reaction. Try again.");}
    else {acceptFullSummary(data,generation);start(data);}
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
  // Optimistic and reaction-only updates do not renew comment count/preview.
  void publish(JSONObject summary){remember(context,collection,id,summary,false);if(render!=null)render.run();}
  void sendNext(){
   ReactionWriteQueue.Target target=queue.begin();
   if(target==null){finish(null);return;}
   sendTarget(target,0);
  }
  void sendTarget(ReactionWriteQueue.Target target,int attempt){
   final int generation=GENERATION.getOrDefault(key,0);
   boolean notifyOwner=Prefs.notifLikes(context) && !owner.isEmpty() && !owner.equals(uid);
   D1Client.requestInteractive(collection,id,"reactions","PUT",D1Client.object("type",target.type,"notify",notifyOwner))
    .addOnSuccessListener(data->{
     if(data.optBoolean("notificationCreated") && data.optJSONObject("notification")!=null)
      committedNotification=data.optJSONObject("notification");
     if(!current())return;
     JSONArray types=data.optJSONArray("types");
     if(types==null){recover(target,attempt);return;}
     if(data.optBoolean("reactionOnly")){
      try{JSONObject merged=new JSONObject(confirmed.toString());merged.put("types",types);confirmed=merged;}
      catch(JSONException invalid){recover(target,attempt);return;}
     }else {confirmed=data;acceptFullSummary(data,generation);}
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
   final int generation=GENERATION.getOrDefault(key,0);
   D1Client.requestInteractive(collection,id,"reactions","GET",null).addOnSuccessListener(data->{
    if(!current())return;
    if(data.optJSONArray("types")!=null){
     confirmed=data;acceptFullSummary(data,generation);
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
   if(committedNotification!=null){
    String notificationId=committedNotification.optString("id","");
    if(!notificationId.isEmpty())try{Notify.pushCommitted(notificationId,D1Client.map(committedNotification));}catch(JSONException ignored){}
   }
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

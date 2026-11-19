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
 private static final android.util.LruCache<String,JSONObject> CACHE=new android.util.LruCache<>(512);
 private static final android.util.LruCache<String,Long> FETCHED_AT=new android.util.LruCache<>(512);
 private static final long REFRESH_MS=8000;
 private static final android.util.LruCache<String,java.lang.ref.WeakReference<Models.Post>> POSTS=new android.util.LruCache<>(512);
 private static final Set<String> LOADING=new HashSet<>(),FAILED=new HashSet<>(),WRITING=new HashSet<>();
 private static final Map<String,Integer> GENERATION=new HashMap<>();
 private static final Map<String,List<Runnable>> WAITERS=new HashMap<>();
 private static void scope(){if(!Objects.equals(actor,Fb.uid())){actor=Fb.uid();CACHE.evictAll();FETCHED_AT.evictAll();POSTS.evictAll();LOADING.clear();FAILED.clear();WRITING.clear();GENERATION.clear();WAITERS.clear();}}
 public static String mine(JSONObject summary){JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("mine")>0)return r.optString("type");}return null;}
 public static int total(JSONObject summary){int n=0;JSONArray a=summary.optJSONArray("types");if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null)n+=r.optInt("count");}return n;}
 public static JSONObject cached(String collection,String id){scope();return CACHE.get(D1Client.key(collection,id));}
 public static String mine(String collection,String id){JSONObject data=cached(collection,id);return data==null?null:mine(data);}
 public static void invalidate(String collection,String id){scope();String key=D1Client.key(collection,id);GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);FETCHED_AT.remove(key);FAILED.remove(key);java.lang.ref.WeakReference<Models.Post> reference=POSTS.get(key);Models.Post post=reference==null?null:reference.get();if(post!=null)hydrate(post);}
 private static void remember(Context context,String collection,String id,JSONObject data){
  CACHE.put(D1Client.key(collection,id),data);FETCHED_AT.put(D1Client.key(collection,id),android.os.SystemClock.elapsedRealtime());List<String> types=new ArrayList<>();JSONArray a=data.optJSONArray("types");
  if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optInt("count")>0)types.add(r.optString("type"));}
  if("posts".equals(collection)){FastCache.setReactionTypes(id,types);FastCache.setReactionState(context,id,mine(data),total(data),true);}
 }
 private static void fetch(Context context,String collection,String id,Runnable ready){
  scope();String key=D1Client.key(collection,id);if(CACHE.get(key)!=null){
   ready.run();Long fetched=FETCHED_AT.get(key);if(fetched!=null && android.os.SystemClock.elapsedRealtime()-fetched<REFRESH_MS)return;
  }if(FAILED.contains(key)||WRITING.contains(key))return;
  WAITERS.computeIfAbsent(key,k->new ArrayList<>()).add(ready);
  if(LOADING.contains(key))return;
  String uid=actor;int generation=GENERATION.getOrDefault(key,0);LOADING.add(key);D1Client.request(collection,id,"reactions","GET",null,null).addOnSuccessListener(data->{
   if(!Objects.equals(uid,Fb.uid()))return;LOADING.remove(key);
   if(WRITING.contains(key)){WAITERS.remove(key);return;}
   if(generation!=GENERATION.getOrDefault(key,0)){List<Runnable> pending=WAITERS.remove(key);if(pending!=null)for(Runnable callback:pending)fetch(context,collection,id,callback);return;}
   remember(context,collection,id,data);List<Runnable> pending=WAITERS.remove(key);if(pending!=null)for(Runnable callback:pending)callback.run();
  }).addOnFailureListener(e->{if(!Objects.equals(uid,Fb.uid()))return;LOADING.remove(key);WAITERS.remove(key);FAILED.add(key);
   new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{if(Objects.equals(uid,Fb.uid()))FAILED.remove(key);},10000);});
 }
 public static void loadPost(Context context,Models.Post post,Runnable render){
  if(post==null || post.id==null)return;
  hydrate(post);
  fetch(context.getApplicationContext(),"posts",post.id,()->{hydrate(post);render.run();});
 }
 public static void hydrate(Models.Post post){
  scope();POSTS.put(D1Client.key("posts",post.id),new java.lang.ref.WeakReference<>(post));
  JSONObject summary=cached("posts",post.id);if(summary!=null){post.likes=total(summary);post.comments=summary.optInt("comments",post.comments);post.reactionsMap.clear();
   String type=mine(summary);if(type!=null){Map<String,Object> row=new HashMap<>();row.put("type",type);post.reactionsMap.put(Fb.uid(),row);}
   if(summary.has("preview")){post.inlineComments.clear();try{for(Map<String,Object> row:D1Client.maps(summary.optJSONArray("preview")))post.inlineComments.add(Models.Comment.fromMap(Format.str(row,"id"),row));}catch(JSONException ignored){}}
  }
 }
 public static void reel(Context context,Models.Reel reel,Runnable render){
  String collection=reel.isFromPost?"posts":"reels";JSONObject summary=cached(collection,reel.id);
  if(summary!=null){reel.likes=total(summary);reel.comments=summary.optInt("comments",reel.comments);reel.reactions.clear();reel.likedBy.clear();String type=mine(summary);if(type!=null){reel.reactions.put(Fb.uid(),type);reel.likedBy.add(Fb.uid());}}
  else fetch(context.getApplicationContext(),collection,reel.id,()->{reel(context,reel,render);render.run();});
 }
 public static void apply(Context context,String collection,String id,String requested,boolean onlyAdd,Runnable render){
  apply(context,collection,id,requested,onlyAdd,false,render);
 }
 static String nextReaction(String old,String requested,boolean toggleAny){
  return toggleAny && old!=null ? null : Objects.equals(old,requested)?null:requested;
 }
 public static void tapLike(Context context,String collection,String id,Runnable render){
  apply(context,collection,id,"like",false,true,render);
 }
 private static void apply(Context context,String collection,String id,String requested,boolean onlyAdd,boolean toggleAny,Runnable render){
  scope();String key=D1Client.key(collection,id);FAILED.remove(key);LOADING.remove(key);
  if(WRITING.contains(key))return;
  if(CACHE.get(key)==null){
   String uid=Fb.uid();WRITING.add(key);
   D1Client.request(collection,id,"reactions","GET",null,null).addOnSuccessListener(data->{
    if(!Objects.equals(uid,Fb.uid()))return;WRITING.remove(key);remember(context.getApplicationContext(),collection,id,data);apply(context,collection,id,requested,onlyAdd,toggleAny,render);
   }).addOnFailureListener(e->{if(Objects.equals(uid,Fb.uid())){WRITING.remove(key);Ui.toast(context,"Couldn't load reaction. Check connection and try again.");}});
   return;
  }
  GENERATION.put(key,GENERATION.getOrDefault(key,0)+1);
  JSONObject previous=CACHE.get(key);String old=mine(previous);if(onlyAdd&&old!=null){render.run();return;}
  String next=nextReaction(old,requested,toggleAny);String uid=Fb.uid();WRITING.add(key);
  try{JSONObject optimistic=new JSONObject(previous.toString());JSONArray adjusted=new JSONArray();Set<String> all=new LinkedHashSet<>(Arrays.asList(Reactions.TYPES));
   for(String type:all){int count=0;JSONArray rows=previous.optJSONArray("types");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null&&type.equals(row.optString("type")))count=row.optInt("count");}
    if(type.equals(old))count--;if(type.equals(next))count++;if(count>0)adjusted.put(D1Client.object("type",type,"count",count,"mine",type.equals(next)?1:0));}
   optimistic.put("types",adjusted);remember(context.getApplicationContext(),collection,id,optimistic);render.run();
  }catch(JSONException ignored){}
  D1Client.request(collection,id,"reactions","PUT",D1Client.object("type",next),null).addOnSuccessListener(data->{
   if(!Objects.equals(uid,Fb.uid()))return;WRITING.remove(key);remember(context.getApplicationContext(),collection,id,data);render.run();
   String owner=data.optString("owner");if(old==null&&next!=null&&!owner.isEmpty()&&!owner.equals(uid))Notify.send(owner,"posts".equals(collection)?"like":"reel_like",Fb.myName()+" liked your "+("posts".equals(collection)?"post":"reel"),id);
   D1Client.changed(key);
  }).addOnFailureListener(e->{if(!Objects.equals(uid,Fb.uid()))return;WRITING.remove(key);remember(context.getApplicationContext(),collection,id,previous);render.run();Ui.toast(context,"Like not saved. Check connection and tap again.");});
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

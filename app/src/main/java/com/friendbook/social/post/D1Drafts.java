package com.friendbook.social.post;
import android.app.*;
import android.content.*;
import android.util.Base64;
import com.friendbook.social.core.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Private, account-scoped unsent comments. Retry retains the original idempotency ID. */
public final class D1Drafts {
 private D1Drafts(){}
 private static final Set<List<String>> RETRYING=new HashSet<>();
 static void sending(String actor,String collection,String id,String commentId){RETRYING.add(Arrays.asList(actor,collection,id,commentId));}
 static void settled(String actor,String collection,String id,String commentId){RETRYING.remove(Arrays.asList(actor,collection,id,commentId));}
 static void retry(Context context,String actor,String collection,String id,JSONObject body,Consumer<JSONObject> saved,Consumer<Exception> failed){
  List<String> key=Arrays.asList(actor,collection,id,body.optString("id"));
  if(actor==null || !Objects.equals(actor,Fb.uid()) || RETRYING.contains(key)
      || !InteractionCooldown.acquire(actor,"comment-draft:"+key.toString()))return;
  RETRYING.add(key);
  try{body.put("notify",true);}catch(JSONException ignored){}
  D1Client.request(collection,id,"comments","POST",body,null).addOnSuccessListener(data->{
   RETRYING.remove(key);ack(context,actor,collection,id,body.optString("id"));
   if(Objects.equals(actor,Fb.uid()) && saved!=null)saved.accept(data);
  }).addOnFailureListener(error->{
   RETRYING.remove(key);if(Objects.equals(actor,Fb.uid()) && failed!=null)failed.accept(error);
  });
 }
 private static SharedPreferences prefs(Context c){return c.getSharedPreferences("d1_unsent_comments",Context.MODE_PRIVATE);}
 private static String prefix(String actor,String collection,String id){return Base64.encodeToString((actor+"\n"+collection+"/"+id).getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP|Base64.URL_SAFE)+":";}
 public static boolean keep(Context c,String actor,String collection,String id,JSONObject body){return c!=null && prefs(c).edit().putString(prefix(actor,collection,id)+body.optString("id"),body.toString()).commit();}
 public static void ack(Context c,String actor,String collection,String id,String commentId){if(c!=null)prefs(c).edit().remove(prefix(actor,collection,id)+commentId).commit();}
 public static List<JSONObject> list(Context c,String actor,String collection,String id){
  List<JSONObject> rows=new ArrayList<>();for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet())if(entry.getKey().startsWith(prefix(actor,collection,id)))try{rows.add(new JSONObject((String)entry.getValue()));}catch(Exception ignored){}return rows;
 }
 public static void show(Activity activity,String collection,String id,Consumer<JSONObject> saved){
  final String actor=Fb.uid();if(actor==null)return;List<JSONObject> rows=list(activity,actor,collection,id);
  if(rows.isEmpty()){Ui.toast(activity,"No unsent comments");return;}
  String[] labels=new String[rows.size()];for(int i=0;i<labels.length;i++){String text=rows.get(i).optString("text");labels[i]=text.isEmpty()?"Photo comment":text.substring(0,Math.min(80,text.length()));}
  new AlertDialog.Builder(activity).setTitle("Unsent comments — tap to retry").setItems(labels,(dialog,index)->{
   retry(activity.getApplicationContext(),actor,collection,id,rows.get(index),data->{
    saved.accept(data);
    if(!activity.isFinishing() && !activity.isDestroyed())Ui.toast(activity,"Comment saved");
   },error->{if(!activity.isFinishing() && !activity.isDestroyed())Ui.toast(activity,"Still unsent. Draft retained; no Firebase write was made.");});
  }).setNegativeButton("Close",null).show();
 }
}

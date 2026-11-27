package com.friendbook.social.core;
import android.app.*;
import android.widget.*;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.*;
import java.util.*;
import java.util.function.Consumer;
/** Paged friend choices. Existing selections outside the loaded page are never discarded. */
public final class FriendPicker {
    private FriendPicker(){}
    public static void single(Activity a,String title,Consumer<Models.User> picked){show(a,title,null,picked,null);}
    public static void multi(Activity a,String title,Map<String,String> initial,Consumer<Map<String,String>> picked){show(a,title,new LinkedHashMap<>(initial),null,picked);}
    private static void show(Activity a,String title,LinkedHashMap<String,String> selected,Consumer<Models.User> single,Consumer<Map<String,String>> multi){
        String actor=Fb.uid();if(actor==null)return;
        LinearLayout root=Ui.col(a),rows=Ui.col(a),controls=Ui.col(a);ScrollView scroll=new ScrollView(a);scroll.addView(rows);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,Ui.dp(a,320)));root.addView(controls);
        AlertDialog.Builder builder=new AlertDialog.Builder(a).setTitle(title).setView(root).setNegativeButton("Cancel",null);
        if(selected!=null)builder.setPositiveButton("Done",(d,w)->{
            if(Objects.equals(actor,Fb.uid()) && !a.isFinishing() && !a.isDestroyed())multi.accept(new LinkedHashMap<>(selected));
        });
        AlertDialog dialog=builder.create();dialog.show();final int[] generation={0};
        ListenerRegistration reg=PagedLists.attach(a,controls,Fb.db().collection("friends").whereArrayContains("participants",actor),20,"friend-picker",(page,error)->{
            int request=++generation[0];rows.removeAllViews();Set<String> ids=new LinkedHashSet<>();
            for(DocumentSnapshot doc:page)for(String uid:Models.ChatRoom.fromDoc(doc).participants)if(!actor.equals(uid))ids.add(uid);
            if(ids.isEmpty()){rows.addView(Ui.tv(a,"No friends on this page.",14,Ui.sub(a)));return;}
            Map<String,TextView> itemRows=new LinkedHashMap<>();
            for(String uid:ids){TextView row=selected==null?Ui.tv(a,"Loading…",15,Ui.txt(a)):new CheckBox(a);Ui.pad(row,12,8,12,8);rows.addView(row);itemRows.put(uid,row);}
            UserBatchLookup.load(new ArrayList<>(ids),users->{
                if(!dialog.isShowing() || request!=generation[0] || !Objects.equals(actor,Fb.uid()))return;
                for(String uid:ids){
                    Models.User user=users.get(uid);TextView row=itemRows.get(uid);if(user==null || row==null)continue;
                    row.setText(user.displayName);
                    if(selected==null)row.setOnClickListener(v->{
                        if(!dialog.isShowing() || !Objects.equals(actor,Fb.uid()) || a.isFinishing() || a.isDestroyed())return;
                        dialog.dismiss();single.accept(user);
                    });
                    else {CheckBox check=(CheckBox)row;check.setChecked(selected.containsKey(uid));check.setOnCheckedChangeListener((button,on)->{if(on)selected.put(uid,user.displayName);else selected.remove(uid);});}
                }
            });
        });
        android.app.Application.ActivityLifecycleCallbacks lifecycle=new android.app.Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity activity,android.os.Bundle state){}
            public void onActivityStarted(Activity activity){}
            public void onActivityResumed(Activity activity){}
            public void onActivityPaused(Activity activity){}
            public void onActivityStopped(Activity activity){if(activity==a && dialog.isShowing())dialog.dismiss();}
            public void onActivitySaveInstanceState(Activity activity,android.os.Bundle state){}
            public void onActivityDestroyed(Activity activity){if(activity==a && dialog.isShowing())dialog.dismiss();}
        };
        a.getApplication().registerActivityLifecycleCallbacks(lifecycle);
        dialog.setOnDismissListener(d->{generation[0]++;reg.remove();a.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);});
    }
}

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
        if(selected!=null)builder.setPositiveButton("Done",(d,w)->multi.accept(new LinkedHashMap<>(selected)));
        AlertDialog dialog=builder.create();dialog.show();final int[] generation={0};
        ListenerRegistration reg=PagedLists.attach(a,controls,Fb.db().collection("friends").whereArrayContains("participants",actor),20,"friend-picker",(page,error)->{
            int request=++generation[0];rows.removeAllViews();Set<String> ids=new LinkedHashSet<>();
            for(DocumentSnapshot doc:page)for(String uid:Models.ChatRoom.fromDoc(doc).participants)if(!actor.equals(uid))ids.add(uid);
            if(ids.isEmpty())rows.addView(Ui.tv(a,"No friends on this page.",14,Ui.sub(a)));
            for(String uid:ids){TextView row=selected==null?Ui.tv(a,"Loading…",15,Ui.txt(a)):new CheckBox(a);Ui.pad(row,12,8,12,8);rows.addView(row);
                Fb.getUser(uid).addOnSuccessListener(user->{
                    if(user==null || !dialog.isShowing() || request!=generation[0] || !Objects.equals(actor,Fb.uid()))return;
                    row.setText(user.displayName);
                    if(selected==null)row.setOnClickListener(v->{dialog.dismiss();single.accept(user);});
                    else {CheckBox check=(CheckBox)row;check.setChecked(selected.containsKey(uid));check.setOnCheckedChangeListener((button,on)->{if(on)selected.put(uid,user.displayName);else selected.remove(uid);});}
                });
            }
        });dialog.setOnDismissListener(d->{generation[0]++;reg.remove();});
    }
}

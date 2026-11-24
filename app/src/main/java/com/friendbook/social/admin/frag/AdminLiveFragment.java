package com.friendbook.social.admin.frag;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Ui;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.ListenerRegistration;
import com.friendbook.social.d1.documents.Query;

import java.util.Map;

/** Admin Live tab — all rooms (live + recent ended), viewers, ⭐ stars, End control. */
public class AdminLiveFragment extends Fragment {

    private LinearLayout list;
    private TextView countT;
    private ListenerRegistration reg;
    private com.friendbook.social.core.CursorPager pages;
    private LinearLayout cards;
    private TextView more;
    private String actor;
    private boolean started;
    private final java.util.Set<String> pendingEnds=new java.util.HashSet<>();
    private final java.util.Set<String> endedIds=new java.util.HashSet<>();
    private final java.util.List<DocumentSnapshot> loaded=new java.util.ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inf, ViewGroup container, Bundle b) {
        android.app.Activity a = getActivity();
        ScrollView sv = new ScrollView(a);
        list = Ui.col(a);
        list.setBackgroundColor(Ui.bg(a));
        Ui.pad(list, 12, 10, 12, 24);
        sv.addView(list);
        countT = Ui.tv(a, "🔴 Live rooms", 12, Ui.sub(a));
        Ui.pad(countT, 2, 0, 2, 8);
        list.addView(countT);
        cards=Ui.col(a);list.addView(cards);
        LinearLayout controls=Ui.row(a);list.addView(controls);
        TextView refresh=Ui.tvBold(a,"Refresh rooms",13,Ui.BLUE);Ui.pad(refresh,8,10,8,10);
        refresh.setOnClickListener(v->startPages());controls.addView(refresh);
        more=Ui.tvBold(a,"Load 10 more",13,Ui.BLUE);Ui.pad(more,8,10,8,10);
        more.setOnClickListener(v->{if(pages!=null)pages.next();});controls.addView(more);
        return sv;
    }

    @Override
    public void onStart() {
        super.onStart();
        android.app.Activity a=getActivity();
        if(a==null || !Admin.isAdmin() || Fb.uid()==null)return;
        if(actor!=null && !actor.equals(Fb.uid())){a.finish();return;}
        actor=Fb.uid();started=true;startPages();
    }

    private void startPages() {
        if(!started || !java.util.Objects.equals(actor,Fb.uid()) || getActivity()==null)return;
        if(reg!=null){reg.remove();reg=null;}
        if(pages!=null){pages.close();pages=null;}
        loaded.clear();renderRooms();
        Query query=Fb.db().collection("lives");
        pages=new com.friendbook.social.core.CursorPager(query,10,"admin-live",(docs,hasMore,busy,error)->{
            if(!started || !java.util.Objects.equals(actor,Fb.uid()) || getActivity()==null)return;
            more.setText(busy?"Loading…":!error.isEmpty()?"Retry page":hasMore?"Load 10 more":"All loaded");
            more.setEnabled(!busy && hasMore);
            if(!busy && (error.isEmpty() || !docs.isEmpty())){loaded.clear();loaded.addAll(docs);renderRooms();}
            if(!error.isEmpty())Ui.toast(getActivity(),error);
        });
        pages.startObserved();
        reg=query.limit(10).addSnapshotListener((snap,err)->{
            if(!started || !java.util.Objects.equals(actor,Fb.uid()) || pages==null)return;
            if(err!=null){pages.observedError();return;}
            if(snap!=null)pages.acceptObservedFirstPage(snap);
        });
    }

    private void renderRooms() {
        android.app.Activity a=getActivity();
        if(!started || a==null || cards==null)return;
        cards.removeAllViews();
        java.util.List<DocumentSnapshot> docs=new java.util.ArrayList<>(loaded);
        java.util.Collections.sort(docs,(x,y)->Long.compare(Format.longOf(y.getData(),"startedAtMs"),Format.longOf(x.getData(),"startedAtMs")));
        countT.setText(docs.size()+" loaded room(s) — sorted within loaded pages");
        if(docs.isEmpty())cards.addView(Ui.tv(a,"No rooms in loaded pages.",13,Ui.sub(a)));
        for(DocumentSnapshot d:docs)cards.addView(row(a,d));
    }

    @Override
    public void onStop() {
        super.onStop();started=false;
        if(reg!=null){reg.remove();reg=null;}
        if(pages!=null){pages.close();pages=null;}
    }

    private View row(android.app.Activity a, DocumentSnapshot d) {
        Map<String, Object> m = d.getData();
        boolean live = "live".equals(Format.str(m, "status")) && !endedIds.contains(d.getId());

        LinearLayout card = Ui.row(a);
        card.setBackground(Ui.round(a, Ui.card(a), 14));
        Ui.pad(card, 12, 10, 12, 10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, 0, 0, Ui.dp(a, 10));
        card.setLayoutParams(cp);

        ImageView av = new ImageView(a);
        String photo = Format.str(m, "hostPhotoUrl");
        if (photo.isEmpty()) photo = Format.str(m, "hostPhoto");
        Ui.avatar(a, av, photo);
        int s = Ui.dp(a, 48);
        card.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout info = Ui.col(a);
        info.setPadding(Ui.dp(a, 12), 0, 0, 0);
        info.addView(Ui.tvBold(a, Format.strOr(m, "hostName", "Host"), 14, Ui.txt(a)));
        info.addView(Ui.tv(a, Format.str(m, "title"), 12, Ui.sub(a)));
        int vCnt = Math.max(Format.intOf(m, "viewers"), Format.intOf(m, "viewerCount"));
        int sCnt = Math.max(Format.intOf(m, "stars"), Format.intOf(m, "totalStars"));
        info.addView(Ui.tv(a, "👁 " + Format.count(vCnt)
                + "  ·  ⭐ " + Format.count(sCnt) + " community stars",
                11, Ui.sub(a)));
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout right = Ui.col(a);
        TextView chip = Ui.roundChip(a, live ? "🔴 LIVE" : "ENDED",
                live ? 0xFFE41E3F : 0x3365676B, 0xFFFFFFFF, 10);
        right.addView(chip);
        if (live) {
            TextView end = Ui.tvBold(a, "End", 12, Ui.RED);
            end.setPadding(Ui.dp(a, 8), Ui.dp(a, 8), 0, 0);
            end.setEnabled(!pendingEnds.contains(d.getId()));
            end.setOnClickListener(v -> Ui.confirm(a, "End this live?",
                    "Viewers will see it end immediately.", "End live", () -> {
                        final String owner=actor,id=d.getId();
                        if(!started || !Admin.isAdmin() || !java.util.Objects.equals(owner,Fb.uid()) || !pendingEnds.add(id))return;
                        renderRooms();
                        Fb.db().moderate("end_live","lives",id).addOnCompleteListener(task->{
                            pendingEnds.remove(id);
                            if(!java.util.Objects.equals(owner,Fb.uid()))return;
                            if(task.isSuccessful())endedIds.add(id);
                            if(started){renderRooms();if(!task.isSuccessful())Ui.toast(a,"Could not end live. Please retry.");}
                        });
                    }));
            right.addView(end);
        }
        card.addView(right);
        card.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(a, com.friendbook.social.live.LiveActivity.class);
            i.putExtra("live_id", d.getId());
            i.putExtra("host_id", Format.str(m, "hostId"));
            a.startActivity(i);
        });
        return card;
    }
}

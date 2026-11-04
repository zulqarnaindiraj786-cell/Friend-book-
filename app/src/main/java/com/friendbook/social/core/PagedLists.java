package com.friendbook.social.core;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.firebase.firestore.*;
import java.util.*;

/** Explicit refresh/more controls for legacy list screens. No silent truncation or background draining. */
public final class PagedLists {
    private PagedLists() {}
    public static final class Page implements Iterable<DocumentSnapshot> {
        private final List<DocumentSnapshot> docs;
        Page(List<DocumentSnapshot> docs){this.docs=Collections.unmodifiableList(new ArrayList<>(docs));}
        public List<DocumentSnapshot> getDocuments(){return docs;}
        public int size(){return docs.size();}
        public boolean isEmpty(){return docs.isEmpty();}
        @Override public Iterator<DocumentSnapshot> iterator(){return docs.iterator();}
    }
    public interface Listener {void changed(Page page,Exception error);}
    public static ListenerRegistration attach(Activity activity,LinearLayout controls,Query query,int size,String key,Listener listener) {
        LinearLayout row=Ui.row(activity);String label=key.split(":",2)[0].replace('-',' ');
        TextView refresh=Ui.tvBold(activity,"Refresh "+label,13,Ui.BLUE);
        TextView more=Ui.tvBold(activity,"Load "+size+" more",13,Ui.BLUE);
        Ui.pad(refresh,12,8,12,8);Ui.pad(more,12,8,12,8);refresh.setVisibility(android.view.View.GONE);row.addView(more);controls.addView(row);
        final CursorPager[] current={null};final boolean[] closed={false};
        Runnable start=()->{
            if(current[0]!=null)current[0].close();
            current[0]=new CursorPager(query,size,key,(docs,hasMore,busy,error)->{
                if(closed[0] || activity.isFinishing())return;
                more.setText(busy?"Loading…":!error.isEmpty()?"Retry page":hasMore?"Load "+size+" more":"All loaded");
                more.setEnabled(!busy && hasMore);refresh.setEnabled(!busy);
                // An empty in-flight cache isn't an authoritative empty result.
                if(!busy && (error.isEmpty() || !docs.isEmpty()))listener.changed(new Page(docs),null);
                if(!error.isEmpty())Ui.toast(activity,error);
            });current[0].start();
        };
        refresh.setOnClickListener(v->start.run());more.setOnClickListener(v->{if(current[0]!=null)current[0].next();});start.run();
        return ()->{closed[0]=true;if(current[0]!=null)current[0].close();controls.removeView(row);};
    }
}

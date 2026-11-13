package com.friendbook.social.core;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/** Fetch only the next small metadata page during real scrolling, not layout/cache hydration. */
public final class NearEndPaging {
    private NearEndPaging() {}
    public static void attach(RecyclerView view,Runnable next) {
        ScrollPageGate gate=new ScrollPageGate();
        view.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrolled(RecyclerView list,int dx,int dy){
                if(list.getScrollState()==RecyclerView.SCROLL_STATE_IDLE || !(list.getLayoutManager() instanceof LinearLayoutManager))return;
                LinearLayoutManager layout=(LinearLayoutManager)list.getLayoutManager();
                int count=list.getAdapter()==null?0:list.getAdapter().getItemCount();
                if(gate.shouldLoad(dy,layout.findLastVisibleItemPosition()>=count-5,count))next.run();
            }
        });
    }
    public static void attachComments(RecyclerView view,java.util.function.Supplier<String> sort,Runnable next){
        ScrollPageGate gate=new ScrollPageGate();
        view.addOnScrollListener(new RecyclerView.OnScrollListener(){
            @Override public void onScrolled(RecyclerView list,int dx,int dy){
                if(list.getScrollState()==RecyclerView.SCROLL_STATE_IDLE || !(list.getLayoutManager() instanceof LinearLayoutManager))return;
                LinearLayoutManager layout=(LinearLayoutManager)list.getLayoutManager();
                int count=list.getAdapter()==null?0:list.getAdapter().getItemCount();
                boolean chronological="all".equals(sort.get());
                boolean near=chronological?layout.findFirstVisibleItemPosition()<=2:layout.findLastVisibleItemPosition()>=count-5;
                if(gate.shouldLoad(chronological?-dy:dy,near,count))next.run();
            }
        });
    }

}

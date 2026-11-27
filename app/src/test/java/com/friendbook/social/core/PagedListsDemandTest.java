package com.friendbook.social.core;

import android.app.Activity;
import android.os.Looper;
import android.widget.LinearLayout;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class PagedListsDemandTest {
    @Test public void cappedHeadUsesCacheAndExplicitRefreshGoesToServer() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
            LinearLayout controls=new LinearLayout(activity);
            Query query=mock(Query.class),head=mock(Query.class);
            when(query.limit(10)).thenReturn(head);
            QuerySnapshot cache=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
            QuerySnapshot server=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
            when(head.get(Source.CACHE)).thenReturn(Tasks.forResult(cache));
            when(head.hasFreshLocalPage(120_000L)).thenReturn(true);
            when(head.get(Source.SERVER)).thenReturn(Tasks.forResult(server));
            AtomicInteger updates=new AtomicInteger();
            ListenerRegistration reg=PagedLists.attach(activity,controls,query,200,"events",(p,e)->updates.incrementAndGet());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(query,atLeastOnce()).limit(10);
            verify(head,never()).get(Source.DEFAULT);
            assertEquals(1,updates.get());
            LinearLayout row=(LinearLayout)controls.getChildAt(0);
            row.getChildAt(0).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(head,times(1)).get(Source.SERVER);
            reg.remove();
            assertEquals(0,controls.getChildCount());
        }
    }

    @Test public void removedOrChangedActorSuppressesLateCallbacks() {
        for(boolean remove:new boolean[]{false,true}) {
            try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
                AtomicReference<String> actor=new AtomicReference<>("viewer");
                fb.when(Fb::uid).thenAnswer(x->actor.get());
                Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
                LinearLayout controls=new LinearLayout(activity);
                Query query=mock(Query.class),head=mock(Query.class);
                when(query.limit(10)).thenReturn(head);
                when(head.get(Source.CACHE)).thenReturn(Tasks.forException(new Exception("miss")));
                TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();
                when(head.get(Source.DEFAULT)).thenReturn(pending.getTask());
                AtomicInteger updates=new AtomicInteger();
                ListenerRegistration reg=PagedLists.attach(activity,controls,query,100,"events",(p,e)->updates.incrementAndGet());
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                if(remove)reg.remove();else actor.set("other");
                pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false)));
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals(0,updates.get());
                if(!remove)reg.remove();
            }
        }
    }
    @Test public void liveDiscoveryRefreshesFreshCacheOnceAndDoesNotAutoPage() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
            LinearLayout controls=new LinearLayout(activity);
            Query query=mock(Query.class),head=mock(Query.class);
            when(query.limit(10)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false))));
            when(head.hasFreshLocalPage(anyLong())).thenReturn(true);
            when(head.get(Source.SERVER)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
            ListenerRegistration reg=PagedLists.attach(activity,controls,query,40,"live-discovery",(p,e)->{});
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(head,times(1)).get(Source.SERVER);
            verify(head,never()).get(Source.DEFAULT);
            verify(query,never()).startAfter(any(DocumentSnapshot.class));
            reg.remove();
        }
    }

}

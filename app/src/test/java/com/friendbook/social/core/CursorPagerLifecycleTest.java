package com.friendbook.social.core;

import android.os.Looper;
import com.friendbook.social.d1.documents.DocumentSnapshot;
import com.friendbook.social.d1.documents.Query;
import com.friendbook.social.d1.documents.QuerySnapshot;
import com.friendbook.social.d1.documents.Source;
import com.google.android.gms.tasks.Tasks;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class CursorPagerLifecycleTest {
    @Test public void closingOnCachedPaintPreventsServerRequestForEmptyPopulatedAndFailedCache() {
        for(int variant=0;variant<6;variant++) {
            try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
                fb.when(Fb::uid).thenReturn("viewer");
                Query query=mock(Query.class),head=mock(Query.class);
                when(query.limit(10)).thenReturn(head);
                QuerySnapshot cache=mock(QuerySnapshot.class);
                DocumentSnapshot doc=mock(com.friendbook.social.d1.documents.QueryDocumentSnapshot.class);when(doc.getId()).thenReturn("one");
                when(cache.isEmpty()).thenReturn(variant%3==0);
                when(cache.size()).thenReturn(variant%3==0?0:1);
                when(cache.getDocuments()).thenReturn(variant%3==0?Collections.emptyList():Collections.singletonList(doc));
                when(cache.iterator()).thenAnswer(call->cache.getDocuments().iterator());
                when(head.get(Source.CACHE)).thenReturn(variant%3==2?Tasks.forException(new java.io.IOException("cache miss")):Tasks.forResult(cache));
                // If the regression returns this stub prevents an unrelated NPE.
                when(head.get(Source.SERVER)).thenReturn(Tasks.forException(new java.io.IOException("must not run")));
                CursorPager[] pager={null};
                pager[0]=new CursorPager(query,10,"lifecycle:"+variant,(docs,more,loading,error)->pager[0].close());
                if(variant<3) pager[0].startFullRefresh(); else pager[0].startDelta();
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                verify(head,never()).get(Source.SERVER);
                verify(head,never()).refreshCachedHead(org.mockito.ArgumentMatchers.nullable(QuerySnapshot.class));
            }
        }
    }
    @Test public void accountChangeDuringCachedPaintPreventsServerRequest() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            AtomicReference<String> actor=new AtomicReference<>("alice");
            fb.when(Fb::uid).thenAnswer(call->actor.get());
            Query query=mock(Query.class),head=mock(Query.class);
            when(query.limit(10)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forException(new java.io.IOException("cache miss")));
            when(head.get(Source.SERVER)).thenReturn(Tasks.forException(new java.io.IOException("must not run")));
            CursorPager pager=new CursorPager(query,10,"account-change",(docs,more,loading,error)->actor.set("bob"));
            pager.startFullRefresh();Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(head,never()).get(Source.SERVER);
        }
    }
    @Test public void openUnchangedPagerStillRefreshesNormally() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Query query=mock(Query.class),head=mock(Query.class);
            when(query.limit(10)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forException(new java.io.IOException("cache miss")));
            when(head.get(Source.SERVER)).thenReturn(Tasks.forException(new java.io.IOException("offline")));
            CursorPager pager=new CursorPager(query,10,"still-open",(docs,more,loading,error)->{});
            pager.startFullRefresh();Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(head,times(1)).get(Source.SERVER);
            pager.close();
        }
    }

    @Test public void timedOutAutomaticFirstPageCanBeStartedAgainWithANewScope() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class);
            MockedStatic<com.friendbook.social.App> app=mockStatic(com.friendbook.social.App.class)) {
            fb.when(Fb::uid).thenReturn("viewer");app.when(com.friendbook.social.App::isForeground).thenReturn(true);
            Query query=mock(Query.class),head=mock(Query.class);when(query.limit(10)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forException(new java.io.IOException("cache miss")));
            when(head.getAutomatic(any(com.friendbook.social.d1.documents.ForegroundReadScope.class)))
                    .thenReturn(new com.google.android.gms.tasks.TaskCompletionSource<QuerySnapshot>().getTask());
            CursorPager pager=new CursorPager(query,10,"foreground-retry",(docs,more,loading,error)->{});
            pager.startCachedForForeground(120_000L);Shadows.shadowOf(Looper.getMainLooper()).idle();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(21));
            pager.startCachedForForeground(120_000L);Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.mockito.ArgumentCaptor<com.friendbook.social.d1.documents.ForegroundReadScope> scopes=
                    org.mockito.ArgumentCaptor.forClass(com.friendbook.social.d1.documents.ForegroundReadScope.class);
            verify(head,times(2)).getAutomatic(scopes.capture());
            org.junit.Assert.assertNotSame(scopes.getAllValues().get(0),scopes.getAllValues().get(1));
            org.junit.Assert.assertFalse(scopes.getAllValues().get(0).whenForeground().isSuccessful());
            pager.close();
        }
    }

    @Test public void observedHeadIsReusedWithoutPrefetchAndLateNextIsIgnoredAfterClose() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Query query=mock(Query.class),head=mock(Query.class),after=mock(Query.class),next=mock(Query.class);
            when(query.limit(10)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new com.friendbook.social.d1.documents.SnapshotMetadata(true,false))));
            java.util.List<DocumentSnapshot> docs=new java.util.ArrayList<>();
            for(int i=0;i<10;i++) {
                DocumentSnapshot doc=mock(com.friendbook.social.d1.documents.QueryDocumentSnapshot.class);
                when(doc.getId()).thenReturn("user"+i);docs.add(doc);
            }
            QuerySnapshot observed=mock(QuerySnapshot.class);
            when(observed.getMetadata()).thenReturn(new com.friendbook.social.d1.documents.SnapshotMetadata(false,false));
            when(observed.getDocuments()).thenReturn(docs);
            when(observed.size()).thenReturn(10);
            when(query.startAfter(docs.get(9))).thenReturn(after);
            when(after.limit(10)).thenReturn(next);
            com.google.android.gms.tasks.TaskCompletionSource<QuerySnapshot> pending=new com.google.android.gms.tasks.TaskCompletionSource<>();
            when(next.get()).thenReturn(pending.getTask());
            java.util.concurrent.atomic.AtomicInteger delivered=new java.util.concurrent.atomic.AtomicInteger();
            CursorPager pager=new CursorPager(query,10,"admin-observed-test",(rows,more,loading,error)->delivered.incrementAndGet());
            pager.startObserved();Shadows.shadowOf(Looper.getMainLooper()).idle();
            pager.acceptObservedFirstPage(observed);
            verify(head,never()).get(Source.SERVER);verify(head,never()).get(Source.DEFAULT);
            verify(query,never()).startAfter(any(DocumentSnapshot.class));
            pager.next();Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(next,times(1)).get();
            int before=delivered.get();pager.close();
            pending.setResult(new QuerySnapshot(Collections.emptyList(),null,new com.friendbook.social.d1.documents.SnapshotMetadata(false,false)));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            org.junit.Assert.assertEquals(before,delivered.get());
        }
    }

}

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

}

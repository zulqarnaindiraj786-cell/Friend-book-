package com.friendbook.social.core;

import android.os.Looper;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.Tasks;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class SearchPageBudgetTest {
    @Test public void hundredSearchViewsOfSameRecentPostsNeedOneColdHeadRead() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Query query=mock(Query.class),head=mock(Query.class);
            AtomicBoolean fresh=new AtomicBoolean(false);
            QuerySnapshot saved=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false));
            QuerySnapshot server=new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false));
            when(query.limit(20)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forResult(saved));
            when(head.hasFreshLocalPage(SearchReadPolicy.EXACT_PAGE_REUSE_MS)).thenAnswer(call->fresh.get());
            when(head.get(Source.SERVER)).thenAnswer(call->{fresh.set(true);return Tasks.forResult(server);});
            for(int term=0;term<100;term++) {
                CursorPager pager=new CursorPager(query,20,"search-budget:"+term,(docs,more,loading,error)->{});
                pager.startCachedFor(SearchReadPolicy.EXACT_PAGE_REUSE_MS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                pager.close();
            }
            verify(head,times(1)).get(Source.SERVER);
            // Expiry or collection invalidation must still produce a real read.
            fresh.set(false);
            CursorPager expired=new CursorPager(query,20,"search-expired",(docs,more,loading,error)->{});
            expired.startCachedFor(SearchReadPolicy.EXACT_PAGE_REUSE_MS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();expired.close();
            verify(head,times(2)).get(Source.SERVER);
        }
    }

    @Test public void explicitRefreshIsNotSuppressedByFreshSearchCache() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("viewer");
            Query query=mock(Query.class),head=mock(Query.class);
            when(query.limit(20)).thenReturn(head);
            when(head.get(Source.CACHE)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(true,false))));
            when(head.hasFreshLocalPage(anyLong())).thenReturn(true);
            when(head.get(Source.SERVER)).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
            CursorPager pager=new CursorPager(query,20,"search-refresh",(docs,more,loading,error)->{});
            pager.startFullRefresh();Shadows.shadowOf(Looper.getMainLooper()).idle();pager.close();
            verify(head,times(1)).get(Source.SERVER);
        }
    }
}

package com.friendbook.social.main;

import com.friendbook.social.core.Fb;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class MainBadgeForegroundDemandTest {
    @Test public void bootstrapKeepsAllThreeBoundedBadgeReadsInActivityScope() throws Exception {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class)) {
            fb.when(Fb::uid).thenReturn("badge-actor");
            D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);
            CollectionReference notifications=mock(CollectionReference.class),chats=mock(CollectionReference.class),requests=mock(CollectionReference.class);
            Query n=mock(Query.class),c=mock(Query.class),r=mock(Query.class);
            when(db.collection("notifications")).thenReturn(notifications);when(notifications.whereEqualTo("toUserId","badge-actor")).thenReturn(n);
            when(n.whereEqualTo("isRead",false)).thenReturn(n);when(n.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(n);when(n.limit(10)).thenReturn(n);
            when(db.collection("chats")).thenReturn(chats);when(chats.whereArrayContains("participants","badge-actor")).thenReturn(c);
            when(c.orderBy(eq("inboxActivityAt"),any(Query.Direction.class))).thenReturn(c);when(c.limit(anyInt())).thenReturn(c);
            when(db.collection("friend_requests")).thenReturn(requests);when(requests.whereEqualTo("toUid","badge-actor")).thenReturn(r);
            when(r.whereEqualTo("status","pending")).thenReturn(r);when(r.orderBy(eq("createdAt"),any(Query.Direction.class))).thenReturn(r);when(r.limit(10)).thenReturn(r);
            TaskCompletionSource<QuerySnapshot> pending=new TaskCompletionSource<>();
            when(n.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(60_000L))).thenReturn(pending.getTask());
            when(c.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(Long.MAX_VALUE))).thenReturn(pending.getTask());
            when(r.getCachedOrFetchAutomatic(any(ForegroundReadScope.class),eq(300_000L))).thenReturn(pending.getTask());
            MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
            java.lang.reflect.Field f=MainActivity.class.getDeclaredField("badgeReadScope");f.setAccessible(true);
            ForegroundReadScope scope=(ForegroundReadScope)f.get(activity);
            java.lang.reflect.Method start=MainActivity.class.getDeclaredMethod("startBadgeListeners");start.setAccessible(true);start.invoke(activity);
            verify(n).getCachedOrFetchAutomatic(scope,60_000L);
            verify(c).getCachedOrFetchAutomatic(scope,Long.MAX_VALUE);
            verify(r).getCachedOrFetchAutomatic(scope,300_000L);
            verify(n,never()).getCachedOrFetch(anyBoolean(),anyLong());
            scope.close();
        }
    }
}

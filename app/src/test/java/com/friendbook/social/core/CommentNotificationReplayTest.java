package com.friendbook.social.core;

import android.os.Looper;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.push.FCMPush;
import com.friendbook.social.push.PushTrace;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class CommentNotificationReplayTest {
    @Test public void hundredCallbacksUseOneCreateOnlyIdentityAndPushOnlyAfterFirstCommit() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class);
            MockedStatic<FCMPush> push=mockStatic(FCMPush.class);
            MockedStatic<PushTrace> trace=mockStatic(PushTrace.class)) {
            fb.when(Fb::uid).thenReturn("actor");fb.when(Fb::myName).thenReturn("Actor");fb.when(Fb::myPhoto).thenReturn("");
            D1DocumentStore store=mock(D1DocumentStore.class);CollectionReference collection=mock(CollectionReference.class);
            fb.when(Fb::db).thenReturn(store);when(store.collection("notifications")).thenReturn(collection);
            Set<String> committed=new HashSet<>();
            when(collection.document(anyString())).thenAnswer(call->{
                String id=call.getArgument(0);DocumentReference ref=mock(DocumentReference.class);when(ref.getId()).thenReturn(id);
                when(ref.create(any())).thenAnswer(write->committed.add(id)?Tasks.<Void>forResult(null):Tasks.<Void>forException(
                    new D1DocumentStoreException(D1DocumentStoreException.Code.ALREADY_EXISTS,"exists")));
                return ref;
            });
            for(int i=0;i<100;i++)Notify.sendForComment("owner","comment","commented","posts","p","c1");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,committed.size());
            push.verify(()->FCMPush.notification(anyString(),anyMap()),times(1));
            Notify.sendForComment("owner","comment","another","posts","p","c2");
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,committed.size());
            push.verify(()->FCMPush.notification(anyString(),anyMap()),times(2));
            verify(collection,never()).add(any());
        }
    }
}

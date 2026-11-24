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
            for(int i=0;i<100;i++){
                Notify.sendForComment("owner","comment","commented","posts","p","c1");
                Shadows.shadowOf(Looper.getMainLooper()).idle();
            }
            verify(collection,times(1)).document(anyString());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1,committed.size());
            push.verify(()->FCMPush.notification(anyString(),anyMap()),times(1));
            Notify.sendForComment("owner","comment","another","posts","p","c2");
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(2,committed.size());
            push.verify(()->FCMPush.notification(anyString(),anyMap()),times(2));
            verify(collection,never()).add(any());
        }
    }
    @Test public void pendingDuplicatesShareOneWriteFailureAllowsRetryAndAccountSwitchBlocksPush() {
        try(MockedStatic<Fb> fb=mockStatic(Fb.class);
            MockedStatic<FCMPush> push=mockStatic(FCMPush.class);
            MockedStatic<PushTrace> trace=mockStatic(PushTrace.class)) {
            fb.when(Fb::uid).thenReturn("pending-actor");
            D1DocumentStore store=mock(D1DocumentStore.class);
            CollectionReference collection=mock(CollectionReference.class);
            DocumentReference ref=mock(DocumentReference.class);
            fb.when(Fb::db).thenReturn(store);when(store.collection("notifications")).thenReturn(collection);
            when(collection.document(anyString())).thenReturn(ref);when(ref.getId()).thenReturn("event");
            com.google.android.gms.tasks.TaskCompletionSource<Void> first=new com.google.android.gms.tasks.TaskCompletionSource<>();
            com.google.android.gms.tasks.TaskCompletionSource<Void> retry=new com.google.android.gms.tasks.TaskCompletionSource<>();
            when(ref.create(any())).thenReturn(first.getTask(),retry.getTask());
            for(int i=0;i<100;i++)Notify.sendForComment("owner","comment","text","posts","p","pending");
            verify(ref,times(1)).create(any());
            first.setException(new java.io.IOException("offline"));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            for(int i=0;i<100;i++)Notify.sendForComment("owner","comment","text","posts","p","pending");
            verify(ref,times(1)).create(any());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            Notify.sendForComment("owner","comment","text","posts","p","pending");
            verify(ref,times(2)).create(any());
            fb.when(Fb::uid).thenReturn("other-account");
            retry.setResult(null);Shadows.shadowOf(Looper.getMainLooper()).idle();
            push.verifyNoInteractions();
            // Another account must never join the previous account's event.
            when(ref.create(any())).thenReturn(Tasks.<Void>forResult(null));
            Notify.sendForComment("owner","comment","text","posts","p","pending");
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            verify(ref,times(3)).create(any());
            push.verify(()->FCMPush.notification(anyString(),anyMap()),times(1));
        }
    }
}

package com.friendbook.social.core;

import com.friendbook.social.d1.documents.*;
import com.friendbook.social.d1.documents.EventListener;
import com.friendbook.social.models.Models;
import com.friendbook.social.push.PushManager;
import com.google.firebase.auth.FirebaseAuth;
import java.util.*;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class ProfileReadDemandTest {
 @After public void clearProfileRegistration() throws Exception {
  Fb.me=null;
  for(String name:new String[]{"meReg","meListenerActor"}){
   java.lang.reflect.Field field=Fb.class.getDeclaredField(name);field.setAccessible(true);field.set(null,null);
  }
 }
 @Test public void cachedAndServerProfileCallbacksDoNotWriteOrAcquireProjectionTokens() {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class)){
   fb.when(Fb::uid).thenReturn("read-actor");fb.when(Fb::startMeListener).thenCallRealMethod();
   FirebaseAuth auth=mock(FirebaseAuth.class);fb.when(Fb::auth).thenReturn(auth);
   D1DocumentStore store=mock(D1DocumentStore.class);CollectionReference users=mock(CollectionReference.class);
   DocumentReference ref=mock(DocumentReference.class);fb.when(Fb::db).thenReturn(store);
   when(store.collection("users")).thenReturn(users);when(users.document("read-actor")).thenReturn(ref);
   List<EventListener<DocumentSnapshot>> callbacks=new ArrayList<>();
   when(ref.addSnapshotListener(org.mockito.ArgumentMatchers.<EventListener<DocumentSnapshot>>any())).thenAnswer(call->{callbacks.add(call.getArgument(0));return (ListenerRegistration)()->{};});
   Fb.startMeListener();assertEquals(1,callbacks.size());clearInvocations(store,ref);
   for(int i=0;i<100;i++){
    Map<String,Object> data=new HashMap<>();data.put("uid","read-actor");data.put("displayName","Name "+i);data.put("photoUrl","photo-"+i);
    callbacks.get(0).onEvent(new DocumentSnapshot(store,"users/read-actor","read-actor",data,true,new SnapshotMetadata(i%2==0,false),1,2),null);
   }
   assertNotNull(Fb.me);assertEquals("Name 99",Fb.me.displayName);
   verifyNoInteractions(auth);verifyNoInteractions(store,ref);
   cache.verify(()->FastCache.saveMe(org.mockito.ArgumentMatchers.nullable(android.content.Context.class),any(Models.User.class)),times(100));
  }
 }
 @Test public void authoritativeBanStillSignsOutAndClearsLocalProfile() {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<Admin> admin=mockStatic(Admin.class);MockedStatic<PushManager> push=mockStatic(PushManager.class)){
   fb.when(Fb::uid).thenReturn("banned-actor");fb.when(Fb::startMeListener).thenCallRealMethod();
   FirebaseAuth auth=mock(FirebaseAuth.class);fb.when(Fb::auth).thenReturn(auth);
   D1DocumentStore store=mock(D1DocumentStore.class);CollectionReference users=mock(CollectionReference.class);DocumentReference ref=mock(DocumentReference.class);
   fb.when(Fb::db).thenReturn(store);when(store.collection("users")).thenReturn(users);when(users.document("banned-actor")).thenReturn(ref);
   List<EventListener<DocumentSnapshot>> callbacks=new ArrayList<>();
   when(ref.addSnapshotListener(org.mockito.ArgumentMatchers.<EventListener<DocumentSnapshot>>any())).thenAnswer(call->{callbacks.add(call.getArgument(0));return (ListenerRegistration)()->{};});
   Fb.startMeListener();Map<String,Object> data=new HashMap<>();data.put("uid","banned-actor");data.put("banned",true);data.put("email","user@example.test");
   callbacks.get(0).onEvent(new DocumentSnapshot(store,"users/banned-actor","banned-actor",data,true,new SnapshotMetadata(false,false),1,2),null);
   verify(auth).signOut();assertNull(Fb.me);
   cache.verify(()->FastCache.clearAll(org.mockito.ArgumentMatchers.nullable(android.content.Context.class)));
  }
 }
}

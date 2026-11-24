package com.friendbook.social.bluetick;

import android.os.Looper;
import android.widget.TextView;
import com.friendbook.social.core.Fb;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.TaskCompletionSource;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ReferralScreenDemandTest {
 private java.lang.reflect.Field field(String name)throws Exception{java.lang.reflect.Field f=BlueTickActivity.class.getDeclaredField(name);f.setAccessible(true);return f;}
 private void payments(D1DocumentStore db){
  CollectionReference c=mock(CollectionReference.class);Query q=mock(Query.class);when(db.collection("payments")).thenReturn(c);
  when(c.whereEqualTo(eq("userId"),any())).thenReturn(q);when(q.whereEqualTo(eq("status"),eq("pending"))).thenReturn(q);
  when(q.limit(1)).thenReturn(q);when(q.addSnapshotListener(any())).thenReturn(mock(ListenerRegistration.class));
 }
 @Test public void openingVerificationReconcilesOnceAndRendersAuthoritativeResult()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);payments(db);
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();when(db.referralStatus()).thenReturn(pending.getTask());
   ActivityController<BlueTickActivity> controller=Robolectric.buildActivity(BlueTickActivity.class).create().start().resume();
   BlueTickActivity activity=controller.get();verify(db,times(1)).referralStatus();
   pending.setResult(new JSONObject("{\"count\":20,\"tickLevel\":\"gold\",\"isVerified\":true,\"awarded\":true}"));
   Shadows.shadowOf(Looper.getMainLooper()).idle();
   assertTrue(((TextView)field("heroStatus").get(activity)).getText().toString().contains("Gold"));
   TextView[] labels=(TextView[])field("progT").get(activity);assertTrue(labels[1].getText().toString().contains("20/20"));
   verify(db,times(1)).referralStatus();controller.pause().stop().destroy();
  }
 }
 @Test public void stoppedScreenIgnoresLateStatus()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("actor");D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);payments(db);
   TaskCompletionSource<JSONObject> pending=new TaskCompletionSource<>();when(db.referralStatus()).thenReturn(pending.getTask());
   ActivityController<BlueTickActivity> controller=Robolectric.buildActivity(BlueTickActivity.class).create().start().resume();
   BlueTickActivity activity=controller.get();controller.pause().stop();String before=((TextView)field("heroStatus").get(activity)).getText().toString();
   pending.setResult(new JSONObject("{\"count\":50,\"tickLevel\":\"diamond\",\"isVerified\":true,\"awarded\":true}"));
   Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(before,((TextView)field("heroStatus").get(activity)).getText().toString());
   verify(db,times(1)).referralStatus();controller.destroy();
  }
 }
}

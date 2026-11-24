package com.friendbook.social.chat;
import android.app.Activity;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ChatReadDemandTest {
 private void set(ChatActivity a,String name,Object value)throws Exception{java.lang.reflect.Field f=ChatActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(a,value);}
 @Test public void hundredForegroundResumesWithoutNewMessagesDoNotResetUnreadAgain()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<Prefs> prefs=mockStatic(Prefs.class);MockedStatic<SoundEffects> sound=mockStatic(SoundEffects.class)){
   fb.when(Fb::uid).thenReturn("receipt-actor");prefs.when(()->Prefs.hiddenMsgs(any(),anyString())).thenReturn(Collections.emptySet());
   ChatActivity a=Robolectric.buildActivity(ChatActivity.class).get();set(a,"chatId","receipt-chat");set(a,"messageActor","receipt-actor");
   java.lang.reflect.Field snapshots=ChatActivity.class.getDeclaredField("messageSnapshots");snapshots.setAccessible(true);
   Models.Message m=new Models.Message();m.id="already-read";m.receiverId="receipt-actor";m.isDelivered=true;m.isSeen=true;((Map<String,Models.Message>)snapshots.get(a)).put(m.id,m);
   D1DocumentStore db=mock(D1DocumentStore.class);fb.when(Fb::db).thenReturn(db);WriteBatch batch=mock(WriteBatch.class);when(db.batch()).thenReturn(batch);
   CollectionReference rooms=mock(CollectionReference.class);when(db.collection("chats")).thenReturn(rooms);when(rooms.document("receipt-chat")).thenReturn(mock(DocumentReference.class));when(batch.commit()).thenReturn(Tasks.forResult(null));
   java.lang.reflect.Method resume=ChatActivity.class.getDeclaredMethod("onResume");resume.setAccessible(true);
   for(int i=0;i<100;i++)resume.invoke(a);
   verify(batch,times(1)).commit();
  }
 }
 @Test public void hundredLiveInviteRebindsDoNotReadLiveDocsOrFrames()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("invite-actor");Activity a=Robolectric.buildActivity(Activity.class).setup().get();
   MessagesAdapter adapter=new MessagesAdapter(a,null);MessagesAdapter.VH holder=adapter.onCreateViewHolder(new android.widget.FrameLayout(a),0);
   Models.Message m=new Models.Message();m.id="invite";m.type="live_invite";m.liveId="live-document";m.senderId="host";
   java.lang.reflect.Method bind=MessagesAdapter.class.getDeclaredMethod("bindLiveInviteVideoCard",Activity.class,MessagesAdapter.VH.class,Models.Message.class);bind.setAccessible(true);
   for(int i=0;i<100;i++)bind.invoke(adapter,a,holder,m);
   fb.verify(Fb::db,never());assertEquals("🔴 LIVE INVITE",holder.liveBadgeTv.getText().toString());
  }
 }
 @Test public void duplicateSendIntentIsBoundedButDifferentTextIsNotBlocked()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
   fb.when(Fb::uid).thenReturn("send-demand-actor");ChatActivity a=Robolectric.buildActivity(ChatActivity.class).get();
   set(a,"chatStarted",true);set(a,"messageActor","send-demand-actor");set(a,"chatId","send-chat");
   java.lang.reflect.Method admit=ChatActivity.class.getDeclaredMethod("admitSend",String.class,String.class);admit.setAccessible(true);
   int accepted=0;for(int i=0;i<100;i++)if((Boolean)admit.invoke(a,"text","same-message"))accepted++;
   assertEquals(1,accepted);assertEquals(true,admit.invoke(a,"text","different-message"));
   fb.when(Fb::uid).thenReturn("replacement-actor");assertEquals(false,admit.invoke(a,"text","new-message"));
   fb.when(Fb::uid).thenReturn("send-demand-actor");set(a,"chatStarted",false);assertEquals(false,admit.invoke(a,"text","stopped-message"));fb.verify(Fb::db,never());
  }
 }

}

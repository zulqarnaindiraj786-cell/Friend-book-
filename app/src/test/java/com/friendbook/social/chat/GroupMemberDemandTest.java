package com.friendbook.social.chat;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class GroupMemberDemandTest {
 @Test public void hundredMemberRendersUseEmbeddedMetadataWithoutProfileReads()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class)){
   fb.when(Fb::uid).thenReturn("member-actor");GroupChatInfoActivity a=Robolectric.buildActivity(GroupChatInfoActivity.class).get();
   Models.ChatRoom room=new Models.ChatRoom();room.createdBy="creator";room.participantNames.put("member","Embedded name");room.participantPhotos.put("member","");
   java.lang.reflect.Field f=GroupChatInfoActivity.class.getDeclaredField("room");f.setAccessible(true);f.set(a,room);
   java.lang.reflect.Method row=GroupChatInfoActivity.class.getDeclaredMethod("buildMemberRow",String.class,boolean.class);row.setAccessible(true);
   for(int i=0;i<100;i++)row.invoke(a,"member",false);
   fb.verify(()->Fb.getUser(anyString()),never());fb.verify(Fb::db,never());
  }
 }
}

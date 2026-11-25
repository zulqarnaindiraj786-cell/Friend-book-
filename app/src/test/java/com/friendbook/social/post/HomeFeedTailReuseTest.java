package com.friendbook.social.post;
import com.friendbook.social.main.HomeFragment;
import com.friendbook.social.d1.documents.*;
import java.util.*;
import org.junit.Test;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class HomeFeedTailReuseTest {
 private java.lang.reflect.Field field(String n)throws Exception{java.lang.reflect.Field f=HomeFragment.class.getDeclaredField(n);f.setAccessible(true);return f;}
 @Test public void cachedHeadCannotDiscardTwentyPostSessionAfterInterruptedRead()throws Exception{
  HomeFragment h=new HomeFragment();List<DocumentSnapshot> docs=new ArrayList<>();
  for(int i=0;i<20;i++)docs.add(mock(DocumentSnapshot.class));
  field("lastPostDocs").set(h,docs);DocumentSnapshot cursor=docs.get(19);
  ((Set<String>)field("loadedPostIds").get(h)).add("tail-post");
  java.lang.reflect.Method m=HomeFragment.class.getDeclaredMethod("applyReusableFeedHead",QuerySnapshot.class,DocumentSnapshot.class,boolean.class,long.class,long.class);m.setAccessible(true);
  QuerySnapshot head=mock(QuerySnapshot.class);
  for(int i=0;i<100;i++)m.invoke(h,head,cursor,false,7L,100L);
  assertSame(docs,field("lastPostDocs").get(h));assertSame(cursor,field("lastDoc").get(h));
  assertFalse(field("moreAvailable").getBoolean(h));assertTrue(((Set<?>)field("loadedPostIds").get(h)).contains("tail-post"));
  assertTrue(field("loadedPosts").getBoolean(h));verifyNoInteractions(head);
 }
}

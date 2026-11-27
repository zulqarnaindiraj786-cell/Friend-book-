package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class NotificationEventKeyTest {
 @Test public void keysAreStableScopedAndDoNotCollapseComments() {
  String like=NotificationEventKey.forAction("alice","bob","like","post1");
  assertNotNull(like);assertEquals(like,NotificationEventKey.forAction("alice","bob","like","post1"));
  assertNotEquals(like,NotificationEventKey.forAction("alice","bob","like","post2"));
  assertNotEquals(like,NotificationEventKey.forAction("carol","bob","like","post1"));
  assertNotEquals(like,NotificationEventKey.forAction("alice","bob","reel_like","post1"));
  assertNotNull(NotificationEventKey.forAction("alice","bob","friend_request",null));
  assertNull(NotificationEventKey.forAction("alice","bob","comment","post1"));
  assertNull(NotificationEventKey.forAction("alice","bob","like",null));
 }
 @Test public void commentReplayIsStableButDistinctCommentsAndResourcesNeverCollapse() {
  String first=NotificationEventKey.forComment("a","b","comment","posts/p","c1");
  assertNotNull(first);
  for(int i=0;i<100;i++)assertEquals(first,NotificationEventKey.forComment("a","b","comment","posts/p","c1"));
  assertNotEquals(first,NotificationEventKey.forComment("a","b","comment","posts/p","c2"));
  assertNotEquals(first,NotificationEventKey.forComment("a","b","reel_comment","reels/p","c1"));
  assertNotEquals(first,NotificationEventKey.forComment("other","b","comment","posts/p","c1"));
  assertNotEquals(first,NotificationEventKey.forComment("a","other","comment","posts/p","c1"));
  assertNotEquals(first,NotificationEventKey.forComment("a","b","reply","posts/p","c1"));
  assertEquals(first,NotificationEventKey.forComment("a","b","reel_comment","posts/p","c1"));
  assertEquals(NotificationEventKey.forComment("a","b","reply","reels/p","c1"),
    NotificationEventKey.forComment("a","b","reel_comment_reply","reels/p","c1"));
  assertNull(NotificationEventKey.forComment("a","b","comment","posts/p",""));
 }
}

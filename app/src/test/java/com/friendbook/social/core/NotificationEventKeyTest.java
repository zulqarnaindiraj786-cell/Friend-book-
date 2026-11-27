package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class NotificationEventKeyTest {
 @Test public void reactionsGroupAcrossActorsOnlyForSameRecipientResourceTypeAndWindow() {
  long windowMs=6L*60*60*1000, start=1_800_000_000_000L/windowMs*windowMs;
  String like=NotificationEventKey.forAction("alice","bob","like","post1",start);
  assertNotNull(like);
  assertEquals(like,NotificationEventKey.forAction("carol","bob","like","post1",start+windowMs-1));
  assertNotEquals(like,NotificationEventKey.forAction("alice","bob","like","post1",start+windowMs));
  assertNotEquals(like,NotificationEventKey.forAction("alice","bob","like","post2",start));
  assertNotEquals(like,NotificationEventKey.forAction("alice","carol","like","post1",start));
  assertNotEquals(like,NotificationEventKey.forAction("alice","bob","reel_like","post1",start));
  String request=NotificationEventKey.forAction("alice","bob","friend_request",null);
  assertNotNull(request);assertEquals(request,NotificationEventKey.forAction("alice","bob","friend_request",null));
  String accepted=NotificationEventKey.forAction("bob","alice","friend_accept",null);
  assertNotNull(accepted);assertEquals(accepted,NotificationEventKey.forAction("bob","alice","friend_accept",null));
  assertNotEquals(accepted,NotificationEventKey.forAction("carol","alice","friend_accept",null));
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

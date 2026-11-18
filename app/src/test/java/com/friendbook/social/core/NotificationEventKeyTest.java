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
}

package com.friendbook.social.d1.documents;
import org.junit.Test;
import static org.junit.Assert.*;
public class ListenerPollingPolicyTest {
 @Test public void onlyDirectChatMessageCollectionsUseFasterSchedule() {
  assertTrue(ListenerPollingPolicy.isChatMessages("chats/alice_bob/messages"));
  assertFalse(ListenerPollingPolicy.isChatMessages("chats//messages"));
  assertFalse(ListenerPollingPolicy.isChatMessages("chats/room/messages/nested/messages"));
  assertFalse(ListenerPollingPolicy.isChatMessages("notifications"));
  assertFalse(ListenerPollingPolicy.isChatMessages(null));
 }
}

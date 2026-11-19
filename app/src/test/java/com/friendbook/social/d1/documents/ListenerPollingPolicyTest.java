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
 @Test public void backsOffDiscoveryAndConfigWithoutLosingCallSignaling() {
  assertFalse(ListenerPollingPolicy.due("posts", true, 5_000));
  assertFalse(ListenerPollingPolicy.due("posts", true, 30_000));
  assertFalse(ListenerPollingPolicy.due("config/ads", true, 60_000));
  assertFalse(ListenerPollingPolicy.due("posts", false, 3_600_000));
  assertFalse(ListenerPollingPolicy.due("chats", false, 10_000));
  assertFalse(ListenerPollingPolicy.due("chats/a_b", true, 5_000));
  assertFalse(ListenerPollingPolicy.due("chats/a_b/messages", true, 2_000));
  assertFalse(ListenerPollingPolicy.due("chats/a_b/messages", false, 30_000));
 }
}

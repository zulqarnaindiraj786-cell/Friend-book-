package com.friendbook.social.d1.documents;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
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

 /** No document may be re-read every second, in the foreground or the background. */
 @Test public void ordinaryDocumentsNeverPoll() {
  String[] ordinary = {"users/u1", "posts/p1", "comments/c1", "notifications", "friends",
          "friend_requests", "settings/global", "config/ads", "reels", "stories", "groups"};
  for (String path : ordinary) {
   assertEquals(path, Long.MAX_VALUE, ListenerPollingPolicy.intervalMs(path, true));
   assertEquals(path, Long.MAX_VALUE, ListenerPollingPolicy.intervalMs(path, false));
   long oneSecond = 1_000L;
   assertFalse(path, ListenerPollingPolicy.due(path, true, oneSecond));
   assertFalse(path, ListenerPollingPolicy.due(path, false, oneSecond));
  }
 }

 /** A visible screen paints cached data instead of re-reading the whole document. */
 @Test public void cachedDocumentsAreServedBeforeAnyNetworkRead() {
  assertEquals(60_000L, ListenerPollingPolicy.cacheFreshMs("users/u1"));
  assertEquals(60_000L, ListenerPollingPolicy.cacheFreshMs("posts/p1"));
  assertEquals(60_000L, ListenerPollingPolicy.cacheFreshMs("settings/global"));
  // Live rooms and an open call room must stay authoritative.
  assertEquals(0L, ListenerPollingPolicy.cacheFreshMs("lives"));
  assertEquals(0L, ListenerPollingPolicy.cacheFreshMs("lives/room1"));
  assertEquals(0L, ListenerPollingPolicy.cacheFreshMs("chats/a_b"));
  assertEquals(0L, ListenerPollingPolicy.cacheFreshMs(null));
 }

 /** Only the demand-armed store ticker may exist; no fixed-rate loops anywhere. */
 @Test public void noFixedRateDocumentLoopsRemain() throws Exception {
  List<File> sources = new ArrayList<>();
  collect(new File("src/main/java"), sources);
  assertTrue("Expected Android sources to be readable", sources.size() > 100);
  List<String> offenders = new ArrayList<>();
  for (File file : sources) {
   String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
   for (String forbidden : new String[]{"scheduleWithFixedDelay", "scheduleAtFixedRate", "new Timer("}) {
    if (text.contains(forbidden)) offenders.add(file.getName() + " -> " + forbidden);
   }
   if (text.contains("TimeUnit.SECONDS, 1,") || text.contains(", 1, 1, TimeUnit.SECONDS)")) {
    // One-second periodic scheduling of any document read is forbidden.
    if (file.getName().equals("D1DocumentStore.java")) offenders.add(file.getName() + " -> 1s schedule");
   }
  }
  assertTrue("Fixed-rate polling must not return: " + offenders, offenders.isEmpty());
 }

 @Test public void storeSchedulesOnlyOneDemandArmableTick() throws Exception {
  File store = new File("src/main/java/com/friendbook/social/d1/documents/D1DocumentStore.java");
  String text = new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8);
  assertTrue("Store must arm its ticker on demand", text.contains("armSignalingTicker"));
  assertTrue("Ticker must stop when nothing needs it", text.contains("hasPeriodicCandidate"));
  assertFalse("Ticker must not be scheduled from the constructor",
          text.contains("private D1DocumentStore() {\n        poller.schedule"));
  assertTrue("Cached documents must short-circuit the network read",
          text.contains("serveFromCacheOnly"));
 }

 private static void collect(File dir, List<File> out) {
  File[] children = dir.listFiles();
  if (children == null) return;
  for (File child : children) {
   if (child.isDirectory()) collect(child, out);
   else if (child.getName().endsWith(".java")) out.add(child);
  }
 }
}

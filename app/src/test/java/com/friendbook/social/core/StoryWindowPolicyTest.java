package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class StoryWindowPolicyTest {
 @Test public void anchorExpiresWithoutAnyTimerAndRejectsFutureClockValues(){
  long first=1_000_000L;
  assertEquals(first,StoryWindowPolicy.anchor(0,first));
  assertEquals(first,StoryWindowPolicy.anchor(first,first+119_999L));
  assertEquals(first+120_000L,StoryWindowPolicy.anchor(first,first+120_000L));
  assertEquals(first-1,StoryWindowPolicy.anchor(first,first-1));
  assertFalse(StoryWindowPolicy.fresh(0,first));
 }
}

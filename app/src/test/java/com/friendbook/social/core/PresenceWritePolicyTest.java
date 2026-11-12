package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class PresenceWritePolicyTest {
 @Test public void repeatedOnlineCoalescesAndSuccessfulWritesAreSpaced(){
  PresenceWritePolicy p=new PresenceWritePolicy();long first=p.begin("a",true,0);assertTrue(first>0);
  assertEquals(0,p.begin("a",true,25000));p.complete(first,true,0);
  assertEquals(0,p.begin("a",true,99999));assertTrue(p.begin("a",true,100000)>0);
 }
 @Test public void failureCanRetryNextHeartbeat(){
  PresenceWritePolicy p=new PresenceWritePolicy();long t=p.begin("a",true,0);p.complete(t,false,0);
  assertTrue(p.begin("a",true,25000)>0);
 }
 @Test public void offlineAndForegroundAreImmediateAndIgnoreLateOnlineCallback(){
  PresenceWritePolicy p=new PresenceWritePolicy();long old=p.begin("a",true,0);
  long offline=p.begin("a",false,1);assertTrue(offline>old);p.complete(old,true,2);p.complete(offline,true,3);
  assertTrue(p.begin("a",true,4)>0);
 }
 @Test public void accountChangesNeverShareThePreviousUsersBudget(){
  PresenceWritePolicy p=new PresenceWritePolicy();long a=p.begin("a",true,0);
  long b=p.begin("b",true,1);assertTrue(b>a);p.complete(a,true,2);p.complete(b,false,3);
  assertTrue(p.begin("b",true,4)>0);
 }
 @Test public void backwardsClockDoesNotSuppressFreshOnline(){
  PresenceWritePolicy p=new PresenceWritePolicy();long a=p.begin("a",true,1000);p.complete(a,true,1000);
  assertTrue(p.begin("a",true,999)>0);
 }
 @Test public void periodicMirrorWritesRemainBelowFiveMinuteFreshness(){
  assertTrue(PresenceWritePolicy.ONLINE_WRITE_INTERVAL_MS+Presence.HEARTBEAT_INTERVAL_MS<PresencePolicy.ONLINE_TTL_MS);
 }
}

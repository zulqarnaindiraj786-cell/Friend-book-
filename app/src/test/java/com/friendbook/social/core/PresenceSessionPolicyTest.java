package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class PresenceSessionPolicyTest {
 @Test public void onlyForegroundSignedInVisibleSessionsCanOpen(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();assertEquals(0,p.begin());
  p.configure("a",true,false);assertEquals(0,p.begin());
  p.configure("a",true,true);assertTrue(p.begin()>0);assertEquals(0,p.begin());
 }
 @Test public void logoutAndBackgroundInvalidateLateCallbacks(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();p.configure("a",true,true);long a=p.begin();
  assertTrue(p.configure(null,false,false));assertFalse(p.opened(a,60000,0));assertEquals(-1,p.failed(a));
  p.configure("a",true,true);a=p.begin();assertTrue(p.opened(a,60000,0));
  assertTrue(p.configure("a",false,true));assertFalse(p.canSend(a,1));assertFalse(p.opened(a,60000,1));
 }
 @Test public void accountChangeAndPrivacyDisableRetireOldConnections(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();p.configure("a",true,true);long a=p.begin();p.opened(a,60000,0);
  assertTrue(p.configure("b",true,true));assertFalse(p.canSend(a,1));long b=p.begin();assertTrue(p.opened(b,60000,1));
  assertTrue(p.configure("b",true,false));assertFalse(p.canSend(b,2));assertEquals(0,p.begin());
 }
 @Test public void refreshBeforeExpiryAndBadLeasesFailClosed(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();p.configure("a",true,true);long a=p.begin();assertTrue(p.opened(a,60000,1000));
  assertTrue(p.canSend(a,30999));assertFalse(p.canSend(a,31000));assertTrue(p.needsRefresh(31000));assertFalse(p.canSend(a,999));
  p.failed(a);a=p.begin();assertFalse(p.opened(a,30000,1000));assertFalse(p.canSend(a,1000));
 }
 @Test public void retryBackoffIsBoundedAndStaleScheduledRetriesCannotCrossAccounts(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();p.configure("a",true,true);
  long[] delays={1000,2000,4000,8000,16000,32000,60000,60000};
  for(long delay:delays){long a=p.begin();assertEquals(delay,p.failed(a));assertTrue(p.retryIsCurrent(p.generation()));}
  long retry=p.generation();p.configure("b",true,true);assertFalse(p.retryIsCurrent(retry));
 }
 @Test public void repeatedResumeDoesNotDuplicateConnectionAndSuccessfulOpenResetsBackoff(){
  PresenceSessionPolicy p=new PresenceSessionPolicy();p.configure("a",true,true);long a=p.begin();p.failed(a);a=p.begin();
  assertTrue(p.opened(a,60000,0));assertFalse(p.configure("a",true,true));assertEquals(0,p.begin());assertEquals(1000,p.failed(a));
 }
}

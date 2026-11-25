package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class HomeSectionReadPolicyTest {
 @Test public void repeatedSameActorLifecycleEntryDoesNotAdmitSecondaryReads(){
  assertTrue(HomeSectionReadPolicy.shouldStart("a",null,false,false));
  for(int entry=0;entry<10000;entry++)assertFalse(HomeSectionReadPolicy.shouldStart("a","a",true,false));
 }
 @Test public void explicitRefreshAccountChangeAndCancelledUnstartedEntryRemainAllowed(){
  assertTrue(HomeSectionReadPolicy.shouldStart("a","a",true,true));
  assertTrue(HomeSectionReadPolicy.shouldStart("b","a",true,false));
  assertTrue(HomeSectionReadPolicy.shouldStart("a","a",false,false));
  assertFalse(HomeSectionReadPolicy.shouldStart(null,"a",true,true));
  assertFalse(HomeSectionReadPolicy.shouldStart("","a",true,true));
 }
}

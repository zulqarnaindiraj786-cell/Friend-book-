package com.friendbook.social.d1.documents;
import org.junit.Test;
import static org.junit.Assert.*;
public class QueryDeliveryStateTest {
 @Test public void cachedResultDoesNotSuppressFirstServerConfirmation() {
  QueryDeliveryState s=new QueryDeliveryState(); s.cached("[1]");
  assertTrue(s.server("[1]")); assertFalse(s.server("[1]"));
  assertTrue(s.server("[2]")); assertFalse(s.server("[2]"));
 }
 @Test public void identicalResultAfterErrorIsDeliveredAsRecovery() {
  QueryDeliveryState s=new QueryDeliveryState(); assertTrue(s.server("[]"));
  s.error(); assertTrue(s.server("[]")); assertFalse(s.server("[]"));
 }
}

package com.friendbook.social.chat;
import org.junit.Test;
import static org.junit.Assert.*;
import com.friendbook.social.d1.documents.D1DocumentStoreException;
public class ChatSendRecoveryTest {
 @Test public void timeoutAndAbsentReadNeverMeanDefinitelyFailed() {
  assertEquals(ChatSendRecovery.State.UNKNOWN,ChatSendRecovery.failure(new java.io.IOException("timeout")));
  assertEquals(ChatSendRecovery.State.UNKNOWN,ChatSendRecovery.read(false,null,"alice"));
  assertEquals(ChatSendRecovery.State.UNKNOWN,ChatSendRecovery.read(true,"bob","alice"));
 }
 @Test public void confirmOnlyOwnSavedMessageAndHandleExplicitDenial() {
  assertEquals(ChatSendRecovery.State.CONFIRMED,ChatSendRecovery.read(true,"alice","alice"));
  assertEquals(ChatSendRecovery.State.DENIED,ChatSendRecovery.failure(new D1DocumentStoreException(D1DocumentStoreException.Code.PERMISSION_DENIED,"Forbidden")));
 }
}

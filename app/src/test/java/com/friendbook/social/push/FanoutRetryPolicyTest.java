package com.friendbook.social.push;

import com.friendbook.social.d1.documents.D1DocumentStoreException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import static org.junit.Assert.*;

public class FanoutRetryPolicyTest {
 @Test public void terminalServerErrorsStopEvenWhenWrappedByTaskAwait() {
  for(D1DocumentStoreException.Code code:new D1DocumentStoreException.Code[]{
   D1DocumentStoreException.Code.PERMISSION_DENIED,D1DocumentStoreException.Code.NOT_FOUND,
   D1DocumentStoreException.Code.UNAUTHENTICATED,D1DocumentStoreException.Code.CANCELLED,
   D1DocumentStoreException.Code.INVALID_ARGUMENT,D1DocumentStoreException.Code.FAILED_PRECONDITION,
   D1DocumentStoreException.Code.OUT_OF_RANGE,D1DocumentStoreException.Code.UNIMPLEMENTED}){
   Throwable error=new ExecutionException(new D1DocumentStoreException(code,"terminal"));
   assertFalse(code.toString(),FanoutRetryPolicy.shouldRetry(error,0));
  }
 }
 @Test public void transientAndUnknownFailuresKeepExistingBoundedRecovery() {
  for(Throwable error:new Throwable[]{new java.io.IOException("offline"),new java.util.concurrent.TimeoutException(),
   new ExecutionException(new D1DocumentStoreException(D1DocumentStoreException.Code.UNAVAILABLE,"outage")),
   new D1DocumentStoreException(D1DocumentStoreException.Code.ABORTED,"conflict"),
   new D1DocumentStoreException(D1DocumentStoreException.Code.RESOURCE_EXHAUSTED,"backoff"),
   new IllegalStateException("unknown")}){
   assertTrue(FanoutRetryPolicy.shouldRetry(error,0));assertTrue(FanoutRetryPolicy.shouldRetry(error,7));
   assertFalse(FanoutRetryPolicy.shouldRetry(error,8));
  }
 }
 @Test public void cyclicCauseChainTerminates() {
  Exception a=new Exception(),b=new Exception();a.initCause(b);b.initCause(a);
  assertTrue(FanoutRetryPolicy.shouldRetry(a,0));
 }
}

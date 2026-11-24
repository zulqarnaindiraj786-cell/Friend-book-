package com.friendbook.social.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class LoopTraceBufferTest {
 @Test public void releaseOptInExactWindowAndNoSecretValues(){
  LoopTraceBuffer b=new LoopTraceBuffer();assertFalse(b.begin(0,"ChatActivity","GET","/v1/posts/private-id/comments",true));
  b.start(100,60000);assertTrue(b.begin(100,"ChatActivity","GET","/v1/posts/private-id/comments",true));
  assertFalse(b.begin(60100,"ChatActivity","GET","/v1/posts/private-id/comments",true));
  String report=b.report(60100);assertTrue(report.contains("Attempts=1"));assertTrue(report.contains("/v1/posts/:id/comments"));assertFalse(report.contains("private-id"));
 }
 @Test public void tenThousandRequestsRemainBoundedAndCountsStayExact(){
  LoopTraceBuffer b=new LoopTraceBuffer();b.start(0,60000);
  for(int i=0;i<10000;i++){b.begin(i,"Screen"+i,"GET","/secret/"+i,true);b.end(i,200,1);}
  String r=b.report(10000);assertTrue(r.contains("Attempts=10000"));assertTrue(r.contains("overflow"));assertFalse(r.contains("/secret/"));assertTrue(r.length()<40000);
 }
 @Test public void resetStopErrorsAndUntrustedScreenAreSafe(){
  LoopTraceBuffer b=new LoopTraceBuffer();b.start(0,60000);b.begin(1,"user@example.com","GET","/private?token=secret",false);b.end(2,-1,1);b.stop();
  assertFalse(b.begin(3,"ChatActivity","GET","/v1/me",true));String r=b.report(3);assertTrue(r.contains("errorsInWindow=1"));assertFalse(r.contains("secret"));assertFalse(r.contains("example"));
  b.start(4,60000);assertTrue(b.report(5).contains("Attempts=0"));
 }
}

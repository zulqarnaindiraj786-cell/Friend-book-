package com.friendbook.social.core;
import okhttp3.*;
import org.junit.Test;
import static org.junit.Assert.*;
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class LoopTraceContextTest {
 @Test public void engagementRoutesAreClassifiedWithoutIds(){
  assertEquals("/v1/engagement/posts/:id/comments",LoopTraceBuffer.route("/v1/engagement/posts/private-id/comments"));
  assertEquals("/v1/engagement/reels/:id/reactions",LoopTraceBuffer.route("/v1/engagement/reels/private-id/reactions"));
 }
 @Test public void queryReportsOnlyKnownRootAndBoundedPageLimit(){
  Request q=new Request.Builder().url("https://example.com/v1/firestore/query").post(RequestBody.create("{\"parent\":\"chats/secret-id/messages\",\"limit\":10,\"filters\":[{\"value\":\"secret\"}],\"startAfter\":[\"private\"]}",MediaType.get("application/json"))).build();
  assertEquals("root=chats limit=10",LoopTrace.context(q));
  Request doc=new Request.Builder().url("https://example.com/v1/firestore/document?path=users%2Fprivate-id").build();
  assertEquals("root=users limit=none",LoopTrace.context(doc));
 }
 @Test public void malformedOrUnknownContextNeverExposesValues(){
  Request q=new Request.Builder().url("https://example.com/v1/firestore/query").post(RequestBody.create("not-json-secret",MediaType.get("application/json"))).build();
  assertEquals("",LoopTrace.context(q));
  Request doc=new Request.Builder().url("https://example.com/v1/firestore/document?path=private-root%2Fsecret").build();
  assertEquals("root=other limit=none",LoopTrace.context(doc));
 }
}

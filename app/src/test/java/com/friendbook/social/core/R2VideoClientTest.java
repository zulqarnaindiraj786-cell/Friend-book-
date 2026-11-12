package com.friendbook.social.core;
import java.io.ByteArrayInputStream;
import org.json.JSONObject;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class R2VideoClientTest {
 @Test public void onlyExactOwnerR2PlaybackUrlIsAccepted()throws Exception{
  String owner="a".repeat(64),id="b".repeat(64),url=R2VideoClient.ORIGIN+"/videos/"+owner+"/"+id+".mp4";
  JSONObject body=new JSONObject().put("url",url).put("provider","r2").put("retention","forever").put("resolution","480p").put("bytes",3);
  assertEquals(url,R2VideoClient.verifiedUrl(body,owner,id));
  for(String wrong:new String[]{"https://res.cloudinary.com/example/video/upload/asset.mp4",url+"?redirect=1",url.replace(owner,"c".repeat(64))}){
   body.put("url",wrong);try{R2VideoClient.verifiedUrl(body,owner,id);fail("Unsafe video URL");}catch(java.io.IOException expected){}
  }
 }
 @Test public void uploadUsesBearerAndBinaryBodyWithoutFollowingRedirects()throws Exception{
  try(MockWebServer server=new MockWebServer()){
   server.start();server.enqueue(new MockResponse().setBody("{\"saved\":true}"));
   JSONObject result=R2VideoClient.send(server.url("/v1/videos").url(),"POST","synthetic-token","a".repeat(64),new ByteArrayInputStream(new byte[]{1,2,3}),3,null);
   assertTrue(result.getBoolean("saved"));RecordedRequest request=server.takeRequest();
   assertEquals("Bearer synthetic-token",request.getHeader("Authorization"));assertEquals(3,request.getBodySize());assertEquals("3",request.getHeader("Content-Length"));
   server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/wrong"));
   try{R2VideoClient.send(server.url("/v1/videos").url(),"GET","synthetic-token","a".repeat(64),null,0,null);fail("Redirect accepted");}catch(java.io.IOException expected){}
   assertEquals("/v1/videos",server.takeRequest().getPath());assertNull(server.takeRequest(200,java.util.concurrent.TimeUnit.MILLISECONDS));
  }
 }
 @Test public void missingExistingVideoReturnsNullButOtherFailuresRemainErrors()throws Exception{
  try(MockWebServer server=new MockWebServer()){
   server.start();server.enqueue(new MockResponse().setResponseCode(404));
   assertNull(R2VideoClient.send(server.url("/v1/videos").url(),"GET","fixture","a".repeat(64),null,0,null));
   server.enqueue(new MockResponse().setResponseCode(503));
   try{R2VideoClient.send(server.url("/v1/videos").url(),"GET","fixture","a".repeat(64),null,0,null);fail("Fallback allowed");}catch(java.io.IOException expected){}
  }
 }
}

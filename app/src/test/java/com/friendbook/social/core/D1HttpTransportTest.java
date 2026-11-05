package com.friendbook.social.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual loopback HTTP, not a live Firebase identity or physical-device test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class D1HttpTransportTest {
 private HttpServer server;
 private URL url;
 private final AtomicInteger calls=new AtomicInteger();
 private final AtomicReference<String> method=new AtomicReference<>(),auth=new AtomicReference<>(),body=new AtomicReference<>();
 private volatile int status=200;private volatile String response="{\"ok\":true}";
 @Before public void start()throws Exception{
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{
   calls.incrementAndGet();method.set(exchange.getRequestMethod());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
   try(InputStream in=exchange.getRequestBody();ByteArrayOutputStream out=new ByteArrayOutputStream()){
    byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
    body.set(out.toString("UTF-8"));
   }
   if(status==302)exchange.getResponseHeaders().set("Location","/redirect-target");
   byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
   try(OutputStream out=exchange.getResponseBody()){out.write(bytes);}catch(IOException expectedWhenClientRejectsOversize){}
  });
  server.start();url=new URL("http://127.0.0.1:"+server.getAddress().getPort()+"/comments");
 }
 @After public void stop(){server.stop(0);}
 private void rejected()throws Exception{
  try{D1HttpTransport.send(url,"POST","synthetic-token",new JSONObject().put("text","fixture"));fail("Expected rejection");}
  catch(IOException expected){}
 }
 @Test public void sendsAuthenticatedUtf8JsonAndParsesResponse()throws Exception{
  JSONObject data=new JSONObject().put("text","سلام");
  assertTrue(D1HttpTransport.send(url,"PUT","synthetic-token",data).getBoolean("ok"));
  assertEquals("PUT",method.get());assertEquals("Bearer synthetic-token",auth.get());
  assertEquals("سلام",new JSONObject(body.get()).getString("text"));assertEquals(1,calls.get());
 }
 @Test public void failedWriteHasNoRetryOrFallback()throws Exception{status=503;rejected();assertEquals(1,calls.get());}
 @Test public void redirectsDoNotForwardCredentialsOrRepeatWrites()throws Exception{status=302;rejected();assertEquals(1,calls.get());}
 @Test public void oversizedRequestRejectedBeforeAnyNetworkWrite()throws Exception{
  char[] chars=new char[32769];java.util.Arrays.fill(chars,'a');
  try{D1HttpTransport.send(url,"POST","synthetic-token",new JSONObject().put("text",new String(chars)));fail("Expected size bound");}
  catch(IOException expected){}assertEquals(0,calls.get());
 }
 @Test public void oversizedResponseRejected()throws Exception{
  char[] chars=new char[1048577];java.util.Arrays.fill(chars,'a');response=new String(chars);rejected();assertEquals(1,calls.get());
 }
 @Test public void malformedSuccessIsNotAcceptedAsJson()throws Exception{
  response="<html>not an API response</html>";
  try{D1HttpTransport.send(url,"GET","synthetic-token",null);fail("Expected JSON rejection");}
  catch(org.json.JSONException expected){}assertEquals(1,calls.get());
 }
}

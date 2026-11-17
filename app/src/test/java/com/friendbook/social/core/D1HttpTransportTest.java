package com.friendbook.social.core;

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
 private ServerSocket server;
 private Thread responder;
 private volatile boolean stopped;
 private URL url;
 private final AtomicInteger calls=new AtomicInteger();
 private final AtomicReference<String> method=new AtomicReference<>(),auth=new AtomicReference<>(),body=new AtomicReference<>();
 private volatile int status=200;private volatile String response="{\"ok\":true}";
 @Before public void start()throws Exception{
  server=new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"));
  responder=new Thread(()->{
   while(!stopped){
    try(Socket socket=server.accept()){
     socket.setSoTimeout(5000);
     InputStream in=socket.getInputStream();String request=line(in);if(request==null)continue;
     calls.incrementAndGet();method.set(request.split(" ")[0]);
     int length=0;String header;
     while((header=line(in))!=null&&!header.isEmpty()){
      int colon=header.indexOf(':');if(colon<0)continue;
      String key=header.substring(0,colon),value=header.substring(colon+1).trim();
      if(key.equalsIgnoreCase("Authorization"))auth.set(value);
      if(key.equalsIgnoreCase("Content-Length"))length=Integer.parseInt(value);
     }
     ByteArrayOutputStream received=new ByteArrayOutputStream();
     for(int i=0;i<length;i++){int next=in.read();if(next<0)throw new EOFException();received.write(next);}
     body.set(received.toString("UTF-8"));
     byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
     String headers="HTTP/1.1 "+status+" Fixture\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+
      "\r\nConnection: close\r\n"+(status==302?"Location: /redirect-target\r\n":"")+"\r\n";
     OutputStream out=socket.getOutputStream();out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }catch(IOException expectedOnCloseOrOversize){}
   }
  });
  responder.setDaemon(true);responder.start();
  url=new URL("http://127.0.0.1:"+server.getLocalPort()+"/comments");
 }
 private static String line(InputStream in)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();int c;
  while((c=in.read())!=-1){if(c=='\n')return out.toString("US-ASCII").replace("\r","");out.write(c);}
  return out.size()==0?null:out.toString("US-ASCII");
 }
 @After public void stop()throws Exception{stopped=true;server.close();responder.join(6000);assertFalse(responder.isAlive());}
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
  // Reported as an IOException: callers only ever handle IOException, so a bare JSONException
  // used to escape them and crash the screen instead of showing a retry.
  try{D1HttpTransport.send(url,"GET","synthetic-token",null);fail("Expected JSON rejection");}
  catch(IOException expected){}assertEquals(1,calls.get());
 }
 @Test public void redirectIsNeverFollowedSoCredentialsStayPut()throws Exception{
  // OkHttp follows redirects by default, which would resend both the body and the bearer token
  // to whatever Location names. The transport must refuse instead.
  assertEquals(false,D1HttpTransport.followsRedirects());
 }
}

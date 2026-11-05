package com.friendbook.social.core;

import android.os.Handler;
import android.os.Looper;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import java.util.concurrent.*;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Prepared Android/JVM loopback tests. Synthetic tokens only, never production auth. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class CloudPresenceSocketTest {
 private Handler immediateHandler(){
  Handler handler=mock(Handler.class);when(handler.getLooper()).thenReturn(Looper.getMainLooper());
  when(handler.post(any(Runnable.class))).thenAnswer(call->{((Runnable)call.getArgument(0)).run();return true;});
  when(handler.postDelayed(any(Runnable.class),anyLong())).thenReturn(true);
  return handler;
 }
 private FirebaseAuth auth(){
  FirebaseAuth auth=mock(FirebaseAuth.class);FirebaseUser user=mock(FirebaseUser.class);GetTokenResult result=mock(GetTokenResult.class);
  when(auth.getUid()).thenReturn("fixture");when(auth.getCurrentUser()).thenReturn(user);when(user.getUid()).thenReturn("fixture");
  when(result.getToken()).thenReturn("synthetic-token");when(result.getExpirationTimestamp()).thenReturn(System.currentTimeMillis()/1000+3600);
  when(user.getIdToken(false)).thenReturn(Tasks.forResult(result));return auth;
 }
 @Test public void disabledGateNeverStartsAuthOrNetwork()throws Exception{
  FirebaseAuth auth=auth();
  try(MockWebServer server=new MockWebServer()){
   server.start();CloudPresenceSocket socket=new CloudPresenceSocket(auth,immediateHandler(),new OkHttpClient(),false,server.url("/v1/presence/socket").toString());
   socket.foreground(true);socket.background();socket.close();
   verify(auth,never()).addAuthStateListener(any());verify(auth,never()).getCurrentUser();
   assertNull(server.takeRequest(150,TimeUnit.MILLISECONDS));
  }
 }
 @Test public void realHandshakeUsesBearerHeaderAndBackgroundSendsOfflineThenCloses()throws Exception{
  BlockingQueue<String> messages=new LinkedBlockingQueue<>();CountDownLatch closed=new CountDownLatch(1);
  try(MockWebServer server=new MockWebServer()){
   server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener(){
    @Override public void onMessage(WebSocket ws,String text){messages.add(text);}
    @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,null);closed.countDown();}
   }));server.start();
   CloudPresenceSocket socket=new CloudPresenceSocket(auth(),immediateHandler(),new OkHttpClient.Builder().followRedirects(false).build(),true,server.url("/v1/presence/socket").toString());
   try{
    socket.foreground(true);RecordedRequest request=server.takeRequest(5,TimeUnit.SECONDS);assertNotNull(request);
    assertEquals("Bearer synthetic-token",request.getHeader("Authorization"));assertEquals("/v1/presence/socket",request.getPath());
    assertEquals("{\"type\":\"active\"}",messages.poll(5,TimeUnit.SECONDS));
    socket.background();assertEquals("{\"type\":\"offline\"}",messages.poll(5,TimeUnit.SECONDS));assertTrue(closed.await(5,TimeUnit.SECONDS));
   }finally{socket.close();}
  }
 }
 @Test public void redirectCannotForwardBearerToAnotherEndpoint()throws Exception{
  try(MockWebServer server=new MockWebServer()){
   server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location","/other"));server.start();
   CloudPresenceSocket socket=new CloudPresenceSocket(auth(),immediateHandler(),new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),true,server.url("/v1/presence/socket").toString());
   try{socket.foreground(true);assertNotNull(server.takeRequest(5,TimeUnit.SECONDS));assertNull(server.takeRequest(300,TimeUnit.MILLISECONDS));}
   finally{socket.close();}
  }
 }
}

package com.friendbook.social.push;

import androidx.work.*;
import com.friendbook.social.core.Fb;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.Tasks;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class PushFanoutDemandTest {
 @Test public void actualWorkerStopsDeniedLookupsButRetriesOutages() throws Exception {
  for(D1DocumentStoreException.Code code:new D1DocumentStoreException.Code[]{
    D1DocumentStoreException.Code.PERMISSION_DENIED,D1DocumentStoreException.Code.NOT_FOUND,
    D1DocumentStoreException.Code.UNAVAILABLE}){
   java.util.concurrent.ExecutorService executor=Executors.newSingleThreadExecutor();
   try {
    ListenableWorker.Result result=executor.submit(()->{
     try(MockedStatic<Fb> fb=mockStatic(Fb.class)){
      fb.when(Fb::uid).thenReturn("actor");
      D1DocumentStore store=mock(D1DocumentStore.class);CollectionReference chats=mock(CollectionReference.class);
      DocumentReference chat=mock(DocumentReference.class);fb.when(Fb::db).thenReturn(store);
      when(store.collection("chats")).thenReturn(chats);when(chats.document("room")).thenReturn(chat);
      when(chat.get(Source.SERVER)).thenReturn(Tasks.forException(new D1DocumentStoreException(code,"lookup failed")));
      WorkerParameters parameters=mock(WorkerParameters.class);
      when(parameters.getInputData()).thenReturn(new Data.Builder().putString("uid","actor").putString("kind","chat").putString("document","room").build());
      when(parameters.getRunAttemptCount()).thenReturn(0);
      PushFanoutWorker worker=new PushFanoutWorker(RuntimeEnvironment.getApplication(),parameters);
      ListenableWorker.Result outcome=worker.doWork();verify(chat,times(1)).get(Source.SERVER);return outcome;
     }
    }).get(10,TimeUnit.SECONDS);
    assertEquals(code.toString(),code==D1DocumentStoreException.Code.UNAVAILABLE?ListenableWorker.Result.retry():ListenableWorker.Result.failure(),result);
   } finally {executor.shutdownNow();}
  }
 }
}

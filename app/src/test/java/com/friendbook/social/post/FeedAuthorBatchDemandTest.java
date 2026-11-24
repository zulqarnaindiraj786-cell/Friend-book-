package com.friendbook.social.post;

import android.app.Activity;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.friendbook.social.models.Models;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FeedAuthorBatchDemandTest {
 @Test public void tenMissingAuthorsUseOneQueryAndRepeatedSubmissionDoesNotRetry() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class);
      MockedStatic<AdsConfig> ads=mockStatic(AdsConfig.class);MockedStatic<ConsentManager> consent=mockStatic(ConsentManager.class)) {
   Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
   D1DocumentStore db=mock(D1DocumentStore.class);CollectionReference users=mock(CollectionReference.class);Query bounded=mock(Query.class);
   fb.when(Fb::uid).thenReturn("batch-actor");fb.when(Fb::db).thenReturn(db);
   when(db.collection("users")).thenReturn(users);
   when(users.whereIn(any(FieldPath.class),anyList())).thenReturn(bounded);
   when(bounded.limit(10)).thenReturn(bounded);
   when(bounded.get()).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
   FeedAdapter adapter=new FeedAdapter(activity,null);
   List<Models.Post> page=new ArrayList<>();for(int i=0;i<10;i++){Models.Post p=new Models.Post();p.id="p"+i;p.authorId="u"+i;p.authorName="";page.add(p);}
   java.lang.reflect.Method hydrate=FeedAdapter.class.getDeclaredMethod("hydrateMissingAuthors",List.class);hydrate.setAccessible(true);
   hydrate.invoke(adapter,page);Shadows.shadowOf(Looper.getMainLooper()).idle();
   for(int i=0;i<100;i++)hydrate.invoke(adapter,page);
   verify(bounded,times(1)).get();
   verify(users,times(1)).whereIn(any(FieldPath.class),argThat(ids->ids.size()==10));
   fb.verify(()->Fb.getUserFresh(anyString()),never());
   adapter.resetAuthorLookupAttempts();hydrate.invoke(adapter,page);verify(bounded,times(2)).get();
  }
 }
}

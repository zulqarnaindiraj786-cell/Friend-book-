package com.friendbook.social.post;

import android.app.*;
import android.os.Looper;
import com.friendbook.social.core.*;
import com.friendbook.social.d1.documents.*;
import com.google.android.gms.tasks.Tasks;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.mockito.Mockito.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class ReactorProfileBatchDemandTest {
 @Test public void repeatedRenderCannotFetchTenMissingPeopleIndividuallyOrRetryThem() throws Exception {
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<FastCache> cache=mockStatic(FastCache.class)) {
   Activity activity=Robolectric.buildActivity(Activity.class).setup().get();AlertDialog dialog=new AlertDialog.Builder(activity).create();dialog.show();
   D1DocumentStore db=mock(D1DocumentStore.class);CollectionReference users=mock(CollectionReference.class);Query bounded=mock(Query.class);
   fb.when(Fb::uid).thenReturn("reactor-batch-actor");fb.when(Fb::db).thenReturn(db);when(db.collection("users")).thenReturn(users);
   when(users.whereIn(any(FieldPath.class),anyList())).thenReturn(bounded);when(bounded.limit(10)).thenReturn(bounded);
   when(bounded.get()).thenReturn(Tasks.forResult(new QuerySnapshot(Collections.emptyList(),null,new SnapshotMetadata(false,false))));
   List<Reactions.ReactorEntry> page=new ArrayList<>();for(int i=0;i<10;i++){Reactions.ReactorEntry e=new Reactions.ReactorEntry();e.uid="u"+i;page.add(e);}
   java.lang.reflect.Method hydrate=Reactions.class.getDeclaredMethod("hydrateReactorProfiles",Activity.class,AlertDialog.class,List.class,Runnable.class);hydrate.setAccessible(true);
   Runnable render=mock(Runnable.class);
   for(int i=0;i<100;i++){hydrate.invoke(null,activity,dialog,page,render);Shadows.shadowOf(Looper.getMainLooper()).idle();}
   verify(bounded,times(1)).get();verify(users,times(1)).whereIn(any(FieldPath.class),argThat(ids->ids.size()==10));
   fb.verify(()->Fb.getUser(anyString()),never());verifyNoInteractions(render);
   dialog.dismiss();hydrate.invoke(null,activity,dialog,Collections.singletonList(new Reactions.ReactorEntry()),render);verify(bounded,times(1)).get();
  }
 }
}

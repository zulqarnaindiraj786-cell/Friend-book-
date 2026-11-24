package com.friendbook.social.post;
import android.app.Activity;
import com.friendbook.social.core.*;
import com.friendbook.social.models.Models;
import java.util.*;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.robolectric.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class FeedReactionFeedbackTest {
 @Test public void hundredReactionObserverUpdatesArePayloadOnlyWithoutReadsOrReorder()throws Exception{
  try(MockedStatic<Fb> fb=mockStatic(Fb.class);MockedStatic<AdsConfig> ads=mockStatic(AdsConfig.class);MockedStatic<ConsentManager> consent=mockStatic(ConsentManager.class)){
   fb.when(Fb::uid).thenReturn("reaction-actor");FeedAdapter adapter=new FeedAdapter(Robolectric.buildActivity(Activity.class).setup().get(),null);
   Models.Post first=new Models.Post();first.id="first";Models.Post second=new Models.Post();second.id="second";
   java.lang.reflect.Field posts=FeedAdapter.class.getDeclaredField("posts");posts.setAccessible(true);((List<Models.Post>)posts.get(adapter)).addAll(Arrays.asList(first,second));
   int[] payloads={0},full={0};adapter.registerAdapterDataObserver(new androidx.recyclerview.widget.RecyclerView.AdapterDataObserver(){
    @Override public void onChanged(){full[0]++;}
    @Override public void onItemRangeChanged(int start,int count,Object payload){if("REACTION_ONLY".equals(payload))payloads[0]+=count;}
   });
   for(int i=0;i<100;i++)adapter.onFastReactionUpdate("second","like",i);
   assertSame(first,adapter.getPosts().get(0));assertSame(second,adapter.getPosts().get(1));assertEquals(99,second.likes);
   assertEquals(100,payloads[0]);assertEquals(0,full[0]);fb.verify(Fb::db,never());fb.verify(()->Fb.getUserFresh(anyString()),never());
  }
 }
}

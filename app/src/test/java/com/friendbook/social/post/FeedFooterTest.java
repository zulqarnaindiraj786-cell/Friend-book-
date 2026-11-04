package com.friendbook.social.post;
import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import com.friendbook.social.models.Models;
import com.friendbook.social.story.StoriesAdapter;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=28)
public class FeedFooterTest {
    private static class Host implements FeedAdapter.Host {
        boolean loading,more=true,error;int calls;
        public void openComments(Models.Post p){} public void openProfile(String uid){}
        public void editPost(Models.Post p){} public void createStory(){} public void openStoryGroup(StoriesAdapter.Group g){}
        public void loadMore(){calls++;} public boolean hasMore(){return more;}
        public boolean automaticPaging(){return true;} public boolean isLoadingMore(){return loading;} public boolean hasPageError(){return error;}
    }
    @Test public void footerFollowsRealRequestStateAndOffersRetryOnlyOnFailure(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();Host host=new Host();
        FeedAdapter adapter=new FeedAdapter(activity,host);
        int position=adapter.getItemCount()-1;
        FeedAdapter.FooterVH footer=(FeedAdapter.FooterVH)adapter.onCreateViewHolder(new LinearLayout(activity),adapter.getItemViewType(position));
        adapter.onBindViewHolder(footer,position);
        assertEquals(View.GONE,footer.more.getVisibility());assertEquals(View.GONE,footer.spinner.getVisibility());
        host.loading=true;adapter.onBindViewHolder(footer,position);
        assertEquals(View.VISIBLE,footer.spinner.getVisibility());assertEquals(View.GONE,footer.end.getVisibility());
        host.loading=false;host.error=true;adapter.onBindViewHolder(footer,position);
        assertEquals(View.GONE,footer.spinner.getVisibility());assertEquals(View.VISIBLE,footer.more.getVisibility());
        footer.more.performClick();assertEquals(1,host.calls);
        host.error=false;host.more=false;adapter.onBindViewHolder(footer,position);
        assertEquals(View.GONE,footer.more.getVisibility());assertEquals(View.VISIBLE,footer.end.getVisibility());activity.finish();
    }
}

package com.friendbook.social.friends;
import android.app.Activity;
import android.app.Application;
import android.view.View;
import android.widget.LinearLayout;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,application=Application.class,sdk=28)
public class PeopleControlTest {
    @Test public void inlineHeadersAndProgressRecycleWithoutStaleSpinnerOrProfileClick(){
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        UsersAdapter adapter=new UsersAdapter(activity,null);UsersAdapter.Row row=new UsersAdapter.Row();
        row.heading="People you may know";row.loading=true;adapter.setData(Arrays.asList(row));
        UsersAdapter.VH holder=adapter.onCreateViewHolder(new LinearLayout(activity),adapter.getItemViewType(0));
        adapter.onBindViewHolder(holder,0);
        assertEquals("People you may know",holder.name.getText().toString());assertEquals(View.VISIBLE,holder.progress.getVisibility());
        row.loading=false;row.heading="";adapter.onBindViewHolder(holder,0);
        assertEquals(View.GONE,holder.progress.getVisibility());assertEquals(View.GONE,holder.name.getVisibility());
        assertFalse(holder.itemView.hasOnClickListeners());activity.finish();
    }
}

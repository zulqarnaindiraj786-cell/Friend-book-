package com.friendbook.social.post;
import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class)
public class HistoricalReactionNoticeTest {
 @Test public void unknownHistoricalActorsAreExplainedNotFabricatedOrCalledEmpty(){
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
  TextView note=new TextView(activity);LinearLayout filter=new LinearLayout(activity),rows=new LinearLayout(activity);
  TextView empty=new TextView(activity);empty.setText("No reactions yet — be the first to like!");rows.addView(empty);
  D1Reactions.historicalNotice(activity,note,filter,rows,36,false);
  assertEquals(View.VISIBLE,note.getVisibility());assertTrue(note.getText().toString().contains("36 historical reactions"));
  assertEquals(View.GONE,filter.getVisibility());assertEquals(1,rows.getChildCount());
  assertTrue(((TextView)rows.getChildAt(0)).getText().toString().contains("No actor identities were recorded"));
 }
 @Test public void knownActorsStayVisibleBesideHistoricalNotice(){
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
  TextView note=new TextView(activity);LinearLayout filter=new LinearLayout(activity),rows=new LinearLayout(activity);
  TextView known=new TextView(activity);known.setText("Identified actor");rows.addView(known);
  D1Reactions.historicalNotice(activity,note,filter,rows,3,true);
  assertEquals(View.VISIBLE,filter.getVisibility());assertSame(known,rows.getChildAt(0));
 }
 @Test public void normalZeroHistoricalResponseKeepsExistingRenderer(){
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
  TextView note=new TextView(activity);LinearLayout filter=new LinearLayout(activity),rows=new LinearLayout(activity);
  TextView known=new TextView(activity);rows.addView(known);
  D1Reactions.historicalNotice(activity,note,filter,rows,0,false);
  assertEquals(View.GONE,note.getVisibility());assertEquals(View.VISIBLE,filter.getVisibility());assertSame(known,rows.getChildAt(0));
 }
}

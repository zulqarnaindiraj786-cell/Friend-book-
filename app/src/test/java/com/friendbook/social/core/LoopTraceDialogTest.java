package com.friendbook.social.core;
import android.app.*;
import android.view.*;
import android.widget.Button;
import org.junit.Test;
import org.robolectric.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk=28,application=android.app.Application.class,manifest=org.robolectric.annotation.Config.NONE)
public class LoopTraceDialogTest {
 private Button find(View view,String label){
  if(view instanceof Button && label.contentEquals(((Button)view).getText()))return (Button)view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
   Button result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;
  }
  return null;
 }
 @Test public void startStopAndShareControlsExistAndClicksActuallyControlCapture(){
  Activity activity=Robolectric.buildActivity(Activity.class).setup().get();LoopTrace.show(activity);
  AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();View root=dialog.getWindow().getDecorView();
  for(String label:new String[]{"Start 1-minute trace","Start 15-minute trace","Stop trace","Stop & Share report","View counts / last report","Delete trace"}){
   Button button=find(root,label);assertNotNull(label,button);assertEquals(View.VISIBLE,button.getVisibility());assertTrue(button.isEnabled());
  }
  find(root,"Start 1-minute trace").performClick();
  okhttp3.Request request=new okhttp3.Request.Builder().url("https://example.com/v1/firestore/query").build();
  long ticket=LoopTrace.begin(request);assertTrue(ticket>=0);LoopTrace.end(ticket,200,10);
  find(root,"Stop trace").performClick();assertEquals(-1L,LoopTrace.begin(request));
  String report=activity.getSharedPreferences("local_loop_trace",0).getString("report","");assertTrue(report.contains("Attempts=1"));
  find(root,"Delete trace").performClick();assertFalse(activity.getSharedPreferences("local_loop_trace",0).contains("report"));
  dialog.dismiss();activity.finish();
 }
}

package com.friendbook.social.core;
import android.app.Activity;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.widget.EditText;
import android.widget.TextView;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class BulkMentionUiTest {
    @Test public void selectedBulkTagsPersistAndDoNotOpenFakeProfiles() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        for(String tag:new String[]{"followers","highlight"}) {
            EditText input=new EditText(activity); String label="@"+tag;
            Map<String,Object> mention=new HashMap<>();
            mention.put("uid","bulk:"+tag);mention.put("label",label);mention.put("start",0);mention.put("end",label.length());
            Mentions.restore(input,label,Collections.singletonList(mention));
            List<Map<String,Object>> saved=Mentions.capture(input); assertTrue(Mentions.hasBulk(saved));
            Spanned display=(Spanned)Mentions.styled(label,saved);
            ClickableSpan[] links=display.getSpans(0,display.length(),ClickableSpan.class);assertEquals(1,links.length);
            links[0].onClick(new TextView(activity));assertNull(shadowOf(activity).getNextStartedActivity());
        }
    }
    @Test public void unselectedPlainTagDoesNotRequestBroadcast() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();EditText input=new EditText(a);
        input.setText("@followers @highlight");assertFalse(Mentions.hasBulk(Mentions.capture(input)));
    }
}

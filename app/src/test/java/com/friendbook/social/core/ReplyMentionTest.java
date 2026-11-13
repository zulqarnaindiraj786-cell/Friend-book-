package com.friendbook.social.core;

import android.app.Activity;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, application=android.app.Application.class)
public class ReplyMentionTest {
    private EditText input() { return new EditText(Robolectric.buildActivity(Activity.class).setup().get()); }
    @Test public void replyIncludesRealAuthorNameAndUidInPostedBody() {
        EditText input=input(); input.setText("Thanks!");
        Mentions.setReplyTarget(input,"comment-author-uid","Ali Khan");
        assertEquals("@Ali Khan Thanks!",input.getText().toString());
        List<Map<String,Object>> saved=Mentions.capture(input);
        assertEquals(1,saved.size()); assertEquals("comment-author-uid",saved.get(0).get("uid"));
        Spanned rendered=(Spanned)Mentions.styled(input.getText().toString(),saved);
        assertEquals(1,rendered.getSpans(0,rendered.length(),ClickableSpan.class).length);
    }
    @Test public void repeatedReplyDoesNotStackAndSwitchKeepsDraft() {
        EditText input=input(); input.setText("My answer");
        Mentions.setReplyTarget(input,"ali","Ali"); Mentions.setReplyTarget(input,"ali","Ali");
        assertEquals("@Ali My answer",input.getText().toString());
        Mentions.setReplyTarget(input,"sara","Sara");
        assertEquals("@Sara My answer",input.getText().toString());
        assertEquals("sara",Mentions.capture(input).get(0).get("uid"));
        Mentions.clearReplyTarget(input); assertEquals("My answer",input.getText().toString());
    }
    @Test public void editedPrefixIsNotDestructivelyRemoved() {
        EditText input=input(); Mentions.setReplyTarget(input,"ali","Ali");
        input.getText().replace(1,4,"Someone"); String draft=input.getText().toString();
        Mentions.clearReplyTarget(input); assertEquals(draft,input.getText().toString());
        assertTrue(Mentions.capture(input).isEmpty());
    }
    @Test public void manualMentionsSurviveCancellation() {
        EditText input=input(); Map<String,Object> identity=new HashMap<>();
        identity.put("uid","sara"); identity.put("label","@Sara"); identity.put("start",0); identity.put("end",5);
        Mentions.restore(input,"@Sara hello",Collections.singletonList(identity));
        Mentions.setReplyTarget(input,"ali","Ali"); Mentions.clearReplyTarget(input);
        assertEquals("@Sara hello",input.getText().toString());
        assertEquals("sara",Mentions.capture(input).get(0).get("uid"));
    }
}

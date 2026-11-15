package com.friendbook.social.core;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.text.*;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = android.app.Application.class)
public class LinkUtilsTest {
    private TextView view() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        return new TextView(a);
    }
    private URLSpan[] links(TextView view) {
        Spanned text = (Spanned) view.getText();
        return text.getSpans(0, text.length(), URLSpan.class);
    }
    @Test public void bindsAfterEmptyCreationAndRecyclesWithoutStaleUrls() {
        TextView v = view(); LinkUtils.makeLinksClickable(v);
        v.setText("Visit https://play.google.com and https://google.com");
        assertEquals(2, links(v).length);
        assertEquals(Color.rgb(26, 115, 232), v.getLinkTextColors().getDefaultColor());
        v.setText("No link here"); assertEquals(0, links(v).length);
        v.setText("Visit www.google.com"); assertEquals(1, links(v).length);
    }
    @Test public void repeatedCallsKeepOneWatcherAndOneSpan() {
        TextView v = view(); v.setText("https://google.com");
        LinkUtils.makeLinksClickable(v);
        Object watcher = v.getTag(com.friendbook.social.R.id.web_link_watcher);
        LinkUtils.makeLinksClickable(v);
        assertSame(watcher, v.getTag(com.friendbook.social.R.id.web_link_watcher));
        assertEquals(1, links(v).length);
    }
    @Test public void preservesExistingHashtagActionAndOpensBrowser() {
        TextView v = view();
        SpannableString text = new SpannableString("#hello https://google.com");
        ClickableSpan hashtag = new ClickableSpan() { public void onClick(View view) {} };
        text.setSpan(hashtag, 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(text); LinkUtils.makeLinksClickable(v);
        assertTrue(((Spanned)v.getText()).getSpanStart(hashtag) >= 0);
        links(v)[0].onClick(v);
        Intent opened = shadowOf((Activity)v.getContext()).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("https://google.com", opened.getDataString());
    }
    @Test public void longUrlIsNotTruncatedOrChanged() {
        TextView v = view(); String url = "https://example.com/path/" + new String(new char[250]).replace('\0', 'a') + "?q=one&x=two";
        v.setText(url); LinkUtils.makeLinksClickable(v);
        assertEquals(url, v.getText().toString());
        assertEquals(url, links(v)[0].getURL());
        assertEquals(Layout.BREAK_STRATEGY_SIMPLE, v.getBreakStrategy());
    }
    @Test public void doesNotMakePlainLabelsStealParentClicks() {
        TextView v = view(); assertFalse(v.isClickable());
        LinkUtils.makeLinksClickable(v);
        v.setText("Ordinary text https://google.com");
        assertFalse(v.isClickable());
        v.setOnClickListener(w -> {});
        v.setText("Caption https://google.com");
        LinkUtils.makeLinksClickable(v);
        assertTrue(v.isClickable());
    }
}

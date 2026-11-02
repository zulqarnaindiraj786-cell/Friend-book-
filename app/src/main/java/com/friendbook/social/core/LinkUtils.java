package com.friendbook.social.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.text.*;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.URLSpan;
import android.text.util.Linkify;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import com.friendbook.social.R;

/** Call once on creation OR after binding. Future setText() calls are automatically re-linked. */
public final class LinkUtils {
    private LinkUtils() {}
    public static void makeLinksClickable(TextView view) {
        if (view == null || view instanceof EditText) return;
        Interaction original = new Interaction(view);
        view.setLinkTextColor(Color.rgb(26, 115, 232));
        view.setAutoLinkMask(Linkify.WEB_URLS);
        view.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if (view.getTag(R.id.web_link_watcher) == null) {
            TextWatcher watcher = new TextWatcher() {
                private Interaction interaction = original;
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { interaction = new Interaction(view); }
                public void onTextChanged(CharSequence s, int start, int before, int count) {}
                public void afterTextChanged(Editable text) {
                    link(text); view.setMovementMethod(WebMovement.INSTANCE); interaction.restore(view);
                }
            };
            view.setTag(R.id.web_link_watcher, watcher);
            view.addTextChangedListener(watcher);
        }
        // Do not flatten Spannable text: existing hashtag/mention ClickableSpans must survive.
        Spannable text = view.getText() instanceof Spannable ? (Spannable) view.getText()
                : new SpannableString(view.getText());
        link(text);
        if (view.getText() != text) view.setText(text, TextView.BufferType.SPANNABLE);
        view.setMovementMethod(WebMovement.INSTANCE);
        view.setLinksClickable(true);
        original.restore(view);
    }
    private static final class Interaction {
        final boolean clickable, longClickable, focusable;
        Interaction(TextView v) { clickable = v.isClickable(); longClickable = v.isLongClickable(); focusable = v.isFocusable(); }
        void restore(TextView v) { v.setClickable(clickable); v.setLongClickable(longClickable); v.setFocusable(focusable); }
    }
    private static void link(Spannable text) {
        Linkify.addLinks(text, Linkify.WEB_URLS);
        for (URLSpan span : text.getSpans(0, text.length(), URLSpan.class)) {
            int start = text.getSpanStart(span), end = text.getSpanEnd(span), flags = text.getSpanFlags(span);
            text.removeSpan(span);
            String url = span.getURL();
            if (WebUrlPolicy.isWebUrl(url)) text.setSpan(new BrowserSpan(url), start, end, flags);
        }
    }
    private static final class BrowserSpan extends URLSpan {
        BrowserSpan(String url) { super(url); }
        @Override public void onClick(View widget) {
            if (!WebUrlPolicy.isWebUrl(getURL())) return;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(getURL()));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            if (!(widget.getContext() instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { widget.getContext().startActivity(intent); }
            catch (ActivityNotFoundException | SecurityException e) { Ui.toast(widget.getContext(), "No browser available to open this link"); }
        }
    }
    /** Consume only span taps; normal caption taps, long press, scrolling and parent actions survive. */
    private static final class WebMovement extends LinkMovementMethod {
        static final WebMovement INSTANCE = new WebMovement();
        @Override public boolean onTouchEvent(TextView view, Spannable text, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_CANCEL) { Selection.removeSelection(text); return false; }
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return false;
            Layout layout = view.getLayout();
            if (layout == null) return false;
            float x = event.getX() - view.getTotalPaddingLeft() + view.getScrollX();
            float y = event.getY() - view.getTotalPaddingTop() + view.getScrollY();
            if (y < 0 || y >= layout.getHeight()) return false;
            int line = layout.getLineForVertical((int) y);
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return false;
            int offset = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = text.getSpans(offset, offset, ClickableSpan.class);
            if (spans.length == 0) { Selection.removeSelection(text); return false; }
            ClickableSpan span = spans[0];
            for (ClickableSpan candidate : spans) if (candidate instanceof BrowserSpan) { span = candidate; break; }
            int start = text.getSpanStart(span), end = text.getSpanEnd(span);
            if (action == MotionEvent.ACTION_DOWN) Selection.setSelection(text, start, end);
            else {
                boolean same = Selection.getSelectionStart(text) == start && Selection.getSelectionEnd(text) == end;
                Selection.removeSelection(text);
                if (same) span.onClick(view);
            }
            return true;
        }
    }
}

package com.example.frienbook.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;

/** Programmatic UI toolkit with Facebook-Lite look, dark mode and font scaling. */
public class Ui {

    public static final int BLUE = 0xFF1877F2;
    public static final int BLUE_DARK = 0xFF0E5FC7;
    public static final int GREEN = 0xFF31A24C;
    public static final int RED = 0xFFE41E3F;
    public static final int ORANGE = 0xFFF7B928;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    // ---- dynamic colors ----
    public static boolean isDark(Context c) { return Prefs.dark(c); }
    public static int bg(Context c)    { return isDark(c) ? 0xFF18191A : 0xFFF0F2F5; }
    public static int card(Context c)  { return isDark(c) ? 0xFF242526 : 0xFFFFFFFF; }
    public static int txt(Context c)   { return isDark(c) ? 0xFFE4E6EB : 0xFF050505; }
    public static int sub(Context c)   { return isDark(c) ? 0xFFB0B3B8 : 0xFF65676B; }
    public static int div(Context c)   { return isDark(c) ? 0xFF3E4042 : 0xFFCED0D4; }
    public static int inputBg(Context c){ return isDark(c) ? 0xFF3A3B3C : 0xFFF0F2F5; }
    public static int unread(Context c){ return isDark(c) ? 0xFF263951 : 0xFFE7F3FF; }

    // ---- drawables ----
    public static GradientDrawable round(Context c, int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, rDp));
        return g;
    }

    public static GradientDrawable roundStroke(Context c, int color, float rDp, int stroke, float wDp) {
        GradientDrawable g = round(c, color, rDp);
        g.setStroke(dp(c, wDp), stroke);
        return g;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    // ---- text ----
    public static TextView tv(Context c, String text, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp * Prefs.fontScale(c));
        t.setTextColor(color);
        t.setIncludeFontPadding(true);
        return t;
    }

    public static TextView tvBold(Context c, String text, float sp, int color) {
        TextView t = tv(c, text, sp, color);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    // ---- buttons ----
    public static TextView btn(Context c, String label) {
        TextView b = tvBold(c, label, 15, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 40));
        b.setBackground(round(c, BLUE, 8));
        return b;
    }

    public static TextView btnGrey(Context c, String label) {
        TextView b = tvBold(c, label, 15, txt(c));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setMinHeight(dp(c, 40));
        b.setBackground(round(c, inputBg(c), 8));
        return b;
    }

    public static TextView btnOutline(Context c, String label, int color) {
        TextView b = tvBold(c, label, 14, color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        b.setMinHeight(dp(c, 36));
        b.setBackground(roundStroke(c, Color.TRANSPARENT, 8, color, 1.5f));
        return b;
    }

    // ---- inputs ----
    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(sub(c));
        e.setTextColor(txt(c));
        e.setTextSize(15 * Prefs.fontScale(c));
        e.setSingleLine(true);
        e.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        e.setBackground(round(c, inputBg(c), 8));
        return e;
    }

    public static EditText multi(Context c, String hint, int minLines) {
        EditText e = input(c, hint);
        e.setSingleLine(false);
        e.setMinLines(minLines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    // ---- images ----
    public static void avatar(Context c, ImageView iv, String url) {
        Drawable ph = circle(inputBg(c));
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(ph);
            return;
        }
        Glide.with(c).load(url).circleCrop()
                .placeholder(ph).error(ph)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(iv);
    }

    public static void img(Context c, ImageView iv, String url) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        if (Prefs.dataSaver(c)) {
            // Data Saver mode (classic Lite feature): load a low-res progressive thumbnail.
            Glide.with(c).load(url).thumbnail(0.08f).centerCrop()
                    .placeholder(ph).error(ph).into(iv);
        } else {
            Glide.with(c).load(url).centerCrop().placeholder(ph).error(ph).into(iv);
        }
    }

    public static void imgFit(Context c, ImageView iv, String url) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        Glide.with(c).load(url).fitCenter().placeholder(ph).error(ph).into(iv);
    }

    // ---- layout helpers ----
    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(div(c));
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.5f))));
        return v;
    }

    public static void pad(View v, int l, int t, int r, int b) {
        Context c = v.getContext();
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    public static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }
    public static LinearLayout.LayoutParams lpw(int h) { return new LinearLayout.LayoutParams(-1, h); }
    public static LinearLayout.LayoutParams lpm(int w, int h, int m, Context c) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        int d = dp(c, m); p.setMargins(d, d, d, d);
        return p;
    }

    // ---- app bars ----
    /** Standard page bar: blue, back arrow, white title. Returns the container. */
    public static LinearLayout pageBar(Activity a, String title) {
        LinearLayout bar = row(a);
        bar.setBackgroundColor(BLUE);
        bar.setPadding(dp(a, 6), 0, dp(a, 12), 0);
        bar.setMinimumHeight(dp(a, 54));

        TextView back = tv(a, "←", 22, Color.WHITE);
        back.setPadding(dp(a, 12), dp(a, 8), dp(a, 12), dp(a, 8));
        back.setOnClickListener(v -> a.onBackPressed());
        bar.addView(back);

        TextView t = tvBold(a, title, 19, Color.WHITE);
        t.setPadding(dp(a, 4), 0, 0, 0);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        return bar;
    }

    public static void statusBar(Window w, int color) {
        w.setStatusBarColor(color);
        w.setNavigationBarColor(color == 0 ? Color.BLACK : color);
    }

    public static void statusBarBlue(Activity a) {
        a.getWindow().setStatusBarColor(BLUE_DARK);
        a.getWindow().setNavigationBarColor(isDark(a) ? bg(a) : Color.WHITE);
    }

    public static void hideKeyboard(Activity a) {
        View v = a.getCurrentFocus();
        if (v != null) {
            InputMethodManager im = (InputMethodManager) a.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }

    // ---- dialogs ----
    public interface Run { void run(); }
    public interface Pick { void pick(int which); }

    public static void confirm(Context c, String title, String msg, String okLabel, Run onOk) {
        new AlertDialog.Builder(c)
                .setTitle(title).setMessage(msg)
                .setPositiveButton(okLabel, (d, w) -> onOk.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    public static void choose(Context c, String title, String[] options, Pick onPick) {
        new AlertDialog.Builder(c)
                .setTitle(title)
                .setItems(options, (d, w) -> onPick.pick(w))
                .show();
    }

    public static void info(Context c, String title, String msg) {
        new AlertDialog.Builder(c).setTitle(title).setMessage(msg)
                .setPositiveButton("OK", null).show();
    }

    // ---- badges / dots ----
    public static TextView badge(Context c) {
        TextView b = tvBold(c, "", 10, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(circle(RED));
        b.setMinWidth(dp(c, 18));
        b.setPadding(dp(c, 4), dp(c, 1), dp(c, 4), dp(c, 1));
        b.setVisibility(View.GONE);
        return b;
    }

    public static void setBadge(TextView badge, int count) {
        Context c = badge.getContext();
        if (count <= 0 || Prefs.quiet(c)) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setVisibility(View.VISIBLE);
            badge.setText(count > 99 ? "99+" : String.valueOf(count));
        }
    }

    public static View onlineDot(Context c) {
        View v = new View(c);
        int d = dp(c, 12);
        GradientDrawable g = circle(GREEN);
        g.setStroke(dp(c, 2), card(c));
        v.setBackground(g);
        v.setLayoutParams(new ViewGroup.LayoutParams(d, d));
        return v;
    }

    /** Split a list of docs into chunks for whereIn queries (max 10 per query). */
    public static <T> List<List<T>> chunks(List<T> in, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i += size)
            out.add(in.subList(i, Math.min(in.size(), i + size)));
        return out;
    }
}

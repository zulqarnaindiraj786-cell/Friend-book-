package com.friendbook.core;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Programmatic UI toolkit with Facebook-Lite 525 look, dark mode, data-saver quality & QR/browser helpers. */
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
        if (!Prefs.quiet(v.getContext()) && Prefs.inAppSounds(v.getContext())) {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    // ---- dynamic colors ----
    public static boolean isDark(Context c) { return Prefs.dark(c); }
    public static int bg(Context c)    { return isDark(c) ? (Prefs.amoled(c) ? 0xFF000000 : 0xFF18191A) : 0xFFF0F2F5; }
    public static int card(Context c)  { return isDark(c) ? (Prefs.amoled(c) ? 0xFF0A0A0A : 0xFF242526) : 0xFFFFFFFF; }
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

    /** Vertical gradient with corner radius (radius in PXELS — pass Ui.dp(c, r)). */
    public static GradientDrawable gradient(int from, int to, float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Color lerp: t=0 → b, t=1 → a. */
    public static int blend(int a, int b, float t) {
        int ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF;
        int rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (rb + (ra - rb) * t);
        int g = (int) (gb + (ga - gb) * t);
        int bl = (int) (bb + (ba - bb) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
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

    // ---- ⚡ Facebook Lite 2G WebP CDN Image Optimizer ----
    /**
     * Rewrites Cloudinary URLs to request tiny server-side WebP thumbnails BEFORE downloading over the wire:
     * - Avatar: ~3 KB (96x96 WebP) instead of 2 MB raw!
     * - 2G / Data Saver: ~14 KB (340px WebP) instead of 3.5 MB raw!
     * - Standard Turbo: ~34 KB (620px WebP)!
     */
    public static String optimizeImageUrl(Context c, String url, boolean avatar) {
        if (url == null || url.isEmpty()) return url;
        int idx = url.indexOf("/image/upload/");
        if (idx < 0) return url;
        String after = url.substring(idx + "/image/upload/".length());
        // Don't double-transform if already transformed
        if (after.startsWith("w_") || after.startsWith("f_") || after.startsWith("q_") || after.startsWith("c_")) {
            return url;
        }
        String transform;
        if (avatar) {
            // Face-aware crisp WebP avatar so heads/faces are never chopped or blurry!
            transform = "w_240,h_240,c_fill,g_auto,f_webp,q_auto:good/";
        } else if (FastCache.isSlow2G(c) || Prefs.photoQuality(c) == 0) {
            transform = "w_480,c_limit,f_webp,q_auto:eco/";
        } else if (Prefs.photoQuality(c) == 1 || Prefs.turbo2g(c)) {
            transform = "w_780,c_limit,f_webp,q_auto:good/";
        } else {
            transform = "w_1200,c_limit,f_webp,q_auto:best/";
        }
        return url.substring(0, idx + "/image/upload/".length()) + transform + after;
    }

    /** Returns full-resolution HD uncropped URL for Full-Screen DP & Photo Viewer. */
    public static String hdImageUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        int idx = url.indexOf("/image/upload/");
        if (idx < 0) return url;
        String after = url.substring(idx + "/image/upload/".length());
        if (after.startsWith("w_") || after.startsWith("f_") || after.startsWith("q_") || after.startsWith("c_")) {
            return url;
        }
        return url.substring(0, idx + "/image/upload/".length()) + "w_1600,c_limit,f_webp,q_auto:best/" + after;
    }

    public static void avatar(Context c, ImageView iv, String url) {
        Drawable ph = circle(inputBg(c));
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(ph);
            return;
        }
        String fastUrl = optimizeImageUrl(c, url, true);
        Glide.with(c.getApplicationContext()).load(fastUrl)
                .circleCrop()
                .override(240, 240)
                .dontAnimate()
                .placeholder(ph).error(ph)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(iv);
    }

    /** High-definition large circular DP for Profile hero header (never blurry!). */
    public static void avatarLarge(Context c, ImageView iv, String url) {
        Drawable ph = circle(inputBg(c));
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(ph);
            return;
        }
        String hdUrl = url;
        int idx = url.indexOf("/image/upload/");
        if (idx >= 0) {
            String after = url.substring(idx + "/image/upload/".length());
            if (!after.startsWith("w_") && !after.startsWith("f_") && !after.startsWith("q_") && !after.startsWith("c_")) {
                hdUrl = url.substring(0, idx + "/image/upload/".length()) + "w_512,h_512,c_fill,g_auto,f_webp,q_auto:best/" + after;
            }
        }
        Glide.with(c.getApplicationContext()).load(hdUrl)
                .circleCrop()
                .override(512, 512)
                .dontAnimate()
                .placeholder(ph).error(ph)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(iv);
    }

    /**
     * Full-aspect-ratio Feed & DP Post Photo loader (Facebook Lite style):
     * Preserves 100% of vertical/portrait/landscape photos from head to toe without square-chopping!
     */
    public static void imgFeed(Context c, ImageView iv, String url) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        String fastUrl = optimizeImageUrl(c, url, false);
        Prefs.recordBytes(c, 38_000L, 162_000L);
        if (Prefs.fullPhotoDisplay(c)) {
            Glide.with(c.getApplicationContext()).load(fastUrl)
                    .fitCenter()
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(ph).error(ph).into(iv);
        } else {
            Glide.with(c.getApplicationContext()).load(fastUrl)
                    .centerCrop()
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(ph).error(ph).into(iv);
        }
    }

    /**
     * Feed photo loader with automatic background on-device visual safety moderation:
     * If explicit/violating visual content is detected, automatically deletes the post from Firestore & FastCache!
     */
    public static void imgFeedModerated(final Context c, ImageView iv, String url,
                                        final String collection, final String docId,
                                        final String authorId, final String caption,
                                        final Runnable onUnsafeDeleted) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        String fastUrl = optimizeImageUrl(c, url, false);
        Prefs.recordBytes(c, 38_000L, 162_000L);
        com.bumptech.glide.request.RequestListener<Drawable> safetyListener =
                new com.bumptech.glide.request.RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,
                                                Object model,
                                                com.bumptech.glide.request.target.Target<Drawable> target,
                                                boolean isFirstResource) {
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model,
                                                   com.bumptech.glide.request.target.Target<Drawable> target,
                                                   com.bumptech.glide.load.DataSource dataSource,
                                                   boolean isFirstResource) {
                        if (resource instanceof android.graphics.drawable.BitmapDrawable) {
                            android.graphics.Bitmap bmp =
                                    ((android.graphics.drawable.BitmapDrawable) resource).getBitmap();
                            ContentSafetyEngine.inspectLoadedBitmapAsync(
                                    c, bmp, collection, docId, authorId, caption, onUnsafeDeleted);
                        }
                        return false;
                    }
                };
        if (Prefs.fullPhotoDisplay(c)) {
            Glide.with(c.getApplicationContext()).load(fastUrl)
                    .fitCenter()
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .listener(safetyListener)
                    .placeholder(ph).error(ph).into(iv);
        } else {
            Glide.with(c.getApplicationContext()).load(fastUrl)
                    .centerCrop()
                    .dontAnimate()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .listener(safetyListener)
                    .placeholder(ph).error(ph).into(iv);
        }
    }

    public static void img(Context c, ImageView iv, String url) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        String fastUrl = optimizeImageUrl(c, url, false);
        Prefs.recordBytes(c, 36_000L, 164_000L);
        Glide.with(c.getApplicationContext()).load(fastUrl)
                .centerCrop()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(ph).error(ph).into(iv);
    }

    public static void imgFit(Context c, ImageView iv, String url) {
        Drawable ph = round(c, inputBg(c), 0);
        if (url == null || url.isEmpty()) { iv.setImageDrawable(ph); return; }
        String hdUrl = hdImageUrl(url);
        Prefs.recordBytes(c, 55_000L, 125_000L);
        Glide.with(c.getApplicationContext()).load(hdUrl)
                .fitCenter()
                .dontAnimate()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(ph).error(ph).into(iv);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.2f MB", bytes / (1024f * 1024f));
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

    /** Soft infinite pulse (used by the animated 💎 diamond tick). */
    public static void pulse(View v) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.55f);
        a.setDuration(700);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        a.start();
        v.setTag(a);
    }

    /** Small rounded chip label (e.g. 🚀 BOOSTED). */
    public static TextView roundChip(Context c, String text, int bg, int fg, float sp) {
        TextView t = tvBold(c, text, sp, fg);
        t.setBackground(round(c, bg, 10));
        t.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        return t;
    }

    /** Open URL cleanly in the user's system browser (100% WebView-free for AdMob policy safety). */
    public static void openUrl(Context c, String url) {
        if (url == null || url.trim().isEmpty()) return;
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            toast(c, "Couldn't open link");
        }
    }

    public static void showQrDialog(Context c, String title, String link) {
        showQrDialog(c, title, "Scan or share on friendbook", link);
    }

    /** Visual QR share card dialog (deterministic matrix + copy/share link). */
    public static void showQrDialog(Context c, String title, String subtitle, String link) {
        LinearLayout box = col(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(box, 20, 16, 20, 8);

        View qr = new View(c) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                int n = 21;
                float cell = Math.min(w, h) / (float) (n + 4);
                float ox = (w - cell * n) / 2f, oy = (h - cell * n) / 2f;
                p.setColor(Color.WHITE);
                canvas.drawRoundRect(0, 0, w, h, dp(c, 14), dp(c, 14), p);
                p.setColor(BLUE);
                long seed = link == null ? 42 : link.hashCode() * 2654435761L;
                for (int r = 0; r < n; r++) {
                    for (int col = 0; col < n; col++) {
                        boolean finder = (r < 7 && col < 7) || (r < 7 && col >= n - 7) || (r >= n - 7 && col < 7);
                        boolean on;
                        if (finder) {
                            int fr = r < 7 ? r : r - (n - 7);
                            int fc = col < 7 ? col : col - (n - 7);
                            on = fr == 0 || fr == 6 || fc == 0 || fc == 6
                                    || (fr >= 2 && fr <= 4 && fc >= 2 && fc <= 4);
                        } else {
                            long mix = seed ^ (r * 131L + col * 977L + (r * col * 31L));
                            on = ((mix >>> ((r + col) & 15)) & 1L) == 1L;
                        }
                        if (on) {
                            canvas.drawRect(ox + col * cell, oy + r * cell,
                                    ox + (col + 1) * cell - 1, oy + (r + 1) * cell - 1, p);
                        }
                    }
                }
            }
        };
        int qs = dp(c, 190);
        box.addView(qr, new LinearLayout.LayoutParams(qs, qs));

        TextView t = tvBold(c, title, 17, txt(c));
        t.setPadding(0, dp(c, 12), 0, dp(c, 2));
        box.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = tv(c, subtitle, 12, sub(c));
            box.addView(s);
        }
        TextView urlTv = tv(c, link, 12, BLUE);
        urlTv.setPadding(0, dp(c, 6), 0, 0);
        box.addView(urlTv);

        new AlertDialog.Builder(c)
                .setView(box)
                .setPositiveButton("📤 Share", (d, w) -> {
                    Intent s = new Intent(Intent.ACTION_SEND);
                    s.setType("text/plain");
                    s.putExtra(Intent.EXTRA_TEXT, title + "\n" + link);
                    c.startActivity(Intent.createChooser(s, "Share QR link"));
                })
                .setNeutralButton("📋 Copy link", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", link));
                    toast(c, "Link copied 🔗");
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Copy text to clipboard & show crisp confirmation toast. */
    public static void copy(Context c, String text) {
        if (c == null) return;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("friendbook", text == null ? "" : text));
        toast(c, "Copied 📋");
    }

    /** Open a geo URI in the user's map app (or silently do nothing). */
    public static void openMap(Context c, String location) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("geo:0,0?q=" + Uri.encode(location)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) { toast(c, "No map app installed"); }
    }

    /** Crisp white status bar with dark icons in light mode (exact Facebook Lite v525 look). */
    @SuppressWarnings("deprecation")
    public static void statusBarLight(Activity a) {
        if (a == null) return;
        Window w = a.getWindow();
        if (w == null) return;
        boolean dark = isDark(a);
        w.setStatusBarColor(card(a));
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (!dark && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Authentic Facebook Lite top "Data mode" + "📡 Buy data / Data mode" strip
     * (matches Screenshots 1 & 2, and opens Pro 2G/Text-Only/Data-Saver controls on tap).
     */
    public static LinearLayout dataModeStrip(final Activity a, final Runnable onModeChanged) {
        LinearLayout wrap = col(a);
        wrap.setBackgroundColor(card(a));
        if (!Prefs.dataModeBar(a)) {
            wrap.setVisibility(View.GONE);
            return wrap;
        }

        LinearLayout row = row(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pad(row, 14, 7, 14, 7);

        final TextView leftTv = tvBold(a, dataModeLeftLabel(a), 14, txt(a));
        row.addView(leftTv, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout pill = row(a);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(round(a, inputBg(a), 8));
        pad(pill, 12, 6, 12, 6);
        ImageView sig = Icons.icon(a, Icons.DATA_SAVER, 15, txt(a));
        pill.addView(sig);
        final TextView pillTv = tvBold(a,
                Prefs.textOnlyMode(a) ? "See photos" : "Buy data", 13, txt(a));
        pillTv.setPadding(dp(a, 6), 0, 0, 0);
        pill.addView(pillTv);
        row.addView(pill);

        View.OnClickListener openSheet = v -> FastCache.showLiteModeDialog(a, () -> {
            leftTv.setText(dataModeLeftLabel(a));
            pillTv.setText(Prefs.textOnlyMode(a) ? "See photos" : "Buy data");
            if (onModeChanged != null) onModeChanged.run();
        });
        leftTv.setOnClickListener(openSheet);
        pill.setOnClickListener(openSheet);

        wrap.addView(row);
        wrap.addView(divider(a));
        return wrap;
    }

    public static String dataModeLeftLabel(Context c) {
        if (Prefs.textOnlyMode(c)) return "Free mode · Text only";
        if (Prefs.dataSaver(c)) return "Data mode · Saver ON";
        return "Data mode";
    }
}

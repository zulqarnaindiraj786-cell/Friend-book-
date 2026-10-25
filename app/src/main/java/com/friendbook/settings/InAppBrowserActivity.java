package com.friendbook.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.friendbook.core.Ui;

/**
 * 🌐 Facebook-Lite style Built-in Browser:
 * Opens external links inside friendbook with a compact security bar, progress indicator,
 * copy/share tools, and 1-tap "Open in external browser".
 */
public class InAppBrowserActivity extends Activity {

    private WebView web;
    private TextView titleTv, domainTv;
    private ProgressBar progress;
    private String currentUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        String url = getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) { finish(); return; }
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        currentUrl = url;

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        // Compact FB-Lite browser top bar
        LinearLayout bar = Ui.row(this);
        bar.setBackgroundColor(Ui.BLUE);
        bar.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        bar.setMinimumHeight(Ui.dp(this, 54));

        TextView close = Ui.tvBold(this, "✕", 18, Color.WHITE);
        close.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        close.setOnClickListener(v -> finish());
        bar.addView(close);

        LinearLayout mid = Ui.col(this);
        titleTv = Ui.tvBold(this, "Loading…", 14, Color.WHITE);
        titleTv.setSingleLine(true);
        domainTv = Ui.tv(this, hostOf(url), 11, 0xCCFFFFFF);
        domainTv.setSingleLine(true);
        mid.addView(titleTv);
        mid.addView(domainTv);
        bar.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        TextView refresh = Ui.tvBold(this, "↻", 19, Color.WHITE);
        refresh.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        refresh.setOnClickListener(v -> { if (web != null) web.reload(); });
        bar.addView(refresh);

        TextView more = Ui.tvBold(this, "⋮", 20, Color.WHITE);
        more.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        more.setOnClickListener(this::showMenu);
        bar.addView(more);
        root.addView(bar);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        try {
            web = new WebView(this);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUseWideViewPort(true);
            ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            ws.setAllowFileAccess(false);
            ws.setAllowContentAccess(false);

            web.setWebChromeClient(new WebChromeClient() {
                @Override public void onProgressChanged(WebView v, int p) {
                    progress.setProgress(p);
                    progress.setVisibility(p >= 100 ? View.GONE : View.VISIBLE);
                }
                @Override public void onReceivedTitle(WebView v, String t) {
                    if (t != null && !t.isEmpty()) titleTv.setText(t);
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageStarted(WebView v, String u, Bitmap fav) {
                    currentUrl = u;
                    domainTv.setText(hostOf(u));
                }
                @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                    Uri u = req.getUrl();
                    if (u == null) return false;
                    String scheme = u.getScheme();
                    if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                        currentUrl = u.toString();
                        domainTv.setText(hostOf(currentUrl));
                        return false;
                    }
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                    return true;
                }
            });
            root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
            web.loadUrl(url);
        } catch (Throwable t) {
            // Fallback on headless / no-WebView ROMs: open external browser cleanly
            openExternal(url);
            finish();
            return;
        }

        setContentView(root);
    }

    private String hostOf(String u) {
        try {
            Uri uri = Uri.parse(u);
            boolean sec = "https".equalsIgnoreCase(uri.getScheme());
            return (sec ? "🔒 " : "🌐 ") + (uri.getHost() == null ? u : uri.getHost());
        } catch (Exception e) { return u; }
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        if (web != null && web.canGoBack()) pm.getMenu().add("← Back");
        if (web != null && web.canGoForward()) pm.getMenu().add("→ Forward");
        pm.getMenu().add("🔗 Copy link");
        pm.getMenu().add("📤 Share link");
        pm.getMenu().add("🌐 Open in external browser");
        pm.setOnMenuItemClickListener(it -> {
            String t = it.getTitle().toString();
            if (t.startsWith("←") && web != null) web.goBack();
            else if (t.startsWith("→") && web != null) web.goForward();
            else if (t.contains("Copy")) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", currentUrl));
                Ui.toast(this, "Link copied 🔗");
            } else if (t.contains("Share")) {
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType("text/plain");
                s.putExtra(Intent.EXTRA_TEXT, currentUrl);
                startActivity(Intent.createChooser(s, "Share link"));
            } else if (t.contains("external")) openExternal(currentUrl);
            return true;
        });
        pm.show();
    }

    private void openExternal(String u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            startActivity(i);
        } catch (Exception e) {
            Ui.toast(this, "No browser installed");
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            try { web.stopLoading(); web.destroy(); } catch (Throwable ignored) { }
        }
        super.onDestroy();
    }
}

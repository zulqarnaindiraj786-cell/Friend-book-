package com.friendbook.social.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.friendbook.social.core.AdsConfig;
import com.friendbook.social.core.Ui;

/**
 * 📄 Dedicated In-App Policy & Legal Document Viewer (`PolicyWebViewActivity`):
 * - Opens the official Google Docs policy URL directly inside friendbook without requiring login
 *   and without redirecting out to external browsers or the Google Docs app.
 * - Strictly ad-free legal viewer screen (100% Google Play & AdMob compliant).
 */
public class PolicyWebViewActivity extends Activity {

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.statusBarLight(this);

        String rawTitle = getIntent().getStringExtra("title");
        String rawUrl = getIntent().getStringExtra("url");
        final String title = (rawTitle == null || rawTitle.trim().isEmpty())
                ? "Privacy Policy" : rawTitle.trim();
        final String targetUrl = (rawUrl == null || rawUrl.trim().isEmpty())
                ? AdsConfig.GOOGLE_DOCS_POLICY_URL : rawUrl.trim();

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));

        LinearLayout bar = Ui.pageBar(this, title);
        TextView reloadBtn = Ui.tvBold(this, "↻", 20, Ui.txt(this));
        reloadBtn.setGravity(Gravity.CENTER);
        Ui.pad(reloadBtn, 10, 4, 6, 4);
        reloadBtn.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        bar.addView(reloadBtn);
        root.addView(bar);
        root.addView(Ui.divider(this));

        final ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(15);
        progress.getProgressDrawable().setColorFilter(Ui.BLUE, android.graphics.PorterDuff.Mode.SRC_IN);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Strip "; wv" token so Google Docs serves the clean public viewer directly without app-install prompts
        String ua = ws.getUserAgentString();
        if (ua != null && ua.contains("; wv")) {
            ws.setUserAgentString(ua.replace("; wv", ""));
        }

        try {
            CookieManager.getInstance().setAcceptCookie(true);
        } catch (Throwable ignored) {}

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progress.setVisibility(View.GONE);
                } else {
                    if (progress.getVisibility() != View.VISIBLE) {
                        progress.setVisibility(View.VISIBLE);
                    }
                    progress.setProgress(Math.max(10, newProgress));
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                return handleUri(view, request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                return handleUri(view, Uri.parse(url));
            }

            private boolean handleUri(WebView view, Uri uri) {
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    // Keep all HTTP/HTTPS policy pages strictly inside the in-app WebView
                    return false;
                }
                if ("intent".equalsIgnoreCase(scheme)) {
                    // Prevent Google Docs from launching external Docs app / account picker; use web fallback if present
                    try {
                        Intent parsed = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                        String fallback = parsed.getStringExtra("browser_fallback_url");
                        if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                            view.loadUrl(fallback);
                        }
                    } catch (Throwable ignored) {}
                    return true;
                }
                // Block market:// or external app schemes so user never leaves the app
                return true;
            }
        });

        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        webView.loadUrl(targetUrl);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.stopLoading();
                webView.loadUrl("about:blank");
                webView.destroy();
            } catch (Throwable ignored) {}
            webView = null;
        }
        super.onDestroy();
    }
}

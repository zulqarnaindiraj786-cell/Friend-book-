package com.friendbook.post;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import com.friendbook.core.Ui;

/** Full-screen media viewer (photo / video) with black background. */
public class FullscreenMediaActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String type = getIntent().getStringExtra("type");
        String url = getIntent().getStringExtra("url");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        if ("video".equals(type)) {
            VideoView vv = new VideoView(this);
            root.addView(vv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
            MediaController mc = new MediaController(this);
            mc.setAnchorView(vv);
            vv.setMediaController(mc);
            vv.setVideoURI(android.net.Uri.parse(url));
            vv.setOnPreparedListener(mp -> { mp.setLooping(true); vv.start(); });
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            root.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            Ui.imgFit(this, iv, url);
        }

        TextView close = Ui.tvBold(this, "✕", 20, Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.circle(0x66000000));
        int s = Ui.dp(this, 42);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(s, s, Gravity.TOP | Gravity.END);
        cp.setMargins(0, Ui.dp(this, 36), Ui.dp(this, 16), 0);
        root.addView(close, cp);
        close.setOnClickListener(v -> finish());

        setContentView(root);
    }
}

package com.friendbook.reels;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 🎬 Standalone Full-Screen Facebook Lite Pro Reels Player:
 * Launched when tapping a Reel on Profile's Reels tab or a Video Post on Feed,
 * providing full Like, Comments, Share, Save, Follow, Sound & swipe-next Reels!
 */
public class ReelPlayerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int id = View.generateViewId();
        root.setId(id);
        setContentView(root);

        if (savedInstanceState == null) {
            ReelsFragment frag = new ReelsFragment();
            Bundle args = new Bundle();
            args.putString("initial_reel_id", getIntent().getStringExtra("reel_id"));
            args.putBoolean("show_back", true);
            frag.setArguments(args);
            getFragmentManager().beginTransaction().replace(id, frag).commit();
        }
    }
}

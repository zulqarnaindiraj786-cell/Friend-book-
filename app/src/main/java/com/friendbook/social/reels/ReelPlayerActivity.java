package com.friendbook.social.reels;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 🎬 Standalone Full-Screen friendbook Pro Reels Player:
 * Launched when tapping a Reel on Profile's Reels tab or a Video Post on Feed,
 * providing full Like, Comments, Share, Save, Follow, Sound & swipe-next Reels!
 */
public class ReelPlayerActivity extends Activity {

    private static final String CONTAINER_ID = "reel_container_id";
    private int containerId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.friendbook.social.core.Ui.statusBar(getWindow(), Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        containerId = savedInstanceState == null ? View.generateViewId() : savedInstanceState.getInt(CONTAINER_ID);
        if (containerId == View.NO_ID || containerId == 0) containerId = View.generateViewId();
        root.setId(containerId);
        setContentView(root);

        if (getFragmentManager().findFragmentById(containerId) == null) {
            ReelsFragment frag = new ReelsFragment();
            Bundle args = new Bundle();
            if (getIntent() != null && getIntent().getExtras() != null) {
                args.putAll(getIntent().getExtras());
            }
            args.putString("initial_reel_id", getIntent().getStringExtra("reel_id"));
            args.putBoolean("show_back", true);
            frag.setArguments(args);
            getFragmentManager().beginTransaction().replace(containerId, frag).commit();
        }
    }
    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(CONTAINER_ID,containerId);
        super.onSaveInstanceState(outState);
    }
}

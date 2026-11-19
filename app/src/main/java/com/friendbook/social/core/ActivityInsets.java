package com.friendbook.social.core;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Fit Android 15+ edge-to-edge content around system bars, cutouts and the keyboard. */
public final class ActivityInsets {
    private ActivityInsets() {}

    public static void apply(Activity activity) {
        if (Build.VERSION.SDK_INT < 35) return; // Existing platform-managed legacy layout.
        View content=activity.findViewById(android.R.id.content);
        if (content==null || content.getTag(com.friendbook.social.R.id.activityMainRoot)!=null) return;
        content.setTag(com.friendbook.social.R.id.activityMainRoot,Boolean.TRUE);
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(),false);
        final int left=content.getPaddingLeft(),top=content.getPaddingTop();
        final int right=content.getPaddingRight(),bottom=content.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(content,(view,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.ime());
            view.setPadding(left+safe.left,top+safe.top,right+safe.right,bottom+safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(content);
    }
}

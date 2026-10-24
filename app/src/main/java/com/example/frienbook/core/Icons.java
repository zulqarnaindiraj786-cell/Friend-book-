package com.example.frienbook.core;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.example.frienbook.R;

/**
 * Facebook-Lite style icon system (v525 look): Lucide/Heroicons-style THIN OUTLINE icons,
 * 2px stroke, tinted #1877F2 (blue) when active and #65676B (gray) when inactive.
 * Fully native VectorDrawables — no emoji anywhere in navigation.
 */
public class Icons {

    /** Facebook blue (active / primary). */
    public static final int BLUE = 0xFF1877F2;
    /** Facebook gray (inactive). */
    public static final int GRAY = 0xFF65676B;

    // ---- the named set the user asked for ----
    public static final int HOME = R.drawable.ic_home_outline;
    public static final int FRIENDS = R.drawable.ic_users_outline;
    public static final int VIDEO = R.drawable.ic_video_outline;
    public static final int MARKETPLACE = R.drawable.ic_store_outline;
    public static final int GROUPS = R.drawable.ic_groups_outline;
    public static final int NOTIFICATIONS = R.drawable.ic_bell_outline;
    public static final int MENU = R.drawable.ic_menu_outline;
    public static final int MESSAGES = R.drawable.ic_message_outline;
    public static final int SETTINGS = R.drawable.ic_gear_outline;
    public static final int SEARCH = R.drawable.ic_search_outline;
    public static final int PLUS = R.drawable.ic_plus_outline;

    // ---- extra outline icons used across menus/settings ----
    public static final int BOOKMARK = R.drawable.ic_bookmark_outline;
    public static final int CAKE = R.drawable.ic_cake_outline;
    public static final int LIST = R.drawable.ic_list_outline;
    public static final int SHIELD = R.drawable.ic_shield_outline;
    public static final int LOGOUT = R.drawable.ic_logout_outline;
    public static final int MOON = R.drawable.ic_moon_outline;
    public static final int INFO = R.drawable.ic_info_outline;
    public static final int HELP = R.drawable.ic_help_outline;
    public static final int LOCK = R.drawable.ic_lock_outline;
    public static final int TRASH = R.drawable.ic_trash_outline;
    public static final int MEGAPHONE = R.drawable.ic_megaphone_outline;
    public static final int KEY = R.drawable.ic_key_outline;
    public static final int GLOBE = R.drawable.ic_globe_outline;
    public static final int USER_PLUS = R.drawable.ic_userplus_outline;
    public static final int ACTIVE = R.drawable.ic_activity_outline;
    public static final int READ_RECEIPTS = R.drawable.ic_checkcheck_outline;
    public static final int BAN = R.drawable.ic_ban_outline;
    public static final int SHAKE = R.drawable.ic_phone_outline;
    public static final int EYE_OFF = R.drawable.ic_eyeoff_outline;
    public static final int SNOOZE = R.drawable.ic_clock_outline;
    public static final int EXPORT = R.drawable.ic_download_outline;
    public static final int TERMS = R.drawable.ic_file_outline;
    public static final int DATA_SAVER = R.drawable.ic_wifi_outline;
    public static final int QUIET = R.drawable.ic_belloff_outline;
    public static final int FONT = R.drawable.ic_type_outline;
    public static final int MAIL = R.drawable.ic_mail_outline;
    public static final int IMAGE = R.drawable.ic_image_outline;
    public static final int CAMERA = R.drawable.ic_camera_outline;
    public static final int CHART = R.drawable.ic_chart_outline;
    public static final int FLAG = R.drawable.ic_flag_outline;
    public static final int MIC = R.drawable.ic_mic_outline;
    public static final int LIKE = R.drawable.ic_like_outline;
    public static final int SEND = R.drawable.ic_send_outline;
    public static final int VERIFIED = R.drawable.ic_verified;
    public static final int BANKNOTE = R.drawable.ic_banknote_outline;
    public static final int STAR = R.drawable.ic_star_outline;
    public static final int SHARE2 = R.drawable.ic_share2_outline;
    public static final int VERIFIED_GOLD = R.drawable.ic_verified_gold;
    public static final int VERIFIED_DIAMOND = R.drawable.ic_verified_diamond;
    public static final int BOOST = R.drawable.ic_rocket_outline;
    public static final int LIVE = R.drawable.ic_live_outline;
    public static final int GIFT = R.drawable.ic_gift_outline;

    /** Native badge drawable for a tick level (untinted). */
    public static int badgeFor(String tickLevel) {
        if ("gold".equals(tickLevel)) return VERIFIED_GOLD;
        if ("diamond".equals(tickLevel)) return VERIFIED_DIAMOND;
        return VERIFIED;
    }

    public static int tickRank(String tickLevel) {
        if ("blue".equals(tickLevel)) return 1;
        if ("gold".equals(tickLevel)) return 2;
        if ("diamond".equals(tickLevel)) return 3;
        return 0;
    }

    /**
     * Show the user's tick badge in an ImageView — blue / gold ✓ / animated diamond.
     * Hidden view when level is none. Untinted so the badge keeps its official colors.
     */
    public static void setTickBadge(ImageView iv, String tickLevel, boolean animateDiamond) {
        int rank = tickRank(tickLevel);
        if (rank == 0) { iv.setVisibility(android.view.View.GONE); return; }
        iv.setVisibility(android.view.View.VISIBLE);
        // clear any previous tint so badge colors stay true (blue/gold/diamond)
        iv.setImageDrawable(null);
        iv.setImageResource(badgeFor(tickLevel));
        Object old = iv.getTag();
        if (old instanceof android.animation.ObjectAnimator) ((android.animation.ObjectAnimator) old).cancel();
        iv.setTag(null);
        iv.setAlpha(1f);
        if (rank == 3 && animateDiamond) Ui.pulse(iv);
    }

    /** A VectorDrawable tinted with the given color. */
    public static Drawable tinted(Context c, int resId, int color) {
        Drawable d = ContextCompat.getDrawable(c, resId);
        if (d == null) return null;
        d = DrawableCompat.wrap(d.mutate());
        DrawableCompat.setTint(d, color);
        return d;
    }

    /** An ImageView showing the outline icon tinted (size in dp). */
    public static ImageView icon(Context c, int resId, int sizeDp, int color) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(tinted(c, resId, color));
        int s = Ui.dp(c, sizeDp);
        iv.setLayoutParams(new android.view.ViewGroup.LayoutParams(s, s));
        return iv;
    }

    /** Facebook-Lite style menu icon: rounded gray circle chip with the outline icon inside. */
    public static LinearLayout chipIcon(Context c, int resId, int color) {
        LinearLayout wrap = new LinearLayout(c);
        wrap.setGravity(Gravity.CENTER);
        wrap.setBackground(Ui.circle(Ui.inputBg(c)));
        int s = Ui.dp(c, 38);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        ImageView iv = icon(c, resId, 20, color);
        wrap.addView(iv);
        return wrap;
    }

    /** Retint an already-created icon view (e.g. tab active/inactive). */
    public static void setTint(ImageView iv, int color) {
        Drawable d = iv.getDrawable();
        if (d != null) DrawableCompat.setTint(d, color);
    }
}

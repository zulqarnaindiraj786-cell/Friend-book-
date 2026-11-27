package com.friendbook.social.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/** Local share action only: no invite database writes, link shorteners or background requests. */
public final class InviteFriends {
    private InviteFriends() { }
    public static String message() {
        return "Join me on friendbook! Connect with friends, share moments and watch Reels.\n"
                + AdsConfig.PLAY_STORE_URL;
    }
    public static Intent shareIntent(String packageName) {
        Intent intent=new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,message());
        if(packageName!=null)intent.setPackage(packageName);
        return intent;
    }
    public static void shareOnWhatsApp(Activity activity) {
        for(String target:new String[]{"com.whatsapp","com.whatsapp.w4b"}) {
            try { activity.startActivity(shareIntent(target));return; }
            catch(ActivityNotFoundException unavailable) { /* Try Business, then the system share sheet. */ }
        }
        try { activity.startActivity(Intent.createChooser(shareIntent(null),"Invite friends")); }
        catch(ActivityNotFoundException unavailable) { Ui.toast(activity,"No sharing app available on this phone."); }
    }
}

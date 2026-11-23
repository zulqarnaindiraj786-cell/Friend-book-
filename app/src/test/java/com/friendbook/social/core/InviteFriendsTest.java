package com.friendbook.social.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.friendbook.social.main.MenuFragment;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class InviteFriendsTest {
    public static class ShareActivity extends Activity {
        String installed="com.whatsapp";
        Intent launched;
        int attempts;
        @Override public void startActivity(Intent intent) {
            attempts++;
            if(Intent.ACTION_SEND.equals(intent.getAction()) && !java.util.Objects.equals(installed,intent.getPackage()))
                throw new ActivityNotFoundException();
            launched=intent;
        }
    }
    private ShareActivity activity(){return Robolectric.buildActivity(ShareActivity.class).setup().get();}
    @Test public void sharedTextContainsHistoricalOfficialPlayListingOnly() {
        Intent send=InviteFriends.shareIntent("com.whatsapp");
        assertEquals(Intent.ACTION_SEND,send.getAction());
        assertEquals("text/plain",send.getType());
        assertEquals("com.whatsapp",send.getPackage());
        String text=send.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(text.contains("https://play.google.com/store/apps/details?id=com.friendbook.social"));
        assertFalse(text.contains("blogspot"));assertFalse(text.contains(".apk"));
    }
    @Test public void whatsappPreferredAndBusinessSupported() {
        ShareActivity a=activity();InviteFriends.shareOnWhatsApp(a);
        assertEquals("com.whatsapp",a.launched.getPackage());assertEquals(1,a.attempts);
        a=activity();a.installed="com.whatsapp.w4b";InviteFriends.shareOnWhatsApp(a);
        assertEquals("com.whatsapp.w4b",a.launched.getPackage());assertEquals(2,a.attempts);
    }
    @Test public void withoutWhatsappUsesSystemShareSheetWithSameLink() {
        ShareActivity a=activity();a.installed=null;InviteFriends.shareOnWhatsApp(a);
        assertEquals(Intent.ACTION_CHOOSER,a.launched.getAction());
        Intent send=a.launched.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(InviteFriends.message(),send.getStringExtra(Intent.EXTRA_TEXT));
        assertNull(send.getPackage());assertEquals(3,a.attempts);
    }
    @Test public void cardButtonStartsShareOnlyWhenClicked() throws Exception {
        ShareActivity a=activity();
        java.lang.reflect.Method build=MenuFragment.class.getDeclaredMethod("inviteFriendsCard",Activity.class);
        build.setAccessible(true);
        LinearLayout card=(LinearLayout)build.invoke(new MenuFragment(),a);
        assertEquals("Invite Friends",((TextView)card.getChildAt(0)).getText().toString());
        assertNull(a.launched);
        TextView button=(TextView)card.getChildAt(2);
        assertEquals("Share on WhatsApp",button.getText().toString());
        button.performClick();assertEquals("com.whatsapp",a.launched.getPackage());
    }
}

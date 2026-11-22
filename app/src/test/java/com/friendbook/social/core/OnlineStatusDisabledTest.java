package com.friendbook.social.core;

import android.os.Looper;
import com.friendbook.social.BuildConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=android.app.Application.class,manifest=Config.NONE)
public class OnlineStatusDisabledTest {
    @Test public void noAuthenticationOrWatcherNeededAndCallbackHidesCachedPresence() {
        assertFalse(BuildConfig.ONLINE_STATUS_ENABLED);
        assertTrue(BuildConfig.CLOUDFLARE_PRESENCE_ENABLED);
        assertFalse(BuildConfig.TYPING_ENABLED);
        Presence.init();
        Presence.goOffline();
        Presence.setActiveStatusVisible(true);
        assertFalse(Presence.isUserOnlineDoc(null));
        assertEquals("",Presence.formatActiveStatus(true,System.currentTimeMillis()));
        AtomicBoolean online=new AtomicBoolean(true);
        AtomicLong seen=new AtomicLong(123);
        Presence.Handle handle=Presence.watchPresence("peer",(active,lastSeen)->{online.set(active);seen.set(lastSeen);});
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(online.get());assertEquals(0L,seen.get());
        handle.close();
    }
    @Test public void disabledTypingDoesNotStartNetworkOrListener() {
        Presence.setTyping("room",true);
        Presence.setTyping("room",false);
        AtomicBoolean typing=new AtomicBoolean(true);
        Presence.Handle h=Presence.watchTyping("room","alice",typing::set);
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(typing.get());
        h.close();
        AtomicBoolean called=new AtomicBoolean(false);
        Presence.Handle closed=Presence.watchTyping("room","alice",ignored->called.set(true));
        closed.close();shadowOf(Looper.getMainLooper()).idle();
        assertFalse(called.get());
    }
    @Test public void closedWatchDoesNotEmitLateCallback() {
        AtomicBoolean called=new AtomicBoolean();
        Presence.Handle handle=Presence.watchPresence("peer",(active,lastSeen)->called.set(true));
        handle.close();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(called.get());
    }
}

package com.friendbook.social.core;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Guards the three on-demand budgets: suggestions, listener caps and cold start. */
public class OnDemandLoadsTest {

    @Test public void chatBadgeReadIsCappedAtTwentyRooms() {
        // QUOTA: the badge is a one-shot read for a pill that shows "99+"; 20 recent rooms is plenty.
        assertEquals(20, OnDemandLoads.CHAT_BADGE_LIMIT);
        assertTrue(OnDemandLoads.FRIEND_GRAPH_LIMIT > 0);
        assertTrue(OnDemandLoads.FRIEND_GRAPH_LIMIT <= 500);
        assertTrue(OnDemandLoads.BADGE_WINDOW <= 32);
    }

    @Test public void suggestionsAreNotRefetchedPerTabSwitch() {
        long now = 1_000_000L;
        long fresh = now - 60_000L;
        assertFalse("same account, cached, already loaded", OnDemandLoads.pymkShouldFetch(
                "u1", "u1", fresh, now, false, true));
        assertTrue("explicit refresh", OnDemandLoads.pymkShouldFetch(
                "u1", "u1", fresh, now, true, true));
        assertTrue("new account", OnDemandLoads.pymkShouldFetch(
                "u2", "u1", fresh, now, false, true));
        assertFalse("tab return never reads", OnDemandLoads.pymkShouldFetchOnTabReturn(false));
        assertTrue("only a gesture reads", OnDemandLoads.pymkShouldFetchOnTabReturn(true));
        assertFalse("stale cache still needs no read", OnDemandLoads.pymkShouldFetch(
                "u1", "u1", now - OnDemandLoads.PYMK_CACHE_MS - 1, now, false, true));
        assertTrue("stale cache with nothing loaded may refresh", OnDemandLoads.pymkShouldFetch(
                "u1", "u1", now - OnDemandLoads.PYMK_CACHE_MS - 1, now, false, false));
        assertFalse("missing account", OnDemandLoads.pymkShouldFetch(
                null, null, 0L, now, true, false));
    }

    @Test public void everyColdStartCollectionListenerIsBounded() throws Exception {
        String main = read("src/main/java/com/friendbook/social/main/MainActivity.java");
        String home = read("src/main/java/com/friendbook/social/main/HomeFragment.java");
        assertTrue("Chat badge listener must be limited",
                main.contains("OnDemandLoads.CHAT_BADGE_LIMIT"));
        assertTrue("Chat badge listener must bound the read",
                main.contains(".limit(com.friendbook.social.core.OnDemandLoads.CHAT_BADGE_LIMIT)"));
        assertTrue("Friend graph listener must be limited",
                home.contains("OnDemandLoads.FRIEND_GRAPH_LIMIT"));
        assertTrue("Suggestions must be served from cache",
                home.contains("restorePymkFromCache") && home.contains("pymkCacheFresh"));
        assertTrue("Suggestions fetch only through the guarded path",
                home.contains("loadNotesUsers(discoveryRefresh)") && home.contains("public void onDiscoveryVisible()"));
        assertFalse("Suggestions must not be re-read on every tab return",
                home.contains("pendingHomeSecondary = () -> {\n            pendingHomeSecondary = null;\n            if (!homeStarted || !isAdded() || isHidden() || !actor.equals(Fb.uid())) return;\n            startHomeWatchers();\n            startStoriesListener();\n            loadFeaturedReelsForHome();\n            loadNotesUsers(true);"));
        assertTrue("Referral totals must not join the cold-start burst",
                main.contains("if(!referralCountLoaded)referralHandler.postDelayed(referralRefresh, 4_000L)"));
    }

    private static String read(String relative) throws Exception {
        File file = new File(relative);
        assertTrue("Missing source: " + relative, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unused")
    private static List<String> unused() { return new ArrayList<>(); }
}

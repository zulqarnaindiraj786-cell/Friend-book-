package com.friendbook.social.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.friendbook.social.auth.LoginActivity;
import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Format;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Presence;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.post.SavedPostsActivity;
import com.friendbook.social.profile.EditProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ⚙ Settings & privacy — Full friendbook v2.2.0 Settings Hub:
 * Live settings search + Account, Security, Tools, Preferences, Media/Data Saver,
 * Wellbeing, Audience & Visibility (Privacy), Your Activity/Archive/Trash, and Legal.
 */
public class SettingsActivity extends Activity {

    private static class SearchEntry {
        View view;
        String keywords;
        TextView sectionHeader;
    }

    private final List<SearchEntry> entries = new ArrayList<>();
    private final Map<TextView, List<SearchEntry>> sectionMap = new HashMap<>();
    private TextView currentHeader;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        entries.clear();
        sectionMap.clear();

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Settings & privacy"));

        // 🔍 Live Search Settings bar (exact friendbook top search bar)
        LinearLayout searchWrap = Ui.col(this);
        searchWrap.setBackgroundColor(Ui.card(this));
        Ui.pad(searchWrap, 12, 8, 12, 8);
        EditText searchIn = Ui.input(this, "🔍 Search settings (e.g. dark, lock, archive, data, 2FA…)");
        searchWrap.addView(searchIn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
        root.addView(searchWrap);
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 6, 12, 24);

        Models.User me = Fb.me;

        // ============ 1. ACCOUNT ============
        body.addView(header("Account"));
        body.addView(rowI(Icons.FRIENDS, "Personal and account information",
                (me != null ? me.displayName : "Name") + " · pronouns · phone · email",
                this::personalInfoDialog));
        body.addView(rowI(Icons.USER_PLUS, "Saved accounts & quick switch",
                Prefs.savedAccounts(this).size() + " account(s) saved on this device",
                this::savedAccountsDialog));
        body.addView(switchRowI(Icons.CHART, "Professional mode (Creator tools)",
                me != null && me.professionalMode, (btn, on) -> {
                    patchUser("professionalMode", on);
                    if (Fb.me != null) Fb.me.professionalMode = on;
                    Ui.toast(this, on ? "Professional mode ON — Creator insights unlocked 📊"
                            : "Professional mode turned off");
                }));
        body.addView(rowI(Icons.SHIELD, "Account ownership and control",
                "Temporarily deactivate or permanently delete account",
                this::ownershipControlDialog));

        // ============ 2. PASSWORD & SECURITY ============
        body.addView(header("Password and security"));
        body.addView(rowI(Icons.KEY, "Change password", "Update your login password", this::changePassword));
        body.addView(rowI(Icons.SHIELD, "Two-step security PIN (2FA)",
                me != null && me.twoFactorPin != null && !me.twoFactorPin.isEmpty()
                        ? "Enabled — cloud security PIN active" : "Off — add an extra security PIN",
                this::twoFactorDialog));
        body.addView(switchRowI(Icons.NOTIFICATIONS, "Get alerts about unrecognized logins",
                me != null && me.loginAlerts, (btn, on) -> {
                    patchUser("loginAlerts", on);
                    if (Fb.me != null) Fb.me.loginAlerts = on;
                    Ui.toast(this, on ? "Login alerts ON 🔔" : "Login alerts OFF");
                }));
        body.addView(rowI(Icons.LOCK, "App lock (PIN)",
                Prefs.pin(this).isEmpty() ? "Off — lock friendbook on this phone"
                        : "On — PIN required when opening app", this::pinDialog));
        body.addView(rowI(Icons.ACTIVE, "Where you're logged in",
                "Active devices & sessions — log out remotely",
                () -> startActivity(new Intent(this, SessionsActivity.class))));
        body.addView(switchRowI(Icons.SHAKE, "Shake phone to report a problem",
                Prefs.shakeToReport(this), (btn, on) -> {
                    Prefs.setShakeToReport(this, on);
                    Ui.toast(this, on ? "Shake to report enabled" : "Shake to report disabled");
                }));

        // ============ 3. TOOLS & MONETIZATION ============
        body.addView(header("Tools & creator hub"));
        body.addView(rowI(Icons.CHART, "📊 Creator Dashboard",
                "Live post reach, total likes, comments, level & creator tools",
                () -> com.friendbook.social.profile.CreatorDashboardDialog.show(this, null)));
        body.addView(rowI(Icons.VERIFIED, "✓ Tick levels & verification",
                "Blue · Gold · Diamond — earn FREE via 5 invites or paid",
                () -> startActivity(new Intent(this, com.friendbook.social.bluetick.BlueTickActivity.class))));
        body.addView(rowI(Icons.BANKNOTE, "Monetization & Stars",
                "In-stream ads, Stars balance (" + (me != null ? me.stars : 0) + " ⭐), fan subscriptions",
                () -> startActivity(new Intent(this, com.friendbook.social.bluetick.MonetizationActivity.class))));
        if (me != null && (Icons.tickRank(me.tickLevel) > 0 || me.professionalMode)) {
            body.addView(rowI(Icons.CHART, "Creator & profile insights",
                    "Reach, total likes, followers, Stars & streak analytics", this::verifiedInsights));
            body.addView(rowI(Icons.MAIL, "Priority support",
                    "Direct fast-track creator/verified support", this::verifiedSupport));
        }
        if (Admin.isAdmin()) {
            body.addView(rowI(Icons.SHIELD, "🛡 Admin Super Panel",
                    "Users, ticks, payments, boosts, live rooms, broadcast",
                    () -> startActivity(new Intent(this, com.friendbook.social.admin.AdminPanelActivity.class))));
            body.addView(rowI(Icons.MEGAPHONE, "📢 AdMob Settings",
                    "Live banner ad unit ID & global kill switch",
                    () -> startActivity(new Intent(this, com.friendbook.social.admin.AdMobSettingsActivity.class))));
        }

        // ============ 4. PREFERENCES ============
        body.addView(header("Preferences"));
        body.addView(rowI(Icons.STAR, "News Feed preferences",
                "Favorites, Most Recent vs Top sort, snooze & reconnect",
                () -> startActivity(new Intent(this, FeedPreferencesActivity.class))));
        body.addView(rowI(Icons.LIKE, "Reaction preferences",
                (Prefs.hideOtherReactions(this) || Prefs.hideMyReactions(this))
                        ? "Reaction counts hidden" : "Showing all reaction counts",
                this::reactionPrefsDialog));
        body.addView(rowI(Icons.NOTIFICATIONS, "Notifications",
                "Manage likes, comments, tags, requests, messages, birthdays & groups",
                this::notificationsDialog));
        body.addView(rowI(Icons.DATA_SAVER, "Data Saver & media quality",
                "Photo: " + Prefs.photoQualityLabel(this).split(" ")[0]
                        + " · Autoplay: " + Prefs.autoplayModeLabel(this),
                this::mediaAndDataDialog));
        body.addView(switchRowI(Icons.DATA_SAVER, "Show top 'Data mode' strip",
                Prefs.dataModeBar(this), (btn, on) -> {
                    Prefs.setDataModeBar(this, on);
                    Ui.toast(this, on ? "Top Data mode strip enabled ✓" : "Top Data mode strip hidden");
                }));
        body.addView(rowI(Icons.MESSAGES, "Default comments filter",
                "Current: " + Prefs.commentSort(this).toUpperCase() + " (All comments / Newest / Relevant)",
                () -> Ui.choose(this, "Default comments filter", new String[]{
                        "💬 All comments — show every comment & reply",
                        "🕐 Newest — show recent comments first",
                        "⭐ Most relevant — top liked & replied first"
                }, idx -> {
                    String m = idx == 1 ? "newest" : (idx == 2 ? "relevant" : "all");
                    Prefs.setCommentSort(this, m);
                    Ui.toast(this, "Comments filter set to: " + m);
                })));
        body.addView(switchRowI(Icons.DATA_SAVER, "Data Saver mode",
                Prefs.dataSaver(this), (btn, on) -> {
                    Prefs.setDataSaver(this, on);
                    Ui.toast(this, on ? "Data Saver ON — saving up to 90% mobile data" : "Data Saver OFF");
                }));

        // ============ 4A+. 🚀 FRIENDBOOK PRO EXTRA FEATURES & CONTROLS ============
        body.addView(header("friendbook Lite Pro extras"));
        body.addView(switchRowI(Icons.MESSAGES, "💬 Inline comments on Home Feed",
                Prefs.inlineFeedComments(this), (btn, on) -> {
                    Prefs.setInlineFeedComments(this, on);
                    Ui.toast(this, on
                            ? "💬 Inline multi-user comments enabled on Home Feed!"
                            : "Inline feed comments hidden");
                }));
        body.addView(switchRowI(Icons.IMAGE, "🖼 Full uncropped HD DP & portrait photos",
                Prefs.fullPhotoDisplay(this), (btn, on) -> {
                    Prefs.setFullPhotoDisplay(this, on);
                    Ui.toast(this, on
                            ? "🖼 Full uncropped photos & DPs enabled!"
                            : "Compact cropped photos enabled");
                }));
        body.addView(switchRowI(Icons.LOCK, "🛡️ Profile Picture Guard (DP protection)",
                Fb.me != null && Fb.me.profilePicGuard, (btn, on) -> {
                    java.util.Map<String, Object> up = new java.util.HashMap<>();
                    up.put("profilePicGuard", on);
                    Fb.patchMe(up);
                    if (Fb.me != null) Fb.me.profilePicGuard = on;
                    Ui.toast(this, on
                            ? "🛡️ Profile Picture Guard ON — downloads & screenshots blocked!"
                            : "Profile Picture Guard OFF");
                }));
        body.addView(switchRowI(Icons.IMAGE, "📢 Share new DP updates to News Feed",
                Prefs.shareDpToFeed(this), (btn, on) -> {
                    Prefs.setShareDpToFeed(this, on);
                    Ui.toast(this, on
                            ? "New DP updates will share in full HD to News Feed ✓"
                            : "DP updates won't auto-post to News Feed");
                }));
        body.addView(switchRowI(Icons.LIKE, "❤️ Double-tap photos & Reels to Like",
                Prefs.doubleTapToLike(this), (btn, on) -> {
                    Prefs.setDoubleTapToLike(this, on);
                    Ui.toast(this, on ? "Double-tap ❤️ Like enabled" : "Double-tap Like disabled");
                }));
        body.addView(switchRowI(Icons.VIDEO, "🔊 Start Reels with audio ON",
                Prefs.reelsStartWithSound(this), (btn, on) -> {
                    Prefs.setReelsStartWithSound(this, on);
                    Ui.toast(this, on ? "Reels start with sound ON 🔊" : "Reels start muted 🔇");
                }));

        // ============ 4B. ⚡ 2G SPEED, TEXT-ONLY MODE & OFFLINE ENGINE ============
        body.addView(header("2G speed & offline engine"));
        body.addView(rowI(Icons.DATA_SAVER, "⚡ Lite 2G mode switcher",
                "Current: " + com.friendbook.social.core.FastCache.modeBadgeLabel(this)
                        + " · " + com.friendbook.social.core.FastCache.netLabel(this),
                () -> com.friendbook.social.core.FastCache.showLiteModeDialog(this, null)));
        body.addView(switchRowI(Icons.DATA_SAVER, "⚡ 2G Ultra-Lite Turbo Engine",
                Prefs.turbo2g(this), (btn, on) -> {
                    Prefs.setTurbo2g(this, on);
                    Ui.toast(this, on
                            ? "⚡ 2G Turbo Engine ON — WebP CDN compression + 0ms instant UI"
                            : "2G Turbo Engine OFF");
                }));
        body.addView(switchRowI(Icons.IMAGE, "📵 Text-Only Basic 2G Mode (0 KB auto photos)",
                Prefs.textOnlyMode(this), (btn, on) -> {
                    Prefs.setTextOnlyMode(this, on);
                    Ui.toast(this, on
                            ? "📵 Text-Only 2G Mode ON — tap 'See Photo' only when needed"
                            : "Auto photo loading restored");
                }));
        body.addView(switchRowI(Icons.ACTIVE, "🚀 Instant screen transitions (zero animation lag)",
                Prefs.instantTransitions(this), (btn, on) -> {
                    Prefs.setInstantTransitions(this, on);
                    Ui.toast(this, on
                            ? "🚀 Instant 0ms transitions enabled"
                            : "Standard Android animations enabled");
                }));
        body.addView(switchRowI(Icons.GLOBE, "📡 Auto-preload feed on Wi-Fi",
                Prefs.wifiPreload(this), (btn, on) -> {
                    Prefs.setWifiPreload(this, on);
                    Ui.toast(this, on ? "Wi-Fi feed preload ON" : "Wi-Fi feed preload OFF");
                }));
        body.addView(rowI(Icons.BOOKMARK, "📥 Save 30 posts for offline reading",
                "Download text & tiny thumbnails now (" + com.friendbook.social.core.FastCache.cachedFeedCount() + " cached)",
                () -> com.friendbook.social.core.FastCache.preloadFeedForOffline(this, null)));
        body.addView(rowI(Icons.CHART, "📊 Live network & speed diagnostics",
                com.friendbook.social.core.FastCache.netLabel(this) + " · "
                        + Ui.formatBytes(Prefs.dataBytesSaved(this)) + " saved",
                () -> Ui.info(this, "📊 Speed & 2G Diagnostics",
                        "Connection: " + com.friendbook.social.core.FastCache.netLabel(this)
                        + "\nActive Mode: " + com.friendbook.social.core.FastCache.modeBadgeLabel(this)
                        + "\nCached Posts in RAM/Disk: " + com.friendbook.social.core.FastCache.cachedFeedCount()
                        + "\nCached Profiles in RAM: " + com.friendbook.social.core.FastCache.cachedUserCount()
                        + "\n0ms RAM Cache Hits: " + com.friendbook.social.core.FastCache.ramHits()
                        + "\nFirestore Offline Cache: 100 MB Persistent Enabled"
                        + "\nData Saved: " + Ui.formatBytes(Prefs.dataBytesSaved(this))
                        + "\nData Used: " + Ui.formatBytes(Prefs.dataBytesUsed(this)))));
        body.addView(rowI(Icons.GLOBE, "📡 Find Wi-Fi networks",
                "Open Wi-Fi settings to connect to fast free Wi-Fi",
                () -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                    } catch (Throwable t) {
                        Ui.toast(this, com.friendbook.social.core.FastCache.netLabel(this));
                    }
                }));
        body.addView(rowI(Icons.SNOOZE, "Your time on friendbook",
                "Today: " + Prefs.todayMinutes(this) + "m · 7-day chart & daily reminder",
                () -> startActivity(new Intent(this, TimeSpentActivity.class))));
        body.addView(rowI(Icons.GLOBE, "Language and region",
                "App language: " + Prefs.appLanguage(this), this::languageDialog));
        body.addView(switchRowI(Icons.MOON, "Dark mode", Prefs.dark(this), (btn, on) -> {
            Prefs.setDark(this, on);
            recreate();
        }));
        body.addView(switchRowI(Icons.MOON, "AMOLED pure-black mode", Prefs.amoled(this), (btn, on) -> {
            Prefs.setAmoled(this, on);
            if (on && !Prefs.dark(this)) Prefs.setDark(this, true);
            recreate();
        }));
        body.addView(rowI(Icons.FONT, "Font size", fontLabel(), this::fontPicker));
        body.addView(switchRowI(Icons.QUIET, "Quiet mode", Prefs.quiet(this),
                (btn, on) -> Prefs.setQuiet(this, on)));
        body.addView(switchRowI(Icons.GLOBE, "Confirm before opening external links",
                Prefs.externalBrowser(this), (btn, on) -> {
                    Prefs.setExternalBrowser(this, on);
                    Ui.toast(this, "External browser preference saved ✓");
                }));

        // ============ 5. AUDIENCE AND VISIBILITY (PRIVACY) ============
        body.addView(header("Audience and visibility"));
        body.addView(rowI(Icons.SHIELD, "Privacy Checkup",
                "5-step guided checkup: audience, lock, friends list, lookup, blocks",
                this::privacyCheckup));
        body.addView(switchRowI(Icons.LOCK, "Profile locking",
                me != null && me.profileLocked, (btn, on) -> {
                    patchUser("profileLocked", on);
                    if (Fb.me != null) Fb.me.profileLocked = on;
                    Ui.toast(this, on ? "🔒 Profile locked — only friends see full profile"
                            : "Profile unlocked");
                }));
        body.addView(rowI(Icons.LIST, "Profile details (Intro, Work, Education, City)",
                "Edit bio, work, school, hometown, relationship & hobbies",
                () -> startActivity(new Intent(this, EditProfileActivity.class))));
        body.addView(rowI(Icons.USER_PLUS, "How people find and contact you",
                "Requests: " + Prefs.friendRequestPrivacy(this)
                        + " · Friends list: " + (me != null ? me.friendsListPrivacy : "Public"),
                this::howPeopleFindDialog));
        body.addView(rowI(Icons.GLOBE, "Posts privacy & Limit Past Posts",
                "Default: " + Prefs.defaultPrivacy(this) + " · tap to change or limit old posts",
                this::postsPrivacyDialog));
        body.addView(rowI(Icons.CAMERA, "Stories privacy & Archive settings",
                "Audience: " + Prefs.storyPrivacy(this)
                        + " · Archive: " + (Prefs.storyArchive(this) ? "On" : "Off"),
                this::storiesPrivacyDialog));
        body.addView(rowI(Icons.VIDEO, "Reels privacy",
                "Default audience: " + Prefs.reelPrivacy(this), this::reelsPrivacyDialog));
        body.addView(rowI(Icons.FRIENDS, "Followers and public content",
                "Who can follow me: " + (me != null ? me.followersPrivacy : "Public"),
                this::followersPrivacyDialog));
        body.addView(rowI(Icons.FLAG, "Profile and tagging",
                "Who can tag: " + (me != null ? me.tagPrivacy : "Everyone")
                        + (me != null && me.tagReviewEnabled ? " · Tag review ON" : ""),
                this::taggingDialog));
        body.addView(switchRowI(Icons.ACTIVE, "Active status (Online green dot)",
                Prefs.activeStatus(this), (btn, on) -> {
                    Prefs.setActiveStatus(this, on);
                    if (on) Presence.init(); else Presence.goOffline();
                }));
        body.addView(switchRowI(Icons.READ_RECEIPTS, "Read receipts in Messenger (Seen ✓✓)",
                Prefs.readReceipts(this), (btn, on) -> Prefs.setReadReceipts(this, on)));
        body.addView(rowI(Icons.BAN, "Blocking",
                (me != null ? me.blocked.size() : 0) + " blocked user(s)",
                () -> startActivity(new Intent(this, BlockedUsersActivity.class))));
        body.addView(rowI(Icons.EYE_OFF, "Restricted list",
                (me != null ? me.restrictedUids.size() : 0)
                        + " friend(s) restricted — they only see your Public posts",
                this::restrictedListDialog));

        // ============ 6. YOUR ACTIVITY & INFORMATION ============
        body.addView(header("Your activity and information"));
        body.addView(rowI(Icons.LIST, "Activity log",
                "Filter your posts, comments & likes in one timeline",
                () -> startActivity(new Intent(this, ActivityLogActivity.class))));
        body.addView(rowI(Icons.BOOKMARK, "Archive & Recycle Bin (Trash)",
                "Story Archive · Post Archive · 30-day Recycle Bin",
                () -> startActivity(new Intent(this, ArchiveTrashActivity.class))));
        body.addView(rowI(Icons.BOOKMARK, "Saved items",
                "Posts, videos & Marketplace listings you bookmarked",
                () -> startActivity(new Intent(this, SavedPostsActivity.class))));
        body.addView(rowI(Icons.EYE_OFF, "Posts you've hidden",
                Prefs.hiddenPosts(this).size() + " hidden post(s) — tap to restore", () -> {
                    int n = Prefs.hiddenPosts(this).size();
                    if (n == 0) { Ui.toast(this, "No hidden posts"); return; }
                    Ui.confirm(this, "Unhide " + n + " post(s)?",
                            "Hidden posts will reappear in your Feed.", "Unhide all", () -> {
                                Prefs.unhideAllPosts(this);
                                recreate();
                            });
                }));
        body.addView(rowI(Icons.SNOOZE, "Snoozed people",
                "Manage people snoozed for 30 days", this::snoozedDialog));
        body.addView(rowI(Icons.INFO, "Access your information",
                "View a complete summary of your friendbook profile & activity",
                this::accessInfoDialog));
        body.addView(rowI(Icons.EXPORT, "Download / Export your information",
                "Export your account data as JSON or plain text", this::exportData));
        body.addView(rowI(Icons.SEARCH, "Clear search history",
                Prefs.searchHistory(this).size() + " recent search(es)", () -> {
                    int n = Prefs.searchHistory(this).size();
                    if (n == 0) { Ui.toast(this, "Search history already clean ✓"); return; }
                    Ui.confirm(this, "Clear " + n + " recent search(es)?", "", "Clear", () -> {
                        Prefs.clearSearch(this);
                        Ui.toast(this, "Search history cleared 🧹");
                        recreate();
                    });
                }));
        body.addView(rowI(Icons.IMAGE, "Storage & clear cache",
                "Data tracked: " + Ui.formatBytes(Prefs.dataBytesUsed(this))
                        + " ·Saved: " + Ui.formatBytes(Prefs.dataBytesSaved(this)),
                this::storageDialog));

        // ============ 7. COMMUNITY STANDARDS, CHILD SAFETY & LEGAL ============
        body.addView(header("Community standards, child safety & legal"));
        body.addView(rowI(Icons.TERMS, "Terms of Service", "Rules for using friendbook",
                () -> Ui.openPolicyInApp(this, "Terms of Service",
                        com.friendbook.social.core.AdsConfig.TERMS_OF_SERVICE_URL)));
        body.addView(rowI(Icons.SHIELD, "Privacy Policy (Data & Ads Policy)",
                "Firebase, Cloudinary & Google AdMob / GDPR disclosure",
                () -> Ui.openPolicyInApp(this, "Privacy Policy",
                        com.friendbook.social.core.AdsConfig.PRIVACY_POLICY_URL)));
        body.addView(rowI(Icons.SHIELD, "Child Safety Standards (Zero-Tolerance CSAE)",
                "Google Play CSAE declaration & 1-tap emergency report", () -> {
                    Ui.choose(this, "🛡 Child Safety Standards (CSAE Policy)", new String[]{
                            "📜 View Zero-Tolerance CSAE Policy",
                            "🚨 Report Child Safety / CSAE Concern Immediately"
                    }, w -> {
                        if (w == 0) {
                            Ui.openPolicyInApp(this, "Zero-Tolerance CSAE Policy",
                                    com.friendbook.social.core.AdsConfig.CHILD_SAFETY_URL);
                        } else if (w == 1) {
                            EditText details = Ui.multi(this, "Describe the account, post, or safety concern…", 3);
                            LinearLayout box = Ui.col(this);
                            Ui.pad(box, 18, 10, 18, 0);
                            box.addView(details, new LinearLayout.LayoutParams(-1, -2));
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle("🚨 Priority Child Safety Report")
                                    .setView(box)
                                    .setPositiveButton("Submit Priority Report", (d, which) -> {
                                        String txt = details.getText().toString().trim();
                                        if (txt.isEmpty()) { Ui.toast(this, "Please enter report details"); return; }
                                        Fb.db().collection("reports").add(Fb.map(
                                                "type", "csae_priority_report",
                                                "category", "csae",
                                                "reason", txt,
                                                "reportedBy", Fb.uid(),
                                                "createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp()));
                                        Ui.info(this, "Priority Report Submitted",
                                                "Thank you. Our safety team prioritizes child safety reports immediately.");
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    });
                }));
        body.addView(rowI(Icons.LOCK, "Ad Privacy & GDPR Consent (UMP)",
                "Manage Google AdMob personalized ad consent",
                () -> com.friendbook.social.core.ConsentManager.showPrivacyOptionsForm(this)));
        body.addView(rowI(Icons.FLAG, "Community Standards & Auto-Moderation",
                "Zero tolerance for nudity, violence, harassment & hate speech", () -> Ui.info(this,
                "Community Standards",
                "• Automated Safety Engine: Explicit/nude photos & videos are automatically blocked and deleted.\n"
                        + "• Zero tolerance for harassment, hate speech, gore, or bullying.\n"
                        + "• Report violating posts, reels, stories, or profiles via ••• → Report or block users anytime.")));
        body.addView(rowI(Icons.TRASH, "Delete Account & Personal Data",
                "Permanently delete your friendbook account, profile & content", this::deleteAccount));
        body.addView(rowI(Icons.HELP, "Help & Support Center", "FAQs · troubleshooting · contact support",
                this::helpDialog));
        final String appVerName = Ui.appVersionName(this);
        final int appVerCode = Ui.appVersionCode(this);
        final String appPkg = getPackageName();
        body.addView(rowI(Icons.INFO, "About", "Version " + appVerName + " · " + appPkg, () -> Ui.info(this,
                "About friendbook",
                "friendbook for Android\nVersion " + appVerName + " (Build " + appVerCode + ")\nPackage: " + appPkg + "\n\n"
                        + "Core friendbook features:\n"
                        + "• News Feed (Top, Most Recent, Favorites, PYMK, Colored Posts, Polls, Life Events, Q&A)\n"
                        + "• Real-Time Live Video & Voice Studio, Co-Host Split-Screen, Live Polls & Star Gifts\n"
                        + "• Stories (Photo, Video, Aa Text Stories, Reactions, Archive & Profile Highlights)\n"
                        + "• Reels, Watch, Groups, Pages (Post as Page), Events & 2-Column Marketplace\n"
                        + "• Messenger (Active Now bar, Voice Notes, Stickers, Quoted Replies, Forwarding, Themes)\n"
                        + "• On-Device Automated Content Safety Moderation, GDPR/UMP Privacy & 2G Data Saver.")));

        // ============ LOG OUT ============
        TextView logout = Ui.btn(this, "🚪 Log out");
        logout.setBackground(Ui.round(this, Ui.RED, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 18);
        body.addView(logout, lp);
        logout.setOnClickListener(v -> Ui.confirm(this, "Log out?",
                "Your account stays saved for quick 1-tap login unless you remove it.", "Log out",
                this::performLogout));

        // Attach settings in local12-row pages; no artificial network calls or timed delay.
        List<View> settingsRows=new ArrayList<>();for(int i=0;i<body.getChildCount();i++)settingsRows.add(body.getChildAt(i));body.removeAllViews();
        android.widget.ProgressBar nextSettings=new android.widget.ProgressBar(this);nextSettings.setVisibility(View.GONE);
        nextSettings.setContentDescription("Loading next settings page");
        final int[] attached={0};final boolean[] attaching={false};
        Runnable appendSettings=()->{
            body.removeView(nextSettings);int end=Math.min(attached[0]+12,settingsRows.size());
            while(attached[0]<end)body.addView(settingsRows.get(attached[0]++));
            if(attached[0]<settingsRows.size())body.addView(nextSettings,new LinearLayout.LayoutParams(Ui.dp(this,26),Ui.dp(this,26)));
            nextSettings.setVisibility(View.GONE);attaching[0]=false;
        };appendSettings.run();
        sv.setOnScrollChangeListener((View.OnScrollChangeListener)(v,x,y,oldX,oldY)->{
            if(y<=oldY || attaching[0] || attached[0]>=settingsRows.size() || y+sv.getHeight()<body.getHeight()-Ui.dp(this,180))return;
            attaching[0]=true;nextSettings.setVisibility(View.VISIBLE);body.postOnAnimation(appendSettings);
        });
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        // Wire live search filtering
        searchIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b2, int c) {
                if(s!=null && s.length()>0){
                    body.removeView(nextSettings);while(attached[0]<settingsRows.size())body.addView(settingsRows.get(attached[0]++));
                }
                filterSettings(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
    }

    private void filterSettings(String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        for (SearchEntry e : entries) {
            boolean show = needle.isEmpty() || e.keywords.contains(needle);
            e.view.setVisibility(show ? View.VISIBLE : View.GONE);
        }
        for (Map.Entry<TextView, List<SearchEntry>> en : sectionMap.entrySet()) {
            boolean any = false;
            for (SearchEntry se : en.getValue()) {
                if (se.view.getVisibility() == View.VISIBLE) { any = true; break; }
            }
            en.getKey().setVisibility(any ? View.VISIBLE : View.GONE);
        }
    }

    private void patchUser(String key, Object val) {
        String uid = Fb.uid();
        if (uid == null) return;
        Fb.db().collection("users").document(uid).set(Fb.map(key, val), SetOptions.merge());
    }

    // ---------- UI builders ----------
    private TextView header(String t) {
        TextView h = Ui.tvBold(this, t.toUpperCase(Locale.US), 12, Ui.sub(this));
        h.setPadding(Ui.dp(this, 4), Ui.dp(this, 16), 0, Ui.dp(this, 6));
        currentHeader = h;
        sectionMap.put(h, new ArrayList<>());
        return h;
    }

    private View rowI(int iconRes, String title, String sub, Runnable action) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 12, 14, 12);
        rowV.addView(Icons.icon(this, iconRes, 21, Icons.GRAY));
        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 14);
        mid.addView(Ui.tvBold(this, title, 15, Ui.txt(this)));
        if (sub != null && !sub.isEmpty()) mid.addView(Ui.tv(this, sub, 12, Ui.sub(this)));
        rowV.addView(mid, mp);
        if (action != null) {
            rowV.addView(Ui.tv(this, "›", 18, Ui.sub(this)));
            rowV.setOnClickListener(v -> action.run());
        }
        LinearLayout wrap = Ui.col(this);
        wrap.addView(rowV);
        wrap.addView(Ui.divider(this));
        registerSearch(wrap, title + " " + (sub == null ? "" : sub));
        return wrap;
    }

    private View switchRowI(int iconRes, String title, boolean on,
                            CompoundButton.OnCheckedChangeListener l) {
        LinearLayout rowV = Ui.row(this);
        rowV.setBackgroundColor(Ui.card(this));
        Ui.pad(rowV, 14, 8, 14, 8);
        rowV.addView(Icons.icon(this, iconRes, 21, Icons.GRAY));
        TextView t = Ui.tvBold(this, title, 15, Ui.txt(this));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = Ui.dp(this, 14);
        rowV.addView(t, tp);
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(l);
        rowV.addView(sw);
        LinearLayout wrap = Ui.col(this);
        wrap.addView(rowV);
        wrap.addView(Ui.divider(this));
        registerSearch(wrap, title);
        return wrap;
    }

    private void registerSearch(View v, String text) {
        SearchEntry se = new SearchEntry();
        se.view = v;
        se.sectionHeader = currentHeader;
        se.keywords = (currentHeader != null ? currentHeader.getText().toString() + " " : "")
                + text.toLowerCase(Locale.ROOT);
        entries.add(se);
        if (currentHeader != null && sectionMap.containsKey(currentHeader)) {
            sectionMap.get(currentHeader).add(se);
        }
    }

    // ---------- Dialogs & Actions ----------

    private void personalInfoDialog() {
        Models.User me = Fb.me;
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 4);
        EditText nameIn = Ui.input(this, "Full name");
        if (me != null) nameIn.setText(me.displayName);
        EditText pronIn = Ui.input(this, "Pronouns (e.g. he/him, she/her)");
        if (me != null && me.pronouns != null) pronIn.setText(me.pronouns);
        EditText phoneIn = Ui.input(this, "Phone number (optional)");
        phoneIn.setInputType(InputType.TYPE_CLASS_PHONE);
        if (me != null && me.phone != null) phoneIn.setText(me.phone);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        lp.bottomMargin = Ui.dp(this, 8);
        box.addView(nameIn, lp);
        box.addView(pronIn, lp);
        box.addView(phoneIn, lp);
        String em = Fb.auth().getCurrentUser() != null ? Fb.auth().getCurrentUser().getEmail() : "";
        box.addView(Ui.tv(this, "📧 Login email: " + em, 12, Ui.sub(this)));

        new AlertDialog.Builder(this)
                .setTitle("Personal information")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = nameIn.getText().toString().trim();
                    if (n.isEmpty()) { Ui.toast(this, "Name cannot be empty"); return; }
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("displayName", n);
                    upd.put("nameLower", n.toLowerCase(Locale.ROOT));
                    upd.put("pronouns", pronIn.getText().toString().trim());
                    upd.put("phone", phoneIn.getText().toString().trim());
                    Fb.db().collection("users").document(Fb.uid()).set(upd, SetOptions.merge())
                            .addOnSuccessListener(x -> {
                                Ui.toast(this, "Personal info updated ✓");
                                recreate();
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void savedAccountsDialog() {
        if (Fb.auth().getCurrentUser() != null) {
            Prefs.saveAccount(this, Fb.uid(), Fb.auth().getCurrentUser().getEmail(),
                    Fb.myName(), Fb.myPhoto());
        }
        List<Prefs.SavedAccount> list = Prefs.savedAccounts(this);
        String[] opts = new String[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            Prefs.SavedAccount a = list.get(i);
            boolean active = Fb.auth().getCurrentUser() != null
                    && a.email.equalsIgnoreCase(Fb.auth().getCurrentUser().getEmail());
            opts[i] = (active ? "🟢 " : "👤 ") + a.name + " (" + a.email + ")"
                    + (active ? " — Active" : " — Tap to switch");
        }
        opts[list.size()] = "＋ Log into another account";
        Ui.choose(this, "Saved accounts on this device", opts, w -> {
            if (w == list.size()) {
                performLogout();
            } else {
                Prefs.SavedAccount target = list.get(w);
                boolean active = Fb.auth().getCurrentUser() != null
                        && target.email.equalsIgnoreCase(Fb.auth().getCurrentUser().getEmail());
                if (active) {
                    Ui.toast(this, "Already signed in as " + target.name);
                } else {
                    Presence.goOffline();
                    com.friendbook.social.core.FastCache.clearAll(this);
                    com.friendbook.social.auth.GoogleAuthHelper.signOutGoogle(this);
                    com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
                    Fb.me = null;
                    Prefs.setUnlockedSession(this, false);
                    Intent i = new Intent(this, LoginActivity.class);
                    i.putExtra("prefill_email", target.email);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finishAffinity();
                }
            }
        });
    }

    private void ownershipControlDialog() {
        String[] opts = {
                "⏸ Temporarily deactivate account (reversible anytime by logging back in)",
                "🗑 Permanently delete account and profile"};
        Ui.choose(this, "Account ownership & control", opts, w -> {
            if (w == 0) {
                Ui.confirm(this, "Deactivate account?",
                        "Your profile will be marked deactivated and you will be signed out. Log back in anytime to reactivate automatically.",
                        "Deactivate", () -> {
                            patchUser("deactivated", true);
                            Ui.toast(this, "Account deactivated — log back in anytime to restore");
                            performLogout();
                        });
            } else {
                deleteAccount();
            }
        });
    }

    private void twoFactorDialog() {
        String cur = Fb.me != null && Fb.me.twoFactorPin != null ? Fb.me.twoFactorPin : "";
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText pin = Ui.input(this, cur.isEmpty() ? "Create 4–6 digit 2FA PIN" : "New 2FA PIN (empty = turn off)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(pin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        new AlertDialog.Builder(this)
                .setTitle("Two-step security PIN")
                .setMessage("Asked when signing in on a new device for extra account protection.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String v = pin.getText().toString().trim();
                    if (!v.isEmpty() && v.length() < 4) {
                        Ui.toast(this, "PIN must be at least 4 digits");
                        return;
                    }
                    patchUser("twoFactorPin", v);
                    if (Fb.me != null) Fb.me.twoFactorPin = v;
                    Ui.toast(this, v.isEmpty() ? "Two-step PIN disabled" : "Two-step security PIN enabled 🛡");
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void reactionPrefsDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 16, 10, 16, 6);
        Switch s1 = new Switch(this);
        s1.setText("Hide number of reactions on posts from others");
        s1.setTextColor(Ui.txt(this));
        s1.setChecked(Prefs.hideOtherReactions(this));
        s1.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 12));
        box.addView(s1);

        Switch s2 = new Switch(this);
        s2.setText("Hide number of reactions on your own posts");
        s2.setTextColor(Ui.txt(this));
        s2.setChecked(Prefs.hideMyReactions(this));
        s2.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(s2);

        new AlertDialog.Builder(this)
                .setTitle("Reaction preferences")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setHideOtherReactions(this, s1.isChecked());
                    Prefs.setHideMyReactions(this, s2.isChecked());
                    Ui.toast(this, "Reaction preferences saved ✓");
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void notificationsDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 8, 18, 8);
        Switch sLikes = makeDialogSwitch("👍 Reactions on my posts", Prefs.notifLikes(this));
        Switch sComms = makeDialogSwitch("💬 Comments & replies", Prefs.notifComments(this));
        Switch sTags = makeDialogSwitch("🏷 Tags & @mentions", Prefs.notifTags(this));
        Switch sReqs = makeDialogSwitch("👥 Friend requests", Prefs.notifRequests(this));
        Switch sMsgs = makeDialogSwitch("✉️ Direct messages", Prefs.notifMessages(this));
        Switch sBday = makeDialogSwitch("🎂 Friends' birthdays", Prefs.notifBirthdays(this));
        Switch sGrp = makeDialogSwitch("👨‍👩‍👧‍👦 Groups activity", Prefs.notifGroups(this));
        Switch sEv = makeDialogSwitch("📅 Events reminders", Prefs.notifEvents(this));
        Switch sVib = makeDialogSwitch("📳 Vibrate on notifications", Prefs.notifVibrate(this));
        box.addView(sLikes); box.addView(sComms); box.addView(sTags);
        box.addView(sReqs); box.addView(sMsgs); box.addView(sBday);
        box.addView(sGrp); box.addView(sEv); box.addView(sVib);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("Notification settings")
                .setView(sc)
                .setNeutralButton("Preview sound", (d, w) -> {
                    String uid = Fb.uid();
                    if (uid == null) return;
                    java.util.Map<String, String> preview = new java.util.HashMap<>();
                    preview.put("toUserId", uid); preview.put("fromUserId", uid);
                    preview.put("type", "push_test");
                    preview.put("eventId", "sound_preview_" + java.util.UUID.randomUUID());
                    preview.put("title", "friendbook"); preview.put("body", "Your friendbook notification sound");
                    com.friendbook.social.push.PushManager.show(this, preview);
                })
                .setPositiveButton("Save", (d, w) -> {
                    Prefs.setNotifLikes(this, sLikes.isChecked());
                    Prefs.setNotifComments(this, sComms.isChecked());
                    Prefs.setNotifTags(this, sTags.isChecked());
                    Prefs.setNotifRequests(this, sReqs.isChecked());
                    Prefs.setNotifMessages(this, sMsgs.isChecked());
                    Prefs.setNotifBirthdays(this, sBday.isChecked());
                    Prefs.setNotifGroups(this, sGrp.isChecked());
                    Prefs.setNotifEvents(this, sEv.isChecked());
                    Prefs.setNotifVibrate(this, sVib.isChecked());
                    Ui.toast(this, "Notification preferences saved 🔔");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Switch makeDialogSwitch(String label, boolean checked) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(Ui.txt(this));
        sw.setChecked(checked);
        sw.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        return sw;
    }

    private void mediaAndDataDialog() {
        String[] opts = {
                "🖼 Photo quality (" + Prefs.photoQualityLabel(this) + ")",
                "🎬 Video autoplay (" + Prefs.autoplayModeLabel(this) + ")",
                "🔊 Videos start with sound: " + (Prefs.videoSound(this) ? "ON" : "OFF"),
                "✨ In-app sounds & haptics: " + (Prefs.inAppSounds(this) ? "ON" : "OFF"),
                "📊 Reset data counter (Used " + Ui.formatBytes(Prefs.dataBytesUsed(this))
                        + " · Saved " + Ui.formatBytes(Prefs.dataBytesSaved(this)) + ")"};
        Ui.choose(this, "Media & Data Saver", opts, w -> {
            if (w == 0) {
                Ui.choose(this, "Photo quality", new String[]{
                        "Low (saves up to 80% data)",
                        "Medium (balanced)",
                        "High (best clarity)"}, q -> {
                    Prefs.setPhotoQuality(this, q);
                    recreate();
                });
            } else if (w == 1) {
                Ui.choose(this, "Autoplay videos", new String[]{
                        "Never autoplay videos",
                        "On Wi-Fi only",
                        "On mobile data and Wi-Fi"}, m -> {
                    Prefs.setAutoplayMode(this, m);
                    recreate();
                });
            } else if (w == 2) {
                Prefs.setVideoSound(this, !Prefs.videoSound(this));
                recreate();
            } else if (w == 3) {
                Prefs.setInAppSounds(this, !Prefs.inAppSounds(this));
                recreate();
            } else {
                Prefs.resetDataStats(this);
                Ui.toast(this, "Data usage counter reset ✓");
                recreate();
            }
        });
    }

    private void languageDialog() {
        String[] langs = {
                "English",
                "Roman Urdu (Pakistani Lite)",
                "اردو (Urdu)",
                "हिन्दी (Hindi)",
                "العربية (Arabic)"};
        Ui.choose(this, "App language", langs, w -> {
            Prefs.setAppLanguage(this, langs[w]);
            Ui.toast(this, "Language set to " + langs[w] + " 🌐");
            recreate();
        });
    }

    private String fontLabel() {
        switch (Prefs.fontSize(this)) {
            case 0: return "Small";
            case 2: return "Large";
            case 3: return "Extra Large";
            default: return "Default (Medium)";
        }
    }

    private void fontPicker() {
        Ui.choose(this, "Font size", new String[]{"Small", "Default (Medium)", "Large", "Extra Large"}, w -> {
            Prefs.setFontSize(this, w);
            recreate();
        });
    }

    private void howPeopleFindDialog() {
        Models.User me = Fb.me;
        String curEmailPriv = (me != null && me.emailPrivacy != null && !me.emailPrivacy.isEmpty())
                ? me.emailPrivacy : "Only me";
        String[] opts = {
                "👥 Who can send friend requests: " + Prefs.friendRequestPrivacy(this),
                "📋 Who can see your friends list: " + (me != null ? me.friendsListPrivacy : "Public"),
                "📧 Who can see your email & phone: " + curEmailPriv};
        Ui.choose(this, "How people find & contact you", opts, w -> {
            if (w == 0) {
                Ui.choose(this, "Who can send you friend requests?",
                        new String[]{"Everyone", "Friends of friends"}, x -> {
                            Prefs.setFriendRequestPrivacy(this, x == 1 ? "Friends of friends" : "Everyone");
                            recreate();
                        });
            } else if (w == 1) {
                String[] p = {"Public", "Friends", "Only me"};
                Ui.choose(this, "Who can see your friends list?", p, x -> {
                    patchUser("friendsListPrivacy", p[x]);
                    if (Fb.me != null) Fb.me.friendsListPrivacy = p[x];
                    recreate();
                });
            } else {
                String[] p = {"Only me", "Friends", "Public"};
                Ui.choose(this, "Who can see your email & phone?", p, x -> {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("emailPrivacy", p[x]);
                    patch.put("contactPrivacy", p[x]);
                    patch.put("contactInfoPrivacy", p[x]);
                    Fb.patchMe(patch);
                    if (Fb.me != null) {
                        Fb.me.emailPrivacy = p[x];
                        Fb.me.contactPrivacy = p[x];
                        Fb.me.contactInfoPrivacy = p[x];
                    }
                    recreate();
                });
            }
        });
    }

    private void postsPrivacyDialog() {
        String[] opts = {
                "🌐 Change default audience (" + Prefs.defaultPrivacy(this) + ")",
                "🔒 Limit Past Posts (convert all your Public posts to Friends)"};
        Ui.choose(this, "Posts privacy", opts, w -> {
            if (w == 0) {
                Ui.choose(this, "Default audience for new posts",
                        new String[]{"🌐 Public", "👥 Friends", "🔒 Only me"}, x -> {
                            Prefs.setDefaultPrivacy(this, x == 1 ? "Friends" : x == 2 ? "Only me" : "Public");
                            recreate();
                        });
            } else {
                Ui.confirm(this, "Limit past posts?",
                        "All past Public posts on your timeline will change to Friends-only.",
                        "Limit Past Posts", () -> com.friendbook.social.core.QueryActions.updateMatching(
                                Fb.db().collection("posts").whereEqualTo("authorId",Fb.uid()).whereEqualTo("privacy","Public"),
                                Fb.map("privacy","Friends"))
                                .addOnSuccessListener(changed->Ui.toast(this,changed+" past post(s) limited to Friends"))
                                .addOnFailureListener(e->Ui.toast(this,"Some posts could not be updated. Retry to continue.")));
            }
        });
    }

    private void storiesPrivacyDialog() {
        int muted = Prefs.mutedStories(this).size();
        String[] opts = {
                "🌐 Story audience: " + Prefs.storyPrivacy(this),
                "📦 Save stories to Archive: " + (Prefs.storyArchive(this) ? "ON" : "OFF"),
                "🔇 Muted stories (" + muted + " muted) — tap to unmute all"};
        Ui.choose(this, "Stories settings", opts, w -> {
            if (w == 0) {
                String[] p = {"Public", "Friends"};
                Ui.choose(this, "Who can see your stories?", p, x -> {
                    Prefs.setStoryPrivacy(this, p[x]);
                    recreate();
                });
            } else if (w == 1) {
                Prefs.setStoryArchive(this, !Prefs.storyArchive(this));
                recreate();
            } else {
                for (String u : Prefs.mutedStories(this)) Prefs.muteStoryAuthor(this, u, false);
                Ui.toast(this, "All muted stories restored ✓");
                recreate();
            }
        });
    }

    private void reelsPrivacyDialog() {
        String[] p = {"Public", "Friends"};
        Ui.choose(this, "Default audience for Reels", p, x -> {
            Prefs.setReelPrivacy(this, p[x]);
            recreate();
        });
    }

    private void followersPrivacyDialog() {
        String[] p = {"Public", "Friends"};
        Ui.choose(this, "Who can follow me?", p, x -> {
            patchUser("followersPrivacy", p[x]);
            if (Fb.me != null) Fb.me.followersPrivacy = p[x];
            recreate();
        });
    }

    private void taggingDialog() {
        Models.User me = Fb.me;
        boolean rev = me != null && me.tagReviewEnabled;
        String[] opts = {
                "🏷 Who can tag me: " + (me != null ? me.tagPrivacy : "Everyone"),
                "🛡 Review tags before they appear: " + (rev ? "ON" : "OFF")};
        Ui.choose(this, "Profile and tagging", opts, w -> {
            if (w == 0) {
                String[] p = {"Everyone", "Friends", "Only me"};
                Ui.choose(this, "Who can tag you in posts?", p, x -> {
                    patchUser("tagPrivacy", p[x]);
                    if (Fb.me != null) Fb.me.tagPrivacy = p[x];
                    recreate();
                });
            } else {
                patchUser("tagReviewEnabled", !rev);
                if (Fb.me != null) Fb.me.tagReviewEnabled = !rev;
                recreate();
            }
        });
    }

    private void restrictedListDialog() {
        com.friendbook.social.core.FriendPicker.single(this,"Tap a friend to toggle Restricted (Public-only)",target->{
            List<String> next=new ArrayList<>(Fb.me!=null?Fb.me.restrictedUids:new ArrayList<>());
            boolean remove=next.contains(target.uid);if(remove)next.remove(target.uid);else next.add(target.uid);
            patchUser("restrictedUids",next);if(Fb.me!=null)Fb.me.restrictedUids=next;
            Ui.toast(this,remove?target.displayName+" removed from Restricted":target.displayName+" added to Restricted");recreate();
        });
    }

    private void accessInfoDialog() {
        String me = Fb.uid();
        if (me == null) return;
        Ui.toast(this, "Loading your account summary…");
        com.friendbook.social.core.CountReads.once(Fb.db().collection("posts").whereEqualTo("authorId",me),totalPosts->{
                    if(isFinishing() || isDestroyed())return;
                    Models.User u = Fb.me;
                    Ui.info(this, "📋 Your friendbook information",
                            "• Name: " + (u != null ? u.displayName : "") + "\n"
                                    + "• Email: " + (u != null ? u.email : "") + "\n"
                                    + "• Joined: " + (u != null && u.createdAt > 0 ? Format.dateTime(u.createdAt) : "—") + "\n"
                                    + "• Friends: " + (u != null ? u.friendsCount : 0) + "\n"
                                    + "• Followers / Following: " + (u != null ? u.followers.size() : 0)
                                    + " / " + (u != null ? u.following.size() : 0) + "\n"
                                    + "• Total posts: " + totalPosts + "\n"
                                    + "• Verification tick: " + (u != null ? Admin.tickLabel(u.tickLevel) : "none") + "\n"
                                    + "• Stars balance: " + (u != null ? u.stars : 0) + " ⭐\n"
                                    + "• Saved reels/highlights: " + (u != null ? u.highlightStoryIds.size() : 0));
                });
    }

    private void storageDialog() {
        String[] opts = {
                "🧹 Clear Glide image & media disk cache",
                "📊 Reset Lite data usage counter",
                "🔍 Clear search history"};
        Ui.choose(this, "Storage & Cache\nUsed: " + Ui.formatBytes(Prefs.dataBytesUsed(this))
                + " · Saved by Lite: " + Ui.formatBytes(Prefs.dataBytesSaved(this)), opts, w -> {
            if (w == 0) {
                Glide.get(this).clearMemory();
                new Thread(() -> {
                    try { Glide.get(this).clearDiskCache(); } catch (Throwable ignored) { }
                    runOnUiThread(() -> Ui.toast(this, "Image & media cache cleared 🧹"));
                }).start();
            } else if (w == 1) {
                Prefs.resetDataStats(this);
                recreate();
            } else {
                Prefs.clearSearch(this);
                recreate();
            }
        });
    }

    private void privacyCheckup() {
        Models.User me = Fb.me;
        boolean locked = me != null && me.profileLocked;
        String[] opts = {
                "1. Default post audience: " + Prefs.defaultPrivacy(this),
                "2. Profile lock: " + (locked ? "ON 🔒" : "OFF"),
                "3. Friends list visibility: " + (me != null ? me.friendsListPrivacy : "Public"),
                "4. Email/phone visibility: " + (me != null && me.emailPrivacy != null ? me.emailPrivacy : "Only me"),
                "5. Blocked users (" + (me != null ? me.blocked.size() : 0) + ")"};
        Ui.choose(this, "🛡 5-Step Privacy Checkup", opts, w -> {
            if (w == 0) postsPrivacyDialog();
            else if (w == 1) {
                patchUser("profileLocked", !locked);
                if (Fb.me != null) Fb.me.profileLocked = !locked;
                recreate();
            } else if (w == 2 || w == 3) howPeopleFindDialog();
            else startActivity(new Intent(this, BlockedUsersActivity.class));
        });
    }

    private void snoozedDialog() {
        StringBuilder sb = new StringBuilder();
        List<String> uids = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String e : getSharedPreferences("fb_prefs", MODE_PRIVATE)
                .getStringSet("snoozed", new HashSet<>())) {
            int i = e.indexOf(':');
            if (i <= 0) continue;
            String uid = e.substring(0, i);
            long until;
            try { until = Long.parseLong(e.substring(i + 1)); } catch (Exception ex) { continue; }
            if (until <= now) continue;
            uids.add(uid);
            long days = (until - now) / 86400_000L + 1;
            sb.append(uid.substring(0, Math.min(8, uid.length()))).append("…  ·  ")
                    .append(days).append(" day(s) left\n");
        }
        if (uids.isEmpty()) { Ui.toast(this, "Nobody snoozed 😊"); return; }
        Ui.confirm(this, "Snoozed people", sb.toString().trim(), "Unsnooze everyone", () -> {
            for (String uid : uids) Prefs.unsnooze(this, uid);
            Ui.toast(this, "All unsnoozed ✓");
        });
    }

    private void exportData() {
        String me = Fb.uid();
        if (me == null) return;
        Ui.choose(this, "Download your information",
                new String[]{"📄 Readable text summary", "{ } JSON format (portable)"}, fmt -> {
                    Ui.toast(this, "Preparing your data export…");
                    Fb.db().collection("users").document(me).get().addOnSuccessListener(d -> {
                        Map<String, Object> m = d.getData();
                        StringBuilder sb = new StringBuilder();
                        if (fmt == 1) {
                            sb.append("{\n  \"exportedAt\": \"").append(new Date()).append("\",\n");
                            sb.append("  \"package\": \"com.friendbook.social\",\n");
                            if (m != null) {
                                int i = 0;
                                for (Map.Entry<String, Object> en : m.entrySet()) {
                                    sb.append("  \"").append(en.getKey()).append("\": \"")
                                            .append(String.valueOf(en.getValue()).replace("\"", "'")).append("\"");
                                    if (++i < m.size()) sb.append(",");
                                    sb.append("\n");
                                }
                            }
                            sb.append("}");
                        } else {
                            sb.append("friendbook data export — ")
                                    .append(java.text.DateFormat.getDateTimeInstance().format(new Date()))
                                    .append("\n\n");
                            if (m != null) for (Map.Entry<String, Object> en : m.entrySet()) {
                                sb.append(en.getKey()).append(": ").append(en.getValue()).append("\n");
                            }
                        }
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_TEXT, sb.toString());
                        startActivity(Intent.createChooser(share, "Export your data"));
                    }).addOnFailureListener(e -> Ui.toast(this, "Export failed"));
                });
    }

    private void verifiedSupport() {
        Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:zulqarnaindiraj786@gmail.com"));
        i.putExtra(Intent.EXTRA_SUBJECT, "✓ Creator / Verified support request — friendbook");
        i.putExtra(Intent.EXTRA_TEXT,
                "Account: " + (Fb.me != null ? Fb.me.displayName : "") + " (" + Fb.uid() + ")\n\n");
        try { startActivity(i); } catch (Exception e) { Ui.toast(this, "No email app installed"); }
    }

    private void verifiedInsights() {
        Ui.toast(this, "Calculating insights…");
        Fb.db().collection("posts").whereEqualTo("authorId", Fb.uid()).limit(300).get()
                .addOnSuccessListener(snap -> {
                    int posts = snap.size(), likes = 0, comments = 0, shares = 0;
                    for (DocumentSnapshot d : snap.getDocuments()) {
                        Long l = d.getLong("likes"); if (l != null) likes += l;
                        Long c = d.getLong("comments"); if (c != null) comments += c;
                        Long s = d.getLong("shares"); if (s != null) shares += s;
                    }
                    Models.User me = Fb.me;
                    Ui.info(this, "📊 Creator & Profile Insights",
                            "📝 Published posts: " + posts + "\n"
                                    + "❤️ Total reactions: " + likes + "\n"
                                    + "💬 Total comments: " + comments + "\n"
                                    + "↗ Total shares: " + shares + "\n"
                                    + "👥 Friends: " + (me != null ? me.friendsCount : 0) + "\n"
                                    + "👀 Followers: " + (me != null ? me.followers.size() : 0) + "\n"
                                    + "⭐ Stars earned: " + (me != null ? me.stars : 0) + "\n"
                                    + "🔥 Daily bonus streak: " + (me != null ? me.dailyStreak : 0) + " day(s)");
                });
    }

    private void helpDialog() {
        String[] opts = {
                "🔐 Login, 2FA & saved accounts",
                "📮 Feed, Archive & 30-day Trash",
                "📶 Data Saver, Lite Browser & Video Autoplay",
                "⭐ Stars, Ticks & Creator Monetization",
                "🐞 Report a problem to friendbook Team",
                "📧 Email support"};
        Ui.choose(this, "Help & Support Center", opts, w -> {
            if (w == 0) Ui.info(this, "🔐 Login & Security",
                    "• Quick-switch between accounts from Menu or Settings → Saved accounts.\n"
                            + "• Enable Two-step security PIN & Login Alerts for extra safety.\n"
                            + "• Temporarily deactivate your account anytime without losing data.");
            else if (w == 1) Ui.info(this, "📮 Archive & Recycle Bin",
                    "• Tap ••• on any of your posts → Move to Archive (hides without deleting) or Move to Trash (kept 30 days).\n"
                            + "• Past stories are saved in Settings → Archive & Recycle Bin → Story Archive, and can be added to Profile Highlights!");
            else if (w == 2) Ui.info(this, "📶 friendbook Lite Data Tools",
                    "• Data Saver compresses images up to 80% and pauses video autoplay.\n"
                            + "• External links open inside the fast built-in friendbook Lite Browser unless changed in Settings.");
            else if (w == 3) Ui.info(this, "⭐ Stars & Verification",
                    "• Invite 5 friends → FREE Blue ✓ · 20 → Gold ✓ · 50 → Diamond 💎.\n"
                            + "• Claim your Daily Bonus every day in Menu to grow your ⭐ streak.");
            else if (w == 4) reportProblemDialog();
            else {
                Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:zulqarnaindiraj786@gmail.com"));
                i.putExtra(Intent.EXTRA_SUBJECT, "friendbook — support request");
                try { startActivity(i); } catch (Exception e) { Ui.toast(this, "No email app installed"); }
            }
        });
    }

    private void reportProblemDialog() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText in = Ui.multi(this, "Describe what went wrong or suggest a feature…", 4);
        box.addView(in, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Report a problem")
                .setView(box)
                .setPositiveButton("Submit", (d, w) -> {
                    String txt = in.getText().toString().trim();
                    if (txt.isEmpty()) return;
                    Map<String, Object> m = new HashMap<>();
                    m.put("uid", Fb.uid());
                    m.put("name", Fb.myName());
                    m.put("text", txt);
                    m.put("app", "friendbook-" + Ui.appVersionName(this) + " (" + Ui.appVersionCode(this) + ")");
                    m.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());
                    Fb.db().collection("feedback").add(m);
                    Ui.toast(this, "Thanks! Report sent to friendbook Team ✓");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void changePassword() {
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText p1 = Ui.input(this, "New password (6+ characters)");
        p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(p1, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        EditText p2 = Ui.input(this, "Confirm new password");
        p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48));
        lp.topMargin = Ui.dp(this, 10);
        box.addView(p2, lp);
        new AlertDialog.Builder(this)
                .setTitle("Change password")
                .setView(box)
                .setPositiveButton("Update", (d, w) -> {
                    String a = p1.getText().toString();
                    if (a.length() < 6) { Ui.toast(this, "Password must be 6+ characters"); return; }
                    if (!a.equals(p2.getText().toString())) { Ui.toast(this, "Passwords don't match"); return; }
                    if (Fb.auth().getCurrentUser() == null) return;
                    Fb.auth().getCurrentUser().updatePassword(a)
                            .addOnSuccessListener(x -> Ui.toast(this, "Password updated ✓"))
                            .addOnFailureListener(e -> Ui.info(this, "Couldn't update",
                                    "For security, log out and log back in, then try again."));
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void pinDialog() {
        boolean has = !Prefs.pin(this).isEmpty();
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 20, 10, 20, 0);
        EditText pin = Ui.input(this, has ? "Enter new PIN (or leave empty to disable)" : "Choose a PIN (4+ digits)");
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        box.addView(pin, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)));
        new AlertDialog.Builder(this)
                .setTitle("App lock")
                .setMessage(has ? "App lock is ON. Enter a new PIN to change it, or leave empty to turn it off."
                        : "Lock friendbook behind a PIN every time it opens.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String p = pin.getText().toString().trim();
                    if (p.isEmpty()) {
                        Prefs.setPin(this, "");
                        Prefs.setUnlockedSession(this, true);
                        Ui.toast(this, "App lock disabled");
                    } else if (p.length() < 4) {
                        Ui.toast(this, "PIN must be at least 4 digits");
                    } else {
                        Prefs.setPin(this, p);
                        Prefs.setUnlockedSession(this, true);
                        Ui.toast(this, "App lock enabled 🔒");
                    }
                    recreate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        if (Fb.auth().getCurrentUser() != null) {
            Prefs.saveAccount(this, Fb.uid(), Fb.auth().getCurrentUser().getEmail(),
                    Fb.myName(), Fb.myPhoto());
        }
        Presence.goOffline();
        com.friendbook.social.core.FastCache.clearAll(this);
        com.friendbook.social.auth.GoogleAuthHelper.signOutGoogle(this);
        com.friendbook.social.push.PushManager.unregister(com.friendbook.social.App.instance);
                    Fb.auth().signOut();
        Fb.me = null;
        Prefs.setUnlockedSession(this, false);
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finishAffinity();
    }

    private void deleteAccount() {
        Ui.confirm(this, "Delete account & personal data permanently?",
                "Your friendbook profile, posts, reels, and stories will be permanently deleted. This cannot be undone.",
                "Delete forever", () -> {
                    if (Fb.auth().getCurrentUser() == null) return;
                    final String uid = Fb.uid();
                    final String em = Fb.auth().getCurrentUser().getEmail();
                    if (uid != null) {
                        String[] cols = {"posts", "reels", "stories", "comments"};
                        for (String col : cols) {
                            Fb.db().collection(col).whereEqualTo("authorId", uid).limit(200).get()
                                    .addOnSuccessListener(snap -> {
                                        for (DocumentSnapshot d : snap) d.getReference().delete();
                                    });
                        }
                        Fb.db().collection("users").document(uid).delete();
                    }
                    Fb.auth().getCurrentUser().delete()
                            .addOnSuccessListener(x -> {
                                if (em != null) Prefs.removeSavedAccount(this, em);
                                com.friendbook.social.core.FastCache.clearAll(this);
                                Fb.me = null;
                                Prefs.setUnlockedSession(this, false);
                                Intent i = new Intent(this, LoginActivity.class);
                                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(i);
                                finishAffinity();
                            })
                            .addOnFailureListener(e -> Ui.info(this, "Re-authentication needed",
                                    "For security, please log out, sign back in once, and tap Delete Account again."));
                });
    }
}

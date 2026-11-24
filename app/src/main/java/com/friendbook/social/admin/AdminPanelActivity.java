package com.friendbook.social.admin;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;

import com.friendbook.social.admin.frag.AdminModeratorsFragment;
import com.friendbook.social.admin.frag.AdminEarningsFragment;
import com.friendbook.social.admin.frag.AdminLiveFragment;
import com.friendbook.social.admin.frag.AdminPaymentsFragment;
import com.friendbook.social.admin.frag.AdminReferralsFragment;
import com.friendbook.social.admin.frag.AdminUsersFragment;
import com.friendbook.social.core.Admin;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Ui;
import com.friendbook.social.d1.documents.FieldValue;
import com.friendbook.social.d1.documents.WriteBatch;

import java.util.List;

/**
 * 🛡 Admin SUPER Dashboard — ONLY for zulqarnaindiraj786@gmail.com (or promoted admins).
 * Normal users never see it. Six tabs:
 *   1) Users    — every user + direct tick control (none/blue/gold/diamond), ban, promote
 *   2) Referrals— who invited how many
 *   3) Payments — every payment (ticks/boosts/subs) w/ screenshot + Approve/Reject
 *   4) Boosts   — approve/stop profile & post boosts
 *   5) Live     — all rooms, viewers, stars, end control
 *   6) Earnings — income by type, 70/30 split, monthly chart, tools
 */
public class AdminPanelActivity extends Activity {

    private FrameLayout container;
    private final ImageView[] tabIcons = new ImageView[6];
    private final TextView[] tabLabels = new TextView[6];
    private int active = -1;
    private Switch quotaSaverSwitch;
    private boolean bindingQuotaSaver;
    private final com.friendbook.social.core.AdsConfig.ChangeListener quotaSaverObserver=(unit,ads)->
            renderQuotaSaverSwitch();

    private static final String[] TAB_NAMES = {"Users", "Moderators", "Referrals", "Requests", "Live", "Analytics"};
    private static final int[] TAB_ICONS = {
            Icons.FRIENDS, Icons.SHIELD, Icons.USER_PLUS, Icons.BANKNOTE, Icons.LIVE, Icons.CHART};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Admin.isAdmin()) { Ui.toast(this, "Admins only"); finish(); return; }
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "🛡 Admin Panel"));
        TextView recovery = Ui.btnGrey(this, "Review legacy group / chat recovery");
        recovery.setOnClickListener(v -> showRecoveryKind());
        root.addView(recovery);
        TextView tools = Ui.btnGrey(this, "More admin tools");
        tools.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Admin tools")
                .setItems(new String[]{"Official Broadcast", "Reports", "Feedback", "Content", "AdMob"}, (dialog, action) -> {
                    Class<?>[] screens = {OfficialBroadcastActivity.class, AdminReportsActivity.class,
                            AdminFeedbackActivity.class, AdminContentActivity.class, AdMobSettingsActivity.class};
                    startActivity(new android.content.Intent(this, screens[action]));
                }).show());
        root.addView(tools);

        LinearLayout quotaRow=Ui.row(this);
        quotaRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(quotaRow,16,8,16,4);
        TextView quotaLabel=Ui.tvBold(this,"Global Quota Saver",14,Ui.txt(this));
        quotaRow.addView(quotaLabel,new LinearLayout.LayoutParams(0,-2,1));
        quotaSaverSwitch=new Switch(this);
        quotaSaverSwitch.setChecked(com.friendbook.social.core.AdsConfig.quotaSaverEnabled());
        quotaSaverSwitch.setOnCheckedChangeListener((button,on)->{
            if(bindingQuotaSaver)return;
            boolean old=!on;
            new AlertDialog.Builder(this).setTitle(on?"Enable Global Quota Saver?":"Disable Global Quota Saver?")
                    .setMessage(on
                            ?"Unchanged automatic list pages may be reused for up to 15 minutes. Manual refreshes, scrolling to more pages, writes, authorization and privacy checks still work. This is not a hard quota cap."
                            :"Automatic page refresh intervals return to each screen's normal setting.")
                    .setNegativeButton("Cancel",(dialog,which)->setQuotaSaverSwitch(old))
                    .setPositiveButton(on?"Turn ON":"Turn OFF",(dialog,which)->
                            com.friendbook.social.core.AdsConfig.saveQuotaSaver(on,this))
                    .setOnCancelListener(dialog->setQuotaSaverSwitch(old)).show();
        });
        quotaRow.addView(quotaSaverSwitch);
        root.addView(quotaRow);
        TextView quotaHint=Ui.tv(this,"Soft saver only · no per-user hard cap · manual actions stay available",11,Ui.sub(this));
        quotaHint.setPadding(Ui.dp(this,16),0,Ui.dp(this,16),Ui.dp(this,8));root.addView(quotaHint);

        root.addView(Ui.divider(this));

        container = new FrameLayout(this);
        container.setId(View.generateViewId());
        root.addView(container, new LinearLayout.LayoutParams(-1, 0, 1));

        // ---- 6 tabs (outline icons, blue active / gray idle — friendbook look) ----
        LinearLayout tabs = Ui.row(this);
        tabs.setBackgroundColor(Ui.card(this));
        tabs.setElevation(Ui.dp(this, 6));
        Ui.pad(tabs, 0, 6, 0, 6);
        for (int i = 0; i < 6; i++) {
            final int tab = i;
            LinearLayout cell = Ui.col(this);
            cell.setGravity(Gravity.CENTER);
            ImageView ic = Icons.icon(this, TAB_ICONS[i], 22, Icons.GRAY);
            tabIcons[i] = ic;
            cell.addView(ic);
            TextView lab = Ui.tv(this, TAB_NAMES[i], 9, Ui.sub(this));
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(0, Ui.dp(this, 2), 0, 0);
            tabLabels[i] = lab;
            cell.addView(lab);
            cell.setOnClickListener(v -> select(tab));
            tabs.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(Ui.divider(this));
        root.addView(tabs);

        setContentView(root);
        select(0);
    }

    private void setQuotaSaverSwitch(boolean enabled){
        if(quotaSaverSwitch==null)return;
        bindingQuotaSaver=true;quotaSaverSwitch.setChecked(enabled);bindingQuotaSaver=false;
    }
    private void renderQuotaSaverSwitch(){setQuotaSaverSwitch(com.friendbook.social.core.AdsConfig.quotaSaverEnabled());}
    @Override protected void onStart(){
        super.onStart();com.friendbook.social.core.AdsConfig.addPlacementListener(quotaSaverObserver);renderQuotaSaverSwitch();
    }
    @Override protected void onStop(){
        com.friendbook.social.core.AdsConfig.removePlacementListener(quotaSaverObserver);super.onStop();
    }

    public void select(int tab) {
        if (tab == active) return;
        active = tab;
        Fragment f;
        switch (tab) {
            case 1: f = new AdminModeratorsFragment(); break;
            case 2: f = new AdminReferralsFragment(); break;
            case 3: f = new AdminPaymentsFragment(); break;
            case 4: f = new AdminLiveFragment(); break;
            case 5: f = new AdminEarningsFragment(); break;
            default: f = new AdminUsersFragment(); break;
        }
        FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(container.getId(), f);
        ft.commitAllowingStateLoss();
        for (int i = 0; i < 6; i++) {
            boolean on = i == tab;
            Icons.setTint(tabIcons[i], on ? Icons.BLUE : Icons.GRAY);
            tabLabels[i].setTextColor(on ? Icons.BLUE : Icons.GRAY);
        }
    }

    private void showRecoveryKind() {
        if (!Admin.isAdmin()) return;
        new AlertDialog.Builder(this).setTitle("Review legacy recovery")
                .setItems(new String[]{"Group", "Group chat"}, (d, which) -> {
                    String kind = which == 0 ? "group" : "chat";
                    EditText idInput = Ui.input(this, which == 0 ? "Group ID" : "Group chat ID");
                    new AlertDialog.Builder(this).setTitle("Enter exact ID")
                            .setView(idInput).setNegativeButton("Cancel", null)
                            .setPositiveButton("Review", (dialog, button) -> {
                                String id = idInput.getText().toString().trim();
                                if (id.isEmpty() || id.contains("/")) { Ui.toast(this, "Enter a valid ID"); return; }
                                Fb.db().previewSocialRecovery(kind, id).addOnSuccessListener(preview -> {
                                    if (isFinishing() || !Admin.isAdmin()) return;
                                    String owner = preview.optString("owner", "");
                                    String revision = preview.optString("revision", "");
                                    org.json.JSONArray people = preview.optJSONArray("participants");
                                    if (owner.isEmpty() || revision.isEmpty() || people == null) {
                                        Ui.toast(this, "Review unavailable"); return;
                                    }
                                    StringBuilder message = new StringBuilder();
                                    message.append("Name: ").append(preview.optString("name", ""))
                                            .append("\nOwner UID: ").append(owner)
                                            .append("\nMembers (").append(people.length()).append("):\n");
                                    for (int i=0; i<people.length(); i++) message.append(people.optString(i)).append("\n");
                                    message.append("\nVerify the owner and every member independently before approving. ")
                                            .append("Approval restores their access; unverified extra admins are removed. ");
                                    if ("group".equals(kind)) message.append("Old pending requests, invites, bans and moderator roles are reset. ");
                                    message.append("The prior record is retained in the private recovery audit.");
                                    new AlertDialog.Builder(this).setTitle("Verify ownership and roster")
                                            .setMessage(message.toString()).setNegativeButton("Cancel", null)
                                            .setPositiveButton("I verified — restore", (confirm, x) ->
                                                    Fb.db().confirmSocialRecovery(kind, id, revision, owner)
                                                            .addOnSuccessListener(result -> Ui.toast(this, "Recovery completed"))
                                                            .addOnFailureListener(error -> Ui.toast(this, "Recovery failed or record changed. Review again.")))
                                            .show();
                                }).addOnFailureListener(error -> Ui.toast(this, "Cannot review this record. Check the ID and try again."));
                            }).show();
                }).setNegativeButton("Cancel", null).show();
    }

    /** Photo/video + linked text composer; delivery runs in durable paginated background work. */
    public void showBroadcastDialog() {
        startActivity(new android.content.Intent(this, OfficialBroadcastActivity.class));
    }
}

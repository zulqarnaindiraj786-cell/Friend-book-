package com.friendbook.social.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.friendbook.social.core.Cloudinary;
import com.friendbook.social.core.FastCache;
import com.friendbook.social.core.Fb;
import com.friendbook.social.core.Icons;
import com.friendbook.social.core.Prefs;
import com.friendbook.social.core.Ui;
import com.friendbook.social.models.Models;
import com.friendbook.social.profile.ProfileActivity;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 👥 Messenger Group Chat Info & Admin Control Center:
 * - Creator & Promoted Group Admins can:
 *     1) Remove ANY member (`jisko Dil kry remove kry`)
 *     2) Make ANY other member a Group Admin (`or wo Kisi AUR user ko bhi admin bna saky`) or dismiss admin
 *     3) Add new members to the group
 *     4) Toggle "Only Admins Can Send Messages"
 *     5) Change Group Name & Group Photo
 * - Regular members can view members, message privately, mute, or leave the group.
 */
public class GroupChatInfoActivity extends Activity {

    private static final int PICK_PHOTO = 702;

    private String chatId;
    private Models.ChatRoom room;
    private ListenerRegistration reg;

    private ImageView groupAvatar;
    private TextView groupTitleTv, groupSubTv;
    private LinearLayout adminCard, membersCol;
    private Switch onlyAdminsSwitch;
    private TextView editNameBtn, addMembersBtn, muteBtn, deleteGroupBtn;
    private boolean updatingSwitch = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);
        chatId = getIntent().getStringExtra("chat_id");
        if (chatId == null || chatId.isEmpty() || Fb.uid() == null) {
            finish();
            return;
        }

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "👥 Group Info & Settings"));
        root.addView(Ui.divider(this));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);
        Ui.pad(body, 12, 12, 12, 28);
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        // 1. Hero Group Card
        LinearLayout hero = Ui.col(this);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(hero, 16, 18, 16, 16);

        groupAvatar = new ImageView(this);
        int as = Ui.dp(this, 84);
        groupAvatar.setOnClickListener(v -> {
            if (room != null && room.isGroupAdmin(Fb.uid())) {
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.setType("image/*");
                startActivityForResult(pick, PICK_PHOTO);
            }
        });
        hero.addView(groupAvatar, new LinearLayout.LayoutParams(as, as));

        groupTitleTv = Ui.tvBold(this, "Group Chat", 20, Ui.txt(this));
        groupTitleTv.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        hero.addView(groupTitleTv);

        groupSubTv = Ui.tv(this, "Loading members…", 13, Ui.sub(this));
        hero.addView(groupSubTv);

        LinearLayout heroBtns = Ui.row(this);
        heroBtns.setGravity(Gravity.CENTER);
        heroBtns.setPadding(0, Ui.dp(this, 12), 0, 0);

        addMembersBtn = Ui.btn(this, "➕ Add Members");
        Ui.pad(addMembersBtn, 14, 8, 14, 8);
        addMembersBtn.setOnClickListener(v -> showAddMembersDialog());
        heroBtns.addView(addMembersBtn);

        editNameBtn = Ui.btnOutline(this, "✏️ Rename", Ui.BLUE);
        Ui.pad(editNameBtn, 14, 8, 14, 8);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-2, -2);
        elp.leftMargin = Ui.dp(this, 8);
        editNameBtn.setOnClickListener(v -> showRenameDialog());
        heroBtns.addView(editNameBtn, elp);
        hero.addView(heroBtns);
        body.addView(hero);

        // 2. Group Admin Settings Card (visible to Group Admins)
        adminCard = Ui.col(this);
        adminCard.setBackground(Ui.roundStroke(this, Ui.card(this), 14, Ui.BLUE, 1));
        Ui.pad(adminCard, 14, 12, 14, 12);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-1, -2);
        alp.topMargin = Ui.dp(this, 10);
        adminCard.setLayoutParams(alp);
        adminCard.addView(Ui.tvBold(this, "👑 Group Admin Controls", 15, Ui.BLUE));
        TextView tip = Ui.tv(this,
                "Tap any member below to Make Group Admin or Remove from Group.", 12, Ui.sub(this));
        tip.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 8));
        adminCard.addView(tip);

        LinearLayout lockRow = Ui.row(this);
        lockRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lockCol = Ui.col(this);
        lockCol.addView(Ui.tvBold(this, "🔒 Only Admins Can Send Messages", 13.5f, Ui.txt(this)));
        lockCol.addView(Ui.tv(this, "Turn on for announcement-only groups", 11.5f, Ui.sub(this)));
        lockRow.addView(lockCol, new LinearLayout.LayoutParams(0, -2, 1));
        onlyAdminsSwitch = new Switch(this);
        onlyAdminsSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSwitch || room == null || !room.isGroupAdmin(Fb.uid())) return;
            Fb.db().collection("chats").document(chatId).update("onlyAdminsCanSend", checked);
            postSystemMessage(checked
                    ? "🔒 " + Fb.myName() + " set group to Only Admins Can Send Messages"
                    : "🔓 " + Fb.myName() + " allowed all members to send messages");
        });
        lockRow.addView(onlyAdminsSwitch);
        adminCard.addView(lockRow);
        body.addView(adminCard);

        // 3. Members Section
        TextView memHead = Ui.tvBold(this, "Group Members", 15, Ui.txt(this));
        Ui.pad(memHead, 4, 14, 4, 6);
        body.addView(memHead);

        membersCol = Ui.col(this);
        body.addView(membersCol);

        // 4. Privacy & Leave Section
        LinearLayout actCard = Ui.col(this);
        actCard.setBackground(Ui.round(this, Ui.card(this), 14));
        Ui.pad(actCard, 14, 12, 14, 12);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = Ui.dp(this, 14);
        actCard.setLayoutParams(actLp);

        muteBtn = Ui.btnGrey(this, Prefs.isChatMuted(this, chatId) ? "🔔 Unmute Notifications" : "🔕 Mute Notifications");
        muteBtn.setOnClickListener(v -> {
            boolean next = !Prefs.isChatMuted(this, chatId);
            Prefs.muteChat(this, chatId, next);
            muteBtn.setText(next ? "🔔 Unmute Notifications" : "🔕 Mute Notifications");
            Ui.toast(this, next ? "Group muted 🔕" : "Group unmuted 🔔");
        });
        actCard.addView(muteBtn, new LinearLayout.LayoutParams(-1, -2));

        TextView leaveBtn = Ui.btnOutline(this, "🚪 Leave Group", Ui.RED);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = Ui.dp(this, 8);
        leaveBtn.setOnClickListener(v -> leaveGroup());
        actCard.addView(leaveBtn, llp);

        deleteGroupBtn = Ui.btnOutline(this, "🗑 Delete Group Permanently", Ui.RED);
        deleteGroupBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = Ui.dp(this, 8);
        deleteGroupBtn.setOnClickListener(v -> Ui.confirm(this, "Delete Group?",
                "Permanently delete this group chat for everyone?", "Delete", () ->
                        Fb.db().collection("chats").document(chatId).delete()
                                .addOnSuccessListener(x -> {
                                    Ui.toast(this, "Group deleted");
                                    finish();
                                })));
        actCard.addView(deleteGroupBtn, dlp);

        body.addView(actCard);
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        reg = Fb.db().collection("chats").document(chatId)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) { finish(); return; }
                    room = Models.ChatRoom.fromDoc(d);
                    renderRoom();
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (reg != null) reg.remove();
    }

    private void renderRoom() {
        if (room == null) return;
        String me = Fb.uid();
        boolean amAdmin = room.isGroupAdmin(me);

        String title = (room.groupName == null || room.groupName.isEmpty()) ? "Group Chat" : room.groupName;
        groupTitleTv.setText(title);
        groupSubTv.setText("👥 " + room.participants.size() + " members"
                + (amAdmin ? " · Tap photo to change" : ""));

        if (room.groupPhoto != null && !room.groupPhoto.isEmpty()) {
            groupAvatar.setPadding(0, 0, 0, 0);
            Ui.avatarLarge(this, groupAvatar, room.groupPhoto);
        } else {
            groupAvatar.setBackground(Ui.circle(Ui.BLUE));
            int p = Ui.dp(this, 20);
            groupAvatar.setPadding(p, p, p, p);
            groupAvatar.setImageDrawable(Icons.tinted(this, Icons.GROUPS, Color.WHITE));
        }

        adminCard.setVisibility(amAdmin ? View.VISIBLE : View.GONE);
        addMembersBtn.setVisibility(amAdmin ? View.VISIBLE : View.GONE);
        editNameBtn.setVisibility(amAdmin ? View.VISIBLE : View.GONE);
        deleteGroupBtn.setVisibility(me != null && me.equals(room.createdBy) ? View.VISIBLE : View.GONE);

        updatingSwitch = true;
        onlyAdminsSwitch.setChecked(room.onlyAdminsCanSend);
        updatingSwitch = false;

        membersCol.removeAllViews();
        for (String uid : room.participants) {
            membersCol.addView(buildMemberRow(uid, amAdmin));
        }
    }

    private View buildMemberRow(String memberUid, boolean amAdmin) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.round(this, Ui.card(this), 12));
        Ui.pad(row, 12, 10, 12, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(this, 6);
        row.setLayoutParams(lp);

        final ImageView av = new ImageView(this);
        int s = Ui.dp(this, 44);
        row.addView(av, new LinearLayout.LayoutParams(s, s));

        LinearLayout mid = Ui.col(this);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1);
        mp.leftMargin = Ui.dp(this, 10);
        LinearLayout nameRow = Ui.row(this);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        final TextView nameTv = Ui.tvBold(this,
                room.participantNames.containsKey(memberUid) ? room.participantNames.get(memberUid) : "Member",
                14.5f, Ui.txt(this));
        nameRow.addView(nameTv);

        final ImageView tk = new ImageView(this);
        int ts = Ui.dp(this, 14);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ts, ts);
        tlp.leftMargin = Ui.dp(this, 4);
        nameRow.addView(tk, tlp);

        boolean isCreator = memberUid.equals(room.createdBy);
        boolean isMemberAdmin = room.isGroupAdmin(memberUid);
        if (isCreator || isMemberAdmin) {
            TextView badge = Ui.roundChip(this,
                    isCreator ? "👑 Creator · Admin" : "🛡 Admin",
                    isCreator ? Ui.ORANGE : Ui.BLUE, Color.WHITE, 9.5f);
            Ui.pad(badge, 7, 2, 7, 2);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
            blp.leftMargin = Ui.dp(this, 6);
            nameRow.addView(badge, blp);
        }
        mid.addView(nameRow);
        TextView roleSub = Ui.tv(this,
                memberUid.equals(Fb.uid()) ? "You" : (amAdmin && !isCreator ? "Tap to manage role or remove" : "Tap for options"),
                11.5f, Ui.sub(this));
        mid.addView(roleSub);
        row.addView(mid, mp);

        TextView more = Ui.tvBold(this, "⋯", 18, Ui.sub(this));
        more.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), 0);
        row.addView(more);

        String fallbackPhoto = room.participantPhotos.get(memberUid);
        Ui.avatar(this, av, fallbackPhoto);
        Fb.getUser(memberUid).addOnSuccessListener(u -> {
            if (u == null || isFinishing()) return;
            FastCache.putUser(u);
            nameTv.setText(u.displayName);
            Ui.avatar(this, av, u.photoUrl);
            Icons.setTickBadge(tk, u.tickLevel, false);
        });

        row.setOnClickListener(v -> showMemberMenu(v, memberUid, nameTv.getText().toString(), amAdmin));
        return row;
    }

    private void showMemberMenu(View anchor, String memberUid, String memberName, boolean amAdmin) {
        String me = Fb.uid();
        if (me == null) return;
        if (me.equals(memberUid)) {
            startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", me));
            return;
        }
        boolean targetIsCreator = memberUid.equals(room.createdBy);
        boolean targetIsAdmin = room.isGroupAdmin(memberUid);

        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "👤 View " + memberName + "'s Profile");
        pm.getMenu().add(0, 2, 0, "💬 Message Privately");
        if (amAdmin && !targetIsCreator) {
            pm.getMenu().add(0, 3, 0, targetIsAdmin
                    ? "➖ Dismiss as Group Admin"
                    : "🛡 Make Group Admin");
            pm.getMenu().add(0, 4, 0, "🚫 Remove " + memberName + " from Group");
        }
        pm.setOnMenuItemClickListener(it -> {
            int id = it.getItemId();
            if (id == 1) {
                startActivity(new Intent(this, ProfileActivity.class).putExtra("uid", memberUid));
            } else if (id == 2) {
                Intent chat = new Intent(this, ChatActivity.class);
                chat.putExtra("uid", memberUid);
                chat.putExtra("name", memberName);
                startActivity(chat);
            } else if (id == 3) {
                Fb.db().collection("chats").document(chatId).update("adminIds",
                        targetIsAdmin ? FieldValue.arrayRemove(memberUid) : FieldValue.arrayUnion(memberUid))
                        .addOnSuccessListener(x -> {
                            postSystemMessage(targetIsAdmin
                                    ? "➖ " + Fb.myName() + " dismissed " + memberName + " as admin"
                                    : "🛡 " + Fb.myName() + " made " + memberName + " a Group Admin");
                            Ui.toast(this, targetIsAdmin ? "Admin dismissed" : memberName + " is now a Group Admin 🛡");
                        });
            } else if (id == 4) {
                Ui.confirm(this, "Remove " + memberName + "?",
                        "Remove " + memberName + " from this group chat?",
                        "Remove", () -> {
                            Map<String, Object> up = new HashMap<>();
                            up.put("participants", FieldValue.arrayRemove(memberUid));
                            up.put("adminIds", FieldValue.arrayRemove(memberUid));
                            Fb.db().collection("chats").document(chatId).update(up)
                                    .addOnSuccessListener(x -> {
                                        postSystemMessage("🚫 " + Fb.myName() + " removed " + memberName);
                                        Ui.toast(this, "Removed " + memberName);
                                    });
                        });
            }
            return true;
        });
        pm.show();
    }

    private void showRenameDialog() {
        if (room == null) return;
        EditText in = Ui.input(this, "Group name");
        in.setText(room.groupName);
        LinearLayout box = Ui.col(this);
        Ui.pad(box, 18, 10, 18, 0);
        box.addView(in);
        new AlertDialog.Builder(this)
                .setTitle("✏️ Rename Group")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String n = in.getText().toString().trim();
                    if (n.isEmpty()) return;
                    Fb.db().collection("chats").document(chatId).update("groupName", n);
                    postSystemMessage("✏️ " + Fb.myName() + " changed group name to \"" + n + "\"");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddMembersDialog() {
        if (room == null) return;
        LinearLayout list = Ui.col(this);
        Ui.pad(list, 14, 10, 14, 10);
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        final Set<Models.User> picked = new LinkedHashSet<>();

        Fb.db().collection("users").limit(200).get().addOnSuccessListener(snap -> {
            if (snap == null || isFinishing()) return;
            for (DocumentSnapshot d : snap.getDocuments()) {
                Models.User u = Models.User.fromDoc(d);
                if (room.participants.contains(u.uid) || u.banned) continue;
                LinearLayout row = Ui.row(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                Ui.pad(row, 4, 7, 4, 7);
                ImageView av = new ImageView(this);
                Ui.avatar(this, av, u.photoUrl);
                int s = Ui.dp(this, 36);
                row.addView(av, new LinearLayout.LayoutParams(s, s));
                TextView nm = Ui.tvBold(this, u.displayName, 14, Ui.txt(this));
                nm.setPadding(Ui.dp(this, 10), 0, 0, 0);
                row.addView(nm, new LinearLayout.LayoutParams(0, -2, 1));
                CheckBox cb = new CheckBox(this);
                cb.setOnCheckedChangeListener((b, ch) -> {
                    if (ch) picked.add(u); else picked.remove(u);
                });
                row.addView(cb);
                row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));
                list.addView(row);
            }
            if (list.getChildCount() == 0) {
                list.addView(Ui.tv(this, "Everyone is already in this group!", 13, Ui.sub(this)));
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("➕ Add Members to Group")
                .setView(sv)
                .setPositiveButton("Add Selected", (d, w) -> {
                    if (picked.isEmpty()) return;
                    Map<String, String> names = new HashMap<>(room.participantNames);
                    Map<String, String> photos = new HashMap<>(room.participantPhotos);
                    List<String> newUids = new ArrayList<>();
                    StringBuilder sb = new StringBuilder();
                    for (Models.User u : picked) {
                        newUids.add(u.uid);
                        names.put(u.uid, u.displayName);
                        photos.put(u.uid, u.photoUrl == null ? "" : u.photoUrl);
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(u.displayName);
                    }
                    Map<String, Object> patch = new HashMap<>();
                    patch.put("participants", FieldValue.arrayUnion(newUids.toArray()));
                    patch.put("participantNames", names);
                    patch.put("participantPhotos", photos);
                    Fb.db().collection("chats").document(chatId).set(patch, SetOptions.merge())
                            .addOnSuccessListener(x -> {
                                postSystemMessage("➕ " + Fb.myName() + " added " + sb);
                                Ui.toast(this, "Added " + newUids.size() + " member(s) ✓");
                            });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void leaveGroup() {
        String me = Fb.uid();
        if (me == null || room == null) return;
        Ui.confirm(this, "Leave \"" + room.groupName + "\"?",
                "You won't receive new messages from this group.", "Leave", () -> {
                    Map<String, Object> up = new HashMap<>();
                    up.put("participants", FieldValue.arrayRemove(me));
                    up.put("adminIds", FieldValue.arrayRemove(me));
                    // If creator leaves and no other admin exists, promote next remaining member!
                    if (me.equals(room.createdBy)) {
                        for (String p : room.participants) {
                            if (!p.equals(me)) {
                                up.put("createdBy", p);
                                up.put("adminIds", FieldValue.arrayUnion(p));
                                break;
                            }
                        }
                    }
                    postSystemMessage("🚪 " + Fb.myName() + " left the group");
                    Fb.db().collection("chats").document(chatId).update(up)
                            .addOnSuccessListener(x -> {
                                Ui.toast(this, "You left the group");
                                finish();
                            });
                });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_PHOTO && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            Ui.toast(this, "Updating group photo…");
            String path = "group_chats/" + chatId + "_" + System.currentTimeMillis() + ".jpg";
            Cloudinary.uploadOrFirebase(this, path, uri)
                    .addOnSuccessListener(url -> {
                        Fb.db().collection("chats").document(chatId).update("groupPhoto", url.toString());
                        postSystemMessage("📷 " + Fb.myName() + " updated the group photo");
                    })
                    .addOnFailureListener(e -> Ui.toast(this, "Photo upload failed"));
        }
    }

    private void postSystemMessage(String text) {
        long now = System.currentTimeMillis();
        Map<String, Object> m = new HashMap<>();
        m.put("senderId", Fb.uid());
        m.put("senderName", Fb.myName());
        m.put("text", text);
        m.put("type", "system");
        m.put("isDelivered", true);
        m.put("isSeen", true);
        m.put("createdAtMs", now);
        m.put("createdAt", FieldValue.serverTimestamp());
        Fb.db().collection("chats").document(chatId).collection("messages").add(m);
        Fb.db().collection("chats").document(chatId).set(Fb.map(
                "lastMessage", text,
                "lastMessageTime", FieldValue.serverTimestamp(),
                "lastMessageTimeMs", now), SetOptions.merge());
    }
}

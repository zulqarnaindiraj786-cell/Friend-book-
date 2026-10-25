package com.friendbook.profile;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.friendbook.core.Cloudinary;
import com.friendbook.core.Fb;
import com.friendbook.core.Ui;
import com.friendbook.models.Models;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ✏️ EditProfileActivity — Full Facebook-Lite Profile & Intro Editor:
 * Avatar, Cover, Name, Pronouns, Bio, Work, Education, Current City, Hometown,
 * Relationship Status, Birthday, Website & Hobbies.
 */
public class EditProfileActivity extends Activity {

    private static final int PICK_AVATAR = 61, PICK_COVER = 62;
    private static final String[] REL_OPTS = {
            "", "Single", "In a relationship", "Engaged", "Married", "It's complicated"
    };

    private ImageView cover, avatar;
    private EditText name, pronouns, bio, work, education, currentCity, hometown, website, hobbies;
    private TextView save, birthdayBtn, relBtn;
    private String birthday = "", relationship = "";
    private ListenerRegistration userReg;
    private String avatarUrl = "", coverUrl = "";
    private boolean busy, loadedOnce;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.statusBarBlue(this);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.bg(this));
        root.addView(Ui.pageBar(this, "Edit profile"));

        ScrollView sv = new ScrollView(this);
        LinearLayout body = Ui.col(this);

        // Cover photo
        FrameLayout coverWrap = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverWrap.addView(cover, new FrameLayout.LayoutParams(-1, Ui.dp(this, 150)));
        TextView coverBtn = Ui.btnGrey(this, "🖼 Change cover");
        FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        cbp.setMargins(0, 0, Ui.dp(this, 10), Ui.dp(this, 10));
        coverWrap.addView(coverBtn, cbp);
        body.addView(coverWrap, new LinearLayout.LayoutParams(-1, Ui.dp(this, 150)));
        coverBtn.setOnClickListener(v -> pick(PICK_COVER));

        // Avatar
        LinearLayout avRow = Ui.row(this);
        avRow.setGravity(Gravity.CENTER_VERTICAL);
        Ui.pad(avRow, 14, 16, 14, 4);
        avatar = new ImageView(this);
        int s = Ui.dp(this, 84);
        avRow.addView(avatar, new LinearLayout.LayoutParams(s, s));
        TextView avBtn = Ui.btnGrey(this, "📷 Change photo");
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-2, -2);
        abp.leftMargin = Ui.dp(this, 16);
        avRow.addView(avBtn, abp);
        body.addView(avRow);
        avBtn.setOnClickListener(v -> pick(PICK_AVATAR));

        LinearLayout form = Ui.col(this);
        Ui.pad(form, 14, 12, 14, 14);

        name = addField(form, "Display name", "Your full name");
        pronouns = addField(form, "💬 Pronouns (optional)", "he/him · she/her · they/them");

        form.addView(label("Bio"));
        bio = Ui.multi(this, "Describe yourself in a short bio…", 3);
        form.addView(bio, lpm());
        form.addView(sp(12));

        TextView introHead = Ui.tvBold(this, "Intro & Public Details", 15, Ui.BLUE);
        introHead.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        form.addView(introHead);

        work = addField(form, "💼 Work", "Company or job title (e.g. Software Engineer at Systems)");
        education = addField(form, "🎓 Education", "University, college or high school");
        currentCity = addField(form, "📍 Current city", "Where do you live? (e.g. Lahore / Frankfurt)");
        hometown = addField(form, "🏠 Hometown", "Where are you from?");

        form.addView(label("❤️ Relationship status"));
        relBtn = Ui.btnGrey(this, "Select relationship status");
        relBtn.setMinHeight(Ui.dp(this, 44));
        relBtn.setOnClickListener(v -> {
            String[] labels = {"Not shown", "Single", "In a relationship", "Engaged", "Married", "It's complicated"};
            Ui.choose(this, "❤️ Relationship status", labels, w -> {
                relationship = REL_OPTS[w];
                relBtn.setText(relationship.isEmpty() ? "Not shown" : "❤️ " + relationship);
            });
        });
        form.addView(relBtn, lpm());
        form.addView(sp(10));

        form.addView(label("🎂 Birthday"));
        birthdayBtn = Ui.btnGrey(this, "Set your birthday");
        birthdayBtn.setMinHeight(Ui.dp(this, 44));
        birthdayBtn.setOnClickListener(v -> pickBirthday());
        form.addView(birthdayBtn, lpm());
        form.addView(sp(10));

        website = addField(form, "🌐 Website or social link", "https://yourportfolio.com");
        hobbies = addField(form, "🎯 Hobbies (comma separated)", "e.g. Cricket, Photography, Coding, Travel");

        body.addView(form);

        save = Ui.btn(this, "Save changes");
        LinearLayout.LayoutParams sp2 = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
        sp2.setMargins(Ui.dp(this, 14), Ui.dp(this, 4), Ui.dp(this, 14), Ui.dp(this, 22));
        body.addView(save, sp2);
        save.setOnClickListener(v -> save());

        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private TextView label(String text) {
        return Ui.tvBold(this, text, 13, Ui.sub(this));
    }

    private EditText addField(LinearLayout form, String title, String hint) {
        form.addView(label(title));
        EditText e = Ui.input(this, hint);
        form.addView(e, lpm());
        form.addView(sp(10));
        return e;
    }

    private View sp(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, Ui.dp(this, h)));
        return v;
    }

    private LinearLayout.LayoutParams lpm() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = Ui.dp(this, 4);
        return p;
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Fb.uid() == null) { finish(); return; }
        userReg = Fb.db().collection("users").document(Fb.uid())
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists()) return;
                    Models.User u = Models.User.fromDoc(d);
                    avatarUrl = u.photoUrl;
                    coverUrl = u.coverUrl;
                    Ui.avatar(this, avatar, avatarUrl);
                    if (!coverUrl.isEmpty()) Ui.img(this, cover, coverUrl);
                    else cover.setBackground(Ui.round(this, Ui.div(this), 0));

                    if (loadedOnce) return;
                    loadedOnce = true;
                    name.setText(u.displayName);
                    name.setSelection(name.getText().length());
                    pronouns.setText(u.pronouns);
                    bio.setText(u.bio);
                    work.setText(u.work);
                    education.setText(u.education);
                    currentCity.setText(u.currentCity);
                    hometown.setText(u.hometown);
                    website.setText(u.website);
                    if (u.hobbies != null && !u.hobbies.isEmpty())
                        hobbies.setText(android.text.TextUtils.join(", ", u.hobbies));
                    relationship = u.relationship == null ? "" : u.relationship;
                    relBtn.setText(relationship.isEmpty() ? "Select relationship status" : "❤️ " + relationship);
                    birthday = u.birthday == null ? "" : u.birthday;
                    birthdayBtn.setText(birthday.isEmpty() ? "Set your birthday"
                            : "🎂 " + birthday + "  (tap to change)");
                });
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (userReg != null) userReg.remove();
    }

    private void pickBirthday() {
        Calendar cal = Calendar.getInstance();
        if (!birthday.isEmpty()) {
            try {
                String[] parts = birthday.split("/");
                cal.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[0]));
                cal.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                if (parts.length > 2) cal.set(Calendar.YEAR, Integer.parseInt(parts[2]));
            } catch (Exception ignored) { }
        }
        new DatePickerDialog(this, (vw, y, m, d) -> {
            birthday = String.format(Locale.US, "%02d/%02d/%04d", d, m + 1, y);
            birthdayBtn.setText("🎂 " + birthday + "  (tap to change)");
        }, cal.get(Calendar.YEAR) - 20,
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void pick(int code) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        startActivityForResult(i, code);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        boolean isAvatar = req == PICK_AVATAR;
        Ui.toast(this, "Uploading…");
        String path = "profiles/" + Fb.uid() + "/" + (isAvatar ? "avatar" : "cover")
                + "_" + System.currentTimeMillis() + ".jpg";
        Cloudinary.uploadOrFirebase(this, path, uri)
                .addOnSuccessListener(url -> {
                    Map<String, Object> patch = new HashMap<>();
                    patch.put(isAvatar ? "photoUrl" : "coverUrl", url.toString());
                    Fb.patchMe(patch);
                    Ui.toast(this, isAvatar ? "Profile photo updated ✓" : "Cover updated ✓");
                })
                .addOnFailureListener(e -> Ui.toast(this, "Upload failed"));
    }

    private void save() {
        if (busy) return;
        String n = name.getText().toString().trim();
        if (n.length() < 2) { Ui.toast(this, "Name is too short"); return; }
        busy = true;
        save.setText("Saving…");

        List<String> hobbyList = new ArrayList<>();
        String rawH = hobbies.getText().toString();
        for (String part : rawH.split(",")) {
            String h = part.trim();
            if (!h.isEmpty()) hobbyList.add(h);
        }

        Map<String, Object> m = new HashMap<>();
        m.put("displayName", n);
        m.put("pronouns", pronouns.getText().toString().trim());
        m.put("bio", bio.getText().toString().trim());
        m.put("work", work.getText().toString().trim());
        m.put("education", education.getText().toString().trim());
        m.put("currentCity", currentCity.getText().toString().trim());
        m.put("hometown", hometown.getText().toString().trim());
        m.put("relationship", relationship == null ? "" : relationship);
        m.put("website", website.getText().toString().trim());
        m.put("hobbies", hobbyList);
        m.put("birthday", birthday == null ? "" : birthday);

        Fb.db().collection("users").document(Fb.uid()).set(m, SetOptions.merge())
                .addOnSuccessListener(x -> {
                    Ui.toast(this, "Profile & Intro updated ✓");
                    finish();
                })
                .addOnFailureListener(e -> {
                    busy = false;
                    save.setText("Save changes");
                    Ui.toast(this, e.getMessage());
                });
    }
}

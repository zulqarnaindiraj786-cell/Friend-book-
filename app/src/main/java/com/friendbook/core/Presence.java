package com.friendbook.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.SetOptions;

import java.util.HashMap;
import java.util.Map;

/**
 * Online/offline presence + typing indicators backed by the Firebase Realtime
 * Database (with onDisconnect) and dual-written to Firestore user docs so the
 * Flutter app sees the same state.
 */
public class Presence {

    public interface Callback { void onPresence(boolean online, long lastSeen); }

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static DatabaseReference statusRef;
    private static ValueEventListener connListener;

    public static void init() {
        String uid = Fb.uid();
        if (uid == null) return;
        if (Fb.rtdb() == null) { writeFirestore(true); return; }
        statusRef = Fb.rtdb().getReference("status").child(uid);
        DatabaseReference connected = Fb.rtdb().getReference(".info/connected");
        if (connListener != null) connected.removeEventListener(connListener);
        connListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean ok = snap.getValue(Boolean.class);
                if (ok == null || !ok || Fb.uid() == null) return;
                Map<String, Object> online = new HashMap<>();
                online.put("state", "online");
                online.put("lastChanged", ServerValue.TIMESTAMP);
                Map<String, Object> offline = new HashMap<>();
                offline.put("state", "offline");
                offline.put("lastChanged", ServerValue.TIMESTAMP);
                statusRef.onDisconnect().setValue(offline);
                if (Prefs.activeStatus(com.friendbook.App.instance)) {
                    statusRef.setValue(online);
                    writeFirestore(true);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        connected.addValueEventListener(connListener);
    }

    private static void writeFirestore(boolean online) {
        String uid = Fb.uid();
        if (uid == null) return;
        Map<String, Object> m = new HashMap<>();
        m.put("isOnline", online && Prefs.activeStatus(com.friendbook.App.instance));
        if (!online) m.put("lastSeen", com.google.firebase.firestore.FieldValue.serverTimestamp());
        Fb.db().collection("users").document(uid).set(m, SetOptions.merge());
    }

    public static void goOffline() {
        writeFirestore(false);
        if (statusRef != null && Fb.uid() != null) {
            Map<String, Object> offline = new HashMap<>();
            offline.put("state", "offline");
            offline.put("lastChanged", ServerValue.TIMESTAMP);
            statusRef.setValue(offline);
        }
    }

    /** Handle to a live presence watcher; close() to detach. */
    public static class Handle {
        private ValueEventListener rtdbL;
        private DatabaseReference ref;
        private ListenerRegistration fsL;
        public void close() {
            if (ref != null && rtdbL != null) ref.removeEventListener(rtdbL);
            if (fsL != null) fsL.remove();
        }
    }

    /** Watch a user's presence: RTDB first, Firestore fallback. */
    public static Handle watch(String uid, Callback cb) {
        Handle h = new Handle();
        final boolean[] rtdbSeen = {false};
        final boolean[] online = {false};
        final long[] lastSeen = {0};
        Runnable emit = () -> cb.onPresence(online[0], lastSeen[0]);

        if (Fb.rtdb() != null) {
            h.ref = Fb.rtdb().getReference("status").child(uid);
            h.rtdbL = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (s.exists()) {
                        rtdbSeen[0] = true;
                        online[0] = "online".equals(s.child("state").getValue(String.class));
                        Long lc = s.child("lastChanged").getValue(Long.class);
                        if (lc != null) lastSeen[0] = lc;
                        emit.run();
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            };
            h.ref.addValueEventListener(h.rtdbL);
        }
        h.fsL = Fb.db().collection("users").document(uid)
                .addSnapshotListener((d, err) -> {
                    if (d == null || !d.exists() || rtdbSeen[0]) return;
                    Boolean o = d.getBoolean("isOnline");
                    online[0] = o != null && o;
                    lastSeen[0] = Format.ts(d.get("lastSeen"));
                    emit.run();
                });
        return h;
    }

    // ---- typing ----
    private static final Map<String, Runnable> typingClear = new HashMap<>();

    public static void setTyping(String chatId, boolean typing) {
        String uid = Fb.uid();
        if (uid == null || chatId == null) return;
        Map<String, Object> inner = new HashMap<>();
        inner.put(uid, typing);
        Map<String, Object> m = new HashMap<>();
        m.put("typing", inner);
        Fb.db().collection("chats").document(chatId).set(m, SetOptions.merge());

        // Fast path through RTDB too (auto-expires after 4s unless refreshed).
        if (Fb.rtdb() != null) {
            DatabaseReference ref = Fb.rtdb().getReference("typing").child(chatId).child(uid);
            ref.setValue(typing);
            Runnable old = typingClear.remove(chatId);
            if (old != null) H.removeCallbacks(old);
            if (typing) {
                Runnable clear = () -> {
                    ref.setValue(false);
                    Map<String, Object> off = new HashMap<>();
                    off.put(uid, false);
                    Map<String, Object> wrap = new HashMap<>();
                    wrap.put("typing", off);
                    Fb.db().collection("chats").document(chatId).set(wrap, SetOptions.merge());
                };
                typingClear.put(chatId, clear);
                H.postDelayed(clear, 4000);
            }
        }
    }

    /** Watch whether the OTHER participant of a 1:1 chat is typing. Firestore is the
     *  interoperable source; RTDB is a low-latency bonus. Callback gets "peer typing". */
    public static Handle watchTyping(String chatId, String myUid, java.util.function.Consumer<Boolean> cb) {
        Handle h = new Handle();
        final boolean[] fsTyping = {false};
        final boolean[] rtdbTyping = {false};
        Runnable emit = () -> cb.accept(fsTyping[0] || rtdbTyping[0]);

        h.fsL = Fb.db().collection("chats").document(chatId)
                .addSnapshotListener((d, err) -> {
                    fsTyping[0] = false;
                    if (d != null && d.exists()) {
                        Object t = d.get("typing");
                        if (t instanceof Map) {
                            for (Map.Entry<?, ?> e : ((Map<?, ?>) t).entrySet()) {
                                if (!String.valueOf(e.getKey()).equals(myUid)
                                        && Boolean.TRUE.equals(e.getValue())) {
                                    fsTyping[0] = true;
                                    break;
                                }
                            }
                        }
                    }
                    emit.run();
                });
        if (Fb.rtdb() != null) {
            h.ref = Fb.rtdb().getReference("typing").child(chatId);
            h.rtdbL = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    rtdbTyping[0] = false;
                    for (DataSnapshot c : s.getChildren()) {
                        if (!myUid.equals(c.getKey()) && Boolean.TRUE.equals(c.getValue(Boolean.class))) {
                            rtdbTyping[0] = true;
                            break;
                        }
                    }
                    emit.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { }
            };
            h.ref.addValueEventListener(h.rtdbL);
        }
        return h;
    }
}

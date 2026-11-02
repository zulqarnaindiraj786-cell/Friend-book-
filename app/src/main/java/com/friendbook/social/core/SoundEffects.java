package com.friendbook.social.core;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.friendbook.social.App;
import com.friendbook.social.R;
import java.util.*;

/** Small original foreground effects; no audio-focus request, player interruption or background roar. */
public final class SoundEffects {
    public static final String REACTION="reaction", COMMENT="comment", SENT="sent", RECEIVED="received";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SoundPool pool;
    private static final Map<String,Integer> samples = new HashMap<>();
    private static final Set<Integer> loaded = new HashSet<>();
    private static final Map<String,Long> lastPlayed = new HashMap<>();
    private static final Map<Integer,Runnable> pending = new HashMap<>();
    private static final List<Integer> streams = new ArrayList<>();
    private static int giftStream;
    private static volatile String visibleChat;
    private SoundEffects() {}
    private static void onMain(Runnable work) {
        if (Looper.myLooper()==Looper.getMainLooper()) work.run(); else MAIN.post(work);
    }
    public static long ticket() { return SystemClock.elapsedRealtime(); }
    public static void visibleChat(String id) { visibleChat=id; }
    public static void clearVisibleChat(String id) { if (Objects.equals(id,visibleChat)) visibleChat=null; }
    public static boolean isVisibleChat(String id) { return id!=null && id.equals(visibleChat) && App.isForeground(); }
    private static boolean allowed(Context context) {
        if (context==null || !App.isForeground() || Prefs.quiet(context) || !Prefs.inAppSounds(context)) return false;
        try {
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        NotificationManager notifications=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        return audio!=null && audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL
                && audio.getStreamVolume(AudioManager.STREAM_MUSIC)>0
                && (notifications==null || notifications.getCurrentInterruptionFilter()==NotificationManager.INTERRUPTION_FILTER_ALL);
        } catch (RuntimeException ignored) { return false; }
    }
    public static void warmUp(Context context) {
        Context app=context.getApplicationContext();
        onMain(() -> {
            if (pool!=null) return;
            try {
            SoundPool current=new SoundPool.Builder().setMaxStreams(3)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
            pool=current;
            current.setOnLoadCompleteListener((sp,id,status) -> {
                if (pool!=sp || status!=0) return;
                loaded.add(id); Runnable action=pending.remove(id); if (action!=null) action.run();
            });
            samples.put(REACTION,current.load(app,R.raw.sfx_reaction,1));
            samples.put(COMMENT,current.load(app,R.raw.sfx_comment,1));
            samples.put(SENT,current.load(app,R.raw.sfx_message_sent,1));
            samples.put(RECEIVED,current.load(app,R.raw.sfx_message_received,1));
            // Load tiny interaction sounds before the longer live effects.
            samples.put("lion",current.load(app,R.raw.sfx_lion_roar,1));
            samples.put("airplane",current.load(app,R.raw.sfx_airplane,1));
            samples.put("gift",current.load(app,R.raw.sfx_gift,1));
            } catch (RuntimeException error) {
                if(pool!=null) pool.release(); pool=null; samples.clear(); loaded.clear(); pending.clear();
                android.util.Log.w("FriendBookSound", "Sound output unavailable; continuing silently");
            }
        });
    }
    /** Immediate local action feedback, not a server-delivery acknowledgement. */
    public static void action(Context c,String effect,String actor) {
        if (actor==null || !actor.equals(Fb.uid())) return;
        play(c,effect,false);
    }
    public static void incoming(Context c) { if (c!=null && Prefs.notifMessages(c)) play(c,RECEIVED,false); }
    public static void gift(Context c,String id,String emoji,String name) {
        play(c,GiftSoundPolicy.effect(id,emoji,name),true);
    }
    private static void play(Context c,String effect,boolean gift) {
        if(c==null) return;
        Context app=c.getApplicationContext(); long requested=ticket(); String actor=Fb.uid();
        if (!allowed(app)) return;
        warmUp(app);
        onMain(() -> {
            if (!allowed(app) || pool==null) return;
            Integer sample=samples.get(effect); if (sample==null) return;
            Runnable action=() -> {
                if (pool==null || !Objects.equals(actor,Fb.uid()) || !allowed(app) || ticket()-requested>120L) return;
                String lane=gift ? "gift" : effect;
                long previous=lastPlayed.getOrDefault(lane,0L);
                if (previous>0 && ticket()-previous<(gift ? 1400L : 160L)) return;
                lastPlayed.put(lane,ticket());
                if (gift && giftStream!=0) pool.stop(giftStream);
                float volume=gift ? .42f : .85f;
                int stream=pool.play(sample,volume,volume,gift?2:1,0,1f);
                if (gift) giftStream=stream;
                streams.add(stream); if(streams.size()>32) streams.remove(0);
            };
            if(loaded.contains(sample)) action.run(); else pending.put(sample,action);
        });
    }
    public static void stopAll() { MAIN.post(() -> {
        pending.clear(); if(pool!=null) for(int stream:streams) pool.stop(stream);
        if(pool!=null && giftStream!=0) pool.stop(giftStream);
        streams.clear(); giftStream=0;
    }); }
    public static void release() { MAIN.post(() -> {
        if(pool!=null) pool.release(); pool=null; samples.clear();loaded.clear();pending.clear();streams.clear();giftStream=0;
    }); }
}

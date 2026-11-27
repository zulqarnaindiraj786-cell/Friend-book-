package com.friendbook.social.push;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local retry checkpoint. Mark only after WorkManager has durably accepted delivery work. */
final class FanoutCheckpoint {
    private static final String STORE="friend_post_fanout_progress";
    private static final long MAX_AGE_MS=7L*24*60*60*1000;
    private static final int MAX_EVENTS=24, BATCH=10;
    private final SharedPreferences prefs;
    private final String event;
    private final Set<String> pending=new HashSet<>();

    FanoutCheckpoint(Context context,String actor,String post,long now) {
        prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE);
        event=hash(actor+"\n"+post);
        prune(now);
        prefs.edit().putLong("m:"+event,now).apply();
    }
    boolean completed(String recipient) {
        String key=hash(recipient);
        return pending.contains(key) || prefs.getBoolean("d:"+event+":"+key,false);
    }
    void markQueued(String recipient) {
        pending.add(hash(recipient));
        if(pending.size()>=BATCH)flush();
    }
    void flush() {
        if(pending.isEmpty())return;
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:pending)edit.putBoolean("d:"+event+":"+key,true);
        // Failure only loses this optimization: replay remains safe via WorkManager
        // unique work and the server's per-device delivery claims.
        if(edit.commit())pending.clear();
    }
    void clear() {
        pending.clear();SharedPreferences.Editor edit=prefs.edit();
        for(String key:prefs.getAll().keySet())if(key.equals("m:"+event) || key.startsWith("d:"+event+":"))edit.remove(key);
        edit.apply();
    }
    private void prune(long now) {
        Map<String,?> all=prefs.getAll();List<Map.Entry<String,Long>> events=new ArrayList<>();
        for(Map.Entry<String,?> entry:all.entrySet())if(entry.getKey().startsWith("m:") && entry.getValue() instanceof Long)
            events.add(new AbstractMap.SimpleImmutableEntry<>(entry.getKey().substring(2),(Long)entry.getValue()));
        events.sort((a,b)->Long.compare(b.getValue(),a.getValue()));Set<String> expired=new HashSet<>();
        for(int i=0;i<events.size();i++){
            Map.Entry<String,Long> e=events.get(i);
            if(!e.getKey().equals(event) && (i>=MAX_EVENTS-1 || now<e.getValue() || now-e.getValue()>MAX_AGE_MS))expired.add(e.getKey());
        }
        if(expired.isEmpty())return;
        SharedPreferences.Editor edit=prefs.edit();
        for(String key:all.keySet())for(String id:expired)
            if(key.equals("m:"+id) || key.startsWith("d:"+id+":")){edit.remove(key);break;}
        edit.apply();
    }
    private static String hash(String value){
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder(64);
            for(byte b:bytes)out.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
            return out.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}

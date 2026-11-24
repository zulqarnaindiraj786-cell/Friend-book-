package com.friendbook.social.core;

import java.util.*;

/** Bounded local diagnostics. Stores only allowlisted route shapes, never IDs/queries/headers. */
final class LoopTraceBuffer {
    static final int MAX_KEYS=96,MAX_EVENTS=160;
    private final Map<String,Integer> counts=new LinkedHashMap<>();
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private long start,deadline,stoppedAt;private int attempts,completed,failed;private boolean active;
    synchronized void start(long now,long duration){counts.clear();events.clear();attempts=completed=failed=0;start=now;deadline=now+duration;stoppedAt=deadline;active=true;}
    synchronized boolean active(long now){if(now>=deadline)active=false;return active;}
    static String route(String path){
        if(path==null)return "other";
        if(path.matches("/v1/firestore/(document|query|count|batch|transaction|profile-bootstrap|story-engagement|claim-referral)"))return path;
        if(path.matches("/v1/engagement/(posts|reels)/[^/]+/(reactions|reactors|comments)(/[^/]+)?")){
            String[] p=path.split("/");return "/v1/engagement/"+p[3]+"/:id/"+p[5];
        }
        if(path.matches("/v1/(posts|reels)/[^/]+/(reactions|reactors|comments)(/[^/]+)?")){
            String[] p=path.split("/");return "/v1/"+p[2]+"/:id/"+p[4];
        }
        if(path.matches("/v1/views(/(posts|reels)/[^/]+)?"))return "/v1/views";
        if(path.matches("/v1/(videos|photos)(/.*)?"))return "/v1/media";
        if(path.equals("/functions/v1/send-fcm-notification"))return "/functions/v1/send-fcm-notification";
        if(path.matches("/v1/(android-config|android-release|me|chat/call|live/pk)"))return path;
        return "other";
    }
    private static String method(String method){return Arrays.asList("GET","POST","PUT","PATCH","DELETE").contains(method)?method:"OTHER";}
    private static String screen(String screen){return screen!=null && screen.matches("[A-Za-z0-9_$]{1,90}")?screen:"unknown";}
    synchronized boolean begin(long now,String screen,String method,String path,boolean foreground){
        return begin(now,screen,method,path,foreground,"");
    }
    synchronized boolean begin(long now,String screen,String method,String path,boolean foreground,String detail){
        if(!active(now))return false;
        if(detail==null || !detail.matches("root=(users|posts|reels|chats|notifications|friends|friend_requests|stories|groups|pages|referrals|other) limit=(none|[0-9]{1,4})"))detail="";
        attempts++;String key=screen(screen)+" "+method(method)+" "+route(path)+" "+(foreground?"FG":"BG")+(detail.isEmpty()?"":" "+detail);
        if(!counts.containsKey(key) && counts.size()>=MAX_KEYS)key="overflow";
        counts.put(key,counts.getOrDefault(key,0)+1);event((now-start)+"ms START "+key);return true;
    }
    synchronized void end(long now,int status,long duration){
        if(!active(now))return;completed++;if(status<200 || status>=400)failed++;
        event((now-start)+"ms END status="+status+" durationMs="+Math.max(0,duration));
    }
    private void event(String value){if(events.size()>=MAX_EVENTS)events.removeFirst();events.addLast(value);}
    synchronized void stop(long now){if(active)stoppedAt=Math.min(now,deadline);active=false;}
    synchronized String report(long now){
        active(now);
        StringBuilder out=new StringBuilder("FriendBook local Loop Trace\nScope: shared Cloudflare HTTP attempts ONLY; NOT billed D1 rows; not automatic loop proof.\n");
        out.append("State: ").append(active?"RUNNING":"STOPPED").append(" elapsedMs=").append(Math.max(0,Math.min(now,active?deadline:stoppedAt)-start))
                .append("\nAttempts=").append(attempts).append(" completionsInWindow=").append(completed).append(" errorsInWindow=").append(failed)
                .append("\nScreen means most recently resumed Activity, not guaranteed request owner. FG/BG is process visibility.\nCounts (bounded keys; overflow aggregated):\n");
        counts.forEach((k,v)->out.append(v).append(" x ").append(k).append('\n'));
        out.append("Recent events (last ").append(MAX_EVENTS).append("; START/END ordering only, not request IDs):\n");
        events.forEach(v->out.append(v).append('\n'));return out.toString();
    }
}

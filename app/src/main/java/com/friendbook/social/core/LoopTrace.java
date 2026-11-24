package com.friendbook.social.core;

import android.app.*;
import android.content.*;
import android.os.*;
import com.friendbook.social.App;
import com.friendbook.social.BuildConfig;

/** User-initiated release diagnostics. No uploads, worker logging or periodic HTTP. */
public final class LoopTrace {
    private static final LoopTraceBuffer BUFFER=new LoopTraceBuffer();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static volatile String screen="unknown";
    private static volatile long generation;
    private static boolean hasSession;
    private LoopTrace(){}
    public static void screen(Activity activity){screen=activity.getClass().getSimpleName();}
    private static long now(){return SystemClock.elapsedRealtime();}
    static synchronized long begin(okhttp3.Request request){
        long at=now(),session=generation;
        return BUFFER.begin(at,screen,request.method(),request.url().encodedPath(),App.isForeground())?session:-1L;
    }
    static synchronized void end(long session,int status,long duration){if(session==generation)BUFFER.end(now(),status,duration);}
    private static String report(){return "Version "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")\n"+BUFFER.report(now());}
    private static void save(Context context){context.getSharedPreferences("local_loop_trace",0).edit().putString("report",report()).apply();}
    private static synchronized void start(Context context,long duration){
        long session=++generation;hasSession=true;BUFFER.start(now(),duration);
        // Clear previous report before recording; process death is not a silently resumed session.
        context.getSharedPreferences("local_loop_trace",0).edit().remove("report").apply();
        MAIN.postDelayed(()->{if(session==generation){BUFFER.stop(now());save(context);}},duration);
        Ui.toast(context,"Loop Trace started. Use the app, then return here to Stop & Share.");
    }
    private static void share(Activity activity){
        if(hasSession){BUFFER.stop(now());save(activity);}
        String text=activity.getSharedPreferences("local_loop_trace",0).getString("report","");
        if(text.isEmpty()){Ui.info(activity,"Loop Trace","No saved report. Start a trace first.");return;}
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT,"FriendBook Loop Trace").putExtra(Intent.EXTRA_TEXT,text);
        activity.startActivity(Intent.createChooser(send,"Share local trace report"));
    }
    public static void show(Activity activity){
        new AlertDialog.Builder(activity).setTitle("Loop Trace — "+(BUFFER.active(now())?"RUNNING":"stopped"))
            .setMessage("Local only. No tokens, user IDs, message text, full URLs or query values recorded. Covers shared Cloudflare HTTP, not all raw/media/third-party traffic or billed D1 rows. Stops automatically; keep this process alive. Start, use Feed/Chat/Reels/etc, return and share.\n\nStarting replaces the previous report.")
            .setItems(new String[]{"Start 1-minute trace","Start 15-minute trace","Stop & Share report","View counts / last report","Delete trace"},(d,which)->{
                if(which==0)start(activity.getApplicationContext(),60_000L);
                if(which==1)start(activity.getApplicationContext(),900_000L);
                if(which==2)share(activity);
                if(which==3)Ui.info(activity,"Local trace",hasSession?report():activity.getSharedPreferences("local_loop_trace",0).getString("report","No saved report"));
                if(which==4){generation++;BUFFER.stop(now());hasSession=false;activity.getSharedPreferences("local_loop_trace",0).edit().clear().apply();Ui.toast(activity,"Trace deleted");}
            }).setNegativeButton("Close",null).show();
    }
}

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
        android.widget.LinearLayout body=new android.widget.LinearLayout(activity);
        body.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad=(int)(16*activity.getResources().getDisplayMetrics().density);
        body.setPadding(pad,pad,pad,pad);
        android.widget.TextView info=new android.widget.TextView(activity);
        info.setText("Local shared-HTTP trace only; not billed D1 rows or every network client. No tokens, IDs, message text or query values recorded. Start, use the app, then return to Stop & Share. Keep the process alive. Starting replaces the previous report.");
        body.addView(info);
        android.widget.ScrollView scroll=new android.widget.ScrollView(activity);scroll.addView(body);
        final AlertDialog dialog=new AlertDialog.Builder(activity)
                .setTitle("Loop Trace — "+(BUFFER.active(now())?"RUNNING":"stopped"))
                .setView(scroll).setNegativeButton("Close",null).create();
        addButton(activity,body,"Start 1-minute trace",()->{
            start(activity.getApplicationContext(),60_000L);dialog.setTitle("Loop Trace — RUNNING");
        });
        addButton(activity,body,"Start 15-minute trace",()->{
            start(activity.getApplicationContext(),900_000L);dialog.setTitle("Loop Trace — RUNNING");
        });
        addButton(activity,body,"Stop trace",()->{
            if(hasSession){BUFFER.stop(now());save(activity);}
            dialog.setTitle("Loop Trace — stopped");Ui.toast(activity,"Trace stopped. Tap Share report.");
        });
        addButton(activity,body,"Stop & Share report",()->{
            share(activity);dialog.setTitle("Loop Trace — stopped");
        });
        addButton(activity,body,"View counts / last report",()->
            Ui.info(activity,"Local trace",hasSession?report():activity.getSharedPreferences("local_loop_trace",0).getString("report","No saved report")));
        addButton(activity,body,"Delete trace",()->{
            generation++;BUFFER.stop(now());hasSession=false;
            activity.getSharedPreferences("local_loop_trace",0).edit().clear().apply();
            dialog.setTitle("Loop Trace — stopped");Ui.toast(activity,"Trace deleted");
        });
        dialog.show();
    }
    private static void addButton(Activity activity,android.widget.LinearLayout body,String text,Runnable action){
        android.widget.Button button=new android.widget.Button(activity);
        button.setText(text);button.setAllCaps(false);
        body.addView(button,new android.widget.LinearLayout.LayoutParams(-1,-2));
        button.setOnClickListener(v->action.run());
    }
}

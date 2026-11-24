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
    private static long captureStartedUtcMillis;
    private LoopTrace(){}
    public static void screen(Activity activity){screen=activity.getClass().getSimpleName();}
    private static long now(){return SystemClock.elapsedRealtime();}
    static synchronized long begin(okhttp3.Request request){
        long at=now(),session=generation;
        if(!BUFFER.active(at))return -1L;
        return BUFFER.begin(at,screen,request.method(),request.url().encodedPath(),App.isForeground(),context(request))?session:-1L;
    }
    /** Structural metadata only. Never retains IDs, filter values, cursors or raw JSON. */
    static String context(okhttp3.Request request){
        String path=request.url().encodedPath();String parent=null;String limit="none";
        try {
            if(path.equals("/v1/firestore/query") && "POST".equals(request.method())){
                okhttp3.RequestBody body=request.body();
                if(body==null || body.isOneShot() || body.isDuplex() || body.contentLength()<0 || body.contentLength()>16384)return "";
                okio.Buffer bytes=new okio.Buffer();body.writeTo(bytes);
                org.json.JSONObject json=new org.json.JSONObject(bytes.readUtf8());
                parent=json.optString("parent","");
                int size=json.optInt("limit",-1);if(size>=0 && size<=9999)limit=Integer.toString(size);
            }else if(path.equals("/v1/firestore/document"))parent=request.url().queryParameter("path");
            else if(path.equals("/v1/firestore/count"))parent=request.url().queryParameter("parent");
            else return "";
            String root=parent==null?"other":parent.split("/",2)[0];
            if(!java.util.Arrays.asList("users","posts","reels","chats","notifications","friends","friend_requests","stories","groups","pages","referrals").contains(root))root="other";
            return "root="+root+" limit="+limit;
        }catch(Exception ignored){return "";}
    }
    static synchronized void end(long session,int status,long duration){if(session==generation)BUFFER.end(now(),status,duration);}
    private static String report(){return "Version "+BuildConfig.VERSION_NAME+" ("+BuildConfig.VERSION_CODE+")\nCapture start UTC milliseconds (device clock): "+captureStartedUtcMillis+"\n"+BUFFER.report(now());}
    private static void save(Context context){context.getSharedPreferences("local_loop_trace",0).edit().putString("report",report()).apply();}
    private static synchronized void start(Context context,long duration){
        long session=++generation;hasSession=true;captureStartedUtcMillis=System.currentTimeMillis();BUFFER.start(now(),duration);
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

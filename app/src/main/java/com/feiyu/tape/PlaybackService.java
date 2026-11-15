package com.feiyu.tape;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 退到后台继续走带的"最小代价"实现：一个低优先级的常驻通知，通知上挂一个"停止"。
 * 通知本身不显示歌名/进度——这台机器的设定就是看不见这些。
 */
public class PlaybackService extends Service {

    public static final String ACTION_STOP = "com.feiyu.tape.STOP";

    private static final String CHANNEL = "tape";
    private static final int NID = 77;

    private TapeEngine engine;

    /** 从 Activity 里调：让机器自己接着走 */
    public static void start(Context c) {
        Intent it = new Intent(c, PlaybackService.class);
        // 8.0 起后台 startService 直接抛 IllegalStateException（以前被 catch 吞了，
        // 结果后台播放其实一直没生效）；8.0 以后改用 startForegroundService。
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                O26.startForeground(c, it);
            } else {
                c.startService(it);
            }
        } catch (Throwable e) {
            try {
                c.startService(it);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 回到前台、或者按了红点：把通知撤掉 */
    public static void stop(Context c) {
        try {
            c.stopService(new Intent(c, PlaybackService.class));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            engine = TapeApp.engine(this);
            if (Build.VERSION.SDK_INT >= 26) {
                O26.channel(this);
            }
        } catch (Throwable t) {
            TapeApp.report(t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            if (engine != null) engine.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForeground(NID, notification());
        } catch (Throwable t) {
            // 有些 ROM 不让起前台通知。起不来就不起了：
            // 宁可没有通知，也不能被系统判成"开了前台服务却不 startForeground"直接杀掉
            TapeApp.report(t);
            try {
                stopSelf();
            } catch (Throwable ignored) {
            }
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent st = new Intent(this, PlaybackService.class);
        st.setAction(ACTION_STOP);
        PendingIntent ps = PendingIntent.getService(this, 2, st, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? O26.builder(this)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("磁带机在走带")
                .setContentText("按停止键 · 或点这里的\"停止\" · 让带子退回去")
                .setContentIntent(pi)
                .addAction(R.drawable.ic_stat, "停止", ps)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 26+ 专用壳：Dalvik（4.4 平板）根本不会加载这个类 */
    static class O26 {
        static void startForeground(Context c, Intent it) {
            c.startForegroundService(it);
        }

        static void channel(Context c) {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(CHANNEL, "走带",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        static Notification.Builder builder(Context c) {
            return new Notification.Builder(c, CHANNEL);
        }
    }
}

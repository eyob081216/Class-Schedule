package com.classplanner.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.atomic.AtomicBoolean;

/** Keeps the phone listening for class updates so notifications arrive within seconds. */
public class LiveService extends Service {
    static final int NOTE_ID = 4202;
    private static final String CHANNEL = "live_listener";

    private AtomicBoolean stop;

    static void start(Context c) {
        if (!Cloud.configured() || !Cloud.signedIn(c) || !Cloud.liveConfigured()) return;
        try {
            c.startForegroundService(new Intent(c, LiveService.class));
        } catch (Exception ignored) {
            // The phone said no (for example while the app is in the background). The 15 minute check still works.
        }
    }

    static void stop(Context c) {
        try {
            c.stopService(new Intent(c, LiveService.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Live updates", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false); // the always-on notice must not put a number on the app icon
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Class Planner")
                .setContentText("Listening for class updates")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTE_ID, n);
        }

        if (stop == null) {
            stop = new AtomicBoolean(false);
            final AtomicBoolean flag = stop;
            final Context ctx = getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    Live.listen(ctx, flag, new Live.Ping() {
                        @Override
                        public void onPing() {
                            try {
                                UpdateJob.checkNow(ctx);
                            } catch (Exception ignored) {
                            }
                        }
                    });
                }
            }).start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (stop != null) stop.set(true);
        stop = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

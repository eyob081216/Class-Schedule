package com.classplanner.widget;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.service.notification.StatusBarNotification;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;

/**
 * Checks for new class updates about every 15 minutes (even when the app is closed),
 * posts a phone notification for each new one and refreshes the widget.
 */
public class UpdateJob extends JobService {
    private static final int JOB_ID = 4201;
    private static final String CHANNEL = "class_updates";

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        for (JobInfo info : js.getAllPendingJobs()) {
            if (info.getId() == JOB_ID) return; // already scheduled
        }
        JobInfo info = new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build();
        js.schedule(info);
    }

    static void cancel(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js != null) js.cancel(JOB_ID);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        final Context ctx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    checkNow(ctx);
                } catch (Exception ignored) {
                    // No internet or a server hiccup: the next run tries again.
                }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    private static final Object LOCK = new Object();

    /** Looks for new updates and notifies about them. Used by the 15 minute job and the live listener. */
    static void checkNow(Context ctx) throws Exception {
        synchronized (LOCK) {
            if (!Cloud.configured() || !Cloud.signedIn(ctx)) return;
            JSONArray all = Cloud.fetchUpdates(ctx);
            JSONArray fresh = Cloud.takeNew(ctx, all, true);
            String me = Cloud.sessionJson(ctx).optString("name", "");
            JSONArray others = new JSONArray();
            for (int i = 0; i < fresh.length(); i++) {
                JSONObject o = fresh.optJSONObject(i);
                // A rep does not need a notification about their own post.
                if (o != null && !(Cloud.sessionJson(ctx).optString("role").equals("rep") && o.optString("by").equals(me))) {
                    others.put(o);
                }
            }
            // If the app is open the screen shows the update itself, so no notification is needed.
            if (others.length() > 0 && !MainActivity.isOpen()) notifyNew(ctx, others);
            ClassWidgetProvider.refreshAll(ctx);
            MainActivity.poke();
        }
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    /** Removes the update notifications (and the number on the app icon) once the person opens the app. */
    static void clearUpdateNotifications(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() != LiveService.NOTE_ID) nm.cancel(s.getId());
            }
        } catch (Exception ignored) {
        }
    }

    static void notifyNew(Context ctx, JSONArray fresh) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Class updates", NotificationManager.IMPORTANCE_HIGH));

        PendingIntent open = PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean eth = ctx.getSharedPreferences(ClassWidgetProvider.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ClassWidgetProvider.KEY_ETHIOPIAN, true);

        for (int i = 0; i < fresh.length(); i++) {
            JSONObject o = fresh.optJSONObject(i);
            if (o == null) continue;
            String[] t = describe(o, eth);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(t[0])
                    .setContentText(t[1])
                    .setStyle(new Notification.BigTextStyle().bigText(t[1]))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build();
            nm.notify(o.optString("id", "u" + i).hashCode(), n);
        }
    }

    /** Title and message for one update. */
    static String[] describe(JSONObject o, boolean eth) {
        String type = o.optString("type", "");
        Schedule.Course course = Schedule.course(o.optString("course", ""));
        String name = course == null ? "A class" : course.name;
        String note = o.optString("text", "");
        LocalDate date = parse(o.optString("date", ""));
        String when = date == null ? "" : Schedule.shortDate(date);
        String extra = note.isEmpty() ? "" : " · " + note;

        switch (type) {
            case "cancel":
                return new String[]{"Class cancelled: " + name, when + extra};
            case "move": {
                LocalDate to = parse(o.optString("toDate", ""));
                int period = o.optInt("toPeriod", 1);
                String dest = to == null ? "a new time" : Schedule.shortDate(to) + " at "
                        + Schedule.fmt(Schedule.periodStart(Math.max(1, Math.min(8, period))), eth);
                return new String[]{"Class moved: " + name, "From " + when + " to " + dest + extra};
            }
            case "room": {
                String room = o.optString("room", "");
                String who = o.optString("who", "");
                StringBuilder sb = new StringBuilder(when);
                if (!room.isEmpty()) sb.append(" · Room ").append(room);
                if (!who.isEmpty()) sb.append(" · ").append(who);
                return new String[]{"Change for " + name, sb + extra};
            }
            default:
                String by = o.optString("by", "");
                return new String[]{by.isEmpty() ? "Class announcement" : "Announcement from " + by, note};
        }
    }

    private static LocalDate parse(String s) {
        try {
            return s.isEmpty() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

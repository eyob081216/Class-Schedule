package com.classplanner.widget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.List;

public class ClassWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "com.classplanner.widget.REFRESH";
    static final String PREFS = "class_planner";
    static final String KEY_ETHIOPIAN = "ethiopian_clock";

    private static final int COLOR_NEUTRAL = 0xFF38BDF8;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, build(context));
        }
        scheduleNext(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refreshAll(context);
        }
    }

    @Override
    public void onEnabled(Context context) {
        scheduleNext(context);
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(refreshIntent(context));
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ClassWidgetProvider.class));
        for (int id : ids) {
            manager.updateAppWidget(id, build(context));
        }
        scheduleNext(context);
    }

    private static PendingIntent refreshIntent(Context context) {
        Intent intent = new Intent(context, ClassWidgetProvider.class).setAction(ACTION_REFRESH);
        return PendingIntent.getBroadcast(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Wake the widget at the next class start, class end or lunch boundary (inexact, needs no permission). */
    private static void scheduleNext(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Calendar cal = Calendar.getInstance();
        int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        int next = 24 * 60 + 1; // one minute past midnight
        int[] candidates = new int[2 + Schedule.blocks(dow).length * 2];
        int n = 0;
        candidates[n++] = Schedule.LUNCH_START;
        candidates[n++] = Schedule.LUNCH_END;
        for (Schedule.Block blk : Schedule.blocks(dow)) {
            candidates[n++] = blk.start;
            candidates[n++] = blk.end;
        }
        for (int c : candidates) {
            if (c > mins && c < next) next = c;
        }

        Calendar at = (Calendar) cal.clone();
        at.set(Calendar.HOUR_OF_DAY, 0);
        at.set(Calendar.MINUTE, 0);
        at.set(Calendar.SECOND, 5);
        at.set(Calendar.MILLISECOND, 0);
        long trigger = at.getTimeInMillis() + next * 60_000L;

        am.set(AlarmManager.RTC, trigger, refreshIntent(context));
    }

    static RemoteViews build(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean eth = prefs.getBoolean(KEY_ETHIOPIAN, true);

        Calendar cal = Calendar.getInstance();
        int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0 ... Sunday = 6
        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);

        List<Schedule.Upcoming> up = Schedule.upcoming(dow, mins);
        Schedule.Upcoming first = up.isEmpty() ? null : up.get(0);
        Schedule.Block[] today = Schedule.blocks(dow);

        String status;
        String title;
        String detail;
        int color = COLOR_NEUTRAL;
        Schedule.Upcoming then;
        String thenLabel = "Then";

        boolean live = first != null && first.daysAhead == 0 && first.block.start <= mins;
        if (live) {
            Schedule.Block b = first.block;
            status = "IN CLASS NOW";
            title = b.course.name;
            detail = "Ends in " + Schedule.duration(b.end - mins) + " · " + b.course.room + " · " + b.course.who;
            color = b.course.color;
            then = up.size() > 1 ? up.get(1) : null;
        } else if (first != null && first.daysAhead == 0) {
            Schedule.Block b = first.block;
            boolean inLunch = mins >= Schedule.LUNCH_START && mins < Schedule.LUNCH_END;
            boolean had = false;
            for (Schedule.Block t : today) {
                if (t.end <= mins) had = true;
            }
            status = inLunch ? "LUNCH BREAK" : (had ? "BETWEEN CLASSES" : "NEXT CLASS TODAY");
            title = b.course.name;
            detail = "Starts at " + Schedule.fmt(b.start, eth) + " (in " + Schedule.duration(b.start - mins) + ") · "
                    + b.course.room + " · " + b.course.who;
            color = b.course.color;
            then = up.size() > 1 ? up.get(1) : null;
        } else {
            title = "No classes today";
            if (dow >= 5) {
                status = "WEEKEND";
                detail = "Enjoy the break.";
            } else if (today.length > 0) {
                status = "DONE FOR TODAY";
                title = "That is all for today";
                detail = "Your last class ended at " + Schedule.fmt(today[today.length - 1].end, eth) + ".";
            } else {
                status = "FREE DAY";
                detail = "Nothing is scheduled.";
            }
            then = first;
            thenLabel = "Next class";
        }

        String thenText = "";
        if (then != null) {
            thenText = thenLabel + ": " + Schedule.dayLabel(then.daysAhead, then.day) + " at "
                    + Schedule.fmt(then.block.start, eth) + " · " + then.block.course.name
                    + " · " + then.block.course.room;
        }

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget);
        rv.setTextViewText(R.id.status, status);
        rv.setTextViewText(R.id.title, title);
        rv.setTextViewText(R.id.detail, detail);
        rv.setTextViewText(R.id.then, thenText);
        rv.setInt(R.id.strip, "setBackgroundColor", color);
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent openApp = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        rv.setOnClickPendingIntent(R.id.root, openApp);
        return rv;
    }
}

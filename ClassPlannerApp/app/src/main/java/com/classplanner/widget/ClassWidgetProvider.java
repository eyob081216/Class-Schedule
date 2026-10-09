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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int mins = now.getHour() * 60 + now.getMinute();

        int next = 24 * 60 + 1; // one minute past midnight
        List<Schedule.Slot> slots = Schedule.activeSlots(today, Cloud.cachedChanges(context));
        int[] candidates = new int[2 + slots.size() * 2];
        int n = 0;
        candidates[n++] = Schedule.LUNCH_START;
        candidates[n++] = Schedule.LUNCH_END;
        for (Schedule.Slot s : slots) {
            candidates[n++] = s.start;
            candidates[n++] = s.end;
        }
        for (int c : candidates) {
            if (c > mins && c < next) next = c;
        }

        long trigger = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                + next * 60_000L + 5_000L;
        am.set(AlarmManager.RTC, trigger, refreshIntent(context));
    }

    static RemoteViews build(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean eth = prefs.getBoolean(KEY_ETHIOPIAN, true);

        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        int mins = now.getHour() * 60 + now.getMinute();
        int dow = today.getDayOfWeek().getValue() - 1; // Monday = 0 ... Sunday = 6

        List<Schedule.Change> changes = Cloud.cachedChanges(context);
        List<Schedule.Upcoming> up = Schedule.upcoming(today, mins, changes);
        Schedule.Upcoming first = up.isEmpty() ? null : up.get(0);
        List<Schedule.Slot> todaySlots = Schedule.activeSlots(today, changes);

        String status;
        String title;
        String detail;
        int color = COLOR_NEUTRAL;
        Schedule.Upcoming then;
        String thenLabel = "Then";
        boolean changed = false;

        boolean live = first != null && first.daysAhead == 0 && first.slot.start <= mins;
        if (live) {
            Schedule.Slot s = first.slot;
            status = "IN CLASS NOW";
            title = s.course.name;
            detail = "Ends in " + Schedule.duration(s.end - mins) + " · " + s.room + " · " + s.who;
            color = s.course.color;
            changed = s.changed;
            then = up.size() > 1 ? up.get(1) : null;
        } else if (first != null && first.daysAhead == 0) {
            Schedule.Slot s = first.slot;
            boolean inLunch = mins >= Schedule.LUNCH_START && mins < Schedule.LUNCH_END;
            boolean had = false;
            for (Schedule.Slot t : todaySlots) {
                if (t.end <= mins) had = true;
            }
            status = inLunch ? "LUNCH BREAK" : (had ? "BETWEEN CLASSES" : "NEXT CLASS TODAY");
            title = s.course.name;
            detail = "Starts at " + Schedule.fmt(s.start, eth) + " (in " + Schedule.duration(s.start - mins) + ") · "
                    + s.room + " · " + s.who;
            color = s.course.color;
            changed = s.changed;
            then = up.size() > 1 ? up.get(1) : null;
        } else {
            title = "No classes today";
            if (dow >= 5) {
                status = "WEEKEND";
                detail = "Enjoy the break.";
            } else if (!todaySlots.isEmpty()) {
                status = "DONE FOR TODAY";
                title = "That is all for today";
                detail = "Your last class ended at " + Schedule.fmt(todaySlots.get(todaySlots.size() - 1).end, eth) + ".";
            } else {
                status = "FREE DAY";
                detail = "Nothing is scheduled.";
            }
            then = first;
            thenLabel = "Next class";
        }
        if (changed) status = status + " · UPDATED";

        String thenText = "";
        if (then != null) {
            thenText = thenLabel + ": " + Schedule.dayLabel(then.daysAhead, then.date) + " at "
                    + Schedule.fmt(then.slot.start, eth) + " · " + then.slot.course.name
                    + " · " + then.slot.room;
        }

        // A class cancelled for today matters more than what comes later.
        StringBuilder cancelled = new StringBuilder();
        for (Schedule.Slot s : Schedule.slotsOn(today, changes)) {
            if (Schedule.CANCELLED.equals(s.status) && s.end > mins) {
                if (cancelled.length() > 0) cancelled.append(", ");
                cancelled.append(s.course.name);
            }
        }
        if (cancelled.length() > 0) thenText = "Cancelled today: " + cancelled;

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

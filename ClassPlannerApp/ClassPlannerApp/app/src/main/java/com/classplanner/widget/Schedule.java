package com.classplanner.widget;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Timetable data and "what is on now / next" logic. Pure Java, no Android classes. */
final class Schedule {
    static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday"};

    /** Lunch in minutes from midnight on a standard clock (6:00 to 7:30 on the Ethiopian clock). */
    static final int LUNCH_START = 720;
    static final int LUNCH_END = 810;

    /** Period start/end in standard minutes. Period 1 = 2:00 Ethiopian = 8:00 AM. */
    private static final int[][] P = {
        {480, 530}, {540, 590}, {600, 650}, {660, 710},
        {810, 860}, {870, 920}, {930, 980}, {990, 1040}
    };

    static final class Course {
        final String name;
        final String room;
        final String who;
        final int color;

        Course(String name, String room, String who, int color) {
            this.name = name;
            this.room = room;
            this.who = who;
            this.color = color;
        }
    }

    static final class Block {
        final Course course;
        final int start;
        final int end;
        final int periods;

        Block(Course course, int start, int end, int periods) {
            this.course = course;
            this.start = start;
            this.end = end;
            this.periods = periods;
        }
    }

    static final class Upcoming {
        final int daysAhead;
        final int day;
        final Block block;

        Upcoming(int daysAhead, int day, Block block) {
            this.daysAhead = daysAhead;
            this.day = day;
            this.block = block;
        }
    }

    static final Course DB = new Course("Database Systems", "LH-06", "Adey E. & Dagmawit M.", 0xFF1D78FF);
    static final Course CP = new Course("Computer Programming II", "LH-06", "Lemlem H.", 0xFFE11D48);
    static final Course EC = new Course("Economics", "LH-04/06", "Amsalu", 0xFFFDC82F);
    static final Course AC = new Course("Accounting I", "LH-04", "Muluneh", 0xFF15803D);
    static final Course MG = new Course("Management", "LH-06", "Dr. Daniel", 0xFF6D44F0);
    static final Course CG = new Course("Combinatorics and Graph Theory", "LH-06", "Dr. Yirgalem Tsegaye", 0xFF0B88A8);

    private static Block b(Course c, int firstPeriod, int lastPeriod) {
        return new Block(c, P[firstPeriod - 1][0], P[lastPeriod - 1][1], lastPeriod - firstPeriod + 1);
    }

    /** Monday to Friday. Saturday and Sunday are free. */
    static final Block[][] WEEK = {
        {b(DB, 2, 4), b(CP, 6, 6)},
        {b(EC, 3, 4), b(CG, 5, 6), b(CP, 7, 8)},
        {b(CG, 3, 3), b(AC, 5, 6), b(MG, 7, 8)},
        {},
        {}
    };

    /** Blocks for a day index (0 = Monday ... 6 = Sunday), in time order. */
    static Block[] blocks(int day) {
        return day >= 0 && day < WEEK.length ? WEEK[day] : new Block[0];
    }

    /** Classes still to come (or in progress) from now, looking up to a week ahead. */
    static List<Upcoming> upcoming(int dow, int mins) {
        List<Upcoming> out = new ArrayList<>();
        for (int k = 0; k <= 7; k++) {
            int day = (dow + k) % 7;
            for (Block blk : blocks(day)) {
                if (k == 0 && blk.end <= mins) continue;
                out.add(new Upcoming(k, day, blk));
            }
        }
        return out;
    }

    static String dayLabel(int daysAhead, int day) {
        if (daysAhead == 0) return "Today";
        if (daysAhead == 1) return "Tomorrow";
        String name = day < DAYS.length ? DAYS[day] : (day == 5 ? "Saturday" : "Sunday");
        return daysAhead == 7 ? "Next " + name : name;
    }

    /** Formats minutes from midnight on the Ethiopian clock (2:00) or a standard clock (8:00 AM). */
    static String fmt(int minutes, boolean ethiopian) {
        int h = (minutes / 60) % 24;
        int m = minutes % 60;
        if (ethiopian) {
            int eh = (((h - 6) % 12) + 12) % 12;
            if (eh == 0) eh = 12;
            return String.format(Locale.US, "%d:%02d", eh, m);
        }
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m, h < 12 ? "AM" : "PM");
    }

    static String duration(int minutes) {
        int h = minutes / 60;
        int r = minutes % 60;
        if (h == 0) return r + " min";
        return r == 0 ? h + "h" : h + "h " + r + "m";
    }
}

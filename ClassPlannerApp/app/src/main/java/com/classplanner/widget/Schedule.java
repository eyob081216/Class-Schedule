package com.classplanner.widget;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Timetable data and "what is on now / next" logic, including dated changes from the class rep.
 * Pure Java: no Android classes, so it can be tested on a normal JVM.
 */
final class Schedule {
    private Schedule() {}

    static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] SHORT_DAYS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** Lunch in minutes from midnight on a standard clock (6:00 to 7:30 on the Ethiopian clock). */
    static final int LUNCH_START = 720;
    static final int LUNCH_END = 810;

    static final String OK = "ok";
    static final String CANCELLED = "cancelled";
    static final String MOVED_AWAY = "moved-away";
    static final String MOVED_IN = "moved-in";

    /** Period start/end in standard minutes. Period 1 = 2:00 Ethiopian = 8:00 AM. */
    private static final int[][] P = {
        {480, 530}, {540, 590}, {600, 650}, {660, 710},
        {810, 860}, {870, 920}, {930, 980}, {990, 1040}
    };

    static final class Course {
        final String id;
        final String name;
        final String room;
        final String who;
        final int color;

        Course(String id, String name, String room, String who, int color) {
            this.id = id;
            this.name = name;
            this.room = room;
            this.who = who;
            this.color = color;
        }
    }

    /** One class in the regular weekly timetable. */
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

    /** One update posted by the class rep. Dates are the date of the class the update is about. */
    static final class Change {
        String id = "";
        String type = "";      // cancel, move, room, announce
        String course = "";
        String room = "";
        String who = "";
        String text = "";
        String by = "";
        LocalDate date;        // the class date this applies to
        LocalDate toDate;      // move: new date
        int toPeriod;          // move: new first period (1 to 8)
        long createdAt;
    }

    /** A class on a given date after the rep's changes are applied. */
    static final class Slot {
        final Course course;
        final int start;
        final int end;
        final int periods;
        String status = OK;
        String room;
        String who;
        boolean changed;
        LocalDate movedTo;
        int movedPeriod;
        LocalDate movedFrom;

        Slot(Course course, int start, int end, int periods) {
            this.course = course;
            this.start = start;
            this.end = end;
            this.periods = periods;
            this.room = course.room;
            this.who = course.who;
        }

        boolean active() {
            return OK.equals(status) || MOVED_IN.equals(status);
        }
    }

    static final class Upcoming {
        final int daysAhead;
        final LocalDate date;
        final Slot slot;

        Upcoming(int daysAhead, LocalDate date, Slot slot) {
            this.daysAhead = daysAhead;
            this.date = date;
            this.slot = slot;
        }
    }

    static final Course DB = new Course("db", "Database Systems", "LH-06", "Adey E. & Dagmawit M.", 0xFF1D78FF);
    static final Course CP = new Course("cp", "Computer Programming II", "LH-06", "Lemlem H.", 0xFFE11D48);
    static final Course EC = new Course("ec", "Economics", "LH-04/06", "Amsalu", 0xFFFDC82F);
    static final Course AC = new Course("ac", "Accounting I", "LH-04", "Muluneh", 0xFF15803D);
    static final Course MG = new Course("mg", "Management", "LH-06", "Dr. Daniel", 0xFF6D44F0);
    static final Course CG = new Course("cg", "Combinatorics and Graph Theory", "LH-06", "Dr. Yirgalem Tsegaye", 0xFF0B88A8);
    private static final Course[] ALL = {DB, CP, EC, AC, MG, CG};

    static Course course(String id) {
        for (Course c : ALL) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    private static Block b(Course c, int firstPeriod, int lastPeriod) {
        return new Block(c, P[firstPeriod - 1][0], P[lastPeriod - 1][1], lastPeriod - firstPeriod + 1);
    }

    /** Monday to Friday. Saturday and Sunday are free. */
    private static final Block[][] WEEK = {
        {b(DB, 2, 4), b(CP, 6, 6)},
        {b(EC, 3, 4), b(CG, 5, 6), b(CP, 7, 8)},
        {b(CG, 3, 3), b(AC, 5, 6), b(MG, 7, 8)},
        {},
        {}
    };

    /** Regular timetable for a day index (0 = Monday ... 6 = Sunday), in time order. */
    static Block[] blocks(int dow) {
        return dow >= 0 && dow < WEEK.length ? WEEK[dow] : new Block[0];
    }

    static int periodStart(int period) {
        return P[period - 1][0];
    }

    static int periodEnd(int period) {
        return P[period - 1][1];
    }

    /** A class must sit wholly in the morning (periods 1 to 4) or the afternoon (5 to 8). */
    static boolean validSpan(int first, int last) {
        return first >= 1 && last <= 8 && ((first <= 4 && last <= 4) || first >= 5);
    }

    /** Final state of one class on one date after all of the rep's changes, oldest first. */
    private static final class State {
        final String courseId;
        final LocalDate from;
        String status = "";
        LocalDate to;
        int toPeriod;
        String room = "";
        String who = "";

        State(String courseId, LocalDate from) {
            this.courseId = courseId;
            this.from = from;
        }
    }

    private static Map<String, State> states(List<Change> changes) {
        List<Change> sorted = new ArrayList<>(changes);
        Collections.sort(sorted, new Comparator<Change>() {
            @Override
            public int compare(Change a, Change b) {
                int c = Long.compare(a.createdAt, b.createdAt);
                return c != 0 ? c : a.id.compareTo(b.id);
            }
        });
        Map<String, State> map = new TreeMap<>();
        for (Change ch : sorted) {
            if (course(ch.course) == null || ch.date == null) continue;
            String key = ch.course + "|" + ch.date;
            State st = map.get(key);
            if (st == null) {
                st = new State(ch.course, ch.date);
                map.put(key, st);
            }
            if ("cancel".equals(ch.type)) {
                st.status = "cancelled";
                st.to = null;
                st.toPeriod = 0;
            } else if ("move".equals(ch.type)) {
                if (ch.toDate != null && ch.toPeriod >= 1 && ch.toPeriod <= 8) {
                    st.status = "moved";
                    st.to = ch.toDate;
                    st.toPeriod = ch.toPeriod;
                }
            } else if ("room".equals(ch.type)) {
                if (!ch.room.isEmpty()) st.room = ch.room;
                if (!ch.who.isEmpty()) st.who = ch.who;
            }
        }
        return map;
    }

    private static Block baseBlock(String courseId, LocalDate date) {
        for (Block bl : blocks(date.getDayOfWeek().getValue() - 1)) {
            if (bl.course.id.equals(courseId)) return bl;
        }
        return null;
    }

    /**
     * Every class on a date, including cancelled ones and ones that moved away (marked by status),
     * plus classes moved onto that date. Sorted by start time.
     */
    static List<Slot> slotsOn(LocalDate date, List<Change> changes) {
        Map<String, State> states = states(changes);
        List<Slot> out = new ArrayList<>();

        for (Block bl : blocks(date.getDayOfWeek().getValue() - 1)) {
            Slot s = new Slot(bl.course, bl.start, bl.end, bl.periods);
            State st = states.get(bl.course.id + "|" + date);
            if (st != null) {
                if (!st.room.isEmpty()) {
                    s.room = st.room;
                    s.changed = true;
                }
                if (!st.who.isEmpty()) {
                    s.who = st.who;
                    s.changed = true;
                }
                if ("cancelled".equals(st.status)) {
                    s.status = CANCELLED;
                    s.changed = true;
                } else if ("moved".equals(st.status)) {
                    s.status = MOVED_AWAY;
                    s.changed = true;
                    s.movedTo = st.to;
                    s.movedPeriod = st.toPeriod;
                }
            }
            out.add(s);
        }

        for (State st : states.values()) {
            if (!"moved".equals(st.status) || !date.equals(st.to)) continue;
            Block orig = baseBlock(st.courseId, st.from);
            if (orig == null) continue;
            int first = st.toPeriod;
            int last = first + orig.periods - 1;
            if (!validSpan(first, last)) continue;
            Slot s = new Slot(orig.course, P[first - 1][0], P[last - 1][1], orig.periods);
            s.status = MOVED_IN;
            s.changed = true;
            s.movedFrom = st.from;
            if (!st.room.isEmpty()) s.room = st.room;
            if (!st.who.isEmpty()) s.who = st.who;
            out.add(s);
        }

        Collections.sort(out, new Comparator<Slot>() {
            @Override
            public int compare(Slot a, Slot b) {
                int c = Integer.compare(a.start, b.start);
                return c != 0 ? c : a.course.id.compareTo(b.course.id);
            }
        });
        return out;
    }

    /** Only the classes that will actually happen on a date. */
    static List<Slot> activeSlots(LocalDate date, List<Change> changes) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : slotsOn(date, changes)) {
            if (s.active()) out.add(s);
        }
        return out;
    }

    /** Classes still to come (or in progress) from now, looking up to a week ahead. */
    static List<Upcoming> upcoming(LocalDate today, int mins, List<Change> changes) {
        List<Upcoming> out = new ArrayList<>();
        for (int k = 0; k <= 7; k++) {
            LocalDate d = today.plusDays(k);
            for (Slot s : slotsOn(d, changes)) {
                if (!s.active()) continue;
                if (k == 0 && s.end <= mins) continue;
                out.add(new Upcoming(k, d, s));
            }
        }
        return out;
    }

    static String dayLabel(int daysAhead, LocalDate date) {
        if (daysAhead == 0) return "Today";
        if (daysAhead == 1) return "Tomorrow";
        String name = DAYS[date.getDayOfWeek().getValue() - 1];
        return daysAhead == 7 ? "Next " + name : name;
    }

    /** For example "Tue 13 Oct". */
    static String shortDate(LocalDate d) {
        return String.format(Locale.US, "%s %d %s", SHORT_DAYS[d.getDayOfWeek().getValue() - 1],
                d.getDayOfMonth(), MONTHS[d.getMonthValue() - 1]);
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

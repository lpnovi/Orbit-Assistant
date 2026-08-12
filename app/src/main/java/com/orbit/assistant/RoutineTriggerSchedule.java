package com.orbit.assistant;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** Pure time-trigger recurrence calculations and human-readable labels. */
public final class RoutineTriggerSchedule {
    private RoutineTriggerSchedule() {}

    public static long nextRun(RoutineTriggerStore.Trigger t, long afterMillis) {
        if (t == null || !t.enabled || !RoutineTriggerStore.TYPE_TIME.equals(t.type)) return 0L;
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime after = Instant.ofEpochMilli(Math.max(0L, afterMillis)).atZone(zone);
        LocalDate start;
        try {
            start = LocalDate.of(t.startYear, t.startMonth, t.startDay);
        } catch (Exception e) {
            return 0L;
        }
        LocalTime time = LocalTime.of(t.hour, t.minute);

        switch (t.mode) {
            case RoutineTriggerStore.MODE_ONCE:
                return futureMillis(start.atTime(time).atZone(zone), after);
            case RoutineTriggerStore.MODE_DAILY:
                return nextDaily(start, time, after, zone);
            case RoutineTriggerStore.MODE_WEEKDAYS:
                return nextWeekPattern(start, time, after, zone, 0b00011111, 1);
            case RoutineTriggerStore.MODE_WEEKENDS:
                return nextWeekPattern(start, time, after, zone, 0b01100000, 1);
            case RoutineTriggerStore.MODE_WEEKLY:
                return nextWeekPattern(start, time, after, zone, t.weekdayMask, Math.max(1, t.intervalCount));
            case RoutineTriggerStore.MODE_CUSTOM:
                if (RoutineTriggerStore.UNIT_DAYS.equals(t.intervalUnit)) {
                    return nextEveryDays(start, time, after, zone, Math.max(1, t.intervalCount));
                }
                if (RoutineTriggerStore.UNIT_WEEKS.equals(t.intervalUnit)) {
                    int mask = t.weekdayMask == 0 ? bitFor(start.getDayOfWeek()) : t.weekdayMask;
                    return nextWeekPattern(start, time, after, zone, mask, Math.max(1, t.intervalCount));
                }
                if (RoutineTriggerStore.UNIT_MONTHS.equals(t.intervalUnit)) {
                    return nextEveryMonths(start, time, after, zone, Math.max(1, t.intervalCount));
                }
                return 0L;
            default:
                return 0L;
        }
    }

    private static long nextDaily(LocalDate start, LocalTime time, ZonedDateTime after, ZoneId zone) {
        LocalDate date = after.toLocalDate().isAfter(start) ? after.toLocalDate() : start;
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (!candidate.isAfter(after)) candidate = date.plusDays(1).atTime(time).atZone(zone);
        return candidate.toInstant().toEpochMilli();
    }

    private static long nextWeekPattern(LocalDate start, LocalTime time, ZonedDateTime after,
                                        ZoneId zone, int mask, int weekInterval) {
        if ((mask & 0x7f) == 0) return 0L;
        LocalDate anchorMonday = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = after.toLocalDate().isAfter(start) ? after.toLocalDate() : start;
        for (int i = 0; i < 3700; i++) {
            LocalDate date = first.plusDays(i);
            if (!isSelected(mask, date.getDayOfWeek())) continue;
            LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long weeks = ChronoUnit.WEEKS.between(anchorMonday, monday);
            if (weeks < 0 || weeks % Math.max(1, weekInterval) != 0) continue;
            ZonedDateTime candidate = date.atTime(time).atZone(zone);
            if (candidate.isAfter(after)) return candidate.toInstant().toEpochMilli();
        }
        return 0L;
    }

    private static long nextEveryDays(LocalDate start, LocalTime time, ZonedDateTime after,
                                      ZoneId zone, int interval) {
        LocalDate date = after.toLocalDate().isAfter(start) ? after.toLocalDate() : start;
        long days = ChronoUnit.DAYS.between(start, date);
        if (days < 0) days = 0;
        long remainder = days % interval;
        if (remainder != 0) date = date.plusDays(interval - remainder);
        ZonedDateTime candidate = date.atTime(time).atZone(zone);
        if (!candidate.isAfter(after)) candidate = date.plusDays(interval).atTime(time).atZone(zone);
        return candidate.toInstant().toEpochMilli();
    }

    private static long nextEveryMonths(LocalDate start, LocalTime time, ZonedDateTime after,
                                        ZoneId zone, int interval) {
        YearMonth anchor = YearMonth.from(start);
        YearMonth month = YearMonth.from(after.toLocalDate());
        long months = ChronoUnit.MONTHS.between(anchor, month);
        if (months < 0) months = 0;
        long remainder = months % interval;
        if (remainder != 0) month = month.plusMonths(interval - remainder);
        for (int i = 0; i < 2400; i++) {
            int day = Math.min(start.getDayOfMonth(), month.lengthOfMonth());
            ZonedDateTime candidate = month.atDay(day).atTime(time).atZone(zone);
            if (!candidate.isBefore(start.atTime(time).atZone(zone)) && candidate.isAfter(after)) {
                return candidate.toInstant().toEpochMilli();
            }
            month = month.plusMonths(interval);
        }
        return 0L;
    }

    private static long futureMillis(ZonedDateTime candidate, ZonedDateTime after) {
        return candidate.isAfter(after) ? candidate.toInstant().toEpochMilli() : 0L;
    }

    public static int bitFor(DayOfWeek day) {
        return 1 << (day.getValue() - 1);
    }

    public static boolean isSelected(int mask, DayOfWeek day) {
        return (mask & bitFor(day)) != 0;
    }

    public static String summary(RoutineTriggerStore.Trigger t) {
        if (t == null) return "Automatic trigger";
        if (RoutineTriggerStore.TYPE_LOCATION.equals(t.type)) {
            String verb = RoutineTriggerStore.LOCATION_EXIT.equals(t.locationTransition) ? "Leave" : "Arrive at";
            return verb + " " + t.locationName + " · " + radiusLabel(t.radiusMeters);
        }
        String time = RoutineActionCatalog.timeLabel(t.hour, t.minute);
        switch (t.mode) {
            case RoutineTriggerStore.MODE_ONCE:
                return "Once · " + shortDate(t) + " · " + time;
            case RoutineTriggerStore.MODE_DAILY:
                return "Daily · " + time;
            case RoutineTriggerStore.MODE_WEEKDAYS:
                return "Weekdays · " + time;
            case RoutineTriggerStore.MODE_WEEKENDS:
                return "Weekends · " + time;
            case RoutineTriggerStore.MODE_WEEKLY:
                if (t.intervalCount == 2) return "Every 2 weeks · " + weekdaySummary(t.weekdayMask) + " · " + time;
                if (t.intervalCount > 1) return "Every " + t.intervalCount + " weeks · " + weekdaySummary(t.weekdayMask) + " · " + time;
                return "Weekly · " + weekdaySummary(t.weekdayMask) + " · " + time;
            case RoutineTriggerStore.MODE_CUSTOM:
                if (RoutineTriggerStore.UNIT_DAYS.equals(t.intervalUnit)) {
                    return "Every " + t.intervalCount + (t.intervalCount == 1 ? " day" : " days") + " · " + time;
                }
                if (RoutineTriggerStore.UNIT_WEEKS.equals(t.intervalUnit)) {
                    return "Every " + t.intervalCount + (t.intervalCount == 1 ? " week" : " weeks") + " · " + weekdaySummary(t.weekdayMask) + " · " + time;
                }
                if (t.intervalCount == 1) return "Monthly · day " + t.startDay + " · " + time;
                return "Every " + t.intervalCount + " months · day " + t.startDay + " · " + time;
            default:
                return "Time trigger · " + time;
        }
    }

    public static String repeatLabel(RoutineTriggerStore.Trigger t) {
        if (t == null) return "Daily";
        switch (t.mode) {
            case RoutineTriggerStore.MODE_ONCE: return "Once";
            case RoutineTriggerStore.MODE_DAILY: return "Daily";
            case RoutineTriggerStore.MODE_WEEKDAYS: return "Weekdays";
            case RoutineTriggerStore.MODE_WEEKENDS: return "Weekends";
            case RoutineTriggerStore.MODE_WEEKLY:
                return t.intervalCount == 2 ? "Every 2 weeks" : "Weekly";
            case RoutineTriggerStore.MODE_CUSTOM:
                if (RoutineTriggerStore.UNIT_MONTHS.equals(t.intervalUnit) && t.intervalCount == 1) return "Monthly";
                return "Custom interval";
            default: return "Daily";
        }
    }

    public static String weekdaySummary(int mask) {
        if (mask == 0b00011111) return "Mon–Fri";
        if (mask == 0b01100000) return "Sat–Sun";
        String[] shortNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (out.length() > 0) out.append(", ");
            out.append(shortNames[i]);
        }
        return out.length() == 0 ? "Choose days" : out.toString();
    }


    public static String radiusLabel(float meters) {
        if (meters >= 1000f) {
            float km = meters / 1000f;
            if (Math.abs(km - Math.round(km)) < 0.01f) return Math.round(km) + " km radius";
            return String.format(Locale.US, "%.1f km radius", km);
        }
        return Math.round(meters) + " m radius";
    }

    private static String shortDate(RoutineTriggerStore.Trigger t) {
        try {
            LocalDate d = LocalDate.of(t.startYear, t.startMonth, t.startDay);
            return d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) + " " +
                    d.getDayOfMonth() + ", " + d.getYear();
        } catch (Exception e) {
            return "date";
        }
    }
}

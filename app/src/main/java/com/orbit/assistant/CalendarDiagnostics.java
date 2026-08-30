package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What Orbit records about Calendar writes, and deliberately nothing more.
 *
 * <p>Calendar contents are among the most personal things on a phone, and a diagnostics report is
 * something people paste into a bug thread. So this records shape, never substance: whether the
 * capability is available, what permission state was seen, how many writable calendars exist, the
 * display name of the one selected, and the counts that came back.
 *
 * <p>Event titles, dates, times, locations, descriptions, source links, attendee information,
 * account addresses, and the contents of any existing calendar never enter this store. The only
 * free text is a short fixed reason string chosen from Orbit's own vocabulary.
 */
public final class CalendarDiagnostics {
    private CalendarDiagnostics() {}

    static void recordWrite(Context c, int writableCalendars, CalendarActionExecutor.Outcome outcome) {
        if (c == null || outcome == null) return;
        edit(c)
                .putInt("calendar_writable_count", Math.max(0, writableCalendars))
                .putString("calendar_target", safe(outcome.calendarName))
                .putInt("calendar_requested", outcome.requested())
                .putInt("calendar_added", outcome.added)
                .putInt("calendar_duplicates", outcome.alreadyPresent)
                .putInt("calendar_failed", outcome.failed)
                .putString("calendar_last_error", safe(outcome.lastError))
                .putLong("calendar_updated", System.currentTimeMillis())
                .apply();
    }

    /**
     * A write that never started. The count is what the plan asked for, so a report can show that
     * nothing was written rather than leaving the previous run's numbers standing.
     */
    static void recordBlocked(Context c, String reason, int requested) {
        if (c == null) return;
        edit(c)
                .putInt("calendar_writable_count", OrbitCalendarStore.hasAccess(c)
                        ? OrbitCalendarStore.writableCalendars(c).size() : 0)
                .putString("calendar_target", "")
                .putInt("calendar_requested", Math.max(0, requested))
                .putInt("calendar_added", 0)
                .putInt("calendar_duplicates", 0)
                .putInt("calendar_failed", 0)
                .putString("calendar_last_error", safe(reason))
                .putLong("calendar_updated", System.currentTimeMillis())
                .apply();
    }

    /** The Calendar block of the diagnostics report, heading included. */
    public static String report(Context c) {
        return "\n\nCalendar" + body(c);
    }

    /**
     * The same lines without the heading, for the sectioned Diagnostics screen.
     *
     * <p>Split out rather than duplicated so the collapsible section and the copied report can
     * never describe the calendar differently.
     */
    public static String body(Context c) {
        SharedPreferences d = DiagnosticStore.prefs(c);
        long updated = d.getLong("calendar_updated", 0L);
        boolean access = OrbitCalendarStore.hasAccess(c);
        int writable = access ? OrbitCalendarStore.writableCalendars(c).size() : 0;
        OrbitCalendarStore.Target target = access ? OrbitCalendarStore.resolveTarget(c) : null;
        String lastError = d.getString("calendar_last_error", "");

        return "\n  Direct calendar writes: available"
                + "\n  Permission: " + (access ? "granted" : "not granted")
                + "\n  Writable calendars: " + writable
                + "\n  Selected calendar: " + (target == null
                        ? (writable > 1 ? "not chosen yet" : "none") : target.label())
                + "\n  Last requested events: " + d.getInt("calendar_requested", 0)
                + "\n  Last added: " + d.getInt("calendar_added", 0)
                + "\n  Last already present: " + d.getInt("calendar_duplicates", 0)
                + "\n  Last failed: " + d.getInt("calendar_failed", 0)
                + "\n  Last calendar issue: " + (lastError == null || lastError.trim().isEmpty()
                        ? "none" : lastError)
                + (updated == 0L ? "" : "\n  Last calendar write at: "
                        + java.text.DateFormat.getDateTimeInstance()
                                .format(new java.util.Date(updated)));
    }

    private static SharedPreferences.Editor edit(Context c) {
        return DiagnosticStore.prefs(c).edit();
    }

    private static String safe(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > 90 ? trimmed.substring(0, 90) : trimmed;
    }
}

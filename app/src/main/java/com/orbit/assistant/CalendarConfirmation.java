package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * What a Calendar confirmation says, shared by full chat and the Side-button overlay.
 *
 * <p>One confirmation covers a whole batch. Twelve dialogs for a twelve-game schedule would be
 * unusable, and would also make it impossible to see the thing that actually matters before
 * agreeing: which calendar this lands in, what date range it covers, and how many of the events
 * have no announced start time yet.
 *
 * <p>Only the wording lives here. Each surface renders it in its own idiom, because the overlay
 * draws its confirmation inside the sheet while full chat uses Orbit's canonical dialog.
 */
public final class CalendarConfirmation {

    public static final class Preview {
        /** The question, e.g. {@code Add 12 events to Personal?} */
        public final String title;
        /** Supporting lines: target calendar, date range, first few events, TBA count. */
        public final List<String> lines;
        /** True when the user has more than one writable calendar to choose between. */
        public final boolean canChangeCalendar;
        public final String calendarName;
        public final int eventCount;

        Preview(String title, List<String> lines, boolean canChangeCalendar,
                String calendarName, int eventCount) {
            this.title = title;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.canChangeCalendar = canChangeCalendar;
            this.calendarName = calendarName == null ? "" : calendarName;
            this.eventCount = eventCount;
        }

        public String detail() { return String.join("\n", lines); }
    }

    /** How many individual events a preview names before summarising the rest. */
    private static final int PREVIEW_EVENTS = 3;

    private CalendarConfirmation() {}

    public static Preview of(Context context, AssistantReply.Action action) {
        CalendarActionExecutor.Plan plan = CalendarActionExecutor.parse(
                action == null ? null : action.params);
        String calendarName = targetName(context);
        List<String> lines = new ArrayList<>();

        if (!plan.ok()) {
            // Still a confirmation rather than a silent failure: the user sees why nothing will be
            // written, and Cancel and Continue both end with zero events added.
            lines.add(plan.error.isEmpty() ? "These events are not valid." : plan.error);
            return new Preview("Add calendar events?", lines,
                    context != null && OrbitCalendarStore.hasChoice(context), calendarName, 0);
        }

        int count = plan.size();
        String title = "Add " + count + (count == 1 ? " event to " : " events to ")
                + calendarName + "?";

        lines.add("Calendar: " + calendarName);
        String range = plan.dateRange();
        if (!range.isEmpty()) lines.add(range);
        for (int i = 0; i < Math.min(PREVIEW_EVENTS, count); i++) {
            lines.add("• " + plan.events.get(i).shortLabel());
        }
        if (count > PREVIEW_EVENTS) {
            int remaining = count - PREVIEW_EVENTS;
            lines.add("• and " + remaining + (remaining == 1 ? " more event" : " more events"));
        }
        int tba = plan.tbaCount();
        if (tba > 0) {
            lines.add(tba + (tba == 1 ? " event has" : " events have")
                    + " no announced start time yet, so " + (tba == 1 ? "it becomes" : "they become")
                    + " an all-day " + CalendarActionExecutor.TBA_MARKER + " entry.");
        }
        return new Preview(title, lines,
                context != null && OrbitCalendarStore.hasChoice(context), calendarName, count);
    }

    /** The calendar Orbit would use, named the way the confirmation and the result both name it. */
    public static String targetName(Context context) {
        if (context == null) return "your calendar";
        if (!OrbitCalendarStore.hasAccess(context)) return "your calendar";
        OrbitCalendarStore.Target target = OrbitCalendarStore.resolveTarget(context);
        if (target != null) return target.label();
        return OrbitCalendarStore.writableCalendars(context).isEmpty()
                ? "your calendar" : "a calendar you choose";
    }

    /**
     * Chooser rows for the writable calendars, in the order they are offered.
     *
     * <p>The owning account is added only where it actually tells two rows apart. Two calendars
     * both called "Personal" on different accounts need it; "Personal" and "Work" on one account
     * are only made harder to read by repeating the same address twice.
     */
    public static String[] calendarChoices(List<OrbitCalendarStore.Target> targets) {
        if (targets == null) return new String[0];
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            OrbitCalendarStore.Target target = targets.get(i);
            boolean shared = false;
            for (int j = 0; j < targets.size(); j++) {
                if (j == i) continue;
                if (targets.get(j).displayName.equalsIgnoreCase(target.displayName)) {
                    shared = true;
                    break;
                }
            }
            labels[i] = shared ? target.chooserLabel() : target.label();
        }
        return labels;
    }

    /** The short card title for a Calendar batch, e.g. {@code Calendar · 12 events}. */
    public static String cardTitle(AssistantReply.Action action) {
        CalendarActionExecutor.Plan plan = CalendarActionExecutor.parse(
                action == null ? null : action.params);
        int count = plan.ok() ? plan.size() : 0;
        if (count <= 0) return "Calendar events";
        return "Calendar · " + count + (count == 1 ? " event" : " events");
    }
}

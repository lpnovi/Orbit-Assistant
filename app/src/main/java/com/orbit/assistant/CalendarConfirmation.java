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
 * <p>The destination is no longer a sentence buried in the detail text. It is a field of its own,
 * described by {@code selectorLabel}, because it is the one part of the agreement a person may
 * want to change before saying yes. Only the wording lives here; each surface draws that field in
 * its own idiom, since the overlay renders inside its sheet while full chat uses Orbit's canonical
 * dialog.
 */
public final class CalendarConfirmation {

    public static final class Preview {
        /** The question, e.g. {@code Add 12 events to Personal?} */
        public final String title;
        /** Supporting lines: date range, first few events, TBA count. Never the destination. */
        public final List<String> lines;
        /** What the destination field reads, e.g. {@code Personal} or {@code Choose a calendar}. */
        public final String selectorLabel;
        /** True when tapping the destination field would offer a real alternative. */
        public final boolean canChangeCalendar;
        /** True when Orbit knows where these events go. */
        public final boolean targetResolved;
        /**
         * True when the phone has several calendars and none is a safe default.
         *
         * <p>The one case where Add is refused by the UI rather than by the executor: there is a
         * real answer here and only the user has it, so falling through to a red card would be
         * manufacturing an error out of a question.
         */
        public final boolean needsChoice;
        public final int eventCount;

        Preview(String title, List<String> lines, String selectorLabel, boolean canChangeCalendar,
                boolean targetResolved, boolean needsChoice, int eventCount) {
            this.title = title;
            this.lines = lines == null ? new ArrayList<>() : lines;
            this.selectorLabel = selectorLabel == null ? "" : selectorLabel;
            this.canChangeCalendar = canChangeCalendar;
            this.targetResolved = targetResolved;
            this.needsChoice = needsChoice;
            this.eventCount = eventCount;
        }

        /** True when Add may execute the action from this confirmation. */
        public boolean canAdd() { return !needsChoice; }

        public String detail() { return String.join("\n", lines); }
    }

    /** How many individual events a preview names before summarising the rest. */
    private static final int PREVIEW_EVENTS = 3;

    private CalendarConfirmation() {}

    /** Convenience for callers that have not already resolved the destination. */
    public static Preview of(Context context, AssistantReply.Action action) {
        return of(context, action, CalendarTargetResolver.state(context));
    }

    /**
     * Builds the confirmation from an already-resolved destination.
     *
     * <p>Taking the state as an argument is the point: the surfaces resolve permission and read
     * the provider first, then describe what they found. Beta 1 described the device before asking
     * it anything, which is how a first-ever Calendar request could be summarised as going to
     * "your calendar" with no way to change it.
     */
    public static Preview of(Context context, AssistantReply.Action action,
                             CalendarTargetResolver.State state) {
        CalendarTargetResolver.State resolved = state == null
                ? CalendarTargetResolver.state(context) : state;
        CalendarActionExecutor.Plan plan = CalendarActionExecutor.parse(
                action == null ? null : action.params);
        List<String> lines = new ArrayList<>();

        if (!plan.ok()) {
            // Still a confirmation rather than a silent failure: the user sees why nothing will be
            // written, and Cancel and Continue both end with zero events added.
            lines.add(plan.error.isEmpty() ? "These events are not valid." : plan.error);
            return new Preview("Add calendar events?", lines, resolved.selectorLabel(),
                    resolved.canChoose(), resolved.resolved(), resolved.needsChoice(), 0);
        }

        int count = plan.size();
        String title = "Add " + count + (count == 1 ? " event to " : " events to ")
                + resolved.destinationName() + "?";

        String range = plan.dateRange();
        if (!range.isEmpty()) lines.add(range);
        CalendarActionExecutor.ClockStyle clock = CalendarActionExecutor.clockStyle(context);
        for (int i = 0; i < Math.min(PREVIEW_EVENTS, count); i++) {
            lines.add("• " + plan.events.get(i).shortLabel(clock));
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
        // Why the destination field reads the way it does, said once, only when it is not obvious.
        if (resolved.needsChoice()) {
            lines.add("Choose a calendar before adding these events.");
        } else if (resolved.noWritableCalendar()) {
            lines.add("No writable calendar is set up on this phone.");
        } else if (!resolved.permitted) {
            lines.add("Orbit needs Calendar access to add these events.");
        }
        return new Preview(title, lines, resolved.selectorLabel(), resolved.canChoose(),
                resolved.resolved(), resolved.needsChoice(), count);
    }

    /** The calendar Orbit would use, named the way the confirmation and the result both name it. */
    public static String targetName(Context context) {
        return CalendarTargetResolver.state(context).destinationName();
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

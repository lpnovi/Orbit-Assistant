package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * The step that turns "Orbit wants to write to a calendar" into "Orbit knows which calendar".
 *
 * <p>This exists because permission and discovery are not independent. Every question Orbit can
 * ask about calendars — how many are writable, which one is primary, whether the remembered one
 * still exists — is unanswerable until Calendar permission is held, and returns an empty,
 * confident-looking "nothing here" before then. Beta 1 built its confirmation from that empty
 * answer, so the very first Calendar request was decided before the device had been asked.
 *
 * <p>So the order is fixed here, once, for both surfaces: resolve permission, then read the
 * provider, then resolve the target, then let a surface draw anything. A grant is never treated
 * as approval to write; it only makes the real state readable.
 */
public final class CalendarTargetResolver {

    public interface Ready { void onReady(State state); }

    /**
     * What Orbit actually knows about the destination right now.
     *
     * <p>A snapshot rather than a set of live lookups, so the confirmation a person is looking at
     * and the decision about whether Add may run cannot disagree with each other.
     */
    public static final class State {
        /** Whether Calendar permission is held. Everything below is meaningless without it. */
        public final boolean permitted;
        /** Every calendar Orbit could write to, empty when not permitted. */
        public final List<OrbitCalendarStore.Target> writable;
        /** The resolved destination, or null when the choice is genuinely the user's. */
        public final OrbitCalendarStore.Target target;

        State(boolean permitted, List<OrbitCalendarStore.Target> writable,
              OrbitCalendarStore.Target target) {
            this.permitted = permitted;
            this.writable = writable == null ? new ArrayList<>() : writable;
            this.target = target;
        }

        /** True when Orbit has a destination and the write may proceed. */
        public boolean resolved() { return target != null; }

        /** True when the phone has calendars, none of them is a safe default, and Orbit must ask. */
        public boolean needsChoice() {
            return permitted && target == null && writable.size() > 1;
        }

        /** True when there is genuinely nowhere to write. */
        public boolean noWritableCalendar() { return permitted && writable.isEmpty(); }

        /** True when a chooser would offer a real alternative rather than a list of one. */
        public boolean canChoose() { return permitted && writable.size() > 1; }

        /** What the selector row reads, in every state including the unhappy ones. */
        public String selectorLabel() {
            if (!permitted) return "Calendar access needed";
            if (writable.isEmpty()) return "No writable calendar";
            if (target != null) return target.label();
            return "Choose a calendar";
        }

        /** How the confirmation title names the destination. */
        public String destinationName() {
            if (target != null) return target.label();
            if (permitted && writable.size() > 1) return "a calendar you choose";
            return "your calendar";
        }
    }

    private CalendarTargetResolver() {}

    /**
     * Reads the current state without asking for anything.
     *
     * <p>A remembered calendar that no longer exists, or that has become read-only, is forgotten
     * here rather than being carried around as a stale id that quietly resolves to nothing. The
     * id is only dropped when the provider actually answered, so a failed query cannot erase a
     * perfectly good choice.
     */
    public static State state(Context c) {
        boolean permitted = OrbitCalendarStore.hasAccess(c);
        List<OrbitCalendarStore.Target> writable = permitted
                ? OrbitCalendarStore.writableCalendars(c) : new ArrayList<>();
        long stored = OrbitCalendarStore.storedTargetId(c);
        if (permitted && stored >= 0 && !writable.isEmpty() && !contains(writable, stored)) {
            OrbitCalendarStore.forgetTarget(c);
            stored = -1L;
        }
        return new State(permitted, writable,
                OrbitCalendarStore.resolveTarget(writable, stored));
    }

    /**
     * Resolves permission if it is missing, then re-reads the provider and reports the real state.
     *
     * <p>The re-read after the prompt is the whole point. Discovery that ran before the grant saw
     * an empty device, and must not be allowed to decide anything afterwards.
     */
    public static void prepare(Context c, Ready onReady) {
        if (onReady == null) return;
        if (c == null) {
            onReady.onReady(new State(false, null, null));
            return;
        }
        ensureAccess(c, () -> onReady.onReady(state(c)));
    }

    /**
     * Records the user's choice.
     *
     * <p>Only the id is stored. Nothing read from the provider — names, accounts, event contents —
     * is ever written to preferences.
     */
    public static State choose(Context c, OrbitCalendarStore.Target target) {
        if (c != null && target != null) OrbitCalendarStore.rememberTarget(c, target.id);
        return state(c);
    }

    /** The chooser rows for the current state, in the order they are offered. */
    public static String[] choices(State state) {
        return CalendarConfirmation.calendarChoices(state == null ? null : state.writable);
    }

    /** The index of the current target among {@link #choices}, or -1 when nothing is chosen. */
    public static int selectedIndex(State state) {
        if (state == null || state.target == null) return -1;
        for (int i = 0; i < state.writable.size(); i++) {
            if (state.writable.get(i).id == state.target.id) return i;
        }
        return -1;
    }

    /**
     * Runs {@code continuation} once Calendar permission has been resolved one way or the other.
     *
     * <p>Shared with {@link CalendarActionGate} so the pre-confirmation and post-approval paths
     * use exactly one permission mechanism. A denial still continues: the caller, and ultimately
     * {@link CalendarActionExecutor}, decides what a denial means, and the executor's refusal is
     * what makes "a denial produces zero writes" true for every route.
     */
    static void ensureAccess(Context context, Runnable continuation) {
        if (continuation == null) return;
        if (context == null || OrbitCalendarStore.hasAccess(context)) {
            continuation.run();
            return;
        }
        final Context app = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
        String token = CalendarAccessBridge.register(granted -> continuation.run());
        if (!CalendarPermissionActivity.start(context, token)) {
            // Android could not open the prompt. Deliver a result rather than leaving the caller
            // waiting forever; the state then simply reports that access is missing.
            CalendarAccessBridge.deliver(token, OrbitCalendarStore.hasAccess(app));
        }
    }

    private static boolean contains(List<OrbitCalendarStore.Target> writable, long id) {
        for (OrbitCalendarStore.Target t : writable) if (t.id == id) return true;
        return false;
    }
}

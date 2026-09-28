package com.orbit.assistant;

import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The device targets Orbit most recently acted on, so a short follow-up can be resolved.
 *
 * <p>"Turn the brightness down." then "A little more." should mean brightness. The authority for
 * that comes from an action Orbit actually executed, not from anything a model said, and it is
 * deliberately narrow: the targets of the last turn that changed something, remembered briefly, and
 * only used when the follow-up names no target of its own.
 *
 * <p>If anything is unclear — no recent action, the memory has aged out, or the follow-up could
 * plausibly mean something else — this resolves to nothing and the request takes its normal path.
 * Guessing wrong here changes the user's phone.
 *
 * <p>Since v0.8.2.0 a turn can change several things ("turn on Do Not Disturb and dim the screen"),
 * so what is remembered is a <em>batch</em>: every target the last run of actions changed. A
 * follow-up only resolves when that batch holds exactly one target. With two, "put it back" could
 * mean either, and Orbit asks rather than choosing.
 */
public final class RecentActionContext {
    /** How long a device action stays available as conversational context. */
    static final long WINDOW_MS = 90_000L;

    /** The kinds of follow-up Orbit can resolve. */
    public enum Target { BRIGHTNESS, VOLUME, FLASHLIGHT, DND, RINGER }

    /** One change Orbit made, with the state before it when Orbit actually read it. */
    private static final class Entry {
        final Target target;
        final int previousPercent;
        final boolean previousOn;
        final String previousMode;

        Entry(Target target, int previousPercent, boolean previousOn, String previousMode) {
            this.target = target;
            this.previousPercent = previousPercent;
            this.previousOn = previousOn;
            this.previousMode = previousMode == null ? "" : previousMode;
        }
    }

    private static final List<Entry> batch = new ArrayList<>();
    private static long atElapsedMs;
    /** True while one run of actions is recording into the same batch. */
    private static boolean collecting;

    private RecentActionContext() {}

    /** Forgets everything. Used at the start of a fresh invocation and by tests. */
    public static synchronized void clear() {
        batch.clear();
        atElapsedMs = 0L;
        collecting = false;
    }

    /**
     * Starts a new batch: everything recorded until {@link #endBatch} belongs to one turn.
     *
     * <p>Called by {@link OrbitActionEngine} around every run, so a multi-action reply is remembered
     * as one thing the user did rather than as whichever step happened to run last.
     */
    public static synchronized void beginBatch() {
        batch.clear();
        collecting = true;
    }

    public static synchronized void endBatch() {
        collecting = false;
    }

    private static void record(Entry entry) {
        if (!collecting) batch.clear();
        for (int i = 0; i < batch.size(); i++) {
            // The same target twice in one turn keeps the state from before the turn began, which
            // is what "put it back" means.
            if (batch.get(i).target == entry.target) {
                atElapsedMs = SystemClock.elapsedRealtime();
                return;
            }
        }
        batch.add(entry);
        atElapsedMs = SystemClock.elapsedRealtime();
    }

    /**
     * Records a level change Orbit performed.
     *
     * @param previous the level before the change, or -1 when Orbit could not read it. Only a
     *                 real reading is stored, because reversal must never invent history.
     */
    public static synchronized void recordLevel(Target changed, int previous) {
        if (changed == null) return;
        record(new Entry(changed, previous >= 0 && previous <= 100 ? previous : -1, false, ""));
    }

    /** Records a flashlight change Orbit performed. */
    public static synchronized void recordFlashlight(boolean nowOn) {
        record(new Entry(Target.FLASHLIGHT, -1, !nowOn, ""));
    }

    /**
     * Records a Do Not Disturb change Orbit performed.
     *
     * @param previousEnabled the state Orbit read before the change, or null when it could not read
     *                        one it can restore exactly. The change is still remembered, so a later
     *                        "put it back" cannot reach past it to an older one, but it will not be
     *                        undone by guessing.
     */
    public static synchronized void recordDnd(Boolean previousEnabled) {
        record(new Entry(Target.DND, -1, previousEnabled != null && previousEnabled,
                previousEnabled == null ? "" : previousEnabled ? "on" : "off"));
    }

    /**
     * Records a ringer change Orbit performed.
     *
     * @param previousMode "normal", "vibrate" or "silent" as Orbit read it before the change, or ""
     *                     when it could not, in which case the change cannot be put back.
     */
    public static synchronized void recordRinger(String previousMode) {
        String mode = previousMode == null ? "" : previousMode.trim().toLowerCase(Locale.US);
        if (!"normal".equals(mode) && !"vibrate".equals(mode) && !"silent".equals(mode)) mode = "";
        record(new Entry(Target.RINGER, -1, false, mode));
    }

    private static boolean fresh() {
        return !batch.isEmpty() && SystemClock.elapsedRealtime() - atElapsedMs <= WINDOW_MS;
    }

    private static Entry single() {
        return fresh() && batch.size() == 1 ? batch.get(0) : null;
    }

    /** The remembered target, or null when there is none, it has aged out, or there are several. */
    public static synchronized Target current() {
        Entry entry = single();
        return entry == null ? null : entry.target;
    }

    /** Whether the last turn changed more than one thing, so a bare follow-up cannot be resolved. */
    public static synchronized boolean isAmbiguous() {
        return fresh() && batch.size() > 1;
    }

    /** Whether any recent change is remembered at all. */
    public static synchronized boolean hasRecent() {
        return fresh();
    }

    /** The level before the last change, or -1 when Orbit does not actually know it. */
    public static synchronized int previousPercent() {
        Entry entry = single();
        return entry == null ? -1 : entry.previousPercent;
    }

    /** The flashlight state before the last change. Only meaningful for a flashlight target. */
    public static synchronized boolean previousFlashlightOn() {
        Entry entry = single();
        return entry != null && entry.target == Target.FLASHLIGHT && entry.previousOn;
    }

    /** The Do Not Disturb state before the last change. Only meaningful for a DND target. */
    public static synchronized boolean previousDndEnabled() {
        Entry entry = single();
        return entry != null && entry.target == Target.DND && "on".equals(entry.previousMode);
    }

    /** The ringer mode before the last change, or "" when unknown or not a ringer target. */
    public static synchronized String previousRingerMode() {
        Entry entry = single();
        return entry == null || entry.target != Target.RINGER ? "" : entry.previousMode;
    }

    // ---- turning a follow-up into an action ------------------------------------------------------

    /** An action that undoes the remembered change, and what Orbit says while doing it. */
    static final class Restore {
        final AssistantReply.Action action;
        final String spoken;
        final String summary;

        Restore(AssistantReply.Action action, String spoken, String summary) {
            this.action = action;
            this.spoken = spoken;
            this.summary = summary;
        }
    }

    /**
     * The action that puts the one remembered change back, or null.
     *
     * <p>Null whenever Orbit does not know exactly what "back" is: nothing recent, several changes,
     * or a level Orbit never read. Built by Orbit from recorded state only.
     */
    static synchronized Restore restore() {
        Entry entry = single();
        if (entry == null) return null;
        try {
            switch (entry.target) {
                case FLASHLIGHT:
                    return new Restore(action("FLASHLIGHT", new JSONObject().put("on", entry.previousOn)),
                            entry.previousOn ? "Turning the flashlight back on."
                                    : "Turning the flashlight back off.",
                            entry.previousOn ? "turn on the flashlight" : "turn off the flashlight");
                case DND:
                    if (entry.previousMode.isEmpty()) return null;
                    return new Restore(action("SET_DND", new JSONObject().put("enabled", entry.previousOn)),
                            entry.previousOn ? "Turning Do Not Disturb back on."
                                    : "Turning Do Not Disturb back off.",
                            entry.previousOn ? "turn on Do Not Disturb" : "turn off Do Not Disturb");
                case RINGER: {
                    if (entry.previousMode.isEmpty()) return null;
                    String mode = entry.previousMode;
                    String spoken = "normal".equals(mode) ? "Turning the ringer back on."
                            : "vibrate".equals(mode) ? "Putting the phone back on vibrate."
                            : "Silencing the phone again.";
                    return new Restore(action("SET_RINGER_MODE", new JSONObject().put("mode", mode)),
                            spoken, "set the ringer back to " + mode);
                }
                case BRIGHTNESS:
                case VOLUME: {
                    // Orbit does not know where it was, so it does not pretend to.
                    if (entry.previousPercent < 0) return null;
                    boolean brightness = entry.target == Target.BRIGHTNESS;
                    String noun = brightness ? "brightness" : "media volume";
                    return new Restore(action(brightness ? "SET_BRIGHTNESS" : "SET_VOLUME",
                            new JSONObject().put("percent", entry.previousPercent)),
                            "Putting " + noun + " back to " + entry.previousPercent + "%.",
                            "set " + noun + " back to " + entry.previousPercent + "%");
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What Orbit asks when a follow-up could mean more than one recent change, or "" when it could
     * not. Names Orbit's own targets only.
     */
    static synchronized String clarification() {
        if (!isAmbiguous()) return "";
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) names.append(i == batch.size() - 1 ? " and " : ", ");
            names.append(name(batch.get(i).target));
        }
        return "I just changed " + names + ". Which one do you mean?";
    }

    static String name(Target target) {
        switch (target) {
            case BRIGHTNESS: return "brightness";
            case VOLUME: return "media volume";
            case FLASHLIGHT: return "the flashlight";
            case DND: return "Do Not Disturb";
            case RINGER: return "the ringer";
            default: return "a setting";
        }
    }

    private static AssistantReply.Action action(String type, JSONObject params) {
        return new AssistantReply.Action(type, params, false);
    }

    // ---- recognising a follow-up ----------------------------------------------------------------

    /** "Put it back", "undo that", "change it back to how it was", "revert". */
    private static final java.util.regex.Pattern RESTORE = java.util.regex.Pattern.compile(
            "^(?:(?:undo|revert|reverse)(?:\\s+(?:it|that|this|the last (?:one|change)))?"
                    + "|(?:put|set|turn|change|switch|bring)\\s+(?:it|that|things|everything)\\s+back"
                    + "(?:\\s+(?:to\\s+)?(?:how|what|where)\\s+(?:it|they)\\s+(?:was|were)(?:\\s+before)?)?"
                    + "|(?:go\\s+)?back\\s+to\\s+(?:how|what)\\s+it\\s+was(?:\\s+before)?"
                    + "|put\\s+it\\s+back\\s+the\\s+way\\s+it\\s+was)$");
    /** "turn it back on", "switch that back off". */
    private static final java.util.regex.Pattern BACK_ON_OFF = java.util.regex.Pattern.compile(
            "^(?:turn|switch|put)\\s+(?:it|that)\\s+back\\s+(on|off)$");
    /** A bare direction: "a little more", "down a bit", "turn it off". */
    private static final java.util.regex.Pattern BARE_DIRECTION = java.util.regex.Pattern.compile(
            "^(?:(?:a\\s+)?(?:little|bit|tad)\\s+)?(?:more|less|higher|lower|louder|quieter|brighter|"
                    + "dimmer|up|down)(?:\\s+(?:a\\s+)?(?:little|bit))?$"
                    + "|^(?:make|turn|switch)\\s+(?:it|that)\\s+(?:(?:a\\s+)?(?:little|bit)\\s+)?"
                    + "(?:more|less|higher|lower|louder|quieter|brighter|dimmer|up|down|on|off)"
                    + "(?:\\s+(?:a\\s+)?(?:little|bit))?$");

    /** Whether a canonical phrase asks to undo the last change. */
    static boolean isRestore(String canonical) {
        return canonical != null && RESTORE.matcher(canonical.trim()).matches();
    }

    /** "on" or "off" for "turn it back on/off", or null. */
    static String backOnOff(String canonical) {
        if (canonical == null) return null;
        java.util.regex.Matcher m = BACK_ON_OFF.matcher(canonical.trim());
        return m.matches() ? m.group(1) : null;
    }

    /** Whether a phrase has the shape of a follow-up about a recent change, of any kind. */
    static boolean looksLikeFollowUp(String canonical) {
        if (canonical == null) return false;
        String q = canonical.trim();
        return isRestore(q) || backOnOff(q) != null || BARE_DIRECTION.matcher(q).matches();
    }

    /**
     * Whether a phrase is a bare follow-up: it asks for something, but names no target of its
     * own. Only these may borrow the remembered target.
     */
    public static boolean isBareFollowUp(String canonical) {
        if (canonical == null) return false;
        String q = canonical.trim();
        if (q.isEmpty()) return false;
        // Naming any target makes this an ordinary command, not a follow-up.
        if (q.matches(".*\\b(brightness|screen|display|volume|sound|audio|flashlight|torch|"
                + "do not disturb|ringer|vibrate|silent)\\b.*")) {
            return false;
        }
        return true;
    }
}

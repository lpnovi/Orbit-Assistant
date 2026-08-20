package com.orbit.assistant;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A persistent, bounded record of Side-button overlay launches.
 *
 * <p>Orbit's assistant overlay occasionally fails to appear after the Side-button gesture, and the
 * failure has never reproduced on demand. {@link ComposerTrace} cannot help here: it lives in
 * memory, and the suspected failure may end the process before the user can reach Diagnostics. So
 * this trace is written to an app-private file as the launch happens, and the next launch — in the
 * next process, after a reboot, after a force stop — can still read what the last one managed to
 * do before it stopped.
 *
 * <p>The point is to identify the <em>last stage that succeeded</em>. Each attempt records the
 * lifecycle milestones from {@code OrbitSessionService.onNewSession} through the first frame the
 * user could actually see, and then whichever ending arrived: an Orbit-initiated dismissal, an
 * internal transition to another Orbit screen, a hide Orbit never asked for, an exception, or
 * nothing at all.
 *
 * <h2>Privacy</h2>
 * The recording API is deliberately closed. Callers pass a stage name from the constants below, and
 * state through {@link State}, which accepts only booleans, counts, and reasons drawn from a fixed
 * allow-list. There is no way to write free text through it. The single exception is the foreground
 * package and app label, which Orbit's existing Diagnostics screen already shows, and which are
 * capped at {@link #MAX_LABEL} characters. No prompt, response, transcript, screen text,
 * clipboard, attachment, memory, routine, or credential can reach this file.
 */
public final class OverlayLaunchTrace {

    // ---- Lifecycle milestones -------------------------------------------------------------

    public static final String STAGE_NEW_SESSION = "sessionService.onNewSession";
    public static final String STAGE_SESSION_CONSTRUCTED = "sessionService.sessionConstructed";
    public static final String STAGE_CREATE_START = "session.onCreate.start";
    public static final String STAGE_CREATE_COMPLETE = "session.onCreate.complete";
    public static final String STAGE_CONTENT_START = "session.contentView.start";
    public static final String STAGE_CONTENT_COMPLETE = "session.contentView.complete";
    public static final String STAGE_PREPARE_START = "session.prepareShow.start";
    public static final String STAGE_PREPARE_RESUME = "session.prepareShow.internalResume";
    public static final String STAGE_RESET_START = "session.prepareShow.reset.start";
    public static final String STAGE_RESET_COMPLETE = "session.prepareShow.reset.complete";
    public static final String STAGE_CONTEXT_START = "session.prepareShow.context.start";
    public static final String STAGE_CONTEXT_COMPLETE = "session.prepareShow.context.complete";
    public static final String STAGE_HISTORY_START = "session.prepareShow.history.start";
    public static final String STAGE_HISTORY_COMPLETE = "session.prepareShow.history.complete";
    public static final String STAGE_SHEET_START = "session.prepareShow.sheet.start";
    public static final String STAGE_SHEET_COMPLETE = "session.prepareShow.sheet.complete";
    public static final String STAGE_HIDDEN_READY = "session.prepareShow.hiddenState.complete";
    public static final String STAGE_INSETS_REQUESTED = "session.prepareShow.insets.requested";
    public static final String STAGE_PREPARE_COMPLETE = "session.prepareShow.complete";
    public static final String STAGE_SHOW = "session.onShow";
    public static final String STAGE_ROOT_ATTACHED = "session.root.attached";
    public static final String STAGE_FIRST_FRAME = "session.firstFrame.visible";
    public static final String STAGE_STABLE_FRAME = "session.firstFrame.stable";
    public static final String STAGE_DISMISS = "session.dismiss.explicit";
    public static final String STAGE_TRANSITION = "session.transition.internal";
    public static final String STAGE_HIDE = "session.onHide";
    public static final String STAGE_DESTROY = "session.onDestroy";

    /**
     * Android asked Orbit to close system dialogs so soon after a fresh external show that the
     * request was treated as the tail of the invocation and ignored. Nothing changes, but if a
     * launch later goes wrong this says the system was already trying to close it.
     */
    public static final String STAGE_CLOSE_DIALOGS_IGNORED = "session.closeSystemDialogs.ignored";

    /**
     * Dismissal work armed by an earlier invocation finished after a newer one had already taken
     * ownership of the shared session, and was refused. Without this the older invocation's exit
     * animation would have hidden the overlay the user just opened.
     */
    public static final String STAGE_DISMISS_STALE = "session.dismiss.stale";

    // ---- Why the overlay went away ---------------------------------------------------------

    /** Orbit closed itself at the user's request. */
    public static final String REASON_CLOSE_BUTTON = "close_button";
    public static final String REASON_SCRIM = "scrim_tap";
    public static final String REASON_SWIPE_DOWN = "swipe_down";
    public static final String REASON_BACK = "back";
    public static final String REASON_SYSTEM_DIALOGS = "close_system_dialogs";
    public static final String REASON_SOURCE_LINK = "source_link";
    public static final String REASON_REPLY_COMPOSER = "reply_composer";
    public static final String REASON_PERMISSION_SETUP = "permission_setup";

    /** Orbit handed off to another Orbit surface and expects to come back or continue there. */
    public static final String REASON_SCREEN_SELECTION = "screen_selection";
    public static final String REASON_ATTACHMENT_PICKER = "attachment_picker";
    public static final String REASON_FULL_CHAT = "full_chat";

    public static final String REASON_UNSPECIFIED = "unspecified";

    private static final Set<String> DISMISS_REASONS = new HashSet<>(Arrays.asList(
            REASON_CLOSE_BUTTON, REASON_SCRIM, REASON_SWIPE_DOWN, REASON_BACK,
            REASON_SYSTEM_DIALOGS, REASON_SOURCE_LINK, REASON_REPLY_COMPOSER,
            REASON_PERMISSION_SETUP, REASON_UNSPECIFIED));

    private static final Set<String> TRANSITION_REASONS = new HashSet<>(Arrays.asList(
            REASON_SCREEN_SELECTION, REASON_ATTACHMENT_PICKER, REASON_FULL_CHAT));

    // ---- What we concluded about an attempt -------------------------------------------------

    /** The overlay was drawn and is still open, or the process ended while it was open. */
    public static final String COMPLETE_VISIBLE = "COMPLETE_VISIBLE";
    /** The overlay was drawn and Orbit itself closed it. */
    public static final String EXPECTED_DISMISS = "EXPECTED_DISMISS";
    /** Orbit handed off to Screen Selection, the attachment picker, or full chat. */
    public static final String INTERNAL_TRANSITION = "INTERNAL_TRANSITION";
    /** The overlay was drawn, then Android hid it without Orbit asking. */
    public static final String SYSTEM_HIDE = "SYSTEM_HIDE";
    /** Preparation finished or was under way, then Android hid it before any frame appeared. */
    public static final String HIDDEN_BEFORE_VISIBLE = "HIDDEN_BEFORE_VISIBLE";
    /** Preparation began, no frame ever appeared, and no ending of any kind was recorded. */
    public static final String INCOMPLETE = "INCOMPLETE";
    /** A session was constructed but never asked to prepare or show. */
    public static final String NEVER_SHOWN = "NEVER_SHOWN";
    /** Something threw. The exception itself is recorded with the attempt. */
    public static final String EXCEPTION = "EXCEPTION";
    /** This attempt belongs to the running process and has not finished yet. */
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String UNKNOWN = "UNKNOWN";

    // ---- Bounds -----------------------------------------------------------------------------

    static final int MAX_ATTEMPTS = 10;
    static final int MAX_EVENTS_PER_ATTEMPT = 64;
    static final int MAX_STACK_FRAMES = 12;
    static final int MAX_CAUSES = 2;
    static final int MAX_CAUSE_FRAMES = 4;
    static final int MAX_EXCEPTION_MESSAGE = 160;
    static final int MAX_DETAIL = 220;
    static final int MAX_LABEL = 64;
    static final int MAX_FILE_BYTES = 128 * 1024;

    private static final String FILE_NAME = "orbit-overlay-launch.log";

    /**
     * Identifies this process for the life of the class. A PID alone is not enough: Android reuses
     * them, and the question we most need to answer is whether an unfinished attempt belongs to a
     * process that is no longer here.
     */
    private static final String PROCESS_TOKEN =
            android.os.Process.myPid() + "." + Long.toHexString(
                    (System.nanoTime() ^ android.os.Process.myTid()) & 0xffffffL);

    private static FileOutputStream out;
    private static File file;
    private static String currentAttemptId;
    private static long currentAttemptStartElapsedMs;
    private static int currentAttemptEvents;
    private static int attemptCounter;
    /** True once the current attempt has been asked to prepare, so a later show starts a new one. */
    private static boolean currentAttemptPrepared;
    /** Stored attempts, counted once per process so pruning does not read the file every launch. */
    private static int knownAttempts = -1;

    private OverlayLaunchTrace() {}

    // ---- Recording --------------------------------------------------------------------------

    /**
     * Opens a new launch attempt. Called from the earliest Orbit-owned point of a Side-button
     * invocation, and again when Android reuses an existing session for a genuinely new external
     * invocation.
     */
    public static synchronized void begin(Context context, String trigger) {
        try {
            open(context);
            prune();
            attemptCounter++;
            currentAttemptId = PROCESS_TOKEN + "-" + attemptCounter;
            currentAttemptStartElapsedMs = SystemClock.elapsedRealtime();
            currentAttemptEvents = 0;
            currentAttemptPrepared = false;
            write("A\t" + currentAttemptId
                    + "\t" + System.currentTimeMillis()
                    + "\t" + PROCESS_TOKEN
                    + "\t" + escape(versionOf(context))
                    + "\t" + escape(clip(trigger, MAX_DETAIL)));
            knownAttempts = Math.max(0, knownAttempts) + 1;
        } catch (Throwable ignored) {
            // Diagnostics must never be the reason an overlay fails to open.
        }
    }

    /**
     * Opens an attempt only if the running one has already been prepared, or if there is none.
     *
     * <p>Android constructs a {@code VoiceInteractionSession} once and then reuses it across
     * invocations, so {@code onNewSession} is not per-invocation and {@code onPrepareShow} is. This
     * folds the first invocation's construction and preparation into one attempt while still giving
     * every later external invocation — including a rapid second press — its own.
     */
    public static synchronized void beginIfInvocationIsNew(Context context, String trigger) {
        if (currentAttemptId == null || currentAttemptPrepared) {
            begin(context, trigger);
        }
    }

    /** Records one lifecycle milestone. */
    public static synchronized void event(String stage) {
        event(stage, null);
    }

    /** Records one lifecycle milestone together with the state Orbit was in at that moment. */
    public static synchronized void event(String stage, State state) {
        try {
            if (currentAttemptId == null || out == null) return;
            if (STAGE_PREPARE_START.equals(stage) || STAGE_PREPARE_RESUME.equals(stage)) {
                currentAttemptPrepared = true;
            }
            if (currentAttemptEvents >= MAX_EVENTS_PER_ATTEMPT) return;
            currentAttemptEvents++;
            String detail = state == null ? "" : state.build();
            write("E\t" + currentAttemptId
                    + "\t" + offsetMs()
                    + "\t" + escape(clip(stage, MAX_DETAIL))
                    + "\t" + escape(clip(detail, MAX_DETAIL)));
        } catch (Throwable ignored) {}
    }

    /**
     * Records a bounded description of a throwable against the stage that produced it.
     *
     * <p>This never handles the throwable. Every call site rethrows immediately afterwards, so
     * Android's own uncaught-exception behaviour is unchanged and no crash becomes a silent
     * failure.
     */
    public static synchronized void exception(String stage, Throwable error) {
        try {
            if (currentAttemptId == null || out == null || error == null) return;
            currentAttemptEvents++;
            write("X\t" + currentAttemptId
                    + "\t" + offsetMs()
                    + "\t" + escape(clip(stage, MAX_DETAIL))
                    + "\t" + escape(describe(error)));
        } catch (Throwable ignored) {}
    }

    /**
     * Records the throwable, then hands it straight back so the caller can rethrow it unchanged.
     *
     * <p>Written as {@code throw OverlayLaunchTrace.rethrow(stage, t);} so the compiler still sees
     * the method as ending there. Unchecked throwables are rethrown exactly as they arrived.
     */
    public static RuntimeException rethrow(String stage, Throwable error) {
        exception(stage, error);
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new RuntimeException(error);
    }

    /**
     * Releases the open log handle and forgets the in-memory attempt, exactly as process death
     * would. Production never calls this; the tests use it to prove that the file alone carries the
     * evidence of an unfinished launch.
     */
    static synchronized void detach() {
        closeQuietly();
        file = null;
        currentAttemptId = null;
        currentAttemptEvents = 0;
        currentAttemptPrepared = false;
        knownAttempts = -1;
    }

    /** The identifier of this process, as it appears in stored attempts. */
    public static String processToken() { return PROCESS_TOKEN; }

    // ---- State ------------------------------------------------------------------------------

    /**
     * A closed set of state fields. Booleans and counts cannot carry user content, and
     * {@link #reason(String)} silently rejects anything outside Orbit's fixed reason list, so no
     * private text can enter the trace through this class.
     */
    public static final class State {
        private final StringBuilder text = new StringBuilder();

        public static State of() { return new State(); }

        public State flag(String name, boolean value) {
            return append(name, Boolean.toString(value));
        }

        public State count(String name, int value) {
            return append(name, Integer.toString(value));
        }

        /** One of the {@code REASON_*} constants. Anything else is stored as unspecified. */
        public State reason(String value) {
            String safe = value != null
                    && (DISMISS_REASONS.contains(value) || TRANSITION_REASONS.contains(value))
                    ? value : REASON_UNSPECIFIED;
            return append("reason", safe);
        }

        /**
         * The app Orbit was invoked over. Orbit's existing Diagnostics screen already reports both
         * of these, so they are not new exposure; they are capped here all the same.
         */
        public State foreground(String packageName, String label) {
            append("fg", clip(sanitize(packageName), MAX_LABEL));
            return append("fgLabel", clip(sanitize(label), MAX_LABEL));
        }

        private State append(String name, String value) {
            if (text.length() > 0) text.append(' ');
            // Space separates fields, so it cannot appear inside one. Only an app label can
            // contain one; booleans, counts and reasons never do.
            String safe = value.isEmpty() ? "-" : value.replace(' ', '_');
            text.append(sanitize(name).replace(' ', '_')).append('=').append(safe);
            return this;
        }

        String build() { return text.toString(); }
    }

    // ---- Reading ----------------------------------------------------------------------------

    /** One stored launch attempt, with everything the report needs to classify it. */
    public static final class Attempt {
        public final String id;
        public final long startedAtMs;
        public final String processToken;
        public final String version;
        public final String trigger;
        public final List<String> lines = new ArrayList<>();
        public final List<String> stages = new ArrayList<>();
        public String exceptionStage = "";
        public String exceptionText = "";
        public String endingReason = "";
        public String foreground = "";
        public boolean truncated;
        /**
         * Set once this attempt has been hidden. A dismissal recorded after the overlay was
         * already gone cannot be the reason it went, so it must not become the ending reason.
         */
        boolean endingSettled;

        Attempt(String id, long startedAtMs, String processToken, String version, String trigger) {
            this.id = id;
            this.startedAtMs = startedAtMs;
            this.processToken = processToken;
            this.version = version;
            this.trigger = trigger;
        }

        public boolean reached(String stage) { return stages.contains(stage); }
        public boolean hasException() { return !exceptionText.isEmpty(); }
        public boolean visible() { return reached(STAGE_FIRST_FRAME); }
        public boolean internalResume() { return reached(STAGE_PREPARE_RESUME); }
        public boolean prepared() {
            return reached(STAGE_PREPARE_START) || reached(STAGE_PREPARE_RESUME);
        }

        /** The last milestone this attempt is known to have reached. */
        public String lastStage() {
            return stages.isEmpty() ? "" : stages.get(stages.size() - 1);
        }

        boolean fromLiveProcess() { return PROCESS_TOKEN.equals(processToken); }
    }

    /** Every stored attempt, oldest first. */
    public static synchronized List<Attempt> attempts(Context context) {
        try {
            open(context);
            return parse(readAll());
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Classifies one attempt. {@code superseded} means a later attempt exists, which is the only
     * way to know that an attempt with no recorded ending is genuinely over.
     */
    public static String status(Attempt attempt, boolean superseded) {
        if (attempt == null) return UNKNOWN;
        if (attempt.hasException()) return EXCEPTION;
        boolean over = superseded || !attempt.fromLiveProcess();
        boolean hidden = attempt.reached(STAGE_HIDE) || attempt.reached(STAGE_DESTROY);
        boolean transition = TRANSITION_REASONS.contains(attempt.endingReason);
        boolean dismissed = DISMISS_REASONS.contains(attempt.endingReason);

        if (attempt.visible()) {
            if (hidden) {
                if (transition) return INTERNAL_TRANSITION;
                if (dismissed) return EXPECTED_DISMISS;
                return SYSTEM_HIDE;
            }
            return COMPLETE_VISIBLE;
        }
        if (hidden) {
            if (transition) return INTERNAL_TRANSITION;
            if (dismissed) return EXPECTED_DISMISS;
            return HIDDEN_BEFORE_VISIBLE;
        }
        if (!over) return IN_PROGRESS;
        if (attempt.prepared()) return INCOMPLETE;
        return NEVER_SHOWN;
    }

    /** A short sentence explaining a status, so the report reads without the source. */
    public static String explain(String status) {
        if (COMPLETE_VISIBLE.equals(status)) {
            return "the overlay was drawn and no ending was recorded";
        }
        if (EXPECTED_DISMISS.equals(status)) {
            return "Orbit closed the overlay itself";
        }
        if (INTERNAL_TRANSITION.equals(status)) {
            return "Orbit handed off to another of its own screens";
        }
        if (SYSTEM_HIDE.equals(status)) {
            return "the overlay was drawn, then hidden without Orbit asking";
        }
        if (HIDDEN_BEFORE_VISIBLE.equals(status)) {
            return "the overlay was hidden before any frame was drawn, and Orbit did not ask";
        }
        if (INCOMPLETE.equals(status)) {
            return "preparation began, nothing was drawn, and no ending arrived";
        }
        if (NEVER_SHOWN.equals(status)) {
            return "a session was built but never asked to appear";
        }
        if (EXCEPTION.equals(status)) {
            return "something threw during the launch";
        }
        if (IN_PROGRESS.equals(status)) {
            return "still running in this process";
        }
        return "not enough was recorded to say";
    }

    /** One line for the ordinary diagnostic report. */
    public static String summary(Context context) {
        List<Attempt> all = attempts(context);
        if (all.isEmpty()) return "No overlay launch recorded yet";
        Attempt last = all.get(all.size() - 1);
        return status(last, false) + " at "
                + DateFormat.getDateTimeInstance().format(new Date(last.startedAtMs));
    }

    /** The pasteable overlay launch report. */
    public static String report(Context context) {
        List<Attempt> all = attempts(context);
        StringBuilder b = new StringBuilder("Orbit overlay launch diagnostics\n");
        b.append("Version: ").append(versionOf(context)).append('\n');
        b.append("This process: ").append(PROCESS_TOKEN).append('\n');
        b.append("Stored attempts: ").append(all.size()).append('\n');
        if (all.isEmpty()) {
            b.append("\nNo Side-button overlay launch has been recorded yet. Open Orbit with the "
                    + "Side-button gesture, then copy this again.\n");
            return b.toString();
        }
        for (int i = 0; i < all.size(); i++) {
            Attempt attempt = all.get(i);
            String status = status(attempt, i < all.size() - 1);
            b.append("\nAttempt ").append(i + 1).append(" of ").append(all.size()).append('\n');
            b.append("  Started: ")
                    .append(DateFormat.getDateTimeInstance().format(new Date(attempt.startedAtMs)))
                    .append('\n');
            b.append("  Status: ").append(status).append(" — ").append(explain(status)).append('\n');
            b.append("  Process: ").append(attempt.processToken)
                    .append(attempt.fromLiveProcess() ? " (this process)" : " (earlier process)")
                    .append('\n');
            b.append("  App version: ").append(attempt.version).append('\n');
            b.append("  Trigger: ").append(attempt.trigger).append('\n');
            b.append("  Internal resume: ").append(attempt.internalResume()).append('\n');
            b.append("  Foreground: ")
                    .append(attempt.foreground.isEmpty() ? "not resolved" : attempt.foreground)
                    .append('\n');
            b.append("  Last stage: ")
                    .append(attempt.lastStage().isEmpty() ? "none" : attempt.lastStage())
                    .append('\n');
            if (!attempt.endingReason.isEmpty()) {
                b.append("  Ending reason: ").append(attempt.endingReason).append('\n');
            }
            for (String line : attempt.lines) b.append("  ").append(line).append('\n');
            if (attempt.truncated) {
                b.append("  … further events for this attempt were dropped at the ")
                        .append(MAX_EVENTS_PER_ATTEMPT).append("-event limit\n");
            }
            if (attempt.hasException()) {
                b.append("  EXCEPTION at ").append(attempt.exceptionStage).append('\n');
                for (String line : attempt.exceptionText.split("\n")) {
                    b.append("    ").append(line).append('\n');
                }
            }
        }
        return b.toString();
    }

    // ---- Storage ----------------------------------------------------------------------------

    private static void open(Context context) throws IOException {
        if (out != null && file != null) return;
        if (context == null) throw new IOException("no context");
        File target = new File(context.getFilesDir(), FILE_NAME);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        out = new FileOutputStream(target, true);
        file = target;
    }

    /**
     * Writes one record.
     *
     * <p>Deliberately unbuffered and never fsynced. One small {@code write} per milestone reaches
     * the page cache immediately, which survives process death, force stop, and a crash — the cases
     * this trace exists for — without paying for a flush to storage on the launch path.
     */
    private static void write(String line) throws IOException {
        if (out == null) return;
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Keeps the newest attempts only, so the log can never grow without bound.
     *
     * <p>The stored attempts are counted once per process. After that this is a length check on an
     * already-open file, so the ordinary launch path does not re-read the log every time.
     */
    private static void prune() {
        try {
            if (file == null || !file.exists()) { knownAttempts = 0; return; }
            if (knownAttempts >= 0 && knownAttempts < MAX_ATTEMPTS
                    && file.length() < MAX_FILE_BYTES) {
                return;
            }
            List<String> lines = readAll();
            List<String> ids = new ArrayList<>();
            for (String line : lines) {
                if ("A".equals(fieldAt(line, 0))) ids.add(fieldAt(line, 1));
            }
            boolean tooMany = ids.size() >= MAX_ATTEMPTS;
            boolean tooBig = file.length() >= MAX_FILE_BYTES;
            if (!tooMany && !tooBig) { knownAttempts = ids.size(); return; }
            int drop = Math.max(tooBig ? 1 : 0, ids.size() - (MAX_ATTEMPTS - 1));
            Set<String> keep = new HashSet<>(ids.subList(Math.min(drop, ids.size()), ids.size()));
            StringBuilder rebuilt = new StringBuilder();
            for (String line : lines) {
                String id = fieldAt(line, 1);
                if (keep.contains(id)) rebuilt.append(line).append('\n');
            }
            closeQuietly();
            Files.write(file.toPath(), rebuilt.toString().getBytes(StandardCharsets.UTF_8));
            out = new FileOutputStream(file, true);
            knownAttempts = keep.size();
        } catch (Throwable ignored) {}
    }

    private static List<String> readAll() {
        try {
            if (file == null || !file.exists()) return Collections.emptyList();
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : raw.split("\n")) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
            return lines;
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
    }

    private static List<Attempt> parse(List<String> lines) {
        List<Attempt> attempts = new ArrayList<>();
        Attempt current = null;
        for (String line : lines) {
            String kind = fieldAt(line, 0);
            String id = fieldAt(line, 1);
            if ("A".equals(kind)) {
                long started = parseLong(fieldAt(line, 2));
                current = new Attempt(id, started, unescape(fieldAt(line, 3)),
                        unescape(fieldAt(line, 4)), unescape(fieldAt(line, 5)));
                attempts.add(current);
                continue;
            }
            if (current == null || !current.id.equals(id)) continue;
            long offset = Math.max(0L, parseLong(fieldAt(line, 2)));
            String stage = unescape(fieldAt(line, 3));
            if ("X".equals(kind)) {
                current.exceptionStage = stage;
                current.exceptionText = unescape(fieldAt(line, 4));
                current.lines.add(String.format(Locale.US, "[%6d ms] EXCEPTION %s", offset, stage));
                continue;
            }
            if (!"E".equals(kind)) continue;
            String detail = unescape(fieldAt(line, 4));
            if (current.stages.size() >= MAX_EVENTS_PER_ATTEMPT) {
                current.truncated = true;
                continue;
            }
            current.stages.add(stage);
            current.lines.add(String.format(Locale.US, "[%6d ms] %s%s",
                    offset, stage, detail.isEmpty() ? "" : " " + detail));
            // Ordering decides the cause. Orbit's own dismissal explains a hide only when it came
            // first; the shared session keeps receiving close-system-dialogs callbacks long after
            // an overlay is gone, and one of those arriving later must not be allowed to relabel
            // an unexplained hide as something Orbit asked for.
            if (STAGE_HIDE.equals(stage) || STAGE_DESTROY.equals(stage)) {
                current.endingSettled = true;
            }
            if (!current.endingSettled
                    && (STAGE_DISMISS.equals(stage) || STAGE_TRANSITION.equals(stage))) {
                String reason = valueOf(detail, "reason");
                if (!reason.isEmpty()) current.endingReason = reason;
            }
            if (STAGE_CONTEXT_COMPLETE.equals(stage)) {
                String pkg = valueOf(detail, "fg");
                String label = valueOf(detail, "fgLabel");
                if (!pkg.isEmpty() && !"-".equals(pkg)) {
                    current.foreground = "-".equals(label) || label.isEmpty()
                            ? pkg : pkg + " (" + label + ")";
                }
            }
        }
        return attempts;
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private static long offsetMs() {
        return Math.max(0L, SystemClock.elapsedRealtime() - currentAttemptStartElapsedMs);
    }

    private static String describe(Throwable error) {
        StringBuilder b = new StringBuilder();
        b.append(error.getClass().getName());
        String message = clip(sanitize(error.getMessage()), MAX_EXCEPTION_MESSAGE);
        if (!message.isEmpty()) b.append(": ").append(message);
        appendFrames(b, error, MAX_STACK_FRAMES);
        Throwable cause = error.getCause();
        for (int i = 0; i < MAX_CAUSES && cause != null && cause != error; i++) {
            b.append("\nCaused by: ").append(cause.getClass().getName());
            String causeMessage = clip(sanitize(cause.getMessage()), MAX_EXCEPTION_MESSAGE);
            if (!causeMessage.isEmpty()) b.append(": ").append(causeMessage);
            appendFrames(b, cause, MAX_CAUSE_FRAMES);
            Throwable next = cause.getCause();
            if (next == cause) break;
            cause = next;
        }
        return b.toString();
    }

    private static void appendFrames(StringBuilder b, Throwable error, int limit) {
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null) return;
        int count = Math.min(limit, frames.length);
        for (int i = 0; i < count; i++) {
            b.append("\n  at ").append(frames[i].toString());
        }
        if (frames.length > count) {
            b.append("\n  … ").append(frames.length - count).append(" more frames");
        }
    }

    private static String versionOf(Context context) {
        if (context == null) return "unknown";
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String valueOf(String detail, String key) {
        if (detail == null || detail.isEmpty()) return "";
        for (String part : detail.split(" ")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) return part.substring(eq + 1);
        }
        return "";
    }

    private static String fieldAt(String line, int index) {
        if (line == null) return "";
        String[] parts = line.split("\t", -1);
        return index < parts.length ? parts[index] : "";
    }

    private static long parseLong(String value) {
        try { return Long.parseLong(value.trim()); } catch (Exception ignored) { return 0L; }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String value) {
        if (value == null) return "";
        StringBuilder b = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) { b.append(c); continue; }
            char next = value.charAt(++i);
            if (next == 't') b.append('\t');
            else if (next == 'n') b.append('\n');
            else b.append(next);
        }
        return b.toString();
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static void closeQuietly() {
        if (out != null) {
            try { out.close(); } catch (Exception ignored) {}
            out = null;
        }
    }
}

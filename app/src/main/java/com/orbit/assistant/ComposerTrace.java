package com.orbit.assistant;

import android.content.Context;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.EditText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A short in-memory log of composer and input-method state transitions, for diagnosing typing
 * failures on real hardware.
 *
 * <p>Three releases of automated tests passed while the phone stayed broken, because every test
 * could only assert what the app believed — {@code hasFocus()}, a typing flag, a visible keyboard —
 * and all of those were true while text entry was dead. This records what Android actually did
 * instead, most importantly whether it ever asked the editor for a new input connection.
 *
 * <p>Only state transitions are recorded. No message text, no recognised speech, no notification
 * or memory content ever enters this buffer.
 */
public final class ComposerTrace {
    private static final int MAX_EVENTS = 240;
    private static final Deque<String> EVENTS = new ArrayDeque<>();
    private static long startedAtMs;

    private ComposerTrace() {}

    /** Clears the buffer at the start of a fresh invocation so one reproduction is easy to read. */
    public static synchronized void begin(String surface) {
        EVENTS.clear();
        startedAtMs = SystemClock.elapsedRealtime();
        add("BEGIN " + safe(surface));
    }

    /** Records one event. Keep the label short and free of user content. */
    public static synchronized void event(String label) {
        add(safe(label));
    }

    /**
     * Records an editor's observable state. This is the line to read when typing has gone dead:
     * it shows focus, enabled/focusable state, window attachment, and whether the window is
     * currently allowed to take the input method.
     */
    public static void snapshot(String label, EditText editor, Context context,
                                android.view.Window window, boolean orbitOwnsIme, boolean typing) {
        StringBuilder b = new StringBuilder(safe(label));
        if (editor == null) {
            b.append(" editor=null");
        } else {
            b.append(" focus=").append(editor.hasFocus())
                    .append(" enabled=").append(editor.isEnabled())
                    .append(" focusableTouch=").append(editor.isFocusableInTouchMode())
                    .append(" attached=").append(editor.getWindowToken() != null)
                    .append(" shown=").append(editor.isShown())
                    .append(" len=").append(editor.getText() == null ? -1 : editor.getText().length());
        }
        b.append(" ownsIme=").append(orbitOwnsIme).append(" typing=").append(typing);
        if (window != null) {
            try {
                int flags = window.getAttributes().flags;
                b.append(" altFocusableIme=")
                        .append((flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0)
                        .append(" notFocusable=")
                        .append((flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
                        .append(" windowFocus=").append(window.getDecorView() != null
                                && window.getDecorView().hasWindowFocus());
            } catch (Exception ignored) {
                b.append(" window=unreadable");
            }
        }
        event(b.toString());
    }

    /** The recorded events, oldest first. */
    public static synchronized List<String> events() {
        return new ArrayList<>(EVENTS);
    }

    /** A pasteable report, or a short note when nothing has been recorded yet. */
    public static synchronized String report() {
        if (EVENTS.isEmpty()) {
            return "Orbit typing diagnostics\n\nNo composer activity recorded yet. "
                    + "Reproduce the problem first, then copy this again.";
        }
        StringBuilder b = new StringBuilder("Orbit typing diagnostics\n");
        b.append("Events: ").append(EVENTS.size()).append("\n\n");
        for (String event : EVENTS) b.append(event).append('\n');
        return b.toString();
    }

    private static void add(String label) {
        if (startedAtMs == 0L) startedAtMs = SystemClock.elapsedRealtime();
        long offset = SystemClock.elapsedRealtime() - startedAtMs;
        while (EVENTS.size() >= MAX_EVENTS) EVENTS.pollFirst();
        EVENTS.addLast(String.format(Locale.US, "[%6d ms] %s", offset, label));
    }

    private static String safe(String value) {
        if (value == null) return "";
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}

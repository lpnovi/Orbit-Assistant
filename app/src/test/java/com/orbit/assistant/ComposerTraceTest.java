package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The composer diagnostic trace, and the tracing editor that feeds it.
 *
 * <p>These tests verify the instrument, not the bug. The point of the instrument is to answer a
 * question no assertion on this machine can: whether Android ever asks the editor for a new input
 * connection during a turn on real hardware.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ComposerTraceTest {
    private Activity activity;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ComposerTrace.begin("test");
    }

    // ---- the tracing editor behaves as an ordinary EditText ----

    @Test public void theTracingEditorIsAnEditText() {
        TracingEditText editor = new TracingEditText(activity);
        assertTrue("substituting it must not change what the composer is",
                editor instanceof EditText);
    }

    @Test public void itReturnsWhateverTheSuperclassBuilt() {
        TracingEditText editor = new TracingEditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        LinearLayout root = new LinearLayout(activity);
        root.addView(editor);
        activity.setContentView(root);
        editor.requestFocus();

        EditorInfo info = new EditorInfo();
        InputConnection connection = editor.onCreateInputConnection(info);
        assertNotNull("tracing must not suppress the real input connection", connection);
    }

    @Test public void itCountsEveryConnectionRequest() {
        TracingEditText editor = new TracingEditText(activity);
        assertEquals(0, editor.connectionsCreated());

        editor.onCreateInputConnection(new EditorInfo());
        editor.onCreateInputConnection(new EditorInfo());
        assertEquals("this count is the whole reason the subclass exists",
                2, editor.connectionsCreated());
    }

    @Test public void connectionRequestsReachTheTrace() {
        TracingEditText editor = new TracingEditText(activity);
        editor.onCreateInputConnection(new EditorInfo());

        assertTrue(anyEventContains("onCreateInputConnection"));
    }

    @Test public void ordinaryTextEditingStillWorks() {
        TracingEditText editor = new TracingEditText(activity);
        editor.setText("hello");
        editor.getText().append(" there");
        assertEquals("hello there", editor.getText().toString());
    }

    // ---- the trace itself ----

    @Test public void eventsAreRecordedInOrderWithTimestamps() {
        ComposerTrace.event("first");
        ComposerTrace.event("second");

        List<String> events = ComposerTrace.events();
        int first = indexOfEventContaining(events, "first");
        int second = indexOfEventContaining(events, "second");
        assertTrue(first >= 0 && second > first);
        assertTrue("every line is timestamped", events.get(first).startsWith("["));
    }

    @Test public void beginningANewInvocationClearsTheBuffer() {
        ComposerTrace.event("stale event");
        assertTrue(anyEventContains("stale event"));

        ComposerTrace.begin("side-button overlay");
        assertFalse("one reproduction should be readable on its own",
                anyEventContains("stale event"));
        assertTrue(anyEventContains("BEGIN"));
    }

    @Test public void theBufferIsBounded() {
        for (int i = 0; i < 600; i++) ComposerTrace.event("event " + i);
        assertTrue("the trace must not grow without limit",
                ComposerTrace.events().size() <= 240);
        assertTrue("the most recent events are the ones kept",
                anyEventContains("event 599"));
    }

    @Test public void aSnapshotRecordsTheStateThatMatters() {
        TracingEditText editor = new TracingEditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        LinearLayout root = new LinearLayout(activity);
        root.addView(editor);
        activity.setContentView(root);
        editor.requestFocus();

        ComposerTrace.snapshot("submit.after-clear", editor, activity,
                activity.getWindow(), true, true);

        String line = eventContaining("submit.after-clear");
        assertNotNull(line);
        for (String field : new String[]{"focus=", "enabled=", "focusableTouch=", "attached=",
                "ownsIme=", "typing=", "altFocusableIme=", "notFocusable="}) {
            assertTrue("snapshot is missing " + field, line.contains(field));
        }
    }

    @Test public void aSnapshotSurvivesAMissingEditorOrWindow() {
        ComposerTrace.snapshot("no-editor", null, activity, null, false, false);
        assertTrue(anyEventContains("editor=null"));
    }

    @Test public void theReportIsPasteableAndSaysSoWhenEmpty() {
        ComposerTrace.begin("side-button overlay");
        ComposerTrace.event("submit.before-clear");
        String report = ComposerTrace.report();
        assertTrue(report.startsWith("Orbit typing diagnostics"));
        assertTrue(report.contains("submit.before-clear"));
    }

    // ---- privacy ----

    @Test public void onlyTheLabelsGivenAreRecorded() {
        // The trace records transitions. Nothing here reads message text, and a snapshot reports
        // the composer's length rather than its contents.
        TracingEditText editor = new TracingEditText(activity);
        editor.setText("my private message about Niki");
        ComposerTrace.snapshot("submit.before-clear", editor, activity, null, true, true);

        String line = eventContaining("submit.before-clear");
        assertNotNull(line);
        assertFalse("composer contents must never enter the trace",
                line.contains("private") || line.contains("Niki"));
        assertTrue("only the length is reported", line.contains("len=29"));
    }

    @Test public void longLabelsAreTruncatedRatherThanUnbounded() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append('x');
        ComposerTrace.event(huge.toString());

        String line = ComposerTrace.events().get(ComposerTrace.events().size() - 1);
        assertTrue("a runaway label must not bloat the buffer", line.length() < 260);
    }

    // ---- helpers ----

    private static boolean anyEventContains(String needle) {
        return eventContaining(needle) != null;
    }

    private static String eventContaining(String needle) {
        for (String event : ComposerTrace.events()) {
            if (event.contains(needle)) return event;
        }
        return null;
    }

    private static int indexOfEventContaining(List<String> events, String needle) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).contains(needle)) return i;
        }
        return -1;
    }
}

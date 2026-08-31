package com.orbit.assistant.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

import com.orbit.assistant.local.ComponentDownloadWorker.WorkState;
import com.orbit.assistant.local.ComponentModelStore.State;

/**
 * Asking WorkManager what is running, and what each possible answer is allowed to mean.
 *
 * <p>The v0.7.7.5 query returned a plain boolean: true if it found live work inside 750 ms, false
 * for everything else — including a timeout, a thrown query, and a WorkManager that was not ready
 * yet. That false then became {@code PAUSED}, which claims a person made a decision. A device
 * recording caught it exactly: Orbit reported the download as Paused while its {@code .part} file
 * was still growing.
 *
 * <p>There are now four answers rather than two, and the one that matters most is
 * {@link WorkState#UNKNOWN} — the answer that says nothing at all, and is therefore not allowed to
 * decide anything.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentWorkQueryTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_local_component", Context.MODE_PRIVATE)
                .edit().clear().commit();
        File[] files = ComponentModelStore.modelDir(context).listFiles();
        if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
            file.delete();
        // Initialised explicitly rather than left to chance. Robolectric's WorkManager singleton
        // survives between test classes in a shared sandbox, so a test that depended on it being
        // absent would pass or fail on class ordering — and the uncertainty path deserves a
        // steadier assertion than that. It gets one below, through classify.
        TestWorkManager.resetQueue(context);
    }

    /** An empty queue is a proven "nothing live", which is a different thing from not knowing. */
    @Test public void anEmptyQueueIsProvenNothingRatherThanUnknown() {
        assertEquals(WorkState.NONE, ComponentDownloadWorker.snapshot(context));
    }

    /** A query that produced no answer at all cannot be turned into a claim about anything. */
    @Test public void anAbsentAnswerIsUnknown() {
        assertEquals(WorkState.UNKNOWN, ComponentDownloadWorker.classify(null, true));
    }

    /**
     * The device recording, as a state machine question.
     *
     * <p>With bytes on disk, a DOWNLOADING mark, and no live work found, the honest answer is that
     * the download was interrupted — not that the user paused it. Both offer Resume and both keep
     * every byte; only one of them tells the user something untrue.
     */
    @Test public void aDownloadThatStoppedByItselfIsNotCalledAPause() throws Exception {
        write(ComponentModelStore.partFile(context), 682_000_000L);
        ComponentModelStore.setState(context, State.DOWNLOADING, "");

        State resolved = ComponentModelStore.state(context);

        assertNotEquals("nobody paused this", State.PAUSED, resolved);
        assertEquals(State.INTERRUPTED, resolved);
        assertFalse(ComponentModelStore.pauseRequested(context));
        assertEquals("and the bytes are still all there",
                682_000_000L, ComponentModelStore.downloadedBytes(context));
    }

    /** And an unanswerable query keeps the last thing that was true instead of guessing. */
    @Test public void anUnknownAnswerPreservesWhatWasLastTrue() {
        assertEquals(State.DOWNLOADING, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.UNKNOWN, false, false, false, true));
        assertEquals(State.QUEUED, ComponentModelStore.reconcile(
                State.QUEUED, WorkState.UNKNOWN, false, false, false, true));
        assertEquals("verification of a large file must not be cut short by a slow query",
                State.VALIDATING, ComponentModelStore.reconcile(
                        State.VALIDATING, WorkState.UNKNOWN, false, false, false, true));
    }

    /** Byte counts come from the file, so they advance whatever WorkManager has to say. */
    @Test public void byteCountsAreReadFromDiskAndNotFromTheWorkQuery() throws Exception {
        write(ComponentModelStore.partFile(context), 123_456_789L);
        assertEquals(123_456_789L, ComponentModelStore.downloadedBytes(context));
        write(ComponentModelStore.partFile(context), 223_456_789L);
        assertEquals("a growing file must read as a growing file",
                223_456_789L, ComponentModelStore.downloadedBytes(context));
    }

    private static void write(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }
}

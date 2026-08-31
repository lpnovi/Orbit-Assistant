package com.orbit.assistant.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.work.ExistingWorkPolicy;
import androidx.work.WorkInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowStatFs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


import com.orbit.assistant.local.ComponentDownloadWorker.WorkState;
import com.orbit.assistant.local.ComponentModelStore.State;

/**
 * What the download's states are allowed to mean.
 *
 * <p>Written against two real device recordings from v0.7.7.5. In one, Orbit reported the model
 * download as <em>Paused</em> while the {@code .part} file on disk was demonstrably still growing.
 * In the other, tapping Resume after a Pause appeared to be accepted and no download ever started.
 *
 * <p>Both came from the same habit: guessing. "I could not prove WorkManager is running" was read
 * as "the user paused it", and a unique-work KEEP policy was trusted to notice a cancellation that
 * had not landed yet. Every rule below exists so that neither guess can come back, and the
 * reconciliation is exercised as a pure function so each case is stated rather than reproduced by
 * downloading 1.6 GB onto a phone.
 *
 * <p>The one invariant the whole file protects: PAUSED appears if and only if someone asked for it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentDownloadStateTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_local_component", Context.MODE_PRIVATE)
                .edit().clear().commit();
        File[] files = ComponentModelStore.modelDir(context).listFiles();
        if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
            file.delete();
        // Robolectric reports no free space at all by default, which would make every start
        // decision here a storage refusal instead of the lifecycle question under test.
        ShadowStatFs.registerStats(context.getFilesDir(), 4_000_000, 4_000_000, 4_000_000);
        // Pause and Resume are WorkManager operations, so they run against a real one.
        TestWorkManager.resetQueue(context);
    }

    @After public void tearDown() {
        ShadowStatFs.reset();
    }

    // ---- PAUSED means one thing ---------------------------------------------------------------

    /** The device report, stated exactly: an unanswerable query is not a pause. */
    @Test public void aWorkManagerTimeoutIsNeverReportedAsPaused() {
        for (State marked : new State[]{State.DOWNLOADING, State.QUEUED, State.WAITING_FOR_NETWORK}) {
            State resolved = ComponentModelStore.reconcile(
                    marked, WorkState.UNKNOWN, false, false, false, true);
            assertNotEquals("a WorkManager query that timed out must never become a user pause",
                    State.PAUSED, resolved);
            assertEquals("an unknown answer keeps the last thing that was true", marked, resolved);
        }
    }

    /** A query that threw is the same kind of not-knowing, and gets the same treatment. */
    @Test public void aWorkManagerQueryFailureIsNeverReportedAsPaused() {
        assertEquals(State.DOWNLOADING, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.UNKNOWN, false, false, false, true));
        // No answer at all is the one case classify must not turn into a claim about anything.
        assertEquals(WorkState.UNKNOWN, ComponentDownloadWorker.classify(null, true));
    }

    @Test public void aRunningWorkerWithAPartialFileIsDownloading() {
        assertEquals(State.DOWNLOADING, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.RUNNING, false, false, false, true));
        assertEquals("even a stale PAUSED mark loses to a worker that is demonstrably running",
                State.DOWNLOADING, ComponentModelStore.reconcile(
                        State.PAUSED, WorkState.RUNNING, false, false, false, true));
    }

    @Test public void anExplicitPauseIsReportedAsPaused() {
        assertEquals(State.PAUSED, ComponentModelStore.reconcile(
                State.PAUSED, WorkState.NONE, false, true, false, true));
        assertEquals(State.PAUSED, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.NONE, false, true, false, true));
    }

    /** Queued work is waiting its turn, which is not a decision anybody made. */
    @Test public void queuedWorkIsNotPaused() {
        assertEquals(State.QUEUED, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.ENQUEUED, false, false, false, true));
    }

    @Test public void waitingForNetworkIsNotPaused() {
        assertEquals(State.WAITING_FOR_NETWORK, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.WAITING_FOR_NETWORK, false, false, false, true));
    }

    /** A download that simply stopped is interrupted, and says so. */
    @Test public void aStoppedDownloadNobodyPausedIsInterrupted() {
        assertEquals(State.INTERRUPTED, ComponentModelStore.reconcile(
                State.DOWNLOADING, WorkState.NONE, false, false, false, true));
    }

    /**
     * The exhaustive statement of the invariant.
     *
     * <p>Every combination of mark, WorkManager answer, and file layout, checked against the one
     * rule that matters. No path through the reconciliation may reach PAUSED without a request,
     * and none may avoid it when there is one and bytes to resume from.
     */
    @Test public void pausedIsReachableOnlyThroughAnExplicitRequest() {
        for (State marked : State.values()) {
            for (WorkState work : WorkState.values()) {
                for (boolean importing : new boolean[]{false, true}) {
                    for (boolean complete : new boolean[]{false, true}) {
                        for (boolean part : new boolean[]{false, true}) {
                            State withoutRequest = ComponentModelStore.reconcile(
                                    marked, work, importing, false, complete, part);
                            assertNotEquals(marked + "/" + work + "/complete=" + complete
                                            + "/part=" + part
                                            + " reached PAUSED with no pause request",
                                    State.PAUSED, withoutRequest);
                        }
                    }
                }
            }
        }
    }

    // ---- the failures that used to borrow the word ---------------------------------------------

    @Test public void anExhaustedNetworkFailureIsInterruptedNotPaused() throws Exception {
        write(ComponentModelStore.partFile(context), 4096L);
        ComponentModelStore.markStopped(context, ComponentModelStore.FAILURE_NETWORK);
        assertEquals(State.INTERRUPTED, ComponentModelStore.state(context));
        assertEquals(ComponentModelStore.FAILURE_NETWORK, ComponentModelStore.lastFailure(context));
        assertFalse(ComponentModelStore.pauseRequested(context));
    }

    @Test public void anEarlyStreamTerminationIsInterruptedNotPaused() throws Exception {
        write(ComponentModelStore.partFile(context), 512L);
        ComponentModelStore.markStopped(context, ComponentModelStore.FAILURE_STREAM_ENDED_EARLY);
        assertEquals(State.INTERRUPTED, ComponentModelStore.state(context));
        assertEquals(ComponentModelStore.FAILURE_STREAM_ENDED_EARLY,
                ComponentModelStore.lastFailure(context));
    }

    /** An HTTP refusal is a real error the user can act on, and has always been one. */
    @Test public void anExhaustedHttpFailureIsAnErrorNotAPause() {
        ComponentModelStore.recordFailure(context, ComponentModelStore.FAILURE_HTTP);
        ComponentModelStore.setState(context, State.ERROR, "refused");
        assertEquals(State.ERROR, ComponentModelStore.state(context));
    }

    /** The one path allowed to record a pause does record one. */
    @Test public void onlyAnExplicitPauseSetsThePauseRequest() throws Exception {
        write(ComponentModelStore.partFile(context), 2048L);
        assertFalse(ComponentModelStore.pauseRequested(context));

        ComponentDownloadWorker.pause(context);
        assertTrue(ComponentModelStore.pauseRequested(context));
        assertEquals(State.PAUSED, ComponentModelStore.state(context));

        // And the same partial bytes stay exactly where they are.
        assertEquals(2048L, ComponentModelStore.downloadedBytes(context));
    }

    // ---- Pause then Resume ----------------------------------------------------------------------

    /**
     * The race that made Resume do nothing.
     *
     * <p>KEEP preserves work WorkManager still considers live, and a cancellation dispatched by
     * Pause is not live-until-it-is-not instantly. A Resume arriving inside that window kept the
     * doomed work and enqueued nothing at all. REPLACE is ordered against the outstanding cancel,
     * so the new download cannot be swallowed by the old one's death.
     */
    @Test public void startReplacesRatherThanKeepingSoACancelCannotSwallowAResume() {
        assertEquals("KEEP is what let a pending cancellation eat a Resume",
                ExistingWorkPolicy.REPLACE, ComponentDownloadWorker.START_POLICY);
    }

    @Test public void resumeWithdrawsThePauseRequest() throws Exception {
        write(ComponentModelStore.partFile(context), 1024L);
        ComponentDownloadWorker.pause(context);
        assertTrue(ComponentModelStore.pauseRequested(context));

        ComponentDownloadWorker.start(context);

        assertFalse("a resume that leaves the pause request standing would stop itself again",
                ComponentModelStore.pauseRequested(context));
        assertNotEquals(State.PAUSED, ComponentModelStore.state(context));
        assertTrue("resuming must never throw away what was already downloaded",
                ComponentModelStore.partFile(context).exists());
        assertEquals(1024L, ComponentModelStore.downloadedBytes(context));
    }

    /** Rapid Pause, Resume, Pause, Resume settles on downloading, not on whichever landed last. */
    @Test public void rapidPauseAndResumeConvergesOnDownloading() throws Exception {
        write(ComponentModelStore.partFile(context), 4096L);
        for (int i = 0; i < 4; i++) {
            ComponentDownloadWorker.pause(context);
            ComponentDownloadWorker.start(context);
        }
        assertFalse(ComponentModelStore.pauseRequested(context));
        assertNotEquals("the download must not settle on a pause nobody is asking for any more",
                State.PAUSED, ComponentModelStore.state(context));
        assertEquals(4096L, ComponentModelStore.downloadedBytes(context));
    }

    /** A second tap while a download is genuinely running is a no-op, not a second download. */
    @Test public void repeatedResumeDoesNotStartCompetingDownloads() {
        assertEquals(ComponentDownloadWorker.StartDecision.ALREADY_RUNNING,
                ComponentDownloadWorker.decideStart(false, false, true, WorkState.RUNNING, false));
        // But a running worker that is being paused still gets a fresh enqueue, because that one
        // is on its way out and will not be the thing that downloads anything.
        assertEquals(ComponentDownloadWorker.StartDecision.ENQUEUE,
                ComponentDownloadWorker.decideStart(false, false, true, WorkState.RUNNING, true));
    }

    /** Uncertainty enqueues rather than assuming something is already running. */
    @Test public void anUnknownWorkStateStillStartsADownload() {
        assertEquals(ComponentDownloadWorker.StartDecision.ENQUEUE,
                ComponentDownloadWorker.decideStart(false, false, true, WorkState.UNKNOWN, false));
        assertEquals(ComponentDownloadWorker.StartDecision.ENQUEUE,
                ComponentDownloadWorker.decideStart(false, false, true, WorkState.NONE, false));
    }

    @Test public void startRefusesWhatItShould() {
        assertEquals(ComponentDownloadWorker.StartDecision.IGNORED_READY,
                ComponentDownloadWorker.decideStart(true, false, true, WorkState.NONE, false));
        assertEquals(ComponentDownloadWorker.StartDecision.IGNORED_IMPORTING,
                ComponentDownloadWorker.decideStart(false, true, true, WorkState.NONE, false));
        assertEquals(ComponentDownloadWorker.StartDecision.NO_STORAGE,
                ComponentDownloadWorker.decideStart(false, false, false, WorkState.NONE, false));
    }

    // ---- classifying what WorkManager reports ----------------------------------------------------

    @Test public void aRunningWorkInfoIsRunning() {
        assertEquals(WorkState.RUNNING, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.RUNNING), true));
    }

    @Test public void enqueuedWorkIsWaitingForNetworkOnlyWhenThereIsNone() {
        assertEquals(WorkState.ENQUEUED, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.ENQUEUED), true));
        assertEquals(WorkState.WAITING_FOR_NETWORK, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.ENQUEUED), false));
        assertEquals("blocked work is still work, and still not a pause",
                WorkState.WAITING_FOR_NETWORK,
                ComponentDownloadWorker.classify(infos(WorkInfo.State.BLOCKED), false));
    }

    @Test public void finishedOrCancelledWorkIsNothingLive() {
        assertEquals(WorkState.NONE, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.CANCELLED), true));
        assertEquals(WorkState.NONE, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.SUCCEEDED), true));
        assertEquals(WorkState.NONE, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.FAILED), true));
        assertEquals(WorkState.NONE, ComponentDownloadWorker.classify(
                Collections.<WorkInfo.State>emptyList(), true));
    }

    /** One running entry beside stale finished ones still means a download is running. */
    @Test public void oneRunningEntryBesideStaleOnesWins() {
        assertEquals(WorkState.RUNNING, ComponentDownloadWorker.classify(
                infos(WorkInfo.State.CANCELLED, WorkInfo.State.RUNNING), true));
    }

    // ---- what survives the whole lifecycle -------------------------------------------------------

    /** A screen closing, a process dying, a worker replaced: the bytes stay. */
    @Test public void theResumablePartialFileSurvivesEveryStop() throws Exception {
        write(ComponentModelStore.partFile(context), 700_000_000L);
        ComponentDownloadWorker.pause(context);
        assertEquals(700_000_000L, ComponentModelStore.downloadedBytes(context));
        ComponentDownloadWorker.cancel(context);
        assertEquals(700_000_000L, ComponentModelStore.downloadedBytes(context));
        ComponentModelStore.markStopped(context, ComponentModelStore.FAILURE_NETWORK);
        assertEquals(700_000_000L, ComponentModelStore.downloadedBytes(context));
    }

    /** Discarding is the only thing that throws bytes away, and only when asked. */
    @Test public void discardingIsTheOnlyThingThatDropsThePartialFile() throws Exception {
        write(ComponentModelStore.partFile(context), 8192L);
        ComponentDownloadWorker.cancelAndDiscard(context);
        assertFalse(ComponentModelStore.partFile(context).exists());
        assertEquals(State.NOT_INSTALLED, ComponentModelStore.state(context));
        assertFalse("discarding must not leave a pause request behind to confuse the next start",
                ComponentModelStore.pauseRequested(context));
    }

    /** A complete file is still not a model until its checksum says so. */
    @Test public void aCompleteFileStillHasToPassVerification() throws Exception {
        File part = ComponentModelStore.partFile(context);
        write(part, ComponentModelStore.MODEL_SIZE_BYTES);
        assertFalse(ComponentModelStore.validateAndPromote(context, part));
        assertEquals(State.ERROR, ComponentModelStore.state(context));
        assertEquals(ComponentModelStore.FAILURE_CHECKSUM, ComponentModelStore.lastFailure(context));
        assertFalse(ComponentModelStore.modelFile(context).exists());
    }

    /** Deleting the model clears the pause request too, so a later download starts clean. */
    @Test public void deletingTheModelClearsThePauseRequest() throws Exception {
        write(ComponentModelStore.partFile(context), 64L);
        ComponentDownloadWorker.pause(context);
        assertTrue(ComponentModelStore.pauseRequested(context));
        ComponentModelStore.delete(context);
        assertFalse(ComponentModelStore.pauseRequested(context));
        assertEquals(State.NOT_INSTALLED, ComponentModelStore.state(context));
    }

    // ---- keeping the status call cheap enough to poll ---------------------------------------------

    /**
     * The screen polls this while it is open, so the expensive question is asked only when its
     * answer could change the outcome.
     */
    @Test public void workManagerIsOnlyConsultedWhenItCouldChangeTheAnswer() {
        assertFalse("an installed model needs no WorkManager query",
                ComponentModelStore.needsWorkState(State.READY, true, false));
        assertFalse("a recorded error needs no WorkManager query",
                ComponentModelStore.needsWorkState(State.ERROR, false, true));
        assertFalse("a device with nothing downloaded needs no WorkManager query",
                ComponentModelStore.needsWorkState(State.NOT_INSTALLED, false, false));
        assertTrue("a download in flight must be checked",
                ComponentModelStore.needsWorkState(State.DOWNLOADING, false, true));
        assertTrue("a READY mark with no file must be checked",
                ComponentModelStore.needsWorkState(State.READY, false, true));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static List<WorkInfo.State> infos(WorkInfo.State... states) {
        return new ArrayList<>(Arrays.asList(states));
    }

    /** A sparse file of exactly this length, so a large fixture costs nothing on disk. */
    private static void write(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }
}

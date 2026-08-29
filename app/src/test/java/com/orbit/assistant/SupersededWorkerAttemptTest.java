package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two executions of one request, and why there were ever two.
 *
 * <p>The device report that started this read:
 * {@code worker-response · req 78e3dcb4 · was committed-done · attempt 3 · already-completed}.
 * The user saw no duplicate answer, so the durable completion claim did its job — but a second
 * execution of one request had reached the model, produced a reply, and arrived at the gate after
 * another one had already won. Beta 3 could record that it happened and nothing about who the two
 * sides were.
 *
 * <p>Two facts explain it, and both are checked or relied on here.
 *
 * <p>First, {@code getRunAttemptCount()} is not a retry counter. work-runtime 2.11.2 increments it
 * in {@code WorkerWrapper.trySetRunning()}, on every ENQUEUED to RUNNING transition, so it counts
 * starts — including restarts after a system interruption or a process death.
 * {@link OrbitRequestWorker} asks for at most one retry of its own, so "attempt 3" is arithmetic
 * proof that at least two of those starts were restarts Orbit never requested. See
 * {@link WorkerAttempt}.
 *
 * <p>Second, stopping a {@code Worker} does not stop it. WorkManager sets the stopped flag,
 * re-enqueues the work and starts the replacement, while the thread already inside
 * {@code doWork()} runs on — and a cloud provider ignores Orbit's cancellation signal, so it runs
 * on until its HTTP response arrives. The two executions overlap, both ask the model, and the
 * loser is refused at the gate.
 *
 * <p>What Beta 4 changes is the overlap, not the guard. The guard is permanent defence in depth
 * and the first test in this file says so.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SupersededWorkerAttemptTest {

    private static final String CHAT = "conversation-superseded";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        ComposerTrace.begin("superseded-test");
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "what is 18% of 75"));
        ConversationStore.save(context, CHAT, history);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    private PendingRequestStore.Item newRequest() {
        return PendingRequestStore.create(context, CHAT, "what is 18% of 75", "", "", false,
                false, Prefs.MODE_BALANCED, false, "");
    }

    private Runnable answerWith(String text, AtomicInteger commits, String id) {
        return () -> {
            commits.incrementAndGet();
            ConversationStore.appendMessage(context, CHAT,
                    new AssistantClient.History("assistant", text));
            PendingRequestStore.markDone(context, id);
        };
    }

    private int assistantMessages() {
        ConversationStore.Conversation chat = ConversationStore.load(context, CHAT);
        if (chat == null) return 0;
        int count = 0;
        for (AssistantClient.History m : chat.messages) {
            if ("assistant".equalsIgnoreCase(m.role)) count++;
        }
        return count;
    }

    // ---- the guard is permanent ------------------------------------------------------------------

    /**
     * Even with the overlap closed upstream, one request id may still only ever produce one
     * persisted answer. This is the property that made the device occurrence harmless, and it does
     * not get to depend on any of the rest of this file being correct.
     */
    @Test public void theCompletionClaimStillRefusesASecondAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(0, false),
                answerWith("13.5", commits, item.id)));
        assertFalse("a second execution of one request must never persist a second answer",
                OrbitRequestManager.completeIfNotCancelled(context, item.id,
                        CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(3, false),
                        answerWith("13.5 again", commits, item.id)));

        assertEquals(1, commits.get());
        assertEquals("one user turn, one assistant message", 1, assistantMessages());
    }

    /** The device line, reproduced end to end, in the vocabulary Beta 4 reports it in. */
    @Test public void theDeviceRefusalIsReproducedWithBothSidesNamed() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        // The older, stopped execution still holding a live provider request wins the race.
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(1, true),
                answerWith("13.5", commits, item.id));
        // The current execution arrives with its own reply and is refused.
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(3, false),
                answerWith("13.5 again", commits, item.id));

        String detail = DiagnosticStore.prefs(context).getString("completion_ignored_detail", "");
        assertTrue(detail, detail.contains(CompletionSource.WORKER_RESPONSE.token));
        assertTrue(detail, detail.contains(RequestTrace.shortId(item.id)));
        assertTrue("the state it found: " + detail, detail.contains("committed-done"));
        assertTrue("the run it was on: " + detail, detail.contains("run 4"));
        assertTrue(detail, detail.contains(RequestTrace.REASON_ALREADY_COMPLETED));
        assertEquals(1, commits.get());
        assertEquals(1, assistantMessages());
    }

    /** A stopped execution that wins the race is still identified as one in the record. */
    @Test public void aStoppedExecutionSaysSoWhenItCompletes() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(0, false),
                answerWith("13.5", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(2, true),
                answerWith("13.5 again", commits, item.id));

        String detail = DiagnosticStore.prefs(context).getString("completion_ignored_detail", "");
        assertTrue("a refusal from a stopped run must be distinguishable: " + detail,
                detail.contains("stopped"));
    }

    // ---- what the run number actually means -------------------------------------------------------

    /**
     * The misrepresentation Beta 3 shipped. Diagnostics called this WorkManager's retry counter;
     * it counts starts. Reporting it 1-based as a run number is what stops "attempt 3" being read
     * as "Orbit retried three times" when Orbit retries at most once.
     */
    @Test public void theRunCountIsAStartCountReportedFromOne() {
        assertEquals("the first execution is run 1", 1, WorkerAttempt.of(0, false).runNumber());
        assertEquals("the device's \"attempt 3\" is the fourth execution",
                4, WorkerAttempt.of(3, false).runNumber());
        assertEquals("run 4", WorkerAttempt.of(3, false).describe());
        assertEquals("run 4 stopped", WorkerAttempt.of(3, true).describe());
        assertTrue(WorkerAttempt.of(0, false).known());
    }

    /** A caller with no worker behind it reports nothing rather than inventing a run number. */
    @Test public void aCallerWithoutAWorkerReportsNothing() {
        assertFalse(WorkerAttempt.NONE.known());
        assertEquals("", WorkerAttempt.NONE.describe());
        assertFalse(WorkerAttempt.of(-5, true).known());

        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("13.5", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("13.5 again", commits, item.id));
        String detail = DiagnosticStore.prefs(context).getString("completion_ignored_detail", "");
        assertFalse("no run number may be invented for a caller that has none: " + detail,
                detail.contains("run "));
        assertTrue(detail, detail.contains(CompletionSource.UNKNOWN.token));
    }

    // ---- the overlap itself -----------------------------------------------------------------------

    /**
     * The narrow fix. WorkManager never runs one unique work item twice at once, so a second
     * execution finding the claim held can only mean the first was stopped and has not returned.
     * It stands down instead of opening a second model call for the same user turn.
     */
    @Test public void asecondExecutionOfOneRequestIsRefusedWhileTheFirstIsAlive() {
        PendingRequestStore.Item item = newRequest();
        assertTrue("the first execution takes the request",
                OrbitRequestManager.beginWorkerAttempt(item.id));
        assertTrue(OrbitRequestManager.hasRunningWorkerAttempt(item.id));
        assertFalse("a second execution must not start its own model call",
                OrbitRequestManager.beginWorkerAttempt(item.id));

        OrbitRequestManager.endWorkerAttempt(item.id);
        assertFalse(OrbitRequestManager.hasRunningWorkerAttempt(item.id));
        assertTrue("and once it is released the request can run again",
                OrbitRequestManager.beginWorkerAttempt(item.id));
    }

    /** Different requests never block each other; the claim is per request id. */
    @Test public void separateRequestsRunConcurrently() {
        PendingRequestStore.Item first = newRequest();
        PendingRequestStore.Item second = newRequest();
        assertTrue(OrbitRequestManager.beginWorkerAttempt(first.id));
        assertTrue(OrbitRequestManager.beginWorkerAttempt(second.id));
        assertFalse(OrbitRequestManager.hasRunningWorkerAttempt("some-other-request"));
    }

    /** Nothing is claimed for a caller with no request, and releasing twice is harmless. */
    @Test public void theClaimSurvivesOddInput() {
        assertFalse(OrbitRequestManager.beginWorkerAttempt(null));
        assertFalse(OrbitRequestManager.beginWorkerAttempt(""));
        assertFalse(OrbitRequestManager.hasRunningWorkerAttempt(null));
        OrbitRequestManager.endWorkerAttempt(null);
        PendingRequestStore.Item item = newRequest();
        OrbitRequestManager.beginWorkerAttempt(item.id);
        OrbitRequestManager.endWorkerAttempt(item.id);
        OrbitRequestManager.endWorkerAttempt(item.id);
        assertFalse(OrbitRequestManager.hasRunningWorkerAttempt(item.id));
    }

    /**
     * In-process on purpose. The window this closes only exists while two threads of one process
     * are alive on one request; after a process death there is no such window, and a restarted
     * execution must be free to run rather than blocked by a claim nobody holds.
     */
    @Test public void aClaimDoesNotSurviveTheProcessThatMadeIt() {
        PendingRequestStore.Item item = newRequest();
        assertTrue(OrbitRequestManager.beginWorkerAttempt(item.id));
        OrbitRequestManager.resetForTest();
        assertFalse("a claim from a dead process must not strand the request",
                OrbitRequestManager.hasRunningWorkerAttempt(item.id));
        assertTrue(OrbitRequestManager.beginWorkerAttempt(item.id));
    }

    // ---- what a superseded execution records -------------------------------------------------------

    @Test public void standingDownIsCountedAndAttributed() {
        PendingRequestStore.Item item = newRequest();
        RequestTrace.attemptSuperseded(context, item.id, WorkerAttempt.of(3, true),
                RequestTrace.STAGE_ALREADY_RUNNING);

        assertEquals(1, DiagnosticStore.prefs(context).getInt("worker_attempts_superseded", 0));
        String detail = DiagnosticStore.prefs(context).getString("worker_superseded_detail", "");
        assertTrue("which request: " + detail, detail.contains(RequestTrace.shortId(item.id)));
        assertTrue("which run: " + detail, detail.contains("run 4"));
        assertTrue("and that it had been stopped: " + detail, detail.contains("stopped"));
        assertTrue("and where it stood down: " + detail,
                detail.contains(RequestTrace.STAGE_ALREADY_RUNNING));
        assertTrue(DiagnosticStore.prefs(context).getLong("worker_superseded_at", 0L) > 0L);
    }

    @Test public void eachStageThatCanStandDownHasItsOwnName() {
        PendingRequestStore.Item item = newRequest();
        String[] stages = {RequestTrace.STAGE_ALREADY_RUNNING, RequestTrace.STAGE_BEFORE_REQUEST,
                RequestTrace.STAGE_ERROR_DISCARDED};
        for (String stage : stages) {
            RequestTrace.attemptSuperseded(context, item.id, WorkerAttempt.of(1, true), stage);
            assertTrue(stage + " must name itself",
                    DiagnosticStore.prefs(context).getString("worker_superseded_detail", "")
                            .contains(stage));
        }
        assertEquals(stages.length,
                DiagnosticStore.prefs(context).getInt("worker_attempts_superseded", 0));
    }

    /**
     * Everything recorded about a competing execution is Orbit's own vocabulary. A refusal, a
     * stand-down, and the run they were on say nothing about what was asked or answered.
     */
    @Test public void noneOfThisProvenanceCarriesUserContent() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(0, false),
                answerWith("13.5 is the answer", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, WorkerAttempt.of(2, true),
                answerWith("13.5 is the answer", commits, item.id));
        RequestTrace.attemptSuperseded(context, item.id, WorkerAttempt.of(2, true),
                RequestTrace.STAGE_ERROR_DISCARDED);

        String recorded = DiagnosticStore.prefs(context).getString("completion_ignored_detail", "")
                + " " + DiagnosticStore.prefs(context).getString("worker_superseded_detail", "")
                + " " + String.join(" ", ComposerTrace.events());
        assertFalse("no prompt text", recorded.contains("18%"));
        assertFalse("no prompt text", recorded.toLowerCase().contains("what is"));
        assertFalse("no answer text", recorded.contains("13.5"));
        assertFalse("no conversation id", recorded.contains(CHAT));
        assertFalse("not even the whole request id", recorded.contains(item.id));
    }
}

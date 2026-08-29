package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

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
 * Where a refused completion came from.
 *
 * <p>Beta 1's request-id guard did its job on the device: one completion attempt was refused and
 * the user saw no second answer. But the report could only say that the count was 1. It could not
 * say which code path had arrived, which WorkManager attempt it was on, what state the request was
 * already in, or even whether it was a duplicate at all — because the single counter labelled
 * "already terminal" also counted every completion refused for the ordinary reason that the user
 * had pressed Stop. Those two events mean opposite things and were indistinguishable.
 *
 * <p>Nothing here weakens the guard, and the first test in this file exists to say so. The guard is
 * permanent defence in depth: even once an upstream duplicate is found and fixed, a second
 * completion for one request must still be unable to persist a second answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CompletionProvenanceTest {

    private static final String CHAT = "conversation-provenance";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        ComposerTrace.begin("provenance-test");
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

    private SharedPreferences diagnostics() { return DiagnosticStore.prefs(context); }
    private String lastRefusal() { return diagnostics().getString("completion_ignored_detail", ""); }

    // ---- the guard is unchanged --------------------------------------------------------------------

    /**
     * The safety net stays, permanently, whatever else is learned about where duplicates come
     * from. One request, one persisted answer.
     */
    @Test public void theTerminalCompletionGuardStillRefusesASecondAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id)));
        assertFalse("a second completion may never persist another answer",
                OrbitRequestManager.completeIfNotCancelled(context, item.id,
                        CompletionSource.WORKER_RESPONSE, 1,
                        answerWith("13.5, roughly.", commits, item.id)));

        assertEquals(1, commits.get());
        assertEquals(1, assistantMessages());
        assertTrue(PendingRequestStore.isCommitted(context, item.id));
    }

    @Test public void theUnattributedOverloadStillEnforcesTheSameRule() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        assertTrue(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("13.5", commits, item.id)));
        assertFalse(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("13.5 again", commits, item.id)));
        assertEquals(1, assistantMessages());
        assertTrue("an unidentified caller is visible rather than silently mislabelled",
                lastRefusal().contains(CompletionSource.UNKNOWN.token));
    }

    // ---- what the refusal now records ---------------------------------------------------------------

    @Test public void aRefusedDuplicateRecordsItsOriginAttemptAndPriorState() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 2, answerWith("13.5 again", commits, item.id));

        String detail = lastRefusal();
        assertTrue("which code path arrived: " + detail,
                detail.contains(CompletionSource.WORKER_RESPONSE.token));
        assertTrue("which request: " + detail, detail.contains(RequestTrace.shortId(item.id)));
        assertTrue("which WorkManager attempt: " + detail, detail.contains("attempt 2"));
        assertTrue("what it was already: " + detail, detail.contains("committed-done"));
        assertTrue("and why it was refused: " + detail,
                detail.contains(RequestTrace.REASON_ALREADY_COMPLETED));
        assertTrue("and when", diagnostics().getLong("completion_ignored_at", 0L) > 0L);
    }

    @Test public void theErrorPathIdentifiesItselfSeparately() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_ERROR, 1, answerWith("Orbit could not finish", commits, item.id));

        assertTrue(lastRefusal().contains(CompletionSource.WORKER_ERROR.token));
        assertNotEquals(CompletionSource.WORKER_ERROR.token, CompletionSource.WORKER_RESPONSE.token);
    }

    /**
     * The distinction the old single counter could not draw. Stopping a request is ordinary and
     * says nothing about duplicates; a completion refused because the request was already answered
     * is the one worth chasing.
     */
    @Test public void stoppingARequestIsCountedButNotAsADuplicate() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        assertFalse(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id)));

        assertEquals(0, commits.get());
        assertEquals("a stop is a refusal", 1, diagnostics().getInt("completions_ignored", 0));
        assertEquals("but it is not a duplicate completion",
                0, diagnostics().getInt("completions_ignored_duplicate", 0));
        assertTrue(lastRefusal().contains(RequestTrace.REASON_CANCELLED));
    }

    @Test public void onlyDuplicatesIncrementTheDuplicateCount() {
        PendingRequestStore.Item first = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, first.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, first.id));
        OrbitRequestManager.completeIfNotCancelled(context, first.id,
                CompletionSource.WORKER_RESPONSE, 1, answerWith("again", commits, first.id));

        PendingRequestStore.Item stopped = newRequest();
        OrbitRequestManager.cancel(context, stopped.id);
        OrbitRequestManager.completeIfNotCancelled(context, stopped.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("late", commits, stopped.id));

        assertEquals(2, diagnostics().getInt("completions_ignored", 0));
        assertEquals(1, diagnostics().getInt("completions_ignored_duplicate", 0));
        assertEquals("the aggregate counter is kept alongside the breakdown",
                1, diagnostics().getInt("completions_committed", 0));
    }

    // ---- privacy ------------------------------------------------------------------------------------

    @Test public void provenanceCarriesNoPromptOrResponseText() {
        PendingRequestStore.Item item = PendingRequestStore.create(context, CHAT,
                "remind me about my hospital appointment with Dr Salt", "", "", false, false,
                Prefs.MODE_BALANCED, false, "");
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0,
                answerWith("Your appointment is on Tuesday at the Royal London.", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 1,
                answerWith("Your appointment is on Tuesday.", commits, item.id));

        String detail = lastRefusal();
        String trace = ComposerTrace.report();
        for (String secret : new String[]{"hospital", "Dr Salt", "Royal London", "Tuesday",
                "appointment", CHAT}) {
            assertFalse("provenance leaked " + secret + ": " + detail, detail.contains(secret));
            assertFalse("trace leaked " + secret, trace.contains(secret));
        }
        assertFalse("not even the whole request id", detail.contains(item.id));
        assertTrue(detail.contains(RequestTrace.shortId(item.id)));
    }

    @Test public void sourceTokensAreOrbitsOwnVocabulary() {
        for (CompletionSource source : CompletionSource.values()) {
            assertTrue(source.name(), source.token.matches("[a-z-]{3,24}"));
        }
    }

    // ---- the lifecycle cases the guard exists for ----------------------------------------------------

    /**
     * The known process-death window: WorkManager re-runs a worker whose answer was already
     * claimed. The worker abandons it before asking the model again, so this never even reaches
     * the guard.
     */
    @Test public void aWorkManagerRetryOfAnAnsweredRequestPersistsNothingAndIsNotCountedTwice() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id));

        PendingRequestStore.Item rerun = PendingRequestStore.load(context, item.id);
        assertTrue("the claim is durable, so a re-run can see it", rerun.committed);
        assertTrue(PendingRequestStore.isTerminal(rerun.status));
        assertEquals(1, assistantMessages());
    }

    @Test public void listenerReattachmentNeverPersistsAnotherAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.Listener listener = new OrbitRequestManager.Listener() {};
        for (int i = 0; i < 5; i++) {
            OrbitRequestManager.addListener(item.id, listener);
            OrbitRequestManager.removeListener(item.id, listener);
        }
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id));
        OrbitRequestManager.addListener(item.id, listener);

        assertEquals(1, assistantMessages());
        assertEquals(0, diagnostics().getInt("completions_ignored_duplicate", 0));
    }

    @Test public void activityRecreationNeverPersistsAnotherAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id));

        // A recreated surface loses every in-process record and reads the durable store instead.
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        assertFalse(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5 again", commits, item.id)));
        assertEquals(1, assistantMessages());
    }

    @Test public void separateRequestsRemainSeparate() {
        PendingRequestStore.Item first = newRequest();
        PendingRequestStore.Item second = newRequest();
        AtomicInteger commits = new AtomicInteger();
        assertTrue(OrbitRequestManager.completeIfNotCancelled(context, first.id,
                CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, first.id)));
        assertTrue("a genuinely separate request is a separate turn",
                OrbitRequestManager.completeIfNotCancelled(context, second.id,
                        CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, second.id)));
        assertEquals(2, assistantMessages());
        assertEquals(0, diagnostics().getInt("completions_ignored_duplicate", 0));
    }

    /** Asking the same thing again later is a person changing their mind, not a duplicate. */
    @Test public void theSameQuestionAskedAgainLaterIsStillAllowed() {
        AtomicInteger commits = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            PendingRequestStore.Item item = newRequest();
            assertTrue(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                    CompletionSource.WORKER_RESPONSE, 0, answerWith("13.5", commits, item.id)));
        }
        assertEquals(3, assistantMessages());
        assertEquals(3, diagnostics().getInt("completions_committed", 0));
        assertEquals(0, diagnostics().getInt("completions_ignored", 0));
    }
}

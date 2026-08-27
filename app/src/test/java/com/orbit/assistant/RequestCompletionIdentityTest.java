package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * One accepted submission produces one request id, and one request id produces at most one
 * persisted assistant completion.
 *
 * <p>The second failure the 0.7.7.7 device reports exposed was different from the duplicate-send
 * one: a trace showed no second submit event, yet the conversation held two similar assistant
 * answers under a single visible user message. "Similar" rather than identical is the clue — the
 * model had been asked twice, which is what happens when a worker is re-run after its answer had
 * already been written but before its state reached disk.
 *
 * <p>So the invariant is enforced on the request id, durably, and never by comparing response text.
 * Attaching more listeners is explicitly not a way around it: a recreated Activity may watch a
 * request as many times as it likes and still cannot cause a second answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RequestCompletionIdentityTest {

    private static final String CHAT = "conversation-completion";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        OrbitRequestManager.resetForTest();
        SubmissionGate.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        seedUserMessage();
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
        SubmissionGate.resetForTest();
    }

    private void seedUserMessage() {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "put the schedule on my calendar"));
        ConversationStore.save(context, CHAT, history);
    }

    private PendingRequestStore.Item newRequest() {
        return PendingRequestStore.create(context, CHAT, "put the schedule on my calendar",
                "", "", false, false, Prefs.MODE_BALANCED, false, "");
    }

    /** What the worker does inside the gate: persist the answer and mark the request done. */
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
        for (AssistantClient.History message : chat.messages) {
            if ("assistant".equalsIgnoreCase(message.role)) count++;
        }
        return count;
    }

    // ---- the invariant -------------------------------------------------------------------------

    @Test public void oneRequestCommitsExactlyOneAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("Added 12 events to Personal.", commits, item.id)));
        assertEquals(1, commits.get());
        assertEquals(1, assistantMessages());
    }

    /**
     * The exact shape of the reported failure: the same turn answered a second time, with
     * different words. Text is never compared; the request id refuses it.
     */
    @Test public void aSecondCompletionForTheSameRequestIsRefused() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("I found Michigan's 12 regular-season games.", commits, item.id));
        boolean second = OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("Here are Michigan's 12 games for this season.", commits, item.id));

        assertFalse("the second completion must not run", second);
        assertEquals(1, commits.get());
        assertEquals("one visible user turn keeps one answer", 1, assistantMessages());
    }

    @Test public void aCompletionCallbackFiringTwiceCommitsOnce() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            OrbitRequestManager.completeIfNotCancelled(context, item.id,
                    answerWith("Done.", commits, item.id));
        }
        assertEquals(1, commits.get());
        assertEquals(1, assistantMessages());
    }

    /**
     * The claim is durable and taken before the answer is written, so a worker re-run by
     * WorkManager after a process death abandons the turn rather than asking the model again.
     */
    @Test public void theCompletionClaimIsDurableAndTakenBeforeTheAnswerIsWritten() {
        PendingRequestStore.Item item = newRequest();
        assertFalse(PendingRequestStore.isCommitted(context, item.id));

        assertTrue("the first claim wins", PendingRequestStore.claimCompletion(context, item.id));
        assertTrue("and is readable afterwards, as a later process would read it",
                PendingRequestStore.isCommitted(context, item.id));
        assertFalse("a second claim is refused",
                PendingRequestStore.claimCompletion(context, item.id));

        PendingRequestStore.Item reloaded = PendingRequestStore.load(context, item.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.committed);
        assertEquals("claiming is not the same as finishing",
                PendingRequestStore.QUEUED, reloaded.status);
    }

    @Test public void aWorkManagerRetryOfACommittedRequestPersistsNothingMore() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("Added 12 events to Personal.", commits, item.id));

        // What a re-run worker sees before it does anything else.
        PendingRequestStore.Item asRetrySeesIt = PendingRequestStore.load(context, item.id);
        assertNotNull(asRetrySeesIt);
        assertTrue("the retry must be able to tell the turn is already answered",
                asRetrySeesIt.committed || PendingRequestStore.isTerminal(asRetrySeesIt.status));

        boolean rerun = OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("A second, similar answer.", commits, item.id));
        assertFalse(rerun);
        assertEquals(1, assistantMessages());
    }

    @Test public void theWorkerAbandonsARerunOfACommittedRequest() {
        String worker = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRequestWorker.java");
        assertTrue("the worker must check the durable claim before running the turn again",
                worker.contains("if (item.committed)"));
        assertTrue(worker.contains("rerun-abandoned"));
    }

    // ---- listeners are observers, never producers --------------------------------------------------

    @Test public void attachingMoreListenersNeverProducesAnotherAnswer() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger commits = new AtomicInteger();

        // A recreated Activity attaching, then another surface attaching as well.
        for (int i = 0; i < 3; i++) {
            OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
                @Override public void onSuccess(String requestId, AssistantReply reply) {
                    delivered.incrementAndGet();
                }
            });
        }
        assertTrue(OrbitRequestManager.hasListeners(item.id));

        OrbitRequestManager.completeIfNotCancelled(context, item.id, () -> {
            answerWith("Added 12 events to Personal.", commits, item.id).run();
            OrbitRequestManager.dispatchSuccess(item.id, new AssistantReply("Added 12 events."));
        });

        assertEquals("every attached surface hears about the one answer", 3, delivered.get());
        assertEquals("but only one answer was ever written", 1, commits.get());
        assertEquals(1, assistantMessages());
    }

    /** Attaching cannot enqueue. The only path that creates work is an accepted submission. */
    @Test public void attachingToAPendingRequestNeverEnqueuesAnother() {
        PendingRequestStore.Item item = newRequest();
        int before = PendingRequestStore.activeForConversation(context, CHAT).size();

        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {});
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {});

        assertEquals(before, PendingRequestStore.activeForConversation(context, CHAT).size());

        String manager = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRequestManager.java");
        int addListener = manager.indexOf("public static void addListener(");
        assertTrue(addListener > 0);
        String body = manager.substring(addListener,
                Math.min(manager.length(), addListener + 600));
        assertFalse("attaching must never reach WorkManager", body.contains("enqueue"));
    }

    /** A recreated chat re-attaches to the request it left running, and starts nothing. */
    @Test public void fullChatAttachesToExistingRequestsRatherThanResending() {
        String chat = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatActivity.java");
        int attach = chat.indexOf("private void attachToPending()");
        assertTrue(attach > 0);
        String body = chat.substring(attach, Math.min(chat.length(), attach + 400));
        assertTrue("it attaches by request id", body.contains("registerRequest(item.id)"));
        assertFalse("and never enqueues", body.contains("OrbitRequestManager.enqueue"));

        int register = chat.indexOf("private void registerRequest(");
        assertTrue(register > 0);
        assertTrue("a request already being watched is not watched twice",
                chat.substring(register, register + 260).contains("listeners.containsKey(id)"));
    }

    /** The overlay handing off to full chat carries a conversation, not a new request. */
    @Test public void theOverlayHandoffCarriesTheConversationNotANewRequest() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        int handoff = session.indexOf("ChatActivity.EXTRA_ASSISTANT_HANDOFF");
        assertTrue(handoff > 0);
        String around = session.substring(Math.max(0, handoff - 700),
                Math.min(session.length(), handoff + 700));
        assertTrue(around.contains("ChatActivity.EXTRA_CONVERSATION_ID"));
        assertFalse("the handoff must not start a second turn",
                around.contains("OrbitRequestManager.enqueue"));
        assertFalse(around.contains("submitPrompt("));
    }

    // ---- stopping still wins ---------------------------------------------------------------------------

    @Test public void aStoppedRequestStillRefusesItsCompletion() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();

        assertTrue(OrbitRequestManager.cancel(context, item.id));
        assertFalse(OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("Too late.", commits, item.id)));
        assertEquals(0, commits.get());
        assertEquals(0, assistantMessages());
    }

    @Test public void aClaimedCompletionCannotBeUndoneByALateStop() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("Added 12 events to Personal.", commits, item.id));

        assertFalse("there is nothing left for a Stop to prevent",
                OrbitRequestManager.cancel(context, item.id));
        assertEquals(1, assistantMessages());
    }

    // ---- diagnostics ---------------------------------------------------------------------------------

    @Test public void diagnosticsShowCommittedAndIgnoredCompletionsWithoutContent() {
        PendingRequestStore.Item item = newRequest();
        AtomicInteger commits = new AtomicInteger();
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("My private answer about hunter2.", commits, item.id));
        OrbitRequestManager.completeIfNotCancelled(context, item.id,
                answerWith("A second answer.", commits, item.id));

        assertEquals(1, DiagnosticStore.prefs(context).getInt("completions_committed", 0));
        assertEquals(1, DiagnosticStore.prefs(context).getInt("completions_ignored", 0));

        String trace = ComposerTrace.report();
        assertTrue(trace.contains("request.completion-committed"));
        assertTrue(trace.contains("request.completion-ignored"));
        assertTrue(trace.contains("reason=already-completed"));
        assertFalse("reply text must never reach a diagnostics buffer", trace.contains("hunter2"));
        assertFalse(trace.contains("private answer"));
    }

    /** Enough of a request id to follow a turn, never the whole conversation identity. */
    @Test public void tracedRequestIdsAreShortened() {
        assertEquals("abcdef12", RequestTrace.shortId("abcdef12-3456-7890-abcd-ef1234567890"));
        assertEquals("none", RequestTrace.shortId(null));
        assertEquals("none", RequestTrace.shortId(""));
    }
}

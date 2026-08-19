package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stopping a reply has to be real: the durable request ends, and nothing it started is allowed to
 * land afterwards.
 *
 * <p>These drive the production cancellation path itself — {@code OrbitRequestManager.cancel},
 * the {@code completeIfNotCancelled} gate every irreversible completion step passes through, and
 * the {@code PendingRequestStore} state machine. The worker cannot be run here because it makes a
 * real model request, so the gate it calls is exercised directly, with a stand-in completion that
 * performs the same steps the worker performs inside it: persisting the answer, telling listeners,
 * running response actions, and posting the background notification.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RequestCancellationTest {
    private Context context;
    private final List<String> cancelledWork = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        cancelledWork.clear();
        OrbitRequestManager.setWorkCanceller(cancelledWork::add);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    /** A conversation the user has already spoken in, which is what any real request starts from. */
    private void seedUserMessage(String conversationId) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "What is the weather?"));
        ConversationStore.save(context, conversationId, history);
    }

    private PendingRequestStore.Item queue(String conversationId) {
        return PendingRequestStore.create(context, conversationId, "What is the weather?", "", "",
                false, false, Prefs.MODE_BALANCED, false, "");
    }

    private List<AssistantClient.History> assistantMessages(String conversationId) {
        ConversationStore.Conversation chat = ConversationStore.load(context, conversationId);
        List<AssistantClient.History> result = new ArrayList<>();
        if (chat == null) return result;
        for (AssistantClient.History h : chat.messages) {
            if ("assistant".equalsIgnoreCase(h.role)) result.add(h);
        }
        return result;
    }

    private String status(String requestId) {
        PendingRequestStore.Item item = PendingRequestStore.load(context, requestId);
        return item == null ? null : item.status;
    }

    // ---- 1. A request stopped before the worker ever runs -------------------------------------

    @Test public void queuedRequestIsCancelledBeforeItRuns() {
        String convo = "convo-queued";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        assertEquals(PendingRequestStore.QUEUED, status(item.id));

        assertTrue(OrbitRequestManager.cancel(context, item.id));

        assertEquals(PendingRequestStore.CANCELLED, status(item.id));
        // The worker's very first guard is exactly this, so a run that starts anyway returns
        // immediately without touching the conversation.
        assertTrue(PendingRequestStore.isTerminal(status(item.id)));
        assertTrue(assistantMessages(convo).isEmpty());
    }

    @Test public void cancellingCancelsTheMatchingUniqueWork() {
        String convo = "convo-work";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);

        OrbitRequestManager.cancel(context, item.id);

        assertEquals(1, cancelledWork.size());
        assertEquals(OrbitRequestManager.uniqueWorkName(item.id), cancelledWork.get(0));
        assertEquals("orbit-request-" + item.id, cancelledWork.get(0));
    }

    // ---- 2. A request stopped while it is running ---------------------------------------------

    @Test public void runningRequestIsCancelled() {
        String convo = "convo-running";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markRunning(context, item.id);
        assertEquals(PendingRequestStore.RUNNING, status(item.id));

        assertTrue(OrbitRequestManager.cancel(context, item.id));

        assertEquals(PendingRequestStore.CANCELLED, status(item.id));
        assertEquals(1, cancelledWork.size());
    }

    // ---- 3-5. What CANCELLED means ------------------------------------------------------------

    @Test public void cancelledIsTerminal() {
        String convo = "convo-terminal";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        OrbitRequestManager.cancel(context, item.id);

        assertTrue(PendingRequestStore.isTerminal(PendingRequestStore.CANCELLED));
        assertTrue(PendingRequestStore.isTerminal(status(item.id)));
        // Terminal means terminal: a second Stop changes nothing and reports that it did nothing.
        assertFalse(OrbitRequestManager.cancel(context, item.id));
        assertEquals(PendingRequestStore.CANCELLED, status(item.id));
    }

    @Test public void cancelledMetadataIsPrunedLikeEveryOtherFinishedRequest() {
        // The predicate the seven-day prune uses, so a stopped request cleans itself up on the
        // same schedule as one that finished or failed.
        assertTrue(PendingRequestStore.isTerminal(PendingRequestStore.DONE));
        assertTrue(PendingRequestStore.isTerminal(PendingRequestStore.FAILED));
        assertTrue(PendingRequestStore.isTerminal(PendingRequestStore.CANCELLED));
        assertFalse(PendingRequestStore.isTerminal(PendingRequestStore.QUEUED));
        assertFalse(PendingRequestStore.isTerminal(PendingRequestStore.RUNNING));
    }

    @Test public void cancelledIsNotFailed() {
        String convo = "convo-not-failed";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        OrbitRequestManager.cancel(context, item.id);

        assertFalse(PendingRequestStore.FAILED.equals(status(item.id)));
        // No Retry, no failure state: the conversation has nothing failed in it to offer them for.
        assertTrue(PendingRequestStore.failedForConversation(context, convo).isEmpty());
        assertNull(PendingRequestStore.latestFailedForConversation(context, convo));
        assertEquals("", PendingRequestStore.load(context, item.id).error);
    }

    @Test public void cancelledIsNotActive() {
        String convo = "convo-not-active";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        assertTrue(PendingRequestStore.hasActiveForConversation(context, convo));

        OrbitRequestManager.cancel(context, item.id);

        assertFalse(PendingRequestStore.hasActiveForConversation(context, convo));
        assertTrue(PendingRequestStore.activeForConversation(context, convo).isEmpty());
        for (PendingRequestStore.Item active : PendingRequestStore.active(context)) {
            assertFalse(item.id.equals(active.id));
        }
    }

    @Test public void aRequestThatAlreadyFinishedCannotBeCancelled() {
        String convo = "convo-done";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markDone(context, item.id);

        assertFalse(OrbitRequestManager.cancel(context, item.id));
        assertEquals(PendingRequestStore.DONE, status(item.id));
        assertTrue(cancelledWork.isEmpty());
    }

    // ---- 6. Nothing more is accepted after the stop --------------------------------------------

    @Test public void noFurtherDeltasAreAcceptedAfterCancellation() {
        String convo = "convo-deltas";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        List<String> seen = new ArrayList<>();
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onDelta(String requestId, String delta) { seen.add(delta); }
        });

        OrbitRequestManager.dispatchDelta(item.id, "The weather today");
        assertEquals(1, seen.size());

        OrbitRequestManager.cancel(context, item.id);
        OrbitRequestManager.dispatchDelta(item.id, "The weather today is sunny and warm");
        OrbitRequestManager.dispatchStarted(item.id);

        assertEquals(1, seen.size());
        // The kept partial is what had streamed when the user stopped, not what arrived after.
        assertEquals(1, assistantMessages(convo).size());
        assertEquals("The weather today", assistantMessages(convo).get(0).content);
    }

    @Test public void aStoppedRequestAcceptsNoNewListeners() {
        String convo = "convo-late-listener";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        OrbitRequestManager.cancel(context, item.id);

        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {});

        assertFalse(OrbitRequestManager.hasListeners(item.id));
    }

    // ---- 7. A completion landing after the stop -------------------------------------------------

    @Test public void cancellationImmediatelyBeforeSuccessPreventsFinalPersistence() {
        String convo = "convo-late-success";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markRunning(context, item.id);

        assertTrue(OrbitRequestManager.cancel(context, item.id));

        // Exactly what the worker does on success, run through the exact gate the worker uses.
        boolean completed = OrbitRequestManager.completeIfNotCancelled(context, item.id, () ->
                ConversationStore.appendMessage(context, convo,
                        new AssistantClient.History("assistant", "The full finished answer.")));

        assertFalse(completed);
        assertTrue(assistantMessages(convo).isEmpty());
        assertEquals(PendingRequestStore.CANCELLED, status(item.id));
    }

    @Test public void cancellationPreventsAFailureBeingWrittenInstead() {
        String convo = "convo-late-error";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markRunning(context, item.id);
        OrbitRequestManager.cancel(context, item.id);

        // Cancelling interrupts the worker, which reaches its error path. Stopping a reply must
        // never be reported to the user as a failure.
        boolean wrote = OrbitRequestManager.completeIfNotCancelled(context, item.id, () -> {
            ConversationStore.appendMessage(context, convo,
                    new AssistantClient.History("assistant", "Orbit could not finish this response."));
            PendingRequestStore.markFailed(context, item.id, "Orbit request was interrupted.");
        });

        assertFalse(wrote);
        assertTrue(assistantMessages(convo).isEmpty());
        assertEquals(PendingRequestStore.CANCELLED, status(item.id));
        assertTrue(PendingRequestStore.failedForConversation(context, convo).isEmpty());
    }

    /**
     * The genuine race: a Stop and a completion released at the same instant, repeatedly. Exactly
     * one may win, and the loser must leave nothing behind.
     */
    @Test public void stopAndCompletionAtTheSameInstantCannotBothLand() throws Exception {
        for (int i = 0; i < 40; i++) {
            String convo = "convo-race-" + i;
            seedUserMessage(convo);
            PendingRequestStore.Item item = queue(convo);
            PendingRequestStore.markRunning(context, item.id);
            OrbitRequestManager.dispatchDelta(item.id, "Partial answer");

            CyclicBarrier start = new CyclicBarrier(2);
            AtomicBoolean completed = new AtomicBoolean(false);
            Thread worker = new Thread(() -> {
                try { start.await(); } catch (Exception ignored) {}
                completed.set(OrbitRequestManager.completeIfNotCancelled(context, item.id, () -> {
                    ConversationStore.appendMessage(context, convo,
                            new AssistantClient.History("assistant", "Full answer"));
                    PendingRequestStore.markDone(context, item.id);
                }));
            });
            worker.start();
            start.await();
            boolean cancelled = OrbitRequestManager.cancel(context, item.id);
            worker.join();

            assertTrue("exactly one of stop and completion may win",
                    cancelled != completed.get());

            List<AssistantClient.History> assistant = assistantMessages(convo);
            assertEquals("one outcome means one assistant message", 1, assistant.size());
            if (cancelled) {
                assertEquals(PendingRequestStore.CANCELLED, status(item.id));
                assertEquals("Partial answer", assistant.get(0).content);
            } else {
                assertEquals(PendingRequestStore.DONE, status(item.id));
                assertEquals("Full answer", assistant.get(0).content);
            }
        }
    }

    // ---- 8-10. Nothing a stopped reply started is allowed to happen ----------------------------

    /**
     * Response actions, the background completion notification, and a spoken reply all hang off a
     * successful completion reaching a listener. One gate closes all three.
     */
    @Test public void cancellationPreventsDeviceActionsNotificationAndSpeech() {
        String convo = "convo-consequences";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markRunning(context, item.id);

        AtomicInteger actionsExecuted = new AtomicInteger();
        AtomicInteger notificationsPosted = new AtomicInteger();
        AtomicInteger spokenReplies = new AtomicInteger();
        AtomicInteger handsFreeTurns = new AtomicInteger();

        // The surfaces run response actions and speak from onSuccess, which is also where a voice
        // turn decides whether to listen again.
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onSuccess(String requestId, AssistantReply reply) {
                actionsExecuted.incrementAndGet();
                spokenReplies.incrementAndGet();
                handsFreeTurns.incrementAndGet();
            }
        });

        OrbitRequestManager.cancel(context, item.id);

        boolean completed = OrbitRequestManager.completeIfNotCancelled(context, item.id, () -> {
            ConversationStore.appendMessage(context, convo,
                    new AssistantClient.History("assistant", "Turning the flashlight on."));
            PendingRequestStore.markDone(context, item.id);
            OrbitRequestManager.dispatchSuccess(item.id, new AssistantReply("Turning the flashlight on."));
            notificationsPosted.incrementAndGet();
        });

        assertFalse(completed);
        assertEquals(0, actionsExecuted.get());
        assertEquals(0, notificationsPosted.get());
        assertEquals(0, spokenReplies.get());
        assertEquals(0, handsFreeTurns.get());
        assertTrue(assistantMessages(convo).isEmpty());
    }

    @Test public void aSuccessDispatchedAnywayNeverReachesTheSurface() {
        String convo = "convo-zombie-dispatch";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onSuccess(String requestId, AssistantReply reply) { successes.incrementAndGet(); }
            @Override public void onError(String requestId, String message) { errors.incrementAndGet(); }
        });

        OrbitRequestManager.cancel(context, item.id);
        OrbitRequestManager.dispatchSuccess(item.id, new AssistantReply("Late answer"));
        OrbitRequestManager.dispatchError(item.id, "Late failure");

        assertEquals(0, successes.get());
        assertEquals(0, errors.get());
    }

    // ---- 11-13. What the user is left looking at ------------------------------------------------

    @Test public void aPartialResponseIsKeptExactlyOnceAndSurvivesReopening() {
        String convo = "convo-partial";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        OrbitRequestManager.dispatchDelta(item.id, "Here is the first half of the");

        List<String> cancelledWith = new ArrayList<>();
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onCancelled(String requestId, String partialText) {
                cancelledWith.add(partialText);
            }
        });

        OrbitRequestManager.cancel(context, item.id);

        assertEquals(1, cancelledWith.size());
        assertEquals("Here is the first half of the", cancelledWith.get(0));

        // Persisted by the manager, so reopening the conversation still shows it.
        List<AssistantClient.History> assistant = assistantMessages(convo);
        assertEquals(1, assistant.size());
        assertEquals("Here is the first half of the", assistant.get(0).content);
        assertNotNull(ConversationStore.load(context, convo));
        assertEquals(1, assistantMessages(convo).size());
    }

    @Test public void aPartialResponseIsNeverDuplicated() {
        String convo = "convo-partial-once";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        OrbitRequestManager.dispatchDelta(item.id, "Streaming so far");

        OrbitRequestManager.cancel(context, item.id);
        // Every way the partial could be written a second time.
        OrbitRequestManager.cancel(context, item.id);
        OrbitRequestManager.dispatchCancelled(item.id, "Streaming so far");
        OrbitRequestManager.dispatchSuccess(item.id, new AssistantReply("Streaming so far and then some"));

        List<AssistantClient.History> assistant = assistantMessages(convo);
        assertEquals(1, assistant.size());
        assertEquals("Streaming so far", assistant.get(0).content);
    }

    @Test public void stoppingBeforeAnyTextLeavesNoAssistantMessage() {
        String convo = "convo-no-text";
        seedUserMessage(convo);
        PendingRequestStore.Item item = queue(convo);
        PendingRequestStore.markRunning(context, item.id);

        List<String> cancelledWith = new ArrayList<>();
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onCancelled(String requestId, String partialText) {
                cancelledWith.add(partialText);
            }
        });

        OrbitRequestManager.cancel(context, item.id);

        assertEquals(1, cancelledWith.size());
        assertEquals("", cancelledWith.get(0));
        // No empty assistant bubble, and the user's own message is untouched.
        assertTrue(assistantMessages(convo).isEmpty());
        ConversationStore.Conversation chat = ConversationStore.load(context, convo);
        assertNotNull(chat);
        assertEquals(1, chat.messages.size());
        assertEquals("user", chat.messages.get(0).role);
    }

    // ---- Cancelling a whole conversation --------------------------------------------------------

    @Test public void stoppingAConversationEndsEveryRequestStillRunningInIt() {
        String convo = "convo-all";
        seedUserMessage(convo);
        PendingRequestStore.Item first = queue(convo);
        PendingRequestStore.Item second = queue(convo);
        PendingRequestStore.markRunning(context, first.id);

        assertTrue(OrbitRequestManager.cancelActiveForConversation(context, convo));

        assertEquals(PendingRequestStore.CANCELLED, status(first.id));
        assertEquals(PendingRequestStore.CANCELLED, status(second.id));
        assertFalse(PendingRequestStore.hasActiveForConversation(context, convo));
        assertEquals(2, cancelledWork.size());
        // Nothing left to stop reports that honestly, so a stale control can put itself right.
        assertFalse(OrbitRequestManager.cancelActiveForConversation(context, convo));
    }

    @Test public void stoppingOneConversationLeavesAnotherAlone() {
        seedUserMessage("convo-a");
        seedUserMessage("convo-b");
        PendingRequestStore.Item mine = queue("convo-a");
        PendingRequestStore.Item theirs = queue("convo-b");

        OrbitRequestManager.cancelActiveForConversation(context, "convo-a");

        assertEquals(PendingRequestStore.CANCELLED, status(mine.id));
        assertEquals(PendingRequestStore.QUEUED, status(theirs.id));
        assertTrue(PendingRequestStore.hasActiveForConversation(context, "convo-b"));
    }
}

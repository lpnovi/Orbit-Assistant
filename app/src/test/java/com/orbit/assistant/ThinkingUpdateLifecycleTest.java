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

/**
 * Thinking updates are observational, and this is where that word is made to mean something.
 *
 * <p>v0.7.7.7 stabilized one gesture producing one request producing one answer. A status line is
 * cosmetic and must not be allowed anywhere near that: these drive the manager's progress channel
 * directly and assert both halves of the contract — that an update reaches whoever is watching the
 * request it belongs to, and that it can reach nothing else. Request identity, never text
 * comparison, is what separates one request's updates from another's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThinkingUpdateLifecycleTest {
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

    private PendingRequestStore.Item queue(String conversationId) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Compare these two architectures."));
        ConversationStore.save(context, conversationId, history);
        return PendingRequestStore.create(context, conversationId,
                "Compare these two architectures.", "", "", false, false,
                Prefs.MODE_DEEP, false, "");
    }

    /** Records what it is told and nothing more, which is all a progress listener may do. */
    private static final class Watcher implements OrbitRequestManager.Listener {
        final List<String> seen = new ArrayList<>();
        @Override public void onThinking(String requestId, ThinkingUpdate update) {
            seen.add(requestId + ":" + update.text);
        }
    }

    // ---- an update reaches the request it belongs to --------------------------------------------

    @Test public void anUpdateReachesAListenerOnItsOwnRequest() {
        PendingRequestStore.Item item = queue("c-thinking");
        Watcher watcher = new Watcher();
        OrbitRequestManager.addListener(item.id, watcher);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));

        assertEquals(1, watcher.seen.size());
        assertEquals(item.id + ":Thinking…", watcher.seen.get(0));
    }

    /**
     * The rule that makes a stale update impossible. Two live requests, one listener each: neither
     * ever sees the other's, and the separation is by id rather than by what the text says.
     */
    @Test public void oneRequestsUpdateNeverReachesAnothersListener() {
        PendingRequestStore.Item first = queue("c-a");
        PendingRequestStore.Item second = queue("c-b");
        Watcher watchingFirst = new Watcher();
        Watcher watchingSecond = new Watcher();
        OrbitRequestManager.addListener(first.id, watchingFirst);
        OrbitRequestManager.addListener(second.id, watchingSecond);

        // Deliberately identical text: if anything anywhere compared strings instead of ids, this
        // is the case that would leak.
        OrbitRequestManager.dispatchThinking(first.id,
                ThinkingUpdate.providerSummary("Comparing the approaches"));
        OrbitRequestManager.dispatchThinking(second.id,
                ThinkingUpdate.providerSummary("Comparing the approaches"));

        assertEquals(1, watchingFirst.seen.size());
        assertEquals(1, watchingSecond.seen.size());
        assertTrue(watchingFirst.seen.get(0).startsWith(first.id + ":"));
        assertTrue(watchingSecond.seen.get(0).startsWith(second.id + ":"));
    }

    @Test public void aNullUpdateIsSimplyIgnored() {
        PendingRequestStore.Item item = queue("c-null");
        Watcher watcher = new Watcher();
        OrbitRequestManager.addListener(item.id, watcher);
        OrbitRequestManager.dispatchThinking(item.id, null);
        assertTrue(watcher.seen.isEmpty());
        assertNull(OrbitRequestManager.latestThinking(item.id));
    }

    // ---- the snapshot exists for handoff and dies with the request ------------------------------

    @Test public void theLatestUpdateIsAvailableForASurfaceThatAttachesMidFlight() {
        PendingRequestStore.Item item = queue("c-handoff");
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WEB_SEARCH));
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WEB_RESULTS));

        ThinkingUpdate latest = OrbitRequestManager.latestThinking(item.id);
        assertNotNull(latest);
        assertEquals("Reading the search results…", latest.text);
    }

    @Test public void stoppingARequestClearsItsStatusImmediately() {
        PendingRequestStore.Item item = queue("c-stop");
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("Checking the failure modes"));
        assertNotNull(OrbitRequestManager.latestThinking(item.id));

        assertTrue(OrbitRequestManager.cancel(context, item.id));
        assertNull("a stopped request must leave no status behind",
                OrbitRequestManager.latestThinking(item.id));
    }

    /** And nothing may put one back afterwards. */
    @Test public void noUpdateCanArriveAfterAStop() {
        PendingRequestStore.Item item = queue("c-stop-late");
        Watcher watcher = new Watcher();
        OrbitRequestManager.addListener(item.id, watcher);
        OrbitRequestManager.cancel(context, item.id);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("A summary that arrived too late"));

        assertTrue("a stopped request must not report progress", watcher.seen.isEmpty());
        assertNull(OrbitRequestManager.latestThinking(item.id));
    }

    @Test public void completingARequestClearsItsStatus() {
        PendingRequestStore.Item item = queue("c-done");
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));
        OrbitRequestManager.dispatchSuccess(item.id,
                new AssistantReply("Here is the answer.", new ArrayList<>()));
        assertNull(OrbitRequestManager.latestThinking(item.id));
    }

    @Test public void failingARequestClearsItsStatus() {
        PendingRequestStore.Item item = queue("c-failed");
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));
        OrbitRequestManager.dispatchError(item.id, "Orbit could not finish this response.");
        assertNull(OrbitRequestManager.latestThinking(item.id));
    }

    /** A superseded request is replaced by a new one, and its status goes with it. */
    @Test public void aSupersededRequestsStatusDoesNotSurviveIntoItsReplacement() {
        PendingRequestStore.Item first = queue("c-retry");
        OrbitRequestManager.dispatchThinking(first.id,
                ThinkingUpdate.providerSummary("Work from the request that was replaced"));
        PendingRequestStore.markSuperseded(context, first.id);
        PendingRequestStore.Item replacement = queue("c-retry");

        assertNull("the replacement starts with no status of its own",
                OrbitRequestManager.latestThinking(replacement.id));
    }

    // ---- the channel cannot touch the request pipeline ------------------------------------------

    /**
     * The load-bearing assertion of this file. A listener that watches progress must be unable to
     * cause a second answer, so a surface may attach, detach, and reattach as often as an Activity
     * recreation or a reopened overlay requires without changing what the request does.
     */
    @Test public void progressListenersNeverEnqueuePersistOrComplete() {
        PendingRequestStore.Item item = queue("c-safety");
        int workBefore = PendingRequestStore.active(context).size();

        Watcher watcher = new Watcher();
        for (int i = 0; i < 5; i++) {
            OrbitRequestManager.addListener(item.id, watcher);
            OrbitRequestManager.dispatchThinking(item.id,
                    ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));
            OrbitRequestManager.removeListener(item.id, watcher);
        }

        assertEquals("watching progress must not enqueue anything",
                workBefore, PendingRequestStore.active(context).size());
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-safety");
        for (AssistantClient.History h : chat.messages) {
            assertFalse("no status text may ever be persisted as a message",
                    h.content != null && h.content.contains("Thinking"));
        }
        assertEquals("no assistant message may exist yet", 1, chat.messages.size());
        PendingRequestStore.Item after = PendingRequestStore.load(context, item.id);
        assertFalse("progress must never claim a completion", after.committed);
        assertEquals(PendingRequestStore.QUEUED, after.status);
    }

    /** Reattaching after an Activity recreation is a no-op on the request itself. */
    @Test public void reattachingAListenerDoesNotDuplicateTheRequest() {
        PendingRequestStore.Item item = queue("c-recreate");
        Watcher first = new Watcher();
        OrbitRequestManager.addListener(item.id, first);
        OrbitRequestManager.removeListener(item.id, first);

        // The screen is rebuilt and attaches a new listener to the same request id.
        Watcher second = new Watcher();
        OrbitRequestManager.addListener(item.id, second);
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));

        assertEquals("only the live listener hears anything", 0, first.seen.size());
        assertEquals(1, second.seen.size());
        assertEquals("still exactly one request", 1, PendingRequestStore.active(context).size());
    }

    /** Two surfaces on one request is normal, and still one request. */
    @Test public void overlayAndFullChatMaySharedOneRequestsUpdates() {
        PendingRequestStore.Item item = queue("c-both");
        Watcher overlay = new Watcher();
        Watcher fullChat = new Watcher();
        OrbitRequestManager.addListener(item.id, overlay);
        OrbitRequestManager.addListener(item.id, fullChat);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("Comparing the approaches"));

        assertEquals(1, overlay.seen.size());
        assertEquals(1, fullChat.seen.size());
        assertEquals(overlay.seen, fullChat.seen);
        assertEquals("still exactly one request", 1, PendingRequestStore.active(context).size());
    }

    /** A listener that throws must not take the request down with it. */
    @Test public void aMisbehavingListenerCannotBreakTheRequest() {
        PendingRequestStore.Item item = queue("c-throwing");
        OrbitRequestManager.addListener(item.id, new OrbitRequestManager.Listener() {
            @Override public void onThinking(String requestId, ThinkingUpdate update) {
                throw new IllegalStateException("a surface failed while rendering a status");
            }
        });
        Watcher healthy = new Watcher();
        OrbitRequestManager.addListener(item.id, healthy);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));

        assertEquals("the other surface still gets its update", 1, healthy.seen.size());
        assertEquals(PendingRequestStore.QUEUED, PendingRequestStore.load(context, item.id).status);
    }
}

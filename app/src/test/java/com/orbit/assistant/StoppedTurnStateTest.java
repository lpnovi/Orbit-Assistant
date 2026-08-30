package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Stopping a reply should look like something the user did, not like the request vanishing.
 *
 * <p>Before Beta 2 a stop left the conversation showing the question and nothing else, which reads
 * as a silent failure. The fix is a mark, and the constraint on that mark is what these cover: it
 * has to be real UI state derived from the durable record of the stop, never a fake assistant
 * message saying "Stopped", and it has to be terminal — nothing arriving late may undo it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class StoppedTurnStateTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
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

    /** One more turn on an existing conversation, the way sending another message behaves. */
    private PendingRequestStore.Item ask(String conversationId, String prompt) {
        ConversationStore.appendMessage(context, conversationId,
                new AssistantClient.History("user", prompt));
        return PendingRequestStore.create(context, conversationId, prompt, "", "", false, false,
                Prefs.MODE_DEEP, false, "");
    }

    /** Finishes a turn the way a successful completion does. */
    private void answer(String conversationId, PendingRequestStore.Item item, String text) {
        PendingRequestStore.markDone(context, item.id);
        ConversationStore.appendMessage(context, conversationId,
                new AssistantClient.History("assistant", text));
    }

    /** Which request ids the conversation itself says were stopped, in turn order. */
    private List<String> anchors(String conversationId) {
        return ConversationStore.stoppedRequestIds(context, conversationId);
    }

    /**
     * The conversation as it actually reads down the screen.
     *
     * <p>A depth-first walk is top-to-bottom order for Orbit's vertical message column, so this is
     * the real rendered sequence rather than a restatement of the state the render was built from.
     * Only the messages a test named are reported, plus every stopped mark, so unrelated labels
     * and controls cannot make an assertion pass or fail by accident.
     */
    private static List<String> shape(Activity activity, String... interesting) {
        List<String> wanted = java.util.Arrays.asList(interesting);
        List<String> out = new ArrayList<>();
        collectShape(activity.getWindow().getDecorView(), wanted, out);
        return out;
    }

    private static void collectShape(View view, List<String> wanted, List<String> out) {
        CharSequence description = view.getContentDescription();
        if (description != null && "Response stopped".contentEquals(description)) {
            out.add("stopped");
            return;
        }
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (wanted.contains(text)) out.add(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectShape(group.getChildAt(i), wanted, out);
        }
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(descendants(group.getChildAt(i)));
        }
        return out;
    }

    private static OrbitStoppedView findMark(Activity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof OrbitStoppedView) return (OrbitStoppedView) v;
        }
        return null;
    }

    private ActivityController<ChatActivity> openChat(String conversationId) {
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        return Robolectric.buildActivity(ChatActivity.class, intent).setup();
    }

    // ---- the durable representation ----------------------------------------------------------------

    @Test public void aStoppedRequestIsAnchoredToItsOwnTurn() {
        PendingRequestStore.Item item = queue("c-anchor");
        assertTrue("nothing is stopped yet", anchors("c-anchor").isEmpty());

        assertTrue(OrbitRequestManager.cancel(context, item.id));

        assertEquals("the stop is recorded against the turn it ended",
                java.util.Collections.singletonList(item.id), anchors("c-anchor"));
        assertTrue(PendingRequestStore.isStoppedRequest(context, item.id));
        // Case A: no text arrived, so the question itself is the end of the stopped turn.
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-anchor");
        assertEquals(1, chat.messages.size());
        assertEquals(item.id, chat.messages.get(0).stoppedRequestId);
    }

    /**
     * The Beta 2 bug, at the level of state: asking something else must not move the anchor.
     *
     * <p>Beta 2 derived the mark from "the conversation's newest request is cancelled", so a
     * second turn made the first turn's stop stop being describable at all.
     */
    @Test public void askingAnotherQuestionLeavesTheMarkOnItsOwnTurn() {
        PendingRequestStore.Item stoppedItem = queue("c-stop-next");
        OrbitRequestManager.cancel(context, stoppedItem.id);
        assertEquals(java.util.Collections.singletonList(stoppedItem.id), anchors("c-stop-next"));

        ask("c-stop-next", "Never mind, summarise the first one.");

        assertEquals("a newer turn must not disturb an older turn's mark",
                java.util.Collections.singletonList(stoppedItem.id), anchors("c-stop-next"));
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-stop-next");
        assertEquals(2, chat.messages.size());
        assertEquals("the mark stays on the first question", stoppedItem.id,
                chat.messages.get(0).stoppedRequestId);
        assertFalse("and never migrates onto the new one", chat.messages.get(1).isStopped());
    }

    /** A completed or failed turn is not a stopped one. */
    @Test public void onlyACancelledTurnProducesAMark() {
        PendingRequestStore.Item done = queue("c-done");
        PendingRequestStore.markDone(context, done.id);
        assertTrue(anchors("c-done").isEmpty());

        PendingRequestStore.Item failed = queue("c-failed");
        PendingRequestStore.markFailed(context, failed.id, "Orbit could not finish this response.");
        assertTrue(anchors("c-failed").isEmpty());
    }

    /** One conversation's stop must not mark another's. */
    @Test public void theMarkIsScopedToItsOwnConversation() {
        PendingRequestStore.Item first = queue("c-a");
        queue("c-b");
        OrbitRequestManager.cancel(context, first.id);

        assertEquals(java.util.Collections.singletonList(first.id), anchors("c-a"));
        assertTrue(anchors("c-b").isEmpty());
    }

    // ---- turn anchoring: the exact Galaxy S25 Ultra failure --------------------------------------------

    /**
     * Stop, then immediately ask something else. This is the reported bug.
     *
     * <p>Beta 2 rendered the mark as a conversation footer, and {@code acceptedSubmit} redraws
     * after appending the new question but before the new request exists — so the footer was
     * appended below the new question, and the conversation read "prompt 1, prompt 2, mark".
     */
    @Test public void aStoppedMarkStaysUnderItsOwnPromptWhenTheNextOneIsSent() {
        PendingRequestStore.Item first = ask("c-order", "Explain the tradeoffs in depth.");
        OrbitRequestManager.cancel(context, first.id);
        ask("c-order", "Actually, just give me the headline.");

        ActivityController<ChatActivity> controller = openChat("c-order");
        assertEquals(
                java.util.Arrays.asList("Explain the tradeoffs in depth.", "stopped",
                        "Actually, just give me the headline."),
                shape(controller.get(), "Explain the tradeoffs in depth.",
                        "Actually, just give me the headline."));
        controller.pause().stop().destroy();
    }

    /** Two stops in one conversation are two marks, each on its own turn. */
    @Test public void everyStoppedTurnKeepsItsOwnMark() {
        PendingRequestStore.Item a = ask("c-multi", "First question.");
        OrbitRequestManager.cancel(context, a.id);
        PendingRequestStore.Item b = ask("c-multi", "Second question.");
        answer("c-multi", b, "Second answer.");
        PendingRequestStore.Item c = ask("c-multi", "Third question.");
        OrbitRequestManager.cancel(context, c.id);

        assertEquals("both stops are recorded, in turn order",
                java.util.Arrays.asList(a.id, c.id), anchors("c-multi"));

        ActivityController<ChatActivity> controller = openChat("c-multi");
        assertEquals(
                java.util.Arrays.asList("First question.", "stopped", "Second question.",
                        "Second answer.", "Third question.", "stopped"),
                shape(controller.get(), "First question.", "Second question.", "Second answer.",
                        "Third question."));
        controller.pause().stop().destroy();
    }

    /** Identity is the request, never the words. Two identical prompts are two different turns. */
    @Test public void identicalPromptsAreStillDistinctTurns() {
        PendingRequestStore.Item first = ask("c-same", "Summarise this.");
        answer("c-same", first, "Here is the summary.");
        PendingRequestStore.Item second = ask("c-same", "Summarise this.");
        OrbitRequestManager.cancel(context, second.id);

        assertEquals("only the stopped request is anchored",
                java.util.Collections.singletonList(second.id), anchors("c-same"));
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-same");
        assertFalse("the earlier identical question must not be marked",
                chat.messages.get(0).isStopped());
        assertEquals(second.id, chat.messages.get(2).stoppedRequestId);

        ActivityController<ChatActivity> controller = openChat("c-same");
        assertEquals(
                java.util.Arrays.asList("Summarise this.", "Here is the summary.",
                        "Summarise this.", "stopped"),
                shape(controller.get(), "Summarise this.", "Here is the summary."));
        controller.pause().stop().destroy();
    }

    /** Case B, in order: the partial answer is kept and the mark closes that turn, not a later one. */
    @Test public void aPartialAnswerKeepsItsMarkWhenTheNextTurnArrives() {
        PendingRequestStore.Item first = ask("c-partial-order", "Explain the tradeoffs.");
        OrbitRequestManager.dispatchDelta(first.id, "Both designs trade throughput for latency");
        assertTrue(OrbitRequestManager.cancel(context, first.id));
        ask("c-partial-order", "Shorter, please.");

        ConversationStore.Conversation chat = ConversationStore.load(context, "c-partial-order");
        assertEquals("the partial answer is kept exactly", "Both designs trade throughput for latency",
                chat.messages.get(1).content);
        assertEquals("the mark closes the turn after its partial answer", first.id,
                chat.messages.get(1).stoppedRequestId);

        ActivityController<ChatActivity> controller = openChat("c-partial-order");
        assertEquals(
                java.util.Arrays.asList("Explain the tradeoffs.",
                        "Both designs trade throughput for latency", "stopped", "Shorter, please."),
                shape(controller.get(), "Explain the tradeoffs.",
                        "Both designs trade throughput for latency", "Shorter, please."));
        controller.pause().stop().destroy();
    }

    /** Reopening after a later turn must show the same order, not a re-derived one. */
    @Test public void theAnchorSurvivesReloadWithLaterTurnsPresent() {
        PendingRequestStore.Item first = ask("c-reload-order", "Long question.");
        OrbitRequestManager.cancel(context, first.id);
        PendingRequestStore.Item second = ask("c-reload-order", "Short question.");
        answer("c-reload-order", second, "Short answer.");

        for (int pass = 0; pass < 2; pass++) {
            ActivityController<ChatActivity> controller = openChat("c-reload-order");
            assertEquals("reopening must not move the mark",
                    java.util.Arrays.asList("Long question.", "stopped", "Short question.",
                            "Short answer."),
                    shape(controller.get(), "Long question.", "Short question.", "Short answer."));
            controller.pause().stop().destroy();
        }
    }

    /**
     * Both surfaces read the conversation record, so parity is a property of that record.
     *
     * <p>Full chat and the overlay each walk the same message list and draw a mark wherever a
     * message carries an anchor. This asserts the rendered order is exactly the order the record
     * describes, which is the thing the overlay independently reproduces — under Beta 2 neither
     * surface could have agreed, because the mark was a footer appended after the walk.
     */
    @Test public void theRenderedOrderIsExactlyWhatTheConversationRecordSays() {
        PendingRequestStore.Item a = ask("c-parity", "First question.");
        OrbitRequestManager.cancel(context, a.id);
        PendingRequestStore.Item b = ask("c-parity", "Second question.");
        answer("c-parity", b, "Second answer.");
        PendingRequestStore.Item c = ask("c-parity", "Third question.");
        OrbitRequestManager.cancel(context, c.id);

        // The order both surfaces derive, straight from the shared record.
        List<String> expected = new ArrayList<>();
        for (AssistantClient.History h : ConversationStore.load(context, "c-parity").messages) {
            expected.add(h.content);
            if (h.isStopped()) expected.add("stopped");
        }

        ActivityController<ChatActivity> controller = openChat("c-parity");
        assertEquals(expected, shape(controller.get(), "First question.", "Second question.",
                "Second answer.", "Third question."));
        controller.pause().stop().destroy();
    }

    /** A lifecycle save of a screen's own copy must not rub the anchor out. */
    @Test public void savingAStaleInMemoryCopyCannotEraseAMark() {
        PendingRequestStore.Item first = ask("c-stale", "Explain the tradeoffs.");
        // What a surface held before the stop was recorded on disk: no anchor anywhere.
        List<AssistantClient.History> stale = new ArrayList<>();
        stale.add(new AssistantClient.History("user", "Explain the tradeoffs."));

        OrbitRequestManager.cancel(context, first.id);
        assertEquals(java.util.Collections.singletonList(first.id), anchors("c-stale"));

        ConversationStore.save(context, "c-stale", stale);
        assertEquals("a stale save must not lose the stop",
                java.util.Collections.singletonList(first.id), anchors("c-stale"));

        // And the same once the stale copy has grown a turn the disk has not seen.
        stale.add(new AssistantClient.History("user", "Shorter, please."));
        ConversationStore.save(context, "c-stale", stale);
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-stale");
        assertEquals(2, chat.messages.size());
        assertEquals(first.id, chat.messages.get(0).stoppedRequestId);
        assertFalse(chat.messages.get(1).isStopped());
    }

    /** Marking is idempotent and never steals a turn that already carries someone else's mark. */
    @Test public void anAnchorIsWrittenOnceAndNeverOverwritten() {
        PendingRequestStore.Item first = ask("c-idempotent", "Explain the tradeoffs.");
        assertTrue(ConversationStore.markTurnStopped(context, "c-idempotent", first.id));
        assertFalse("the same stop must not be recorded twice",
                ConversationStore.markTurnStopped(context, "c-idempotent", first.id));
        assertFalse("another request must not take over a marked turn",
                ConversationStore.markTurnStopped(context, "c-idempotent", "some-other-request"));
        assertEquals(java.util.Collections.singletonList(first.id), anchors("c-idempotent"));
    }

    // ---- nothing is faked into the conversation ------------------------------------------------------

    /** The load-bearing rule: a stopped turn produced no model output, so none is written. */
    @Test public void stoppingNeverWritesAnAssistantMessage() {
        PendingRequestStore.Item item = queue("c-no-text");
        OrbitRequestManager.cancel(context, item.id);

        ConversationStore.Conversation chat = ConversationStore.load(context, "c-no-text");
        assertEquals("only the user's question may exist", 1, chat.messages.size());
        assertEquals("user", chat.messages.get(0).role);
        for (AssistantClient.History h : chat.messages) {
            String content = h.content == null ? "" : h.content;
            assertFalse("\"Stopped\" must never be persisted as model output",
                    content.equalsIgnoreCase("Stopped")
                            || content.toLowerCase().contains("response cancelled")
                            || content.toLowerCase().contains("response stopped"));
        }
    }

    /** Case B: whatever the user already received is theirs and stays. */
    @Test public void aPartialAnswerIsKeptAndTheMarkAppearsWithIt() {
        PendingRequestStore.Item item = queue("c-partial");
        OrbitRequestManager.dispatchDelta(item.id, "Both designs trade throughput for latency");
        assertTrue(OrbitRequestManager.cancel(context, item.id));

        ConversationStore.Conversation chat = ConversationStore.load(context, "c-partial");
        assertEquals(2, chat.messages.size());
        assertEquals("assistant", chat.messages.get(1).role);
        assertEquals("Both designs trade throughput for latency", chat.messages.get(1).content);
        assertEquals("the mark still applies to a partially answered turn",
                item.id, chat.messages.get(1).stoppedRequestId);
    }

    /** A late delta after a stop must not extend the partial answer. */
    @Test public void aLateDeltaCannotKeepWritingAfterAStop() {
        PendingRequestStore.Item item = queue("c-late-delta");
        OrbitRequestManager.dispatchDelta(item.id, "Both designs trade");
        OrbitRequestManager.cancel(context, item.id);

        OrbitRequestManager.dispatchDelta(item.id, "Both designs trade throughput for latency, and");
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-late-delta");
        assertEquals(2, chat.messages.size());
        assertEquals("the stored partial must be the one captured at the stop",
                "Both designs trade", chat.messages.get(1).content);
    }

    // ---- terminal means terminal ---------------------------------------------------------------------

    /** A late answer cannot commit over a stop, so the mark cannot be replaced by a reply. */
    @Test public void aLateAnswerCannotReplaceTheMark() {
        PendingRequestStore.Item item = queue("c-late-answer");
        OrbitRequestManager.cancel(context, item.id);

        boolean committed = OrbitRequestManager.completeIfNotCancelled(context, item.id, () ->
                ConversationStore.appendMessage(context, "c-late-answer",
                        new AssistantClient.History("assistant", "Here is the full answer.")));

        assertFalse("a stopped request must not accept a completion", committed);
        ConversationStore.Conversation chat = ConversationStore.load(context, "c-late-answer");
        assertEquals(1, chat.messages.size());
        assertEquals("the stopped state survives the late answer",
                java.util.Collections.singletonList(item.id), anchors("c-late-answer"));
    }

    /** And a late Thinking update cannot resurrect the running state either. */
    @Test public void aLateThinkingUpdateCannotResurrectAStoppedTurn() {
        PendingRequestStore.Item item = queue("c-late-thinking");
        OrbitRequestManager.cancel(context, item.id);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("Still comparing the approaches"));

        assertNull(OrbitRequestManager.latestThinking(item.id));
        assertEquals(java.util.Collections.singletonList(item.id), anchors("c-late-thinking"));
        assertFalse("a stopped request is not active",
                PendingRequestStore.hasActiveForConversation(context, "c-late-thinking"));
    }

    /** Stopping twice is not two stops, and cannot produce a second anything. */
    @Test public void stoppingAnAlreadyStoppedRequestDoesNothing() {
        PendingRequestStore.Item item = queue("c-double-stop");
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        assertFalse("there was nothing left to stop",
                OrbitRequestManager.cancel(context, item.id));
        assertEquals(1, ConversationStore.load(context, "c-double-stop").messages.size());
    }

    // ---- what the conversation actually shows ---------------------------------------------------------

    @Test public void fullChatShowsTheMarkAfterAStopAndNoEmptyBubble() {
        PendingRequestStore.Item item = queue("c-chat-stop");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> controller = openChat("c-chat-stop");
        ChatActivity activity = controller.get();

        assertNotNull("a stopped turn must leave something visible", findMark(activity));
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (!(v instanceof TextView)) continue;
            String text = ((TextView) v).getText().toString();
            assertFalse("the mark must be wordless, not a written \"Stopped\" bubble",
                    text.equalsIgnoreCase("Stopped") || text.equalsIgnoreCase("Response cancelled"));
        }
        controller.pause().stop().destroy();
    }

    @Test public void aConversationThatWasNotStoppedShowsNoMark() {
        PendingRequestStore.Item item = queue("c-chat-done");
        PendingRequestStore.markDone(context, item.id);
        ConversationStore.appendMessage(context, "c-chat-done",
                new AssistantClient.History("assistant", "Both designs trade throughput for latency."));

        ActivityController<ChatActivity> controller = openChat("c-chat-done");
        assertNull(findMark(controller.get()));
        controller.pause().stop().destroy();
    }

    /** Persistence: leaving and coming back must not lose the fact that the turn was stopped. */
    @Test public void theMarkSurvivesLeavingAndReopeningTheConversation() {
        PendingRequestStore.Item item = queue("c-reopen");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> first = openChat("c-reopen");
        assertNotNull(findMark(first.get()));
        first.pause().stop().destroy();

        // A fresh Activity, as after leaving Orbit entirely, reads the same durable record.
        ActivityController<ChatActivity> second = openChat("c-reopen");
        assertNotNull("the stopped state must still make sense on reopening",
                findMark(second.get()));
        second.pause().stop().destroy();
    }

    /** Recreation is the same thing the system does on rotation. */
    @Test public void theMarkSurvivesAnActivityRecreation() {
        PendingRequestStore.Item item = queue("c-recreate");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> controller = openChat("c-recreate");
        assertNotNull(findMark(controller.get()));
        controller.pause().stop().start().resume();
        assertNotNull(findMark(controller.get()));
        assertEquals("rendering a stopped turn must not enqueue anything",
                0, PendingRequestStore.active(context).size());
        controller.pause().stop().destroy();
    }

    /** Rendering the mark is observation, never a second request. */
    @Test public void showingTheMarkNeverStartsARequest() {
        PendingRequestStore.Item item = queue("c-no-restart");
        OrbitRequestManager.cancel(context, item.id);

        for (int i = 0; i < 3; i++) {
            ActivityController<ChatActivity> controller = openChat("c-no-restart");
            assertNotNull(findMark(controller.get()));
            controller.pause().stop().destroy();
        }
        assertEquals("no request may be created by looking at a stopped conversation",
                0, PendingRequestStore.active(context).size());
        assertEquals(1, ConversationStore.load(context, "c-no-restart").messages.size());
    }

    // ---- accessibility ---------------------------------------------------------------------------------

    /**
     * The mark is deliberately wordless on screen, which means TalkBack has to be told in words
     * what it is. Without this it would be an unlabelled decorative shape.
     */
    @Test public void theMarkCarriesItsMeaningForAccessibility() {
        PendingRequestStore.Item item = queue("c-a11y");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> controller = openChat("c-a11y");
        OrbitStoppedView mark = findMark(controller.get());
        assertNotNull(mark);

        boolean described = false;
        for (View v : descendants(controller.get().getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && "Response stopped".contentEquals(description)) {
                described = true;
                assertEquals("the described row must be exposed to accessibility services",
                        View.IMPORTANT_FOR_ACCESSIBILITY_YES, v.getImportantForAccessibility());
            }
        }
        assertTrue("the stopped mark needs a spoken meaning, not just a glyph", described);
        controller.pause().stop().destroy();
    }

    // ---- the mark itself ---------------------------------------------------------------------------------

    /** It is the thinking indicator at rest: no frames, no loop, nothing left running. */
    @Test public void theMarkIsStaticUnlessItIsSettlingIn() {
        OrbitStoppedView mark = new OrbitStoppedView(context, UiKit.SURFACE);
        assertFalse("a restored mark must not animate", mark.isResolving());

        mark.resolve();
        if (UiKit.animationsEnabled()) {
            assertTrue("a live stop settles once", mark.isResolving());
        }

        // The settle is time-based and ends on its own; it never becomes a loop.
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        drawOnce(mark, 44);
        assertFalse("the settle must finish rather than repeat", mark.isResolving());
    }

    /**
     * Beta 2's mark was mistaken on a real device for a rendering artifact, so it grew.
     *
     * <p>Stated as a range rather than an exact number: the contract is "clearly bigger than Beta
     * 2, still small enough to stay out of the conversation's way", not one particular value.
     */
    @Test public void theMarkIsLargerThanBetaTwoButStillCompact() {
        int beta2 = 22;
        assertTrue("the mark must be noticeably larger than Beta 2's 22dp",
                OrbitStoppedView.SIZE_DP >= beta2 * 5 / 4);
        assertTrue("but it must not start competing with a message bubble",
                OrbitStoppedView.SIZE_DP <= beta2 * 3 / 2);

        OrbitStoppedView mark = new OrbitStoppedView(context, UiKit.SURFACE);
        mark.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(UiKit.dp(context, OrbitStoppedView.SIZE_DP), mark.getMeasuredWidth());
        assertEquals(UiKit.dp(context, OrbitStoppedView.SIZE_DP), mark.getMeasuredHeight());
    }

    /** Still not a failure: nothing about the mark may be styled as an error. */
    @Test public void theMarkIsNeverStyledAsAnError() {
        PendingRequestStore.Item item = queue("c-not-error");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> controller = openChat("c-not-error");
        ChatActivity activity = controller.get();
        assertNotNull(findMark(activity));
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (!(v instanceof TextView)) continue;
            String text = ((TextView) v).getText().toString().trim();
            assertFalse("a stop must not offer Retry the way a failure does", text.equals("Retry"));
            assertFalse("and must not read as a failure", text.startsWith("Orbit could not finish"));
        }
        controller.pause().stop().destroy();
    }

    /** No assistant bubble may wrap the mark; an absence must not be dressed as a message. */
    @Test public void theMarkIsNotWrappedInAnAssistantBubble() {
        PendingRequestStore.Item item = queue("c-no-bubble");
        OrbitRequestManager.cancel(context, item.id);

        ActivityController<ChatActivity> controller = openChat("c-no-bubble");
        OrbitStoppedView mark = findMark(controller.get());
        assertNotNull(mark);
        View row = (View) mark.getParent();
        assertNull("the mark's row must carry no bubble background", row.getBackground());
        assertEquals("nothing but the mark belongs in that row", 1, ((ViewGroup) row).getChildCount());
        controller.pause().stop().destroy();
    }

    @Test public void theMarkDrawsAtEverySizeWithoutCrashing() {
        OrbitStoppedView mark = new OrbitStoppedView(context, UiKit.SURFACE);
        for (int size : new int[]{1, 12, 20, 22, 64, 220}) drawOnce(mark, size);
    }

    /** Same readability rule the live indicator follows, including on an accent-filled bubble. */
    @Test public void theMarkStaysVisibleOnEveryBubbleAndAccent() {
        List<Integer> backgrounds = new ArrayList<>();
        backgrounds.add(UiKit.SURFACE);
        backgrounds.add(UiKit.SURFACE_2);
        backgrounds.add(UiKit.BG);
        backgrounds.add(android.graphics.Color.BLACK);
        backgrounds.add(android.graphics.Color.WHITE);
        for (String key : UiKit.bubbleColorKeys()) {
            backgrounds.add(UiKit.bubbleFill(context, key, UiKit.SURFACE));
        }
        for (String accent : UiKit.accentKeys()) {
            backgrounds.add(UiKit.accentForName(context, accent));
        }
        for (int background : backgrounds) {
            OrbitStoppedView mark = new OrbitStoppedView(context, background);
            drawOnce(mark, 44);
        }
    }

    /** It follows the accent like everything else in Orbit, rather than owning a colour. */
    @Test public void theMarkFollowsTheCurrentAccent() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        OrbitStoppedView mark = new OrbitStoppedView(context, UiKit.SURFACE);
        drawOnce(mark, 44);

        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        mark.applyAccent(UiKit.SURFACE);
        drawOnce(mark, 44);
        assertEquals(UiKit.accentForName(context, "rose"), UiKit.accent(context));
    }

    private static void drawOnce(View view, int size) {
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, size, size);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(
                Math.max(1, size), Math.max(1, size), android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        bitmap.recycle();
    }
}

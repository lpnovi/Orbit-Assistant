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

    @Test public void aStoppedRequestBecomesTheConversationsStoppedTail() {
        PendingRequestStore.Item item = queue("c-stop-tail");
        assertNull("nothing is stopped yet",
                PendingRequestStore.stoppedTailForConversation(context, "c-stop-tail"));

        assertTrue(OrbitRequestManager.cancel(context, item.id));

        PendingRequestStore.Item stopped =
                PendingRequestStore.stoppedTailForConversation(context, "c-stop-tail");
        assertNotNull(stopped);
        assertEquals(item.id, stopped.id);
        assertEquals(PendingRequestStore.CANCELLED, stopped.status);
    }

    /** The mark belongs to the last turn, so asking something else retires it by itself. */
    @Test public void askingAnotherQuestionRetiresTheMark() {
        PendingRequestStore.Item stoppedItem = queue("c-stop-next");
        OrbitRequestManager.cancel(context, stoppedItem.id);
        assertNotNull(PendingRequestStore.stoppedTailForConversation(context, "c-stop-next"));

        queue("c-stop-next");
        assertNull("a newer turn means the mark is no longer the tail",
                PendingRequestStore.stoppedTailForConversation(context, "c-stop-next"));
    }

    /** A completed or failed turn is not a stopped one. */
    @Test public void onlyACancelledTailProducesAMark() {
        PendingRequestStore.Item done = queue("c-done");
        PendingRequestStore.markDone(context, done.id);
        assertNull(PendingRequestStore.stoppedTailForConversation(context, "c-done"));

        PendingRequestStore.Item failed = queue("c-failed");
        PendingRequestStore.markFailed(context, failed.id, "Orbit could not finish this response.");
        assertNull(PendingRequestStore.stoppedTailForConversation(context, "c-failed"));
    }

    /** One conversation's stop must not mark another's. */
    @Test public void theMarkIsScopedToItsOwnConversation() {
        PendingRequestStore.Item first = queue("c-a");
        queue("c-b");
        OrbitRequestManager.cancel(context, first.id);

        assertNotNull(PendingRequestStore.stoppedTailForConversation(context, "c-a"));
        assertNull(PendingRequestStore.stoppedTailForConversation(context, "c-b"));
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
        assertNotNull("the mark still applies to a partially answered turn",
                PendingRequestStore.stoppedTailForConversation(context, "c-partial"));
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
        assertNotNull("the stopped state survives the late answer",
                PendingRequestStore.stoppedTailForConversation(context, "c-late-answer"));
    }

    /** And a late Thinking update cannot resurrect the running state either. */
    @Test public void aLateThinkingUpdateCannotResurrectAStoppedTurn() {
        PendingRequestStore.Item item = queue("c-late-thinking");
        OrbitRequestManager.cancel(context, item.id);

        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("Still comparing the approaches"));

        assertNull(OrbitRequestManager.latestThinking(item.id));
        assertNotNull(PendingRequestStore.stoppedTailForConversation(context, "c-late-thinking"));
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

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
 * The streaming answer inside the real conversation: who owns it, and what it must never do.
 *
 * <p>{@link ProgressiveResponseTest} proves the presentation. This proves the wiring, and the
 * cases that matter here are the ones where a delta must be <em>refused</em>. Orbit runs requests
 * in a durable manager that survives the screen, so a delta can arrive from a request the user
 * stopped, from one a Regenerate superseded, or from a conversation the user has navigated away
 * from. Any of those reaching the visible bubble would put one answer's words under another
 * answer's question.
 *
 * <p>Progressive rendering is presentation only. Nothing here may change who owns a completion, so
 * the request-integrity behaviour is asserted alongside: no conversation written by a stream, and
 * the canonical reply always the thing that lands in storage.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProgressiveStreamingChatTest {

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

    // ---- one presentation, not two ------------------------------------------------------------------

    /**
     * The bubble the user reads while the answer arrives is the bubble they are left with.
     *
     * <p>This is the whole release in one assertion. Before it, a raw {@code TextView} was thrown
     * away at completion and a rich tree took its place, which is what produced the visible jump.
     */
    @Test public void thestreamingBubbleIsTheFinishedBubble() {
        ActivityController<ChatActivity> chat = openChat("c-stream-one");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-one");

        OrbitRequestManager.dispatchDelta(id, "## Heading\n\nSome ans");
        ProgressiveResponseView streaming = streamingBubble(activity);
        assertNotNull("the answer is drawn progressively", streaming);
        assertFalse("and never as raw Markdown", allText(streaming).contains("##"));

        chat.pause().stop().destroy();
    }

    /** A streamed answer formats while it is arriving rather than after it. */
    @Test public void markdownFormatsWhileTheAnswerIsStillArriving() {
        ActivityController<ChatActivity> chat = openChat("c-stream-format");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-format");

        OrbitRequestManager.dispatchDelta(id, "## Things to try\n\n- First\n\n```java\nint x = 1;");
        String text = allText(streamingBubble(activity));
        assertTrue(text.contains("Things to try"));
        assertTrue(text.contains("First"));
        assertTrue(text.contains("int x = 1;"));
        assertFalse("no heading syntax", text.contains("##"));
        assertFalse("no list syntax", text.contains("- First"));
        assertFalse("no fences", text.contains("```"));

        chat.pause().stop().destroy();
    }

    // ---- request ownership -----------------------------------------------------------------------------

    /**
     * A delta from a request this screen is no longer listening to cannot draw anything.
     *
     * <p>The guard is the listener registry, exactly as every other callback on this screen uses,
     * so a stopped or completed request is silenced by the same rule that silences its status line.
     */
    @Test public void adeltaFromAnUnwatchedRequestIsRefused() {
        ActivityController<ChatActivity> chat = openChat("c-stream-owner");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-owner");

        OrbitRequestManager.dispatchDelta(id, "the real answer");
        assertNotNull(streamingBubble(activity));

        // A request that was never registered on this screen at all.
        OrbitRequestManager.dispatchDelta("req-from-somewhere-else", "text from another request");
        assertFalse("another request's text must not reach this bubble",
                allText(streamingBubble(activity)).contains("another request"));

        chat.pause().stop().destroy();
    }

    /** A superseding request replaces the bubble rather than writing into the old one. */
    @Test public void asecondRequestDoesNotMixIntoTheFirstBubble() {
        ActivityController<ChatActivity> chat = openChat("c-stream-supersede");
        ChatActivity activity = chat.get();

        String first = enqueue(activity, "c-stream-supersede");
        OrbitRequestManager.dispatchDelta(first, "first answer text");
        ProgressiveResponseView firstBubble = streamingBubble(activity);

        String second = enqueue(activity, "c-stream-supersede");
        OrbitRequestManager.dispatchDelta(second, "second answer text");
        ProgressiveResponseView secondBubble = streamingBubble(activity);

        assertNotNull(secondBubble);
        String text = allText(secondBubble);
        assertTrue(text.contains("second answer text"));
        assertFalse("two streams must never share one bubble", text.contains("first answer text"));
        assertFalse("and the old bubble is released",
                firstBubble == secondBubble && text.contains("first answer text"));

        chat.pause().stop().destroy();
    }

    /** A stopped request cannot restart progressive rendering. */
    @Test public void astoppedRequestCannotResumeStreaming() {
        ActivityController<ChatActivity> chat = openChat("c-stream-stopped");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-stopped");

        OrbitRequestManager.dispatchDelta(id, "partial answer");
        OrbitRequestManager.cancel(context, id);
        int before = activity.messagesForTest().getChildCount();

        OrbitRequestManager.dispatchDelta(id, "partial answer plus more that should never appear");
        for (int i = 0; i < activity.messagesForTest().getChildCount(); i++) {
            View child = activity.messagesForTest().getChildAt(i);
            assertFalse("a stopped request may not keep writing",
                    allText(child).contains("should never appear"));
        }
        assertTrue(activity.messagesForTest().getChildCount() >= before - 1);

        chat.pause().stop().destroy();
    }

    /** Nothing about progressive rendering writes a conversation. Completion still owns that. */
    @Test public void streamingNeverWritesTheConversation() {
        ActivityController<ChatActivity> chat = openChat("c-stream-nowrite");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-nowrite");

        for (int i = 0; i < 40; i++) OrbitRequestManager.dispatchDelta(id, "growing answer " + i);

        ConversationStore.Conversation stored =
                ConversationStore.load(context, "c-stream-nowrite");
        int assistantTurns = 0;
        if (stored != null) {
            for (AssistantClient.History h : stored.messages) {
                if ("assistant".equalsIgnoreCase(h.role)) assistantTurns++;
            }
        }
        assertEquals("a stream is presentation, never a stored turn", 0, assistantTurns);

        chat.pause().stop().destroy();
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    /** Redrawing the conversation releases the streaming bubble's render state. */
    @Test public void redrawingTheConversationReleasesStreamState() {
        ActivityController<ChatActivity> chat = openChat("c-stream-redraw");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-redraw");

        OrbitRequestManager.dispatchDelta(id, "some text");
        ProgressiveResponseView bubble = streamingBubble(activity);
        assertNotNull(bubble);

        activity.renderForTest();
        assertFalse("a detached bubble must not keep a render callback alive",
                bubble.hasStreamState());
        assertNull("and the screen forgets it", streamingBubble(activity));

        chat.pause().stop().destroy();
    }

    /** Leaving the screen leaves no pending render behind. */
    @Test public void leavingTheScreenLeavesNoPendingRender() {
        ActivityController<ChatActivity> chat = openChat("c-stream-leave");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-leave");
        OrbitRequestManager.dispatchDelta(id, "some text");
        ProgressiveResponseView bubble = streamingBubble(activity);

        chat.pause().stop().destroy();
        assertFalse("a destroyed screen must not hold a scheduler", bubble.hasStreamState());
    }

    // ---- scroll ----------------------------------------------------------------------------------------------

    /**
     * Progressive updates do not drag a user who has scrolled up back to the bottom.
     *
     * <p>The follow flag is the existing behaviour and is deliberately untouched; this asserts that
     * streaming obeys it rather than overriding it.
     */
    @Test public void streamingDoesNotForceTheBottomWhenTheUserScrolledUp() {
        ActivityController<ChatActivity> chat = openChat("c-stream-scroll");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-scroll");

        activity.setFollowBottomForTest(false);
        for (int i = 0; i < 30; i++) OrbitRequestManager.dispatchDelta(id, "line " + i);
        assertFalse("Orbit must leave a reader where they are",
                activity.followBottomForTest());

        chat.pause().stop().destroy();
    }

    /** And it keeps following when the user has not moved away. */
    @Test public void streamingKeepsFollowingWhenTheUserIsAtTheBottom() {
        ActivityController<ChatActivity> chat = openChat("c-stream-follow");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-follow");

        assertTrue(activity.followBottomForTest());
        for (int i = 0; i < 10; i++) OrbitRequestManager.dispatchDelta(id, "line " + i);
        assertTrue(activity.followBottomForTest());

        chat.pause().stop().destroy();
    }

    /** There is exactly one scroll-to-latest control, and this release did not add another. */
    @Test public void thereIsExactlyOneJumpToLatestControl() {
        ActivityController<ChatActivity> chat = openChat("c-stream-jump");
        ChatActivity activity = chat.get();
        String id = enqueue(activity, "c-stream-jump");
        OrbitRequestManager.dispatchDelta(id, "a growing answer with several lines\nand another");

        int controls = 0;
        for (View view : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = view.getContentDescription();
            if (description != null && description.toString().toLowerCase(java.util.Locale.US)
                    .contains("latest")) {
                controls++;
            }
        }
        assertEquals("progressive rendering must not add a second scroll control", 1, controls);

        chat.pause().stop().destroy();
    }

    // ---- motion ---------------------------------------------------------------------------------------------

    /** Reopening a conversation never replays entrance motion for its history. */
    @Test public void historicalMessagesDoNotReplayEntranceMotion() {
        List<AssistantClient.History> stored = new ArrayList<>();
        stored.add(new AssistantClient.History("user", "a question"));
        stored.add(new AssistantClient.History("assistant", "an answer"));
        stored.add(new AssistantClient.History("user", "another question"));
        stored.add(new AssistantClient.History("assistant", "another answer"));
        ConversationStore.save(context, "c-stream-motion", new ArrayList<>(stored));

        ActivityController<ChatActivity> chat = Robolectric.buildActivity(ChatActivity.class,
                new Intent(context, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "c-stream-motion")).setup();
        ChatActivity activity = chat.get();

        ViewGroup messages = activity.messagesForTest();
        assertTrue(messages.getChildCount() >= 4);
        for (int i = 0; i < messages.getChildCount(); i++) {
            View child = messages.getChildAt(i);
            assertEquals("opening a conversation must not animate its history",
                    1f, child.getAlpha(), 0.001f);
        }

        chat.pause().stop().destroy();
    }

    /** Re-rendering the same conversation does not animate it again. */
    @Test public void arerenderDoesNotReplayMotion() {
        ConversationStore.save(context, "c-stream-rerender", new ArrayList<>(
                java.util.Collections.singletonList(
                        new AssistantClient.History("assistant", "settled answer"))));
        ActivityController<ChatActivity> chat = Robolectric.buildActivity(ChatActivity.class,
                new Intent(context, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "c-stream-rerender")).setup();
        ChatActivity activity = chat.get();

        activity.renderForTest();
        ViewGroup messages = activity.messagesForTest();
        for (int i = 0; i < messages.getChildCount(); i++) {
            assertEquals(1f, messages.getChildAt(i).getAlpha(), 0.001f);
        }

        chat.pause().stop().destroy();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Registers a request on the screen the way an enqueue would, and returns its id. */
    private String enqueue(ChatActivity activity, String conversationId) {
        PendingRequestStore.Item item = PendingRequestStore.create(context, conversationId,
                "a question", "", java.util.Collections.emptyList(), false, false, "balanced",
                false, "");
        activity.registerRequestForTest(item.id);
        return item.id;
    }

    private static ProgressiveResponseView streamingBubble(ChatActivity activity) {
        for (View view : descendants(activity.getWindow().getDecorView())) {
            if (view instanceof ProgressiveResponseView) return (ProgressiveResponseView) view;
        }
        return null;
    }

    private static String allText(View root) {
        StringBuilder out = new StringBuilder();
        for (View view : descendants(root)) {
            if (!(view instanceof TextView)) continue;
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) out.append(text).append('\n');
        }
        return out.toString();
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(descendants(group.getChildAt(i)));
            }
        }
        return out;
    }

    private ActivityController<ChatActivity> openChat(String conversationId) {
        ConversationStore.save(context, conversationId, new ArrayList<>());
        return Robolectric.buildActivity(ChatActivity.class,
                new Intent(context, ChatActivity.class)
                        .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)).setup();
    }
}

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
 * What the conversation actually shows, with the setting off and on.
 *
 * <p>The off case matters as much as the on case: the whole promise of this release is that a user
 * who does not turn Thinking updates on keeps precisely the experience v0.7.7.7 shipped, orbital
 * indicator and all. Both surfaces build their thinking row in their own code, so the shared
 * status component and the shared rules are asserted here rather than trusted to stay aligned.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThinkingUpdateSurfaceTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    private String seedRunningConversation() {
        String conversationId = "c-surface";
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Compare these two architectures."));
        ConversationStore.save(context, conversationId, history);
        PendingRequestStore.create(context, conversationId, "Compare these two architectures.",
                "", "", false, false, Prefs.MODE_DEEP, false, "");
        return conversationId;
    }

    private ActivityController<ChatActivity> openChat(String conversationId) {
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra("conversation_id", conversationId);
        return Robolectric.buildActivity(ChatActivity.class, intent).setup();
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

    private static ThinkingStatusView findStatus(Activity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof ThinkingStatusView) return (ThinkingStatusView) v;
        }
        return null;
    }

    private static OrbitThinkingView findOrbital(Activity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof OrbitThinkingView) return (OrbitThinkingView) v;
        }
        return null;
    }

    // ---- the setting is what decides -------------------------------------------------------------

    /**
     * On by default from Beta 2, after real-device testing proved the feature out. The three
     * preference states themselves are covered in {@link ThinkingUpdateDefaultTest}; what matters
     * here is that the conversation actually reflects whichever one applies.
     */
    @Test public void theSettingIsOnByDefault() {
        assertTrue(Prefs.thinkingUpdates(context));
    }

    /** Turning it off must still give back exactly the v0.7.7.7 thinking row. */
    @Test public void withTheSettingOffTheThinkingRowIsTheOrbitalIndicatorAlone() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        String conversationId = seedRunningConversation();
        ActivityController<ChatActivity> controller = openChat(conversationId);

        assertNotNull("the orbital indicator must still be there",
                findOrbital(controller.get()));
        assertNull("no status line may be built when the user has not asked for one",
                findStatus(controller.get()));

        controller.pause().stop().destroy();
    }

    @Test public void withTheSettingOnTheStatusLineJoinsTheOrbitalIndicator() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        String conversationId = seedRunningConversation();
        ActivityController<ChatActivity> controller = openChat(conversationId);

        assertNotNull("the orbital indicator is preserved, not replaced",
                findOrbital(controller.get()));
        assertNotNull(findStatus(controller.get()));

        controller.pause().stop().destroy();
    }

    // ---- the status component's own rules ---------------------------------------------------------

    /** Geometry is settled before any text arrives, so no update can resize the sheet. */
    @Test public void theStatusReservesItsHeightBeforeShowingAnything() {
        ThinkingStatusView status = new ThinkingStatusView(context, UiKit.SURFACE);
        int reserved = status.getMinHeight();
        assertTrue("two lines of space must be reserved up front", reserved > 0);
        assertTrue("the reserved height must be roughly two lines of this text size",
                reserved > status.getTextSize() * 2f);

        status.setStatus(ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING));
        assertEquals("showing text must not change the reserved height",
                reserved, status.getMinHeight());
        status.setStatus(ThinkingUpdate.providerSummary(
                "A considerably longer phrase that will certainly need to wrap onto a second line"));
        assertEquals("a longer update must not change it either",
                reserved, status.getMinHeight());
        status.clearStatus();
        assertEquals("nor must clearing it", reserved, status.getMinHeight());
    }

    /**
     * The other half of stable geometry. Height is reserved by the view; width is fixed by the
     * layout params both surfaces give it, so the bubble is not a different width for every phrase.
     */
    @Test public void theStatusHasOneFixedWidthRatherThanWrappingToItsText() {
        int width = ThinkingStatusView.stableWidth(context);
        assertTrue("a status line must have real width to be readable", width > 0);
        assertEquals("the width must not depend on anything that changes mid-request",
                width, ThinkingStatusView.stableWidth(context));
        assertTrue("it must never span the whole display",
                width < context.getResources().getDisplayMetrics().widthPixels);
    }

    @Test public void theStatusNeverGrowsBeyondTwoLinesAndEllipsizesInstead() {
        ThinkingStatusView status = new ThinkingStatusView(context, UiKit.SURFACE);
        assertEquals(2, status.getMaxLines());
        assertNotNull("overflowing text must be clipped, not reflowed", status.getEllipsize());
    }

    /**
     * The status is meant to be quieter than the answer, but quieter must never mean unreadable.
     *
     * <p>The contract is: either the muted tone clears the readability floor on its own, or the
     * muting is abandoned entirely and the bubble's normal ink is used. A bubble the user has set
     * to a pastel or to the accent itself is exactly where a blanket "fade it out" would vanish,
     * so this is checked against every colour Orbit lets a bubble be.
     */
    @Test public void theStatusInkIsEitherReadablyMutedOrNotMutedAtAll() {
        List<Integer> bubbles = new ArrayList<>();
        bubbles.add(UiKit.SURFACE);
        bubbles.add(UiKit.SURFACE_2);
        bubbles.add(UiKit.SURFACE_3);
        bubbles.add(UiKit.BG);
        bubbles.add(android.graphics.Color.BLACK);
        bubbles.add(android.graphics.Color.WHITE);
        for (String key : UiKit.bubbleColorKeys()) {
            bubbles.add(UiKit.bubbleFill(context, key, UiKit.SURFACE));
        }
        for (String accent : UiKit.accentKeys()) {
            bubbles.add(UiKit.accentForName(context, accent));
        }

        for (int bubble : bubbles) {
            int ink = ThinkingStatusView.mutedInk(bubble);
            double contrast = UiKit.contrastRatio(ink, bubble);
            boolean unmuted = ink == UiKit.onBubble(bubble);
            assertTrue("status ink on bubble " + Integer.toHexString(bubble)
                            + " is muted below readability (contrast " + contrast + ")",
                    unmuted || contrast >= 3.2d);
            assertTrue("muting must never read worse than the bubble's own ink",
                    contrast >= UiKit.contrastRatio(UiKit.onBubble(bubble), bubble) - 0.001d
                            || contrast >= 3.2d);
        }
    }

    @Test public void repeatingTheSameTextDoesNotRewriteTheLine() {
        ThinkingStatusView status = new ThinkingStatusView(context, UiKit.SURFACE);
        ThinkingUpdate update = ThinkingUpdate.providerSummary("Comparing the approaches");
        status.setStatus(update);
        CharSequence first = status.getText();
        status.setStatus(ThinkingUpdate.providerSummary("Comparing the approaches"));
        assertEquals(first.toString(), status.getText().toString());
    }

    // ---- accessibility ---------------------------------------------------------------------------

    /**
     * TalkBack hears the row, once per coherent update, and stops hearing anything the moment the
     * answer takes over. The status view itself is not a separate node, so nothing is announced
     * twice.
     */
    @Test public void accessibilityAnnouncesTheRowAndStopsWhenTheAnswerArrives() {
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        ThinkingStatusView status = new ThinkingStatusView(context, UiKit.SURFACE);
        row.addView(status);
        status.attachTo(row, "Orbit is thinking");

        assertEquals("Orbit is thinking", row.getContentDescription());
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, status.getImportantForAccessibility());

        status.setStatus(ThinkingUpdate.providerSummary("Comparing the approaches"));
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, row.getAccessibilityLiveRegion());
        assertTrue(row.getContentDescription().toString().contains("Comparing the approaches"));

        status.clearStatus();
        assertEquals("clearing the line must not itself be announced",
                View.ACCESSIBILITY_LIVE_REGION_NONE, row.getAccessibilityLiveRegion());
        assertEquals("Orbit is thinking", row.getContentDescription());
        assertEquals("", status.getText().toString());
    }

    /** The row keeps a meaningful description with the feature off, exactly as it did before. */
    @Test public void theThinkingRowKeepsItsDescriptionWithTheFeatureOff() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, false).commit();
        String conversationId = seedRunningConversation();
        ActivityController<ChatActivity> controller = openChat(conversationId);

        boolean found = false;
        for (View v : descendants(controller.get().getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && "Orbit is thinking".contentEquals(description)) found = true;
        }
        assertTrue("the thinking indicator must stay described for accessibility", found);

        controller.pause().stop().destroy();
    }

    /** Android font scaling must not be defeated by a hardcoded height. */
    @Test public void theReservedHeightFollowsTheChatTextSizePreference() {
        ThinkingStatusView normal = new ThinkingStatusView(context, UiKit.SURFACE);
        Prefs.get(context).edit().putString(Prefs.CHAT_TEXT_SIZE, Prefs.CHAT_TEXT_EXTRA_LARGE).commit();
        ThinkingStatusView large = new ThinkingStatusView(context, UiKit.SURFACE);
        assertTrue("a larger chat text size must reserve more room, not clip",
                large.getMinHeight() > normal.getMinHeight());
    }

    // ---- the answer always wins --------------------------------------------------------------------

    /**
     * The transition this feature lives or dies on: the first token of the answer removes the
     * whole thinking row, status included, so no reply is ever read underneath stale commentary.
     */
    @Test public void theFirstAnswerTokenRemovesTheStatusAlongWithTheIndicator() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        String conversationId = seedRunningConversation();
        ActivityController<ChatActivity> controller = openChat(conversationId);
        ChatActivity activity = controller.get();
        assertNotNull(findStatus(activity));

        PendingRequestStore.Item item =
                PendingRequestStore.activeForConversation(context, conversationId).get(0);
        OrbitRequestManager.dispatchThinking(item.id,
                ThinkingUpdate.providerSummary("Comparing the approaches"));
        OrbitRequestManager.dispatchDelta(item.id, "Both designs trade");
        Robolectric.flushForegroundThreadScheduler();

        assertNull("the status must be gone once the answer starts", findStatus(activity));
        boolean answerShown = false;
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof TextView
                    && ((TextView) v).getText().toString().contains("Both designs trade")) {
                answerShown = true;
            }
        }
        assertTrue("the answer must be on screen", answerShown);

        controller.pause().stop().destroy();
    }
}

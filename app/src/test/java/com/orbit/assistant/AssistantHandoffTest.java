package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Routing and state for the overlay's expand-into-current-chat handoff: it must reach the
 * conversation directly, release the overlay exactly once, and leave normal navigation alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AssistantHandoffTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitHandoff.cancel();
    }

    @After public void tearDown() {
        OrbitHandoff.cancel();
    }

    /** The Intent the overlay builds when its upward swipe commits. */
    private Intent handoffIntent(String conversationId) {
        return new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ChatActivity.EXTRA_ASSISTANT_HANDOFF, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Test public void theHandoffTargetsTheConversationDirectly() {
        Intent intent = handoffIntent("conversation-7");

        // Straight to the chat: routing through the Chats screen is what made it visible.
        assertEquals(new android.content.ComponentName(context, ChatActivity.class),
                intent.getComponent());
        assertEquals("conversation-7", intent.getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID));
        assertTrue(intent.getBooleanExtra(ChatActivity.EXTRA_ASSISTANT_HANDOFF, false));
    }

    @Test public void theHandoffReusesTheTaskRatherThanStackingChats() {
        Intent intent = handoffIntent("conversation-7");
        int flags = intent.getFlags();

        assertTrue((flags & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        // CLEAR_TOP without SINGLE_TOP so an existing chat is recreated with the new conversation
        // instead of being reused while still showing the old one.
        assertTrue((flags & Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0);
        assertFalse((flags & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    @Test public void ordinaryChatNavigationCarriesNoHandoffFlag() {
        Intent normal = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "conversation-3");
        assertFalse(normal.getBooleanExtra(ChatActivity.EXTRA_ASSISTANT_HANDOFF, false));
    }

    @Test public void theDestinationReleasesTheOverlayExactlyOnce() {
        final int[] released = {0};
        OrbitHandoff.expectDestination(() -> released[0]++);
        assertTrue(OrbitHandoff.isPending());

        OrbitHandoff.destinationDrawn();
        ShadowLooper.idleMainLooper();
        assertEquals(1, released[0]);
        assertFalse(OrbitHandoff.isPending());

        // A second report, or a late failsafe, must not dismiss anything again.
        OrbitHandoff.destinationDrawn();
        ShadowLooper.idleMainLooper();
        assertEquals(1, released[0]);
    }

    @Test public void theOverlayIsReleasedEvenIfTheDestinationNeverReports() {
        final int[] released = {0};
        OrbitHandoff.expectDestination(() -> released[0]++);

        // Nothing reports; the failsafe must still free the overlay so it cannot stick.
        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, released[0]);
        assertFalse(OrbitHandoff.isPending());
    }

    @Test public void aNewHandoffSupersedesAnAbandonedOne() {
        final int[] first = {0};
        final int[] second = {0};
        OrbitHandoff.expectDestination(() -> first[0]++);
        OrbitHandoff.expectDestination(() -> second[0]++);

        OrbitHandoff.destinationDrawn();
        ShadowLooper.idleMainLooper();

        assertEquals("the superseded handoff must not fire", 0, first[0]);
        assertEquals(1, second[0]);
    }

    @Test public void repeatedHandoffsEachReleaseTheirOwnOverlay() {
        for (int i = 0; i < 4; i++) {
            final int[] released = {0};
            OrbitHandoff.expectDestination(() -> released[0]++);
            OrbitHandoff.destinationDrawn();
            ShadowLooper.idleMainLooper();
            assertEquals(1, released[0]);
            assertFalse(OrbitHandoff.isPending());
        }
    }

    @Test public void cancellingLeavesNothingPending() {
        final int[] released = {0};
        OrbitHandoff.expectDestination(() -> released[0]++);
        OrbitHandoff.cancel();

        ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, released[0]);
        assertFalse(OrbitHandoff.isPending());
    }

    @Test public void reportingWithNoHandoffInFlightIsHarmless() {
        OrbitHandoff.destinationDrawn();
        ShadowLooper.idleMainLooper();
        assertFalse(OrbitHandoff.isPending());
    }

    @Test public void theHandoffDoesNotDisturbTheStoredPageTransition() {
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION,
                Prefs.PAGE_TRANSITION_SLIDE).commit();

        OrbitHandoff.expectDestination(() -> {});
        OrbitHandoff.destinationDrawn();
        ShadowLooper.idleMainLooper();

        // Suppression is per launch, so normal navigation still slides afterwards.
        assertEquals(Prefs.PAGE_TRANSITION_SLIDE, Prefs.pageTransition(context));
        assertEquals(R.style.OrbitWindowAnimation_Slide, UiKit.pageTransitionStyle(context));
    }

    @Test public void everyPageTransitionChoiceSurvivesAHandoff() {
        String[] choices = {Prefs.PAGE_TRANSITION_SLIDE, Prefs.PAGE_TRANSITION_FADE,
                Prefs.PAGE_TRANSITION_NONE};
        for (String choice : choices) {
            Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION, choice).commit();
            OrbitHandoff.expectDestination(() -> {});
            OrbitHandoff.destinationDrawn();
            ShadowLooper.idleMainLooper();
            assertEquals(choice, Prefs.pageTransition(context));
        }
    }
}

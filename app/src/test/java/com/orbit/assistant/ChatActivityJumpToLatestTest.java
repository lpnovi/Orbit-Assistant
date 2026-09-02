package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Full chat hosts Jump to latest in the conversation pane, never in the composer, and keeps it
 * hidden while the newest messages are still on screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatActivityJumpToLatestTest {

    @Test public void theControlIsPresentButHiddenOnAFreshChat() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);
        assertNotNull("full chat must host Jump to latest", jump);
        assertEquals("Jump to latest", jump.getContentDescription());
        assertEquals(View.GONE, jump.getVisibility());
    }

    @Test public void theControlSitsInTheConversationPaneNotTheComposer() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);
        assertTrue("the control must float over the conversation, not live in the composer",
                jump.getParent() instanceof FrameLayout);
        FrameLayout pane = (FrameLayout) jump.getParent();
        boolean hasScroll = false;
        for (int i = 0; i < pane.getChildCount(); i++) {
            if (pane.getChildAt(i) instanceof ScrollView) hasScroll = true;
        }
        assertTrue("the conversation pane must still be the scroller", hasScroll);

        EditText composer = findComposer(activity.getWindow().getDecorView());
        assertNotNull(composer);
        assertFalse("the jump control must not be inside the text field's parent row",
                isDescendant(composer.getParent(), jump));
    }

    // ---- resting opacity -----------------------------------------------------------------------

    /**
     * The visible control rests slightly translucent, so it covers less of the answer behind it.
     *
     * <p>Asserted against the named constant rather than against a number: the entrance animation,
     * the reduced-motion path and the press-release spring all have to land on the same value, and
     * a literal in any one of them is exactly how a button ends up snapping between two opacities.
     */
    @Test public void theVisibleControlRestsSlightlyTranslucent() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);

        activity.applyJumpLatestForTest(true);
        settleAnimations();

        assertEquals(View.VISIBLE, jump.getVisibility());
        assertEquals("the settled control must land on the shared resting alpha",
                ChatActivity.JUMP_LATEST_ALPHA, jump.getAlpha(), 0.001f);
        assertEquals("and must not be left mid-entrance", 0f, jump.getTranslationY(), 0.001f);
    }

    /** The chosen value is a slight translucency, not a glass effect. */
    @Test public void theRestingAlphaIsSubtle() {
        assertTrue("it must still read as a solid control",
                ChatActivity.JUMP_LATEST_ALPHA >= 0.88f);
        assertTrue("but must let the text behind it through",
                ChatActivity.JUMP_LATEST_ALPHA < 1f);
    }

    /** With animations off the control arrives at the same resting value immediately. */
    @Test public void reducedMotionLandsOnTheSameRestingAlpha() throws Exception {
        setAnimationScale(0f);
        try {
            ChatActivity activity = openChat();
            ImageButton jump = findJump(activity);
            activity.applyJumpLatestForTest(true);

            assertEquals(View.VISIBLE, jump.getVisibility());
            assertEquals("reduced motion changes the journey, never the destination",
                    ChatActivity.JUMP_LATEST_ALPHA, jump.getAlpha(), 0.001f);
            assertEquals(0f, jump.getTranslationY(), 0.001f);
        } finally {
            setAnimationScale(1f);
        }
    }

    /** Hidden still means invisible, not merely faint. */
    @Test public void theHiddenControlIsStillFullyHidden() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);
        activity.applyJumpLatestForTest(true);
        settleAnimations();
        activity.applyJumpLatestForTest(false);
        settleAnimations();

        assertEquals(View.GONE, jump.getVisibility());
        assertEquals(0f, jump.getAlpha(), 0.001f);
    }

    /** Transparency is appearance only: one control, same place, same behaviour. */
    @Test public void thereIsStillExactlyOneJumpControlAndItStillJumps() {
        ChatActivity activity = openChat();
        assertEquals("a second scroll control must never be introduced",
                1, countByDescription(activity.getWindow().getDecorView(), "Jump to latest"));

        ImageButton jump = findJump(activity);
        assertTrue("it is still a button that can be pressed", jump.hasOnClickListeners());
        assertTrue("with its touch target unchanged",
                jump.getLayoutParams().width == UiKit.dp(activity, 42)
                        && jump.getLayoutParams().height == UiKit.dp(activity, 42));

        activity.setFollowBottomForTest(false);
        jump.performClick();
        assertTrue("and tapping it still returns to the newest messages",
                activity.followBottomForTest());
    }

    /** Robolectric's own animator scale, matching how the rest of the suite disables motion. */
    private static void setAnimationScale(float scale) throws Exception {
        java.lang.reflect.Method setScale = org.robolectric.shadows.ShadowValueAnimator.class
                .getDeclaredMethod("setDurationScale", float.class);
        setScale.setAccessible(true);
        setScale.invoke(null, scale);
    }

    /** Runs the posted animation frames so an entrance has actually finished. */
    private static void settleAnimations() {
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    private static int countByDescription(View view, String description) {
        int count = 0;
        CharSequence label = view.getContentDescription();
        if (description.equals(label == null ? null : label.toString())) count++;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countByDescription(group.getChildAt(i), description);
            }
        }
        return count;
    }

    private ChatActivity openChat() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "jump-latest-test");
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        return controller.get();
    }

    private static ImageButton findJump(ChatActivity activity) {
        return (ImageButton) findByDescription(activity.getWindow().getDecorView(), "Jump to latest");
    }

    private static View findByDescription(View view, String description) {
        CharSequence label = view.getContentDescription();
        if (description.equals(label == null ? null : label.toString())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText findComposer(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findComposer(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isDescendant(android.view.ViewParent parent, View child) {
        android.view.ViewParent walk = child.getParent();
        while (walk != null) {
            if (walk == parent) return true;
            walk = walk.getParent();
        }
        return false;
    }
}

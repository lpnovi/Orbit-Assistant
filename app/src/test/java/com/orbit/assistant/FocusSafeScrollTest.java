package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The auto-scroll that was taking focus away from the composer.
 *
 * <p>A physical Samsung trace showed the editor focused at {@code response.rendered} and losing
 * focus 39 ms later, matching Orbit's 40 ms delayed scroll, then repeating 41-42 ms after each
 * inset-triggered scroll. {@code ScrollView.fullScroll(View.FOCUS_DOWN)} searches for a focusable
 * view in the scroll direction and focuses it, and Orbit appends focusable response controls just
 * before scrolling.
 *
 * <p>These tests build that arrangement — a focused editor outside the scroller, focusable
 * controls at the bottom inside it — and fail if the focus-navigation API ever comes back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class FocusSafeScrollTest {
    private Activity activity;
    private TracingEditText editor;
    private ScrollView scroll;
    private LinearLayout messages;
    private Button responseAction;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        ComposerTrace.begin("test");

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // The message list, tall enough to scroll.
        scroll = new ScrollView(activity);
        messages = new LinearLayout(activity);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(600, 400));

        // The composer sits outside the scroller, as it does on both surfaces.
        editor = new TracingEditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        root.addView(editor, new LinearLayout.LayoutParams(600, 100));

        activity.setContentView(root);
        addConversation();
        layout();
    }

    /** Fills the list with bubbles and the focusable response controls Orbit appends. */
    private void addConversation() {
        for (int i = 0; i < 12; i++) {
            View bubble = new View(activity);
            messages.addView(bubble, new LinearLayout.LayoutParams(600, 120));
        }
        // Copy / Regenerate / source / action-card controls: focusable, and last in the list.
        responseAction = new Button(activity);
        responseAction.setText("Copy");
        responseAction.setFocusable(true);
        messages.addView(responseAction, new LinearLayout.LayoutParams(300, 120));
    }

    private void layout() {
        scroll.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 600, 400);
    }

    // ---- the physical failure ----

    @Test public void autoScrollingToTheNewestMessageKeepsComposerFocus() {
        editor.requestFocus();
        assertTrue(editor.hasFocus());

        FocusSafeScroll.toBottom(scroll, false);

        assertTrue("the composer must still own focus after auto-scroll", editor.hasFocus());
        assertFalse("a response control must not steal focus", responseAction.hasFocus());
    }

    @Test public void theOldImplementationWouldHaveTakenFocus() {
        // Documents the mechanism this class replaced, so the difference is visible rather than
        // asserted. If this ever stops taking focus, the guard tests above lose their meaning.
        editor.requestFocus();
        assertTrue(editor.hasFocus());

        scroll.fullScroll(View.FOCUS_DOWN);

        assertFalse("fullScroll is focus navigation, not scrolling", editor.hasFocus());
    }

    @Test public void autoScrollStillMovesThePosition() {
        editor.requestFocus();
        scroll.scrollTo(0, 0);
        assertEquals(0, scroll.getScrollY());

        FocusSafeScroll.toBottom(scroll, false);
        assertTrue("the newest message has to actually come into view", scroll.getScrollY() > 0);
        assertEquals(FocusSafeScroll.bottomOffset(scroll), scroll.getScrollY());
    }

    @Test public void scrollingToTopIsAlsoFocusNeutral() {
        editor.requestFocus();
        FocusSafeScroll.toBottom(scroll, false);

        FocusSafeScroll.toTop(scroll, false);
        assertEquals(0, scroll.getScrollY());
        assertTrue(editor.hasFocus());
        assertFalse(responseAction.hasFocus());
    }

    @Test public void noRequestFocusIsNeededToRecover() {
        // The fix is that focus is never taken, not that it is put back afterwards.
        editor.requestFocus();
        int connectionsBefore = editor.connectionsCreated();

        FocusSafeScroll.toBottom(scroll, false);

        assertTrue(editor.hasFocus());
        assertEquals("no re-attachment should be required", connectionsBefore,
                editor.connectionsCreated());
    }

    // ---- the overlay inset path ----

    @Test public void anInsetTriggeredScrollDoesNotDisturbTheTypingLease() {
        // typing lease active, editor focused, IME visible, insets request an auto-scroll
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        FocusSafeScroll.toBottom(scroll, false);

        assertTrue("this is the exact physical failure", editor.hasFocus());
        assertTrue(ime.isTyping());
        assertFalse(responseAction.hasFocus());
    }

    @Test public void repeatedInsetScrollsNeverAccumulateFocusLoss() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        // The trace showed this repeating after every re-tap; it must be harmless every time.
        for (int i = 0; i < 5; i++) {
            FocusSafeScroll.toBottom(scroll, false);
            assertTrue("scroll " + i + " took focus", editor.hasFocus());
        }
        assertTrue(ime.isTyping());
    }

    // ---- the response render path ----

    @Test public void renderingAResponseAndScrollingKeepsComposerFocus() {
        editor.requestFocus();

        // A reply arrives: bubble, then the focusable controls, then the auto-scroll.
        View bubble = new View(activity);
        messages.addView(bubble, new LinearLayout.LayoutParams(600, 200));
        Button regenerate = new Button(activity);
        regenerate.setText("Regenerate");
        regenerate.setFocusable(true);
        messages.addView(regenerate, new LinearLayout.LayoutParams(300, 120));
        layout();

        FocusSafeScroll.toBottom(scroll, false);

        assertTrue("response rendering must not cost the composer its focus", editor.hasFocus());
        assertFalse(regenerate.hasFocus());
        assertFalse(responseAction.hasFocus());
    }

    @Test public void anActionCardDoesNotStealFocusEither() {
        editor.requestFocus();

        // Action cards carry their own controls, such as the flashlight reversal button.
        Button undo = new Button(activity);
        undo.setText("Turn off");
        undo.setFocusable(true);
        messages.addView(undo, new LinearLayout.LayoutParams(300, 120));
        layout();

        FocusSafeScroll.toBottom(scroll, false);
        assertTrue(editor.hasFocus());
        assertFalse(undo.hasFocus());
    }

    @Test public void threeConsecutiveTurnsKeepFocusThroughout() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        for (int turn = 1; turn <= 3; turn++) {
            editor.getText().append("message " + turn);
            editor.getText().clear();

            Button control = new Button(activity);
            control.setText("Copy " + turn);
            control.setFocusable(true);
            messages.addView(control, new LinearLayout.LayoutParams(300, 120));
            layout();
            FocusSafeScroll.toBottom(scroll, false);

            assertTrue("turn " + turn + " lost composer focus", editor.hasFocus());
            assertFalse("turn " + turn + " focused a response control", control.hasFocus());
            assertTrue(ime.isTyping());
        }
    }

    // ---- edges ----

    @Test public void anEmptyOrMissingScrollerIsHarmless() {
        FocusSafeScroll.toBottom(null, false);
        FocusSafeScroll.toTop(null, false);
        assertEquals(-1, FocusSafeScroll.bottomOffset(null));

        ScrollView empty = new ScrollView(activity);
        assertEquals(-1, FocusSafeScroll.bottomOffset(empty));
        FocusSafeScroll.toBottom(empty, false);
    }

    @Test public void contentShorterThanTheViewportScrollsToZero() {
        ScrollView small = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        small.addView(content, new ViewGroup.LayoutParams(600, 50));
        small.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
        small.layout(0, 0, 600, 400);

        assertEquals(0, FocusSafeScroll.bottomOffset(small));
        FocusSafeScroll.toBottom(small, false);
        assertEquals(0, small.getScrollY());
    }

    @Test public void smoothScrollingIsAlsoFocusNeutral() {
        editor.requestFocus();
        FocusSafeScroll.toBottom(scroll, true);
        assertTrue(editor.hasFocus());
        assertFalse(responseAction.hasFocus());
    }
}

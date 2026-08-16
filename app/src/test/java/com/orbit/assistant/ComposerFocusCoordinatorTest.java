package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The Side-button composer's focus and input-method coordination, driven through real views and
 * real focus callbacks.
 *
 * <p>v0.7.3.5 shipped a crash that 1134 passing tests missed, because the composer's state object
 * was tested in isolation and nothing exercised a focus event reaching the IME path. These tests
 * wire an actual {@link EditText} to an actual focus listener, so a focus-gained callback that
 * moves focus again shows up here rather than on the phone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ComposerFocusCoordinatorTest {
    private Activity activity;
    private EditText editor;
    private View holder;

    /** Records what the host surface was asked to do. */
    private static final class Bridge implements ComposerFocusCoordinator.ImeBridge {
        int windowOpened;
        int refreshes;
        int typingClaims;
        Runnable duringRefresh;

        @Override public void allowWindowToTakeIme() { windowOpened++; }
        @Override public void onTypingClaimed() { typingClaims++; }
        @Override public void refreshInputConnection() {
            refreshes++;
            // Guards against runaway recursion in the misbehaving-bridge tests below.
            if (refreshes > 50) throw new AssertionError("input connection refresh ran away");
            if (duringRefresh != null) duringRefresh.run();
        }
    }

    private Bridge bridge;
    private ComposerFocusCoordinator coordinator;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);

        editor = new EditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);

        holder = new View(activity);
        holder.setFocusable(true);
        holder.setFocusableInTouchMode(true);

        root.addView(editor);
        root.addView(holder);
        activity.setContentView(root);

        bridge = new Bridge();
        coordinator = new ComposerFocusCoordinator(editor, holder, bridge);
        // Exactly the wiring the overlay uses.
        editor.setOnFocusChangeListener((v, hasFocus) -> coordinator.onFocusChanged(hasFocus));
    }

    // ---- the crash ----

    @Test public void theFirstTapDoesNotRecurse() {
        coordinator.onEditorTapped();

        assertTrue("the editor has to end up focused", editor.hasFocus());
        assertEquals("one tap must produce exactly one connection refresh", 1, bridge.refreshes);
        assertEquals(1, bridge.typingClaims);
    }

    @Test public void tappingAnAlreadyFocusedComposerDoesNotLoop() {
        coordinator.onEditorTapped();
        int afterFirst = bridge.refreshes;

        coordinator.onEditorTapped();
        assertEquals("a second tap refreshes once more, it does not spin",
                afterFirst + 1, bridge.refreshes);
        assertTrue(editor.hasFocus());
    }

    @Test public void aFocusGainRefreshesTheConnectionExactlyOnce() {
        editor.requestFocus();

        assertTrue(editor.hasFocus());
        assertEquals(1, bridge.refreshes);
        assertEquals(1, bridge.typingClaims);
    }

    @Test public void losingFocusClaimsNothing() {
        editor.requestFocus();
        int claims = bridge.typingClaims;

        holder.requestFocus();
        assertFalse(editor.hasFocus());
        assertEquals("focus leaving is not a decision to type", claims, bridge.typingClaims);
    }

    /**
     * The exact v0.7.3.5 shape: the IME path clears and re-requests focus, re-entering the same
     * focus listener. Before the split this recursed until the process died.
     */
    @Test public void aBridgeThatMovesFocusStillTerminates() {
        bridge.duringRefresh = () -> {
            editor.clearFocus();
            editor.requestFocus();
        };

        coordinator.onEditorTapped();

        assertTrue("the reentrancy guard must bound this", bridge.refreshes <= 2);
        assertTrue(editor.hasFocus());
    }

    @Test public void repeatedFocusChurnFromTheBridgeCannotRunAway() {
        bridge.duringRefresh = () -> {
            for (int i = 0; i < 5; i++) {
                editor.clearFocus();
                editor.requestFocus();
            }
        };

        coordinator.onEditorTapped();
        assertTrue(bridge.refreshes <= 2);
    }

    // ---- releasing and reacquiring ----

    @Test public void releasingParksFocusOnTheHolder() {
        coordinator.onEditorTapped();
        assertTrue(editor.hasFocus());

        coordinator.release();
        assertFalse("the editor has to genuinely give up focus", editor.hasFocus());
        assertTrue(holder.hasFocus());
        assertFalse(coordinator.editorHasFocus());
    }

    @Test public void theComposerCanBeReacquiredAfterRelease() {
        coordinator.onEditorTapped();
        coordinator.release();
        int refreshesBefore = bridge.refreshes;

        coordinator.onEditorTapped();
        assertTrue(editor.hasFocus());
        assertEquals("one tap is enough to come back", refreshesBefore + 1, bridge.refreshes);
    }

    // ---- multi-turn typing, the v0.7.3.5 behaviour being preserved ----

    @Test public void threeTypedTurnsInOneInvocationEachStaySane() {
        ComposerImeState ime = new ComposerImeState();
        ime.reset();

        coordinator.onEditorTapped();
        ime.attach();

        for (int turn = 1; turn <= 3; turn++) {
            assertTrue("turn " + turn + " lost the editor", editor.hasFocus());
            assertTrue("turn " + turn + " lost typing ownership", ime.isTyping());
            // A typed send keeps the editor, so nothing is torn down between turns.
            assertFalse(ime.shouldReleaseOnSubmit(false));
        }
        assertEquals("no turn may re-run the attach sequence on its own", 1, bridge.refreshes);
    }

    @Test public void aVoiceTurnReleasesAndTheNextTapStillWorks() {
        ComposerImeState ime = new ComposerImeState();
        coordinator.onEditorTapped();
        ime.attach();

        // A spoken request still puts the keyboard away.
        assertTrue(ime.shouldReleaseOnSubmit(true));
        coordinator.release();
        ime.release();
        assertFalse(editor.hasFocus());

        coordinator.onEditorTapped();
        ime.attach();
        assertTrue(editor.hasFocus());
        assertTrue(ime.isTyping());
    }

    @Test public void anInternalResumeCanRestoreTheKeyboardWithoutRecursing() {
        // Screen Selection and the attachment pickers come back to this same session and ask for
        // the keyboard again; that path goes through the same single-step attach.
        coordinator.onEditorTapped();
        coordinator.release();

        coordinator.onEditorTapped();
        assertTrue(editor.hasFocus());
        assertEquals(2, bridge.typingClaims);
    }

    @Test public void aFreshInvocationOpensTheWindowForTheImeEveryTime() {
        // Keyboard-aware invocation re-arms the flag for each new invocation, so every attach
        // has to ask for the window again.
        coordinator.onEditorTapped();
        int opened = bridge.windowOpened;
        assertTrue(opened >= 1);

        coordinator.release();
        coordinator.onEditorTapped();
        assertTrue("the next invocation must also clear the flag", bridge.windowOpened > opened);
    }

    @Test public void theFocusHolderIsWhatActuallyReleasesTheEditor() {
        // The reason the holder exists, demonstrated directly: with nowhere for focus to go,
        // clearFocus() is not a release at all, because the view root hands focus straight back
        // to the only view that can take it. That bounce-back left the editor holding focus with
        // a dead input connection, and made the next tap a no-op that built no new one.
        ComposerFocusCoordinator noHolder =
                new ComposerFocusCoordinator(editor, null, new Bridge());
        editor.requestFocus();
        assertTrue(editor.hasFocus());

        noHolder.release();
        assertTrue("clearFocus() alone lets focus bounce straight back", editor.hasFocus());

        // With a holder to receive it, the release is real and the editor can be re-focused
        // later as a genuine transition.
        coordinator.release();
        assertFalse(editor.hasFocus());
        assertTrue(holder.hasFocus());
    }

    @Test public void aNullEditorIsHarmless() {
        ComposerFocusCoordinator none = new ComposerFocusCoordinator(null, null, new Bridge());
        none.onEditorTapped();
        none.onFocusChanged(true);
        none.release();
        assertFalse(none.editorHasFocus());
    }
}

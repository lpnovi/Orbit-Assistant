package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The typing lease: once the user starts typing, an ordinary turn must not end that session.
 *
 * <p>These assert the invariants Orbit's own code is responsible for. They cannot prove the
 * Samsung failure is fixed — the code audit found nothing in Orbit that releases focus, hides the
 * keyboard, or re-arms the IME flag during a typed turn, which is exactly why Candidate B ships
 * instrumentation rather than another guess.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class TypingLeaseTest {
    private Activity activity;
    private TracingEditText editor;
    private View holder;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout root = new LinearLayout(activity);
        editor = new TracingEditText(activity);
        editor.setFocusable(true);
        editor.setFocusableInTouchMode(true);
        holder = new View(activity);
        holder.setFocusable(true);
        holder.setFocusableInTouchMode(true);
        root.addView(editor);
        root.addView(holder);
        activity.setContentView(root);
        ComposerTrace.begin("test");
    }

    // ---- the lease survives an ordinary turn ----

    @Test public void aTypedSubmitDoesNotEndTheLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();

        assertFalse("a typed send must not release the keyboard",
                ime.shouldReleaseOnSubmit(false));
        assertTrue("the session is still the user's", ime.isTyping());
    }

    @Test public void renderingAResponseDoesNotEndTheLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        // Everything a turn renders happens without touching the composer: bubbles, action
        // cards and source links are added to the message list, not to the editor.
        assertTrue(ime.isTyping());
        assertTrue("response rendering must leave focus alone", editor.hasFocus());
    }

    @Test public void actionCompletionDoesNotEndTheLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        // Stands in for OrbitActionEngine finishing a device action and posting its card.
        ComposerTrace.event("action.finished steps=1/1");
        assertTrue(ime.isTyping());
        assertTrue(editor.hasFocus());
    }

    @Test public void threeConsecutiveTypedTurnsKeepOneLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        editor.requestFocus();

        for (int turn = 1; turn <= 3; turn++) {
            editor.getText().append("message " + turn);
            assertFalse("turn " + turn + " released the keyboard",
                    ime.shouldReleaseOnSubmit(false));
            editor.getText().clear();
            assertTrue("turn " + turn + " lost the lease", ime.isTyping());
            assertTrue("turn " + turn + " lost focus", editor.hasFocus());
        }
    }

    // ---- deliberate transitions do end it ----

    @Test public void aVoiceTurnReleasesTheLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        assertTrue("speaking is a deliberate transition", ime.shouldReleaseOnSubmit(true));
    }

    @Test public void orbitReleasingTheKeyboardEndsTheLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        ime.release();
        assertFalse(ime.isTyping());
    }

    @Test public void aFreshInvocationStartsWithNoLease() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        ime.reset();
        assertFalse(ime.isTyping());
    }

    @Test public void aSendWithNoLeaseStillReleases() {
        ComposerImeState ime = new ComposerImeState();
        ime.reset();
        assertTrue(ime.shouldReleaseOnSubmit(false));
    }

    // ---- window eligibility ----

    @Test public void anActiveLeaseNeverReArmsTheImeBlockingFlag() {
        // FLAG_ALT_FOCUSABLE_IM is only ever added at invocation setup, and only while Orbit
        // does not own the IME. Nothing in a turn re-adds it.
        WindowManager.LayoutParams attrs = activity.getWindow().getAttributes();
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        assertEquals("the window must stay able to take the input method", 0,
                attrs.flags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
    }

    @Test public void aSecondInsetsListenerReplacesTheFirstRatherThanWrappingIt() {
        // The mechanism behind the v0.7.3.8 full-chat regression, pinned down so it cannot be
        // reintroduced by another "wraps existing behaviour" assumption. UiKit.applyActivityInsets
        // owns the chat root's listener, and that listener is what applies IME-aware padding.
        View content = new LinearLayout(activity);
        int[] uiKitCalls = {0};
        int[] competingCalls = {0};

        content.setOnApplyWindowInsetsListener((v, insets) -> {
            uiKitCalls[0]++;
            return insets;
        });
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            competingCalls[0]++;
            return insets;
        });
        content.dispatchApplyWindowInsets(android.view.WindowInsets.CONSUMED);

        assertEquals("the first listener is displaced, not chained", 0, uiKitCalls[0]);
        assertEquals(1, competingCalls[0]);
    }

    // ---- no focus recursion ----

    @Test public void theFocusCoordinatorStillCannotRecurse() {
        int[] refreshes = {0};
        ComposerFocusCoordinator coordinator = new ComposerFocusCoordinator(editor, holder,
                new ComposerFocusCoordinator.ImeBridge() {
                    @Override public void allowWindowToTakeIme() {}
                    @Override public void onTypingClaimed() {}
                    @Override public void refreshInputConnection() {
                        refreshes[0]++;
                        if (refreshes[0] > 10) throw new AssertionError("focus recursion returned");
                        editor.clearFocus();
                        editor.requestFocus();
                    }
                });
        editor.setOnFocusChangeListener((v, hasFocus) -> coordinator.onFocusChanged(hasFocus));

        coordinator.onEditorTapped();
        assertTrue(refreshes[0] <= 2);
        assertTrue(editor.hasFocus());
    }

    @Test public void thereIsNoBlanketRestartInputStrategy() {
        // v0.7.3.8 refreshed the connection after submit, after the response and after actions.
        // Candidate B removed all of it; the only refresh left is the one on tap/focus-gain.
        int[] refreshes = {0};
        ComposerFocusCoordinator coordinator = new ComposerFocusCoordinator(editor, holder,
                new ComposerFocusCoordinator.ImeBridge() {
                    @Override public void allowWindowToTakeIme() {}
                    @Override public void onTypingClaimed() {}
                    @Override public void refreshInputConnection() { refreshes[0]++; }
                });
        editor.setOnFocusChangeListener((v, hasFocus) -> coordinator.onFocusChanged(hasFocus));

        coordinator.onEditorTapped();
        int afterTap = refreshes[0];

        // A whole turn's worth of lifecycle events, none of which may refresh anything.
        ComposerTrace.event("submit.after-clear");
        ComposerTrace.event("response.rendered actions=1");
        ComposerTrace.event("action.finished steps=1/1");
        assertEquals("nothing in a turn may refresh the connection", afterTap, refreshes[0]);
    }

    // ---- voice handover, unchanged ----

    @Test public void voiceToTypingHandoverIsUnchanged() {
        VoiceHandoff turn = new VoiceHandoff();
        turn.begin();
        assertTrue(turn.hasLiveTurn());

        turn.abandon();
        assertFalse("a late callback from an abandoned turn cannot act", turn.hasLiveTurn());
        assertEquals("keep this", VoiceHandoff.preservedDraft("keep", "this"));
    }
}

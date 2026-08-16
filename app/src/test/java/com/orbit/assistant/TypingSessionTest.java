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
 * Continuous typing across consecutive turns, driven through real views and real focus events.
 *
 * <p>Three releases of state-only assertions passed while the phone stayed broken, so these tests
 * model the thing that actually failed: an editor that still reports focus, with a keyboard still
 * on screen, behind an input connection that no longer works. The stand-in for that connection is
 * rebuilt only when the code issues a refresh, so a turn that fails to refresh leaves the editor
 * unable to accept text here exactly as it did on the device.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class TypingSessionTest {
    private Activity activity;
    private EditText editor;
    private View holder;
    private Session session;

    /**
     * A composer whose input connection can go stale independently of focus, mirroring the
     * Samsung behaviour: clearing the editor's text programmatically drops the connection, and
     * only an explicit in-place refresh brings it back.
     */
    private final class Session {
        final ComposerImeState ime = new ComposerImeState();
        final ComposerFocusCoordinator focus;
        boolean connectionLive;
        int refreshes;
        int focusMoves;
        boolean visible = true;

        Session() {
            focus = new ComposerFocusCoordinator(editor, holder,
                    new ComposerFocusCoordinator.ImeBridge() {
                        @Override public void allowWindowToTakeIme() {}
                        @Override public void onTypingClaimed() { ime.attach(); }
                        @Override public void refreshInputConnection() {
                            refreshes++;
                            connectionLive = true;
                            if (refreshes > 20) throw new AssertionError("refresh loop");
                        }
                    });
            editor.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) focusMoves++;
                focus.onFocusChanged(hasFocus);
            });
        }

        void tapComposer() {
            focus.onEditorTapped();
        }

        /** What a keypress does: it only lands when the connection is actually live. */
        boolean typeCharacter(String ch) {
            if (!connectionLive || !editor.hasFocus()) return false;
            editor.setText(editor.getText().toString() + ch);
            return true;
        }

        /** The send path: clear the editor, which drops the connection, then revalidate. */
        void send() {
            editor.setText("");
            connectionLive = false;
            ime.beginTurn();
            revalidate();
        }

        /** Response completion, including any action cards. */
        void responseCompleted() {
            revalidate();
        }

        void revalidate() {
            if (!ime.shouldRevalidate()) return;
            focus.revalidateWithoutMovingFocus();
        }

        void userDismissesKeyboard() {
            ime.onKeyboardVisibilityChanged(true);
            ime.onKeyboardVisibilityChanged(false);
        }

        void orbitReleases() {
            ime.release();
            focus.release();
            connectionLive = false;
        }
    }

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
        session = new Session();
    }

    // ---- the reported bug ----

    @Test public void theFirstTypedTurnWorks() {
        session.tapComposer();
        assertTrue(editor.hasFocus());
        assertTrue("first message must be typeable", session.typeCharacter("h"));
        assertEquals("h", editor.getText().toString());
    }

    @Test public void theSecondTypedTurnWorksWithoutTappingAgain() {
        session.tapComposer();
        session.typeCharacter("h");
        session.send();
        session.responseCompleted();

        assertTrue("the editor must still be focused", editor.hasFocus());
        assertTrue("the second message must land without another tap",
                session.typeCharacter("s"));
        assertEquals("s", editor.getText().toString());
    }

    @Test public void aThirdTypedTurnAlsoWorks() {
        session.tapComposer();
        for (int turn = 1; turn <= 3; turn++) {
            assertTrue("turn " + turn + " could not be typed", session.typeCharacter("x"));
            session.send();
            session.responseCompleted();
        }
        assertTrue("a fourth message must still be typeable", session.typeCharacter("x"));
    }

    @Test public void aReplyThatRunsADeviceActionStillLeavesTheComposerUsable() {
        // The physical reproduction was "turn on flashlight": the action ran, its card rendered,
        // and the composer went dead.
        session.tapComposer();
        session.typeCharacter("turn on flashlight");
        session.send();
        // Action executed, card posted, then the turn finishes.
        session.responseCompleted();

        assertTrue(session.typeCharacter("turn it off"));
    }

    @Test public void withoutRevalidationTheEditorWouldStayDead() {
        // Proves the test can actually observe the bug rather than passing by construction.
        session.tapComposer();
        session.typeCharacter("h");
        editor.setText("");
        session.connectionLive = false;

        assertTrue("focus alone is not evidence of a usable editor", editor.hasFocus());
        assertFalse("this is exactly what the phone did", session.typeCharacter("s"));
    }

    // ---- bounded, focus-stable revalidation ----

    @Test public void revalidationNeverMovesFocus() {
        session.tapComposer();
        int movesAfterTap = session.focusMoves;

        session.send();
        session.responseCompleted();
        assertEquals("revalidation must not touch focus", movesAfterTap, session.focusMoves);
        assertTrue(editor.hasFocus());
    }

    @Test public void revalidationIsBoundedPerTurn() {
        session.tapComposer();
        session.send();
        int afterSend = session.refreshes;

        // Several completion callbacks can arrive for one turn; only one more may act.
        session.responseCompleted();
        session.responseCompleted();
        session.responseCompleted();
        assertEquals("at most one further refresh per turn", afterSend + 1, session.refreshes);
        assertEquals(0, session.ime.revalidationsRemaining());
    }

    @Test public void eachNewTurnGetsAFreshAllowance() {
        session.tapComposer();
        session.send();
        session.responseCompleted();
        int afterFirstTurn = session.refreshes;

        session.send();
        assertTrue("a new turn may refresh again", session.refreshes > afterFirstTurn);
    }

    @Test public void revalidationCannotRecurse() {
        // v0.7.3.5's crash shape: the refresh itself moves focus.
        Session recursive = new Session();
        recursive.ime.attach();
        editor.requestFocus();
        recursive.ime.beginTurn();
        recursive.revalidate();
        assertTrue("bounded regardless", recursive.refreshes <= 2);
    }

    // ---- the user's choices win ----

    @Test public void aDismissedKeyboardIsNotReopened() {
        session.tapComposer();
        session.typeCharacter("h");
        session.send();

        session.userDismissesKeyboard();
        assertFalse("dismissal ends the typing session", session.ime.isTyping());

        int before = session.refreshes;
        session.responseCompleted();
        assertEquals("the reply must not summon the keyboard back", before, session.refreshes);
    }

    @Test public void orbitsOwnHideIsNotMistakenForTheUsersDismissal() {
        session.tapComposer();
        session.orbitReleases();
        assertFalse(session.ime.isTyping());

        // The visibility change that follows Orbit's own hide changes nothing further.
        assertFalse(session.ime.onKeyboardVisibilityChanged(false));
    }

    @Test public void aReleasedComposerCanBeReacquiredWithOneTap() {
        session.tapComposer();
        session.orbitReleases();
        assertFalse(editor.hasFocus());

        session.tapComposer();
        assertTrue(editor.hasFocus());
        assertTrue(session.ime.isTyping());
        assertTrue(session.typeCharacter("h"));
    }

    // ---- voice and lifecycle boundaries ----

    @Test public void aVoiceTurnStillReleasesTheKeyboard() {
        session.tapComposer();
        assertTrue(session.ime.shouldReleaseOnSubmit(true));
        assertFalse(session.ime.shouldReleaseOnSubmit(false));
    }

    @Test public void aVoiceOriginTurnDoesNotClaimTyping() {
        ComposerImeState fresh = new ComposerImeState();
        fresh.reset();
        assertFalse(fresh.isTyping());
        // Nothing about a spoken turn attaches the composer.
        fresh.beginTurn();
        assertFalse("a voice turn must not revalidate a typing session it never had",
                fresh.shouldRevalidate());
    }

    @Test public void theVoiceToTypingHandoffStillClaimsTheComposer() {
        VoiceHandoff turn = new VoiceHandoff();
        turn.begin();
        assertTrue(turn.hasLiveTurn());

        turn.abandon();
        session.tapComposer();
        assertFalse(turn.hasLiveTurn());
        assertTrue(session.ime.isTyping());
        assertTrue(session.typeCharacter("typed instead"));
    }

    @Test public void aFreshInvocationClearsStaleTypingState() {
        session.tapComposer();
        session.send();
        assertTrue(session.ime.isTyping());

        session.ime.reset();
        assertFalse(session.ime.isTyping());
        assertFalse("a fresh invocation may not inherit an allowance",
                session.ime.shouldRevalidate());
    }

    @Test public void aTemporaryResumeKeepsTheTypingSession() {
        // Screen Selection and the attachment pickers hide this same session and return to it;
        // the internal-resume path deliberately does not reset.
        session.tapComposer();
        session.typeCharacter("look at this");
        assertTrue(session.ime.isTyping());

        // Returning restores the editor rather than inventing a new session.
        session.focus.onEditorTapped();
        assertTrue(session.ime.isTyping());
        assertTrue(session.typeCharacter(" and this"));
    }

    @Test public void aResumeDoesNotInventTypingThatWasNotThere() {
        ComposerImeState idle = new ComposerImeState();
        idle.reset();
        idle.onKeyboardVisibilityChanged(false);
        assertFalse(idle.isTyping());
    }

    // ---- state model ----

    @Test public void focusAndTypingIntentAreSeparateIdeas() {
        session.tapComposer();
        assertTrue(editor.hasFocus());
        assertTrue(session.ime.isTyping());

        // The editor can still report focus after intent has ended.
        session.ime.onKeyboardVisibilityChanged(true);
        session.ime.onKeyboardVisibilityChanged(false);
        assertFalse(session.ime.isTyping());
        assertTrue("focus is unchanged by intent ending", editor.hasFocus());
    }
}

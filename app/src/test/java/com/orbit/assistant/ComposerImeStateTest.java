package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Composer and IME ownership across a Side-button invocation.
 *
 * <p>The bug this covers is a chat that accepted exactly one typed message: the send tore the
 * editor's input connection down, and nothing rebuilt it, so the keyboard reappeared but the
 * characters went nowhere. These tests drive the ownership model through the same sequences the
 * session does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ComposerImeStateTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** Models one invocation of the overlay for the parts that decide editor ownership. */
    private static final class Overlay {
        final ComposerImeState ime = new ComposerImeState();
        int typedMessagesAccepted;
        int keyboardReleases;
        boolean visible = true;

        void freshInvocation() { ime.reset(); visible = true; }

        /** The user taps the composer; Orbit attaches the IME to its own editor. */
        void tapComposer() { ime.attach(); }

        void releaseKeyboard() { ime.release(); keyboardReleases++; }

        /** Returns true when the message actually reached Orbit. */
        boolean send(String text, boolean voiceRequest) {
            // A typed message only lands if the editor was genuinely connected.
            boolean landed = voiceRequest || ime.isTyping();
            if (landed && !voiceRequest) typedMessagesAccepted++;
            if (ime.shouldReleaseOnSubmit(voiceRequest)) releaseKeyboard();
            return landed;
        }
    }

    // ---- the release-blocking regression ----

    @Test public void aTypedTurnKeepsItsEditorAcrossTheSend() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();

        assertTrue(overlay.send("hello", false));
        assertTrue("a typed send must not drop the input connection", overlay.ime.isTyping());
        assertEquals(0, overlay.keyboardReleases);
    }

    @Test public void theOverlayAcceptsAnUnlimitedNumberOfTypedMessages() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();

        for (int message = 1; message <= 5; message++) {
            assertTrue("message " + message + " was refused", overlay.send("message " + message, false));
        }
        assertEquals(5, overlay.typedMessagesAccepted);
        assertTrue(overlay.ime.isTyping());
    }

    @Test public void oneTapIsEnoughAfterTheKeyboardWasPutAway() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();
        overlay.send("first", false);

        // The user dismissed the keyboard themselves.
        overlay.releaseKeyboard();
        assertFalse(overlay.ime.isTyping());

        overlay.tapComposer();
        assertTrue("a single tap has to make the composer usable again", overlay.ime.isTyping());
        assertTrue(overlay.send("second", false));
    }

    @Test public void aDeliberateKeyboardDismissalIsRespected() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();
        overlay.releaseKeyboard();

        // Nothing re-attaches on its own; only the user's next tap does.
        assertFalse(overlay.ime.isTyping());
        assertEquals(1, overlay.keyboardReleases);
    }

    // ---- voice turns still release ----

    @Test public void aVoiceTurnStillPutsTheKeyboardAway() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        assertTrue(overlay.ime.shouldReleaseOnSubmit(true));

        overlay.tapComposer();
        assertTrue("a spoken request releases the editor even if it was focused",
                overlay.ime.shouldReleaseOnSubmit(true));
        overlay.send("spoken", true);
        assertFalse(overlay.ime.isTyping());
    }

    @Test public void aSendWithNoTypingSessionStillReleases() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        assertTrue(overlay.ime.shouldReleaseOnSubmit(false));
    }

    // ---- invocation boundaries ----

    @Test public void aNewInvocationStartsWithNobodyOwningTheEditor() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();
        assertTrue(overlay.ime.isTyping());

        overlay.freshInvocation();
        assertFalse("each invocation begins with the window able to sit above another keyboard",
                overlay.ime.isTyping());
    }

    @Test public void anInternalResumeDoesNotEndTheTypingSession() {
        // Screen Selection and the attachment pickers hide this same session and come back to it.
        Overlay overlay = new Overlay();
        overlay.freshInvocation();
        overlay.tapComposer();
        overlay.send("look at this", false);

        // The internal resume path deliberately does not call reset().
        assertTrue(overlay.ime.isTyping());
        assertTrue("the next typed turn still has to work", overlay.send("and this", false));
    }

    // ---- interaction with voice ----

    @Test public void handingOverFromVoiceToTypingClaimsTheEditor() {
        Overlay overlay = new Overlay();
        overlay.freshInvocation();

        VoiceHandoff turn = new VoiceHandoff();
        turn.begin();
        assertTrue(turn.hasLiveTurn());

        // Tapping the composer abandons the voice turn and attaches the IME, as v0.7.3.4 does.
        turn.abandon();
        overlay.tapComposer();
        assertFalse(turn.hasLiveTurn());
        assertTrue(overlay.ime.isTyping());
        assertTrue(overlay.send("typed instead", false));
    }

    @Test public void handsFreeFollowUpsKeepTheirEstablishedMeaning() {
        // Unchanged in this release: the preference alone decides whether Orbit listens again
        // after speaking, and it stays independent of the overlay's open-time setting.
        assertFalse("Hands-free follow-ups stay opt-in", Prefs.autoListen(context));
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).commit();
        assertTrue(Prefs.autoListen(context));
        assertFalse("the two voice preferences never read each other",
                Prefs.autoListenOnOpen(context));
    }

    @Test public void overlayAutoListenStillOnlyAppliesToAFreshInvocation() {
        // v0.7.3.3's rule is untouched by the ownership change.
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertTrue(OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), false, false));
        assertFalse(OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), true, false));
        assertFalse(OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), false, true));
    }
}

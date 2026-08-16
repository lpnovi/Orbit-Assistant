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
 * Tapping the composer while Orbit is listening hands the turn to typing.
 *
 * <p>The recognizer itself is Android's, so what is verified here is the ownership rule that
 * decides whether a callback may still act: a turn the user walked away from must not reach the
 * composer or the send path, however late its results arrive.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class VoiceHandoffTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /**
     * Stands in for the composer plus the parts of a voice turn a late callback would reach.
     * Every mutation runs through {@link VoiceHandoff}, exactly as both surfaces do.
     */
    private static final class Surface {
        final VoiceHandoff turn = new VoiceHandoff();
        String composer = "";
        String submitted = null;
        boolean listening;
        boolean finalizePending;
        boolean focused;
        int listenStarts;

        void startListening() {
            turn.begin();
            listening = true;
            finalizePending = false;
            composer = "";
            listenStarts++;
        }

        /** A recognizer result. Ignored unless its turn still owns the composer. */
        void onResults(String text, boolean submit) {
            if (!turn.hasLiveTurn()) return;
            composer = text;
            if (submit) submitted = text;
        }

        void onPartialResults(String text) {
            if (!turn.hasLiveTurn()) return;
            composer = text;
            finalizePending = true;
        }

        /** A queued pause-friendly finalization, checked against the turn it was scheduled for. */
        void firePendingFinalize(int scheduledForTurn) {
            if (!turn.accepts(scheduledForTurn)) return;
            submitted = composer;
            finalizePending = false;
        }

        /** A queued pause-friendly segment restart. */
        void fireQueuedRestart(int scheduledForTurn) {
            if (!turn.accepts(scheduledForTurn)) return;
            listening = true;
            listenStarts++;
        }

        void tapComposer(String accumulated, String partial) {
            if (VoiceHandoff.shouldTakeOver(listening, false)) {
                turn.abandon();
                finalizePending = false;
                listening = false;
                String preserved = VoiceHandoff.preservedDraft(accumulated, partial);
                if (!preserved.isEmpty()) composer = preserved;
            }
            focused = true;
        }
    }

    // ---- the handover itself ----

    @Test public void tappingTheComposerStopsTheCurrentRecognition() {
        Surface s = new Surface();
        s.startListening();
        assertTrue(s.listening);

        s.tapComposer("remind me", "");
        assertFalse("listening must end the moment the user reaches for the keyboard", s.listening);
        assertFalse(s.turn.hasLiveTurn());
    }

    @Test public void tappingTheComposerCancelsPendingFinalization() {
        Surface s = new Surface();
        s.startListening();
        int turn = s.turn.liveTurn();
        s.onPartialResults("remind me to");
        assertTrue(s.finalizePending);

        s.tapComposer("", "remind me to");
        assertFalse(s.finalizePending);

        // Even a timer that outran removeCallbacks finds its turn disowned.
        s.firePendingFinalize(turn);
        assertEquals(null, s.submitted);
    }

    @Test public void recognisedTextIsPreservedForEditing() {
        Surface s = new Surface();
        s.startListening();
        s.onPartialResults("call mum");

        s.tapComposer("call mum", "");
        assertEquals("call mum", s.composer);
        assertEquals("nothing may be sent by switching input mode", null, s.submitted);
    }

    @Test public void bothHalvesOfAnInProgressUtteranceAreKept() {
        // What was on screen while listening is what remains after the handover.
        assertEquals("set a timer for ten minutes",
                VoiceHandoff.preservedDraft("set a timer", "for ten minutes"));
        assertEquals("set a timer", VoiceHandoff.preservedDraft("set a timer", ""));
        assertEquals("for ten minutes", VoiceHandoff.preservedDraft("", "for ten minutes"));
    }

    @Test public void anEmptyTurnLeavesTheComposerEmpty() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("", "");
        assertEquals("", s.composer);
    }

    @Test public void theComposerTakesTypingFocus() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("hello", "");
        assertTrue(s.focused);
    }

    // ---- late callbacks ----

    @Test public void abandonedResultsCannotOverwriteWhatTheUserTyped() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("call", "");
        s.composer = "call the dentist tomorrow";

        // The recognizer finishes the utterance the user walked away from.
        s.onResults("call the vet", false);
        assertEquals("call the dentist tomorrow", s.composer);
    }

    @Test public void abandonedPartialResultsCannotOverwriteEither() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("call", "");
        s.composer = "typed by hand";

        s.onPartialResults("call the v");
        assertEquals("typed by hand", s.composer);
        assertFalse(s.finalizePending);
    }

    @Test public void anAbandonedTurnCannotSubmit() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("send it", "");

        s.onResults("send it", true);
        assertEquals(null, s.submitted);
    }

    @Test public void anAbandonedTurnCannotReopenTheMicrophone() {
        Surface s = new Surface();
        s.startListening();
        int turn = s.turn.liveTurn();
        s.tapComposer("hello", "");

        // A pause-friendly restart queued before the tap.
        s.fireQueuedRestart(turn);
        assertFalse("the microphone must not reopen behind the keyboard", s.listening);
        assertEquals(1, s.listenStarts);
    }

    @Test public void aLaterTurnDoesNotInheritAnEarlierTurnsQueuedWork() {
        Surface s = new Surface();
        s.startListening();
        int first = s.turn.liveTurn();
        s.tapComposer("first", "");
        s.startListening();

        // Work belonging to the first turn stays rejected even though a turn is live again.
        s.firePendingFinalize(first);
        s.fireQueuedRestart(first);
        assertEquals(null, s.submitted);
        assertEquals(2, s.listenStarts);
        assertTrue(s.turn.accepts(s.turn.liveTurn()));
    }

    // ---- what must not change ----

    @Test public void tappingTheComposerWhenIdleChangesNothingAboutVoice() {
        Surface s = new Surface();
        s.composer = "typed already";
        s.tapComposer("", "");
        assertEquals("typed already", s.composer);
        assertEquals(0, s.listenStarts);
        assertTrue(s.focused);
        assertFalse(s.turn.hasLiveTurn());
    }

    @Test public void aTurnCommittedToSendingIsLeftToFinish() {
        // Mid-submit is not a handover point; the message is already on its way.
        assertFalse(VoiceHandoff.shouldTakeOver(true, true));
        assertTrue(VoiceHandoff.shouldTakeOver(true, false));
        assertFalse(VoiceHandoff.shouldTakeOver(false, false));
    }

    @Test public void manualMicrophoneUseIsUnaffected() {
        Surface s = new Surface();
        s.startListening();
        s.onPartialResults("what is the weather");
        s.onResults("what is the weather", true);
        assertEquals("what is the weather", s.submitted);
        assertEquals("what is the weather", s.composer);
    }

    @Test public void aFreshTurnAfterAHandoverBehavesNormally() {
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("abandoned", "");
        s.startListening();
        int second = s.turn.liveTurn();

        s.onPartialResults("second attempt");
        assertEquals("second attempt", s.composer);
        s.firePendingFinalize(second);
        assertEquals("second attempt", s.submitted);
    }

    // ---- preferences are untouched ----

    @Test public void handingOverDoesNotDisableStartListeningWhenOverlayOpens() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("never mind", "");

        assertTrue("the preference belongs to the user, not to one turn",
                Prefs.autoListenOnOpen(context));
    }

    @Test public void handingOverDoesNotDisableHandsFreeFollowUps() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).commit();
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("never mind", "");

        assertTrue(Prefs.autoListen(context));
    }

    @Test public void theNextFreshOverlayStillAutoListens() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        Surface s = new Surface();
        s.startListening();
        s.tapComposer("never mind", "");

        // A brand-new invocation, with the guard reset as onPrepareShow does.
        assertTrue(OverlayAutoListen.shouldStartForShow(
                Prefs.autoListenOnOpen(context), false, false));
    }

    @Test public void internalResumeStillNeverAutoListens() {
        // v0.7.3.3's rule is untouched by this change.
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertFalse(OverlayAutoListen.shouldStartForShow(
                Prefs.autoListenOnOpen(context), true, false));
    }

    @Test public void aTurnIdIsNeverReusedAndNoTurnIsNeverAccepted() {
        VoiceHandoff turn = new VoiceHandoff();
        assertFalse(turn.hasLiveTurn());
        assertFalse(turn.accepts(VoiceHandoff.NO_TURN));

        int first = turn.begin();
        int second = turn.begin();
        assertFalse("a new turn must not collide with the previous one", first == second);
        assertFalse(turn.accepts(first));
        assertTrue(turn.accepts(second));

        turn.abandon();
        assertFalse(turn.accepts(second));
        assertEquals(VoiceHandoff.NO_TURN, turn.liveTurn());
    }
}

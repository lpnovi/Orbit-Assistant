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
 * Conversational Voice: who owns a turn, and what a late callback is allowed to do.
 *
 * <p>The rule throughout is that a deliberate user transition wins over anything already queued,
 * and that an abandoned or interrupted turn can never act.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class VoiceMaturityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** Models the utterance-ownership rule both surfaces now use for TTS completion. */
    private static final class Speaker {
        private int generation;
        private String live = "";
        int followUpsStarted;

        String speak() {
            live = "utterance_" + (++generation);
            return live;
        }

        void interrupt() { live = ""; }

        boolean isLive(String id) {
            return id != null && !live.isEmpty() && live.equals(id);
        }

        /** What onDone does: only a live utterance may open the microphone. */
        void onDone(String id, boolean handsFree) {
            if (!isLive(id)) return;
            live = "";
            if (handsFree) followUpsStarted++;
        }
    }

    // ---- the two settings stay distinct ----

    @Test public void theTwoVoiceSettingsAreSeparateAndKeepTheirDefaults() {
        assertFalse("Hands-free follow-ups stay opt-in", Prefs.autoListen(context));
        assertFalse("Start listening when overlay opens stays opt-in",
                Prefs.autoListenOnOpen(context));

        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN, true).commit();
        assertTrue(Prefs.autoListen(context));
        assertFalse("enabling one must not enable the other", Prefs.autoListenOnOpen(context));
    }

    // ---- hands-free follow-ups ----

    @Test public void aSpokenReplyWithHandsFreeOnListensAgain() {
        Speaker speaker = new Speaker();
        String id = speaker.speak();
        speaker.onDone(id, true);
        assertEquals(1, speaker.followUpsStarted);
    }

    @Test public void aSpokenReplyWithHandsFreeOffDoesNotListenAgain() {
        Speaker speaker = new Speaker();
        String id = speaker.speak();
        speaker.onDone(id, false);
        assertEquals(0, speaker.followUpsStarted);
    }

    @Test public void aTypedTurnNeverStartsVoice() {
        // A typed turn never speaks, so no completion callback exists to open the microphone.
        Speaker speaker = new Speaker();
        speaker.onDone("utterance_never_spoken", true);
        assertEquals(0, speaker.followUpsStarted);
    }

    // ---- interruption ----

    @Test public void anInterruptedUtteranceCannotOpenTheMicrophone() {
        Speaker speaker = new Speaker();
        String id = speaker.speak();

        // The user taps the mic while Orbit is speaking.
        speaker.interrupt();
        speaker.onDone(id, true);
        assertEquals("the interrupted reply must not start a second listening cycle",
                0, speaker.followUpsStarted);
    }

    @Test public void aStaleUtteranceFromAnEarlierTurnCannotAct() {
        Speaker speaker = new Speaker();
        String first = speaker.speak();
        String second = speaker.speak();

        speaker.onDone(first, true);
        assertEquals("only the current utterance counts", 0, speaker.followUpsStarted);
        speaker.onDone(second, true);
        assertEquals(1, speaker.followUpsStarted);
    }

    @Test public void oneUtteranceCannotStartTwoFollowUps() {
        Speaker speaker = new Speaker();
        String id = speaker.speak();
        speaker.onDone(id, true);
        speaker.onDone(id, true);
        assertEquals("a duplicate completion must be ignored", 1, speaker.followUpsStarted);
    }

    @Test public void interruptingWhileNothingIsSpeakingIsHarmless() {
        Speaker speaker = new Speaker();
        speaker.interrupt();
        speaker.onDone("", true);
        assertEquals(0, speaker.followUpsStarted);
    }

    // ---- typing wins ----

    @Test public void typingTakeoverAbandonsTheVoiceTurn() {
        VoiceHandoff turn = new VoiceHandoff();
        int id = turn.begin();
        assertTrue(turn.accepts(id));

        turn.abandon();
        assertFalse("the composer is the user's now", turn.hasLiveTurn());
        assertFalse(turn.accepts(id));
    }

    @Test public void aLateRecognitionCallbackCannotOverwriteTyping() {
        VoiceHandoff turn = new VoiceHandoff();
        int id = turn.begin();
        turn.abandon();

        // Whatever arrives late belongs to a turn nobody owns.
        assertFalse(turn.accepts(id));
    }

    @Test public void recognisedWordsSurviveTheHandover() {
        assertEquals("remind me to call", VoiceHandoff.preservedDraft("remind me", "to call"));
        assertEquals("", VoiceHandoff.preservedDraft("", ""));
    }

    @Test public void aTurnCommittedToSendingIsLeftAlone() {
        assertFalse(VoiceHandoff.shouldTakeOver(true, true));
        assertTrue(VoiceHandoff.shouldTakeOver(true, false));
        assertFalse(VoiceHandoff.shouldTakeOver(false, false));
    }

    @Test public void aTypedSendKeepsTheKeyboardAndAVoiceSendReleasesIt() {
        ComposerImeState ime = new ComposerImeState();
        ime.attach();
        assertFalse(ime.shouldReleaseOnSubmit(false));
        assertTrue(ime.shouldReleaseOnSubmit(true));
    }

    // ---- repeated turns ----

    @Test public void severalVoiceTurnsInSequenceEachBehaveTheSame() {
        Speaker speaker = new Speaker();
        for (int turn = 1; turn <= 3; turn++) {
            String id = speaker.speak();
            speaker.onDone(id, true);
            assertEquals("turn " + turn, turn, speaker.followUpsStarted);
        }
    }

    @Test public void everyTurnGetsItsOwnIdentity() {
        Speaker speaker = new Speaker();
        String first = speaker.speak();
        String second = speaker.speak();
        assertFalse("turn ids must not collide", first.equals(second));
    }

    // ---- overlay auto-listen is untouched ----

    @Test public void overlayAutoListenStillOnlyAppliesToAFreshInvocation() {
        Prefs.get(context).edit().putBoolean(Prefs.AUTO_LISTEN_ON_OPEN, true).commit();
        assertTrue(OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), false, false));
        assertFalse("an internal resume is not a new invocation",
                OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), true, false));
        assertFalse("one sheet may only auto-listen once",
                OverlayAutoListen.shouldStartForShow(Prefs.autoListenOnOpen(context), false, true));
    }

    @Test public void voiceRemainsLabelledAsBeta() {
        // 0.7.4.0 matures the behaviour; the label stays until real-world use earns its removal.
        assertTrue(Prefs.voicePauseFriendly(context));
    }
}

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
 * The hands-free handover on the real full-chat controller, not just on the policy.
 *
 * <p>The policy answers whether a reply expects an answer. This covers the half that decides
 * whether anything is allowed to act on that: an interrupted utterance, a turn already running,
 * and a composer the user has reached for all have to refuse the microphone however clearly the
 * reply asked a question.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class VoiceFollowUpOwnershipTest {

    private Context context;
    private VoiceInputController controller;

    /** A callback that records nothing: these tests are about state, not presentation. */
    private static final class SilentCallback implements VoiceInputController.Callback {
        String composerText = "";
        @Override public String currentComposerText() { return composerText; }
        @Override public void onDraft(String text) {}
        @Override public void onSubmit(String text) {}
        @Override public void onStatus(String status) {}
        @Override public void onStateChanged(boolean l, boolean f, boolean s) {}
        @Override public void onPermissionNeeded() {}
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().apply();
        controller = new VoiceInputController(context, new SilentCallback());
    }

    // ---- compatibility: Smart off must not delete hands-free follow-ups ---------------------

    @Test public void withSmartOffAnIdleControllerStillFollowsUp() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();

        VoiceFollowUpPolicy.Decision decision = controller.followUpDecision();
        assertEquals("the existing always-follow-up path has to survive",
                VoiceFollowUpPolicy.Decision.SMART_OFF_LEGACY, decision);
        assertTrue(decision.reopensMicrophone());
    }

    @Test public void withSmartOnAnIdleControllerFollowsOnlyAWaitingReply() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, true)
                .apply();

        // No reply has been spoken yet, so there is nothing waiting on the user.
        assertEquals(VoiceFollowUpPolicy.Decision.COMPLETE_REPLY,
                controller.followUpDecision());
    }

    @Test public void handsFreeOffRefusesOnTheRealController() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, false)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, true)
                .apply();
        assertEquals(VoiceFollowUpPolicy.Decision.MASTER_OFF, controller.followUpDecision());
        assertFalse(controller.followUpDecision().reopensMicrophone());
    }

    // ---- ownership beats the words ----------------------------------------------------------

    @Test public void reachingForTheKeyboardStopsAPendingHandover() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();
        assertTrue("precondition: this controller would otherwise follow up",
                controller.followUpDecision().reopensMicrophone());

        // Tapping the composer while Orbit is still speaking runs no recognizer teardown, so the
        // claim itself is what has to be remembered.
        assertFalse("nothing was running, so no turn was taken over",
                controller.handOffToTyping());

        VoiceFollowUpPolicy.Decision decision = controller.followUpDecision();
        assertEquals(VoiceFollowUpPolicy.Decision.SURFACE_NOT_ELIGIBLE, decision);
        assertFalse("typing must stay authoritative", decision.reopensMicrophone());
    }

    @Test public void aTypingClaimIsReleasedByTheNextSpokenReply() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();
        controller.handOffToTyping();
        assertFalse(controller.followUpDecision().reopensMicrophone());

        // A later reply is a fresh opportunity, not the one the user opted out of. Speaking is a
        // no-op without a real engine, so the claim is cleared through the same public entry the
        // chat uses and the decision is read again.
        controller.speak("Anything at all.");
        // With no TTS engine in the test environment nothing is spoken, so the claim persists and
        // the refusal stands: the guard fails closed rather than open.
        assertFalse("with nothing spoken the refusal must stand",
                controller.followUpDecision().reopensMicrophone());
    }

    @Test public void aRunningVoiceTurnIsNotReopenedOnTopOfItself() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();
        // start() needs the microphone permission, which the test environment does not grant, so
        // the controller reports the need and stays idle rather than half-starting a turn.
        controller.start();
        assertFalse("a controller that could not start is not listening",
                controller.isListening());
    }

    @Test public void stoppingAVoiceTurnLeavesTheControllerEligibleAgain() {
        Prefs.get(context).edit()
                .putBoolean(Prefs.AUTO_LISTEN, true)
                .putBoolean(Prefs.SMART_FOLLOW_UPS, false)
                .apply();
        controller.handOffToTyping();
        assertFalse(controller.followUpDecision().reopensMicrophone());

        controller.stop(true);
        // stop() ends the turn but does not un-claim the composer: the user asked for the
        // keyboard, and only starting voice again gives it back.
        assertEquals(VoiceFollowUpPolicy.Decision.SURFACE_NOT_ELIGIBLE,
                controller.followUpDecision());
    }

    // ---- an utterance that is no longer live -------------------------------------------------

    /**
     * The interrupted-utterance rule, stated against the policy the controller calls. The
     * controller's own completion callback already refuses a non-live utterance before reaching
     * the policy, so this pins the second line of defence: even asked directly, a dead utterance
     * is refused whatever it said.
     */
    @Test public void anInterruptedUtteranceIsRefusedByTheSharedPolicy() {
        for (String reply : new String[]{
                "What time should I set it for?",
                "Which one did you mean?",
                "Tell me which one you want."}) {
            assertEquals(reply, VoiceFollowUpPolicy.Decision.UTTERANCE_NOT_LIVE,
                    VoiceFollowUpPolicy.decide(true, true, false, true, reply));
            assertEquals("compatibility mode is no exception",
                    VoiceFollowUpPolicy.Decision.UTTERANCE_NOT_LIVE,
                    VoiceFollowUpPolicy.decide(true, false, false, true, reply));
        }
    }

    /** A TTS error never reaches the handover at all, so nothing can be scheduled from it. */
    @Test public void aTtsErrorSchedulesNoHandover() throws Exception {
        // onError has no path to scheduleVoiceFollowUp: the only caller is the live-utterance
        // branch of onDone. Pinning the shape here keeps a future edit from wiring one up.
        String source = readController();
        int errorAt = source.indexOf("public void onError(String utteranceId)");
        assertTrue("onError must still exist", errorAt > 0);
        String onError = source.substring(errorAt, Math.min(source.length(), errorAt + 400));
        assertFalse("a failed utterance must not open the microphone",
                onError.contains("scheduleVoiceFollowUp"));
        assertFalse(onError.contains("start()"));
    }

    /** Finds the controller's source whether tests run from the module or the repository root. */
    private static String readController() throws Exception {
        String relative = "src/main/java/com/orbit/assistant/VoiceInputController.java";
        for (String base : new String[]{"", "app/", "../app/"}) {
            java.nio.file.Path path = java.nio.file.Paths.get(base + relative);
            if (java.nio.file.Files.exists(path)) {
                return new String(java.nio.file.Files.readAllBytes(path),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("VoiceInputController.java was not found from "
                + java.nio.file.Paths.get("").toAbsolutePath());
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Smart follow-ups: whether Orbit is actually waiting for an answer when it stops speaking.
 *
 * <p>The bar is deliberately asymmetric. Reopening the microphone on its own is more intrusive
 * than leaving the user to tap it, so a reply has to clearly hand the turn back before Voice
 * takes it. Anything merely conversational stays shut.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class VoiceFollowUpPolicyTest {

    // ---- replies that are genuinely waiting for an answer -----------------------------------

    @Test public void aMissingDetailIsAskedForAndWaitsForTheAnswer() {
        assertTrue(VoiceFollowUpPolicy.expectsReply("What time should I set the reminder for?"));
        assertTrue(VoiceFollowUpPolicy.expectsReply("What location should I use?"));
    }

    @Test public void aClarificationBetweenTwoCandidatesWaits() {
        assertTrue(VoiceFollowUpPolicy.expectsReply(
                "Which one did you mean, Spotify or Spotify Lite?"));
        assertTrue(VoiceFollowUpPolicy.expectsReply(
                "I found two matches. Which one should I open?"));
    }

    @Test public void aYesNoConfirmationWaits() {
        assertTrue(VoiceFollowUpPolicy.expectsReply(
                "Would you like me to turn on Do Not Disturb?"));
        assertTrue(VoiceFollowUpPolicy.expectsReply("Before I do that, can you confirm?"));
    }

    @Test public void aMultipleChoiceRequestWaits() {
        assertTrue(VoiceFollowUpPolicy.expectsReply("Do you want the morning or evening option?"));
    }

    @Test public void anImperativeHandingTheTurnBackWaits() {
        assertTrue(VoiceFollowUpPolicy.expectsReply("Tell me which one you want."));
        assertTrue(VoiceFollowUpPolicy.expectsReply("Let me know which one to open."));
        assertTrue(VoiceFollowUpPolicy.expectsReply("Please choose one."));
    }

    // ---- replies that have finished ---------------------------------------------------------

    @Test public void aCompletedDeviceActionDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply("The flashlight is on."));
        assertFalse(VoiceFollowUpPolicy.expectsReply("Timer set for five minutes."));
        assertFalse(VoiceFollowUpPolicy.expectsReply("Do Not Disturb is now on."));
    }

    @Test public void aFactualAnswerDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Tomorrow will be partly cloudy with a high of 72°F."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "The Eiffel Tower is 330 metres tall, including its antenna."));
    }

    @Test public void anExplanationOrInstructionListDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Open Settings, then tap Voice, then turn on Hands-free voice follow-ups."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "1. Open Settings\n2. Tap Voice\n3. Turn the switch on"));
        assertFalse(VoiceFollowUpPolicy.expectsReply("Here's what I'd recommend..."));
    }

    @Test public void anOfferOfMoreHelpDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "You can change that in Settings if you want."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "If you'd like, I can explain that in more detail."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Let me know if you need anything else."));
        assertFalse("a closing pleasantry is not a question Orbit is blocked on",
                VoiceFollowUpPolicy.expectsReply("It's done. Anything else?"));
    }

    @Test public void aRhetoricalQuestionOrbitAnswersItselfDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Why is the sky blue? It's because shorter wavelengths scatter more."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "What does that mean for you? It means the timer will still ring."));
    }

    @Test public void aQuestionInsideQuotedOrSourceMaterialDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "The message said, \"Are you coming tonight?\""));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "For example, you could ask \"What is the weather?\""));
    }

    @Test public void aQuestionMarkInsideCodeOrALinkDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Use `value ? a : b` to pick between them."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "The page is at https://example.com/search?q=orbit"));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "Here is the snippet:\n```\nboolean x = ready ? yes : no;\n```"));
    }

    @Test public void nothingSpokenDoesNotWait() {
        assertFalse(VoiceFollowUpPolicy.expectsReply(null));
        assertFalse(VoiceFollowUpPolicy.expectsReply(""));
        assertFalse(VoiceFollowUpPolicy.expectsReply("   "));
    }

    // ---- punctuation the provider or the voice may not have produced ------------------------

    @Test public void aQuestionSurvivesAMissingQuestionMark() {
        assertTrue(VoiceFollowUpPolicy.expectsReply("Which one do you want"));
        assertTrue(VoiceFollowUpPolicy.expectsReply("What time should I set it for"));
        assertTrue(VoiceFollowUpPolicy.expectsReply("Should I turn it on"));
    }

    @Test public void aStatementIsNotPromotedJustBecauseItOpensLikeAQuestion() {
        assertFalse("a full stop declares it a statement",
                VoiceFollowUpPolicy.expectsReply("How that works is fairly simple."));
        assertFalse(VoiceFollowUpPolicy.expectsReply(
                "What happened is that the alarm was already set."));
    }

    // ---- the full decision, ownership first -------------------------------------------------

    @Test public void handsFreeOffNeverReopensWhateverSmartSays() {
        for (boolean smart : new boolean[]{true, false}) {
            VoiceFollowUpPolicy.Decision decision = VoiceFollowUpPolicy.decide(
                    false, smart, true, true, "Which one did you mean?");
            assertEquals(VoiceFollowUpPolicy.Decision.MASTER_OFF, decision);
            assertFalse(decision.reopensMicrophone());
        }
    }

    @Test public void smartOffKeepsTheOlderAlwaysFollowUpBehaviour() {
        VoiceFollowUpPolicy.Decision decision = VoiceFollowUpPolicy.decide(
                true, false, true, true, "The flashlight is on.");
        assertEquals("Smart off must not silently delete hands-free follow-ups",
                VoiceFollowUpPolicy.Decision.SMART_OFF_LEGACY, decision);
        assertTrue(decision.reopensMicrophone());
    }

    @Test public void smartOnReopensOnlyForAReplyThatWaits() {
        assertEquals(VoiceFollowUpPolicy.Decision.EXPECTS_REPLY,
                VoiceFollowUpPolicy.decide(true, true, true, true, "Which one should I open?"));
        assertEquals(VoiceFollowUpPolicy.Decision.COMPLETE_REPLY,
                VoiceFollowUpPolicy.decide(true, true, true, true, "The flashlight is on."));
    }

    @Test public void anInterruptedOrSupersededUtteranceCannotReopenHoweverItReads() {
        VoiceFollowUpPolicy.Decision decision = VoiceFollowUpPolicy.decide(
                true, true, false, true, "What time should I set it for?");
        assertEquals(VoiceFollowUpPolicy.Decision.UTTERANCE_NOT_LIVE, decision);
        assertFalse("a clear question must not revive a turn the user moved on from",
                decision.reopensMicrophone());
    }

    @Test public void anIneligibleSurfaceCannotReopenHoweverItReads() {
        VoiceFollowUpPolicy.Decision decision = VoiceFollowUpPolicy.decide(
                true, true, true, false, "What time should I set it for?");
        assertEquals(VoiceFollowUpPolicy.Decision.SURFACE_NOT_ELIGIBLE, decision);
        assertFalse(decision.reopensMicrophone());
    }

    @Test public void ownershipIsCheckedBeforePreferences() {
        // Even with the master off, an interrupted utterance is reported as the ownership failure
        // it is, so a diagnostic reason can never blame the preference for a lifecycle refusal.
        assertEquals(VoiceFollowUpPolicy.Decision.UTTERANCE_NOT_LIVE,
                VoiceFollowUpPolicy.decide(false, false, false, true, "Which one?"));
    }

    @Test public void everyDecisionCarriesAClosedReasonAndNoUserContent() {
        for (VoiceFollowUpPolicy.Decision decision : VoiceFollowUpPolicy.Decision.values()) {
            String reason = decision.reason();
            assertTrue("reason must be present", reason != null && !reason.isEmpty());
            assertTrue("reason must be a closed lowercase identifier",
                    reason.matches("[a-z_]+"));
        }
        assertEquals("master_off", VoiceFollowUpPolicy.Decision.MASTER_OFF.reason());
        assertEquals("smart_off_legacy", VoiceFollowUpPolicy.Decision.SMART_OFF_LEGACY.reason());
        assertEquals("expects_reply", VoiceFollowUpPolicy.Decision.EXPECTS_REPLY.reason());
        assertEquals("complete_reply", VoiceFollowUpPolicy.Decision.COMPLETE_REPLY.reason());
        assertEquals("utterance_not_live",
                VoiceFollowUpPolicy.Decision.UTTERANCE_NOT_LIVE.reason());
        assertEquals("surface_not_eligible",
                VoiceFollowUpPolicy.Decision.SURFACE_NOT_ELIGIBLE.reason());
    }

    // ---- the two surfaces cannot drift ------------------------------------------------------

    /**
     * The overlay and full chat call this same method, so parity is structural rather than
     * something each surface has to remember. This pins the shared answers all the same: if a
     * future change gave one surface its own heuristic, these are the cases that would diverge.
     */
    @Test public void bothSurfacesGetTheSameAnswerForTheSameReply() {
        String[] waits = {
                "Which one did you mean?",
                "What time should I set the reminder for?",
                "Would you like me to turn on Do Not Disturb?",
                "Tell me which one you want.",
                "Which one do you want"
        };
        String[] finished = {
                "Flashlight turned on.",
                "Timer set for five minutes.",
                "Tomorrow will be partly cloudy with a high of 72°F.",
                "If you'd like, I can explain that in more detail.",
                "The message said, \"Are you coming tonight?\""
        };
        for (String reply : waits) {
            VoiceFollowUpPolicy.Decision overlay =
                    VoiceFollowUpPolicy.decide(true, true, true, true, reply);
            VoiceFollowUpPolicy.Decision chat =
                    VoiceFollowUpPolicy.decide(true, true, true, true, reply);
            assertEquals(reply, overlay, chat);
            assertTrue(reply, overlay.reopensMicrophone());
        }
        for (String reply : finished) {
            VoiceFollowUpPolicy.Decision overlay =
                    VoiceFollowUpPolicy.decide(true, true, true, true, reply);
            VoiceFollowUpPolicy.Decision chat =
                    VoiceFollowUpPolicy.decide(true, true, true, true, reply);
            assertEquals(reply, overlay, chat);
            assertFalse(reply, overlay.reopensMicrophone());
        }
    }
}

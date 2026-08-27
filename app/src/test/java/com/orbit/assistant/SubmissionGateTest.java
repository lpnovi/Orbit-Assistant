package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One human send gesture, one accepted submission.
 *
 * <p>A diagnostics trace from a real device recorded the same prompt being submitted twice about
 * 450 ms apart. Full chat had no gate at all: the send button, the keyboard's Send key, and the
 * voice controller's final transcript could each start a turn independently, and the most awkward
 * of those is genuinely late — the recogniser finalises a few hundred milliseconds after the user
 * has already pressed Send, with the same words.
 *
 * <p>The last case matters as much as the rest: sending the same words again on purpose, later,
 * has to keep working. A gate that permanently remembered text would be a different bug.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SubmissionGateTest {

    private static final String CHAT = "conversation-1";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SubmissionGate.resetForTest();
        DiagnosticStore.prefs(context).edit().clear().commit();
    }

    @After public void tearDown() {
        SubmissionGate.resetForTest();
    }

    private static List<String> noActiveRequests() {
        return Collections.emptyList();
    }

    private SubmissionGate.Decision offer(String text, String source, long now) {
        return SubmissionGate.offer(noActiveRequests(), CHAT, text, source, now);
    }

    /** A complete accepted submission: the gate says yes, then the request is created. */
    private void completeSubmission(String text, String source, long now) {
        assertTrue(offer(text, source, now).accepted);
        SubmissionGate.settle(CHAT);
    }

    // ---- one gesture ---------------------------------------------------------------------------

    @Test public void oneSendButtonTapProducesOneAcceptedSubmission() {
        SubmissionGate.Decision first = offer("what is the weather", SubmissionGate.SOURCE_BUTTON, 1_000L);
        assertTrue(first.accepted);
        SubmissionGate.settle(CHAT);

        SubmissionGate.Decision second = offer("what is the weather",
                SubmissionGate.SOURCE_BUTTON, 1_450L);
        assertFalse("a second tap 450 ms later is the same gesture", second.accepted);
        assertEquals(SubmissionGate.REASON_WINDOW, second.reason);
    }

    /**
     * The button and the keyboard's Send key racing. The first one in holds the claim until its
     * request exists, so the second cannot slip past even before the timing window applies.
     */
    @Test public void aButtonAndKeyboardRaceProducesOneSubmission() {
        SubmissionGate.Decision button = offer("send this", SubmissionGate.SOURCE_BUTTON, 5_000L);
        assertTrue(button.accepted);

        SubmissionGate.Decision ime = offer("send this", SubmissionGate.SOURCE_IME, 5_000L);
        assertFalse(ime.accepted);
        assertEquals("the claim is what refuses it, not the clock",
                SubmissionGate.REASON_IN_FLIGHT, ime.reason);

        SubmissionGate.settle(CHAT);
    }

    /** Different words racing are still refused while a submission is being accepted. */
    @Test public void nothingElseSlipsThroughWhileASubmissionIsBeingAccepted() {
        assertTrue(offer("first message", SubmissionGate.SOURCE_BUTTON, 9_000L).accepted);
        assertTrue(SubmissionGate.isClaimed(CHAT));

        SubmissionGate.Decision other = offer("a different message",
                SubmissionGate.SOURCE_IME, 9_000L);
        assertFalse(other.accepted);
        assertEquals(SubmissionGate.REASON_IN_FLIGHT, other.reason);

        SubmissionGate.settle(CHAT);
        assertFalse(SubmissionGate.isClaimed(CHAT));
        assertTrue(offer("a different message", SubmissionGate.SOURCE_IME, 9_100L).accepted);
    }

    /** The voice controller finalising just after the user already pressed Send. */
    @Test public void aLateVoiceTranscriptCannotResendWhatWasJustSent() {
        completeSubmission("set a timer for ten minutes", SubmissionGate.SOURCE_BUTTON, 20_000L);

        SubmissionGate.Decision voice = offer("set a timer for ten minutes",
                SubmissionGate.SOURCE_VOICE, 20_300L);
        assertFalse(voice.accepted);
        assertEquals(SubmissionGate.REASON_WINDOW, voice.reason);
    }

    @Test public void aRepeatedCallbackForOneGestureCommitsOnce() {
        int accepted = 0;
        for (int i = 0; i < 5; i++) {
            SubmissionGate.Decision decision = offer("hello", SubmissionGate.SOURCE_VOICE, 30_000L);
            if (decision.accepted) accepted++;
        }
        assertEquals(1, accepted);
        SubmissionGate.settle(CHAT);
    }

    /** Case and spacing do not make two gestures different. */
    @Test public void trivialTextDifferencesAreStillTheSameGesture() {
        completeSubmission("What is the weather?", SubmissionGate.SOURCE_BUTTON, 40_000L);
        assertFalse(offer("what is   the weather?", SubmissionGate.SOURCE_IME, 40_200L).accepted);
        assertFalse(offer("  WHAT IS THE WEATHER? ", SubmissionGate.SOURCE_VOICE, 40_400L).accepted);
    }

    // ---- the durable identity check --------------------------------------------------------------

    /**
     * The structural gate, and the one that survives a recreated Activity: a conversation cannot
     * hold two in-flight requests carrying the same prompt.
     */
    @Test public void anIdenticalRequestAlreadyRunningRefusesAnother() {
        List<String> active = new ArrayList<>(Arrays.asList("put the schedule on my calendar"));
        SubmissionGate.Decision decision = SubmissionGate.offer(active, CHAT,
                "Put the schedule on my calendar", SubmissionGate.SOURCE_BUTTON, 100_000L);

        assertFalse(decision.accepted);
        assertEquals(SubmissionGate.REASON_ACTIVE_REQUEST, decision.reason);
    }

    @Test public void aDifferentPromptIsStillAcceptedWhileOneIsRunning() {
        List<String> active = new ArrayList<>(Arrays.asList("what is the weather"));
        assertTrue(SubmissionGate.offer(active, CHAT, "and tomorrow?",
                SubmissionGate.SOURCE_BUTTON, 100_000L).accepted);
    }

    @Test public void theActiveCheckIsPerConversation() {
        List<String> active = new ArrayList<>(Arrays.asList("same words"));
        assertFalse(SubmissionGate.offer(active, CHAT, "same words",
                SubmissionGate.SOURCE_BUTTON, 1L).accepted);
        // Another chat has its own active requests; nothing about this one applies there.
        assertTrue(SubmissionGate.offer(noActiveRequests(), "conversation-2", "same words",
                SubmissionGate.SOURCE_BUTTON, 1L).accepted);
    }

    @Test public void oneConversationsClaimDoesNotBlockAnother() {
        assertTrue(offer("hello", SubmissionGate.SOURCE_BUTTON, 1L).accepted);
        assertTrue(SubmissionGate.offer(noActiveRequests(), "conversation-2", "hello",
                SubmissionGate.SOURCE_BUTTON, 1L).accepted);
    }

    // ---- the user is still allowed to repeat themselves --------------------------------------------

    @Test public void thesameWordsSentDeliberatelyLaterAreAccepted() {
        completeSubmission("try again", SubmissionGate.SOURCE_BUTTON, 200_000L);

        long later = 200_000L + SubmissionGate.DUPLICATE_WINDOW_MS + 1L;
        SubmissionGate.Decision again = offer("try again", SubmissionGate.SOURCE_BUTTON, later);
        assertTrue("suppressing repeated text forever would be a different bug", again.accepted);
        SubmissionGate.settle(CHAT);
    }

    @Test public void theWindowIsShortEnoughToBeUnnoticeable() {
        assertTrue("a guard long enough to feel broken is not a guard",
                SubmissionGate.DUPLICATE_WINDOW_MS <= 2_000L);
        assertTrue("but long enough to cover a late voice finalisation",
                SubmissionGate.DUPLICATE_WINDOW_MS >= 1_000L);
    }

    // ---- nothing empty ever becomes a turn ------------------------------------------------------------

    @Test public void emptyGesturesAreNeverAccepted() {
        assertFalse(offer("", SubmissionGate.SOURCE_BUTTON, 1L).accepted);
        assertFalse(offer("   ", SubmissionGate.SOURCE_IME, 1L).accepted);
        assertFalse(offer(null, SubmissionGate.SOURCE_VOICE, 1L).accepted);
        assertFalse("an empty gesture must not take the claim either",
                SubmissionGate.isClaimed(CHAT));
    }

    // ---- the surfaces agree ----------------------------------------------------------------------------

    /** Both composers reach the gate, so the overlay and full chat obey one rule. */
    @Test public void bothSurfacesRouteEverySubmissionThroughTheGate() {
        String chat = readSource("ChatActivity.java");
        assertTrue(chat.contains("SubmissionGate.offer(this, conversationId, q, source)"));
        assertTrue(chat.contains("SubmissionGate.settle(conversationId)"));
        assertTrue("the send button names its source",
                chat.contains("submit(false, SubmissionGate.SOURCE_BUTTON)"));
        assertTrue("and so does the keyboard's Send key",
                chat.contains("submit(false, SubmissionGate.SOURCE_IME)"));

        String overlay = readSource("OrbitSession.java");
        assertTrue(overlay.contains("SubmissionGate.offer(getContext(), conversationId, q,"));
        assertTrue(overlay.contains("SubmissionGate.settle(conversationId)"));
    }

    /**
     * The keyboard's Send key used to bypass the composer's Stop state entirely, which is how a
     * second turn could start behind a reply that was already generating.
     */
    @Test public void theKeyboardSendKeyRespectsTheStopState() {
        String chat = readSource("ChatActivity.java");
        int imeAction = chat.indexOf("EditorInfo.IME_ACTION_SEND) return false");
        assertTrue("the editor action listener must still exist", imeAction > 0);
        String listener = chat.substring(imeAction, Math.min(chat.length(), imeAction + 400));
        assertTrue("the Send key must stop a running reply rather than start another",
                listener.contains("if (showingStop) stopGenerating();"));
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    @Test public void diagnosticsCountDecisionsWithoutRecordingWhatWasTyped() {
        SubmissionGate.offer(context, CHAT, "my bank password is hunter2",
                SubmissionGate.SOURCE_BUTTON);
        SubmissionGate.settle(CHAT);
        SubmissionGate.offer(context, CHAT, "my bank password is hunter2",
                SubmissionGate.SOURCE_IME);

        assertEquals(1, DiagnosticStore.prefs(context).getInt("submissions_accepted", 0));
        assertEquals(1, DiagnosticStore.prefs(context).getInt("submissions_suppressed", 0));
        assertEquals(SubmissionGate.SOURCE_IME,
                DiagnosticStore.prefs(context).getString("submission_source", ""));
        assertEquals(SubmissionGate.REASON_WINDOW,
                DiagnosticStore.prefs(context).getString("submission_suppressed_reason", ""));

        String trace = ComposerTrace.report();
        assertTrue(trace.contains("submit.accepted"));
        assertTrue(trace.contains("submit.suppressed"));
        assertFalse("prompt text must never reach a diagnostics buffer",
                trace.contains("hunter2"));
        assertFalse(trace.contains("password"));
        for (String value : DiagnosticStore.prefs(context).getAll().values().stream()
                .filter(v -> v instanceof String).map(Object::toString)
                .collect(java.util.stream.Collectors.toList())) {
            assertFalse(value.contains("hunter2"));
        }
    }

    private static String readSource(String fileName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + fileName);
    }
}

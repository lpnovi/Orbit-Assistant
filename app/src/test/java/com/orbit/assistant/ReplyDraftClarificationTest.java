package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Telling a question apart from a message, and giving the controls to the right turn.
 *
 * <h2>The device failure this file exists for</h2>
 *
 * <p>Drafting a reply to a Discord group chat, Orbit could not tell which participant the user was
 * and correctly asked: <em>"Which participant are you in this chat, Little Lu or Nimpy Impy?"</em>
 * The overlay then offered <b>Copy</b>, <b>Use in chat</b> and <b>Regenerate</b> underneath it, i.e.
 * offered to send Orbit's own question into the group. The user answered, Orbit produced the real
 * draft — and the controls stayed attached to the question.
 *
 * <p>Two separate things were missing: any notion of <em>what kind</em> of output a turn was, and
 * any binding between a control row and the turn it belonged to.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ReplyDraftClarificationTest {

    private Context context;
    private static final String CONVERSATION = "test-conversation";

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        ReplyDraftContext.clear(context, CONVERSATION);
    }

    // ---- the classification --------------------------------------------------------------------

    @Test public void aMarkedDraftIsSendable() {
        ReplyDraftOutcome outcome = ReplyDraftOutcome.parse(
                "[[ORBIT_DRAFT]]That sucks 😭 hopefully it unfreezes soon.");
        assertEquals(ReplyDraftOutcome.Kind.DRAFT, outcome.kind);
        assertTrue(outcome.isSendableDraft());
        assertEquals("That sucks 😭 hopefully it unfreezes soon.", outcome.text);
    }

    @Test public void aMarkedClarificationIsNot() {
        ReplyDraftOutcome outcome = ReplyDraftOutcome.parse(
                "[[ORBIT_ASK]]Which participant are you in this chat, Little Lu or Nimpy Impy?");
        assertEquals(ReplyDraftOutcome.Kind.CLARIFICATION, outcome.kind);
        assertFalse("Orbit's own question is never something to send", outcome.isSendableDraft());
        assertEquals("Which participant are you in this chat, Little Lu or Nimpy Impy?",
                outcome.text);
    }

    /**
     * The case that rules out every punctuation heuristic.
     *
     * <p>A drafted message can be a question. "It ends in a question mark" would have classified
     * this as a clarification and taken away the control the user actually wanted.
     */
    @Test public void aDraftEndingInAQuestionMarkIsStillADraft() {
        ReplyDraftOutcome outcome =
                ReplyDraftOutcome.parse("[[ORBIT_DRAFT]]Do you still want to go tonight?");
        assertEquals(ReplyDraftOutcome.Kind.DRAFT, outcome.kind);
        assertTrue(outcome.isSendableDraft());
        assertEquals("Do you still want to go tonight?", outcome.text);
    }

    /** And a clarification without a question mark is still a clarification. */
    @Test public void aClarificationWithoutAQuestionMarkIsStillAClarification() {
        ReplyDraftOutcome outcome = ReplyDraftOutcome.parse(
                "[[ORBIT_ASK]]Tell me which of the two people on screen is you.");
        assertEquals(ReplyDraftOutcome.Kind.CLARIFICATION, outcome.kind);
        assertFalse(outcome.isSendableDraft());
    }

    /** Nothing about the classification can be derived from the prose, and nothing tries. */
    @Test public void theClassifierReadsOnlyTheMarker() {
        for (String prose : new String[]{
                "Which participant are you?",
                "Who are you in this chat?",
                "Do you still want to go tonight?",
                "That sucks, hopefully it unfreezes soon.",
                "?"}) {
            assertEquals("unmarked prose is never classified by its shape: " + prose,
                    ReplyDraftOutcome.Kind.UNKNOWN, ReplyDraftOutcome.parse(prose).kind);
        }
    }

    /** An unmarked reply fails safe: usable, but never offered as something to send. */
    @Test public void anUnmarkedReplyFailsSafe() {
        ReplyDraftOutcome outcome = ReplyDraftOutcome.parse("Sure, here you go.");
        assertEquals(ReplyDraftOutcome.Kind.UNKNOWN, outcome.kind);
        assertFalse("a reply Orbit cannot vouch for is not offered for sending",
                outcome.isSendableDraft());
        assertEquals("but the text is untouched", "Sure, here you go.", outcome.text);
    }

    @Test public void emptyAndNullOutputAreHandled() {
        assertEquals(ReplyDraftOutcome.Kind.UNKNOWN, ReplyDraftOutcome.parse(null).kind);
        assertEquals(ReplyDraftOutcome.Kind.UNKNOWN, ReplyDraftOutcome.parse("   ").kind);
        assertEquals("", ReplyDraftOutcome.parse(null).text);
    }

    // ---- the marker never reaches a person -------------------------------------------------------

    @Test public void theMarkerIsStrippedFromWhatTheUserSees() {
        assertFalse(ReplyDraftOutcome.parse("[[ORBIT_DRAFT]]Hello").text.contains("ORBIT_DRAFT"));
        assertFalse(ReplyDraftOutcome.parse("[[ORBIT_ASK]]Who?").text.contains("ORBIT_ASK"));
        assertEquals("Hello", ReplyDraftOutcome.parse("  [[ORBIT_DRAFT]]  Hello  ").text);
    }

    /** And a stray marker anywhere is removed even by paths that never classify. */
    @Test public void strayMarkersAreRemovedWherverTheyAppear() {
        assertEquals("Hello there",
                ReplyDraftOutcome.strip("Hello [[ORBIT_DRAFT]]there"));
        assertEquals("Hello", ReplyDraftOutcome.strip("[[ORBIT_ASK]]Hello[[ORBIT_DRAFT]]"));
        assertEquals("", ReplyDraftOutcome.strip(null));
        assertEquals("untouched", ReplyDraftOutcome.strip("untouched"));
    }

    /** The stored conversation carries the reply, not Orbit's bookkeeping. */
    @Test public void markersAreNotPersistedIntoTheConversation() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the reply is parsed before it is stored",
                session.contains("ReplyDraftOutcome.parse(text)"));
        assertTrue("and what is stored is the stripped text",
                session.contains("outcome.text.isEmpty() ? text : outcome.text"));
    }

    // ---- the flow ------------------------------------------------------------------------------------

    /**
     * Answering Orbit's question is still part of the drafting flow.
     *
     * <p>"Im neither im Lou and this is a gc" matches no draft-request wording rule, so before this
     * the turn that finally produced the sendable draft was not treated as a draft turn at all — and
     * got no controls, which is why the stale ones were the only ones on screen.
     */
    @Test public void answeringAClarificationStaysInTheDraftFlow() {
        String request = "draft a reply to this conversation on my screen";
        assertTrue(ReplyDraftContext.isReplyDraftTurn(context, CONVERSATION, request));

        String answer = "Im neither im Lou and this is a gc";
        assertFalse("the answer is not a draft request by wording",
                ReplyDraftContext.isDraftRequest(answer));
        assertFalse("and is not in the flow until Orbit has actually asked something",
                ReplyDraftContext.isReplyDraftTurn(context, CONVERSATION, answer));

        ReplyDraftContext.recordOutcome(context, CONVERSATION,
                ReplyDraftOutcome.Kind.CLARIFICATION);
        assertTrue("once Orbit has asked, the answer belongs to the flow",
                ReplyDraftContext.isReplyDraftTurn(context, CONVERSATION, answer));
    }

    /** And a sendable draft closes the flow, so ordinary conversation afterwards is ordinary. */
    @Test public void aDraftEndsTheClarificationFlow() {
        ReplyDraftContext.recordOutcome(context, CONVERSATION,
                ReplyDraftOutcome.Kind.CLARIFICATION);
        assertTrue(ReplyDraftContext.awaitingClarification(context, CONVERSATION));

        ReplyDraftContext.recordOutcome(context, CONVERSATION, ReplyDraftOutcome.Kind.DRAFT);
        assertFalse(ReplyDraftContext.awaitingClarification(context, CONVERSATION));
        assertFalse("a later unrelated message is not a draft turn",
                ReplyDraftContext.isReplyDraftTurn(context, CONVERSATION, "what's the weather"));
    }

    /** The flow state is conversation-scoped and survives a process rebuild, because it is stored. */
    @Test public void theFlowStateSurvivesARebuild() {
        ReplyDraftContext.recordOutcome(context, CONVERSATION,
                ReplyDraftOutcome.Kind.CLARIFICATION);
        // Nothing is held in memory: a fresh read is what any rebuilt process would do.
        assertTrue(ReplyDraftContext.awaitingClarification(context, CONVERSATION));
        assertFalse("and it does not leak into another conversation",
                ReplyDraftContext.awaitingClarification(context, "some-other-conversation"));
    }

    /** The contract reaches the model on the request's own trusted-context channel. */
    @Test public void theContractIsAttachedToDraftTurnsOnly() {
        List<AssistantClient.History> empty = new ArrayList<>();
        String draftTurn = ReplyDraftContext.observeAndGet(context, CONVERSATION,
                "draft a reply to the message on my screen", "some screen text", null, empty);
        assertTrue("a draft turn carries the classification contract",
                draftTurn.contains("[[ORBIT_DRAFT]]") && draftTurn.contains("[[ORBIT_ASK]]"));

        ReplyDraftContext.clear(context, "plain-conversation");
        String ordinaryTurn = ReplyDraftContext.observeAndGet(context, "plain-conversation",
                "what is the capital of France", "", null, empty);
        assertFalse("an ordinary turn does not",
                ordinaryTurn.contains("[[ORBIT_DRAFT]]"));
    }

    // ---- the controls bind to a turn -------------------------------------------------------------------

    /**
     * A clarification gets Copy and Regenerate, and never an insert control.
     *
     * <p>Asserted from the overlay's source, which is how {@code OrbitSession} — a
     * VoiceInteractionSession, not constructible in a unit test — is pinned elsewhere in this suite.
     */
    @Test public void onlyASendableDraftEverOffersAnInsertControl() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the insert control requires a known draft on an insertable surface",
                session.contains(
                        "kind == ReplyDraftOutcome.Kind.DRAFT && ReplySurface.canInsert(surface)"));
    }

    /** The visible Regenerate control in the overlay stays, on every kind of reply-draft turn. */
    @Test public void theVisibleRegenerateControlIsPreserved() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("the small Regenerate button must still be built",
                session.contains("regen.setContentDescription(\"Regenerate response\")"));
        assertTrue("and still regenerate", session.contains("regenerateLastResponse()"));
        // It is added on the canRegenerate branch, which is outside the draft/insert gate, so a
        // clarification keeps it.
        int insertGate = session.indexOf(
                "kind == ReplyDraftOutcome.Kind.DRAFT && ReplySurface.canInsert(surface)");
        int regenerate = session.indexOf("regen.setContentDescription(\"Regenerate response\")");
        assertTrue("Regenerate must not sit inside the insert gate",
                insertGate > 0 && regenerate > insertGate);
    }

    /** Controls belong to a turn, and a stale row cannot act once a newer turn arrives. */
    @Test public void controlsAreBoundToTheTurnTheyBelongTo() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue("a row knows its turn", session.contains("private boolean ownsDraftTurn("));
        assertTrue("the previous row is detached when a new turn arrives",
                session.contains("clearDraftReplyActions()"));
        for (String handler : new String[]{
                "if (!ownsDraftTurn(turnId)) return;"}) {
            assertTrue("every control checks it owns the turn", session.contains(handler));
        }
        assertTrue("and the turn is the request's own identity",
                session.contains("final String draftTurnId = requestId == null ? \"\" : requestId;"));
        assertFalse("there is no single global draft string for a row to act on",
                session.contains("private String lastDraft"));
    }

    /** Only the newest assistant turn can own the controls after a conversation is redrawn. */
    @Test public void aRebuiltConversationGivesControlsOnlyToTheNewestTurn() {
        String session = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        assertTrue(session.contains("boolean draftReply = newest && i > 0"));
        assertTrue("and the clarification state decides the kind, not the text",
                session.contains("ReplyDraftContext.awaitingClarification(getContext(), conversationId)"));
    }

    /** A clarification and a draft are different kinds, which is the whole point. */
    @Test public void theTwoKindsAreNotInterchangeable() {
        assertNotEquals(ReplyDraftOutcome.Kind.DRAFT, ReplyDraftOutcome.Kind.CLARIFICATION);
        assertFalse(ReplyDraftOutcome.parse("[[ORBIT_ASK]]Who are you?").isSendableDraft());
        assertTrue(ReplyDraftOutcome.parse("[[ORBIT_DRAFT]]Who are you?").isSendableDraft());
    }
}

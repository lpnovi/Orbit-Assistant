package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What may become a visible thinking update, and what it is turned into first.
 *
 * <p>The type carries two very different things — text a provider published for a person to read,
 * and Orbit's own description of what Orbit is doing — and the difference has to survive being
 * moved around the app. These pin the construction rules that keep it honest, and the sanitizing
 * that keeps arbitrary provider text from arriving at a {@code TextView} as anything but one short
 * plain line.
 */
public final class ThinkingUpdateTest {

    // ---- the two sources cannot be confused for each other --------------------------------------

    @Test public void orbitProgressIsNeverLabelledAsComingFromTheProvider() {
        ThinkingUpdate working = ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING);
        assertNotNull(working);
        assertEquals(ThinkingUpdate.Source.ORBIT_PROGRESS, working.source());
        assertFalse(working.fromProvider());
    }

    @Test public void aProviderSummaryIsTheOnlyThingLabelledAsOne() {
        ThinkingUpdate summary = ThinkingUpdate.providerSummary("Comparing the approaches");
        assertNotNull(summary);
        assertTrue(summary.fromProvider());
        assertEquals(ThinkingUpdate.Stage.PROVIDER_REASONING_SUMMARY, summary.stage);
    }

    /**
     * The progress door refuses the summary stage. Without this, any Orbit code path could mint
     * something that claims to be the model's own words.
     */
    @Test public void theProgressFactoryRefusesToMintAProviderSummary() {
        assertNull(ThinkingUpdate.progress(ThinkingUpdate.Stage.PROVIDER_REASONING_SUMMARY));
        assertNull(ThinkingUpdate.progress(
                ThinkingUpdate.Stage.PROVIDER_REASONING_SUMMARY, "I considered three theories"));
    }

    @Test public void everyOrbitProgressStageIsOrbitProgress() {
        for (ThinkingUpdate.Stage stage : ThinkingUpdate.Stage.values()) {
            if (stage == ThinkingUpdate.Stage.PROVIDER_REASONING_SUMMARY) continue;
            assertEquals(stage + " must be Orbit describing its own execution",
                    ThinkingUpdate.Source.ORBIT_PROGRESS, stage.source());
        }
    }

    // ---- nothing empty or invented ever reaches the UI ------------------------------------------

    @Test public void emptyAndWhitespaceOnlyTextProduceNothingAtAll() {
        assertNull(ThinkingUpdate.providerSummary(null));
        assertNull(ThinkingUpdate.providerSummary(""));
        assertNull(ThinkingUpdate.providerSummary("   \n\t  "));
        assertNull(ThinkingUpdate.progress(ThinkingUpdate.Stage.MODEL_REASONING, ""));
        assertNull(ThinkingUpdate.progress(null));
    }

    /** A stage with no wording of its own must not produce a blank status line. */
    @Test public void modelReasoningWithoutSuppliedTextProducesNothing() {
        assertNull(ThinkingUpdate.progress(ThinkingUpdate.Stage.MODEL_REASONING));
    }

    @Test public void orbitProgressStagesCarryOrbitsOwnWording() {
        assertEquals("Thinking…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WORKING).text);
        assertEquals("Using your screen for context…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.SCREEN_CONTEXT).text);
        assertEquals("Searching the web…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WEB_SEARCH).text);
        assertEquals("Reading the search results…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WEB_RESULTS).text);
        assertEquals("Running on your phone…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.LOCAL_INFERENCE).text);
    }

    /** Orbit names a model only when it recognises it, and never guesses one. */
    @Test public void modelReasoningNamesOnlyModelsOrbitKnows() {
        assertEquals("Reasoning with Sol…", ThinkingUpdate.modelReasoning("gpt-5.6-sol").text);
        assertEquals("Reasoning with Luna…", ThinkingUpdate.modelReasoning("gpt-5.6-luna").text);
        assertEquals("Reasoning with Terra…", ThinkingUpdate.modelReasoning("gpt-5.6-terra").text);
        assertNull(ThinkingUpdate.modelReasoning("some-other-model"));
        assertNull(ThinkingUpdate.modelReasoning(""));
        assertNull(ThinkingUpdate.modelReasoning(null));
    }

    // ---- arbitrary provider text becomes one short plain line -----------------------------------

    @Test public void lineBreaksAndRunsOfWhitespaceCollapseToSingleSpaces() {
        assertEquals("Comparing the approaches then checking failure modes",
                ThinkingUpdate.providerSummary(
                        "  Comparing the   approaches\n\nthen\tchecking failure modes  ").text);
    }

    @Test public void controlCharactersAndBidiOverridesAreRemoved() {
        // A vertical tab, a right-to-left override, a zero-width space, and a non-breaking space:
        // characters that are invisible, reorder what is read, or defeat whitespace collapsing.
        String hostile = "Comparing\u000B\u202Ethe\u200B approaches\u00A0";
        String text = ThinkingUpdate.providerSummary(hostile).text;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            assertFalse("a control character reached the UI: " + (int) c, Character.isISOControl(c));
            assertFalse("a formatting character reached the UI: " + (int) c,
                    Character.getType(c) == Character.FORMAT);
        }
        assertTrue(text.contains("approaches"));
    }

    /** Markdown is discarded rather than honoured: the status line is a label, not a document. */
    @Test public void markdownMarksAreStrippedToTheirWords() {
        assertEquals("Comparing the approaches",
                ThinkingUpdate.providerSummary("**Comparing the approaches**").text);
        assertEquals("Weighing the options",
                ThinkingUpdate.providerSummary("## Weighing the options").text);
        assertEquals("Checking failure modes",
                ThinkingUpdate.providerSummary("- Checking `failure` modes").text);
    }

    @Test public void pathologicallyLongTextIsCutAtAWordBoundaryAndMarked() {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 4000) huge.append("reasoning about the problem ");
        String text = ThinkingUpdate.providerSummary(huge.toString()).text;
        assertTrue("the status line must stay bounded: " + text.length(),
                text.length() <= ThinkingUpdate.MAX_TEXT_CHARS + 1);
        assertTrue("a cut must be visible rather than silent", text.endsWith("…"));
        assertFalse("the cut must not leave a dangling space",
                text.substring(0, text.length() - 1).endsWith(" "));
    }

    /** Orbit's no-em-dash house rule applies to provider text too. */
    @Test public void emDashesAreReplacedLikeEverywhereElseInOrbit() {
        assertFalse(ThinkingUpdate.providerSummary("Comparing — then deciding").text.contains("—"));
    }

    /** Nothing that ends up in a log-shaped context may carry what an update said. */
    @Test public void toStringNeverCarriesTheText() {
        ThinkingUpdate summary = ThinkingUpdate.providerSummary("a private sounding phrase");
        assertFalse(summary.toString().contains("private"));
        assertTrue(summary.toString().contains("provider-reasoning-summary"));
    }
}

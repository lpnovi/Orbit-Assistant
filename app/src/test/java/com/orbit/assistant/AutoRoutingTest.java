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

import java.util.ArrayList;
import java.util.List;

/**
 * What Auto chooses, and — the part that kept going wrong — what it refuses to be talked into.
 *
 * <p>Real-device testing on 0.7.7.7 beta 2 produced the case this file exists for. A deliberately
 * hard architecture prompt (idempotent state machine, WorkManager retry racing an Activity
 * recreation, three architectures compared, race conditions and failure modes named, and an
 * explicit instruction to challenge its own recommendation) was answered by the middle model at
 * 62% confidence with the reason "planning or decision task". Every strong signal in it had
 * collapsed into one broad keyword bucket, and the Balanced starting bias then outweighed what was
 * left.
 *
 * <p>So the negative cases here matter as much as the escalation. Auto becoming globally more
 * expensive would be a worse bug than the one being fixed: length, politeness, and whatever app
 * happened to be on screen must each stay unable to reach Deep on their own.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AutoRoutingTest {

    /** The exact prompt used as the Deep acceptance test on the device. */
    private static final String ARCHITECTURE_PROMPT =
            "Think carefully and reason through this architecture problem step by step. Orbit "
                    + "receives a calendar permission result after an Activity has been recreated "
                    + "at the same time that a WorkManager retry resumes the same pending request. "
                    + "Design an idempotent state machine that guarantees the user action can "
                    + "execute at most once while still recovering correctly from process death. "
                    + "Compare at least three possible architectures, identify race conditions, "
                    + "persistence boundaries, and failure modes in each, challenge your initial "
                    + "recommendation, and then recommend the safest design and explain why.";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        DiagnosticStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private AutoRouter.Decision route(String prompt) {
        return AutoRouter.route(context, prompt, "", null, null, false, "");
    }

    private AutoRouter.Decision routeWithScreen(String prompt, String screenText, String category,
                                                int confidence) {
        DiagnosticStore.recordClassification(context, category, confidence, false, "test");
        return AutoRouter.route(context, prompt, screenText, null, null, false, "");
    }

    private void expectMode(String expected, String prompt) {
        AutoRouter.Decision decision = route(prompt);
        assertEquals("Auto chose " + decision.mode + " (" + decision.reason + ") for: " + prompt,
                expected, decision.mode);
    }

    private void expectFast(String prompt) { expectMode(Prefs.MODE_FAST, prompt); }
    private void expectBalanced(String prompt) { expectMode(Prefs.MODE_BALANCED, prompt); }
    private void expectDeep(String prompt) { expectMode(Prefs.MODE_DEEP, prompt); }

    /** The whole point of a mode: which model and how much reasoning it actually buys. */
    private void expectModel(String prompt, String model, String reasoning) {
        AutoRouter.Decision decision = route(prompt);
        assertEquals(model, Prefs.effectiveModelForMode(context, decision.mode, prompt));
        assertEquals(reasoning, Prefs.effectiveReasoningForMode(context, decision.mode, prompt));
    }

    // ---- the real-device regression -----------------------------------------------------------

    @Test public void deviceArchitecturePromptReachesDeepSol() {
        AutoRouter.Decision decision = route(ARCHITECTURE_PROMPT);
        assertEquals("the 0.7.7.7 beta 2 under-routing case must now reach Deep",
                Prefs.MODE_DEEP, decision.mode);
        assertEquals("gpt-5.6-sol",
                Prefs.effectiveModelForMode(context, decision.mode, ARCHITECTURE_PROMPT));
        assertEquals("high",
                Prefs.effectiveReasoningForMode(context, decision.mode, ARCHITECTURE_PROMPT));
        assertTrue("beta 2 answered this at 62%; a decisive combination should be confident",
                decision.confidence >= 88);
    }

    @Test public void deviceArchitecturePromptExplainsWhichSignalsMattered() {
        AutoRouter.Decision decision = route(ARCHITECTURE_PROMPT);
        assertTrue("several distinct reasoning dimensions should be recognised, not one bucket",
                decision.dimensions.size() >= AutoRouter.DECISIVE_DIMENSIONS);
        assertTrue(decision.dimensions.contains(ReasoningDimension.ARCHITECTURE));
        assertTrue(decision.dimensions.contains(ReasoningDimension.CONCURRENCY));
        assertTrue(decision.dimensions.contains(ReasoningDimension.LIFECYCLE));
        assertTrue(decision.dimensions.contains(ReasoningDimension.MULTI_OPTION));
        assertTrue(decision.dimensions.contains(ReasoningDimension.FAILURE_MODE));
        assertTrue(decision.dimensions.contains(ReasoningDimension.SELF_CRITIQUE));
        assertFalse("the reason should no longer be the generic planning bucket",
                "planning or decision task".equals(decision.reason));
        assertTrue("the reason should name the dominant signals: " + decision.reason,
                decision.reason.contains("architecture"));
    }

    /** No prompt text may reach diagnostics, however revealing the reason becomes. */
    @Test public void routingReasonNeverQuotesThePrompt() {
        AutoRouter.Decision decision = route(ARCHITECTURE_PROMPT);
        assertFalse(decision.reason.contains("Orbit receives a calendar"));
        assertFalse(decision.reason.contains("WorkManager"));
        for (ReasoningDimension dimension : ReasoningDimension.values()) {
            assertFalse("dimension labels are Orbit's own vocabulary",
                    dimension.label.contains("`") || dimension.label.length() > 48);
        }
    }

    // ---- Fast: simple work stays cheap --------------------------------------------------------

    @Test public void simpleArithmeticStaysFast() {
        expectFast("What is 18% of 75? Just give me the answer.");
        expectModel("What is 18% of 75? Just give me the answer.", "gpt-5.6-luna", "low");
    }

    @Test public void simpleFactualRequestsStayFast() {
        expectFast("What is the capital of Portugal?");
        expectFast("When did the Apollo 11 landing happen?");
        expectFast("Define entropy");
    }

    @Test public void briefFormattingRequestsStayFast() {
        expectFast("Can you make that all caps?");
        expectFast("Do you have a shorter version?");
    }

    @Test public void shortCasualMessagesStayFast() {
        expectFast("hey, how are you?");
        expectFast("thanks!");
    }

    /**
     * "This", "which", and "think" all contain "hi". Substring matching used to file any short
     * prompt containing one of them as small talk, which is a Fast decision reached for a reason
     * that had nothing to do with the request.
     */
    @Test public void shortQuestionsAreNotMistakenForSmallTalk() {
        AutoRouter.Decision decision = route("Which of these is bigger?");
        assertFalse("a real question is not a greeting: " + decision.reason,
                decision.reason.contains("casual"));
    }

    // ---- Balanced: ordinary work stays in the middle -------------------------------------------

    @Test public void normalExplanationsStayBalanced() {
        expectBalanced("Explain how a heat pump moves heat from cold outdoor air into a house, "
                + "and why its efficiency drops as it gets colder outside.");
        expectModel("Explain how a heat pump moves heat from cold outdoor air into a house, "
                + "and why its efficiency drops as it gets colder outside.",
                "gpt-5.6-terra", "medium");
    }

    @Test public void ordinaryProductComparisonsStayBalanced() {
        expectBalanced("I am choosing between the Galaxy S25 Ultra and the Pixel 10 Pro for "
                + "photography and battery life. Compare them and tell me which is the better "
                + "buy for someone who mostly shoots at night.");
    }

    @Test public void straightforwardCodingQuestionsStayBalanced() {
        expectBalanced("Write a Java method that takes a list of strings and returns the ones "
                + "that are valid email addresses, and explain the regex you use.");
        expectBalanced("Why does my RecyclerView show the wrong item after I scroll and come "
                + "back? I am setting the text in onBindViewHolder.");
    }

    @Test public void normalPlanningStaysBalanced() {
        expectBalanced("Help me plan a four day trip to Lisbon in October. I like food markets, "
                + "walking, and I want one day trip outside the city.");
    }

    @Test public void moderateScreenContextComparisonStaysBalanced() {
        AutoRouter.Decision decision = routeWithScreen(
                "Is this one worth the extra money compared to the cheaper model?",
                repeat("Product listing details. ", 60), AppProfileStore.CATEGORY_PRODUCT, 88);
        assertEquals(Prefs.MODE_BALANCED, decision.mode);
    }

    // ---- Deep: several kinds of difficulty at once ---------------------------------------------

    @Test public void architectureConcurrencyAndFailureComparisonReachesDeep() {
        expectDeep("We need to design a system where two services can both write the same order "
                + "record. Compare three possible architectures, identify the race conditions in "
                + "each, and describe the failure modes when one service is partitioned away.");
    }

    @Test public void difficultRootCauseDebuggingReachesDeep() {
        expectDeep("Our uploads intermittently fail only when the app is backgrounded during a "
                + "token refresh. Find the root cause: it could be the retry scheduler, the "
                + "credential cache lifecycle, or a race condition between them, and the "
                + "failure modes of each are different.");
    }

    @Test public void multiArchitectureTradeoffWithSelfCritiqueReachesDeep() {
        expectDeep("Lay out three possible architectures for offline-first sync, weigh the "
                + "trade-offs of each against our constraints, then challenge your initial "
                + "recommendation before you settle on one.");
    }

    @Test public void proofAndInvariantHeavyReasoningReachesDeep() {
        expectDeep("Prove that this scheduling algorithm never starves a task. State the "
                + "invariant it maintains, give the worst-case time complexity, and show a "
                + "counterexample if the invariant is dropped.");
    }

    @Test public void stateMachineAndProcessDeathProblemReachesDeep() {
        expectDeep("Design a state machine for a payment confirmation that must remain correct "
                + "across process death. Show the persistence boundaries, prove the at most once "
                + "guarantee, and list the failure modes for each transition.");
    }

    @Test public void deepDecisionsSelectSolAndHighReasoning() {
        AutoRouter.Decision decision = route(ARCHITECTURE_PROMPT);
        assertEquals(Prefs.MODE_DEEP, decision.mode);
        assertEquals("gpt-5.6-sol",
                Prefs.effectiveModelForMode(context, decision.mode, ARCHITECTURE_PROMPT));
    }

    // ---- what must NOT reach Deep --------------------------------------------------------------

    @Test public void verbosityAloneDoesNotForceDeep() {
        String rambling = repeat("So anyway I was at the shop earlier and I picked up some "
                + "bread and then I remembered we already had bread at home, which was "
                + "annoying, and then the bus was late as usual. ", 8)
                + "What should I make for dinner?";
        assertTrue(rambling.length() > 900);
        AutoRouter.Decision decision = route(rambling);
        assertFalse("a long message is not a hard one: " + decision.reason,
                Prefs.MODE_DEEP.equals(decision.mode));
    }

    @Test public void thinkCarefullyAloneDoesNotForceDeep() {
        expectBalanced("Think carefully and tell me what to cook tonight with chicken, rice, "
                + "and whatever else is usually in a cupboard.");
        AutoRouter.Decision decision = route("Think carefully: what is 12 times 14?");
        assertFalse("politeness is not evidence", Prefs.MODE_DEEP.equals(decision.mode));
    }

    @Test public void stepByStepAloneDoesNotForceDeep() {
        expectBalanced("Walk me through step by step how to change a bike tyre.");
    }

    @Test public void screenshotAndDocumentContextAloneDoNotForceDeep() {
        AutoRouter.Decision decision = routeWithScreen("What does this say?",
                repeat("Document body text. ", 120), AppProfileStore.CATEGORY_DOCUMENT, 78);
        assertFalse("an incidental document classification is not a hard request",
                Prefs.MODE_DEEP.equals(decision.mode));
    }

    /**
     * The device report also showed the launcher classified as a Document at 78%. Incidental
     * screen classification must not dilute a prompt that has already proven itself difficult.
     */
    @Test public void incidentalScreenContextDoesNotSuppressADifficultPrompt() {
        AutoRouter.Decision decision = routeWithScreen(ARCHITECTURE_PROMPT,
                repeat("Launcher screen text. ", 80), AppProfileStore.CATEGORY_DOCUMENT, 78);
        assertEquals("the user's own request is the strongest signal for reasoning complexity",
                Prefs.MODE_DEEP, decision.mode);
    }

    /**
     * A hard <em>topic</em> is not a hard <em>request</em>. Asking what a race condition is stays
     * a definition question; only combining several kinds of reasoning earns Deep.
     */
    @Test public void oneHardKeywordAloneDoesNotForceDeep() {
        assertFalse(Prefs.MODE_DEEP.equals(route("What is a race condition?").mode));
        expectBalanced("Explain what idempotent means in an API, with a short example.");
    }

    @Test public void longConversationAloneDoesNotForceDeep() {
        List<AssistantClient.History> history = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            history.add(new AssistantClient.History("user", repeat("Chat about holidays. ", 20)));
            history.add(new AssistantClient.History("assistant", repeat("Sounds nice. ", 20)));
        }
        AutoRouter.Decision decision = AutoRouter.route(context, "And after that?", "", null,
                history, false, "");
        assertFalse(Prefs.MODE_DEEP.equals(decision.mode));
    }

    // ---- manual modes are untouched -------------------------------------------------------------

    @Test public void forcedManualModesBypassAutoEntirely() {
        assertEquals("gpt-5.6-luna",
                Prefs.effectiveModelForMode(context, Prefs.MODE_FAST, ARCHITECTURE_PROMPT));
        assertEquals("low",
                Prefs.effectiveReasoningForMode(context, Prefs.MODE_FAST, ARCHITECTURE_PROMPT));
        assertEquals("gpt-5.6-terra",
                Prefs.effectiveModelForMode(context, Prefs.MODE_BALANCED, ARCHITECTURE_PROMPT));
        assertEquals("medium",
                Prefs.effectiveReasoningForMode(context, Prefs.MODE_BALANCED, ARCHITECTURE_PROMPT));
        assertEquals("gpt-5.6-sol",
                Prefs.effectiveModelForMode(context, Prefs.MODE_DEEP, "hi"));
        assertEquals("high",
                Prefs.effectiveReasoningForMode(context, Prefs.MODE_DEEP, "hi"));
    }

    @Test public void customModeStillFollowsTheSavedModel() {
        Prefs.get(context).edit().putString(Prefs.MODEL, "gpt-5.6-luna")
                .putString(Prefs.REASONING, "low").commit();
        assertEquals("gpt-5.6-luna",
                Prefs.effectiveModelForMode(context, Prefs.MODE_CUSTOM, ARCHITECTURE_PROMPT));
        assertEquals("low",
                Prefs.effectiveReasoningForMode(context, Prefs.MODE_CUSTOM, ARCHITECTURE_PROMPT));
    }

    private static String repeat(String value, int times) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < times; i++) b.append(value);
        return b.toString();
    }
}

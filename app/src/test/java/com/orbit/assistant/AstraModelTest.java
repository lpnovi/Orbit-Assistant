package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * GPT-6 Astra is optional, explicit, and truthful about itself.
 *
 * <p>Three properties, and the release stands or falls on all three. <b>Optional</b>: nothing
 * routes to it. Astra's allowance is consumed faster than Sol's, so Orbit deciding to spend it
 * because a question looked hard would be spending something of the user's without being asked -
 * Auto, Fast, Balanced and Deep are asserted here to be exactly what they were. <b>Explicit</b>: it
 * appears only where the account-backed path can actually serve it. <b>Truthful</b>: the model
 * Orbit names is the model that answered, and an account that cannot reach Astra is told so rather
 * than quietly served Sol under Astra's name.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AstraModelTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- identity ---------------------------------------------------------------------------------

    @Test public void astraHasAStableIdentifierAndDisplayName() {
        assertEquals("gpt-6-astra", OrbitModelCatalog.ASTRA);
        assertTrue(OrbitModelCatalog.isAstra(OrbitModelCatalog.ASTRA));
        assertEquals("Astra", OrbitModelCatalog.displayName(OrbitModelCatalog.ASTRA));
        assertEquals("GPT-6 Astra", OrbitModelCatalog.settingsLabel(OrbitModelCatalog.ASTRA));
    }

    /** The existing three keep their names, and an unknown id gets none. */
    @Test public void theExistingModelNamesAreUnchanged() {
        assertEquals("Luna", OrbitModelCatalog.displayName(OrbitModelCatalog.LUNA));
        assertEquals("Terra", OrbitModelCatalog.displayName(OrbitModelCatalog.TERRA));
        assertEquals("Sol", OrbitModelCatalog.displayName(OrbitModelCatalog.SOL));
        assertEquals("", OrbitModelCatalog.displayName("some-other-model"));
        assertEquals("", OrbitModelCatalog.displayName(""));
        assertEquals("", OrbitModelCatalog.displayName(null));
        assertFalse(OrbitModelCatalog.isAstra(OrbitModelCatalog.SOL));
    }

    // ---- provider scope ---------------------------------------------------------------------------

    /**
     * Astra is offered by the provider it has been validated against, and by no other.
     *
     * <p>A picker entry is a promise that choosing it works. Orbit's relay and OpenRouter paths
     * have not been tested against Astra, so offering it there would mean a setting whose only
     * effect is a backend error.
     */
    @Test public void onlyTheChatGptPathOffersAstra() {
        assertTrue(OrbitModelCatalog.supports(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA));
        for (AiProvider provider : AiProviders.all()) {
            if (Prefs.PROVIDER_CHATGPT.equals(provider.id())) continue;
            assertFalse(provider.displayName() + " has not been validated against Astra",
                    OrbitModelCatalog.supports(provider.id(), OrbitModelCatalog.ASTRA));
        }
        assertEquals(4, OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT).length);
        assertEquals(3, OrbitModelCatalog.modelsFor(Prefs.PROVIDER_RELAY).length);
    }

    /** Every provider still offers the three models it always did. */
    @Test public void everyProviderKeepsLunaTerraAndSol() {
        for (AiProvider provider : AiProviders.all()) {
            for (String model : new String[]{
                    OrbitModelCatalog.LUNA, OrbitModelCatalog.TERRA, OrbitModelCatalog.SOL}) {
                assertTrue(provider.displayName() + " must still offer " + model,
                        OrbitModelCatalog.supports(provider.id(), model));
            }
        }
    }

    // ---- routing ---------------------------------------------------------------------------------

    /**
     * Auto routing is byte-for-byte the routing that was validated on a real device.
     *
     * <p>The single most important assertion in this file. Astra existing must not change what
     * happens to somebody who never touched the model picker.
     */
    @Test public void autoRoutingIsCompletelyUnchanged() {
        assertEquals(OrbitModelCatalog.LUNA,
                Prefs.effectiveModelForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals(OrbitModelCatalog.TERRA,
                Prefs.effectiveModelForMode(context, Prefs.MODE_BALANCED, "hi"));
        assertEquals("Deep must still be Sol, never Astra", OrbitModelCatalog.SOL,
                Prefs.effectiveModelForMode(context, Prefs.MODE_DEEP, "hi"));

        assertEquals("low", Prefs.effectiveReasoningForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals("medium", Prefs.effectiveReasoningForMode(context, Prefs.MODE_BALANCED, "hi"));
        assertEquals("high", Prefs.effectiveReasoningForMode(context, Prefs.MODE_DEEP, "hi"));

        for (String prompt : new String[]{
                "hi", "think deeply about this complex architectural problem and evaluate in depth",
                "what is 2 + 2", "analyze and compare these three approaches thoroughly"}) {
            assertFalse("Auto must never route to Astra: [" + prompt + "]",
                    OrbitModelCatalog.isAstra(
                            Prefs.effectiveModelForMode(context, Prefs.MODE_AUTO, prompt)));
        }
    }

    /** Astra is reached only by choosing Custom and selecting it. */
    @Test public void astraIsReachedOnlyThroughCustom() {
        Prefs.get(context).edit().putString(Prefs.MODEL, OrbitModelCatalog.ASTRA).commit();
        assertEquals(OrbitModelCatalog.ASTRA,
                Prefs.effectiveModelForMode(context, Prefs.MODE_CUSTOM, "anything"));
        assertEquals("choosing it for Custom must not change Deep", OrbitModelCatalog.SOL,
                Prefs.effectiveModelForMode(context, Prefs.MODE_DEEP, "anything"));
        // Auto answers a short question with Luna and a hard one with Sol, exactly as it did
        // before Astra existed. What matters is that neither answer is ever Astra.
        assertEquals("or Auto on a short question", OrbitModelCatalog.LUNA,
                Prefs.effectiveModelForMode(context, Prefs.MODE_AUTO, "hi"));
        assertEquals("or Auto on a hard one", OrbitModelCatalog.SOL,
                Prefs.effectiveModelForMode(context, Prefs.MODE_AUTO,
                        "think deeply and reason carefully about this"));
    }

    // ---- reasoning ---------------------------------------------------------------------------------

    /**
     * Astra has no {@code none}, so Orbit never sends one.
     *
     * <p>A user who set Custom reasoning to none before choosing Astra would otherwise find every
     * request failing for a reason nothing on screen explained. The substitution is {@code low},
     * which is the nearest thing Astra has to "do not spend time thinking".
     */
    @Test public void astraNeverReceivesAnUnsupportedNone() {
        assertEquals("low", OrbitModelCatalog.reasoningFor(OrbitModelCatalog.ASTRA, "none"));
        assertEquals("low", OrbitModelCatalog.reasoningFor(OrbitModelCatalog.ASTRA, ""));
        assertEquals("low", OrbitModelCatalog.reasoningFor(OrbitModelCatalog.ASTRA, null));

        Prefs.get(context).edit()
                .putString(Prefs.MODEL, OrbitModelCatalog.ASTRA)
                .putString(Prefs.REASONING, "none").commit();
        assertEquals("low", Prefs.effectiveReasoningForMode(context, Prefs.MODE_CUSTOM, "hi"));
    }

    /** Every effort Astra does accept passes through untouched. */
    @Test public void everySupportedAstraEffortIsSentAsChosen() {
        for (String effort : new String[]{"low", "medium", "high", "xhigh", "max"}) {
            assertEquals(effort, OrbitModelCatalog.reasoningFor(OrbitModelCatalog.ASTRA, effort));
        }
    }

    /** And the other models keep {@code none}, exactly as they always have. */
    @Test public void theExistingModelsKeepNone() {
        for (String model : new String[]{
                OrbitModelCatalog.LUNA, OrbitModelCatalog.TERRA, OrbitModelCatalog.SOL}) {
            assertEquals("none", OrbitModelCatalog.reasoningFor(model, "none"));
            assertEquals("high", OrbitModelCatalog.reasoningFor(model, "high"));
        }
        Prefs.get(context).edit()
                .putString(Prefs.MODEL, OrbitModelCatalog.SOL)
                .putString(Prefs.REASONING, "none").commit();
        assertEquals("none", Prefs.effectiveReasoningForMode(context, Prefs.MODE_CUSTOM, "hi"));
    }

    // ---- thinking updates ---------------------------------------------------------------------------

    @Test public void thinkingUpdatesNameAstraWhenAstraIsUsed() {
        assertEquals("Reasoning with Astra…",
                ThinkingUpdate.modelReasoning(OrbitModelCatalog.ASTRA).text);
        assertEquals("Reasoning with Sol…",
                ThinkingUpdate.modelReasoning(OrbitModelCatalog.SOL).text);
        assertEquals("Reasoning with Terra…",
                ThinkingUpdate.modelReasoning(OrbitModelCatalog.TERRA).text);
        assertEquals("Reasoning with Luna…",
                ThinkingUpdate.modelReasoning(OrbitModelCatalog.LUNA).text);
        assertNull("a model Orbit has no name for is never named",
                ThinkingUpdate.modelReasoning("some-other-model"));
    }

    /**
     * The status line names the model that was actually sent, not the one that was selected.
     *
     * <p>The distinction only shows up when they differ, which is exactly the fallback case, and
     * that is the case where getting it wrong is a lie rather than a detail.
     */
    @Test public void thinkingUpdatesNameTheModelActuallySent() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("the request's own model is what the status line reports",
                client.contains("ThinkingUpdate.modelReasoning(model)"));
        assertEquals("Sol", OrbitModelCatalog.displayName(
                ChatGptClient.modelFor(context, Prefs.MODE_CUSTOM, "hi", true)));
    }

    // ---- unavailability ------------------------------------------------------------------------------

    /** A backend that will not serve Astra to this account is recognised. */
    @Test public void aRealEntitlementFailureIsRecognised() {
        for (String error : new String[]{
                "The ChatGPT/Codex backend did not make gpt-6-astra available for this request.",
                "The model `gpt-6-astra` does not exist or you do not have access to it.",
                "model not found: gpt-6-astra",
                "This model is not available for your account.",
                "Your ChatGPT account or workspace did not allow this Codex-backed request. "
                        + "- unsupported model"}) {
            assertTrue("[" + error + "] means Astra is not reachable here",
                    OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.ASTRA, error));
        }
    }

    /**
     * And an ordinary failure is never mislabelled as one.
     *
     * <p>The cost of being wrong runs both ways. Telling somebody their subscription cannot reach
     * Astra when their connection dropped sends them to look at the wrong thing entirely.
     */
    @Test public void ordinaryFailuresAreNotMislabelledAsEntitlementProblems() {
        for (String error : new String[]{
                "Orbit timed out while waiting for ChatGPT.",
                "ChatGPT request failed: timeout",
                "Unable to resolve host chatgpt.com",
                "Orbit request was interrupted.",
                "ChatGPT/Codex backend error 500",
                "Your ChatGPT/Codex account allowance appears to be at its current limit.",
                "network is unreachable",
                "connection reset by peer"}) {
            assertFalse("[" + error + "] is not an Astra eligibility failure",
                    OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.ASTRA, error));
        }
        assertFalse(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.ASTRA, null));
    }

    /** The same errors mean nothing at all for a model that has no availability question. */
    @Test public void theUnavailabilityCheckOnlyEverAppliesToAstra() {
        for (String model : new String[]{
                OrbitModelCatalog.LUNA, OrbitModelCatalog.TERRA, OrbitModelCatalog.SOL}) {
            assertFalse(OrbitModelCatalog.looksUnavailable(model,
                    "The model does not exist or you do not have access to it."));
        }
    }

    /** What the user is told is about Astra, and points at what they can do instead. */
    @Test public void theUnavailableMessageIsTruthfulAndActionable() {
        String message = OrbitModelCatalog.unavailableMessage();
        assertTrue(message.contains("Astra"));
        assertTrue(message.contains("not available"));
        assertTrue("it must say where to change it", message.contains("Settings"));
        assertFalse("and must not blame the network", message.toLowerCase().contains("network"));
    }

    // ---- fallback ------------------------------------------------------------------------------------

    /**
     * A fallback to Sol says so, in the answer.
     *
     * <p>Non-negotiable, and the only reason the fallback is allowed to exist. A silent
     * substitution would mean Orbit showing Sol's answer while the interface still said Astra.
     */
    @Test public void afallbackAnswerSaysWhichModelActuallyAnsweredIt() {
        String notice = OrbitModelCatalog.fallbackNotice();
        assertTrue(notice.contains("Astra"));
        assertTrue(notice.contains("Sol"));
        assertTrue(notice.contains("unavailable"));

        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("the notice must be prefixed to the answer the user reads",
                client.contains("OrbitModelCatalog.fallbackNotice() + \"\\n\\n\" + reply.text"));
    }

    /** The fallback is attempted once, and its own failure is reported honestly. */
    @Test public void thefallbackIsAttemptedExactlyOnce() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("only a first attempt may fall back",
                client.contains("if (!astraFallback && OrbitModelCatalog.looksUnavailable("));
        assertTrue("a failed fallback tells the truth about Astra",
                client.contains("cb.onError(OrbitModelCatalog.unavailableMessage())"));
        assertEquals("the fallback model is fixed, not searched for", OrbitModelCatalog.SOL,
                ChatGptClient.modelFor(context, Prefs.MODE_CUSTOM, "hi", true));
    }

    // ---- diagnostics ------------------------------------------------------------------------------------

    /** Diagnostics reports the model that ran, and says when it differed from the one asked for. */
    @Test public void diagnosticsReportsRequestedAndEffectiveModels() {
        DiagnosticStore.recordEffectiveModel(context, OrbitModelCatalog.ASTRA, OrbitModelCatalog.SOL);
        DiagnosticStore.recordModelFallback(context, OrbitModelCatalog.ASTRA, OrbitModelCatalog.SOL);
        android.content.SharedPreferences d = DiagnosticStore.prefs(context);
        assertEquals(OrbitModelCatalog.ASTRA, d.getString("model_requested", ""));
        assertEquals(OrbitModelCatalog.SOL, d.getString("model_effective", ""));
        assertEquals(OrbitModelCatalog.SOL, d.getString("model_fallback_to", ""));

        String screen = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/DiagnosticsActivity.java");
        assertTrue(screen.contains("Requested model"));
        assertTrue(screen.contains("Effective model"));
        assertTrue("and it must say plainly when the two disagree",
                screen.contains("Orbit did not use the requested model"));
    }

    // ---- settings ------------------------------------------------------------------------------------

    /** Astra appears in Settings under its real name, with honest supporting copy. */
    @Test public void settingsOffersAstraWithHonestCopy() {
        String settings = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/SettingsActivity.java");
        assertTrue("the picker is driven by the catalog rather than a literal list",
                settings.contains("OrbitModelCatalog.modelsFor(Prefs.provider(this))"));
        assertTrue("availability is stated plainly",
                settings.contains("Availability depends on your ChatGPT/Codex account"));
        assertTrue("and so is the cost", settings.contains("uses its allowance faster"));
        assertTrue("while Auto is promised unchanged",
                settings.contains("never route to Astra on their own"));
        assertFalse("no fabricated quota may be shown", settings.contains("% Astra remaining"));
        assertFalse("and Orbit must never scrape a usage page",
                settings.contains("chatgpt.com/settings"));
    }

    /** Nothing anywhere polls for an Astra allowance. */
    @Test public void nothingPollsForAnAstraAllowance() {
        String catalog = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitModelCatalog.java");
        for (String forbidden : new String[]{
                "HttpURLConnection", "URL", "quota", "remaining", "usage"}) {
            assertFalse("the catalog must not try to learn an allowance: " + forbidden,
                    catalog.contains(forbidden));
        }
    }

    /** The catalog is the one place a model id is written down. */
    @Test public void modelIdentifiersAreNotDuplicatedAcrossTheApp() {
        assertNotNull(OrbitModelCatalog.CHATGPT_MODELS);
        String prefs = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/Prefs.java");
        assertFalse("Prefs must read the catalog rather than keep its own literals",
                prefs.contains("\"gpt-5.6-luna\""));
        assertFalse(prefs.contains("\"gpt-6-astra\""));
        String thinking = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ThinkingUpdate.java");
        assertTrue("and so must the status line",
                thinking.contains("OrbitModelCatalog.displayName"));
    }
}

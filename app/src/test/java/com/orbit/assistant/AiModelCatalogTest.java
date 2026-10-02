package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 0.8.3.0 model catalog: exact ids, names, strengths, and honest availability handling.
 *
 * <p>Successor of AstraModelTest. What that test protected (no invented names, no unsupported
 * effort reaching the backend, network failures never reported as entitlement problems, nothing
 * polling for an allowance) is still protected here; what it protected about Auto routing and the
 * Astra-to-Sol fallback is gone with those features.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiModelCatalogTest {

    private static List<String> ids(List<AiModelSpec> specs) {
        List<String> out = new ArrayList<>();
        for (AiModelSpec s : specs) out.add(s.id);
        return out;
    }

    @Test public void chatGptOffersExactlyTheCurrentModelsInOrder() {
        assertEquals(Arrays.asList("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra",
                        "gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol"),
                ids(OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT)));
        assertEquals("gpt-6-luna", OrbitModelCatalog.LUNA);
        assertEquals("gpt-6.1-sol", OrbitModelCatalog.SOL);
        assertEquals("gpt-6-astra", OrbitModelCatalog.ASTRA);
    }

    @Test public void friendlyNamesAreExact() {
        assertEquals("GPT-6 Luna", OrbitModelCatalog.displayName("gpt-6-luna"));
        assertEquals("GPT-6.1 Sol", OrbitModelCatalog.displayName("gpt-6.1-sol"));
        assertEquals("GPT-6 Astra", OrbitModelCatalog.displayName("gpt-6-astra"));
        assertEquals("GPT-5.6 Luna", OrbitModelCatalog.displayName("gpt-5.6-luna"));
        assertEquals("GPT-5.6 Terra", OrbitModelCatalog.displayName("gpt-5.6-terra"));
        assertEquals("GPT-5.6 Sol", OrbitModelCatalog.displayName("gpt-5.6-sol"));
        assertEquals("Orbit Local", OrbitModelCatalog.displayName(OrbitModelCatalog.ORBIT_LOCAL));
    }

    @Test public void lunaAcceptsEveryStrengthIncludingNone() {
        AiModelSpec luna = OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA);
        assertEquals(Arrays.asList(AiStrength.NONE, AiStrength.LOW, AiStrength.MEDIUM,
                AiStrength.HIGH, AiStrength.XHIGH, AiStrength.MAX), luna.strengths);
        assertEquals(AiStrength.NONE, luna.resolveStrength(AiStrength.NONE));
    }

    @Test public void solAndAstraRefuseNoneAndRemapItToLow() {
        for (String model : new String[]{OrbitModelCatalog.SOL, OrbitModelCatalog.ASTRA}) {
            AiModelSpec spec = OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, model);
            assertEquals(model, Arrays.asList(AiStrength.LOW, AiStrength.MEDIUM, AiStrength.HIGH,
                    AiStrength.XHIGH, AiStrength.MAX), spec.strengths);
            assertFalse(model, spec.supports(AiStrength.NONE));
            assertEquals(model, AiStrength.LOW, spec.resolveStrength(AiStrength.NONE));
        }
    }

    @Test public void backendEffortIdsAreExact() {
        assertEquals("none", AiStrength.NONE.id);
        assertEquals("low", AiStrength.LOW.id);
        assertEquals("medium", AiStrength.MEDIUM.id);
        assertEquals("high", AiStrength.HIGH.id);
        assertEquals("xhigh", AiStrength.XHIGH.id);
        assertEquals("max", AiStrength.MAX.id);
        assertEquals("Extra High", AiStrength.XHIGH.label);
        assertEquals(AiStrength.XHIGH, AiStrength.fromId("Extra High"));
        assertNull(AiStrength.fromId("turbo"));
    }

    @Test public void everySupportedStrengthIsSentAsChosen() {
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT)) {
            for (AiStrength s : spec.strengths) assertEquals(s, spec.resolveStrength(s));
        }
    }

    @Test public void everyOfficialChatGptModelCarriesTheVerifiedContextWindow() {
        for (AiModelSpec spec : OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT)) {
            assertEquals(spec.id, 1_050_000, spec.contextWindowTokens);
        }
    }

    @Test public void providerFallbackCatalogsAreCapabilityAwareAndOrdered() {
        List<AiModelSpec> claude = OrbitModelCatalog.modelsFor(Prefs.PROVIDER_ANTHROPIC);
        assertEquals(OrbitModelCatalog.CLAUDE_OPUS_5_5, claude.get(0).id);
        assertEquals(1_000_000, claude.get(0).contextWindowTokens);
        assertTrue(claude.get(0).vision);
        assertTrue(claude.get(0).nativeFiles);
        assertTrue(claude.get(0).tools);
        assertEquals(200_000, OrbitModelCatalog.spec(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_HAIKU_4_5).contextWindowTokens);
        assertFalse(OrbitModelCatalog.spec(Prefs.PROVIDER_ANTHROPIC,
                OrbitModelCatalog.CLAUDE_HAIKU_4_5).hasStrengths());

        AiModelSpec grok = OrbitModelCatalog.spec(Prefs.PROVIDER_XAI,
                OrbitModelCatalog.GROK_4_7);
        assertEquals(500_000, grok.contextWindowTokens);
        assertTrue(grok.vision);
        assertEquals(Arrays.asList(AiStrength.LOW, AiStrength.MEDIUM,
                AiStrength.HIGH, AiStrength.XHIGH), grok.strengths);
        assertEquals("static_official", grok.metadataSource);
    }

    @Test public void everyGpt56ModelAcceptsNoneThroughMaxAndDefaultsToMedium() {
        for (String id : new String[]{OrbitModelCatalog.GPT_5_6_LUNA,
                OrbitModelCatalog.GPT_5_6_TERRA, OrbitModelCatalog.GPT_5_6_SOL}) {
            AiModelSpec spec = OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, id);
            assertNotNull(id, spec);
            assertEquals(id, Arrays.asList(AiStrength.values()), spec.strengths);
            assertEquals(id, AiStrength.MEDIUM, spec.defaultStrength);
        }
    }

    @Test public void availabilityIsCatalogDataNotScreenLogic() {
        assertTrue(OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA).availabilityVaries);
        assertFalse(OrbitModelCatalog.spec(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA).availabilityVaries);
        for (String file : new String[]{"ChatActivity.java", "OrbitSession.java", "SettingsActivity.java",
                "AiSelectorDialog.java", "ChatGptClient.java", "AssistantClient.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile(
                    "app/src/main/java/com/orbit/assistant/" + file);
            assertFalse(file + " must not special-case a model by name",
                    source.contains("isAstra(") || source.contains("contains(\"astra\")")
                            || source.contains("OrbitModelCatalog.ASTRA.equals"));
        }
    }

    @Test public void orbitLocalExposesNoStrength() {
        AiModelSpec local = OrbitModelCatalog.spec(Prefs.PROVIDER_LOCAL, OrbitModelCatalog.ORBIT_LOCAL);
        assertNotNull(local);
        assertFalse(local.hasStrengths());
        assertNull(local.resolveStrength(AiStrength.HIGH));
    }

    @Test public void gpt56IsFirstClassForChatGptAndStoredIdsStayExact() {
        List<String> offered = ids(OrbitModelCatalog.modelsFor(Prefs.PROVIDER_CHATGPT));
        assertTrue(offered.contains(OrbitModelCatalog.GPT_5_6_LUNA));
        assertTrue(offered.contains(OrbitModelCatalog.GPT_5_6_TERRA));
        assertTrue(offered.contains(OrbitModelCatalog.GPT_5_6_SOL));
        assertEquals(OrbitModelCatalog.GPT_5_6_LUNA,
                OrbitModelCatalog.successorOf(OrbitModelCatalog.GPT_5_6_LUNA));
        assertEquals(OrbitModelCatalog.GPT_5_6_TERRA,
                OrbitModelCatalog.successorOf(OrbitModelCatalog.GPT_5_6_TERRA));
        assertEquals(OrbitModelCatalog.GPT_5_6_SOL,
                OrbitModelCatalog.successorOf(OrbitModelCatalog.GPT_5_6_SOL));
    }

    @Test public void unknownModelsResolveSafelyAndAreNeverNamed() {
        assertEquals("", OrbitModelCatalog.displayName("gpt-9-mystery"));
        assertEquals("", OrbitModelCatalog.displayName(null));
        AiSelection resolved = AiSelections.resolve(
                AiSelection.of(Prefs.PROVIDER_CHATGPT, "gpt-9-mystery", AiStrength.HIGH));
        assertEquals(OrbitModelCatalog.LUNA, resolved.model);
        assertEquals(AiStrength.HIGH, resolved.strength);
        assertTrue(AiSelections.isValid(resolved));
    }

    @Test public void openRouterHasNoModelsAndIsNeverASelection() {
        assertTrue(OrbitModelCatalog.modelsFor(Prefs.PROVIDER_OPENROUTER).isEmpty());
        assertEquals(Prefs.PROVIDER_CHATGPT, AiSelections.resolve(
                AiSelection.of(Prefs.PROVIDER_OPENROUTER, "x", null)).provider);
    }

    // ---- availability --------------------------------------------------------------------------

    @Test public void aRealEntitlementRefusalIsRecognisedForAnyModel() {
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.ASTRA,
                "The ChatGPT/Codex backend did not make gpt-6-astra available for this request."));
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.SOL,
                "ChatGPT request failed (HTTP 400): The model gpt-6.1-sol is not supported when using Codex with a ChatGPT account."));
        assertTrue(OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.LUNA,
                "Your account does not have access to model gpt-6-luna"));
    }

    @Test public void networkFailuresAreNeverEntitlementProblems() {
        for (String error : new String[]{
                "ChatGPT request failed: timeout",
                "ChatGPT request failed: Unable to resolve host \"chatgpt.com\": No address associated with hostname",
                "ChatGPT request failed: Connection reset",
                "Orbit could not reach the network. The model is unavailable offline.",
                "java.net.SocketTimeoutException: Read timed out",
                "ChatGPT request failed: UnknownHostException"}) {
            for (String model : OrbitModelCatalog.currentModelIds()) {
                assertFalse(error, OrbitModelCatalog.looksUnavailable(model, error));
            }
        }
        assertFalse("an unrelated error is not about a model",
                OrbitModelCatalog.looksUnavailable(OrbitModelCatalog.SOL, "Rate limit reached. Try again later."));
    }

    @Test public void theUnavailableMessageNamesTheModelAndKeepsTheChoice() {
        String message = OrbitModelCatalog.unavailableMessage(OrbitModelCatalog.ASTRA);
        assertTrue(message.startsWith("GPT-6 Astra is not available through this ChatGPT account"));
        assertTrue(message.contains("Your selection has not been changed"));
        assertFalse("never claims another model answered", message.contains("used Sol"));
    }

    @Test public void nothingSubstitutesAnUnavailableModel() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertFalse(client.contains("astraFallback"));
        assertFalse(client.contains("fallbackNotice"));
        assertTrue(client.contains("cb.onError(OrbitModelCatalog.unavailableMessage(model))"));
        String worker = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRequestWorker.java");
        assertFalse("the overload retry may not move to a lighter model", worker.contains("MODE_FAST"));
    }

    @Test public void nothingPollsForAnAllowance() {
        String catalog = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitModelCatalog.java");
        for (String forbidden : new String[]{"HttpURLConnection", "quota", "remaining", "usage"}) {
            assertFalse(forbidden, catalog.contains(forbidden));
        }
    }

    @Test public void modelIdentifiersLiveOnlyInTheCatalog() {
        for (String file : new String[]{"Prefs.java", "ThinkingUpdate.java", "SettingsActivity.java",
                "ChatActivity.java", "OrbitSession.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile(
                    "app/src/main/java/com/orbit/assistant/" + file);
            assertFalse(file, source.contains("\"gpt-6-luna\"") || source.contains("\"gpt-6.1-sol\"")
                    || source.contains("\"gpt-6-astra\"") || source.contains("\"gpt-5.6-terra\""));
        }
    }

    @Test public void thinkingUpdatesNameTheModelActuallySent() {
        assertEquals("Reasoning with GPT-6.1 Sol…",
                ThinkingUpdate.modelReasoning(OrbitModelCatalog.SOL).text);
        assertNull("an unknown id is never given a guessed name",
                ThinkingUpdate.modelReasoning("gpt-9-mystery"));
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Providers do not behave identically here, and Orbit is required to say so rather than pretend.
 *
 * <p>Only a provider whose protocol genuinely carries a user-facing reasoning summary may claim
 * one. Everything else falls back to Orbit describing its own execution, which is honest but is a
 * different thing, and the capability flag is what lets the rest of the app tell them apart
 * without comparing provider ids.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ThinkingUpdateProviderTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        context.getSharedPreferences("orbit_reasoning_summary", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    // ---- capability declarations -----------------------------------------------------------------

    @Test public void onlyChatGptDeclaresThatItCarriesReasoningSummaries() {
        for (AiProvider provider : AiProviders.all()) {
            boolean expected = Prefs.PROVIDER_CHATGPT.equals(provider.id());
            assertEquals(provider.displayName() + " declares the wrong summary capability",
                    expected, provider.capabilities().reasoningSummaries);
        }
    }

    /** A provider that cannot stream at all certainly cannot stream a summary. */
    @Test public void noProviderClaimsSummariesWithoutStreaming() {
        for (AiProvider provider : AiProviders.all()) {
            if (!provider.capabilities().reasoningSummaries) continue;
            assertTrue(provider.displayName() + " claims summaries but cannot stream",
                    provider.capabilities().streaming);
        }
    }

    /** OpenRouter stays exactly where v0.7.7.7 left it: setup only, and not activated by this. */
    @Test public void openRouterIsUnchangedAndStillNotSelectable() {
        AiProvider openRouter = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        assertFalse(openRouter.selectable(context));
        assertFalse(openRouter.capabilities().reasoningSummaries);
        assertEquals(AiProvider.Status.COMING_SOON, openRouter.status(context));
    }

    /** Orbit Local answers offline and says only what it is actually doing. */
    @Test public void orbitLocalFallsBackToItsOwnExecutionState() {
        AiProvider local = AiProviders.byId(Prefs.PROVIDER_LOCAL);
        assertFalse(local.capabilities().reasoningSummaries);
        assertEquals("Running on your phone…",
                ThinkingUpdate.progress(ThinkingUpdate.Stage.LOCAL_INFERENCE).text);
    }

    // ---- runtime observation of the ChatGPT backend ------------------------------------------------

    @Test public void orbitIsWillingToAskUntilTheBackendRefuses() {
        assertEquals(ReasoningSummarySupport.UNKNOWN, ReasoningSummarySupport.state(context));
        assertTrue(ReasoningSummarySupport.mayRequest(context));

        ReasoningSummarySupport.markUnsupported(context);
        assertFalse("one refusal is enough to stop asking",
                ReasoningSummarySupport.mayRequest(context));
        assertEquals("no", ReasoningSummarySupport.stateLabel(context));
    }

    @Test public void oneRealSummaryIsEnoughToKnowItWorks() {
        ReasoningSummarySupport.record(context, true);
        assertEquals(ReasoningSummarySupport.SUPPORTED, ReasoningSummarySupport.state(context));
        assertEquals("yes", ReasoningSummarySupport.stateLabel(context));
        assertTrue(ReasoningSummarySupport.mayRequest(context));
    }

    @Test public void aRequestThatSimplyProducedNoSummaryProvesNothingEitherWay() {
        ReasoningSummarySupport.record(context, false);
        assertEquals("a quiet request must not be read as a refusal",
                ReasoningSummarySupport.UNKNOWN, ReasoningSummarySupport.state(context));
    }

    /**
     * The narrow half of the fallback, and the important one: an ordinary failure must keep being
     * reported as a failure rather than being swallowed behind a silent retry.
     */
    @Test public void onlyARefusalOfTheSummaryFieldTriggersTheFallback() {
        assertTrue(ReasoningSummarySupport.looksLikeSummaryRefusal(400,
                "{\"error\":{\"message\":\"Unknown parameter: 'reasoning.summary'.\"}}"));
        assertTrue(ReasoningSummarySupport.looksLikeSummaryRefusal(400,
                "{\"error\":{\"message\":\"reasoning.summary is not supported for this model\"}}"));

        for (int code : new int[]{401, 403, 404, 429, 500, 502, 503}) {
            assertFalse("HTTP " + code + " must stay a real error",
                    ReasoningSummarySupport.looksLikeSummaryRefusal(code,
                            "{\"error\":{\"message\":\"reasoning.summary unsupported\"}}"));
        }
        assertFalse("an unrelated 400 must stay a real error",
                ReasoningSummarySupport.looksLikeSummaryRefusal(400,
                        "{\"error\":{\"message\":\"Invalid model id\"}}"));
        assertFalse(ReasoningSummarySupport.looksLikeSummaryRefusal(400, ""));
        assertFalse(ReasoningSummarySupport.looksLikeSummaryRefusal(400, null));
    }

    // ---- what the request actually carries ---------------------------------------------------------

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) return directory;
        }
        throw new AssertionError("repository root was not found above " + start);
    }

    private static String read(String relativePath) {
        try {
            return new String(Files.readAllBytes(repositoryRoot().resolve(relativePath)),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }

    /**
     * Asking for a summary must not change what the model is asked to do.
     *
     * <p>Auto's calibration was validated on a real device, and a status line is not a reason to
     * disturb it: the summary is requested alongside the effort the router already chose, never
     * instead of it and never above it.
     */
    @Test public void requestingASummaryNeverRaisesTheReasoningEffort() {
        String client = read("app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        int at = client.indexOf("if (askForSummary) reasoning.put(\"summary\"");
        assertTrue("the summary must be requested on the existing reasoning object", at > 0);
        String around = client.substring(Math.max(0, at - 700), at);
        assertTrue("the effort must still come from the router",
                around.contains("Prefs.effectiveReasoningForMode"));
        assertFalse("the summary request must not overwrite the effort",
                client.contains("reasoning.put(\"effort\", \"high\")"));
    }

    /** Auto's validated calibration is untouched by this release. */
    @Test public void autoRoutingCalibrationIsUnchanged() {
        assertEquals("gpt-5.6-luna", Prefs.effectiveModelForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals("low", Prefs.effectiveReasoningForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals("gpt-5.6-terra", Prefs.effectiveModelForMode(context, Prefs.MODE_BALANCED, "hi"));
        assertEquals("medium", Prefs.effectiveReasoningForMode(context, Prefs.MODE_BALANCED, "hi"));
        assertEquals("gpt-5.6-sol", Prefs.effectiveModelForMode(context, Prefs.MODE_DEEP, "hi"));
        assertEquals("high", Prefs.effectiveReasoningForMode(context, Prefs.MODE_DEEP, "hi"));

        // And the same answers with the feature on: the setting reaches the request, not the router.
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        assertEquals("gpt-5.6-luna", Prefs.effectiveModelForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals("low", Prefs.effectiveReasoningForMode(context, Prefs.MODE_FAST, "hi"));
        assertEquals("high", Prefs.effectiveReasoningForMode(context, Prefs.MODE_DEEP, "hi"));
    }

    /** The flag is frozen onto the request rather than read again later. */
    @Test public void theRequestCarriesTheSettingItWasSubmittedWith() {
        AiRequest on = AiRequest.builder().prompt("x").thinkingUpdates(true).build();
        AiRequest off = AiRequest.builder().prompt("x").build();
        assertTrue(on.thinkingUpdates);
        assertFalse("off is the default for a request that was never told otherwise",
                off.thinkingUpdates);
    }

    /** Requesting summaries is gated on the user's choice, not on the provider being capable. */
    @Test public void aSummaryIsOnlyRequestedWhenTheUserAskedForOne() {
        String client = read("app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("the request must be gated on both the setting and the observed support",
                client.contains("thinkingUpdates && ReasoningSummarySupport.mayRequest(context)"));
    }

    // ---- the fallback still answers ----------------------------------------------------------------

    /**
     * A provider Orbit cannot reach must still fail in plain language, exactly as it did before.
     * A status feature is not allowed to change what an unconfigured provider says.
     */
    @Test public void anUnavailableProviderStillFailsCleanlyWithTheFeatureOn() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        AtomicReference<String> error = new AtomicReference<>();
        AiRequest request = AiRequest.builder()
                .prompt("Compare these architectures.")
                .history(new ArrayList<>())
                .intelligenceMode(Prefs.MODE_DEEP)
                .thinkingUpdates(true)
                .build();
        AiProviders.byId(Prefs.PROVIDER_RELAY).send(context, request, new AssistantClient.Callback() {
            @Override public void onThinking(ThinkingUpdate update) {
                fail("a provider that cannot run must not narrate progress");
            }
            @Override public void onSuccess(AssistantReply reply) { fail("no reply was possible"); }
            @Override public void onError(String message) { error.set(message); }
        });
        assertNotNull(error.get());
        assertEquals(RelayProvider.NOT_CONFIGURED_ERROR, error.get());
    }

    @Test public void openRouterStillRefusesChatWithTheFeatureOn() {
        Prefs.get(context).edit().putBoolean(Prefs.THINKING_UPDATES, true).commit();
        AtomicReference<String> error = new AtomicReference<>();
        AiRequest request = AiRequest.builder().prompt("x").thinkingUpdates(true).build();
        AiProviders.byId(Prefs.PROVIDER_OPENROUTER).send(context, request,
                new AssistantClient.Callback() {
                    @Override public void onThinking(ThinkingUpdate update) {
                        fail("a provider whose chat does not run must narrate nothing");
                    }
                    @Override public void onSuccess(AssistantReply reply) { fail("unreachable"); }
                    @Override public void onError(String message) { error.set(message); }
                });
        assertNotNull(error.get());
    }

    @Test public void diagnosticsReportTheSourceWithoutTheText() {
        assertEquals("none", ReasoningSummarySupport.lastSource(context));
        ReasoningSummarySupport.recordDisplayed(context,
                ThinkingUpdate.progress(ThinkingUpdate.Stage.WEB_SEARCH));
        assertEquals("orbit-progress", ReasoningSummarySupport.lastSource(context));
        ReasoningSummarySupport.recordDisplayed(context,
                ThinkingUpdate.providerSummary("Comparing the approaches"));
        assertEquals("provider-summary", ReasoningSummarySupport.lastSource(context));
        assertEquals(2, ReasoningSummarySupport.updatesReceived(context));
        assertTrue(ReasoningSummarySupport.lastUpdateAt(context) > 0L);
    }

    @Test public void nullsAreIgnoredByTheDiagnosticsRecorder() {
        ReasoningSummarySupport.recordDisplayed(context, null);
        ReasoningSummarySupport.recordDisplayed(null, ThinkingUpdate.providerSummary("x"));
        assertEquals(0, ReasoningSummarySupport.updatesReceived(context));
        assertNull(null);
    }
}

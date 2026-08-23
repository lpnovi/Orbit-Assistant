package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The provider registry is the seam the 0.7.7 line stands on: explicit selection, honest
 * capability metadata, and no silent substitution. These tests pin that contract.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiProvidersTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void everyProviderIdIsKnownToPrefs() {
        for (AiProvider p : AiProviders.all()) {
            assertEquals("provider ids must survive preference normalization",
                    p.id(), Prefs.normalizeProvider(p.id()));
        }
    }

    @Test public void unknownStoredProviderFallsBackToChatGpt() {
        Prefs.get(context).edit().putString(Prefs.PROVIDER, "something-old").commit();
        assertEquals(Prefs.PROVIDER_CHATGPT, Prefs.provider(context));
        assertEquals(Prefs.PROVIDER_CHATGPT, AiProviders.active(context).id());
    }

    @Test public void byIdNeverReturnsNull() {
        assertNotNull(AiProviders.byId("no-such-provider"));
        assertEquals(Prefs.PROVIDER_CHATGPT, AiProviders.byId("no-such-provider").id());
        assertEquals(Prefs.PROVIDER_LOCAL, AiProviders.byId(Prefs.PROVIDER_LOCAL).id());
        assertEquals(Prefs.PROVIDER_OPENROUTER, AiProviders.byId(Prefs.PROVIDER_OPENROUTER).id());
        assertEquals(Prefs.PROVIDER_RELAY, AiProviders.byId(Prefs.PROVIDER_RELAY).id());
    }

    @Test public void openRouterIsAShellAndCannotBecomeActive() {
        AiProvider openRouter = AiProviders.byId(Prefs.PROVIDER_OPENROUTER);
        assertFalse("the shell must not be selectable", openRouter.selectable(context));
        assertFalse(AiProviders.select(context, Prefs.PROVIDER_OPENROUTER));
        assertEquals("a refused selection must not change the stored provider",
                Prefs.PROVIDER_CHATGPT, Prefs.provider(context));
    }

    @Test public void selectionIsExplicitAndPersisted() {
        assertTrue(AiProviders.select(context, Prefs.PROVIDER_RELAY));
        assertEquals(Prefs.PROVIDER_RELAY, Prefs.provider(context));
        assertEquals(Prefs.PROVIDER_RELAY, AiProviders.active(context).id());
        assertTrue(AiProviders.select(context, Prefs.PROVIDER_CHATGPT));
        assertEquals(Prefs.PROVIDER_CHATGPT, AiProviders.active(context).id());
    }

    @Test public void capabilityMetadataIsHonest() {
        AiCapabilities chatgpt = AiProviders.byId(Prefs.PROVIDER_CHATGPT).capabilities();
        assertTrue(chatgpt.streaming);
        assertTrue(chatgpt.deviceActions);
        assertTrue(chatgpt.images);
        assertTrue(chatgpt.hostedWebSearch);
        assertTrue(chatgpt.reasoningLevels);
        assertFalse(chatgpt.offline);

        AiCapabilities local = AiProviders.byId(Prefs.PROVIDER_LOCAL).capabilities();
        assertTrue("offline operation is Orbit Local's whole point", local.offline);
        assertTrue(local.streaming);
        assertFalse("device actions are not implemented locally yet and must not be claimed",
                local.deviceActions);
        assertFalse(local.images);
        assertFalse(local.needsCredentials);
        assertFalse("the single local model has no real strength levels", local.reasoningLevels);
        assertFalse(local.routinePlanning);

        AiCapabilities relay = AiProviders.byId(Prefs.PROVIDER_RELAY).capabilities();
        assertFalse("the relay returns one complete response", relay.streaming);
        assertFalse(relay.offline);
    }

    @Test public void unreadyProvidersAnswerWithClearErrorsNotExceptions() {
        AtomicReference<String> error = new AtomicReference<>();
        AssistantClient.Callback callback = new AssistantClient.Callback() {
            @Override public void onSuccess(AssistantReply reply) {}
            @Override public void onError(String message) { error.set(message); }
        };
        AiRequest request = AiRequest.builder().prompt("hello").build();

        AiProviders.byId(Prefs.PROVIDER_CHATGPT).send(context, request, callback);
        assertNotNull(error.get());
        assertTrue("signed-out ChatGPT must ask for sign-in, got: " + error.get(),
                error.get().contains("Sign in with ChatGPT"));

        error.set(null);
        AiProviders.byId(Prefs.PROVIDER_LOCAL).send(context, request, callback);
        assertNotNull(error.get());
        assertTrue("Orbit Local without its model must say so, got: " + error.get(),
                error.get().contains("not installed"));

        error.set(null);
        AiProviders.byId(Prefs.PROVIDER_RELAY).send(context, request, callback);
        assertNotNull(error.get());
        assertTrue("an unconfigured relay must say so, got: " + error.get(),
                error.get().contains("no relay is configured"));

        error.set(null);
        AiProviders.byId(Prefs.PROVIDER_OPENROUTER).send(context, request, callback);
        assertNotNull(error.get());
        assertTrue(error.get().contains("not available yet"));
    }

    @Test public void planningOnOrbitLocalFailsWithGuidanceInsteadOfBadJson() {
        AtomicReference<String> error = new AtomicReference<>();
        AiProviders.byId(Prefs.PROVIDER_LOCAL).plan(context, "turn on the flashlight at 9",
                Prefs.MODE_BALANCED, new AssistantClient.PlanCallback() {
                    @Override public void onText(String rawResponse, String providerLabel) {}
                    @Override public void onError(String message) { error.set(message); }
                });
        assertNotNull(error.get());
        assertTrue(error.get().contains("ChatGPT"));
    }

    @Test public void capabilityChipsStateStrengthsAndGapsPlainly() {
        java.util.List<String> local = AiProvidersActivity.capabilityChips(
                AiProviders.byId(Prefs.PROVIDER_LOCAL).capabilities());
        assertTrue(local.contains("Works offline"));
        assertFalse("a chip may never claim an absent capability",
                local.contains("Device actions"));
        assertTrue("missing device actions must be admitted, not hidden",
                AiProvidersActivity.capabilityLimitation(
                        AiProviders.byId(Prefs.PROVIDER_LOCAL).capabilities())
                        .contains("Can't run device actions"));

        AiCapabilities chatgptCaps = AiProviders.byId(Prefs.PROVIDER_CHATGPT).capabilities();
        assertTrue(AiProvidersActivity.capabilityChips(chatgptCaps).contains("Device actions"));
        assertEquals("a fully capable provider has no limitation line",
                "", AiProvidersActivity.capabilityLimitation(chatgptCaps));
    }

    /** Status and offered actions must always agree: not-ready providers are not selectable UI. */
    @Test public void cardActionsFollowProviderState() {
        assertEquals(java.util.Arrays.asList(
                        AiProvidersActivity.ACTION_USE, AiProvidersActivity.ACTION_MANAGE),
                AiProvidersActivity.actionLabels(AiProvider.Status.READY, false));
        assertEquals("the active provider needs no Use button",
                java.util.Collections.singletonList(AiProvidersActivity.ACTION_MANAGE),
                AiProvidersActivity.actionLabels(AiProvider.Status.READY, true));
        assertEquals("an unconfigured provider offers only the step that makes it usable",
                java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.NEEDS_SETUP, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.NOT_INSTALLED, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_SET_UP),
                AiProvidersActivity.actionLabels(AiProvider.Status.COMING_SOON, false));
        assertEquals(java.util.Collections.singletonList(AiProvidersActivity.ACTION_DETAILS),
                AiProvidersActivity.actionLabels(AiProvider.Status.UNSUPPORTED, false));
        for (AiProvider.Status status : AiProvider.Status.values()) {
            if (status == AiProvider.Status.READY) continue;
            assertFalse("Use this provider must never appear while " + status,
                    AiProvidersActivity.actionLabels(status, false)
                            .contains(AiProvidersActivity.ACTION_USE));
        }
    }
}

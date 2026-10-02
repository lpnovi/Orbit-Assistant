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

import java.util.Map;

/** Claude and xAI credentials follow the same hardware-only, provider-isolated security contract. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProviderCredentialSecurityTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void keysAreNeverPlaintextAndNeverBackedUp() throws Exception {
        String anthropic = "sk-ant-test-very-secret";
        String xai = "xai-test-very-secret";
        SecureStore.saveAnthropicKey(context, anthropic);
        SecureStore.saveXaiKey(context, xai);
        for (Map.Entry<String, ?> entry : Prefs.get(context).getAll().entrySet()) {
            String value = String.valueOf(entry.getValue());
            assertFalse(entry.getKey(), value.contains(anthropic));
            assertFalse(entry.getKey(), value.contains(xai));
        }
        String backup = Prefs.backupSnapshot(context).toString();
        assertFalse(backup.contains("anthropic_key"));
        assertFalse(backup.contains("xai_key"));
        assertFalse(backup.contains(anthropic));
        assertFalse(backup.contains(xai));
    }

    @Test public void saveIsAllOrNothingAndCredentialsAreProviderIsolated() {
        boolean aSaved = SecureStore.saveAnthropicKey(context, "anthropic-only");
        if (aSaved) assertEquals("anthropic-only", SecureStore.loadAnthropicKey(context));
        else assertEquals("", SecureStore.loadAnthropicKey(context));
        assertEquals("one provider never reads the other's alias", "", SecureStore.loadXaiKey(context));

        boolean xSaved = SecureStore.saveXaiKey(context, "xai-only");
        if (xSaved) assertEquals("xai-only", SecureStore.loadXaiKey(context));
        else assertEquals("", SecureStore.loadXaiKey(context));
        assertFalse(SecureStore.loadAnthropicKey(context).contains("xai-only"));
    }

    @Test public void deletingCredentialImmediatelyMakesProviderUnready() {
        SecureStore.saveAnthropicKey(context, "anthropic-key");
        SecureStore.clearAnthropicKey(context);
        assertFalse(SecureStore.hasAnthropicKey(context));
        assertEquals(AiProvider.Status.NEEDS_SETUP,
                AiProviders.byId(Prefs.PROVIDER_ANTHROPIC).status(context));
        SecureStore.saveXaiKey(context, "xai-key");
        SecureStore.clearXaiKey(context);
        assertFalse(SecureStore.hasXaiKey(context));
        assertEquals(AiProvider.Status.NEEDS_SETUP,
                AiProviders.byId(Prefs.PROVIDER_XAI).status(context));
    }

    @Test public void responseDetailsContainSelectionNotCredentials() throws Exception {
        ResponseDetails details = ResponseDetails.sentWith(AiSelection.of(
                Prefs.PROVIDER_ANTHROPIC, OrbitModelCatalog.CLAUDE_OPUS_5_5,
                AiStrength.MEDIUM));
        String serialized = details.toJson().toString();
        assertTrue(serialized.contains(OrbitModelCatalog.CLAUDE_OPUS_5_5));
        assertFalse(serialized.toLowerCase().contains("api key"));
        assertFalse(serialized.contains("sk-ant"));
    }
}

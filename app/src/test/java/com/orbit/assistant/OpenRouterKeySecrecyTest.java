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

/**
 * Provider API keys must never exist in plaintext, never enter backups, and never be silently
 * downgraded to insecure storage. Robolectric has no real Android Keystore, which makes it the
 * perfect hostile environment: encryption is impossible here, so saving must fail visibly and
 * leave no trace, rather than fall back to plaintext the way the low-stakes relay token does.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OpenRouterKeySecrecyTest {
    private static final String SECRET = "sk-or-v1-super-secret-value";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void theKeyIsNeverStoredInPlaintext() {
        SecureStore.saveOpenRouterKey(context, SECRET);
        for (Map.Entry<String, ?> entry : Prefs.get(context).getAll().entrySet()) {
            Object value = entry.getValue();
            assertFalse("preference '" + entry.getKey() + "' contains the raw API key",
                    value instanceof String && ((String) value).contains(SECRET));
        }
    }

    @Test public void savingIsAllOrNothingNeverDowngraded() {
        boolean saved = SecureStore.saveOpenRouterKey(context, SECRET);
        if (saved) {
            // An environment with a working keystore must round-trip the key encrypted.
            assertEquals(SECRET, SecureStore.loadOpenRouterKey(context));
            assertTrue(SecureStore.hasOpenRouterKey(context));
        } else {
            // Without a keystore the save must fail visibly and leave nothing readable — there
            // is deliberately no plaintext fallback for provider API keys.
            assertEquals("", SecureStore.loadOpenRouterKey(context));
            assertFalse(SecureStore.hasOpenRouterKey(context));
        }
    }

    @Test public void clearingAnEmptyKeyIsSafe() {
        SecureStore.clearOpenRouterKey(context);
        assertFalse(SecureStore.hasOpenRouterKey(context));
        assertTrue(SecureStore.saveOpenRouterKey(context, ""));
    }

    @Test public void backupsNeverContainProviderCredentialKeys() throws Exception {
        // Even if encrypted blobs existed, the backup snapshot must not carry them.
        Prefs.get(context).edit()
                .putString("openrouter_key_enc", "blob")
                .putString("openrouter_key_iv", "iv")
                .commit();
        org.json.JSONObject snapshot = Prefs.backupSnapshot(context);
        assertFalse(snapshot.has("openrouter_key_enc"));
        assertFalse(snapshot.has("openrouter_key_iv"));
    }
}

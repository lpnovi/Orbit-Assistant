package com.orbit.assistant;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public final class ExtensionsActivityInstrumentedTest {
    private Context context;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        clearState();
    }

    @After public void tearDown() { clearState(); }

    @Test public void emptyState_remainsResumedAfterFiveSeconds() {
        assertLaunchRemainsResumed(5000L);
    }

    @Test public void threeV1Extensions_launch() throws Exception {
        seedInstalled("orbit-web-tools.orbitext", "developer-tools.orbitext",
                "quick-links.orbitext");
        assertLaunchRemainsResumed(500L);
    }

    @Test public void discordUnconfigured_launch() throws Exception {
        seedInstalled("discord-webhook.orbitext");
        assertLaunchRemainsResumed(500L);
    }

    @Test public void ntfyUnconfigured_launch() throws Exception {
        seedInstalled("ntfy-notifications.orbitext");
        assertLaunchRemainsResumed(500L);
    }

    @Test public void mixedV1V2_launch() throws Exception {
        seedInstalled("orbit-web-tools.orbitext", "discord-webhook.orbitext",
                "ntfy-notifications.orbitext");
        assertLaunchRemainsResumed(500L);
    }

    @Test public void disabledV2_launch() throws Exception {
        seedInstalled("discord-webhook.orbitext");
        OrbitExtensionStore.setEnabled(context, "com.orbit.extensions.discord-webhook", false);
        assertLaunchRemainsResumed(500L);
    }

    @Test public void repeatedLaunchAndExit_twentyTimes() {
        for (int i = 0; i < 20; i++) assertLaunchRemainsResumed(100L);
    }

    private void assertLaunchRemainsResumed(long waitMillis) {
        try (ActivityScenario<ExtensionsActivity> scenario =
                     ActivityScenario.launch(ExtensionsActivity.class)) {
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
            SystemClock.sleep(waitMillis);
            assertEquals(Lifecycle.State.RESUMED, scenario.getState());
        }
    }

    private void seedInstalled(String... assets) throws Exception {
        JSONArray entries = new JSONArray();
        long installedAt = 1700000000000L;
        for (String asset : assets) {
            entries.put(new JSONObject()
                    .put("manifest", new JSONObject(readAsset(asset)))
                    .put("enabled", true)
                    .put("installedAt", installedAt++));
        }
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().putString("installed_v1", entries.toString()).commit();
    }

    private String readAsset(String name) throws Exception {
        try (InputStream input = context.getAssets().open("orbit-extensions/" + name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void clearState() {
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("orbit_extension_secrets", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }
}

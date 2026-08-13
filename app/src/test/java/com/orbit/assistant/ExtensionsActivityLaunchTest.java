package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ExtensionsActivityLaunchTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("orbit_extension_secrets", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After public void tearDown() {
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("orbit_extension_secrets", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test public void freshState_launchesAndStaysOpen() {
        assertLaunches();
    }

    @Test public void allBundledManifests_parseUnderDeclaredSchema() throws Exception {
        String[] assets = {"orbit-web-tools.orbitext", "developer-tools.orbitext",
                "quick-links.orbitext", "discord-webhook.orbitext",
                "ntfy-notifications.orbitext"};
        List<Integer> schemas = new ArrayList<>();
        for (String asset : assets)
            schemas.add(OrbitExtension.parse(readAsset(asset)).schemaVersion);
        assertEquals(java.util.Arrays.asList(1, 1, 1, 2, 2), schemas);
    }

    @Test public void legacyV1UpgradeState_launches() throws Exception {
        seedInstalled("orbit-web-tools.orbitext", "developer-tools.orbitext",
                "quick-links.orbitext");
        assertLaunches();
        assertEquals(3, OrbitExtensionStore.list(context).size());
        for (OrbitExtensionStore.Installed installed : OrbitExtensionStore.list(context))
            assertTrue(OrbitExtensionStore.isConfigured(context, installed));
    }

    @Test public void unconfiguredV2AndUnreadableSecretState_launchesAsNeedsSetup()
            throws Exception {
        seedInstalled("discord-webhook.orbitext");
        context.getSharedPreferences("orbit_extension_secrets", Context.MODE_PRIVATE)
                .edit().putString("encrypted_entries_v1", "{not-json").commit();
        ExtensionsActivity activity = assertLaunches();
        assertTrue(collectText(activity.getWindow().getDecorView()).contains("NEEDS SETUP"));
    }

    @Test public void configuredV2AndMixedV1V2State_launches() throws Exception {
        seedInstalled("orbit-web-tools.orbitext", "ntfy-notifications.orbitext");
        JSONObject configuration = new JSONObject()
                .put("server_url", "https://ntfy.sh")
                .put("topic", "orbit-test");
        assertTrue(OrbitExtensionStore.setConfiguration(
                context, "com.orbit.extensions.ntfy", configuration));
        assertLaunches();
        OrbitExtensionStore.Installed installed = OrbitExtensionStore.find(
                context, "com.orbit.extensions.ntfy");
        assertNotNull(installed);
        assertTrue(OrbitExtensionStore.isConfigured(context, installed));
    }

    @Test public void disabledRemovedAndReinstalledState_launches() throws Exception {
        OrbitExtension extension = OrbitExtension.parse(readAsset("orbit-web-tools.orbitext"));
        assertTrue(OrbitExtensionStore.install(context, extension));
        assertTrue(OrbitExtensionStore.setEnabled(context, extension.id, false));
        assertLaunches();
        assertTrue(OrbitExtensionStore.remove(context, extension.id));
        assertTrue(OrbitExtensionStore.install(context, extension));
        assertLaunches();
    }

    @Test public void corruptIndividualEntry_isolatedAndRemovableWithoutTouchingValidEntry()
            throws Exception {
        JSONArray entries = new JSONArray()
                .put(new JSONObject().put("manifest", new JSONObject()
                        .put("schemaVersion", 999).put("id", "broken.extension")))
                .put(new JSONObject()
                        .put("manifest", new JSONObject(readAsset("orbit-web-tools.orbitext")))
                        .put("enabled", true)
                        .put("installedAt", 1700000000000L));
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().putString("installed_v1", entries.toString()).commit();

        ExtensionsActivity activity = assertLaunches();
        assertTrue(collectText(activity.getWindow().getDecorView())
                .contains("Extension unavailable"));
        OrbitExtensionStore.ManagerSnapshot snapshot = OrbitExtensionStore.managerSnapshot(context);
        assertTrue(snapshot.entries.get(0).isUnavailable());
        assertTrue(OrbitExtensionStore.removeUnavailable(
                context, snapshot.entries.get(0).removalToken));
        assertEquals(1, OrbitExtensionStore.list(context).size());
        assertEquals("com.orbit.extensions.web-tools",
                OrbitExtensionStore.list(context).get(0).extension.id);
    }

    @Test public void staleWrongTypeStore_isolatedAndRemovable() {
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().putInt("installed_v1", 7).commit();
        ExtensionsActivity activity = assertLaunches();
        assertTrue(collectText(activity.getWindow().getDecorView())
                .contains("Extension unavailable"));
        String token = OrbitExtensionStore.managerSnapshot(context).unreadableStoreToken;
        assertNotNull(token);
        assertTrue(OrbitExtensionStore.removeUnavailable(context, token));
        assertTrue(OrbitExtensionStore.managerSnapshot(context).entries.isEmpty());
    }

    @Test public void validMutation_preservesNeighboringUnavailableEntry() throws Exception {
        JSONArray entries = new JSONArray()
                .put(new JSONObject().put("manifest", "not-an-object"))
                .put(new JSONObject()
                        .put("manifest", new JSONObject(readAsset("orbit-web-tools.orbitext")))
                        .put("enabled", true)
                        .put("installedAt", 1700000000000L));
        context.getSharedPreferences("orbit_extensions", Context.MODE_PRIVATE)
                .edit().putString("installed_v1", entries.toString()).commit();
        assertTrue(OrbitExtensionStore.setEnabled(
                context, "com.orbit.extensions.web-tools", false));
        OrbitExtensionStore.ManagerSnapshot snapshot = OrbitExtensionStore.managerSnapshot(context);
        assertEquals(2, snapshot.entries.size());
        assertTrue(snapshot.entries.get(0).isUnavailable());
        assertFalse(snapshot.entries.get(1).installed.enabled);
    }

    private ExtensionsActivity assertLaunches() {
        try (ActivityController<ExtensionsActivity> controller =
                     Robolectric.buildActivity(ExtensionsActivity.class).setup()) {
            ExtensionsActivity activity = controller.get();
            assertNotNull(activity.getWindow().getDecorView());
            assertFalse(activity.isFinishing());
            return activity;
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

    private String collectText(View root) {
        StringBuilder text = new StringBuilder();
        collectText(root, text);
        return text.toString();
    }

    private void collectText(View root, StringBuilder out) {
        if (root instanceof TextView) out.append(((TextView) root).getText()).append('\n');
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++)
                collectText(group.getChildAt(i), out);
        }
    }
}

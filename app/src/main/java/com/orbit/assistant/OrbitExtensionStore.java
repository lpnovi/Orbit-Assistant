package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Private storage for validated declarative extension manifests and enabled state. */
public final class OrbitExtensionStore {
    private static final String LOG_TAG = "OrbitExtensions";
    private static final String FILE = "orbit_extensions";
    private static final String KEY = "installed_v1";
    public static final int MAX_EXTENSIONS = 25;
    private static final int MAX_STORE_BYTES = 2 * 1024 * 1024;

    public static final class Installed {
        public final OrbitExtension extension;
        public final boolean enabled;
        public final long installedAt;
        public final JSONObject configuration;

        private Installed(OrbitExtension extension, boolean enabled, long installedAt,
                          JSONObject configuration) {
            this.extension = extension;
            this.enabled = enabled;
            this.installedAt = installedAt;
            this.configuration = copy(configuration);
        }
    }

    public static final class ActionChoice {
        public final OrbitExtension extension;
        public final OrbitExtension.Action action;

        private ActionChoice(OrbitExtension extension, OrbitExtension.Action action) {
            this.extension = extension;
            this.action = action;
        }
    }

    /** One manager row. Invalid rows retain only an opaque removal token. */
    static final class ManagerEntry {
        final Installed installed;
        final String removalToken;

        private ManagerEntry(Installed installed, String removalToken) {
            this.installed = installed;
            this.removalToken = removalToken;
        }

        boolean isUnavailable() { return installed == null; }
    }

    static final class ManagerSnapshot {
        final List<ManagerEntry> entries;
        final String unreadableStoreToken;

        private ManagerSnapshot(List<ManagerEntry> entries, String unreadableStoreToken) {
            this.entries = Collections.unmodifiableList(entries);
            this.unreadableStoreToken = unreadableStoreToken;
        }
    }

    private OrbitExtensionStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized List<Installed> list(Context context) {
        if (context == null) return Collections.emptyList();
        List<Installed> out = new ArrayList<>();
        for (ManagerEntry entry : managerSnapshot(context).entries)
            if (entry.installed != null) out.add(entry.installed);
        return out;
    }

    static synchronized ManagerSnapshot managerSnapshot(Context context) {
        List<ManagerEntry> entries = new ArrayList<>();
        if (context == null) return new ManagerSnapshot(entries, null);
        final Object stored;
        try { stored = prefs(context).getAll().get(KEY); }
        catch (RuntimeException error) {
            logFailure("store_read", error);
            return new ManagerSnapshot(entries, fingerprint("root:unreadable"));
        }
        if (stored == null) return new ManagerSnapshot(entries, null);
        if (!(stored instanceof String)) {
            logFailure("store_type", new IllegalStateException(stored.getClass().getSimpleName()));
            return new ManagerSnapshot(entries, fingerprint("root:" + stored.getClass().getName()));
        }
        String raw = (String) stored;
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_STORE_BYTES)
            return new ManagerSnapshot(entries, fingerprint("root:" + raw));
        final JSONArray array;
        try { array = new JSONArray(raw); }
        catch (Exception error) {
            logFailure("store_json", error);
            return new ManagerSnapshot(entries, fingerprint("root:" + raw));
        }
        // Render at most the supported capacity plus one explicit overflow row.
        // A stale oversized store therefore cannot create an unbounded view tree.
        for (int i = 0; i < array.length() && entries.size() <= MAX_EXTENSIONS; i++) {
            JSONObject item = array.optJSONObject(i);
            String token = fingerprint(i + ":" + String.valueOf(array.opt(i)));
            if (entries.size() >= MAX_EXTENSIONS) {
                entries.add(new ManagerEntry(null, token));
                break;
            }
            try { entries.add(new ManagerEntry(parseInstalled(item), null)); }
            catch (Exception error) {
                logFailure("entry_parse", error);
                entries.add(new ManagerEntry(null, token));
            }
        }
        return new ManagerSnapshot(entries, null);
    }

    public static synchronized Installed find(Context context, String extensionId) {
        if (extensionId == null) return null;
        for (Installed installed : list(context)) {
            if (installed.extension.id.equals(extensionId)) return installed;
        }
        return null;
    }

    public static synchronized OrbitExtension.Action resolveEnabledAction(
            Context context, String extensionId, String actionId) {
        Installed installed = find(context, extensionId);
        return installed == null || !installed.enabled ? null : installed.extension.findAction(actionId);
    }

    public static synchronized JSONObject configuration(Context context, String extensionId) {
        Installed installed = find(context, extensionId);
        return installed == null ? new JSONObject() : copy(installed.configuration);
    }

    public static synchronized Map<String, String> resolvedConfiguration(
            Context context, Installed installed) {
        if (context == null || installed == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (OrbitExtension.SetupField field : installed.extension.setupFields) {
            String value;
            if (field.isSecret()) {
                value = OrbitExtensionSecretStore.load(context, installed.extension.id, field.id);
            } else {
                value = installed.configuration.optString(field.id, field.defaultValue);
            }
            if (value == null) value = "";
            try { value = OrbitExtensionV2.validateSetupValue(field, value); }
            catch (Exception ignored) { return null; }
            if (field.required && value.isEmpty()) return null;
            values.put(field.id, value);
        }
        return values;
    }

    public static synchronized boolean isConfigured(Context context, Installed installed) {
        return resolvedConfiguration(context, installed) != null;
    }

    public static synchronized List<ActionChoice> enabledActions(Context context) {
        List<ActionChoice> out = new ArrayList<>();
        for (Installed installed : list(context)) {
            if (!installed.enabled || !isConfigured(context, installed)) continue;
            for (OrbitExtension.Action action : installed.extension.actions)
                out.add(new ActionChoice(installed.extension, action));
        }
        return out;
    }

    public static synchronized boolean install(Context context, OrbitExtension extension) {
        if (context == null || extension == null) return false;
        JSONArray current = storedArray(context);
        if (current == null || current.length() >= MAX_EXTENSIONS) return false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            JSONObject manifest = item == null ? null : item.optJSONObject("manifest");
            if (manifest != null && extension.id.equals(manifest.optString("id", ""))) return false;
        }
        try {
            current.put(new JSONObject()
                    .put("manifest", extension.toJson())
                    .put("configuration", new JSONObject())
                    .put("enabled", true)
                    .put("installedAt", System.currentTimeMillis()));
            return commitArray(context, current);
        } catch (Exception ignored) { return false; }
    }

    public static synchronized boolean setEnabled(Context context, String extensionId, boolean enabled) {
        JSONArray current = storedArray(context);
        if (current == null) return false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject raw = current.optJSONObject(i);
            try {
                Installed item = parseInstalled(raw);
                if (!item.extension.id.equals(extensionId)) continue;
                if (item.enabled == enabled) return false;
                raw.put("enabled", enabled);
                return commitArray(context, current);
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static synchronized boolean remove(Context context, String extensionId) {
        JSONArray current = storedArray(context);
        if (current == null) return false;
        int index = -1;
        for (int i = 0; i < current.length(); i++) {
            try {
                if (parseInstalled(current.optJSONObject(i)).extension.id.equals(extensionId)) {
                    index = i;
                    break;
                }
            } catch (Exception ignored) {}
        }
        if (index < 0) return false;
        // Clear credentials first. If secure removal fails, keep the extension installed
        // rather than allowing a later reinstall to silently inherit an old credential.
        if (!OrbitExtensionSecretStore.clearExtension(context, extensionId)) return false;
        current.remove(index);
        return commitArray(context, current);
    }

    static synchronized boolean removeUnavailable(Context context, String removalToken) {
        if (context == null || removalToken == null || removalToken.isEmpty()) return false;
        Object stored = prefs(context).getAll().get(KEY);
        if (!(stored instanceof String)) {
            String token = stored == null ? "" : fingerprint("root:" + stored.getClass().getName());
            return removalToken.equals(token) && prefs(context).edit().remove(KEY).commit();
        }
        String rawStore = (String) stored;
        if (removalToken.equals(fingerprint("root:" + rawStore)))
            return prefs(context).edit().remove(KEY).commit();
        JSONArray current = storedArray(context);
        if (current == null) return false;
        for (int i = 0; i < current.length(); i++) {
            String token = fingerprint(i + ":" + String.valueOf(current.opt(i)));
            if (!removalToken.equals(token)) continue;
            try {
                parseInstalled(current.optJSONObject(i));
                return false;
            } catch (Exception expectedUnavailable) {
                current.remove(i);
                return commitArray(context, current);
            }
        }
        return false;
    }

    public static synchronized boolean setConfiguration(Context context, String extensionId,
                                                         JSONObject configuration) {
        JSONArray current = storedArray(context);
        if (current == null) return false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject raw = current.optJSONObject(i);
            try {
                Installed item = parseInstalled(raw);
                if (!item.extension.id.equals(extensionId)) continue;
                JSONObject safe = OrbitExtensionV2.validateAndNormalizeConfiguration(
                        item.extension, configuration);
                raw.put("configuration", safe);
                return commitArray(context, current);
            } catch (Exception ignored) {
                // Keep scanning: a damaged entry must not shadow a later valid one.
            }
        }
        return false;
    }

    static synchronized JSONArray backupJson(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (Installed item : list(context)) {
                array.put(new JSONObject()
                        .put("manifest", item.extension.toJson())
                        .put("configuration", item.configuration)
                        .put("enabled", item.enabled)
                        .put("installedAt", item.installedAt));
            }
        } catch (Exception ignored) {}
        return array;
    }

    static synchronized boolean restoreBackupJson(Context context, JSONArray array) {
        if (context == null || !isValidBackup(array)) return false;
        return prefs(context).edit().putString(KEY, array.toString()).commit();
    }

    static boolean isValidBackup(JSONArray array) {
        if (array == null || array.length() > MAX_EXTENSIONS) return false;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            JSONObject manifest = item == null ? null : item.optJSONObject("manifest");
            if (manifest == null || !(item.opt("enabled") instanceof Boolean) ||
                    item.optLong("installedAt", -1L) < 0L || hasUnknownEntryKey(item)) return false;
            try {
                OrbitExtension extension = OrbitExtension.parse(manifest);
                OrbitExtensionV2.validateAndNormalizeConfiguration(
                        extension, item.optJSONObject("configuration"));
                if (ids.contains(extension.id)) return false;
                ids.add(extension.id);
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUnknownEntryKey(JSONObject item) {
        Iterator<String> keys = item.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!("manifest".equals(key) || "configuration".equals(key) ||
                    "enabled".equals(key) || "installedAt".equals(key)))
                return true;
        }
        return false;
    }

    private static Installed parseInstalled(JSONObject item) {
        if (item == null) throw new IllegalArgumentException("Missing extension entry.");
        JSONObject manifest = item.optJSONObject("manifest");
        if (manifest == null) throw new IllegalArgumentException("Missing extension manifest.");
        if (item.has("configuration") && item.optJSONObject("configuration") == null)
            throw new IllegalArgumentException("Invalid extension configuration.");
        OrbitExtension extension = OrbitExtension.parse(manifest);
        JSONObject configuration = OrbitExtensionV2.validateAndNormalizeConfiguration(
                extension, item.optJSONObject("configuration"));
        return new Installed(extension, item.optBoolean("enabled", true),
                item.optLong("installedAt", 0L), configuration);
    }

    private static JSONArray storedArray(Context context) {
        if (context == null) return null;
        try {
            Object stored = prefs(context).getAll().get(KEY);
            if (stored == null) return new JSONArray();
            if (!(stored instanceof String)) return null;
            String raw = (String) stored;
            if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_STORE_BYTES) return null;
            return new JSONArray(raw);
        } catch (Exception ignored) { return null; }
    }

    private static boolean commitArray(Context context, JSONArray array) {
        if (context == null || array == null) return false;
        String raw = array.toString();
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_STORE_BYTES) return false;
        return prefs(context).edit().putString(KEY, raw).commit();
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }

    private static void logFailure(String stage, Exception error) {
        Log.w(LOG_TAG, "stage=" + stage + " error=" +
                (error == null ? "unknown" : error.getClass().getSimpleName()));
    }

    private static JSONObject copy(JSONObject source) {
        try { return source == null ? new JSONObject() : new JSONObject(source.toString()); }
        catch (Exception ignored) { return new JSONObject(); }
    }
}

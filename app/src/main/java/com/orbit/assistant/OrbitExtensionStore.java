package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Private storage for validated declarative extension manifests and enabled state. */
public final class OrbitExtensionStore {
    private static final String FILE = "orbit_extensions";
    private static final String KEY = "installed_v1";
    public static final int MAX_EXTENSIONS = 25;

    public static final class Installed {
        public final OrbitExtension extension;
        public final boolean enabled;
        public final long installedAt;

        private Installed(OrbitExtension extension, boolean enabled, long installedAt) {
            this.extension = extension;
            this.enabled = enabled;
            this.installedAt = installedAt;
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

    private OrbitExtensionStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized List<Installed> list(Context context) {
        if (context == null) return Collections.emptyList();
        List<Installed> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs(context).getString(KEY, "[]"));
            for (int i = 0; i < array.length() && out.size() < MAX_EXTENSIONS; i++) {
                JSONObject item = array.optJSONObject(i);
                JSONObject manifest = item == null ? null : item.optJSONObject("manifest");
                if (manifest == null) continue;
                try {
                    out.add(new Installed(OrbitExtension.parse(manifest),
                            item.optBoolean("enabled", true), item.optLong("installedAt", 0L)));
                } catch (Exception ignored) {
                    // Damaged private entries remain inert rather than becoming executable.
                }
            }
        } catch (Exception ignored) {}
        return out;
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

    public static synchronized List<ActionChoice> enabledActions(Context context) {
        List<ActionChoice> out = new ArrayList<>();
        for (Installed installed : list(context)) {
            if (!installed.enabled) continue;
            for (OrbitExtension.Action action : installed.extension.actions)
                out.add(new ActionChoice(installed.extension, action));
        }
        return out;
    }

    public static synchronized boolean install(Context context, OrbitExtension extension) {
        if (context == null || extension == null || find(context, extension.id) != null) return false;
        List<Installed> current = new ArrayList<>(list(context));
        if (current.size() >= MAX_EXTENSIONS) return false;
        current.add(new Installed(extension, true, System.currentTimeMillis()));
        return write(context, current);
    }

    public static synchronized boolean setEnabled(Context context, String extensionId, boolean enabled) {
        List<Installed> current = new ArrayList<>(list(context));
        boolean changed = false;
        for (int i = 0; i < current.size(); i++) {
            Installed item = current.get(i);
            if (!item.extension.id.equals(extensionId)) continue;
            current.set(i, new Installed(item.extension, enabled, item.installedAt));
            changed = item.enabled != enabled;
            break;
        }
        return changed && write(context, current);
    }

    public static synchronized boolean remove(Context context, String extensionId) {
        List<Installed> current = new ArrayList<>(list(context));
        boolean removed = false;
        for (int i = current.size() - 1; i >= 0; i--) {
            if (current.get(i).extension.id.equals(extensionId)) {
                current.remove(i);
                removed = true;
            }
        }
        return removed && write(context, current);
    }

    static synchronized JSONArray backupJson(Context context) {
        JSONArray array = new JSONArray();
        try {
            for (Installed item : list(context)) {
                array.put(new JSONObject()
                        .put("manifest", item.extension.toJson())
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
            if (!("manifest".equals(key) || "enabled".equals(key) || "installedAt".equals(key)))
                return true;
        }
        return false;
    }

    private static boolean write(Context context, List<Installed> installed) {
        JSONArray array = new JSONArray();
        try {
            for (Installed item : installed) {
                array.put(new JSONObject()
                        .put("manifest", item.extension.toJson())
                        .put("enabled", item.enabled)
                        .put("installedAt", item.installedAt));
            }
            return prefs(context).edit().putString(KEY, array.toString()).commit();
        } catch (Exception ignored) {
            return false;
        }
    }
}

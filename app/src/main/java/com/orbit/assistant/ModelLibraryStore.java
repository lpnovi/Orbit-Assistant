package com.orbit.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Durable user state for Model Library. Catalog caches deliberately live elsewhere. */
public final class ModelLibraryStore {
    static final int MAX_RECENTS = 6;

    private ModelLibraryStore() {}

    private static String key(String provider, String model) {
        return safe(provider) + "/" + safe(model);
    }

    public static synchronized boolean isFavorite(Context c, String provider, String model) {
        String wanted = key(provider, model);
        for (String item : readStrings(c, Prefs.AI_MODEL_FAVORITES)) {
            if (wanted.equals(item)) return true;
        }
        return false;
    }

    public static synchronized void setFavorite(Context c, String provider, String model,
                                                boolean favorite) {
        String wanted = key(provider, model);
        List<String> values = readStrings(c, Prefs.AI_MODEL_FAVORITES);
        values.remove(wanted);
        if (favorite) values.add(wanted);
        writeStrings(c, Prefs.AI_MODEL_FAVORITES, values);
    }

    public static synchronized List<AiSelection> favorites(Context c) {
        List<AiSelection> out = new ArrayList<>();
        for (String item : readStrings(c, Prefs.AI_MODEL_FAVORITES)) {
            int slash = item.indexOf('/');
            if (slash <= 0 || slash == item.length() - 1) continue;
            String provider = item.substring(0, slash);
            String model = item.substring(slash + 1);
            AiModelSpec spec = OrbitModelCatalog.spec(provider, model);
            out.add(AiSelection.of(provider, model, spec == null ? null : spec.defaultStrength));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Records only a real user conversation turn the user chose explicitly. Metadata jobs never
     * call this method, and neither does an Auto-routed turn: Recents are the models the user
     * picked, never the ones Auto picked for them.
     */
    public static synchronized void recordRecent(Context c, AiSelection selection) {
        if (c == null || selection == null || selection.provider.isEmpty() || selection.model.isEmpty()
                || selection.isAuto()) return;
        String encoded = selection.encode();
        List<String> values = readStrings(c, Prefs.AI_MODEL_RECENTS);
        for (int i = values.size() - 1; i >= 0; i--) {
            AiSelection old = AiSelection.decode(values.get(i));
            if (old == null || (old.provider.equals(selection.provider)
                    && old.model.equals(selection.model))) values.remove(i);
        }
        values.add(0, encoded);
        while (values.size() > MAX_RECENTS) values.remove(values.size() - 1);
        writeStrings(c, Prefs.AI_MODEL_RECENTS, values);
        rememberProviderDefault(c, selection);
    }

    public static synchronized List<AiSelection> recents(Context c) {
        List<AiSelection> out = new ArrayList<>();
        for (String value : readStrings(c, Prefs.AI_MODEL_RECENTS)) {
            AiSelection decoded = AiSelection.decode(value);
            if (decoded != null) out.add(decoded);
        }
        return Collections.unmodifiableList(out);
    }

    /** Auto is nobody's provider default: switching a chat to Auto leaves these untouched. */
    public static synchronized void rememberProviderDefault(Context c, AiSelection selection) {
        if (c == null || selection == null || selection.isAuto()) return;
        try {
            JSONObject all = new JSONObject(Prefs.get(c).getString(Prefs.AI_PROVIDER_DEFAULTS, "{}"));
            all.put(selection.provider, selection.encode());
            Prefs.get(c).edit().putString(Prefs.AI_PROVIDER_DEFAULTS, all.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static synchronized AiSelection providerDefault(Context c, String provider) {
        try {
            JSONObject all = new JSONObject(Prefs.get(c).getString(Prefs.AI_PROVIDER_DEFAULTS, "{}"));
            AiSelection stored = AiSelection.decode(all.optString(provider, ""));
            if (stored != null && provider.equals(stored.provider)
                    && (OrbitModelCatalog.spec(provider, stored.model) != null
                    || Prefs.PROVIDER_ANTHROPIC.equals(provider)
                    || Prefs.PROVIDER_XAI.equals(provider))) return stored;
        } catch (Exception ignored) {}
        AiModelSpec first = OrbitModelCatalog.defaultModel(provider);
        return first == null ? null : AiSelection.of(provider, first.id, first.defaultStrength);
    }

    private static List<String> readStrings(Context c, String pref) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(Prefs.get(c).getString(pref, "[]"));
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty() && !out.contains(value)) out.add(value);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void writeStrings(Context c, String pref, List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        Prefs.get(c).edit().putString(pref, array.toString()).apply();
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}

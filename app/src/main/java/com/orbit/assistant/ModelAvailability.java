package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What Orbit has actually learned about which models this account can reach.
 *
 * <p>Only first-hand evidence: a model is marked unavailable when the backend refused it in a way
 * {@link OrbitModelCatalog#looksUnavailable} recognises, and cleared the moment it answers. The mark
 * expires after a day because access changes with plans and rollouts. Pickers use it to label a
 * model honestly; nothing uses it to choose a different model on the user's behalf.
 */
public final class ModelAvailability {
    private static final String FILE = "orbit_model_availability";
    static final long EXPIRY_MS = 24L * 60L * 60L * 1000L;

    private ModelAvailability() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String key(String provider, String model) {
        return (provider == null ? "" : provider) + "/" + (model == null ? "" : model);
    }

    public static void markUnavailable(Context c, String provider, String model) {
        if (c == null) return;
        prefs(c).edit().putLong(key(provider, model), System.currentTimeMillis()).apply();
    }

    public static void markAvailable(Context c, String provider, String model) {
        if (c == null) return;
        SharedPreferences p = prefs(c);
        if (p.contains(key(provider, model))) p.edit().remove(key(provider, model)).apply();
    }

    /** True when this account refused this model within the last day. */
    public static boolean knownUnavailable(Context c, String provider, String model) {
        if (c == null) return false;
        long at = prefs(c).getLong(key(provider, model), 0L);
        return at > 0L && System.currentTimeMillis() - at < EXPIRY_MS;
    }
}

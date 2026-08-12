package com.orbit.assistant;

import android.content.Context;
import android.content.SharedPreferences;

/** Tiny private state cache used only by Orbit's hidden diagnostics screen. */
public final class DiagnosticStore {
    private static final String FILE = "orbit_diagnostics";
    private DiagnosticStore() {}

    public static void recordScreen(Context c, String pkg, String label, boolean hasText, boolean hasScreenshot) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("foreground_package", safe(pkg))
                .putString("foreground_label", safe(label))
                .putBoolean("screen_text", hasText)
                .putBoolean("screenshot", hasScreenshot)
                .putLong("screen_updated", System.currentTimeMillis())
                .apply();
    }

    public static void recordClassification(Context c, String category, int confidence,
                                            boolean profileOverride, String reason) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("context_category", safe(category))
                .putInt("context_confidence", Math.max(0, Math.min(100, confidence)))
                .putBoolean("context_profile_override", profileOverride)
                .putString("context_reason", safe(reason))
                .putLong("context_updated", System.currentTimeMillis())
                .apply();
    }

    public static void recordAutoRouting(Context c, String mode, int confidence,
                                         String reason, String model, String reasoning) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("auto_mode", Prefs.normalizeMode(mode))
                .putInt("auto_confidence", Math.max(0, Math.min(100, confidence)))
                .putString("auto_reason", safe(reason))
                .putString("auto_model", safe(model))
                .putString("auto_reasoning", safe(reasoning))
                .putLong("auto_updated", System.currentTimeMillis())
                .apply();
    }

    public static void recordAppBehavior(Context c, String profileSource, String privacy,
                                         String screenPolicy, String screenshotPolicy,
                                         String mode, String actions) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("app_profile_source", safe(profileSource))
                .putString("app_effective_privacy", safe(privacy))
                .putString("app_effective_screen", safe(screenPolicy))
                .putString("app_effective_screenshot", safe(screenshotPolicy))
                .putString("app_effective_mode", safe(mode))
                .putString("app_effective_actions", safe(actions))
                .putLong("app_behavior_updated", System.currentTimeMillis())
                .apply();
    }

    public static void recordError(Context c, String error) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("last_error", safe(error))
                .putLong("error_updated", System.currentTimeMillis())
                .apply();
    }

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String lastForegroundPackage(Context c) {
        return prefs(c).getString("foreground_package", "");
    }

    public static String lastForegroundAppLabel(Context c) {
        return prefs(c).getString("foreground_label", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}

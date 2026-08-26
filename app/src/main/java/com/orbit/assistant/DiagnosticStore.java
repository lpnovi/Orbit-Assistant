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

    /**
     * The last Create with Orbit planning attempt, for the hidden diagnostics screen.
     *
     * <p>Records only the planning exchange: which provider answered, what shape the response had,
     * how many steps survived validation, and a bounded copy of the planner response itself. The
     * routine description was already sent to the planner by the user's own request. Nothing else
     * about the device is recorded here: no memories, notifications, screen context, attachments,
     * saved-place coordinates, or extension secrets ever reach the planning path in the first
     * place, so none of them can appear in this trace.
     */
    public static void recordRoutinePlan(Context c, String provider, String rawResponse,
                                         RoutineDraft.Outcome outcome, boolean repairAttempted,
                                         String failure) {
        if (c == null) return;
        String raw = safe(rawResponse).trim();
        if (raw.length() > MAX_PLAN_TRACE) raw = raw.substring(0, MAX_PLAN_TRACE) + "… (truncated)";
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("plan_provider", safe(provider))
                .putString("plan_shape", outcome == null ? "no response" : outcome.shape)
                .putBoolean("plan_parsed", outcome != null && outcome.planFound)
                .putInt("plan_steps_returned", outcome == null ? 0 : outcome.stepsReturned)
                .putInt("plan_steps_accepted", outcome == null ? 0 : outcome.stepsAccepted)
                .putString("plan_types", outcome == null ? "" : join(outcome.returnedTypes))
                .putString("plan_rejected", outcome == null ? "" : join(outcome.rejected))
                .putBoolean("plan_trigger", outcome != null && outcome.draft != null
                        && outcome.draft.hasTrigger())
                .putBoolean("plan_repair", repairAttempted)
                .putString("plan_failure", safe(failure))
                .putString("plan_raw", raw)
                .putLong("plan_updated", System.currentTimeMillis())
                .apply();
    }

    private static final int MAX_PLAN_TRACE = 1200;

    private static String join(java.util.List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) out.append(", ");
            out.append(safe(value));
        }
        return out.toString();
    }

    /**
     * How far the last Orbit Local component uninstall got, and what threw if anything did.
     *
     * <p>Stage names and exception class names only — no filesystem paths, no model bytes, and
     * nothing about any conversation. This exists because v0.7.7.5's removal failed completely
     * silently on a real device: the platform refused the request without an exception and Orbit
     * caught nothing, so there was no trace anywhere of a button that plainly did nothing.
     */
    public static void recordComponentUninstall(Context c, String stage, String detail) {
        if (c == null) return;
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("local_uninstall_stage", safe(stage))
                .putString("local_uninstall_detail", safe(detail))
                .putLong("local_uninstall_updated", System.currentTimeMillis())
                .apply();
    }

    public static String lastComponentUninstallStage(Context c) {
        return prefs(c).getString("local_uninstall_stage", "");
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

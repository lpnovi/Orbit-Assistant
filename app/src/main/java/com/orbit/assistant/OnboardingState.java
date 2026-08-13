package com.orbit.assistant;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.Map;

/** Versioned onboarding state plus the one-time legacy-install migration gate. */
public final class OnboardingState {
    public static final int CURRENT_VERSION = 1;
    private static final String COMPLETED_VERSION = "onboarding_version_completed";
    private static final String CURRENT_STEP = "onboarding_current_step";
    private static final String STARTED_VERSION = "onboarding_started_version";
    private static final String ASK_TILE_CONFIRMED = "onboarding_ask_tile_confirmed";
    private static final String STARTER_ROUTINE_ID = "onboarding_starter_routine_id";

    private OnboardingState() {}

    public static boolean shouldLaunchAutomatically(Context c) {
        int completed = completedVersion(c);
        if (completed >= CURRENT_VERSION) return false;
        if (Prefs.get(c).getInt(STARTED_VERSION, 0) == CURRENT_VERSION) return true;
        // A prior explicit onboarding completion is not a legacy-install ambiguity.
        // Future materially new onboarding versions should be allowed to launch.
        if (completed > 0) {
            Prefs.get(c).edit().putInt(STARTED_VERSION, CURRENT_VERSION)
                    .putInt(CURRENT_STEP, 0).commit();
            return true;
        }
        if (looksLikeLegacyInstall(c)) {
            markCompleted(c);
            return false;
        }
        Prefs.get(c).edit().putInt(STARTED_VERSION, CURRENT_VERSION).commit();
        return true;
    }

    public static int completedVersion(Context c) {
        return Prefs.get(c).getInt(COMPLETED_VERSION, 0);
    }

    public static void markCompleted(Context c) {
        Prefs.get(c).edit()
                .putInt(COMPLETED_VERSION, CURRENT_VERSION)
                .remove(CURRENT_STEP)
                .remove(STARTED_VERSION)
                .commit();
    }

    public static int currentStep(Context c) {
        return Math.max(0, Math.min(7, Prefs.get(c).getInt(CURRENT_STEP, 0)));
    }

    public static void setCurrentStep(Context c, int step) {
        Prefs.get(c).edit().putInt(CURRENT_STEP, Math.max(0, Math.min(7, step))).commit();
    }

    public static boolean askTileConfirmed(Context c) {
        return Prefs.get(c).getBoolean(ASK_TILE_CONFIRMED, false);
    }

    public static void setAskTileConfirmed(Context c, boolean confirmed) {
        Prefs.get(c).edit().putBoolean(ASK_TILE_CONFIRMED, confirmed).apply();
    }

    public static String starterRoutineId(Context c) {
        return Prefs.get(c).getString(STARTER_ROUTINE_ID, "").trim();
    }

    public static void setStarterRoutineId(Context c, String routineId) {
        Prefs.get(c).edit().putString(STARTER_ROUTINE_ID,
                routineId == null ? "" : routineId.trim()).apply();
    }

    private static boolean looksLikeLegacyInstall(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            // A real package update is authoritative even if the user kept Orbit's defaults.
            if (info.lastUpdateTime - info.firstInstallTime > 5_000L) return true;
        } catch (Exception ignored) {}

        if (ChatGptAuth.isSignedIn(c)) return true;
        if (!ConversationStore.list(c).isEmpty() || !RoutineStore.list(c).isEmpty() ||
                !MemoryStore.list(c).isEmpty() || !ReminderStore.list(c).isEmpty() ||
                !SavedPlaceStore.list(c).isEmpty() || !AppProfileStore.list(c).isEmpty() ||
                !CustomCommandStore.list(c).isEmpty()) return true;

        for (Map.Entry<String, ?> entry : Prefs.get(c).getAll().entrySet()) {
            String key = entry.getKey();
            if (!COMPLETED_VERSION.equals(key) && !CURRENT_STEP.equals(key) &&
                    !STARTED_VERSION.equals(key) && !ASK_TILE_CONFIRMED.equals(key) &&
                    !STARTER_ROUTINE_ID.equals(key)) return true;
        }
        return false;
    }
}

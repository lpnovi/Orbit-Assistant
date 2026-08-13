package com.orbit.assistant;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.widget.Toast;

/** Shared setup/status paths used by Settings and first-run onboarding. */
public final class OrbitSetupHelper {
    private OrbitSetupHelper() {}

    public static boolean isOrbitAssistantActive(Context context) {
        if (context == null) return false;
        return VoiceInteractionService.isActiveService(context,
                new ComponentName(context, OrbitVoiceInteractionService.class));
    }

    public static boolean openAssistantSettings(Activity activity, int requestCode,
                                                boolean explain) {
        if (activity == null) return false;
        Intent[] candidates = new Intent[]{
                new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS)
        };
        for (Intent candidate : candidates) {
            try {
                if (candidate.resolveActivity(activity.getPackageManager()) == null) continue;
                if (explain) Toast.makeText(activity,
                        "Choose Orbit as the Digital assistant app", Toast.LENGTH_LONG).show();
                activity.startActivityForResult(candidate, requestCode);
                return true;
            } catch (Exception ignored) {}
        }
        return false;
    }
}

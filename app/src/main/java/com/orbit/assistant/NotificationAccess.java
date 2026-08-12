package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

public final class NotificationAccess {
    private NotificationAccess() {}

    public static boolean enabled(Context c) {
        if (c == null) return false;
        try {
            return NotificationManagerCompat.getEnabledListenerPackages(c)
                    .contains(c.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
    }
}

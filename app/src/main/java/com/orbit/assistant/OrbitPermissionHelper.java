package com.orbit.assistant;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import java.util.Locale;

/** Central home for special Android permission/setup screens used by Orbit actions. */
public final class OrbitPermissionHelper {
    private OrbitPermissionHelper() {}

    public static boolean canWriteSystemSettings(Context context) {
        return context != null && Settings.System.canWrite(context);
    }

    public static boolean hasDndAccess(Context context) {
        if (context == null) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.isNotificationPolicyAccessGranted();
    }

    public static boolean supportsSetupFor(AssistantReply.Action action) {
        if (action == null || action.type == null) return false;
        String type = action.type.toUpperCase(Locale.US);
        return "SET_BRIGHTNESS".equals(type) || "SET_DND".equals(type) || "SET_REMINDER".equals(type);
    }

    public static boolean openSetupForAction(Context context, AssistantReply.Action action) {
        if (context == null || action == null || action.type == null) return false;
        String type = action.type.toUpperCase(Locale.US);
        if ("SET_BRIGHTNESS".equals(type)) return openWriteSettings(context);
        if ("SET_DND".equals(type)) return openDndAccess(context);
        if ("SET_REMINDER".equals(type)) { ReminderNotifier.openSettings(context); return true; }
        return false;
    }

    public static boolean openWriteSettings(Context context) {
        if (context == null) return false;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Exception ignored) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
                return true;
            } catch (Exception ignoredAgain) {
                return false;
            }
        }
    }

    public static boolean openDndAccess(Context context) {
        if (context == null) return false;
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}

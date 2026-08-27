package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Shared, direct routing for Android permission and special-access setup surfaces. */
public final class CapabilityAccessHelper {
    private CapabilityAccessHelper() {}

    public static boolean permissionGranted(Activity activity, String permission) {
        return activity != null && permission != null &&
                activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestOrManageRuntimePermission(Activity activity, String permission,
                                                        int requestCode) {
        if (activity == null || permission == null) return;
        if (permissionGranted(activity, permission)) openAppDetails(activity);
        else activity.requestPermissions(new String[]{permission}, requestCode);
    }

    /**
     * Calendar needs both halves at once.
     *
     * <p>Android grants runtime permissions per request, not per group, so asking for only
     * WRITE_CALENDAR would leave Orbit able to insert an event and unable to see which calendar to
     * put it in or to read back what it wrote. Both are therefore requested together, and the row
     * only reads as ready when both are actually held.
     */
    public static void requestOrManageCalendar(Activity activity, int requestCode) {
        if (activity == null) return;
        if (OrbitCalendarStore.hasAccess(activity)) {
            openAppDetails(activity);
            return;
        }
        activity.requestPermissions(new String[]{
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR}, requestCode);
    }

    public static void requestOrManageNotifications(Activity activity, int requestCode) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                !permissionGranted(activity, Manifest.permission.POST_NOTIFICATIONS)) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
            return;
        }
        openAppNotificationSettings(activity);
    }

    public static void openAppDetails(Activity activity) {
        if (activity == null) return;
        openWithFallback(activity,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())),
                new Intent(Settings.ACTION_SETTINGS));
    }

    public static void openAppNotificationSettings(Activity activity) {
        if (activity == null) return;
        Intent notifications = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
        openWithFallback(activity, notifications,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())));
    }

    public static void openNotificationIntelligence(Activity activity) {
        if (activity == null) return;
        openWithFallback(activity, NotificationAccess.settingsIntent(),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName())));
    }

    public static void openWriteSettings(Activity activity) {
        if (activity != null && !OrbitPermissionHelper.openWriteSettings(activity))
            openAppDetails(activity);
    }

    public static void openDndAccess(Activity activity) {
        if (activity != null && !OrbitPermissionHelper.openDndAccess(activity))
            openAppDetails(activity);
    }

    public static void openExactAlarmAccess(Activity activity) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 31) RoutineTriggerScheduler.openExactAlarmAccess(activity);
        else openAppDetails(activity);
    }

    public static void requestOrManageTriggerAlerts(Activity activity, int requestCode) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                !RoutineTriggerNotifier.runtimePermissionGranted(activity)) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
        } else {
            RoutineTriggerNotifier.openAlertSettings(activity);
        }
    }

    /**
     * Progress location automation only after an explicit user action. Android 11+
     * background access remains a clear Settings handoff rather than an automatic request.
     */
    public static void setupLocationAutomation(Activity activity, int fineRequestCode,
                                               int backgroundRequestCode) {
        if (activity == null) return;
        if (!RoutineLocationTriggerScheduler.hasFineLocation(activity)) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, fineRequestCode);
            return;
        }
        if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(activity)) {
            if (Build.VERSION.SDK_INT == 29) {
                activity.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        backgroundRequestCode);
            } else {
                String option = RoutineLocationTriggerScheduler.backgroundPermissionLabel(activity);
                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setTitle("Allow background location")
                        .setMessage("For arrive/leave Routines to work while Orbit is closed, open Orbit's app permissions, choose Location, then select \"" + option + "\".")
                        .setNegativeButton("Not now", null)
                        .setPositiveButton("Open settings", (d, w) ->
                                RoutineLocationTriggerScheduler.openAppLocationSettings(activity))
                        .create();
                UiKit.styleOrbitDialog(dialog, activity, false);
                dialog.show();
            }
            return;
        }
        if (!RoutineLocationTriggerScheduler.isLocationEnabled(activity)) {
            RoutineLocationTriggerScheduler.openLocationServices(activity);
            return;
        }
        RoutineLocationTriggerScheduler.openAppLocationSettings(activity);
    }

    public static String locationAutomationStatus(Activity activity) {
        if (RoutineLocationTriggerScheduler.ready(activity)) return "Ready";
        if (!RoutineLocationTriggerScheduler.hasFineLocation(activity)) return "Needs precise";
        if (!RoutineLocationTriggerScheduler.hasBackgroundLocation(activity)) return "Needs background";
        return "Location off";
    }

    private static void openWithFallback(Activity activity, Intent preferred, Intent fallback) {
        try {
            activity.startActivity(preferred);
        } catch (Exception ignored) {
            try { activity.startActivity(fallback); }
            catch (Exception ignoredAgain) {}
        }
    }
}

package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Attention-only notifications for automatic routines that cannot finish silently. */
public final class RoutineTriggerNotifier {
    private static final String CHANNEL = "orbit_routine_triggers";
    private RoutineTriggerNotifier() {}

    public static void notifyNeedsContinuation(Context c, RoutineStore.Routine routine,
                                               String triggerId, int startIndex, String reason) {
        if (c == null || routine == null) return;
        if (!notificationsAllowed(c)) return;
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        // Chats underneath, so Back returns into Orbit and the swipe gesture has a real page.
        Intent open = new Intent(c, RoutinesActivity.class)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_ROUTINE_ID, routine.id)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_START_INDEX, Math.max(0, startIndex))
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_TRIGGER_ID, triggerId == null ? "" : triggerId);
        int requestCode = ("continue:" + routine.id + ":" + triggerId).hashCode() & 0x7fffffff;
        PendingIntent pi = PendingIntent.getActivities(c, requestCode,
                OrbitNavigation.stackFor(c, open),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String body = reason == null || reason.trim().isEmpty()
                ? "Tap to finish the routine."
                : reason.trim();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle(routine.name + " needs you")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        nm.notify(requestCode, b.build());
    }

    public static void notifyFailure(Context c, RoutineStore.Routine routine, String triggerId, int startIndex, String reason) {
        if (c == null || routine == null || !notificationsAllowed(c)) return;
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Chats underneath, so Back returns into Orbit and the swipe gesture has a real page.
        Intent open = new Intent(c, RoutinesActivity.class)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_ROUTINE_ID, routine.id)
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_START_INDEX, Math.max(0, startIndex))
                .putExtra(RoutinesActivity.EXTRA_AUTORUN_TRIGGER_ID, triggerId == null ? "" : triggerId);
        int requestCode = ("failed:" + routine.id + ":" + triggerId).hashCode() & 0x7fffffff;
        PendingIntent pi = PendingIntent.getActivities(c, requestCode,
                OrbitNavigation.stackFor(c, open),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String base = reason == null || reason.trim().isEmpty() ? "The automatic routine stopped." : reason.trim();
        String body = base + " · Tap to retry from this step.";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle(routine.name + " stopped")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ERROR);
        nm.notify(requestCode, b.build());
    }

    /** True only when Android can actually display Orbit's routine-trigger alerts. */
    public static boolean notificationsAllowed(Context c) {
        if (c == null || !runtimePermissionGranted(c)) return false;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        ensureChannel(c);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL);
            if (ch == null || ch.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    /** Whether Orbit has the Android 13+ runtime notification permission. */
    public static boolean runtimePermissionGranted(Context c) {
        return c != null && (Build.VERSION.SDK_INT < 33 ||
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }

    /** Opens the most specific Android settings page for Orbit's routine-trigger alert channel. */
    public static void openAlertSettings(Context c) {
        if (c == null) return;
        ensureChannel(c);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        }
        if (!(c instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(intent);
        } catch (Exception ignored) {
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof android.app.Activity)) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(fallback);
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Routine triggers", NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Only alerts you when an automatic Orbit routine needs attention or a foreground step.");
        nm.createNotificationChannel(ch);
    }
}

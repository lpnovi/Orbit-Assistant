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

/** Notification delivery for Orbit reminders. */
public final class ReminderNotifier {
    private static final String CHANNEL = OrbitNotificationChannels.REMINDERS;

    private ReminderNotifier() {}

    public static boolean notificationsAllowed(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;
        ensureChannel(c);
        if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        return true;
    }

    public static void show(Context c, ReminderStore.Item item) {
        if (c == null || item == null || !notificationsAllowed(c)) return;
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int requestCode = ("reminder-open:" + item.id).hashCode() & 0x7fffffff;
        PendingIntent pi = PendingIntent.getActivity(c, requestCode, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle("Orbit reminder")
                .setContentText(item.message)
                .setStyle(new Notification.BigTextStyle().bigText(item.message))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_HIGH);
        nm.notify(item.id.hashCode() & 0x7fffffff, b.build());
    }

    public static void openSettings(Context c) {
        if (c == null) return;
        ensureChannel(c);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33 &&
                c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        } else if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL);
        } else {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
        }
        if (!(c instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(intent); }
        catch (Exception ignored) {}
    }

    /** Declared at start-up; still asserted here so the importance check reads a real channel. */
    private static void ensureChannel(Context c) {
        OrbitNotificationChannels.ensure(c, CHANNEL);
    }
}

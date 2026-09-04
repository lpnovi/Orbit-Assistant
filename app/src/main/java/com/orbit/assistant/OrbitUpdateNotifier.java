package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** One notification per available Orbit version. */
public final class OrbitUpdateNotifier {
    private static final String CHANNEL = OrbitNotificationChannels.UPDATES;
    private static final int NOTIFICATION_ID = 0x4f524255;

    private OrbitUpdateNotifier() {}

    public static boolean show(Context context, OrbitUpdater.Release release) {
        if (release == null) return false;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        // Start-up has normally already declared this. Kept so the channel is guaranteed to exist
        // in whatever process reaches this, and so the importance check below reads a real channel.
        OrbitNotificationChannels.ensure(context, CHANNEL);
        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(CHANNEL);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }

        // Chats underneath, so Back from the update screen returns into Orbit rather than ending
        // the task at the launcher, and the swipe gesture has a real page to reveal.
        Intent open = new Intent(context, UpdateActivity.class);
        PendingIntent pending = PendingIntent.getActivities(context, NOTIFICATION_ID,
                OrbitNavigation.stackFor(context, open),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // A Beta build is named so the user can tell at a glance what they are being offered. A
        // Stable release reaching a Beta-channel user is still an ordinary Orbit update and is
        // announced as one.
        boolean beta = release.isBeta();
        String title = beta ? "Orbit Beta update available" : "Orbit update available";
        String text = beta
                ? "Orbit Assistant " + release.displayName() + " is available"
                : "Orbit Assistant v" + release.versionName + " is available";
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_orbit)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS);
        manager.notify(NOTIFICATION_ID, builder.build());
        return true;
    }

    public static void cancel(Context context) {
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

}

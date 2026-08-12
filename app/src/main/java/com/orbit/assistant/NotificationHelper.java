package com.orbit.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Optional, quiet notifications for background Orbit completions. */
public final class NotificationHelper {
    private static final String CHANNEL = "orbit_background_responses";
    private NotificationHelper() {}

    public static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Background responses", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Notifies you when an Orbit response finishes after you leave the chat.");
        nm.createNotificationChannel(channel);
    }

    public static void notifyResponseComplete(Context c, String conversationId, String prompt, String response) {
        if (!Prefs.backgroundNotifications(c)) return;
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Intent home = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Intent open = new Intent(c, ChatActivity.class).putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        int requestCode = Math.abs((conversationId == null ? "orbit" : conversationId).hashCode());
        PendingIntent pi = PendingIntent.getActivities(c, requestCode, new Intent[]{home, open},
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = compact(prompt, 52);
        if (title.isEmpty()) title = "Orbit finished responding";
        String body = compact(response, 110);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(c, CHANNEL)
                : new Notification.Builder(c);
        b.setSmallIcon(com.orbit.assistant.R.drawable.ic_orbit)
                .setContentTitle("Orbit finished: " + title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE);
        nm.notify(requestCode, b.build());
    }

    private static String compact(String s, int max) {
        String value = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 1)).trim() + "…";
    }
}

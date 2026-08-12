package com.orbit.assistant;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class OrbitNotificationListenerService extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) capture(sbn);
            }
        } catch (Exception ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        capture(sbn);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        NotificationStore.markRemoved(this, sbn.getKey(), System.currentTimeMillis());
    }

    private void capture(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        String pkg = safe(sbn.getPackageName());
        if (pkg.isEmpty() || getPackageName().equals(pkg)) return;

        String label = appLabel(pkg);
        NotificationStore.rememberKnownApp(this, pkg, label);
        if (NotificationStore.isBlocked(this, pkg)) return;

        Notification n = sbn.getNotification();
        Bundle e = n.extras == null ? new Bundle() : n.extras;

        String title = text(e.getCharSequence(Notification.EXTRA_TITLE));
        String body = text(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = text(e.getCharSequence(Notification.EXTRA_TEXT));
        String sub = text(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String conversation = text(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));

        // Ignore visually empty service/status notifications. Orbit still records
        // the app identity so the user can exclude it later if desired.
        if (title.isEmpty() && body.isEmpty() && sub.isEmpty() && conversation.isEmpty()) return;

        NotificationStore.upsert(this, new NotificationStore.Item(
                sbn.getKey(), pkg, label, title, body, sub, conversation,
                sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis(),
                0L, sbn.isOngoing()));
    }

    private String appLabel(String pkg) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null ? pkg : label.toString();
        } catch (Exception ignored) {
            return pkg;
        }
    }

    private static String text(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}

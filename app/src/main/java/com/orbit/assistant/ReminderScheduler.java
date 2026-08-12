package com.orbit.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** AlarmManager-backed scheduler for one-time Orbit reminders. */
public final class ReminderScheduler {
    public static final class ScheduleResult {
        public final boolean scheduled;
        public final boolean exact;
        public final String message;

        ScheduleResult(boolean scheduled, boolean exact, String message) {
            this.scheduled = scheduled;
            this.exact = exact;
            this.message = message == null ? "" : message;
        }
    }

    private ReminderScheduler() {}

    public static synchronized ScheduleResult schedule(Context c, ReminderStore.Item item) {
        if (c == null || item == null) return new ScheduleResult(false, false, "Invalid reminder");
        if (item.triggerAt <= System.currentTimeMillis() + 500L) {
            return new ScheduleResult(false, false, "Reminder time must be in the future");
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return new ScheduleResult(false, false, "Alarm service unavailable");
        cancelAlarm(c, item.id);
        PendingIntent pi = pending(c, item.id, PendingIntent.FLAG_UPDATE_CURRENT);
        boolean exact = RoutineTriggerScheduler.canScheduleExact(c);
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi);
            ReminderStore.upsert(c, item);
            return new ScheduleResult(true, exact, exact ? "precise" : "approximate");
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, item.triggerAt, pi);
                ReminderStore.upsert(c, item);
                return new ScheduleResult(true, false, "approximate");
            } catch (Exception ignored) {
                return new ScheduleResult(false, false, "Could not schedule reminder");
            }
        } catch (Exception e) {
            return new ScheduleResult(false, false, "Could not schedule reminder");
        }
    }

    public static synchronized void cancel(Context c, String id) {
        if (c == null || id == null || id.trim().isEmpty()) return;
        cancelAlarm(c, id);
        ReminderStore.remove(c, id);
    }

    /** Cancels only the platform alarm; used while an atomic restore swaps stores. */
    static synchronized void cancelScheduled(Context c, String id) {
        cancelAlarm(c, id);
    }

    private static void cancelAlarm(Context c, String id) {
        if (c == null || id == null || id.trim().isEmpty()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            PendingIntent existing = pending(c, id, PendingIntent.FLAG_NO_CREATE);
            if (existing != null) am.cancel(existing);
        } catch (Exception ignored) {}
    }

    public static synchronized void rescheduleAll(Context c) {
        if (c == null) return;
        long now = System.currentTimeMillis();
        for (ReminderStore.Item item : ReminderStore.list(c)) {
            if (item.triggerAt <= now) {
                ReminderStore.remove(c, item.id);
                continue;
            }
            schedule(c, item);
        }
    }

    private static PendingIntent pending(Context c, String id, int extraFlag) {
        Intent i = new Intent(c, ReminderReceiver.class)
                .setAction("com.orbit.assistant.FIRE_REMINDER")
                .setData(Uri.parse("orbit://reminder/" + Uri.encode(id)))
                .putExtra(ReminderReceiver.EXTRA_REMINDER_ID, id);
        int requestCode = id.hashCode() & 0x7fffffff;
        return PendingIntent.getBroadcast(c, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | extraFlag);
    }
}

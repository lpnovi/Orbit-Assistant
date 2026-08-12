package com.orbit.assistant;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.util.List;

/** Restores and manages automatic Routine triggers, including AlarmManager time schedules. */
public final class RoutineTriggerScheduler {
    private RoutineTriggerScheduler() {}

    public static boolean canScheduleExact(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static void openExactAlarmAccess(Context c) {
        if (c == null || Build.VERSION.SDK_INT < 31) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    public static synchronized long schedule(Context c, RoutineTriggerStore.Trigger trigger) {
        if (c == null || trigger == null) return 0L;
        if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
            RoutineLocationTriggerScheduler.schedule(c, trigger);
            return 0L;
        }
        cancel(c, trigger.id);
        if (!trigger.enabled) {
            RoutineTriggerStore.upsert(c, trigger.withNextRun(0L));
            return 0L;
        }
        long next = RoutineTriggerSchedule.nextRun(trigger, System.currentTimeMillis() + 500L);
        if (next <= 0L) {
            boolean keepEnabled = !RoutineTriggerStore.MODE_ONCE.equals(trigger.mode);
            RoutineTriggerStore.updateRunState(c, trigger.id, trigger.lastRunAt, 0L,
                    trigger.lastResult, keepEnabled);
            return 0L;
        }
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            RoutineTriggerStore.upsert(c, trigger.withNextRun(0L));
            return 0L;
        }
        PendingIntent pi = pending(c, trigger.id, next, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (canScheduleExact(c)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                // Safe fallback: the trigger still runs, but Android may batch it slightly.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
            RoutineTriggerStore.upsert(c, trigger.withNextRun(next));
            return next;
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
                RoutineTriggerStore.upsert(c, trigger.withNextRun(next));
                return next;
            } catch (Exception ignored) {
                RoutineTriggerStore.upsert(c, trigger.withNextRun(0L));
                return 0L;
            }
        } catch (Exception e) {
            RoutineTriggerStore.upsert(c, trigger.withNextRun(0L));
            return 0L;
        }
    }

    public static synchronized void rescheduleAll(Context c) {
        if (c == null) return;
        List<RoutineTriggerStore.Trigger> triggers = RoutineTriggerStore.list(c);
        for (RoutineTriggerStore.Trigger trigger : triggers) {
            if (!trigger.enabled) {
                cancel(c, trigger.id);
            } else if (RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) {
                RoutineLocationTriggerScheduler.schedule(c, trigger);
            } else {
                schedule(c, trigger);
            }
        }
    }

    public static synchronized void cancel(Context c, String triggerId) {
        if (c == null || triggerId == null || triggerId.trim().isEmpty()) return;
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try {
                PendingIntent existing = pending(c, triggerId, 0L, PendingIntent.FLAG_NO_CREATE);
                if (existing != null) am.cancel(existing);
            } catch (Exception ignored) {}
        }
        RoutineLocationTriggerScheduler.cancel(c, triggerId);
    }

    public static synchronized void cancelForRoutine(Context c, String routineId) {
        if (c == null || routineId == null) return;
        for (RoutineTriggerStore.Trigger t : RoutineTriggerStore.listForRoutine(c, routineId)) {
            cancel(c, t.id);
        }
    }

    private static PendingIntent pending(Context c, String triggerId, long scheduledAt, int extraFlag) {
        Intent i = new Intent(c, RoutineTriggerReceiver.class)
                .setAction("com.orbit.assistant.RUN_ROUTINE_TRIGGER")
                .setData(Uri.parse("orbit://routine-trigger/" + Uri.encode(triggerId)))
                .putExtra(RoutineTriggerReceiver.EXTRA_TRIGGER_ID, triggerId)
                .putExtra(RoutineTriggerReceiver.EXTRA_SCHEDULED_AT, scheduledAt);
        int requestCode = triggerId.hashCode() & 0x7fffffff;
        int flags = PendingIntent.FLAG_IMMUTABLE | extraFlag;
        return PendingIntent.getBroadcast(c, requestCode, i, flags);
    }
}

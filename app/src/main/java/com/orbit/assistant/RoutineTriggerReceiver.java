package com.orbit.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** AlarmManager entry point for an automatic routine trigger. */
public class RoutineTriggerReceiver extends BroadcastReceiver {
    public static final String EXTRA_TRIGGER_ID = "trigger_id";
    public static final String EXTRA_SCHEDULED_AT = "scheduled_at";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String id = intent.getStringExtra(EXTRA_TRIGGER_ID);
        long scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L);
        RoutineTriggerStore.Trigger trigger = RoutineTriggerStore.findById(context, id);
        if (trigger == null || !trigger.enabled || !RoutineTriggerStore.TYPE_TIME.equals(trigger.type)) return;

        // Ignore a stale PendingIntent left over from a schedule edit/cancel race.
        if (scheduledAt > 0 && trigger.nextRunAt > 0 && Math.abs(scheduledAt - trigger.nextRunAt) > 2_000L) return;

        RoutineStore.Routine routine = RoutineStore.findById(context, trigger.routineId);
        if (routine == null) {
            RoutineTriggerScheduler.cancel(context, trigger.id);
            RoutineTriggerStore.delete(context, trigger.id);
            return;
        }

        // Always establish the next recurrence before executing the routine, so a
        // process kill or action failure cannot accidentally lose future runs.
        if (RoutineTriggerStore.MODE_ONCE.equals(trigger.mode)) {
            RoutineTriggerStore.upsert(context, trigger.withEnabled(false).withNextRun(0L));
        } else {
            RoutineTriggerScheduler.schedule(context, trigger.withNextRun(0L));
        }
        PendingResult pending = goAsync();
        RoutineTriggerExecution.execute(context, routine,
                RoutineTriggerStore.findById(context, trigger.id), pending::finish);
    }
}

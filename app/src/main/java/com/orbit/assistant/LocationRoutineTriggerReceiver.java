package com.orbit.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;

/** Receives platform proximity entry/exit events and runs matching saved Routines. */
public final class LocationRoutineTriggerReceiver extends BroadcastReceiver {
    public static final String EXTRA_TRIGGER_ID = "trigger_id";
    private static final long RETRIGGER_GUARD_MS = 120_000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String id = intent.getStringExtra(EXTRA_TRIGGER_ID);
        RoutineTriggerStore.Trigger trigger = RoutineTriggerStore.findById(context, id);
        if (trigger == null || !trigger.enabled || !RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) return;

        if (!intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return;
        boolean entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        boolean expectedEntering = RoutineTriggerStore.LOCATION_ENTER.equals(trigger.locationTransition);
        if (entering != expectedEntering) return;

        long now = System.currentTimeMillis();
        if (trigger.lastRunAt > 0L && now - trigger.lastRunAt < RETRIGGER_GUARD_MS) return;

        RoutineStore.Routine routine = RoutineStore.findById(context, trigger.routineId);
        if (routine == null) {
            RoutineLocationTriggerScheduler.cancel(context, trigger.id);
            RoutineTriggerStore.delete(context, trigger.id);
            return;
        }

        // Stamp the transition before the action chain starts so a noisy boundary cannot
        // enqueue the same location event twice while the first run is still executing.
        RoutineTriggerStore.updateRunState(context, trigger.id, now, 0L, "Triggered · running", true);
        RoutineTriggerExecution.execute(context, routine, RoutineTriggerStore.findById(context, trigger.id));
    }
}

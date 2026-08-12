package com.orbit.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores automatic Routine triggers and reminders after relevant Android state changes. */
public class RoutineTriggerRescheduleReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        RoutineTriggerScheduler.rescheduleAll(context);
        ReminderScheduler.rescheduleAll(context);
    }
}

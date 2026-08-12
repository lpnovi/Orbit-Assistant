package com.orbit.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a stored one-time Orbit reminder. */
public final class ReminderReceiver extends BroadcastReceiver {
    public static final String EXTRA_REMINDER_ID = "reminder_id";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String id = intent.getStringExtra(EXTRA_REMINDER_ID);
        ReminderStore.Item item = ReminderStore.get(context, id);
        if (item == null) return;
        ReminderStore.remove(context, item.id);
        ReminderNotifier.show(context, item);
    }
}

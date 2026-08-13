package com.orbit.assistant;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Explicit, non-exported entry point for validated headless widget actions. */
public final class OrbitWidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION_RUN_ROUTINE = "com.orbit.assistant.widget.RUN_ROUTINE";
    static final String ACTION_TOGGLE_FLASHLIGHT = "com.orbit.assistant.widget.TOGGLE_FLASHLIGHT";
    static final String EXTRA_WIDGET_ID = "widget_id";
    static final String EXTRA_SLOT = "slot";

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        int widgetId = intent.getIntExtra(EXTRA_WIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID);
        int slot = intent.getIntExtra(EXTRA_SLOT, -2);
        Context app = context.getApplicationContext();

        if (ACTION_RUN_ROUTINE.equals(action)) {
            if (!OrbitWidgets.isConfiguredAction(app, widgetId, slot, OrbitWidgets.TYPE_ROUTINE)) return;
            String routineId = OrbitWidgets.configuredRoutineId(app, widgetId, slot);
            RoutineStore.Routine routine = RoutineStore.findById(app, routineId);
            if (routine == null) {
                OrbitWidgets.updateAll(app);
                app.startActivity(OrbitWidgets.configurationIntent(app, widgetId));
                return;
            }
            PendingResult pending = goAsync();
            OrbitWidgetExecutor.runRoutine(app, routine, pending::finish);
            return;
        }

        if (ACTION_TOGGLE_FLASHLIGHT.equals(action)) {
            if (!OrbitWidgets.isConfiguredAction(app, widgetId, slot,
                    OrbitWidgets.TYPE_FLASHLIGHT)) return;
            if (!OrbitWidgetExecutor.hasCameraPermission(app)) {
                app.startActivity(OrbitWidgetActionActivity.permissionIntent(app));
                return;
            }
            PendingResult pending = goAsync();
            OrbitWidgetExecutor.toggleFlashlight(app, result -> {
                Toast.makeText(app, result == null
                                ? "Flashlight action did not finish." : result.message,
                        Toast.LENGTH_SHORT).show();
                pending.finish();
            });
        }
    }
}

package com.orbit.assistant;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Configurable launcher entry into one local saved Routine. */
public final class RunRoutineWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) OrbitWidgets.updateRunRoutine(context, manager, id);
    }

    @Override public void onDeleted(Context context, int[] widgetIds) {
        for (int id : widgetIds) OrbitWidgets.deleteWidget(context, id);
    }
}

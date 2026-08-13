package com.orbit.assistant;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Compact launcher entry into Orbit's normal full chat. */
public final class AskOrbitWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) OrbitWidgets.updateAsk(context, manager, id);
    }
}

package com.orbit.assistant;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** Configurable two-or-four-slot launcher surface for bounded Orbit actions. */
public final class QuickActionsWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        for (int id : widgetIds) OrbitWidgets.updateQuickActions(context, manager, id);
    }

    @Override public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                                    int widgetId, Bundle newOptions) {
        OrbitWidgets.updateQuickActions(context, manager, widgetId);
    }

    @Override public void onDeleted(Context context, int[] widgetIds) {
        for (int id : widgetIds) OrbitWidgets.deleteWidget(context, id);
    }
}

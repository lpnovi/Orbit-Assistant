package com.orbit.assistant;

import android.app.Activity;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.Toast;

/** Shared state, rendering, and safe launch helpers for Orbit home-screen widgets. */
public final class OrbitWidgets {
    static final String TYPE_NONE = "none";
    static final String TYPE_ASK = "ask";
    static final String TYPE_ROUTINE = "routine";
    static final String TYPE_FLASHLIGHT = "flashlight";
    static final String TYPE_REMINDER = "reminder";

    private static final String FILE = "orbit_widgets";
    private static final String RUN_ROUTINE_PREFIX = "run_routine_";
    private static final String QUICK_TYPE_PREFIX = "quick_type_";
    private static final String QUICK_ROUTINE_PREFIX = "quick_routine_";
    private static final int QUICK_SLOTS = 4;

    private OrbitWidgets() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean saveRunRoutine(Context context, int widgetId, String routineId) {
        return prefs(context).edit().putString(RUN_ROUTINE_PREFIX + widgetId,
                clean(routineId)).commit();
    }

    static String runRoutineId(Context context, int widgetId) {
        return prefs(context).getString(RUN_ROUTINE_PREFIX + widgetId, "").trim();
    }

    static boolean saveQuickSlots(Context context, int widgetId, String[] types,
                                  String[] routineIds) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (int slot = 0; slot < QUICK_SLOTS; slot++) {
            String type = types != null && slot < types.length ? normalizeType(types[slot]) : TYPE_NONE;
            String routineId = routineIds != null && slot < routineIds.length
                    ? clean(routineIds[slot]) : "";
            editor.putString(quickTypeKey(widgetId, slot), type);
            editor.putString(quickRoutineKey(widgetId, slot), routineId);
        }
        return editor.commit();
    }

    static String quickType(Context context, int widgetId, int slot) {
        String fallback = slot == 0 ? TYPE_ASK : slot == 1 ? TYPE_FLASHLIGHT
                : slot == 2 ? TYPE_REMINDER : TYPE_NONE;
        return normalizeType(prefs(context).getString(quickTypeKey(widgetId, slot), fallback));
    }

    static String quickRoutineId(Context context, int widgetId, int slot) {
        return prefs(context).getString(quickRoutineKey(widgetId, slot), "").trim();
    }

    static void deleteWidget(Context context, int widgetId) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .remove(RUN_ROUTINE_PREFIX + widgetId);
        for (int slot = 0; slot < QUICK_SLOTS; slot++) {
            editor.remove(quickTypeKey(widgetId, slot));
            editor.remove(quickRoutineKey(widgetId, slot));
        }
        editor.apply();
    }

    public static void updateAll(Context context) {
        if (context == null) return;
        Context app = context.getApplicationContext();
        updateProvider(app, AskOrbitWidgetProvider.class);
        updateProvider(app, RunRoutineWidgetProvider.class);
        updateProvider(app, QuickActionsWidgetProvider.class);
    }

    static void updateAsk(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_ask_orbit);
        applyTheme(context, views, R.id.widget_ask_card);
        tintImage(views, R.id.widget_ask_icon, UiKit.accent(context));
        views.setTextColor(R.id.widget_ask_label, UiKit.TEXT);
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true);
        views.setOnClickPendingIntent(R.id.widget_ask_card,
                activityPendingIntent(context, intent, widgetId, 0, TYPE_ASK));
        views.setContentDescription(R.id.widget_ask_card, "Ask Orbit. Opens a new Orbit chat.");
        manager.updateAppWidget(widgetId, views);
    }

    static void updateRunRoutine(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_run_routine);
        applyTheme(context, views, R.id.widget_routine_card);
        tintImage(views, R.id.widget_routine_icon, UiKit.accent(context));
        views.setTextColor(R.id.widget_routine_name, UiKit.TEXT);
        views.setTextColor(R.id.widget_routine_hint, UiKit.MUTED);

        String routineId = runRoutineId(context, widgetId);
        RoutineStore.Routine routine = RoutineStore.findById(context, routineId);
        PendingIntent pending;
        if (routine == null) {
            views.setTextViewText(R.id.widget_routine_name, "Routine unavailable");
            views.setTextViewText(R.id.widget_routine_hint, "Tap to choose another");
            views.setContentDescription(R.id.widget_routine_card,
                    "Routine unavailable. Tap to reconfigure this Orbit widget.");
            pending = configurationPendingIntent(context, widgetId, 0);
        } else {
            views.setTextViewText(R.id.widget_routine_name, routine.name);
            views.setTextViewText(R.id.widget_routine_hint, "Tap to run");
            views.setContentDescription(R.id.widget_routine_card, "Run " + routine.name);
            pending = actionPendingIntent(context,
                    OrbitWidgetActionReceiver.ACTION_RUN_ROUTINE, widgetId, -1);
        }
        views.setOnClickPendingIntent(R.id.widget_routine_card, pending);
        manager.updateAppWidget(widgetId, views);
    }

    static void updateQuickActions(Context context, AppWidgetManager manager, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_quick_actions);
        applyTheme(context, views, R.id.widget_quick_card);
        views.setTextColor(R.id.widget_quick_title, UiKit.TEXT);
        int accent = UiKit.accent(context);
        int[] containers = {R.id.widget_quick_slot_1, R.id.widget_quick_slot_2,
                R.id.widget_quick_slot_3, R.id.widget_quick_slot_4};
        int[] icons = {R.id.widget_quick_icon_1, R.id.widget_quick_icon_2,
                R.id.widget_quick_icon_3, R.id.widget_quick_icon_4};
        int[] labels = {R.id.widget_quick_label_1, R.id.widget_quick_label_2,
                R.id.widget_quick_label_3, R.id.widget_quick_label_4};

        int visibleSlots = visibleQuickSlots(manager.getAppWidgetOptions(widgetId));
        views.setViewVisibility(R.id.widget_quick_second_row,
                visibleSlots > 2 ? View.VISIBLE : View.GONE);
        for (int slot = 0; slot < QUICK_SLOTS; slot++) {
            String type = quickType(context, widgetId, slot);
            Slot presentation = slotPresentation(context, widgetId, slot, type);
            boolean visible = slot < visibleSlots && !TYPE_NONE.equals(type);
            views.setViewVisibility(containers[slot], visible ? View.VISIBLE : View.GONE);
            if (!visible) continue;
            views.setImageViewResource(icons[slot], presentation.icon);
            tintImage(views, icons[slot], accent);
            views.setTextViewText(labels[slot], presentation.label);
            views.setTextColor(labels[slot], UiKit.TEXT);
            views.setContentDescription(containers[slot], presentation.description);
            views.setOnClickPendingIntent(containers[slot], presentation.pendingIntent);
        }
        views.setContentDescription(R.id.widget_quick_card, "Orbit Quick Actions");
        manager.updateAppWidget(widgetId, views);
    }

    private static Slot slotPresentation(Context context, int widgetId, int slot, String type) {
        if (TYPE_ROUTINE.equals(type)) {
            String routineId = quickRoutineId(context, widgetId, slot);
            RoutineStore.Routine routine = RoutineStore.findById(context, routineId);
            if (routine == null) {
                return new Slot("Unavailable", R.drawable.ic_routine_tile,
                        "Routine unavailable. Tap to configure Quick Actions.",
                        configurationPendingIntent(context, widgetId, slot + 1));
            }
            return new Slot(routine.name, R.drawable.ic_routine_tile, "Run " + routine.name,
                    actionPendingIntent(context, OrbitWidgetActionReceiver.ACTION_RUN_ROUTINE,
                            widgetId, slot));
        }
        if (TYPE_FLASHLIGHT.equals(type)) {
            return new Slot("Flashlight", R.drawable.ic_widget_flashlight,
                    "Toggle flashlight", actionPendingIntent(context,
                    OrbitWidgetActionReceiver.ACTION_TOGGLE_FLASHLIGHT, widgetId, slot));
        }
        if (TYPE_REMINDER.equals(type)) {
            Intent intent = new Intent(context, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true)
                    .putExtra(ChatActivity.EXTRA_INITIAL_DRAFT, "Remind me ");
            return new Slot("Reminder", R.drawable.ic_widget_reminder,
                    "Create an Orbit reminder", activityPendingIntent(context, intent, widgetId, slot, type));
        }
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true);
        return new Slot("Ask Orbit", R.drawable.ic_orbit_tile, "Ask Orbit",
                activityPendingIntent(context, intent, widgetId, slot, TYPE_ASK));
    }

    public static void requestPin(Activity activity, Class<?> providerClass, String label) {
        if (activity == null) return;
        AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        if (Build.VERSION.SDK_INT < 26 || !manager.isRequestPinAppWidgetSupported()) {
            Toast.makeText(activity,
                    "Open your launcher's widget picker, then choose Orbit.", Toast.LENGTH_LONG).show();
            return;
        }
        boolean requested = manager.requestPinAppWidget(
                new ComponentName(activity, providerClass), null, null);
        if (!requested) {
            Toast.makeText(activity,
                    "Open your launcher's widget picker, then choose Orbit.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, "Choose where to place the " + label + " widget.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    static boolean isOwnedConfiguration(Context context, int widgetId) {
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId);
        if (info == null || info.provider == null ||
                !context.getPackageName().equals(info.provider.getPackageName())) return false;
        String className = info.provider.getClassName();
        return RunRoutineWidgetProvider.class.getName().equals(className) ||
                QuickActionsWidgetProvider.class.getName().equals(className);
    }

    static boolean isRunRoutineWidget(Context context, int widgetId) {
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId);
        return info != null && info.provider != null &&
                RunRoutineWidgetProvider.class.getName().equals(info.provider.getClassName());
    }

    static boolean isConfiguredAction(Context context, int widgetId, int slot, String type) {
        if (context == null || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false;
        AppWidgetProviderInfo info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId);
        if (info == null || info.provider == null ||
                !context.getPackageName().equals(info.provider.getPackageName())) return false;
        String provider = info.provider.getClassName();
        if (RunRoutineWidgetProvider.class.getName().equals(provider)) {
            return slot == -1 && TYPE_ROUTINE.equals(type) && !runRoutineId(context, widgetId).isEmpty();
        }
        return QuickActionsWidgetProvider.class.getName().equals(provider) &&
                slot >= 0 && slot < QUICK_SLOTS && type.equals(quickType(context, widgetId, slot));
    }

    static String configuredRoutineId(Context context, int widgetId, int slot) {
        if (!isConfiguredAction(context, widgetId, slot, TYPE_ROUTINE)) return "";
        return slot == -1 ? runRoutineId(context, widgetId) : quickRoutineId(context, widgetId, slot);
    }

    private static PendingIntent activityPendingIntent(Context context, Intent intent, int widgetId,
                                                       int slot, String action) {
        intent.setPackage(context.getPackageName());
        intent.setData(Uri.parse("orbit://widget/" + widgetId + "/" + slot + "/" + action));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        int code = requestCode(widgetId, slot, action);
        boolean conversation = intent.getComponent() != null
                && ChatActivity.class.getName().equals(intent.getComponent().getClassName());
        if (!conversation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return PendingIntent.getActivity(context, code, intent, flags);
        }
        // Chats underneath the conversation, not the launcher. A widget used to drop a chat in as
        // the root of a fresh task, so Back left Orbit entirely and the conversation's own back
        // gesture had nothing of Orbit's to reveal behind it. Only conversations get the extra
        // screen: the widget configuration screen is its own destination and keeps its own task.
        return PendingIntent.getActivities(context, code, ChatActivity.stackFor(context, intent), flags);
    }

    private static PendingIntent configurationPendingIntent(Context context, int widgetId, int slot) {
        Intent intent = configurationIntent(context, widgetId);
        return activityPendingIntent(context, intent, widgetId, slot, "configure");
    }

    static Intent configurationIntent(Context context, int widgetId) {
        return new Intent(context, OrbitWidgetConfigureActivity.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    private static PendingIntent actionPendingIntent(Context context, String action,
                                                     int widgetId, int slot) {
        Intent intent = new Intent(context, OrbitWidgetActionReceiver.class)
                .setAction(action)
                .setPackage(context.getPackageName())
                .putExtra(OrbitWidgetActionReceiver.EXTRA_WIDGET_ID, widgetId)
                .putExtra(OrbitWidgetActionReceiver.EXTRA_SLOT, slot)
                .setData(Uri.parse("orbit://widget/" + widgetId + "/" + slot + "/" + action));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, requestCode(widgetId, slot, action),
                intent, flags);
    }

    private static void updateProvider(Context context, Class<?> provider) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        for (int id : ids) {
            if (provider == AskOrbitWidgetProvider.class) updateAsk(context, manager, id);
            else if (provider == RunRoutineWidgetProvider.class) updateRunRoutine(context, manager, id);
            else updateQuickActions(context, manager, id);
        }
    }

    private static void applyTheme(Context context, RemoteViews views, int cardId) {
        UiKit.syncTheme(context);
        views.setInt(cardId, "setBackgroundResource", Prefs.amoledMode(context)
                ? R.drawable.widget_surface_amoled : R.drawable.widget_surface);
    }

    private static void tintImage(RemoteViews views, int viewId, int color) {
        if (Build.VERSION.SDK_INT >= 31) {
            views.setColorStateList(viewId, "setImageTintList", ColorStateList.valueOf(color));
        } else {
            views.setInt(viewId, "setColorFilter", color);
        }
    }

    private static int visibleQuickSlots(Bundle options) {
        if (options == null) return 4;
        int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 120);
        return minHeight < 100 ? 2 : 4;
    }

    private static int requestCode(int widgetId, int slot, String action) {
        return 690000 + Math.abs((widgetId + ":" + slot + ":" + action).hashCode() % 2000000000);
    }

    private static String quickTypeKey(int widgetId, int slot) {
        return QUICK_TYPE_PREFIX + widgetId + "_" + slot;
    }

    private static String quickRoutineKey(int widgetId, int slot) {
        return QUICK_ROUTINE_PREFIX + widgetId + "_" + slot;
    }

    private static String normalizeType(String type) {
        if (TYPE_ASK.equals(type) || TYPE_ROUTINE.equals(type) || TYPE_FLASHLIGHT.equals(type) ||
                TYPE_REMINDER.equals(type) || TYPE_NONE.equals(type)) return type;
        return TYPE_NONE;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class Slot {
        final String label;
        final int icon;
        final String description;
        final PendingIntent pendingIntent;

        Slot(String label, int icon, String description, PendingIntent pendingIntent) {
            this.label = label;
            this.icon = icon;
            this.description = description;
            this.pendingIntent = pendingIntent;
        }
    }
}

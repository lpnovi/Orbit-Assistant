package com.orbit.assistant;

import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Shared, user-consented Quick Settings tile setup and launch helpers. */
public final class QuickSettingsTiles {
    public interface AddTileCallback {
        void onResult(boolean addedOrAlreadyPresent);
    }

    private QuickSettingsTiles() {}

    public static RoutineStore.Routine assignedRoutine(Context c) {
        String id = Prefs.quickSettingsRoutineId(c);
        if (id.isEmpty()) return null;
        RoutineStore.Routine routine = RoutineStore.findById(c, id);
        if (routine == null) Prefs.setQuickSettingsRoutineId(c, "");
        return routine;
    }

    public static void refreshRoutineTile(Context c) {
        if (c == null) return;
        try {
            TileService.requestListeningState(c,
                    new ComponentName(c, OrbitRoutineTileService.class));
        } catch (Exception ignored) {}
    }

    public static void requestAddAskTile(android.app.Activity activity) {
        requestAddAskTile(activity, null);
    }

    public static void requestAddAskTile(android.app.Activity activity, AddTileCallback callback) {
        requestAddTile(activity, AskOrbitTileService.class, "Orbit", R.drawable.ic_orbit_tile,
                callback);
    }

    public static void requestAddRoutineTile(android.app.Activity activity) {
        requestAddTile(activity, OrbitRoutineTileService.class,
                "Orbit Routine", R.drawable.ic_routine_tile, null);
    }

    private static void requestAddTile(android.app.Activity activity,
                                       Class<? extends TileService> service,
                                       String label, int iconResource,
                                       AddTileCallback callback) {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(activity,
                    "Open Android Quick Settings, tap Edit, then add the " + label + " tile.",
                    Toast.LENGTH_LONG).show();
            if (callback != null) callback.onResult(false);
            return;
        }
        StatusBarManager manager = activity.getSystemService(StatusBarManager.class);
        if (manager == null) {
            Toast.makeText(activity, "Android's Quick Settings editor is unavailable.",
                    Toast.LENGTH_LONG).show();
            if (callback != null) callback.onResult(false);
            return;
        }
        manager.requestAddTileService(
                new ComponentName(activity, service), label,
                Icon.createWithResource(activity, iconResource), activity.getMainExecutor(),
                result -> {
                    if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                        Toast.makeText(activity, label + " added to Quick Settings.",
                                Toast.LENGTH_SHORT).show();
                    } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                        Toast.makeText(activity, label + " is already in Quick Settings.",
                                Toast.LENGTH_SHORT).show();
                    } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED) {
                        Toast.makeText(activity,
                                "Open Android Quick Settings, tap Edit, then add the " + label + " tile.",
                                Toast.LENGTH_LONG).show();
                    }
                    if (callback != null) callback.onResult(
                            result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED);
                });
    }

    static PendingIntent activityPendingIntent(Context c, Intent intent, int requestCode) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}

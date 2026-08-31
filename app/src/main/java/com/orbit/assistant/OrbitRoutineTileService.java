package com.orbit.assistant;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings entry point for one user-selected saved Routine. */
public final class OrbitRoutineTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        RoutineStore.Routine routine = QuickSettingsTiles.assignedRoutine(this);
        tile.setLabel("Orbit Routine");
        tile.setState(routine == null ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(routine == null ? "Choose a Routine" : routine.name);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            tile.setStateDescription(routine == null ? "No Routine assigned" : "Run " + routine.name);
        }
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        Runnable open = this::openRoutine;
        if (isLocked()) unlockAndRun(open);
        else open.run();
    }

    private void openRoutine() {
        RoutineStore.Routine routine = QuickSettingsTiles.assignedRoutine(this);
        if (routine != null) {
            // Reuse the same headless-safe executor as Routine widgets. Actions that
            // genuinely need confirmation or setup still hand off to Orbit's runner.
            OrbitWidgetExecutor.runRoutine(this, routine, () -> {});
            return;
        }
        Intent intent = new Intent(this, RoutinesActivity.class);
        PendingIntent pending = QuickSettingsTiles.orbitPagePendingIntent(this, 6502, intent);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending);
        else startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

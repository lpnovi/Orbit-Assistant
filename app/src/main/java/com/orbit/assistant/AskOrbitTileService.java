package com.orbit.assistant;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.service.voice.VoiceInteractionService;

/** Quick Settings entry point for Orbit's existing assistant session. */
public final class AskOrbitTileService extends TileService {
    @Override public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean active = VoiceInteractionService.isActiveService(this,
                new ComponentName(this, OrbitVoiceInteractionService.class));
        tile.setLabel("Orbit");
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(active ? "Ask Orbit" : "Set up assistant");
        if (Build.VERSION.SDK_INT >= 30) {
            tile.setStateDescription(active ? "Ready" : "Orbit is not the active assistant");
        }
        tile.updateTile();
    }

    @Override public void onClick() {
        super.onClick();
        Runnable open = this::openOrbit;
        if (isLocked()) unlockAndRun(open);
        else open.run();
    }

    private void openOrbit() {
        if (OrbitVoiceInteractionService.showOrbitSession(this)) return;
        boolean active = VoiceInteractionService.isActiveService(this,
                new ComponentName(this, OrbitVoiceInteractionService.class));
        Intent intent = active
                ? new Intent(Intent.ACTION_ASSIST)
                : SettingsActivity.assistantSetupIntent(this);
        PendingIntent pending = QuickSettingsTiles.activityPendingIntent(this, intent, 6501);
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending);
        else startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}

package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/**
 * Orbit-owned invisible bridge whose only job is asking Android for Calendar permission.
 *
 * <p>It exists because the Side-button overlay is a {@code VoiceInteractionSession}, not an
 * Activity, and cannot call {@code requestPermissions} at all. Routing both surfaces through one
 * bridge means Calendar approval behaves identically in full chat and in the overlay, and means
 * neither surface has to keep its own permission plumbing.
 *
 * <p>The result reported back is Android's actual answer, read from the granted state after the
 * prompt closes. Opening the prompt is never treated as success.
 */
public final class CalendarPermissionActivity extends Activity {
    public static final String EXTRA_TOKEN = "orbit_calendar_permission_token";
    private static final int REQ_CALENDAR = 7401;

    private String token = "";
    private boolean requested;
    private boolean delivered;

    /** Opens the prompt for {@code token}, or returns false if no Activity could be started. */
    public static boolean start(Context context, String token) {
        if (context == null || token == null || token.isEmpty()) return false;
        try {
            Intent intent = new Intent(context, CalendarPermissionActivity.class)
                    .putExtra(EXTRA_TOKEN, token)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String supplied = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TOKEN);
        token = supplied == null ? "" : supplied;
        if (state != null) requested = state.getBoolean("requested", false);
        if (OrbitCalendarStore.hasAccess(this)) {
            finishWith(true);
            return;
        }
        if (!requested) {
            requested = true;
            try {
                requestPermissions(new String[]{
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR}, REQ_CALENDAR);
            } catch (Exception ignored) {
                finishWith(false);
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CALENDAR) return;
        // Both permissions, read from the real granted state rather than from the result array
        // alone, so a partial grant can never read as full Calendar access.
        finishWith(OrbitCalendarStore.hasAccess(this));
    }

    @Override public void onBackPressed() { finishWith(false); }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("requested", requested);
        super.onSaveInstanceState(out);
    }

    @Override protected void onDestroy() {
        // A dismissal Orbit never saw the result of is a denial, never a silent success.
        if (isFinishing()) deliver(OrbitCalendarStore.hasAccess(this));
        super.onDestroy();
    }

    private void finishWith(boolean granted) {
        deliver(granted);
        if (!isFinishing()) finishAndRemoveTask();
    }

    private void deliver(boolean granted) {
        if (delivered) return;
        delivered = true;
        CalendarAccessBridge.deliver(token, granted);
    }
}

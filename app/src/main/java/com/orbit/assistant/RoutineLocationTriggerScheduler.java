package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Platform-location monitoring for arrive/leave Routine triggers. */
public final class RoutineLocationTriggerScheduler {
    private RoutineLocationTriggerScheduler() {}

    public static boolean hasFineLocation(Context c) {
        return c != null && c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasBackgroundLocation(Context c) {
        if (c == null) return false;
        if (Build.VERSION.SDK_INT < 29) return hasFineLocation(c);
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }


    /** Best cached system location, shared by location triggers and Routine IF conditions. */
    public static Location bestLastKnownLocation(Context c) {
        if (c == null || !hasFineLocation(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        try {
            for (String provider : lm.getProviders(true)) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate == null) continue;
                if (best == null || candidate.getTime() > best.getTime() ||
                        (candidate.getTime() == best.getTime() && candidate.getAccuracy() < best.getAccuracy())) {
                    best = candidate;
                }
            }
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {}
        return best;
    }

    public static boolean isLocationEnabled(Context c) {
        if (c == null) return false;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    public static boolean ready(Context c) {
        return hasFineLocation(c) && hasBackgroundLocation(c) && isLocationEnabled(c);
    }

    /** Returns true only when the proximity monitor was successfully registered. */
    public static synchronized boolean schedule(Context c, RoutineTriggerStore.Trigger trigger) {
        if (c == null || trigger == null || !RoutineTriggerStore.TYPE_LOCATION.equals(trigger.type)) return false;
        cancel(c, trigger.id);
        if (!trigger.enabled || !ready(c)) return false;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        try {
            lm.addProximityAlert(trigger.latitude, trigger.longitude, trigger.radiusMeters, -1L,
                    pending(c, trigger.id, PendingIntent.FLAG_UPDATE_CURRENT));
            return true;
        } catch (SecurityException ignored) {
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized void cancel(Context c, String triggerId) {
        if (c == null || triggerId == null || triggerId.trim().isEmpty()) return;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            PendingIntent existing = pending(c, triggerId, PendingIntent.FLAG_NO_CREATE);
            if (existing != null) lm.removeProximityAlert(existing);
        } catch (SecurityException ignored) {
            // Revoking location access can make explicit removal unavailable. Android
            // also stops delivering location callbacks when the permission is gone.
        } catch (Exception ignored) {}
    }

    public static void openAppLocationSettings(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + c.getPackageName()));
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignoredAgain) {}
        }
    }

    public static void openLocationServices(Context c) {
        if (c == null) return;
        try {
            Intent i = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) {
            openAppLocationSettings(c);
        }
    }

    public static String backgroundPermissionLabel(Context c) {
        if (c != null && Build.VERSION.SDK_INT >= 30) {
            try {
                CharSequence label = c.getPackageManager().getBackgroundPermissionOptionLabel();
                if (label != null && label.length() > 0) return label.toString();
            } catch (Exception ignored) {}
        }
        return "Allow all the time";
    }

    private static PendingIntent pending(Context c, String triggerId, int extraFlag) {
        Intent i = new Intent(c, LocationRoutineTriggerReceiver.class)
                .setAction("com.orbit.assistant.RUN_LOCATION_ROUTINE_TRIGGER")
                .setData(Uri.parse("orbit://location-routine-trigger/" + Uri.encode(triggerId)))
                .putExtra(LocationRoutineTriggerReceiver.EXTRA_TRIGGER_ID, triggerId);
        int requestCode = triggerId.hashCode() & 0x7fffffff;
        int mutableFlag = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(c, requestCode, i, mutableFlag | extraFlag);
    }
}

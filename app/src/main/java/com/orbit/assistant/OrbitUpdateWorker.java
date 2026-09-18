package com.orbit.assistant;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Lightweight, network-constrained stable-release check. Never downloads an APK.
 *
 * <p>GitHub edition only: in the Google Play edition it is never scheduled and does nothing if run.
 */
public final class OrbitUpdateWorker extends Worker {
    private static final String UNIQUE_WORK = "orbit-stable-update-check";
    private static final long MIN_CHECK_SPACING_MS = 20L * 60L * 60L * 1000L;

    public OrbitUpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void schedule(Context context) {
        if (!OrbitDistribution.selfUpdates()) {
            // Google Play updates the Play edition. A check scheduled by a GitHub install that was
            // later replaced from Play survives in WorkManager, so it is removed rather than left.
            WorkManager.getInstance(context.getApplicationContext()).cancelUniqueWork(UNIQUE_WORK);
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                OrbitUpdateWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("orbit-updates")
                .build();
        WorkManager.getInstance(context.getApplicationContext()).enqueueUniquePeriodicWork(
                UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull @Override public Result doWork() {
        Context context = getApplicationContext();
        if (!OrbitDistribution.selfUpdates()) return Result.success();
        if (System.currentTimeMillis() - OrbitUpdater.lastCheckMs(context) < MIN_CHECK_SPACING_MS) {
            return Result.success();
        }
        try {
            OrbitUpdater.CheckResult result = OrbitUpdater.checkNow(context);
            if (result.updateAvailable && result.release != null &&
                    Prefs.updateNotifications(context) &&
                    !OrbitUpdater.wasNotified(context, result.release.versionCode) &&
                    OrbitUpdateNotifier.show(context, result.release)) {
                OrbitUpdater.markNotified(context, result.release.versionCode);
            }
        } catch (Exception ignored) {
            // A background update-service failure must never affect normal Orbit operation.
        }
        return Result.success();
    }
}

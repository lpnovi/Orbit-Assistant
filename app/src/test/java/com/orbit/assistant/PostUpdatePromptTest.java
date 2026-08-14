package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The one-time acknowledgement of a completed Orbit update. It is created only where the updater
 * has proved the running build reached the version it was installing, and consumed exactly once.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class PostUpdatePromptTest {
    private static final String PENDING_CODE = "orbit_update_pending_install_code";
    private static final String PENDING_FILE = "orbit_update_pending_install_file";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    /** Records a verified install that was aiming for the given version code. */
    private void pendingInstallTargeting(long versionCode) {
        Prefs.get(context).edit()
                .putLong(PENDING_CODE, versionCode)
                .putString(PENDING_FILE, "Orbit-Assistant-update.apk")
                .commit();
    }

    @Test public void aFreshInstallHasNothingToAcknowledge() {
        assertEquals("", OrbitUpdater.pendingPostUpdateVersion(context));
        OrbitUpdater.reconcilePendingInstall(context);
        assertEquals("a fresh install must never prompt", "",
                OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void anOrdinaryLaunchHasNothingToAcknowledge() {
        for (int i = 0; i < 3; i++) OrbitUpdater.reconcilePendingInstall(context);
        assertEquals("", OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void reachingTheInstalledVersionRecordsTheUpdate() {
        // The update was aiming for exactly this build, so it succeeded.
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);

        assertEquals(BuildConfig.VERSION_NAME, OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void anInstallThatNeverCompletedRecordsNothing() {
        // Cancelled, failed, or still pending: this build is older than the target.
        pendingInstallTargeting(BuildConfig.VERSION_CODE + 5);
        OrbitUpdater.reconcilePendingInstall(context);

        assertEquals("an unfinished install must not prompt", "",
                OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void theMarkerIsConsumedExactlyOnce() {
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);
        assertTrue(!OrbitUpdater.pendingPostUpdateVersion(context).isEmpty());

        OrbitUpdater.clearPostUpdateVersion(context);
        assertEquals("", OrbitUpdater.pendingPostUpdateVersion(context));

        // Later launches, recreations, and background/foreground cycles all read the same store.
        for (int i = 0; i < 4; i++) {
            assertEquals("the prompt must not return", "",
                    OrbitUpdater.pendingPostUpdateVersion(context));
        }
    }

    @Test public void reconcilingAgainAfterAcknowledgementDoesNotRecreateIt() {
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);
        OrbitUpdater.clearPostUpdateVersion(context);

        // The pending install state was cleared alongside it, so nothing regenerates the marker.
        OrbitUpdater.reconcilePendingInstall(context);
        assertEquals("", OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void leavingItPendingKeepsItForTheCompanionApp() {
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);

        // The overlay never consumes it, so reading without clearing must leave it in place.
        assertEquals(BuildConfig.VERSION_NAME, OrbitUpdater.pendingPostUpdateVersion(context));
        assertEquals(BuildConfig.VERSION_NAME, OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void aLaterUpdateCanBeAcknowledgedAgain() {
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);
        OrbitUpdater.clearPostUpdateVersion(context);

        // A subsequent verified update repeats the cycle rather than being suppressed forever.
        pendingInstallTargeting(BuildConfig.VERSION_CODE);
        OrbitUpdater.reconcilePendingInstall(context);
        assertEquals(BuildConfig.VERSION_NAME, OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void malformedPendingStateIsIgnored() {
        Prefs.get(context).edit()
                .putLong(PENDING_CODE, BuildConfig.VERSION_CODE)
                .putString(PENDING_FILE, "../escape.apk")
                .commit();
        OrbitUpdater.reconcilePendingInstall(context);

        assertEquals("only a well-formed verified install counts", "",
                OrbitUpdater.pendingPostUpdateVersion(context));
    }

    @Test public void clearingWithNothingPendingIsHarmless() {
        OrbitUpdater.clearPostUpdateVersion(context);
        assertEquals("", OrbitUpdater.pendingPostUpdateVersion(context));
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The update channel preference, and the update state that belongs to it.
 *
 * <p>One rule outranks everything else here: nobody becomes a Beta tester without having said so.
 * That has to hold for a device upgrading in place from an older Orbit that never had this
 * preference, for a fresh install, and — the case that is easy to get wrong — for a device
 * restored from someone's backup.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class UpdateChannelTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- the default ------------------------------------------------------------------------------

    /** A device that has never seen this preference is Stable, exactly like every Orbit before it. */
    @Test public void theDefaultIsStable() {
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        assertFalse(Prefs.betaChannel(context));
    }

    @Test public void anUnrecognisedStoredValueFallsBackToStable() {
        Prefs.get(context).edit().putString(Prefs.UPDATE_CHANNEL, "nightly").commit();
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        assertFalse(Prefs.betaChannel(context));

        Prefs.get(context).edit().putString(Prefs.UPDATE_CHANNEL, "").commit();
        assertFalse(Prefs.betaChannel(context));
    }

    @Test public void normalizationOnlyEverAcceptsTheTwoKnownChannels() {
        assertEquals(Prefs.CHANNEL_BETA, Prefs.normalizeChannel(Prefs.CHANNEL_BETA));
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.normalizeChannel(Prefs.CHANNEL_STABLE));
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.normalizeChannel(null));
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.normalizeChannel("Beta"));
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.normalizeChannel("BETA"));
    }

    // ---- explicit enrolment -----------------------------------------------------------------------

    @Test public void joiningBetaPersists() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        assertTrue(Prefs.betaChannel(context));
        assertEquals(Prefs.CHANNEL_BETA, Prefs.updateChannel(context));
    }

    @Test public void returningToStablePersists() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_STABLE);
        assertEquals(Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        assertFalse(Prefs.betaChannel(context));
    }

    /**
     * A normal in-place app update keeps the preference: it lives in Orbit's ordinary
     * SharedPreferences, which Android carries across an upgrade untouched.
     */
    @Test public void anInPlaceUpdateKeepsTheSelectedChannel() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        // Nothing in Orbit rewrites the preference on launch; reading it again is what an upgraded
        // build does, and it must still say Beta.
        assertEquals(Prefs.CHANNEL_BETA,
                Prefs.get(context).getString(Prefs.UPDATE_CHANNEL, Prefs.CHANNEL_STABLE));
        assertTrue(Prefs.betaChannel(context));
    }

    // ---- Backup & Restore -------------------------------------------------------------------------

    /**
     * Beta enrolment is a standing acceptance of risk, made on one device. Restoring a backup onto
     * a new phone must not carry it across, so the key is deliberately absent from the backup
     * allowlists rather than merely undocumented.
     */
    @Test public void theChannelIsNeverIncludedInABackup() throws Exception {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        JSONObject snapshot = Prefs.backupSnapshot(context);
        assertFalse("Beta enrolment must never be written into a backup",
                snapshot.has(Prefs.UPDATE_CHANNEL));
    }

    @Test public void restoringABackupCannotEnrolADeviceInBeta() throws Exception {
        // A backup taken on a Beta device.
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        Prefs.get(context).edit().putBoolean(Prefs.UPDATE_NOTIFICATIONS, false).commit();
        JSONObject snapshot = Prefs.backupSnapshot(context);

        // Restored onto a fresh install, which starts on Stable.
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, snapshot));

        assertEquals("a restored backup must leave the device on Stable",
                Prefs.CHANNEL_STABLE, Prefs.updateChannel(context));
        assertFalse(Prefs.betaChannel(context));
        // The ordinary, non-risk update preference still restores normally.
        assertFalse(Prefs.updateNotifications(context));
    }

    /** A tampered backup naming the channel key is rejected outright, not partially applied. */
    @Test public void aBackupClaimingTheChannelKeyIsRejected() throws Exception {
        JSONObject snapshot = Prefs.backupSnapshot(context);
        snapshot.put(Prefs.UPDATE_CHANNEL, Prefs.CHANNEL_BETA);
        assertFalse(Prefs.validBackupSnapshot(snapshot));
        assertFalse(Prefs.restoreBackupSnapshot(context, snapshot));
        assertFalse(Prefs.betaChannel(context));
    }

    // ---- channel changes clear the state the old channel owned ------------------------------------

    /**
     * A Beta candidate cached while enrolled must not survive a return to Stable. The cache is
     * dropped on the change, and read back through a second guard that refuses a Beta build in the
     * Stable channel even if a stale entry somehow existed.
     */
    @Test public void switchingToStableRemovesACachedBetaCandidate() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        writeCachedRelease("0.9.9.9-beta.1", 99999L, Prefs.CHANNEL_BETA);
        assertNotNullRelease();

        Prefs.setUpdateChannel(context, Prefs.CHANNEL_STABLE);
        assertNull("a Beta candidate must not survive the switch to Stable",
                OrbitUpdater.loadCachedAvailable(context));
    }

    @Test public void switchingToBetaClearsAStableCandidate() {
        writeCachedRelease("0.9.9.9", 99999L, Prefs.CHANNEL_STABLE);
        assertNotNullRelease();

        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        assertNull("the old channel's candidate is stale by definition",
                OrbitUpdater.loadCachedAvailable(context));
    }

    /** Even without a channel change, a Stable device must never surface a Beta build. */
    @Test public void stableNeverSurfacesABetaBuildFromTheCache() {
        writeCachedRelease("0.9.9.9-beta.4", 99999L, Prefs.CHANNEL_STABLE);
        assertNull(OrbitUpdater.loadCachedAvailable(context));
    }

    @Test public void aBetaDeviceStillSeesACachedBetaCandidate() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        writeCachedRelease("0.9.9.9-beta.4", 99999L, Prefs.CHANNEL_BETA);
        OrbitUpdater.Release release = OrbitUpdater.loadCachedAvailable(context);
        assertTrue(release != null && release.isBeta());
        assertEquals("0.9.9.9 Beta 4", release.displayName());
    }

    /** No downgrade, ever: a cached candidate at or below the installed code is not offered. */
    @Test public void aCachedCandidateIsNeverOlderThanTheInstalledBuild() {
        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);
        writeCachedRelease("0.0.0.1-beta.1", 1L, Prefs.CHANNEL_BETA);
        assertNull(OrbitUpdater.loadCachedAvailable(context));

        writeCachedRelease(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Prefs.CHANNEL_BETA);
        assertNull("an equal versionCode is not an update",
                OrbitUpdater.loadCachedAvailable(context));
    }

    // ---- notification and throttling bookkeeping --------------------------------------------------

    /**
     * Stable's "already told them about this" record must not silence the first Beta notification,
     * and the check throttles must not make a freshly switched channel wait hours for its first
     * look.
     */
    @Test public void changingChannelAllowsAPromptFreshCheck() {
        OrbitUpdater.markNotified(context, 99999L);
        assertTrue(OrbitUpdater.wasNotified(context, 99999L));
        assertTrue(OrbitUpdater.claimForegroundCheck(context));
        assertFalse("the foreground check is throttled once claimed",
                OrbitUpdater.claimForegroundCheck(context));

        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);

        assertFalse("notification bookkeeping from the old channel must not carry over",
                OrbitUpdater.wasNotified(context, 99999L));
        assertTrue("a freshly switched channel may check immediately",
                OrbitUpdater.claimForegroundCheck(context));
        assertEquals(0L, OrbitUpdater.lastCheckMs(context));
    }

    /**
     * An install already in flight is not update discovery, and must survive a preference change:
     * a verified APK on its way to Android's installer must not be orphaned.
     */
    @Test public void aPendingInstallSurvivesAChannelChange() {
        Prefs.get(context).edit()
                .putLong("orbit_update_pending_install_code", 99999L)
                .putString("orbit_update_pending_install_file", "Orbit-Assistant-v0.9.9.9.apk")
                .putString("orbit_post_update_version", "0.9.9.8")
                .commit();

        Prefs.setUpdateChannel(context, Prefs.CHANNEL_BETA);

        assertEquals(99999L,
                Prefs.get(context).getLong("orbit_update_pending_install_code", 0L));
        assertEquals("Orbit-Assistant-v0.9.9.9.apk",
                Prefs.get(context).getString("orbit_update_pending_install_file", ""));
        assertEquals("0.9.9.8", OrbitUpdater.pendingPostUpdateVersion(context));
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private void assertNotNullRelease() {
        assertTrue("the cached candidate should be readable in its own channel",
                OrbitUpdater.loadCachedAvailable(context) != null);
    }

    private void writeCachedRelease(String versionName, long versionCode, String channel) {
        try {
            JSONObject cached = new JSONObject()
                    .put("tag", "v" + versionName)
                    .put("versionName", versionName)
                    .put("versionCode", versionCode)
                    .put("apkAssetName", "Orbit-Assistant-v" + versionName + ".apk")
                    .put("apkSha256", UpdateDiscoveryTest.APK_SHA256)
                    .put("certificateSha256", UpdateDiscoveryTest.CERTIFICATE_SHA256)
                    .put("apkSize", 52_000_000L)
                    .put("releaseNotes", "notes")
                    .put("channel", channel);
            Prefs.get(context).edit()
                    .putString("orbit_update_cached_release", cached.toString()).commit();
        } catch (Exception e) {
            throw new AssertionError("could not stage a cached release", e);
        }
    }
}

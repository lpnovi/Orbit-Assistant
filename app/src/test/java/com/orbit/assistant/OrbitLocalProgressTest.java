package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Watching a download that is actually moving.
 *
 * <p>A device recording showed the Orbit Local screen frozen on a stale megabyte figure while the
 * {@code .part} file underneath kept growing. The screen was not slow; it had stopped looking. Its
 * refresh loop read status asynchronously, then decided whether to schedule another tick from the
 * <em>previous</em> status — which, one tick into a fresh download, still said nothing was
 * installed. So polling switched itself off exactly when it was needed.
 *
 * <p>The fix is structural: the next tick is scheduled where the fresh status lands, the screen
 * keeps observing for as long as it is visible, and only one reader is ever outstanding. These
 * tests pin the parts of that which are decisions rather than timing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalProgressTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitLocalProvider.invalidateStatus();
    }

    // ---- what keeps the screen watching ---------------------------------------------------------

    /**
     * A fresh DOWNLOADING status is what must keep the loop alive.
     *
     * <p>The exact case that failed: the loop asked "is the model busy?" of a status that had not
     * been replaced yet, and the honest answer for the old one was no.
     */
    @Test public void aFreshDownloadingStatusIsLiveWork() {
        assertTrue(status(OrbitLocalStatus.DOWNLOADING, 682_000_000L).modelInFlight());
        assertTrue(status(OrbitLocalStatus.VALIDATING, 1L).modelInFlight());
        assertTrue(status(OrbitLocalStatus.IMPORTING, 1L).modelInFlight());
    }

    /** Queued and offline downloads are live work too: they resume without anyone doing anything. */
    @Test public void queuedAndOfflineDownloadsAreStillLiveWork() {
        assertTrue("a queued download will start on its own, so the screen must keep looking",
                status(OrbitLocalStatus.QUEUED, 5L).modelInFlight());
        assertTrue("an offline download resumes on its own, so the screen must keep looking",
                status(OrbitLocalStatus.WAITING_FOR_NETWORK, 5L).modelInFlight());
    }

    /** Settled states are not live work, and the screen falls back to its slower cadence. */
    @Test public void settledStatesAreNotLiveWork() {
        assertFalse(status(OrbitLocalStatus.READY, 1L).modelInFlight());
        assertFalse(status(OrbitLocalStatus.PAUSED, 1L).modelInFlight());
        assertFalse(status(OrbitLocalStatus.INTERRUPTED, 1L).modelInFlight());
        assertFalse(status(OrbitLocalStatus.NOT_INSTALLED, 0L).modelInFlight());
    }

    /** Whatever stopped it, bytes worth keeping deserve a bar and a Resume. */
    @Test public void everyStateWithBytesToShowShowsProgress() {
        assertTrue(status(OrbitLocalStatus.DOWNLOADING, 1L).showsProgress());
        assertTrue(status(OrbitLocalStatus.QUEUED, 1L).showsProgress());
        assertTrue(status(OrbitLocalStatus.WAITING_FOR_NETWORK, 1L).showsProgress());
        assertTrue(status(OrbitLocalStatus.PAUSED, 1L).showsProgress());
        assertTrue(status(OrbitLocalStatus.INTERRUPTED, 1L).showsProgress());
        assertFalse(status(OrbitLocalStatus.READY, 1L).showsProgress());
    }

    @Test public void bothStoppedStatesOfferResume() {
        assertTrue(status(OrbitLocalStatus.PAUSED, 1L).modelResumable());
        assertTrue(status(OrbitLocalStatus.INTERRUPTED, 1L).modelResumable());
        assertFalse(status(OrbitLocalStatus.DOWNLOADING, 1L).modelResumable());
        assertFalse(status(OrbitLocalStatus.ERROR, 1L).modelResumable());
    }

    // ---- the figures on screen ---------------------------------------------------------------------

    /**
     * The byte count and the bar come from the same number, which comes from the component.
     *
     * <p>There is no second counter in Orbit that could keep ticking after the download stopped,
     * or lag behind one that is still going.
     */
    @Test public void progressIsDerivedFromTheComponentsOwnByteCount() {
        OrbitLocalStatus half = status(OrbitLocalStatus.DOWNLOADING, 799_278_360L);
        assertEquals(500, half.progressPerMille());
        assertEquals(50, half.progressPerMille() / 10);

        OrbitLocalStatus later = status(OrbitLocalStatus.DOWNLOADING, 682_000_000L);
        assertEquals(426, later.progressPerMille());
        assertEquals("682 MB", LocalModelStore.formatBytes(later.modelBytes));
        assertEquals("1.60 GB", LocalModelStore.formatBytes(later.modelSizeBytes));
    }

    /** A download that advances produces a different reading, which is what triggers a redraw. */
    @Test public void anAdvancingDownloadReadsAsChanged() {
        assertFalse("the byte count has to be part of what counts as a change",
                describe(status(OrbitLocalStatus.DOWNLOADING, 100L))
                        .equals(describe(status(OrbitLocalStatus.DOWNLOADING, 200L))));
    }

    /** Every state the component can report has a label; none falls through to "Not installed". */
    @Test public void everyStateHasItsOwnLabel() {
        assertEquals("Downloading", status(OrbitLocalStatus.DOWNLOADING, 1L).stateLabel());
        assertEquals("Preparing", status(OrbitLocalStatus.QUEUED, 1L).stateLabel());
        assertEquals("Waiting to connect",
                status(OrbitLocalStatus.WAITING_FOR_NETWORK, 1L).stateLabel());
        assertEquals("Interrupted", status(OrbitLocalStatus.INTERRUPTED, 1L).stateLabel());
        assertEquals("Paused", status(OrbitLocalStatus.PAUSED, 1L).stateLabel());
        assertEquals("Verifying", status(OrbitLocalStatus.VALIDATING, 1L).stateLabel());
        assertEquals("Ready", status(OrbitLocalStatus.READY, 1L).stateLabel());
    }

    /** No label leaks the component's internal vocabulary to the user. */
    @Test public void noLabelExposesInternalJargon() {
        for (String state : new String[]{OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.QUEUED,
                OrbitLocalStatus.WAITING_FOR_NETWORK, OrbitLocalStatus.DOWNLOADING,
                OrbitLocalStatus.INTERRUPTED, OrbitLocalStatus.PAUSED, OrbitLocalStatus.VALIDATING,
                OrbitLocalStatus.IMPORTING, OrbitLocalStatus.READY, OrbitLocalStatus.ERROR}) {
            String label = status(state, 1L).stateLabel();
            assertFalse(label + " reads like an enum constant", label.contains("_"));
            assertFalse(label + " reads like an enum constant", label.equals(label.toUpperCase()));
            assertFalse("WorkManager is Orbit's business, not the user's",
                    label.toLowerCase().contains("worker"));
        }
    }

    // ---- one reader at a time ------------------------------------------------------------------------

    /**
     * Status reads go through one executor, so a screen left open cannot accumulate threads.
     *
     * <p>Asserted against the source because the property is structural: the guarantee is that
     * there is no code path that spawns a reader of its own.
     */
    @Test public void statusReadsCannotOverlap() {
        String client = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalClient.java");
        assertTrue("status reads must be serialised through one executor",
                client.contains("STATUS_EXECUTOR.execute("));
        assertFalse("a per-call thread is what let polling accumulate readers",
                client.contains("}, \"orbit-local-status\").start()"));

        String screen = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/LocalAiActivity.java");
        assertTrue("a tick arriving mid-read must be dropped, not doubled up",
                screen.contains("if (statusInFlight) return;"));
        assertTrue("and the next tick is scheduled where the fresh status lands",
                screen.contains("scheduleNextRefresh();"));
    }

    /**
     * Leaving the screen stops watching, and only that.
     *
     * <p>The download belongs to the component's own WorkManager job in its own process. Nothing
     * in this Activity's pause path may reach for a pause, a cancel, or a delete.
     */
    @Test public void leavingTheScreenNeverStopsTheDownload() {
        String screen = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/LocalAiActivity.java");
        int pause = screen.indexOf("@Override protected void onPause()");
        assertTrue("onPause was not found", pause > 0);
        String body = screen.substring(pause, screen.indexOf('}', screen.indexOf('{', pause)) + 1);
        assertFalse("leaving the screen must never pause the download",
                body.contains("pauseModelDownload"));
        assertFalse("nor cancel it", body.contains("cancelModelDownload"));
        assertFalse("nor delete anything", body.contains("deleteModel"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static String describe(OrbitLocalStatus status) {
        return status.modelState + ":" + status.modelBytes;
    }

    private static OrbitLocalStatus status(String state, long bytes) {
        Bundle bundle = new Bundle();
        bundle.putInt("protocol", OrbitLocalComponent.PROTOCOL_VERSION);
        bundle.putString("componentVersionName", BuildConfig.VERSION_NAME);
        bundle.putLong("componentVersionCode", BuildConfig.VERSION_CODE);
        bundle.putString("modelState", state);
        bundle.putString("modelId", LocalModelStore.MODEL_ID);
        bundle.putString("modelDisplayName", LocalModelStore.MODEL_DISPLAY_NAME);
        bundle.putLong("modelBytes", bytes);
        bundle.putLong("modelTotalBytes", bytes);
        bundle.putLong("modelSizeBytes", LocalModelStore.MODEL_SIZE_BYTES);
        bundle.putString("modelError", "");
        bundle.putLong("freeBytes", 8_000_000_000L);
        return OrbitLocalStatus.from(bundle);
    }
}

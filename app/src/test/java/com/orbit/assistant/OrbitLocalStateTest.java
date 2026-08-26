package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Bundle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * How Orbit reads the component's state, and what it refuses to conclude from it.
 *
 * <p>The component reports across a process boundary, so the answers arrive as loose Bundle keys
 * that Orbit has to interpret. The rule throughout is pessimism: an unreachable component, an
 * unrecognised state word, or a missing key can only ever make Orbit Local look less ready, never
 * more. Presenting a model as installed when it is not would mean routing chat to something that
 * cannot answer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalStateTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        LocalModelStore.deleteLegacy(context);
        OrbitLocalProvider.invalidateStatus();
    }

    // ---- reading the component's status --------------------------------------------------------------

    @Test public void aFullStatusIsReadBack() {
        OrbitLocalStatus status = OrbitLocalStatus.from(bundle(OrbitLocalStatus.READY, 1_598_556_720L));
        assertEquals(OrbitLocalComponent.PROTOCOL_VERSION, status.protocol);
        assertEquals("0.7.7.5-beta.1", status.componentVersionName);
        assertEquals(OrbitLocalStatus.READY, status.modelState);
        assertTrue(status.modelReady());
        assertFalse(status.modelBusy());
        assertEquals(1000, status.progressPerMille());
        assertEquals("Ready", status.stateLabel());
    }

    /** A state word Orbit does not know is never trusted into meaning "usable". */
    @Test public void anUnknownStateIsTreatedAsNothingInstalled() {
        assertEquals(OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.normalizeState("SORT_OF_READY"));
        assertEquals(OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.normalizeState("ready"));
        assertEquals(OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.normalizeState(""));
        assertEquals(OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.normalizeState(null));

        OrbitLocalStatus status = OrbitLocalStatus.from(bundle("ALMOST_READY", 1_598_556_720L));
        assertFalse("an unrecognised state must never read as ready", status.modelReady());
    }

    @Test public void everyKnownStateSurvivesTheRoundTrip() {
        for (String state : new String[]{OrbitLocalStatus.NOT_INSTALLED, OrbitLocalStatus.PAUSED,
                OrbitLocalStatus.QUEUED, OrbitLocalStatus.WAITING_FOR_NETWORK,
                OrbitLocalStatus.DOWNLOADING, OrbitLocalStatus.INTERRUPTED,
                OrbitLocalStatus.VALIDATING, OrbitLocalStatus.IMPORTING, OrbitLocalStatus.READY,
                OrbitLocalStatus.ERROR}) {
            assertEquals(state, OrbitLocalStatus.from(bundle(state, 0L)).modelState);
        }
    }

    @Test public void busyStatesAreTheOnesWorthPollingFor() {
        assertTrue(OrbitLocalStatus.from(bundle(OrbitLocalStatus.DOWNLOADING, 1L)).modelBusy());
        assertTrue(OrbitLocalStatus.from(bundle(OrbitLocalStatus.VALIDATING, 1L)).modelBusy());
        assertTrue(OrbitLocalStatus.from(bundle(OrbitLocalStatus.IMPORTING, 1L)).modelBusy());
        assertFalse(OrbitLocalStatus.from(bundle(OrbitLocalStatus.PAUSED, 1L)).modelBusy());
        assertFalse(OrbitLocalStatus.from(bundle(OrbitLocalStatus.READY, 1L)).modelBusy());
    }

    /**
     * Protocol 2 stopped one word standing in for four different situations.
     *
     * <p>A queued download, an offline one, and one that was cut short are all things that happen
     * without anybody choosing them, and each now says so. PAUSED is left meaning the one thing it
     * should always have meant.
     */
    @Test public void aPauseIsDistinguishableFromEverythingThatMerelyStopped() {
        assertTrue(OrbitLocalStatus.from(bundle(OrbitLocalStatus.PAUSED, 1L)).modelResumable());
        assertTrue(OrbitLocalStatus.from(bundle(OrbitLocalStatus.INTERRUPTED, 1L)).modelResumable());
        assertFalse("a queued download is not stopped",
                OrbitLocalStatus.from(bundle(OrbitLocalStatus.QUEUED, 1L)).modelResumable());
        assertFalse("an offline download is not stopped either",
                OrbitLocalStatus.from(bundle(OrbitLocalStatus.WAITING_FOR_NETWORK, 1L))
                        .modelResumable());
        assertEquals("Paused", OrbitLocalStatus.from(bundle(OrbitLocalStatus.PAUSED, 1L))
                .stateLabel());
        assertEquals("Interrupted", OrbitLocalStatus.from(bundle(OrbitLocalStatus.INTERRUPTED, 1L))
                .stateLabel());
    }

    @Test public void progressIsBoundedAndSafeWithNoSize() {
        assertEquals(500, OrbitLocalStatus.from(bundle(OrbitLocalStatus.DOWNLOADING,
                799_278_360L)).progressPerMille());
        assertEquals(0, OrbitLocalStatus.from(bundle(OrbitLocalStatus.DOWNLOADING, 0L))
                .progressPerMille());
        Bundle noSize = bundle(OrbitLocalStatus.DOWNLOADING, 100L);
        noSize.putLong("modelSizeBytes", 0L);
        assertEquals("a missing size must not divide by zero",
                0, OrbitLocalStatus.from(noSize).progressPerMille());
    }

    @Test public void anEmptyBundleDegradesGracefully() {
        OrbitLocalStatus status = OrbitLocalStatus.from(new Bundle());
        assertFalse(status.modelReady());
        assertEquals(OrbitLocalStatus.NOT_INSTALLED, status.modelState);
        assertEquals("Local model", status.modelDisplayName);
        assertNull(OrbitLocalStatus.from(null));
    }

    // ---- provider availability -------------------------------------------------------------------

    /**
     * With no component installed there is no runtime, so Orbit Local cannot be chosen and cannot
     * stay chosen — whatever model files happen to exist on the device.
     */
    @Test public void withoutTheComponentOrbitLocalIsUnavailable() {
        AiProvider local = AiProviders.byId(Prefs.PROVIDER_LOCAL);
        assertFalse("no component means no local AI", local.selectable(context));
        assertFalse(AiProviders.select(context, Prefs.PROVIDER_LOCAL));
    }

    /** A stored selection cannot survive the component being gone. */
    @Test public void aStoredLocalSelectionFallsBackWhenTheComponentIsMissing() {
        Prefs.get(context).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_LOCAL).commit();
        assertEquals("Orbit must never route chat to a provider that cannot answer",
                Prefs.PROVIDER_CHATGPT, AiProviders.active(context).id());
    }

    /** Even a full legacy model does not make Orbit Local usable on its own. */
    @Test public void aLegacyModelAloneDoesNotMakeLocalAvailable() throws Exception {
        writeLegacyModel(LocalModelStore.MODEL_SIZE_BYTES);
        assertTrue(LocalModelStore.hasLegacyModel(context));
        assertFalse("the model still needs a runtime to execute it",
                AiProviders.byId(Prefs.PROVIDER_LOCAL).selectable(context));
    }

    /**
     * The status line names the thing that is actually missing. A device that already downloaded
     * 1.6 GB under an older Orbit is told the expensive part is done, not simply "not installed".
     */
    @Test public void theStatusLineExplainsWhatIsMissing() {
        assertEquals("Component not installed", OrbitLocalProvider.componentStatusDetail(
                OrbitLocalComponent.State.NOT_INSTALLED, false));
        assertEquals("Component required · model ready to move",
                OrbitLocalProvider.componentStatusDetail(
                        OrbitLocalComponent.State.NOT_INSTALLED, true));
        assertEquals("Component not verified", OrbitLocalProvider.componentStatusDetail(
                OrbitLocalComponent.State.UNTRUSTED, true));
        assertEquals("Component update required", OrbitLocalProvider.componentStatusDetail(
                OrbitLocalComponent.State.UPDATE_REQUIRED, false));
        assertEquals("a usable component is not itself the problem", "",
                OrbitLocalProvider.componentStatusDetail(OrbitLocalComponent.State.INSTALLED, false));
    }

    // ---- the legacy model an older Orbit left behind -----------------------------------------------

    @Test public void noLegacyModelOnAFreshDevice() {
        assertFalse(LocalModelStore.hasLegacyModel(context));
        assertFalse(LocalModelStore.hasLegacyLeftovers(context));
        assertEquals(0L, LocalModelStore.legacyBytes(context));
    }

    @Test public void aCompleteLegacyModelIsDetected() throws Exception {
        writeLegacyModel(LocalModelStore.MODEL_SIZE_BYTES);
        assertTrue(LocalModelStore.hasLegacyModel(context));
        assertFalse(LocalModelStore.hasLegacyLeftovers(context));
        assertEquals(LocalModelStore.MODEL_SIZE_BYTES, LocalModelStore.legacyBytes(context));
    }

    /** A truncated file is not a model. It is leftovers, and is described as such. */
    @Test public void anIncompleteLegacyFileIsNotAModel() throws Exception {
        writeLegacyModel(4096L);
        assertFalse("a wrong-sized file must never be offered as a movable model",
                LocalModelStore.hasLegacyModel(context));
        assertTrue(LocalModelStore.hasLegacyLeftovers(context));
        assertEquals(4096L, LocalModelStore.legacyBytes(context));
    }

    @Test public void deletingTheLegacyCopyRemovesEverythingItLeftBehind() throws Exception {
        writeLegacyModel(LocalModelStore.MODEL_SIZE_BYTES);
        write(new File(LocalModelStore.legacyModelDir(context),
                LocalModelStore.MODEL_FILE_NAME + ".part"), 32L);
        assertTrue(LocalModelStore.legacyBytes(context) > LocalModelStore.MODEL_SIZE_BYTES);

        LocalModelStore.deleteLegacy(context);

        assertFalse(LocalModelStore.hasLegacyModel(context));
        assertEquals(0L, LocalModelStore.legacyBytes(context));
        assertFalse(LocalModelStore.legacyModelFile(context).exists());
    }

    /**
     * Migration needs room for a second copy until the first is removed. Refusing up front is what
     * lets Orbit offer the honest replace-instead-of-move path rather than stranding the user
     * half-way through a transfer that cannot finish.
     */
    @Test public void migrationRequiresRoomForTwoCopies() {
        long need = LocalModelStore.MODEL_SIZE_BYTES + LocalModelStore.STORAGE_MARGIN_BYTES;
        assertTrue("plenty of room to hold both copies",
                LocalModelStore.enoughStorageToMigrate(need + 1));
        assertTrue("exactly enough is enough", LocalModelStore.enoughStorageToMigrate(need));
        assertFalse("a device that cannot hold two copies must be offered the replace path instead",
                LocalModelStore.enoughStorageToMigrate(need - 1));
        assertFalse(LocalModelStore.enoughStorageToMigrate(0L));
        assertTrue("an unknown free-space reading must not block the attempt",
                LocalModelStore.enoughStorageToMigrate(-1L));
    }

    @Test public void sizesAreFormattedForPeople() {
        assertEquals("1.60 GB", LocalModelStore.formatBytes(1_598_556_720L));
        assertEquals("29 MB", LocalModelStore.formatBytes(29_000_000L));
        assertEquals("0 B", LocalModelStore.formatBytes(0L));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private void writeLegacyModel(long bytes) throws Exception {
        write(LocalModelStore.legacyModelFile(context), bytes);
    }

    /** A sparse file of the exact length, so a 1.6 GB fixture costs nothing on disk. */
    private static void write(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }

    private static Bundle bundle(String state, long bytes) {
        Bundle bundle = new Bundle();
        bundle.putInt("protocol", OrbitLocalComponent.PROTOCOL_VERSION);
        bundle.putString("componentVersionName", "0.7.7.5-beta.1");
        bundle.putLong("componentVersionCode", 728L);
        bundle.putString("modelState", state);
        bundle.putString("modelId", "qwen2.5-1.5b-instruct-q8");
        bundle.putString("modelDisplayName", "Qwen 2.5 (1.5B)");
        bundle.putLong("modelBytes", bytes);
        bundle.putLong("modelTotalBytes", bytes);
        bundle.putLong("modelSizeBytes", 1_598_556_720L);
        bundle.putString("modelError", "");
        bundle.putLong("freeBytes", 20_000_000_000L);
        return bundle;
    }
}

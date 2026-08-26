package com.orbit.assistant.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The model's state machine, now that the component owns it.
 *
 * <p>These rules moved across the process boundary in v0.7.7.5 and had to survive the move intact,
 * because they are what stop a partial or corrupted 1.6 GB download being treated as a working
 * model: no READY without a complete, checksum-verified file; no lost partial downloads; and a
 * file that fails validation is deleted rather than left where it could later be mistaken for the
 * real thing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentModelStoreTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_local_component", Context.MODE_PRIVATE)
                .edit().clear().commit();
        File[] files = ComponentModelStore.modelDir(context).listFiles();
        if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    // ---- the state machine -------------------------------------------------------------------------

    @Test public void aFreshComponentHasNoModel() {
        assertEquals(ComponentModelStore.State.NOT_INSTALLED, ComponentModelStore.state(context));
        assertFalse(ComponentModelStore.isReady(context));
        assertEquals(0L, ComponentModelStore.downloadedBytes(context));
        assertEquals(0L, ComponentModelStore.totalModelBytes(context));
    }

    /** A mark without its file is a lie the filesystem can disprove, so it is disproved. */
    @Test public void aReadyMarkWithoutItsFileDowngradesInsteadOfLying() {
        ComponentModelStore.setState(context, ComponentModelStore.State.READY, "");
        assertEquals(ComponentModelStore.State.NOT_INSTALLED, ComponentModelStore.state(context));
        assertFalse(ComponentModelStore.isReady(context));
    }

    @Test public void partialBytesFromADeadProcessBecomeResumable() throws Exception {
        write(ComponentModelStore.partFile(context), 4L);
        ComponentModelStore.setState(context, ComponentModelStore.State.DOWNLOADING, "");
        assertEquals("an interrupted download must offer resume, not restart",
                ComponentModelStore.State.PAUSED, ComponentModelStore.state(context));
        assertEquals(4L, ComponentModelStore.downloadedBytes(context));
    }

    /** An import that died with its process leaves nothing that could be mistaken for progress. */
    @Test public void anAbandonedImportIsSweptAway() throws Exception {
        write(ComponentModelStore.importFile(context), 1024L);
        ComponentModelStore.setState(context, ComponentModelStore.State.IMPORTING, "");
        assertEquals(ComponentModelStore.State.NOT_INSTALLED, ComponentModelStore.state(context));
        assertFalse("a half-copied import must not survive as usable data",
                ComponentModelStore.importFile(context).exists());
    }

    /**
     * A complete file only ever exists after verified promotion, so a lost preference mark
     * re-adopts it instead of demanding a fresh multi-gigabyte download.
     */
    @Test public void aCompleteFileWithNoMarkIsReadopted() throws Exception {
        write(ComponentModelStore.modelFile(context), ComponentModelStore.MODEL_SIZE_BYTES);
        assertEquals(ComponentModelStore.State.READY, ComponentModelStore.state(context));
        assertTrue(ComponentModelStore.isReady(context));
    }

    @Test public void aWrongSizedFileIsNeverReadyRegardlessOfItsMark() throws Exception {
        write(ComponentModelStore.modelFile(context), ComponentModelStore.MODEL_SIZE_BYTES - 1);
        ComponentModelStore.setState(context, ComponentModelStore.State.READY, "");
        assertFalse("one byte short is not a model", ComponentModelStore.isReady(context));
    }

    // ---- validation --------------------------------------------------------------------------------

    @Test public void anIncompleteFileNeverValidates() throws Exception {
        File part = ComponentModelStore.partFile(context);
        write(part, 16L);
        assertFalse(ComponentModelStore.validateAndPromote(context, part));
        assertEquals(ComponentModelStore.State.ERROR, ComponentModelStore.state(context));
        assertFalse("incomplete bytes must be removed, not kept as a fake model", part.exists());
        assertFalse(ComponentModelStore.modelFile(context).exists());
        assertFalse(ComponentModelStore.errorMessage(context).isEmpty());
    }

    /** Right size, wrong contents. Only the checksum can tell, and it must be what decides. */
    @Test public void aCorruptedFileOfTheRightSizeIsRejected() throws Exception {
        File part = ComponentModelStore.partFile(context);
        write(part, ComponentModelStore.MODEL_SIZE_BYTES);
        assertFalse("a size match must never be mistaken for an integrity match",
                ComponentModelStore.validateAndPromote(context, part));
        assertEquals(ComponentModelStore.State.ERROR, ComponentModelStore.state(context));
        assertFalse(part.exists());
        assertFalse(ComponentModelStore.modelFile(context).exists());
        assertFalse(ComponentModelStore.isReady(context));
    }

    @Test public void theModelIsPinnedToAnExactSizeAndChecksum() {
        assertEquals(1_598_556_720L, ComponentModelStore.MODEL_SIZE_BYTES);
        assertEquals(64, ComponentModelStore.MODEL_SHA256.length());
        assertTrue(ComponentModelStore.MODEL_SHA256.matches("^[0-9a-f]{64}$"));
        assertTrue(ComponentModelStore.MODEL_DOWNLOAD_URL.startsWith("https://"));
        assertTrue(ComponentModelStore.MODEL_DOWNLOAD_URL
                .contains("litert-community/Qwen2.5-1.5B-Instruct"));
    }

    /** The component keeps the same model identity, so a migrated file is the same model. */
    @Test public void theModelIdentityMatchesWhatOrbitMigratesFrom() {
        assertEquals("qwen2.5-1.5b-instruct-q8", ComponentModelStore.MODEL_ID);
        assertEquals("qwen2.5-1.5b-instruct-q8.task", ComponentModelStore.MODEL_FILE_NAME);
        assertEquals(4096, ComponentModelStore.MODEL_MAX_TOKENS);
    }

    // ---- deletion ----------------------------------------------------------------------------------

    @Test public void deleteClearsEverythingAndIsRestartable() throws Exception {
        write(ComponentModelStore.partFile(context), 8L);
        ComponentModelStore.setState(context, ComponentModelStore.State.ERROR, "boom");
        ComponentModelStore.delete(context);
        assertEquals(ComponentModelStore.State.NOT_INSTALLED, ComponentModelStore.state(context));
        assertFalse(ComponentModelStore.partFile(context).exists());
        assertEquals("", ComponentModelStore.errorMessage(context));
    }

    @Test public void deleteSweepsStrayModelFilesButNothingElse() throws Exception {
        File dir = ComponentModelStore.modelDir(context);
        write(ComponentModelStore.partFile(context), 1L);
        write(new File(dir, ComponentModelStore.MODEL_FILE_NAME + ".tmp"), 1L);
        File unrelated = new File(dir, "something-else.txt");
        write(unrelated, 1L);

        ComponentModelStore.delete(context);

        assertFalse(new File(dir, ComponentModelStore.MODEL_FILE_NAME + ".tmp").exists());
        assertFalse(ComponentModelStore.partFile(context).exists());
        assertTrue("deletion must not sweep files belonging to something else", unrelated.exists());
    }

    @Test public void clearingAnErrorReturnsToACleanStartingPoint() {
        ComponentModelStore.setState(context, ComponentModelStore.State.ERROR, "boom");
        assertEquals(ComponentModelStore.State.ERROR, ComponentModelStore.state(context));
        ComponentModelStore.clearError(context);
        assertEquals(ComponentModelStore.State.NOT_INSTALLED, ComponentModelStore.state(context));
    }

    // ---- storage accounting ------------------------------------------------------------------------

    @Test public void totalBytesCoversEverythingTheComponentHolds() throws Exception {
        write(ComponentModelStore.partFile(context), 100L);
        write(new File(ComponentModelStore.modelDir(context), "leftover.bin"), 50L);
        assertEquals(150L, ComponentModelStore.totalModelBytes(context));
    }

    /**
     * A device with no room is told so before a multi-gigabyte download starts, and an unreadable
     * free-space figure never blocks a capable device on the strength of a number the platform
     * would not give us.
     */
    @Test public void storageIsCheckedBeforeCommittingToADownload() {
        long need = ComponentModelStore.MODEL_SIZE_BYTES;
        long margin = ComponentModelStore.STORAGE_MARGIN_BYTES;
        assertTrue("plenty of room", ComponentModelStore.enoughStorage(need + margin + 1, need));
        assertTrue("exactly enough is enough", ComponentModelStore.enoughStorage(need + margin, need));
        assertFalse("one byte short of the safety margin is not enough",
                ComponentModelStore.enoughStorage(need + margin - 1, need));
        assertFalse("a nearly full device must be refused up front",
                ComponentModelStore.enoughStorage(1024L, need));
        assertTrue("an unknown reading must not block the attempt",
                ComponentModelStore.enoughStorage(-1L, need));
    }

    /** Resuming only needs room for what is still missing, not for the whole model again. */
    @Test public void resumingOnlyNeedsRoomForWhatIsLeft() {
        long margin = ComponentModelStore.STORAGE_MARGIN_BYTES;
        assertTrue(ComponentModelStore.enoughStorage(margin + 1000L, 1000L));
        assertFalse(ComponentModelStore.enoughStorage(margin + 1000L,
                ComponentModelStore.MODEL_SIZE_BYTES));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    /** A sparse file of exactly this length, so a 1.6 GB fixture costs nothing on disk. */
    private static void write(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }
}

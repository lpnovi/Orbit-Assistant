package com.orbit.assistant;

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
 * The local model's state machine must never claim more than the filesystem can prove: no READY
 * without a complete verified file, no lost partial downloads, and no corrupted file surviving
 * validation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class LocalModelStoreTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_local_ai", Context.MODE_PRIVATE).edit().clear().commit();
        //noinspection ResultOfMethodCallIgnored
        LocalModelStore.modelFile(context).delete();
        //noinspection ResultOfMethodCallIgnored
        LocalModelStore.partFile(context).delete();
    }

    @Test public void aFreshInstallIsNotInstalled() {
        assertEquals(LocalModelStore.State.NOT_INSTALLED, LocalModelStore.state(context));
        assertFalse(LocalModelStore.isReady(context));
        assertEquals(0L, LocalModelStore.downloadedBytes(context));
    }

    @Test public void aReadyMarkWithoutItsFileDowngradesInsteadOfLying() {
        LocalModelStore.setState(context, LocalModelStore.State.READY, "");
        assertEquals("READY without the model file must not survive a read",
                LocalModelStore.State.NOT_INSTALLED, LocalModelStore.state(context));
        assertFalse(LocalModelStore.isReady(context));
    }

    @Test public void partialBytesFromADeadProcessBecomeResumable() throws Exception {
        write(LocalModelStore.partFile(context), new byte[]{1, 2, 3, 4});
        LocalModelStore.setState(context, LocalModelStore.State.DOWNLOADING, "");
        assertEquals("an interrupted download must offer resume, not restart",
                LocalModelStore.State.PAUSED, LocalModelStore.state(context));
        assertEquals(4L, LocalModelStore.downloadedBytes(context));
    }

    @Test public void anIncompleteFileNeverValidates() throws Exception {
        write(LocalModelStore.partFile(context), "not a real model".getBytes());
        assertFalse(LocalModelStore.validateAndPromote(context));
        assertEquals(LocalModelStore.State.ERROR, LocalModelStore.state(context));
        assertFalse("corrupted bytes must be removed, not kept as a fake model",
                LocalModelStore.partFile(context).exists());
        assertFalse(LocalModelStore.modelFile(context).exists());
        assertFalse(LocalModelStore.errorMessage(context).isEmpty());
    }

    @Test public void deleteClearsEverythingAndIsRestartable() throws Exception {
        write(LocalModelStore.partFile(context), new byte[]{9});
        LocalModelStore.setState(context, LocalModelStore.State.ERROR, "boom");
        LocalModelStore.delete(context);
        assertEquals(LocalModelStore.State.NOT_INSTALLED, LocalModelStore.state(context));
        assertFalse(LocalModelStore.partFile(context).exists());
        assertEquals("", LocalModelStore.errorMessage(context));
    }

    @Test public void deleteSweepsStrayModelFilesButNothingElse() throws Exception {
        File dir = LocalModelStore.modelFile(context).getParentFile();
        write(LocalModelStore.partFile(context), new byte[]{1});
        File stray = new File(dir, LocalModelStore.MODEL_FILE_NAME + ".tmp");
        write(stray, new byte[]{2});
        File unrelated = new File(dir, "some-other-model.task");
        write(unrelated, new byte[]{3});

        LocalModelStore.delete(context);

        assertFalse("partial bytes must be removed", LocalModelStore.partFile(context).exists());
        assertFalse("stray temp files of this model must be removed", stray.exists());
        assertTrue("another model's file must never be touched", unrelated.exists());
        //noinspection ResultOfMethodCallIgnored
        unrelated.delete();
    }

    @Test public void aCompleteFileWithALostMarkIsReAdoptedNotReDownloaded() throws Exception {
        // A file of the exact pinned size can only exist through checksum promotion; losing the
        // preference mark (cleared prefs, key migration) must not cost a 1.6 GB re-download.
        File model = LocalModelStore.modelFile(context);
        //noinspection ResultOfMethodCallIgnored
        model.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(model, "rw")) {
            raf.setLength(LocalModelStore.MODEL_SIZE_BYTES);
        }
        assertEquals(LocalModelStore.State.READY, LocalModelStore.state(context));
        assertTrue(LocalModelStore.isReady(context));
    }

    @Test public void modelSpecsKeepIndependentStateAndFiles() {
        // The store must not assume a single local component: a future device-action model gets
        // its own files and its own state keys, never sharing the chat model's.
        LocalModelStore.ModelSpec future = new LocalModelStore.ModelSpec(
                "future-intent-model", "Future Intent Model",
                "https://example.invalid/model.task", 1234L, "00", 1024,
                "model_state:future-intent-model", "model_error:future-intent-model");
        assertFalse(future.fileName.equals(LocalModelStore.CHAT_MODEL.fileName));
        assertFalse(future.stateKey.equals(LocalModelStore.CHAT_MODEL.stateKey));

        LocalModelStore.setState(context, future, LocalModelStore.State.ERROR, "future boom");
        assertEquals("another spec's state must not leak into the chat model",
                LocalModelStore.State.NOT_INSTALLED, LocalModelStore.state(context));
        assertEquals(LocalModelStore.State.ERROR, LocalModelStore.state(context, future));

        LocalModelStore.setState(context, LocalModelStore.CHAT_MODEL,
                LocalModelStore.State.NOT_INSTALLED, "");
        assertEquals("the chat model's state must not clobber another spec's",
                LocalModelStore.State.ERROR, LocalModelStore.state(context, future));
    }

    @Test public void clearErrorReturnsToACleanState() {
        LocalModelStore.setState(context, LocalModelStore.State.ERROR, "boom");
        LocalModelStore.clearError(context);
        assertEquals(LocalModelStore.State.NOT_INSTALLED, LocalModelStore.state(context));
    }

    @Test public void theModelPinIsInternallyConsistent() {
        assertTrue(LocalModelStore.MODEL_SIZE_BYTES > 1_000_000_000L);
        assertEquals("a SHA-256 pin must be 64 hex characters",
                64, LocalModelStore.MODEL_SHA256.length());
        assertTrue(LocalModelStore.MODEL_DOWNLOAD_URL.startsWith("https://"));
        assertTrue("the download must come from the pinned public model repository",
                LocalModelStore.MODEL_DOWNLOAD_URL.contains("litert-community/Qwen2.5-1.5B-Instruct"));
    }

    @Test public void byteFormattingIsHuman() {
        assertEquals("1.6 GB", LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES));
        assertEquals("500 MB", LocalModelStore.formatBytes(500_000_000L));
    }

    private static void write(File file, byte[] bytes) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
}

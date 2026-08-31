package com.orbit.assistant.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
 * Two models in one component, and the independence that makes that safe.
 *
 * <p>The component held exactly one model for three releases, and its state machine, its download,
 * its storage accounting and its deletion were all written for one. Beta 1 gives it a second, and
 * the failure mode of getting that wrong is severe in a specific way: deleting a 521 MB action model
 * must not take a 1.6 GB chat model with it, and installing one must not make Orbit believe the
 * other has arrived.
 *
 * <p>The other thing asserted here is that the chat model's identity did not move. Its preference
 * keys and its file name are exactly what they were, so a phone that already has it recognises it
 * unchanged and re-downloads nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentActionModelTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_local_component", Context.MODE_PRIVATE)
                .edit().clear().commit();
        File[] files = ComponentModelStore.modelDir(context).listFiles();
        if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
            file.delete();
        TestWorkManager.resetQueue(context);
    }

    // ---- the pin ---------------------------------------------------------------------------------

    @Test public void theActionModelIsPinnedToAnExactSizeAndChecksum() {
        ComponentModelSpec spec = ComponentModelSpec.ACTION;
        assertEquals(546_660_344L, spec.sizeBytes);
        assertEquals(64, spec.sha256.length());
        assertTrue(spec.sha256.matches("^[0-9a-f]{64}$"));
        assertTrue(spec.downloadUrl.startsWith("https://"));
        assertTrue(spec.downloadUrl.contains("litert-community/Qwen2.5-0.5B-Instruct"));
        assertEquals("qwen2.5-0.5b-instruct-q8", spec.id);
        assertEquals("qwen2.5-0.5b-instruct-q8.task", spec.fileName());
        assertEquals(1280, spec.maxTokens);
        assertEquals("Apache-2.0", spec.license);
    }

    /** The chat model's identity is unchanged, so an existing install re-downloads nothing. */
    @Test public void theChatModelIdentityDidNotMove() {
        assertEquals("qwen2.5-1.5b-instruct-q8", ComponentModelSpec.CHAT.id);
        assertEquals("qwen2.5-1.5b-instruct-q8.task", ComponentModelSpec.CHAT.fileName());
        assertEquals(1_598_556_720L, ComponentModelSpec.CHAT.sizeBytes);
        assertEquals("the chat model keeps its original preference keys",
                "model_", ComponentModelSpec.CHAT.keyPrefix());
        assertEquals("and its original background-work name",
                ComponentDownloadWorker.UNIQUE_WORK, ComponentModelSpec.CHAT.workName());
    }

    @Test public void theTwoModelsShareNothing() {
        assertNotEquals(ComponentModelSpec.CHAT.id, ComponentModelSpec.ACTION.id);
        assertNotEquals(ComponentModelSpec.CHAT.fileName(), ComponentModelSpec.ACTION.fileName());
        assertNotEquals(ComponentModelSpec.CHAT.keyPrefix(), ComponentModelSpec.ACTION.keyPrefix());
        assertNotEquals(ComponentModelSpec.CHAT.workName(), ComponentModelSpec.ACTION.workName());
        assertNotEquals(ComponentModelSpec.CHAT.downloadUrl, ComponentModelSpec.ACTION.downloadUrl);
        assertNotEquals(ComponentModelSpec.CHAT.sha256, ComponentModelSpec.ACTION.sha256);
    }

    @Test public void anUnknownSlotNameResolvesToTheChatModel() {
        assertEquals(ComponentModelSpec.CHAT, ComponentModelSpec.forSlotName(null));
        assertEquals(ComponentModelSpec.CHAT, ComponentModelSpec.forSlotName(""));
        assertEquals(ComponentModelSpec.CHAT, ComponentModelSpec.forSlotName("nonsense"));
        assertEquals(ComponentModelSpec.ACTION, ComponentModelSpec.forSlotName("ACTION"));
        assertEquals(ComponentModelSpec.ACTION, ComponentModelSpec.forSlotName("action"));
    }

    // ---- independent state -------------------------------------------------------------------------

    @Test public void eachModelHasItsOwnState() throws Exception {
        write(ComponentModelStore.modelFile(context, ComponentModelSpec.ACTION),
                ComponentModelSpec.ACTION.sizeBytes);

        assertTrue("the action model is installed",
                ComponentModelStore.isReady(context, ComponentModelSpec.ACTION));
        assertFalse("and the chat model is not",
                ComponentModelStore.isReady(context, ComponentModelSpec.CHAT));
        assertEquals(ComponentModelStore.State.NOT_INSTALLED,
                ComponentModelStore.state(context, ComponentModelSpec.CHAT));
    }

    @Test public void eachModelHasItsOwnErrorAndFailure() {
        ComponentModelStore.setState(context, ComponentModelSpec.ACTION,
                ComponentModelStore.State.ERROR, "action boom");
        ComponentModelStore.recordFailure(context, ComponentModelSpec.ACTION,
                ComponentModelStore.FAILURE_CHECKSUM);

        assertEquals("action boom",
                ComponentModelStore.errorMessage(context, ComponentModelSpec.ACTION));
        assertEquals("", ComponentModelStore.errorMessage(context, ComponentModelSpec.CHAT));
        assertEquals(ComponentModelStore.FAILURE_CHECKSUM,
                ComponentModelStore.lastFailure(context, ComponentModelSpec.ACTION));
        assertEquals(ComponentModelStore.FAILURE_NONE,
                ComponentModelStore.lastFailure(context, ComponentModelSpec.CHAT));
    }

    @Test public void pausingOneDoesNotPauseTheOther() {
        ComponentDownloadWorker.pause(context, ComponentModelSpec.ACTION);
        assertTrue(ComponentModelStore.pauseRequested(context, ComponentModelSpec.ACTION));
        assertFalse(ComponentModelStore.pauseRequested(context, ComponentModelSpec.CHAT));
    }

    // ---- independent storage and deletion -----------------------------------------------------------

    @Test public void eachModelAccountsForItsOwnStorage() throws Exception {
        write(ComponentModelStore.partFile(context, ComponentModelSpec.CHAT), 1000L);
        write(ComponentModelStore.partFile(context, ComponentModelSpec.ACTION), 250L);

        assertEquals(1000L, ComponentModelStore.totalModelBytes(context, ComponentModelSpec.CHAT));
        assertEquals(250L, ComponentModelStore.totalModelBytes(context, ComponentModelSpec.ACTION));
        assertEquals(1250L, ComponentModelStore.allModelBytes(context));
    }

    /**
     * The one that matters most.
     *
     * <p>Deleting the small model must leave the large one exactly where it was, byte for byte.
     */
    @Test public void deletingOneModelLeavesTheOtherCompletelyIntact() throws Exception {
        write(ComponentModelStore.modelFile(context, ComponentModelSpec.CHAT),
                ComponentModelSpec.CHAT.sizeBytes);
        write(ComponentModelStore.modelFile(context, ComponentModelSpec.ACTION),
                ComponentModelSpec.ACTION.sizeBytes);
        write(ComponentModelStore.partFile(context, ComponentModelSpec.ACTION), 64L);

        ComponentModelStore.delete(context, ComponentModelSpec.ACTION);

        assertFalse("the action model file is gone",
                ComponentModelStore.modelFile(context, ComponentModelSpec.ACTION).exists());
        assertFalse("and so are its partial bytes",
                ComponentModelStore.partFile(context, ComponentModelSpec.ACTION).exists());
        assertEquals(ComponentModelStore.State.NOT_INSTALLED,
                ComponentModelStore.state(context, ComponentModelSpec.ACTION));

        assertTrue("the chat model must be untouched",
                ComponentModelStore.modelFile(context, ComponentModelSpec.CHAT).exists());
        assertEquals(ComponentModelSpec.CHAT.sizeBytes,
                ComponentModelStore.modelFile(context, ComponentModelSpec.CHAT).length());
        assertTrue(ComponentModelStore.isReady(context, ComponentModelSpec.CHAT));
    }

    @Test public void deletingTheChatModelLeavesTheActionModelIntact() throws Exception {
        write(ComponentModelStore.modelFile(context, ComponentModelSpec.CHAT),
                ComponentModelSpec.CHAT.sizeBytes);
        write(ComponentModelStore.modelFile(context, ComponentModelSpec.ACTION),
                ComponentModelSpec.ACTION.sizeBytes);

        ComponentModelStore.delete(context, ComponentModelSpec.CHAT);

        assertFalse(ComponentModelStore.modelFile(context, ComponentModelSpec.CHAT).exists());
        assertTrue(ComponentModelStore.isReady(context, ComponentModelSpec.ACTION));
    }

    // ---- verification -------------------------------------------------------------------------------

    /** A file of the right size but the wrong contents is never promoted, for either model. */
    @Test public void theActionModelIsChecksumVerifiedLikeTheChatModel() throws Exception {
        File part = ComponentModelStore.partFile(context, ComponentModelSpec.ACTION);
        write(part, ComponentModelSpec.ACTION.sizeBytes);

        assertFalse("a size match is not an integrity match",
                ComponentModelStore.validateAndPromote(context, ComponentModelSpec.ACTION, part));
        assertEquals(ComponentModelStore.State.ERROR,
                ComponentModelStore.state(context, ComponentModelSpec.ACTION));
        assertFalse("and the bad bytes are removed", part.exists());
        assertFalse(ComponentModelStore.modelFile(context, ComponentModelSpec.ACTION).exists());
    }

    /** The download decision is per model, so a ready chat model never blocks the action download. */
    @Test public void theStartDecisionIsMadePerModel() {
        assertEquals(ComponentDownloadWorker.StartDecision.ENQUEUE,
                ComponentDownloadWorker.decideStart(false, false, true,
                        ComponentDownloadWorker.WorkState.NONE, false));
        assertEquals(ComponentDownloadWorker.StartDecision.IGNORED_READY,
                ComponentDownloadWorker.decideStart(true, false, true,
                        ComponentDownloadWorker.WorkState.NONE, false));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static void write(File file, long bytes) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{0});
        }
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(bytes);
        }
    }
}

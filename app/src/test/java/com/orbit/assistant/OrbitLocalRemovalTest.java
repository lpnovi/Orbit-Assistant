package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;

/**
 * The order in which Orbit Local is taken apart.
 *
 * <p>v0.7.7.5 had it backwards. {@code removeOrbitLocal} deleted the component's model, Orbit's own
 * legacy model, and the installer cache, and <em>then</em> asked Android to uninstall the package.
 * On a device where the uninstall never opened — which is exactly what happened — or where the
 * user backed out of it, that left a component still installed with its 1.6 GB model already
 * thrown away, and Orbit with no way to tell that apart from success.
 *
 * <p>These tests hold the corrected order: Android is asked first, the package manager's answer is
 * the only proof, and a package that is still installed means nothing at all was deleted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalRemovalTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        LocalModelStore.deleteLegacy(context);
        OrbitLocalInstaller.cleanup(context);
        OrbitLocalProvider.invalidateStatus();
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        LocalModelStore.deleteLegacy(context);
        OrbitLocalInstaller.cleanup(context);
        Prefs.get(context).edit().clear().commit();
    }

    // ---- the ordering decision -----------------------------------------------------------------

    /** A component that is still installed can only ever mean nothing happened. */
    @Test public void aStillInstalledComponentIsAlwaysACancellation() {
        assertEquals(LocalAiActivity.RemovalOutcome.CANCELLED,
                LocalAiActivity.removalOutcome("everything", true));
        assertEquals(LocalAiActivity.RemovalOutcome.CANCELLED,
                LocalAiActivity.removalOutcome("component", true));
    }

    @Test public void onlyAConfirmedAbsentPackageCountsAsRemoved() {
        assertEquals(LocalAiActivity.RemovalOutcome.REMOVED_EVERYTHING,
                LocalAiActivity.removalOutcome("everything", false));
        assertEquals(LocalAiActivity.RemovalOutcome.REMOVED_COMPONENT,
                LocalAiActivity.removalOutcome("component", false));
    }

    /** Without a request in flight, resuming this screen settles nothing and deletes nothing. */
    @Test public void nothingIsSettledWhenNoRemovalWasRequested() {
        assertEquals(LocalAiActivity.RemovalOutcome.NOTHING_PENDING,
                LocalAiActivity.removalOutcome("", false));
        assertEquals(LocalAiActivity.RemovalOutcome.NOTHING_PENDING,
                LocalAiActivity.removalOutcome(null, true));
    }

    /**
     * The two removals are different requests, and neither borrows the other's reach.
     *
     * <p>"Uninstall component" removes the component and the model Android takes with it. It must
     * not also delete a model an older Orbit still owns in Orbit's own sandbox — nobody asked it
     * to, and that model is the thing the migration path exists to rescue.
     */
    @Test public void uninstallingTheComponentDoesNotReachOrbitsOwnModelData() throws Exception {
        writeLegacyModelData();
        long before = LocalModelStore.legacyBytes(context);
        assertTrue(before > 0L);

        ActivityController<LocalAiActivity> controller = launchWithPendingRemoval("component");
        controller.pause().resume();

        assertEquals("a component uninstall must leave Orbit's own model data alone",
                before, LocalModelStore.legacyBytes(context));
        controller.destroy();
    }

    /** The global removal does reach it, because that is precisely what it says it does. */
    @Test public void removingOrbitLocalReachesOrbitsOwnModelData() throws Exception {
        writeLegacyModelData();
        assertTrue(LocalModelStore.legacyBytes(context) > 0L);

        ActivityController<LocalAiActivity> controller = launchWithPendingRemoval("everything");
        controller.pause().resume();

        assertEquals("Remove Orbit Local means everything Orbit Local uses",
                0L, LocalModelStore.legacyBytes(context));
        controller.destroy();
    }

    /**
     * The failure this patch exists for, from the other side.
     *
     * <p>Robolectric never has the component installed, so the "cancelled" path is exercised by
     * pinning the decision rather than the Activity — but the property is the one that matters: a
     * cancellation deletes nothing, and it is impossible to reach a deletion from it.
     */
    @Test public void aCancelledUninstallLeavesEveryByteWhereItWas() throws Exception {
        writeLegacyModelData();
        long legacyBefore = LocalModelStore.legacyBytes(context);
        File cached = new File(OrbitLocalInstaller.componentDirectory(context), "cached.apk");
        writeFile(cached, 128L);

        assertEquals(LocalAiActivity.RemovalOutcome.CANCELLED,
                LocalAiActivity.removalOutcome("everything", true));

        assertEquals("a cancelled removal must not touch model data",
                legacyBefore, LocalModelStore.legacyBytes(context));
        assertTrue("nor the downloaded installer that a retry would reuse", cached.isFile());
    }

    /** The pending marker survives the Activity being destroyed by Android's uninstall UI. */
    @Test public void thePendingRemovalSurvivesActivityRecreation() {
        Prefs.get(context).edit().putString("orbit_local_removal_pending", "everything").apply();
        assertEquals("everything", LocalAiActivity.pendingRemovalScope(context));

        ActivityController<LocalAiActivity> first = Robolectric.buildActivity(LocalAiActivity.class);
        first.create();
        first.destroy();

        // A field would have been lost with the Activity; a preference is not.
        assertEquals("everything", LocalAiActivity.pendingRemovalScope(context));
    }

    /** Once settled, the marker is cleared, so a later visit cannot re-run the cleanup. */
    @Test public void aSettledRemovalDoesNotSettleTwice() throws Exception {
        writeLegacyModelData();
        ActivityController<LocalAiActivity> controller = launchWithPendingRemoval("everything");
        controller.pause().resume();
        assertEquals("", LocalAiActivity.pendingRemovalScope(context));

        // Legacy data written again afterwards must not be swept by a stale pending removal.
        writeLegacyModelData();
        long restored = LocalModelStore.legacyBytes(context);
        controller.pause().resume();
        assertEquals(restored, LocalModelStore.legacyBytes(context));
        controller.destroy();
    }

    /** A confirmed removal hands the provider back to something that can actually answer. */
    @Test public void aConfirmedRemovalStopsOrbitLocalBeingTheActiveProvider() {
        ActivityController<LocalAiActivity> controller = launchWithPendingRemoval("everything");
        Prefs.get(context).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_LOCAL).commit();

        controller.pause().resume();

        assertFalse("a removed Orbit Local cannot remain the active provider",
                Prefs.PROVIDER_LOCAL.equals(Prefs.provider(context)));
        controller.destroy();
    }

    // ---- deleting only the model -----------------------------------------------------------------

    /**
     * Deleting the model is a different action with a different reach, and always has been.
     *
     * <p>It goes through the component's own service and never through Android's package
     * uninstaller, so the component stays installed and another model can be downloaded without
     * setting anything up again.
     */
    @Test public void deletingTheModelNeverAsksAndroidToRemoveAnything() {
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/LocalAiActivity.java");
        int deleteAction = source.indexOf("private void confirmDeleteModel");
        assertTrue("confirmDeleteModel was not found", deleteAction > 0);
        String body = source.substring(deleteAction,
                source.indexOf("// ---- shared button layout", deleteAction));
        assertTrue("the model is deleted through the component's own service",
                body.contains("OrbitLocalClient.deleteModel(this)"));
        assertFalse("deleting a model must never uninstall the component",
                body.contains("OrbitLocalUninstaller"));
        assertFalse(body.contains("removeOrbitLocal("));
    }

    /** And the component's own delete leaves the component installed and its engine unloaded. */
    @Test public void theComponentsDeleteUnloadsTheEngineAndKeepsTheComponent() {
        String source = ComponentUninstallTest.readRepositoryFile(
                "local/src/main/java/com/orbit/assistant/local/ComponentModelStore.java");
        int delete = source.indexOf("public static void delete(Context c, ComponentModelSpec spec)");
        assertTrue("ComponentModelStore.delete was not found", delete > 0);
        String body = source.substring(delete, source.indexOf("public static void clearError", delete));
        // Per slot on both counts since v0.7.8.0 Beta 1: deleting the action model must free the
        // action model's memory and stop the action model's download, and must leave a 1.6 GB chat
        // model loaded and downloading exactly as it was.
        assertTrue("a deleted model must not stay loaded in memory",
                body.contains("LocalLlmEngine.unload(spec.slot)"));
        assertTrue("and the in-flight download must stop",
                body.contains("ComponentDownloadWorker.cancel(c, spec)"));
        assertTrue("and only this model's files may be swept",
                body.contains("file.getName().startsWith(spec.fileName())"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * The screen, open, with a removal waiting on Android's answer.
     *
     * <p>The marker is set after the first resume so the caller's {@code pause().resume()} is the
     * one that settles it — the same shape as returning from Android's uninstall confirmation.
     */
    private ActivityController<LocalAiActivity> launchWithPendingRemoval(String scope) {
        ActivityController<LocalAiActivity> controller =
                Robolectric.buildActivity(LocalAiActivity.class);
        controller.setup();
        Prefs.get(context).edit().putString("orbit_local_removal_pending", scope).commit();
        return controller;
    }

    private void writeLegacyModelData() throws Exception {
        writeFile(new File(LocalModelStore.legacyModelDir(context), "leftover.part"), 4096L);
    }

    private static void writeFile(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }
}

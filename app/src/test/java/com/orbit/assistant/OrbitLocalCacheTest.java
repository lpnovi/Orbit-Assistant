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
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.TreeSet;

/**
 * What Orbit keeps in {@code cache/orbit-local/}, and what it is careful never to delete.
 *
 * <p>That directory is a handoff staging area, not an archive. Every Beta publishes a differently
 * named component APK, so without a policy a tester who follows three Betas ends up carrying three
 * 35 MB installers for components that can never be installed again — which is what a device
 * running v0.7.7.5-beta.2 actually accumulated.
 *
 * <p>The rules pull in two directions and both matter. Delete too eagerly and a cancelled install
 * costs another 35 MB download, or worse, the file Android is reading right now disappears
 * underneath it. Delete too little and the cache grows forever. So each rule is pinned here
 * separately, along with the two things component cleanup must never reach: the model, and every
 * other cache directory Orbit owns.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalCacheTest {

    private Context context;
    private File directory;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        directory = OrbitLocalInstaller.componentDirectory(context);
        OrbitLocalInstaller.cleanup(context);
        LocalModelStore.deleteLegacy(context);
    }

    // ---- after a confirmed installation ---------------------------------------------------------

    /**
     * The leak this release closes.
     *
     * <p>Once the package manager confirms the component, Android is storing that APK itself and
     * Orbit's downloaded copy is pure duplication.
     */
    @Test public void aConfirmedInstallationClearsTheDownloadedInstaller() {
        File installer = write(currentAssetName(), 35_000_000L);
        assertTrue(installer.isFile());

        OrbitLocalInstaller.cleanupAfterInstall(context);

        assertFalse("the installer must not survive the installation it performed",
                installer.exists());
        assertEquals("nothing at all is left staged", 0, remaining().size());
    }

    @Test public void aConfirmedInstallationAlsoClearsInterruptedDownloads() {
        write(currentAssetName() + ".part", 4096L);
        write("Orbit-Local-v0.7.7.5-beta.1.apk", 1024L);

        OrbitLocalInstaller.cleanupAfterInstall(context);

        assertEquals(0, remaining().size());
    }

    // ---- pruning between attempts ----------------------------------------------------------------

    /** An installer built for another Orbit release can never be installed by this one. */
    @Test public void installersFromOtherOrbitVersionsArePruned() {
        write("Orbit-Local-v0.7.7.5-beta.1.apk", 35_000_000L);
        write("Orbit-Local-v0.7.7.5-beta.2.apk", 35_000_000L);
        write("Orbit-Local-v0.7.7.4.apk", 35_000_000L);
        File current = write(currentAssetName(), 35_000_000L);

        OrbitLocalInstaller.prune(context, null);

        assertEquals("only this release's component may remain",
                new TreeSet<>(java.util.Collections.singletonList(currentAssetName())), remaining());
        assertTrue(current.isFile());
    }

    /** A half-finished download is restarted from zero, so keeping it buys nothing. */
    @Test public void staleFragmentsArePruned() {
        write(currentAssetName() + ".part", 12_000_000L);
        write("Orbit-Local-v0.7.7.5-beta.1.apk.part", 900L);

        OrbitLocalInstaller.prune(context, null);

        assertEquals("no .part file survives a prune", 0, remaining().size());
    }

    /**
     * A cancelled install keeps its verified download, so Retry does not cost 35 MB again.
     *
     * <p>Orbit reports the component as still missing in that case — the package manager is what
     * decides that — but the file it would hand over next time is already on disk and verified.
     */
    @Test public void thisVersionsVerifiedInstallerSurvivesACancelledInstall() {
        File installer = write(currentAssetName(), 35_000_000L);

        OrbitLocalInstaller.prune(context, null);

        assertTrue("cancelling an install must not force a second download",
                installer.isFile());
    }

    /** The file currently being handed to Android is never removed, whatever else happens. */
    @Test public void theInstallerInUseIsNeverRemoved() {
        File inUse = write(currentAssetName(), 35_000_000L);
        inUse.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L);
        write("Orbit-Local-v0.7.7.5-beta.1.apk", 1024L);

        OrbitLocalInstaller.prune(context, inUse);

        assertTrue("the file Android is reading must survive even when it looks abandoned",
                inUse.isFile());
        assertEquals(new TreeSet<>(java.util.Collections.singletonList(currentAssetName())),
                remaining());
    }

    /** Kept for retries, not kept forever. */
    @Test public void anInstallerNobodyUsedIsEventuallyAbandoned() {
        File installer = write(currentAssetName(), 35_000_000L);
        installer.setLastModified(System.currentTimeMillis() - 49L * 60 * 60 * 1000L);

        OrbitLocalInstaller.prune(context, null);

        assertFalse("an installer left untouched for two days is abandoned", installer.exists());
    }

    @Test public void aRecentInstallerIsNotAbandonedYet() {
        File installer = write(currentAssetName(), 35_000_000L);
        installer.setLastModified(System.currentTimeMillis() - 60L * 60 * 1000L);

        OrbitLocalInstaller.prune(context, null);

        assertTrue(installer.isFile());
    }

    @Test public void pruningAnEmptyCacheIsHarmless() {
        OrbitLocalInstaller.prune(context, null);
        OrbitLocalInstaller.cleanupAfterInstall(context);
        assertEquals(0, remaining().size());
    }

    // ---- what component cleanup must never touch ---------------------------------------------------

    /**
     * The 1.6 GB model is not an installer artifact.
     *
     * <p>It lives outside this directory precisely so that clearing installer staging can never
     * cost the user a multi-gigabyte re-download.
     */
    @Test public void modelStorageIsNeverTouchedByComponentCleanup() throws Exception {
        writeAt(LocalModelStore.legacyModelFile(context), LocalModelStore.MODEL_SIZE_BYTES);
        assertTrue(LocalModelStore.hasLegacyModel(context));

        write(currentAssetName(), 35_000_000L);
        OrbitLocalInstaller.prune(context, null);
        OrbitLocalInstaller.cleanupAfterInstall(context);
        OrbitLocalInstaller.cleanup(context);

        assertTrue("component cleanup must never reach the model",
                LocalModelStore.hasLegacyModel(context));
        assertEquals(LocalModelStore.MODEL_SIZE_BYTES, LocalModelStore.legacyBytes(context));
    }

    /** Nothing outside {@code cache/orbit-local/} is cleaned by any of these. */
    @Test public void unrelatedCacheDirectoriesAreUntouched() throws Exception {
        File updates = new File(context.getCacheDir(), "updates");
        File camera = new File(context.getCacheDir(), "orbit_picker_camera");
        File pendingUpdate = new File(updates, "Orbit-Assistant-v0.7.7.5-beta.3.apk");
        File photo = new File(camera, "capture.jpg");
        writeAt(pendingUpdate, 2048L);
        writeAt(photo, 512L);

        write(currentAssetName(), 35_000_000L);
        OrbitLocalInstaller.prune(context, null);
        OrbitLocalInstaller.cleanupAfterInstall(context);

        assertTrue("the pending Orbit update installer belongs to the updater",
                pendingUpdate.isFile());
        assertTrue(photo.isFile());
        assertEquals(0, remaining().size());
    }

    /** Cleanup empties the directory rather than removing it: the next download needs it. */
    @Test public void theStagingDirectorySurvivesCleanup() {
        write(currentAssetName(), 1024L);
        OrbitLocalInstaller.cleanupAfterInstall(context);
        assertTrue(directory.isDirectory());
        assertEquals(directory, OrbitLocalInstaller.componentDirectory(context));
    }

    // ---- the name the policy is built on -------------------------------------------------------------

    /** The asset name is version-specific, which is why old ones must be pruned at all. */
    @Test public void eachReleaseHasItsOwnComponentAssetName() {
        assertEquals("Orbit-Local-v" + BuildConfig.VERSION_NAME + ".apk",
                OrbitLocalInstaller.assetName());
        assertFalse("beta.2's asset is not this build's",
                "Orbit-Local-v0.7.7.5-beta.2.apk".equals(OrbitLocalInstaller.assetName()));
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static String currentAssetName() {
        return OrbitLocalInstaller.assetName();
    }

    private File write(String name, long length) {
        File file = new File(directory, name);
        try {
            writeAt(file, length);
        } catch (Exception e) {
            throw new AssertionError("could not stage " + name, e);
        }
        return file;
    }

    /** A sparse file of the exact length, so a 35 MB fixture costs nothing on disk. */
    private static void writeAt(File file, long length) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(length);
        }
    }

    private TreeSet<String> remaining() {
        String[] names = directory.list();
        return names == null ? new TreeSet<>() : new TreeSet<>(Arrays.asList(names));
    }
}

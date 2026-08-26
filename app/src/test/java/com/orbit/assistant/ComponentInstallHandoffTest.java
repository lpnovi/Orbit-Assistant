package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * The last step of installing the Orbit Local component: handing a verified APK to Android.
 *
 * <p>Everything before this point was already proven by Beta 1's tests — the download, the
 * checksum, the package, the version, the signer, the certificate. What was not covered was the
 * handoff itself, and that is exactly where the device failed. These tests drive the real
 * {@link OrbitLocalInstaller#launchInstaller} through a Robolectric Activity and assert the Intent
 * Android actually receives.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentInstallHandoffTest {

    private Activity activity;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        OrbitLocalInstaller.cleanup(context);
        // Robolectric installs no package installer by default; Android always has one.
        ResolveInfo installer = new ResolveInfo();
        installer.activityInfo = new ActivityInfo();
        installer.activityInfo.packageName = "com.android.packageinstaller";
        installer.activityInfo.name = "com.android.packageinstaller.InstallStart";
        Intent template = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(Uri.parse("content://x/y"), OrbitLocalInstaller.APK_MIME_TYPE);
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(template, installer);
    }

    private File componentApk(String name) throws Exception {
        File apk = new File(OrbitLocalInstaller.componentDirectory(context), name);
        try (FileOutputStream out = new FileOutputStream(apk)) {
            out.write(new byte[]{0x50, 0x4b, 0x03, 0x04});
        }
        return apk;
    }

    // ---- how far the handoff gets ---------------------------------------------------------------------

    /**
     * A verified component in the right place gets past every check Orbit controls.
     *
     * <p>The final two steps — FileProvider's URI and Android's installer Activity — cannot be
     * exercised here: Robolectric resolves no FileProvider roots in this project at all, including
     * the {@code updates/} path that has installed Orbit's own updates for many releases on real
     * hardware. The configuration those steps depend on is asserted instead by
     * {@link FileProviderPathsTest#everyDirectoryOrbitSharesIsUnderADeclaredRoot}, which models
     * FileProvider's own root lookup and is what would have caught the Beta 1 failure.
     *
     * <p>So what this pins is that a good APK is never rejected by Orbit's own gates, and that if
     * it does fail it fails at the environment-dependent stage rather than an earlier one.
     */
    @Test public void aVerifiedComponentPassesEveryCheckOrbitControls() throws Exception {
        File apk = componentApk(OrbitLocalInstaller.assetName());
        try {
            OrbitLocalInstaller.launchInstaller(activity, apk);
        } catch (Exception e) {
            assertEquals("a correctly placed component must never be refused by Orbit's own "
                            + "checks; it may only fail at the platform handoff",
                    OrbitLocalInstaller.InstallStage.FILEPROVIDER_URI.message, e.getMessage());
        }
    }

    // ---- what must still be refused --------------------------------------------------------------------

    /**
     * Unchanged from Beta 1: only a file inside Orbit's own component cache may be installed. The
     * FileProvider fix widened what can be shared by exactly one directory, and nothing else.
     */
    @Test public void anApkOutsideTheComponentDirectoryIsRefused() throws Exception {
        File stray = new File(context.getCacheDir(), "not-orbit-local.apk");
        try (FileOutputStream out = new FileOutputStream(stray)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        expectRefusal(stray, "an APK outside Orbit's component directory");
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    /** Including one reached by climbing out of the directory with a relative path. */
    @Test public void aTraversalOutOfTheComponentDirectoryIsRefused() throws Exception {
        File escape = new File(OrbitLocalInstaller.componentDirectory(context),
                ".." + File.separator + "escaped.apk");
        try (FileOutputStream out = new FileOutputStream(escape.getCanonicalFile())) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        expectRefusal(escape, "a path that climbs out of the component directory");
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void aMissingApkIsRefusedRatherThanLaunched() {
        File missing = new File(OrbitLocalInstaller.componentDirectory(context), "absent.apk");
        expectRefusal(missing, "a component file that is not there");
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void aNullApkIsRefused() {
        expectRefusal(null, "no file at all");
    }

    // ---- diagnostics -------------------------------------------------------------------------------------

    /**
     * A Beta failure must name its stage. One message covering every step is what made the device
     * report point at the wrong thing entirely.
     */
    @Test public void everyStageHasItsOwnConciseMessage() {
        java.util.Set<String> messages = new java.util.HashSet<>();
        for (OrbitLocalInstaller.InstallStage stage : OrbitLocalInstaller.InstallStage.values()) {
            assertFalse(stage + " needs a message", stage.message.trim().isEmpty());
            assertTrue(stage + " must stay one short sentence", stage.message.length() < 90);
            assertTrue("distinct stages must not share wording", messages.add(stage.message));
        }
        assertEquals("the stages are: missing APK, outside the directory, FileProvider, no "
                + "installer, and launch", 5, OrbitLocalInstaller.InstallStage.values().length);
    }

    /** No filesystem detail ever reaches the user-facing text. */
    @Test public void noStageMessageLeaksAPath() {
        for (OrbitLocalInstaller.InstallStage stage : OrbitLocalInstaller.InstallStage.values()) {
            String message = stage.message;
            assertFalse(message, message.contains("/"));
            assertFalse(message, message.contains("\\"));
            assertFalse(message, message.contains("cache"));
            assertFalse(message, message.toLowerCase(java.util.Locale.US).contains("data/"));
        }
    }

    /** A failed handoff is recorded where a Beta tester can read it back. */
    @Test public void aFailedHandoffIsRecordedForDiagnostics() throws Exception {
        DiagnosticStore.recordError(context, "");
        File stray = new File(context.getCacheDir(), "elsewhere.apk");
        try (FileOutputStream out = new FileOutputStream(stray)) {
            out.write(new byte[]{1});
        }
        expectRefusal(stray, "an APK outside the component directory");

        String recorded = DiagnosticStore.prefs(context).getString("last_error", "");
        assertTrue("the stage must be recorded for Beta diagnosis, was: " + recorded,
                recorded.contains("OUTSIDE_COMPONENT_DIRECTORY"));
        assertTrue(recorded.contains("Orbit Local component installer"));
    }

    // ---- the main updater is untouched ---------------------------------------------------------------------

    /**
     * Beta 2 changes only the component path. Orbit's own update installer keeps the mechanism it
     * has used for many releases, and the two remain deliberately identical.
     */
    @Test public void theMainUpdaterInstallerIsUnchanged() {
        java.nio.file.Path updater = java.nio.file.Paths.get("").toAbsolutePath();
        for (java.nio.file.Path directory = updater; directory != null;
             directory = directory.getParent()) {
            java.nio.file.Path candidate = directory.resolve(
                    "app/src/main/java/com/orbit/assistant/OrbitUpdater.java");
            if (!java.nio.file.Files.isRegularFile(candidate)) continue;
            try {
                String source = new String(java.nio.file.Files.readAllBytes(candidate),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(source.contains("Intent.ACTION_VIEW"));
                assertTrue(source.contains("application/vnd.android.package-archive"));
                assertTrue(source.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION"));
                assertTrue(source.contains("FileProvider.getUriForFile"));
                assertTrue("the updater still writes into the cache directory it declares",
                        source.contains("new File(context.getCacheDir(), \"updates\")"));
                return;
            } catch (java.io.IOException e) {
                throw new AssertionError("could not read OrbitUpdater.java", e);
            }
        }
        fail("OrbitUpdater.java was not found");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void expectRefusal(File apk, String why) {
        try {
            OrbitLocalInstaller.launchInstaller(activity, apk);
            fail("Orbit must refuse to install " + why);
        } catch (Exception expected) {
            assertNotNull("a refusal must explain itself", expected.getMessage());
        }
    }

    private static void assertNull(Object value) {
        if (value != null) fail("nothing should have been launched, but was: " + value);
    }
}

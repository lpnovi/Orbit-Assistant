package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Asking Android to remove the Orbit Local component.
 *
 * <p>Written against a Galaxy S25 Ultra recording of v0.7.7.5 where "Remove Orbit Local" did
 * nothing whatsoever: Orbit's confirmation appeared, the user confirmed, Orbit's dialog closed,
 * and Android's uninstall confirmation never came. The package stayed, and Orbit said nothing.
 *
 * <p>The cause was not a bug in Orbit's logic. Orbit did not hold {@code REQUEST_DELETE_PACKAGES},
 * which Android has required of any app targeting API 28 or later since Android 9 — Orbit targets
 * 35. The platform's uninstaller logs the refusal and finishes without drawing anything, so
 * {@code startActivity} returned normally and told the caller nothing, and the old implementation
 * wrapped the whole thing in a catch that discarded even the exceptions it did get.
 *
 * <p>Robolectric cannot render Samsung's uninstall dialog, and nothing here pretends otherwise.
 * What it can do is pin the two halves that were actually wrong: the manifest declaration the
 * platform checks, and the fact that a failure to launch now produces a reportable outcome instead
 * of silence. The dialog itself stays a real-device check.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ComponentUninstallTest {

    private Activity activity;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        DiagnosticStore.prefs(context).edit().clear().commit();
    }

    // ---- the manifest contract Android actually enforces ------------------------------------------

    /**
     * The single line whose absence made the button do nothing.
     *
     * <p>Normal protection level, so declaring it is the whole requirement — there is no runtime
     * prompt to forget. Asserted against the manifest source rather than the runtime, because the
     * declaration is what the platform reads before it will draw its confirmation.
     */
    @Test public void theManifestDeclaresThePackageDeletePermission() {
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");
        assertTrue("Android refuses an uninstall request from an app targeting API 28+ that does "
                        + "not hold REQUEST_DELETE_PACKAGES, and it refuses it silently. Without "
                        + "this line, Remove Orbit Local does nothing at all.",
                manifest.contains("android.permission.REQUEST_DELETE_PACKAGES"));
    }

    /** And Orbit targets an SDK high enough for that requirement to apply. */
    @Test public void orbitTargetsAnSdkWhereThePermissionIsRequired() {
        String gradle = readRepositoryFile("app/build.gradle");
        int target = -1;
        for (String line : gradle.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("targetSdk ")) {
                target = Integer.parseInt(trimmed.substring("targetSdk ".length()).trim());
                break;
            }
        }
        assertTrue("targetSdk was not found in app/build.gradle", target > 0);
        assertTrue("REQUEST_DELETE_PACKAGES is required from API 28; this assertion exists so the "
                        + "manifest declaration is never dropped as 'unnecessary'", target >= 28);
    }

    /** Orbit holds it at runtime, which is what the platform checks on the way in. */
    @Test public void orbitHoldsThePermissionAtRuntime() {
        assertTrue("the declared permission must actually be granted to this package",
                OrbitLocalUninstaller.canRequestUninstall(context));
    }

    @Test public void theReceiverThatReadsAndroidsAnswerIsRegistered() {
        String manifest = readRepositoryFile("app/src/main/AndroidManifest.xml");
        assertTrue("without the receiver, Android's uninstall result reaches nobody",
                manifest.contains("<receiver android:name=\".OrbitLocalUninstallReceiver\""));
        assertTrue("it is reached through an explicit PendingIntent, so it needs no exported surface",
                manifest.contains("<receiver android:name=\".OrbitLocalUninstallReceiver\" "
                        + "android:exported=\"false\" />"));
    }

    // ---- exactly one package, ever ------------------------------------------------------------------

    /**
     * The target is a constant, not a parameter.
     *
     * <p>There is no call shape that could aim this at another app, which is a stronger guarantee
     * than validating a package name would be.
     */
    @Test public void nothingButTheComponentCanEverBeTargeted() {
        String source = readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitLocalUninstaller.java");
        assertTrue("the uninstall must name the component constant",
                source.contains("installer.uninstall(OrbitLocalComponent.PACKAGE"));
        assertFalse("no caller may choose the package being removed",
                source.contains("request(Activity activity, String packageName"));
        assertEquals("com.orbit.assistant.local", OrbitLocalComponent.PACKAGE);
    }

    /** The receiver ignores a status about anything that is not the component. */
    @Test public void aStatusForAnotherPackageIsIgnored() {
        Intent status = new Intent(OrbitLocalUninstaller.ACTION_UNINSTALL_STATUS)
                .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "com.example.other")
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
        new OrbitLocalUninstallReceiver().onReceive(context, status);
        assertEquals("a success for someone else's package must not be recorded as ours",
                "", DiagnosticStore.lastComponentUninstallStage(context));
    }

    // ---- outcomes are distinguishable, and never silent ---------------------------------------------

    /** With nothing installed there is nothing to ask about, and that is said rather than assumed. */
    @Test public void requestingRemovalOfAMissingComponentReportsItAsMissing() {
        assertFalse(OrbitLocalComponent.isInstalled(context));
        assertEquals(OrbitLocalUninstaller.Launch.NOT_INSTALLED,
                OrbitLocalUninstaller.request(activity));
        assertEquals(OrbitLocalUninstaller.Stage.NOT_INSTALLED.name(),
                DiagnosticStore.lastComponentUninstallStage(context));
    }

    /** Every outcome the user could hit produces a sentence, and none of them is empty. */
    @Test public void everyReportableOutcomeHasSomethingToSay() {
        OrbitLocalUninstaller.Stage[] reportable = {
                OrbitLocalUninstaller.Stage.SUCCEEDED,
                OrbitLocalUninstaller.Stage.CANCELLED,
                OrbitLocalUninstaller.Stage.REFUSED,
                OrbitLocalUninstaller.Stage.CONFIRM_MISSING,
                OrbitLocalUninstaller.Stage.NOT_LAUNCHED,
                OrbitLocalUninstaller.Stage.NOT_INSTALLED,
        };
        for (OrbitLocalUninstaller.Stage stage : reportable) {
            assertFalse("v0.7.7.5 said nothing at all for " + stage + "; that is the bug",
                    OrbitLocalUninstaller.message(stage).isEmpty());
        }
        assertTrue("a failure to launch must tell the user nothing was deleted",
                OrbitLocalUninstaller.message(OrbitLocalUninstaller.Stage.NOT_LAUNCHED)
                        .contains("Nothing was deleted"));
        assertTrue("and must offer the way round it",
                OrbitLocalUninstaller.message(OrbitLocalUninstaller.Stage.NOT_LAUNCHED)
                        .contains("Android Settings"));
    }

    /** A cancellation and a success are two different recorded answers, not one silence. */
    @Test public void cancellationAndSuccessAreRecordedDistinctly() {
        new OrbitLocalUninstallReceiver().onReceive(context, statusFor(
                PackageInstaller.STATUS_FAILURE_ABORTED));
        assertEquals(OrbitLocalUninstaller.Stage.CANCELLED.name(),
                DiagnosticStore.lastComponentUninstallStage(context));

        new OrbitLocalUninstallReceiver().onReceive(context, statusFor(
                PackageInstaller.STATUS_SUCCESS));
        assertEquals(OrbitLocalUninstaller.Stage.SUCCEEDED.name(),
                DiagnosticStore.lastComponentUninstallStage(context));
    }

    /** An Android-side refusal is its own answer too, and not confused with the user backing out. */
    @Test public void anAndroidRefusalIsRecordedAsARefusal() {
        new OrbitLocalUninstallReceiver().onReceive(context, statusFor(
                PackageInstaller.STATUS_FAILURE_BLOCKED));
        assertEquals(OrbitLocalUninstaller.Stage.REFUSED.name(),
                DiagnosticStore.lastComponentUninstallStage(context));
    }

    /**
     * The step that puts Samsung's dialog on screen.
     *
     * <p>Android reports STATUS_PENDING_USER_ACTION and hands back the Intent to show; showing it
     * is the receiver's job. A missing Intent is the one case that cannot show anything, and it is
     * recorded rather than passed over — a confirmation that cannot be shown is a removal that
     * cannot happen.
     */
    @Test public void aPendingConfirmationWithNoIntentIsRecordedRatherThanIgnored() {
        Intent status = statusFor(PackageInstaller.STATUS_PENDING_USER_ACTION);
        new OrbitLocalUninstallReceiver().onReceive(context, status);
        assertEquals(OrbitLocalUninstaller.Stage.CONFIRM_MISSING.name(),
                DiagnosticStore.lastComponentUninstallStage(context));
    }

    @Test public void aPendingConfirmationWithAnIntentIsOpened() {
        Intent confirm = new Intent("android.intent.action.UNINSTALL_CONFIRM");
        Intent status = statusFor(PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm);
        new OrbitLocalUninstallReceiver().onReceive(context, status);
        assertEquals(OrbitLocalUninstaller.Stage.CONFIRM_SHOWN.name(),
                DiagnosticStore.lastComponentUninstallStage(context));
        assertTrue("a broadcast has no task of its own to show an Activity in",
                (confirm.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    /** Diagnostics carry stage names, never anything about the user. */
    @Test public void diagnosticsRecordStagesAndNothingPrivate() {
        OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.NOT_LAUNCHED,
                new IllegalStateException("/data/user/0/com.orbit.assistant/files/secret"));
        String stage = DiagnosticStore.lastComponentUninstallStage(context);
        String detail = DiagnosticStore.prefs(context).getString("local_uninstall_detail", "");
        assertEquals(OrbitLocalUninstaller.Stage.NOT_LAUNCHED.name(), stage);
        assertEquals("only the exception's class name is kept",
                "IllegalStateException", detail);
        assertFalse("no path may ever reach diagnostics", detail.contains("/data/"));
    }

    /** The package manager is the only authority on whether the component is really gone. */
    @Test public void confirmedRemovalIsReadFromThePackageManager() {
        assertTrue("Robolectric has no component installed, so it must read as removed",
                OrbitLocalUninstaller.confirmedRemoved(context));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static Intent statusFor(int status) {
        return new Intent(OrbitLocalUninstaller.ACTION_UNINSTALL_STATUS)
                .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, OrbitLocalComponent.PACKAGE)
                .putExtra(PackageInstaller.EXTRA_STATUS, status);
    }

    static String readRepositoryFile(String relativePath) {
        Path start = Paths.get("").toAbsolutePath();
        Path root = null;
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) {
                root = directory;
                break;
            }
        }
        assertNotNull("repository root was not found above " + start, root);
        Path file = root.resolve(relativePath);
        assertTrue("expected file is missing: " + relativePath, Files.isRegularFile(file));
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }
}

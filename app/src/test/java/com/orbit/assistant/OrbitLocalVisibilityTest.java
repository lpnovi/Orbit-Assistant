package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Whether Orbit can see its own optional component, and whether it is allowed to speak to it.
 *
 * <p>This is the test that would have caught the v0.7.7.5-beta.2 device failure. Android installed
 * {@code com.orbit.assistant.local} and reported "Install success"; Orbit went on showing "Not
 * installed", because on a modern Android {@code getPackageInfo} for a package Orbit never declared
 * an interest in throws {@code NameNotFoundException} whether or not that package exists. Nothing
 * in the code was wrong. The manifest simply never asked to see it.
 *
 * <p>Two declarations carry that, and neither is visible from Java, so both are asserted against
 * the real manifests: the {@code <package>} query that lets Orbit observe the component, and the
 * signature-level {@code <uses-permission>} that lets Orbit bind its service. Getting only the
 * first would fix the card and leave every IPC call failing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalVisibilityTest {

    /** The permission the component declares at signature level, and Orbit must request. */
    private static final String BIND_PERMISSION = "com.orbit.assistant.permission.BIND_ORBIT_LOCAL";

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitLocalProvider.invalidateStatus();
    }

    // ---- the manifest declarations --------------------------------------------------------------

    /** The fix itself: Orbit names the component package, exactly and explicitly. */
    @Test public void theComponentPackageIsDeclaredVisible() {
        assertTrue("Orbit's manifest must declare <package android:name=\""
                        + OrbitLocalComponent.PACKAGE + "\" /> inside <queries>, or the component "
                        + "stays invisible to getPackageInfo even once Android has installed it",
                queriesBlock(mainManifest()).contains(
                        "<package android:name=\"" + OrbitLocalComponent.PACKAGE + "\" />"));
    }

    /** The declaration names the package the code actually looks for, not a copied string. */
    @Test public void theDeclaredPackageIsTheOneOrbitQueries() {
        String declared = declaredQueryPackages(mainManifest()).toString();
        assertTrue("the manifest declares " + declared + " but OrbitLocalComponent looks for "
                        + OrbitLocalComponent.PACKAGE,
                declaredQueryPackages(mainManifest()).contains(OrbitLocalComponent.PACKAGE));
    }

    /**
     * Visibility is granted for one known package, never for the device.
     *
     * <p>{@code QUERY_ALL_PACKAGES} would fix the same bug by letting Orbit enumerate every app the
     * user has installed. Orbit needs to see its own optional component and nothing else.
     */
    @Test public void orbitNeverAsksToSeeEveryPackage() {
        assertFalse("QUERY_ALL_PACKAGES must never be introduced",
                mainManifest().contains("QUERY_ALL_PACKAGES"));
        assertFalse("and not in the component either",
                componentManifest().contains("QUERY_ALL_PACKAGES"));
    }

    /** Exactly one package is named. A second one needs a reason and a test of its own. */
    @Test public void exactlyOnePackageIsNamed() {
        assertEquals("Orbit declares visibility of its own component and nothing else",
                java.util.Collections.singletonList(OrbitLocalComponent.PACKAGE),
                declaredQueryPackages(mainManifest()));
    }

    /** Every pre-existing intent query survives. Visibility was added, not swapped in. */
    @Test public void theExistingIntentQueriesAreUntouched() {
        String queries = queriesBlock(mainManifest());
        for (String action : new String[]{"android.speech.RecognitionService",
                "android.intent.action.MAIN", "android.intent.action.PICK",
                "android.intent.action.GET_CONTENT"}) {
            assertTrue("the " + action + " query must be preserved", queries.contains(action));
        }
        assertEquals("all four intent queries remain", 4, queries.split("<intent>", -1).length - 1);
    }

    // ---- being allowed to bind ---------------------------------------------------------------------

    /**
     * Seeing the package is not permission to use it.
     *
     * <p>The component's service is protected by a signature-level permission, so a bind from an
     * app that has not requested it is refused by Android before the service is ever reached.
     */
    @Test public void orbitRequestsTheComponentsBindPermission() {
        assertTrue("Orbit must request " + BIND_PERMISSION + " or bindService is refused",
                mainManifest().contains(
                        "<uses-permission android:name=\"" + BIND_PERMISSION + "\" />"));
    }

    /** The component declares that permission at signature level, and protects the service with it. */
    @Test public void theComponentProtectsItsServiceWithThatPermission() {
        String manifest = componentManifest();
        assertTrue(manifest.contains("android:name=\"" + BIND_PERMISSION + "\""));
        assertTrue("signature level is what ties it to Orbit's certificate",
                manifest.contains("android:protectionLevel=\"signature\""));
        assertTrue("and the service must actually be protected by it",
                manifest.contains("android:permission=\"" + BIND_PERMISSION + "\""));
    }

    /** The component can read Orbit's certificate back, which is its half of the trust check. */
    @Test public void theComponentCanSeeOrbitInReturn() {
        assertTrue("OrbitLocalService verifies its caller through the package manager, so it must "
                        + "declare visibility of Orbit rather than rely on the implicit grant",
                declaredQueryPackages(componentManifest()).contains("com.orbit.assistant"));
    }

    /** The service Orbit binds to is the one the component publishes, by action and package. */
    @Test public void theBindContractMatchesOnBothSides() {
        assertTrue("the component must publish " + OrbitLocalComponent.BIND_ACTION,
                componentManifest().contains(
                        "<action android:name=\"" + OrbitLocalComponent.BIND_ACTION + "\" />"));
        assertTrue("and export the service so Orbit can reach it",
                componentManifest().contains("android:exported=\"true\""));

        Intent intent = new Intent(OrbitLocalComponent.BIND_ACTION)
                .setPackage(OrbitLocalComponent.PACKAGE);
        assertEquals("Orbit binds by action, scoped to the component package",
                OrbitLocalComponent.PACKAGE, intent.getPackage());
        assertEquals(OrbitLocalComponent.BIND_ACTION, intent.getAction());
    }

    /** The component is a separate package, never a second copy of Orbit's own. */
    @Test public void theComponentIsItsOwnPackage() {
        assertNotEquals(context.getPackageName(), OrbitLocalComponent.PACKAGE);
        assertTrue(OrbitLocalComponent.PACKAGE.startsWith(context.getPackageName() + "."));
    }

    /** And still has no launcher icon: Orbit Local is a component, not a second app. */
    @Test public void theComponentStillHasNoLauncherEntry() {
        String manifest = componentManifest();
        assertFalse("the component must never appear in the launcher",
                manifest.contains("android.intent.category.LAUNCHER"));
        assertFalse(manifest.contains("<activity"));
    }

    // ---- what visibility does and does not decide ---------------------------------------------------

    /**
     * Visibility is not trust, asserted through the real package manager.
     *
     * <p>Now that Orbit can see any package with the component's name, the certificate check is the
     * only thing standing between it and a hostile package that simply chose that name. An
     * installed package Orbit cannot verify reads as UNTRUSTED, never as INSTALLED, and
     * {@code isUsable} stays false.
     */
    @Test public void aVisiblePackageWithTheRightNameIsStillNotTrusted() {
        installFakeComponent();

        assertTrue("the package is now observable, which is the whole point of the manifest change",
                OrbitLocalComponent.isInstalled(context));
        assertEquals("but an unverifiable signer can only ever read as untrusted",
                OrbitLocalComponent.State.UNTRUSTED, OrbitLocalComponent.state(context));
        assertFalse(OrbitLocalComponent.isUsable(context));
        assertEquals(OrbitLocalClient.UNTRUSTED, OrbitLocalClient.unavailableReason(context));
        assertFalse("and Orbit Local must not become selectable because of it",
                AiProviders.byId(Prefs.PROVIDER_LOCAL).selectable(context));
    }

    /** With nothing installed, the same query reports exactly that. */
    @Test public void anAbsentPackageReadsAsNotInstalled() {
        assertFalse(OrbitLocalComponent.isInstalled(context));
        assertEquals(OrbitLocalComponent.State.NOT_INSTALLED, OrbitLocalComponent.state(context));
        assertEquals("", OrbitLocalComponent.installedVersionName(context));
        assertEquals(0L, OrbitLocalComponent.installedVersionCode(context));
        assertEquals(0L, OrbitLocalComponent.installedApkBytes(context));
        assertEquals(OrbitLocalClient.NOT_INSTALLED, OrbitLocalClient.unavailableReason(context));
    }

    /** The observed version is read back from the package, not from what Orbit hoped it installed. */
    @Test public void theObservedVersionComesFromThePackage() {
        installFakeComponent();
        assertEquals("0.0.1-not-orbit", OrbitLocalComponent.installedVersionName(context));
        assertEquals(1L, OrbitLocalComponent.installedVersionCode(context));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /**
     * A package that merely calls itself Orbit Local.
     *
     * <p>Robolectric cannot fabricate a {@link android.content.pm.SigningInfo}, which is exactly
     * the shape of the threat: a package with the right name and no provable Orbit signature.
     */
    private void installFakeComponent() {
        PackageInfo info = new PackageInfo();
        info.packageName = OrbitLocalComponent.PACKAGE;
        info.versionName = "0.0.1-not-orbit";
        info.setLongVersionCode(1L);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = OrbitLocalComponent.PACKAGE;
        ShadowPackageManager shadow = Shadows.shadowOf(context.getPackageManager());
        shadow.installPackage(info);
    }

    private static String mainManifest() {
        return declarations(read("app/src/main/AndroidManifest.xml"));
    }

    private static String componentManifest() {
        return declarations(read("local/src/main/AndroidManifest.xml"));
    }

    /**
     * The manifest with its comments removed.
     *
     * <p>Both manifests explain in prose why {@code QUERY_ALL_PACKAGES} is deliberately not used,
     * and a test that searched the raw text would read that explanation as the thing it forbids.
     * Only what Android actually parses is asserted here.
     */
    private static String declarations(String manifest) {
        return manifest.replaceAll("(?s)<!--.*?-->", "");
    }

    /** The text between &lt;queries&gt; and &lt;/queries&gt;, or "" when there is no such block. */
    private static String queriesBlock(String manifest) {
        int start = manifest.indexOf("<queries>");
        int end = manifest.indexOf("</queries>");
        return start < 0 || end < start ? "" : manifest.substring(start, end);
    }

    /** Every package named by a &lt;package&gt; entry inside &lt;queries&gt;. */
    private static java.util.List<String> declaredQueryPackages(String manifest) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String block = queriesBlock(manifest);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<package\\s+android:name=\"([^\"]+)\"")
                .matcher(block);
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static String read(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        if (!Files.isRegularFile(file)) fail("expected file is missing: " + relativePath);
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) return directory;
        }
        throw new AssertionError("repository root was not found above " + start);
    }
}

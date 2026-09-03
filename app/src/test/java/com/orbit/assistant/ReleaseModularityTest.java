package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The guard that keeps Orbit Local optional.
 *
 * <p>The entire point of v0.7.7.5 is that people who never use local AI stop paying for its
 * inference runtime — tens of megabytes of native library in every single install. That property
 * is invisible in normal use and trivially easy to destroy: one {@code implementation
 * "com.google.mediapipe:tasks-genai"} added to the wrong module, for one convenient import, and
 * every Orbit download quietly gets large again with nothing failing.
 *
 * <p>So it is asserted here rather than remembered. If this test fails, the main Orbit APK has
 * regained the on-device inference runtime and Orbit Local is no longer optional.
 */
public final class ReleaseModularityTest {

    private static final String RUNTIME_DEPENDENCY = "com.google.mediapipe:tasks-genai";
    private static final String RUNTIME_PACKAGE = "com.google.mediapipe.tasks.genai";

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) return directory;
        }
        throw new AssertionError("repository root was not found above " + start);
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

    // ---- the main app must not carry the runtime ------------------------------------------------

    @Test public void theMainAppDoesNotDependOnTheInferenceRuntime() {
        String gradle = read("app/build.gradle");
        boolean declared = false;
        for (String line : gradle.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;
            if (trimmed.contains(RUNTIME_DEPENDENCY)) declared = true;
        }
        assertFalse("The main Orbit package has accidentally regained the local inference runtime ("
                        + RUNTIME_DEPENDENCY + " in app/build.gradle). Orbit Local is no longer "
                        + "optional: every Orbit install would carry the MediaPipe/LiteRT native "
                        + "libraries again. Move the dependency back to the :local component.",
                declared);
    }

    /** Nothing in the main app may even import the runtime's classes. */
    @Test public void noMainAppSourceImportsTheInferenceRuntime() throws IOException {
        Path sources = repositoryRoot().resolve("app/src/main/java");
        try (java.util.stream.Stream<Path> files = Files.walk(sources)) {
            java.util.List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                                    .contains(RUNTIME_PACKAGE);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toList());
            assertTrue("The main Orbit package has accidentally regained the local inference "
                            + "runtime: " + offenders + " import " + RUNTIME_PACKAGE
                            + ". Inference belongs in the optional :local component.",
                    offenders.isEmpty());
        }
    }

    // ---- the component must carry it -------------------------------------------------------------

    @Test public void theComponentDoesDependOnTheInferenceRuntime() {
        assertTrue("The Orbit Local component must contain the inference runtime; without it "
                        + "there is nothing to run the model.",
                read("local/build.gradle").contains(RUNTIME_DEPENDENCY));
    }

    @Test public void theComponentIsARegisteredModule() {
        assertTrue("the optional component must be part of the build",
                read("settings.gradle").contains("include ':local'"));
    }

    // ---- the component is a component, not a second app --------------------------------------------

    /**
     * From a user's point of view there is only Orbit. The component may appear in Android's app
     * list — that is honest and unavoidable — but it must never look like a second assistant.
     */
    @Test public void theComponentHasNoLauncherPresence() {
        String manifest = read("local/src/main/AndroidManifest.xml");
        assertFalse("the component must have no launcher entry",
                manifest.contains("android.intent.category.LAUNCHER"));
        assertFalse("the component must have no Activity at all",
                manifest.contains("<activity"));
        assertFalse("the component must not register as an assistant",
                manifest.contains("VoiceInteractionService"));
        assertFalse("the component must not declare a launcher icon",
                manifest.contains("android:icon"));
    }

    /** Its one exported surface is the service, and it is permission-guarded. */
    @Test public void theComponentServiceIsGuardedBySignaturePermission() {
        String manifest = read("local/src/main/AndroidManifest.xml");
        assertTrue("the bind permission must exist",
                manifest.contains("com.orbit.assistant.permission.BIND_ORBIT_LOCAL"));
        assertTrue("and it must be signature-level, so only Orbit's own signer can hold it",
                manifest.contains("android:protectionLevel=\"signature\""));
        assertTrue("the service must require it",
                manifest.contains("android:permission=\"com.orbit.assistant.permission.BIND_ORBIT_LOCAL\""));
    }

    @Test public void bothModulesShareOneIpcContract() {
        assertTrue(read("app/build.gradle").contains("main.aidl.srcDirs += ['../ipc']"));
        assertTrue(read("local/build.gradle").contains("main.aidl.srcDirs += ['../ipc']"));
        assertTrue(Files.isRegularFile(
                repositoryRoot().resolve("ipc/com/orbit/assistant/local/IOrbitLocalService.aidl")));
    }

    // ---- both halves are versioned and signed together ----------------------------------------------

    @Test public void theComponentIsVersionedInLockstepWithOrbit() {
        String componentGradle = read("local/build.gradle");
        assertTrue("the component must carry this release's versionName",
                componentGradle.contains("versionName \"" + BuildConfig.VERSION_NAME + "\""));
        assertTrue("the component must carry this release's versionCode",
                componentGradle.contains("versionCode " + BuildConfig.VERSION_CODE));
    }

    @Test public void theComponentUsesTheSameReleaseSigningIdentity() {
        String componentGradle = read("local/build.gradle");
        assertTrue(componentGradle.contains("ORBIT_RELEASE_STORE_FILE"));
        assertTrue(componentGradle.contains("ORBIT_RELEASE_KEY_ALIAS"));
        assertTrue("an unsigned component would be refused by Orbit at install time",
                componentGradle.contains("signingConfigs.release"));
    }

    // ---- the release workflow publishes both ---------------------------------------------------------

    @Test public void theWorkflowBuildsVerifiesAndPublishesTheComponent() {
        String workflow = read(".github/workflows/release.yml");
        assertTrue(workflow.contains("EXPECTED_COMPONENT_PACKAGE_NAME: com.orbit.assistant.local"));
        assertTrue(workflow.contains("local/build/outputs/apk/release/local-release.apk"));
        assertTrue(workflow.contains("::error::Orbit Local component package name verification failed."));
        assertTrue(workflow.contains("::error::Orbit Local component version verification failed."));
        assertTrue(workflow.contains("::error::Orbit Local component signer-count verification failed."));
        assertTrue(workflow.contains("::error::Orbit Local component signing certificate verification failed."));
        assertTrue("the component APK must be published",
                workflow.contains("Orbit-Local-v${EXPECTED_VERSION_NAME}.apk"));
        assertTrue("and its checksum with it",
                workflow.contains("output_component_checksum=\"${output_component}.sha256\""));
    }

    /**
     * The manifest stays schema 1 so the updater already shipped in v0.7.7.4 keeps working. The
     * component block is additive, and an older Orbit simply never reads it.
     */
    @Test public void theUpdateManifestStaysBackwardCompatible() {
        String workflow = read(".github/workflows/release.yml");
        assertTrue("schema 1 must not change in this release",
                workflow.contains("\"schema\": 1"));
        assertTrue("component metadata is additive", workflow.contains("\"component\": {"));
        assertTrue(workflow.contains("\"protocol\": int(os.environ[\"COMPONENT_PROTOCOL\"])"));
        assertTrue("the main APK keeps the name older Orbit versions expect",
                workflow.contains("Orbit-Assistant-v${EXPECTED_VERSION_NAME}.apk"));
    }

    /**
     * Orbit Deck goes out as a Beta, because only a phone can judge it.
     *
     * <p>Everything this release adds is something that has to be looked at and held: tile spacing,
     * how a drag feels under a thumb, whether a grid of shortcuts reads as a finished Orbit surface
     * or as a settings screen. A test suite can prove the layout survives large text and that no
     * provider is contacted; it cannot prove the thing is worth opening. So this is published as a
     * prerelease, and the guard's job is to stop a versionName that has quietly lost its prerelease
     * metadata from being published to the Beta channel as though it were finished.
     */
    @Test public void thisReleaseIsABetaAwaitingDeviceValidation() {
        assertTrue(BuildConfig.VERSION_NAME + " must be a Beta version",
                OrbitVersion.isBeta(BuildConfig.VERSION_NAME));
        assertTrue(OrbitVersion.installedIsBeta());
        assertTrue(read("CHANGELOG.md").contains("- **v" + BuildConfig.VERSION_NAME + "**:"));
    }

    /**
     * The IPC contract version Orbit speaks, the one the component answers with, and the one the
     * release manifest publishes are one number in three files. A drift makes every install of the
     * component fail its protocol check.
     */
    @Test public void bothSidesAndTheReleaseManifestAgreeOnTheProtocol() {
        String expected = String.valueOf(OrbitLocalComponent.PROTOCOL_VERSION);
        assertTrue("the component must implement the protocol Orbit speaks",
                read("local/src/main/java/com/orbit/assistant/local/OrbitLocalService.java")
                        .contains("PROTOCOL_VERSION = " + expected));
        assertTrue("and the release manifest must publish it",
                read(".github/workflows/release.yml")
                        .contains("ORBIT_LOCAL_PROTOCOL: \"" + expected + "\""));
    }
}

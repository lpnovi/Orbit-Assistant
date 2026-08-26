package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Every directory Orbit hands to another app must be declared to FileProvider.
 *
 * <p>This is the test that would have caught the v0.7.7.5-beta.1 device failure. The Orbit Local
 * component APK downloaded and verified perfectly, and then the handoff to Android's package
 * installer failed outright, because {@code cache/orbit-local/} had no configured root:
 * {@code FileProvider.getUriForFile} throws for a path it was never told about. Nothing in the
 * build, the unit suite, or the code review could see it — only a phone could, and only at the
 * last step of a multi-megabyte download.
 *
 * <p>So the relationship is asserted here from both ends: the directories the code actually writes
 * installable files into, and the directories the XML actually exposes, have to be the same set.
 * The second half matters as much as the first — a wildcard root would make every test here pass
 * while handing arbitrary cached files to whichever app received the Intent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class FileProviderPathsTest {

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

    private static String paths() {
        return read("app/src/main/res/xml/file_paths.xml");
    }

    // ---- the bug this release fixes ----------------------------------------------------------------

    /** The Orbit Local component cache must be exposed, or the installer handoff cannot happen. */
    @Test public void theOrbitLocalComponentDirectoryIsExposed() {
        assertTrue("cache/orbit-local/ must be declared to FileProvider, or the verified Orbit "
                        + "Local APK cannot be handed to Android's package installer at all",
                paths().contains("<cache-path name=\"orbit_local_component\" path=\"orbit-local/\" />"));
    }

    /**
     * The declared path and the directory the installer actually writes to must be the same one.
     *
     * <p>Asserted against the real {@link OrbitLocalInstaller#componentDirectory} rather than a
     * copied string, so renaming the directory without updating the XML fails here instead of on a
     * phone.
     */
    @Test public void theDeclaredPathMatchesTheDirectoryTheInstallerUses() {
        Context context = RuntimeEnvironment.getApplication();
        File directory = OrbitLocalInstaller.componentDirectory(context);
        File cacheRoot = context.getCacheDir();

        assertEquals("the component APK must live directly under the cache root",
                cacheRoot.getAbsolutePath(), directory.getParentFile().getAbsolutePath());
        assertEquals("orbit-local", directory.getName());
        assertTrue("file_paths.xml must expose exactly that directory name",
                paths().contains("path=\"" + directory.getName() + "/\""));
    }

    /** The same relationship for the main updater, which is why that path has always worked. */
    @Test public void theUpdaterDirectoryRemainsExposed() {
        assertTrue(paths().contains("<cache-path name=\"orbit_updates\" path=\"updates/\" />"));
    }

    @Test public void theCameraPickerDirectoryRemainsExposed() {
        assertTrue(paths().contains(
                "<cache-path name=\"orbit_picker_camera\" path=\"orbit_picker_camera/\" />"));
    }

    // ---- the surface stays narrow --------------------------------------------------------------------

    /**
     * The invariant that was actually violated, checked the way FileProvider checks it.
     *
     * <p>{@code SimplePathStrategy} resolves a file by finding a configured root that contains it;
     * a file under no root gets no URI and the call throws. So this resolves the real directories
     * Orbit hands out against the real declared roots. It is deliberately not a string comparison
     * — renaming either side breaks it.
     *
     * <p>End-to-end {@code getUriForFile} is not asserted here because Robolectric cannot resolve
     * FileProvider roots in this project at all: the {@code updates/} path, which has installed
     * Orbit's own updates for many releases on real devices, fails there identically. Modelling
     * the lookup is what makes this catchable off-device.
     */
    @Test public void everyDirectoryOrbitSharesIsUnderADeclaredRoot() {
        Context context = RuntimeEnvironment.getApplication();
        assertCovered(OrbitLocalInstaller.componentDirectory(context),
                "the Orbit Local component installer");
        assertCovered(new File(context.getCacheDir(), "updates"), "the main Orbit updater");
    }

    private void assertCovered(File directory, String who) {
        File cacheRoot = RuntimeEnvironment.getApplication().getCacheDir();
        for (String declared : declaredCachePaths()) {
            File root = new File(cacheRoot, declared);
            if (directory.getAbsolutePath().equals(root.getAbsolutePath())
                    || directory.getAbsolutePath().startsWith(
                            root.getAbsolutePath() + File.separator)) {
                return;
            }
        }
        fail("The directory used by " + who + " (" + directory.getName() + ") is not covered by "
                + "any <cache-path> in file_paths.xml. FileProvider will refuse to produce a URI "
                + "for it, and the handoff to the other app will fail. Declared roots: "
                + declaredCachePaths());
    }

    /** The path attribute of every declared cache root, read from the real XML. */
    private static java.util.List<String> declaredCachePaths() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : paths().split("\n")) {
            if (!line.contains("<cache-path")) continue;
            int start = line.indexOf("path=\"") + 6;
            out.add(line.substring(start, line.indexOf('"', start)));
        }
        return out;
    }

    /**
     * A wildcard root would fix the bug and create a worse one: every cached file Orbit holds —
     * downloaded update APKs, attachment scratch files, screen captures — would become grantable
     * to whichever app received an Intent.
     */
    @Test public void theWholeCacheIsNeverExposed() {
        for (String path : declaredCachePaths()) {
            assertFalse("the entire cache root must never be exposed, found: " + path,
                    ".".equals(path) || "/".equals(path) || path.trim().isEmpty());
        }
    }

    /** Nothing outside the cache is shared at all: no files, external, or root paths. */
    @Test public void onlyCacheDirectoriesAreShared() {
        String xml = paths();
        for (String forbidden : new String[]{"<files-path", "<external-path",
                "<external-files-path", "<external-cache-path", "<root-path"}) {
            assertFalse("Orbit shares only specific cache directories, never " + forbidden,
                    xml.contains(forbidden));
        }
    }

    /** Exactly three declared roots, each one deliberate. A fourth needs a test of its own. */
    @Test public void everyExposedDirectoryIsAccountedFor() {
        String xml = paths();
        int declared = xml.split("<cache-path", -1).length - 1;
        assertEquals("Orbit exposes exactly the update, camera, and Orbit Local directories. "
                + "Adding another means adding its test here too.", 3, declared);
    }

    /** Each entry names a specific subdirectory, never a bare root. */
    @Test public void everyEntryNamesASubdirectory() {
        for (String line : paths().split("\n")) {
            if (!line.contains("<cache-path")) continue;
            int start = line.indexOf("path=\"") + 6;
            String path = line.substring(start, line.indexOf('"', start));
            assertTrue("a shared path must name a subdirectory: " + line, path.length() > 1);
            assertTrue("a shared path must end at a directory boundary: " + line, path.endsWith("/"));
        }
    }

    // ---- the provider itself -------------------------------------------------------------------------

    @Test public void theProviderIsPrivateAndGrantsPerUri() {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains("android:authorities=\"${applicationId}.fileprovider\""));
        assertTrue("the provider must never be exported", manifest.contains("android:exported=\"false\""));
        assertTrue(manifest.contains("android:grantUriPermissions=\"true\""));
        assertTrue("and must point at this configuration",
                manifest.contains("android:resource=\"@xml/file_paths\""));
    }

    /** The authority the installer asks for is the one the manifest declares. */
    @Test public void theInstallerUsesTheDeclaredAuthority() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(context.getPackageName() + ".fileprovider",
                OrbitLocalInstaller.fileProviderAuthority(context));
    }

    @Test public void theInstallerUsesTheApkMimeType() {
        assertEquals("application/vnd.android.package-archive", OrbitLocalInstaller.APK_MIME_TYPE);
    }
}

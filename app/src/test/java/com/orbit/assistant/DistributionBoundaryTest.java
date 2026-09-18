package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The GitHub / Google Play distribution boundary, seen from the GitHub edition.
 *
 * <p>This suite runs as testDebugUnitTest, which is the GitHub edition, so it proves two things:
 * that the GitHub edition still has everything its updater and Orbit Local installer need, and that
 * the Play edition's sources and build configuration contain none of it. The Play edition's own
 * runtime behaviour is proved by the Play*Test classes in src/testPlay, run by testPlayUnitTest
 * against the real Play variant and its real merged manifest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class DistributionBoundaryTest {

    // ---- the GitHub edition is unchanged -----------------------------------------------------------

    @Test public void theGitHubBuildIdentifiesItselfAsGitHub() {
        assertEquals("github", OrbitEdition.ID);
        assertEquals(OrbitDistribution.Channel.GITHUB, OrbitDistribution.current());
        assertFalse(OrbitDistribution.isPlay());
        assertTrue(OrbitDistribution.selfUpdates());
        assertTrue(OrbitDistribution.installsOrbitLocalComponent());
        assertEquals("GitHub", OrbitDistribution.label());
    }

    @Test public void theGitHubEditionKeepsItsUpdaterEndpoints() {
        assertEquals("https://api.github.com/repos/lpnovi/Orbit-Assistant/releases/latest",
                OrbitEdition.latestReleaseApi());
        assertEquals("https://api.github.com/repos/lpnovi/Orbit-Assistant/releases?per_page=15",
                OrbitEdition.releasesListApi(15));
        assertEquals("https://github.com/lpnovi/Orbit-Assistant/releases/download/",
                OrbitEdition.releaseDownloadBase());
        assertEquals("application/vnd.android.package-archive",
                OrbitEdition.apkInstallerIntent(android.net.Uri.parse("content://x/y")).getType());
    }

    @Test public void theGitHubManifestStillRequestsPackageInstalls() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        List<String> requested = Arrays.asList(info.requestedPermissions);
        assertTrue("the GitHub updater and Orbit Local installer need it",
                requested.contains("android.permission.REQUEST_INSTALL_PACKAGES"));
        assertTrue(read("app/src/main/AndroidManifest.xml")
                .contains("android.permission.REQUEST_INSTALL_PACKAGES"));
    }

    /** Location-triggered Routines are unchanged in the GitHub edition. */
    @Test public void theGitHubEditionKeepsBackgroundLocationTriggers() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        assertTrue(Arrays.asList(info.requestedPermissions)
                .contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
        assertTrue(OrbitDistribution.supportsLocationTriggers());

        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        assertTrue("the GitHub edition still reads Android's real answer",
                RoutineLocationTriggerScheduler.hasBackgroundLocation(context));
    }

    // ---- one application, one identity -----------------------------------------------------------

    @Test public void thePackageIsComOrbitAssistantInEveryEdition() {
        assertEquals("com.orbit.assistant", RuntimeEnvironment.getApplication().getPackageName());
        String gradle = read("app/build.gradle");
        assertEquals("exactly one applicationId, shared by every build type",
                1, count(gradle, "applicationId "));
        assertTrue(gradle.contains("applicationId 'com.orbit.assistant'"));
        assertFalse("no build type may fork the package", gradle.contains("applicationIdSuffix"));
        assertFalse(gradle.contains("versionNameSuffix"));
        assertFalse("no second application through flavors", gradle.contains("productFlavors"));
    }

    @Test public void thePlayBuildTypeSharesTheVersionAndIsNotDebuggable() {
        String play = playBlock();
        assertFalse("Play and GitHub builds of a release share one versionCode",
                play.contains("versionCode"));
        assertFalse(play.contains("versionName"));
        assertTrue(play.contains("debuggable false"));
        assertFalse("never signed with the GitHub release key",
                play.contains("signingConfigs.release"));
        assertTrue(play.contains("signingConfigs.playUpload"));
        assertFalse("initWith(release) would copy the release signing config",
                play.contains("initWith"));
    }

    @Test public void theGitHubReleaseBuildIsStillSignedByTheReleaseKey() {
        String gradle = read("app/build.gradle");
        int release = gradle.indexOf("        release {\n            if (orbitReleaseSigningReady)");
        assertTrue("the release build type is untouched", release > 0);
        assertTrue(gradle.contains("tasks.named('preReleaseBuild')"));
    }

    @Test public void bothModulesTargetApi36() {
        for (String file : new String[]{"app/build.gradle", "local/build.gradle"}) {
            String gradle = read(file);
            assertTrue(file, gradle.contains("compileSdk 36"));
            assertTrue(file, gradle.contains("targetSdk 36"));
            assertTrue(file + " keeps minSdk 29", gradle.contains("minSdk 29"));
        }
    }

    /** API 36 made Back callbacks the default; Orbit keeps its old default explicitly. */
    @Test public void theApplicationKeepsLegacyBackUnlessAScreenOptsIn() {
        String manifest = read("app/src/main/AndroidManifest.xml");
        String application = manifest.substring(manifest.indexOf("<application"),
                manifest.indexOf(">", manifest.indexOf("<application")));
        assertTrue(application.contains("android:enableOnBackInvokedCallback=\"false\""));
    }

    // ---- the rules ---------------------------------------------------------------------------------

    @Test public void anUnknownEditionFailsClosed() {
        assertEquals(OrbitDistribution.Channel.GITHUB, OrbitDistribution.parse("github"));
        assertEquals(OrbitDistribution.Channel.PLAY, OrbitDistribution.parse("play"));
        assertEquals(OrbitDistribution.Channel.PLAY, OrbitDistribution.parse(""));
        assertEquals(OrbitDistribution.Channel.PLAY, OrbitDistribution.parse(null));
        assertEquals(OrbitDistribution.Channel.PLAY, OrbitDistribution.parse("GitHub"));
    }

    @Test public void playAllowsNoSelfUpdateNoComponentInstallAndNoProPreview() {
        OrbitDistribution.Channel play = OrbitDistribution.Channel.PLAY;
        assertFalse(OrbitDistribution.selfUpdates(play));
        assertFalse(OrbitDistribution.installsOrbitLocalComponent(play));
        assertFalse(OrbitDistribution.allowsProPreview(play));
        assertFalse(OrbitDistribution.supportsLocationTriggers(play));
        assertEquals("Google Play", OrbitDistribution.label(play));

        OrbitDistribution.Channel github = OrbitDistribution.Channel.GITHUB;
        assertTrue(OrbitDistribution.selfUpdates(github));
        assertTrue(OrbitDistribution.installsOrbitLocalComponent(github));
        assertTrue(OrbitDistribution.allowsProPreview(github));
        assertTrue(OrbitDistribution.supportsLocationTriggers(github));
    }

    /** Orbit Local serves only the GitHub-signed edition. */
    @Test public void orbitLocalIsUsableOnlyByTheGitHubEdition() {
        assertTrue(OrbitDistribution.supportsOrbitLocal());
        assertTrue(OrbitDistribution.supportsOrbitLocal(OrbitDistribution.Channel.GITHUB));
        assertFalse(OrbitDistribution.supportsOrbitLocal(OrbitDistribution.Channel.PLAY));
        String component = withoutComments(
                read("app/src/main/java/com/orbit/assistant/OrbitLocalComponent.java"));
        int usable = component.indexOf("public static boolean isUsable(Context context) {");
        assertTrue(usable > 0);
        assertTrue("the edition is checked before the component's state is even read",
                component.indexOf("OrbitDistribution.supportsOrbitLocal()", usable)
                        < component.indexOf("state(context)", usable));
    }

    /**
     * The dual-signature model, stated the same way everywhere.
     *
     * <p>GitHub Orbit is signed with the GitHub release key, Play Orbit with a Google-generated
     * Play key, and the GitHub key is never exported to Google. A document drifting back to the
     * earlier same-key plan would send someone to upload the GitHub key to Play Console.
     */
    @Test public void theSigningDocumentsDescribeTwoSeparateKeys() {
        for (String file : new String[]{"docs/PLAY_STORE.md", "docs/PLAY_CONSOLE_CHECKLIST.md",
                "CLAUDE.md"}) {
            String text = read(file);
            assertTrue(file, text.contains("Google-generated"));
            for (String stale : new String[]{"use your own existing app signing key",
                    "Enroll the existing key", "same app-signing certificate",
                    "enrolled with the existing key", "PEPK instructions using"}) {
                assertFalse(file + " still says: " + stale, text.contains(stale));
            }
        }
        assertTrue(read("docs/PLAY_STORE.md").contains("Neither edition can update or replace the other"));
        assertTrue(read("app/build.gradle").contains("Google-generated app signing key"));
    }

    // ---- the Play edition's sources carry no install path -----------------------------------------

    @Test public void thePlayManifestOverlayRemovesTheInstallPermission() {
        String overlay = read("app/src/play/AndroidManifest.xml");
        Matcher m = Pattern.compile("<uses-permission\\s+android:name=\"android\\.permission"
                + "\\.REQUEST_INSTALL_PACKAGES\"\\s+tools:node=\"remove\"\\s*/>").matcher(overlay);
        assertTrue(m.find());
    }

    @Test public void thePlayManifestOverlayRemovesBackgroundLocationAndNothingElse() {
        String overlay = read("app/src/play/AndroidManifest.xml");
        Matcher m = Pattern.compile("<uses-permission\\s+android:name=\"android\\.permission"
                + "\\.ACCESS_BACKGROUND_LOCATION\"\\s+tools:node=\"remove\"\\s*/>").matcher(overlay);
        assertTrue(m.find());
        assertEquals("the overlay removes exactly two permissions", 2,
                count(overlay, "tools:node=\"remove\""));
        assertFalse(overlay.contains("ACCESS_FINE_LOCATION"));
        assertFalse(overlay.contains("ACCESS_COARSE_LOCATION"));
    }

    @Test public void thePlayEditionHasNoGitHubEndpointAndNoInstallerHandOff() {
        String play = read("app/src/play/java/com/orbit/assistant/OrbitEdition.java");
        String code = withoutComments(play);
        for (String banned : new String[]{"github.com", "api.github", "ACTION_VIEW",
                "package-archive", "ACTION_MANAGE_UNKNOWN_APP_SOURCES", "PackageInstaller",
                "startActivity", "canRequestPackageInstalls()", "HttpURLConnection", "new URL("}) {
            assertFalse("the Play OrbitEdition must not contain " + banned, code.contains(banned));
        }
        assertTrue(code.contains("static final String ID = \"play\";"));
        assertTrue(code.contains("return false;"));
    }

    /** Both editions expose the same members, so main compiles against either and swaps cleanly. */
    @Test public void bothEditionsHaveTheSameShape() {
        assertEquals(signatures(read("app/src/github/java/com/orbit/assistant/OrbitEdition.java")),
                signatures(read("app/src/play/java/com/orbit/assistant/OrbitEdition.java")));
    }

    /**
     * No code shared by both editions may reach GitHub release assets or Android's installer
     * directly. It has to go through OrbitEdition, which is what the Play build replaces.
     */
    @Test public void sharedCodeReachesAssetsAndTheInstallerOnlyThroughOrbitEdition() throws IOException {
        Pattern handOff = Pattern.compile(
                "setDataAndType\\([^;]*(package-archive|APK_MIME_TYPE)");
        try (Stream<Path> files = Files.list(repo().resolve("app/src/main/java/com/orbit/assistant"))) {
            for (Path file : files.collect(Collectors.toList())) {
                String code = withoutComments(new String(Files.readAllBytes(file),
                        StandardCharsets.UTF_8));
                String name = file.getFileName().toString();
                assertFalse(name + " downloads release assets itself",
                        code.contains("/releases/download/"));
                assertFalse(name + " opens the unknown-sources page itself",
                        code.contains("ACTION_MANAGE_UNKNOWN_APP_SOURCES"));
                assertFalse(name + " builds an APK install intent itself",
                        handOff.matcher(code).find());
                assertFalse(name + " uses the legacy install action",
                        code.contains("ACTION_INSTALL_PACKAGE"));
                assertFalse(name + " opens a package installer session",
                        code.contains("createSession("));
            }
        }
    }

    // ---- Stable and Play never point at a Pro Preview they do not have ---------------------------

    @Test public void withoutThePreviewLockedProSaysItCannotBeBoughtYet() {
        String instruction = "Enable Pro Preview in Diagnostics to try saved layouts.";
        assertEquals(instruction, OrbitProEntitlement.lockedGuidance(true, instruction));
        String stable = OrbitProEntitlement.lockedGuidance(false, instruction);
        assertEquals("Orbit Pro isn't available for purchase yet.", stable);
        assertFalse(stable.contains("Preview"));
        assertFalse(stable.contains("Diagnostics"));
    }

    @Test public void aStableVersionNameIsNeverPreviewEligible() {
        assertFalse(OrbitProEntitlement.previewAvailable(false, "0.8.0.0"));
        assertTrue(OrbitProEntitlement.previewAvailable(false, "0.8.0.1-beta.1"));
    }

    /**
     * Every user-facing Pro Preview instruction in Orbit is an argument to lockedGuidance, so a
     * Stable or Play build replaces it with the truth instead of showing it.
     */
    @Test public void everyProPreviewInstructionGoesThroughLockedGuidance() throws IOException {
        Pattern instruction = Pattern.compile("\"[^\"\\n]*Enable Pro Preview[^\"\\n]*\"");
        int found = 0;
        try (Stream<Path> files = Files.list(repo().resolve("app/src/main/java/com/orbit/assistant"))) {
            for (Path file : files.collect(Collectors.toList())) {
                String code = withoutComments(new String(Files.readAllBytes(file),
                        StandardCharsets.UTF_8));
                Matcher m = instruction.matcher(code);
                while (m.find()) {
                    found++;
                    String before = code.substring(Math.max(0, m.start() - 160), m.start());
                    assertTrue(file.getFileName() + " shows a Pro Preview instruction without "
                            + "OrbitProEntitlement.lockedGuidance: " + m.group(),
                            before.contains("OrbitProEntitlement.lockedGuidance("));
                }
            }
        }
        assertEquals("Deck layouts, Deck tile appearance, and Theme Studio", 3, found);
    }

    // ---- What's New in the Play edition ----------------------------------------------------------

    @Test public void playReleaseNotesDropOnlyTheApkFooter() {
        String body = "## Orbit Assistant v0.8.0.0\n\nSummary.\n\n- One\n- Two\n\n"
                + "This APK is signed with Orbit Assistant's permanent release certificate. "
                + "The attached `.sha256` file can be used to verify the APK download.\n";
        String play = WhatsNewActivity.withoutApkFooter(body);
        assertFalse(play.contains("APK"));
        assertTrue(play.contains("Summary."));
        assertTrue(play.contains("- Two"));
        assertEquals("", WhatsNewActivity.withoutApkFooter(null));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static String playBlock() {
        String gradle = read("app/build.gradle");
        int start = gradle.indexOf("        play {");
        assertTrue("the play build type exists", start > 0);
        int end = gradle.indexOf("\n        }", start);
        return gradle.substring(start, end);
    }

    private static List<String> signatures(String source) {
        Matcher m = Pattern.compile("\\n    static [^=;{]+?(?=\\s*[={;])").matcher(source);
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        while (m.find()) out.add(m.group().trim().replaceAll("\\s+", " "));
        java.util.Collections.sort(out);
        assertFalse(out.isEmpty());
        return out;
    }

    private static String withoutComments(String source) {
        // Block comments, then whole-line // comments. Never "//" mid-line: that would also strip
        // the "https://" out of the very URLs these checks look for.
        // The lookbehind keeps a MIME wildcard such as "image/*" from opening a false comment.
        return source.replaceAll("(?s)(?<![\\w/])/\\*.*?\\*/", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    static Path repo() {
        for (Path dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("app/build.gradle"))) return dir;
        }
        fail("repository root not found");
        return null;
    }

    static String read(String relative) {
        try {
            return new String(Files.readAllBytes(repo().resolve(relative)), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("could not read " + relative, e);
        }
    }
}

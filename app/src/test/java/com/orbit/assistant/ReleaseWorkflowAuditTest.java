package com.orbit.assistant;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/**
 * An audit of the release workflow, because nothing else in this repository can test it.
 *
 * <p>The workflow is what turns a tag into a signed, verified, published Orbit release, and it now
 * has to handle two tag shapes without ever confusing them. It only ever runs on GitHub, so these
 * assertions read the YAML directly and pin the parts that would be dangerous to lose: the tag
 * patterns, the requirement that the tag matches the tagged source, the derivation of GitHub's
 * prerelease flag from the tag rather than from a hand-set input, and every verification step that
 * existed before Beta was a concept.
 */
public final class ReleaseWorkflowAuditTest {

    private static String workflow() {
        // Unit tests run from the module directory; the workflow lives at the repository root.
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve(".github/workflows/release.yml");
            if (Files.isRegularFile(candidate)) {
                try {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("could not read release.yml", e);
                }
            }
        }
        throw new AssertionError("release.yml was not found above " + start);
    }

    private static void contains(String yaml, String needle, String why) {
        assertTrue(why + " — missing from release.yml: " + needle, yaml.contains(needle));
    }

    // ---- tag shapes -------------------------------------------------------------------------------

    @Test public void bothTagShapesAreRecognised() {
        String yaml = workflow();
        contains(yaml, "stable_pattern='^v[0-9]+(\\.[0-9]+)+$'",
                "Stable tags must still be accepted exactly as before");
        contains(yaml, "beta_pattern='^v[0-9]+(\\.[0-9]+)+-beta\\.[1-9][0-9]*$'",
                "Beta tags must be accepted, and only in the one canonical form");
    }

    /** The same patterns Orbit itself enforces, so the app and the pipeline cannot drift apart. */
    @Test public void theWorkflowPatternsAgreeWithOrbitsOwnRules() {
        Pattern stable = Pattern.compile("^v[0-9]+(\\.[0-9]+)+$");
        Pattern beta = Pattern.compile("^v[0-9]+(\\.[0-9]+)+-beta\\.[1-9][0-9]*$");

        for (String tag : new String[]{"v0.7.7.4", "v0.7.7.5", "v1.0.0.0"}) {
            assertTrue(tag, stable.matcher(tag).matches());
            assertTrue(tag, OrbitVersion.isStableTag(tag));
        }
        for (String tag : new String[]{"v0.7.7.5-beta.1", "v0.8.0.0-beta.14"}) {
            assertTrue(tag, beta.matcher(tag).matches());
            assertTrue(tag, OrbitVersion.isBetaTag(tag));
        }
        for (String tag : new String[]{"v0.7.7.5-beta", "v0.7.7.5-beta.0", "v0.7.7.5-beta.zero",
                "v0.7.7.5-test", "beta-v0.7.7.5", "v0.7.7.5-beta.01"}) {
            assertTrue(tag + " must be rejected by the workflow",
                    !stable.matcher(tag).matches() && !beta.matcher(tag).matches());
            assertTrue(tag + " must be rejected by Orbit", !OrbitVersion.isValidTag(tag));
        }
    }

    @Test public void aMalformedTagStopsTheRelease() {
        contains(workflow(), "::error::Requested release tag is malformed.",
                "an unrecognised tag must fail before anything is published");
    }

    // ---- tag and source must agree ------------------------------------------------------------------

    @Test public void theTagMustMatchTheTaggedSourceVersion() {
        String yaml = workflow();
        contains(yaml, "\"${RELEASE_TAG}\" != \"v${source_version_name}\"",
                "the tag must equal v + the source versionName, suffix included");
        contains(yaml, "::error::Release tag does not match the tagged source version.",
                "a mismatch must stop the release");
        contains(yaml, "git tag --points-at HEAD",
                "the checked-out source must be the requested tag");
    }

    // ---- release type ------------------------------------------------------------------------------

    /**
     * GitHub's prerelease flag is derived from the validated tag, never passed in by hand. Orbit's
     * updater refuses any release where the tag shape and that flag disagree, so a hand-set flag
     * would be able to publish a release no Orbit could install.
     */
    @Test public void theReleaseTypeIsDerivedFromTheTag() {
        String yaml = workflow();
        contains(yaml, "prerelease_flag=\"true\"", "a Beta tag publishes as a prerelease");
        contains(yaml, "prerelease_flag=\"false\"", "a Stable tag publishes as a normal release");
        contains(yaml, "prerelease_args+=(--prerelease)",
                "the prerelease flag must actually reach gh release create");
        contains(yaml, "\"${prerelease_args[@]}\"", "and must be passed to the publish command");
    }

    @Test public void betaReleasesAreNotDrafts() {
        String yaml = workflow();
        assertTrue("a Beta release must be a published prerelease, never a draft",
                !yaml.contains("--draft"));
    }

    @Test public void theReleaseTitleUsesTheReadableBuildName() {
        String yaml = workflow();
        contains(yaml, "display_name=\"${base_version} Beta ${beta_number}\"",
                "a Beta release is titled Orbit Assistant v<version> Beta <n>");
        contains(yaml, "--title \"Orbit Assistant v${{ steps.source.outputs.display_name }}\"",
                "the published title must use that readable name");
    }

    /** The workflow's title format and Orbit's own must produce the same string. */
    @Test public void theTitleFormatMatchesOrbitsHelper() {
        assertTrue(OrbitVersion.releaseTitle("0.7.7.4").equals("Orbit Assistant v0.7.7.4"));
        assertTrue(OrbitVersion.releaseTitle("0.7.7.5-beta.1")
                .equals("Orbit Assistant v0.7.7.5 Beta 1"));
    }

    // ---- verification that must never be weakened ----------------------------------------------------

    @Test public void everyReleaseCheckStillRuns() {
        String yaml = workflow();
        contains(yaml, "::error::APK package name verification failed.", "package name is verified");
        contains(yaml, "::error::APK version verification failed.", "versionName and code are verified");
        contains(yaml, "::error::APK signer-count verification failed.", "the signer count is verified");
        contains(yaml, "::error::APK signing certificate verification failed.",
                "the permanent certificate is verified");
        contains(yaml, "EXPECTED_PACKAGE_NAME: com.orbit.assistant",
                "the package name is pinned in the workflow");
        contains(yaml, "EXPECTED_CERT_SHA256: 7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:"
                        + "94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3",
                "the permanent Orbit certificate is pinned in the workflow");
        contains(yaml, "sha256sum", "the APK checksum file is generated");
        contains(yaml, "\"schema\": 1", "the manifest schema stays 1 for older Orbit installs");
        contains(yaml, "orbit-update.json", "the update manifest is generated and published");
        contains(yaml, "--verify-tag", "gh must confirm the tag before publishing");
    }

    /**
     * v0.7.7.3's updater builds this exact asset name and will refuse anything else, so the naming
     * must not change in the release that v0.7.7.3 users are meant to receive.
     */
    @Test public void theApkAssetNamingIsUnchanged() {
        contains(workflow(), "Orbit-Assistant-v${EXPECTED_VERSION_NAME}.apk",
                "the APK asset keeps the name older Orbit versions expect");
    }

    @Test public void theCertificatePinMatchesTheOneCompiledIntoOrbit() throws Exception {
        String yaml = workflow();
        java.lang.reflect.Field field = OrbitUpdater.class.getDeclaredField("CERTIFICATE_SHA256");
        field.setAccessible(true);
        String compiledIn = (String) field.get(null);
        assertTrue("the workflow pin and the pin inside OrbitUpdater must be identical",
                yaml.contains("EXPECTED_CERT_SHA256: " + compiledIn));
    }

    // ---- release notes -------------------------------------------------------------------------------

    @Test public void releaseNotesComeFromTheChangelogAndStayShort() {
        String yaml = workflow();
        contains(yaml, "marker = f'- **v{version}**:'",
                "notes are keyed by the exact versionName, Beta suffix included");
        contains(yaml, "No CHANGELOG.md entry found for v", "a missing entry fails the release");
        contains(yaml, "if body_words > 200:", "release notes stay concise");
        contains(yaml, "notes = f'## Orbit Assistant v{display}", "the heading uses the readable name");
    }

    /** The changelog entry this very release will be published from must already exist. */
    @Test public void thisReleaseHasItsChangelogEntry() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            Path candidate = directory.resolve("CHANGELOG.md");
            if (!Files.isRegularFile(candidate)) continue;
            try {
                String changelog = new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                assertTrue("CHANGELOG.md needs an entry for v" + BuildConfig.VERSION_NAME,
                        changelog.contains("- **v" + BuildConfig.VERSION_NAME + "**:"));
                return;
            } catch (IOException e) {
                throw new AssertionError("could not read CHANGELOG.md", e);
            }
        }
        fail("CHANGELOG.md was not found above " + start);
    }
}

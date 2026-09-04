package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * How Orbit reads and writes its own version strings.
 *
 * <p>The rejection cases carry the weight here. Orbit publishes Beta builds from the same
 * repository, package, and signing certificate as Stable ones, so "is this a legitimate Orbit
 * Beta version" is a question with a security flavour: a tag such as {@code v0.7.7.5-test} or
 * {@code v0.7.7.5-beta.0} is not an Orbit release, and must never be parsed into one.
 */
public final class OrbitVersionTest {

    // ---- what counts as a version ---------------------------------------------------------------

    @Test public void stableVersionsAreRecognised() {
        assertTrue(OrbitVersion.isStable("0.7.7.5"));
        assertTrue(OrbitVersion.isStable("1.0.0.0"));
        assertTrue(OrbitVersion.isStable("0.8.0"));
        assertFalse(OrbitVersion.isBeta("0.7.7.5"));
    }

    @Test public void betaVersionsAreRecognised() {
        assertTrue(OrbitVersion.isBeta("0.7.7.5-beta.1"));
        assertTrue(OrbitVersion.isBeta("0.7.7.5-beta.2"));
        assertTrue(OrbitVersion.isBeta("0.8.0.0-beta.14"));
        assertFalse(OrbitVersion.isStable("0.7.7.5-beta.1"));
    }

    /** A malformed suffix is not a Beta. It is not a version at all. */
    @Test public void malformedSuffixesAreNotBetas() {
        for (String bad : new String[]{
                "0.7.7.5-beta", "0.7.7.5-beta.0", "0.7.7.5-beta.zero", "0.7.7.5-beta.01",
                "0.7.7.5-test", "0.7.7.5-rc.1", "0.7.7.5-BETA.1", "0.7.7.5beta1",
                "0.7.7.5-beta.1.2", "beta.1", "", "0", "not-a-version"}) {
            assertFalse(bad + " must not read as a Beta", OrbitVersion.isBeta(bad));
            assertFalse(bad + " must not read as a valid Orbit version", OrbitVersion.isValid(bad));
        }
        assertFalse(OrbitVersion.isValid(null));
    }

    @Test public void baseVersionStripsTheBetaSuffix() {
        assertEquals("0.7.7.5", OrbitVersion.baseVersion("0.7.7.5-beta.3"));
        assertEquals("0.7.7.5", OrbitVersion.baseVersion("0.7.7.5"));
        assertEquals("", OrbitVersion.baseVersion("0.7.7.5-beta.0"));
        assertEquals("", OrbitVersion.baseVersion(null));
    }

    @Test public void theBetaCounterIsRead() {
        assertEquals(1, OrbitVersion.betaNumber("0.7.7.5-beta.1"));
        assertEquals(12, OrbitVersion.betaNumber("0.7.7.5-beta.12"));
        assertEquals(0, OrbitVersion.betaNumber("0.7.7.5"));
        assertEquals(0, OrbitVersion.betaNumber("0.7.7.5-beta.0"));
    }

    // ---- how a build is written for a person ------------------------------------------------------

    @Test public void buildsAreDisplayedTheWayPeopleReadThem() {
        assertEquals("0.7.7.5", OrbitVersion.displayName("0.7.7.5"));
        assertEquals("0.7.7.5 Beta 1", OrbitVersion.displayName("0.7.7.5-beta.1"));
        assertEquals("0.7.7.5 Beta 12", OrbitVersion.displayName("0.7.7.5-beta.12"));
        assertEquals("0.8.0.0 Beta 14", OrbitVersion.displayName("0.8.0.0-beta.14"));
    }

    /** An unrecognised string is shown as it is, never dressed up as a legitimate Beta. */
    @Test public void unrecognisedStringsAreNotPresentedAsBetas() {
        assertEquals("0.7.7.5-beta.0", OrbitVersion.displayName("0.7.7.5-beta.0"));
        assertEquals("0.7.7.5-test", OrbitVersion.displayName("0.7.7.5-test"));
    }

    @Test public void releaseTitlesMatchTheWorkflow() {
        assertEquals("Orbit Assistant v0.7.7.4", OrbitVersion.releaseTitle("0.7.7.4"));
        assertEquals("Orbit Assistant v0.7.7.5 Beta 1", OrbitVersion.releaseTitle("0.7.7.5-beta.1"));
    }

    // ---- tags -------------------------------------------------------------------------------------

    @Test public void tagsFollowTheVersionRules() {
        assertTrue(OrbitVersion.isStableTag("v0.7.7.5"));
        assertTrue(OrbitVersion.isBetaTag("v0.7.7.5-beta.1"));
        assertTrue(OrbitVersion.isValidTag("v0.8.0.0-beta.14"));

        assertFalse(OrbitVersion.isValidTag("0.7.7.5"));
        assertFalse(OrbitVersion.isValidTag("v0.7.7.5-beta"));
        assertFalse(OrbitVersion.isValidTag("v0.7.7.5-beta.0"));
        assertFalse(OrbitVersion.isValidTag("v0.7.7.5-beta.zero"));
        assertFalse(OrbitVersion.isValidTag("v0.7.7.5-test"));
        assertFalse(OrbitVersion.isValidTag("beta-v0.7.7.5"));
        assertFalse(OrbitVersion.isValidTag(null));

        assertFalse("a beta tag is not a stable tag", OrbitVersion.isStableTag("v0.7.7.5-beta.1"));
        assertFalse("a stable tag is not a beta tag", OrbitVersion.isBetaTag("v0.7.7.5"));
    }

    @Test public void tagsAndVersionsRoundTrip() {
        assertEquals("0.7.7.5", OrbitVersion.versionFromTag("v0.7.7.5"));
        assertEquals("0.7.7.5-beta.2", OrbitVersion.versionFromTag("v0.7.7.5-beta.2"));
        assertEquals("", OrbitVersion.versionFromTag("v0.7.7.5-beta.0"));
        assertEquals("v0.7.7.5-beta.2", OrbitVersion.tagFor("0.7.7.5-beta.2"));
        assertEquals("v0.7.7.4", OrbitVersion.tagFor("0.7.7.4"));
    }

    // ---- ordering ---------------------------------------------------------------------------------

    @Test public void newerBaseVersionsRankHigher() {
        assertTrue(OrbitVersion.compareVersions("0.7.7.6", "0.7.7.5") > 0);
        assertTrue(OrbitVersion.compareVersions("0.7.7.5", "0.7.7.6") < 0);
        assertEquals(0, OrbitVersion.compareVersions("0.7.7.5", "0.7.7.5"));
        assertTrue(OrbitVersion.compareVersions("0.7.7.10", "0.7.7.9") > 0);
    }

    /** A Beta is a step towards its Stable release, so the finished release outranks it. */
    @Test public void aStableReleaseOutranksItsOwnBetas() {
        assertTrue(OrbitVersion.compareVersions("0.7.7.5", "0.7.7.5-beta.3") > 0);
        assertTrue(OrbitVersion.compareVersions("0.7.7.5-beta.3", "0.7.7.5") < 0);
        assertTrue(OrbitVersion.compareVersions("0.7.7.5-beta.2", "0.7.7.5-beta.1") > 0);
        assertTrue("a later cycle's Beta still outranks an earlier finished release",
                OrbitVersion.compareVersions("0.7.7.6-beta.1", "0.7.7.5") > 0);
    }

    @Test public void unrecognisedVersionsSortBelowEverything() {
        assertTrue(OrbitVersion.compareVersions("0.0.0.1", "nonsense") > 0);
        assertTrue(OrbitVersion.compareVersions("nonsense", "0.0.0.1") < 0);
        assertEquals(0, OrbitVersion.compareVersions("nonsense", "also-nonsense"));
    }

    // ---- this installation ------------------------------------------------------------------------

    /**
     * Whatever Orbit ships as, its own version must parse. A build whose versionName Orbit cannot
     * read would break update comparisons and the release workflow's tag check together, so this
     * fails at build time rather than on a phone.
     */
    @Test public void thisBuildHasAVersionOrbitUnderstands() {
        assertTrue("BuildConfig version must be a valid Orbit version",
                OrbitVersion.isValid(BuildConfig.VERSION_NAME));
        assertEquals(OrbitVersion.displayName(BuildConfig.VERSION_NAME),
                OrbitVersion.installedDisplayName());
        assertEquals(OrbitVersion.isBeta(BuildConfig.VERSION_NAME), OrbitVersion.installedIsBeta());
        assertTrue("its tag must be one the release workflow accepts",
                OrbitVersion.isValidTag(OrbitVersion.tagFor(BuildConfig.VERSION_NAME)));
    }

    /**
     * The 0.7.8.3 line is still a Beta, and this is its fourth.
     *
     * <p>Beta 4 exists so one preview fix reaches a phone through the normal update path, which is
     * the only way a Beta ever gets to a device. It changes nothing the suite can newly prove: the
     * Theme Studio preview now draws Orbit's own mark beside the title instead of a coloured disc,
     * and whether that reads correctly at eighteen points on a real panel is exactly the kind of
     * question the unit tests cannot answer. So this ships as a prerelease like the three before
     * it, and the guard's job is unchanged - stop the 0.7.8.3 line going out as Stable until the
     * S25 Ultra and the Tab S9 Plus have said it should.
     */
    @Test public void thisBuildIsTheFourthThemeStudioBeta() {
        String version = BuildConfig.VERSION_NAME;
        assertTrue(OrbitVersion.installedIsBeta());
        assertTrue(OrbitVersion.isBeta(version));
        assertFalse(OrbitVersion.isStable(version));
        assertEquals("0.7.8.3", OrbitVersion.baseVersion(version));
        assertEquals(4, OrbitVersion.betaNumber(version));

        assertEquals("Orbit Assistant v0.7.8.3 Beta 4", OrbitVersion.releaseTitle(version));
        assertEquals("v0.7.8.3-beta.4", OrbitVersion.tagFor(version));
        assertTrue("the release workflow must publish it as a prerelease",
                OrbitVersion.isBetaTag(OrbitVersion.tagFor(version)));
        assertFalse("and never as Stable",
                OrbitVersion.isStableTag(OrbitVersion.tagFor(version)));
        assertTrue("it must outrank the Beta it fixes",
                OrbitVersion.compareVersions(version, "0.7.8.3-beta.3") > 0);
        assertTrue("and every Beta before that",
                OrbitVersion.compareVersions(version, "0.7.8.3-beta.2") > 0);
        assertTrue("it must outrank the Stable line it was built from",
                OrbitVersion.compareVersions(version, "0.7.8.2") > 0);
        assertTrue("and every Beta of that line",
                OrbitVersion.compareVersions(version, "0.7.8.2-beta.4") > 0);
        assertTrue("while the Stable it is working towards still outranks it",
                OrbitVersion.compareVersions("0.7.8.3", version) > 0);
    }
}

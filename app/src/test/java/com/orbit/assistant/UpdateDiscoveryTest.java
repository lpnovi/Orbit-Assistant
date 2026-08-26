package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Which GitHub releases Orbit will and will not accept, in each channel.
 *
 * <p>This is the security surface of the Beta feature. Beta means newer and less tested code; it
 * does not mean a weaker idea of what counts as an official Orbit release. Every rule a Stable
 * candidate has to satisfy is applied identically to a Beta one, and the two labels have to agree
 * with each other: a {@code -beta.N} tag published as a normal release, or a plain version tag
 * flagged as a prerelease, is a mislabelled release and is refused in both channels.
 *
 * <p>Driven entirely through the parsing entry points with hand-built release JSON, so the rules
 * are exercised exactly and no test ever touches the network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class UpdateDiscoveryTest {

    static final String CERTIFICATE_SHA256 =
            "7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3";
    static final String APK_SHA256 =
            "a1b2c3d4e5f6071829304152637485960718293a4b5c6d7e8f90112233445566";
    private static final String DOWNLOAD_BASE =
            "https://github.com/lpnovi/Orbit-Assistant/releases/download/";

    // ---- structural eligibility -------------------------------------------------------------------

    @Test public void aNormalStableReleaseIsEligibleInBothChannels() {
        JSONObject release = release("v0.9.9.9", false, false);
        assertTrue(OrbitUpdater.structurallyEligible(release, Prefs.CHANNEL_STABLE));
        assertTrue("Beta is not 'only Beta' — a Stable release is eligible too",
                OrbitUpdater.structurallyEligible(release, Prefs.CHANNEL_BETA));
    }

    @Test public void aBetaPrereleaseIsEligibleOnlyOnBeta() {
        JSONObject release = release("v0.9.9.9-beta.1", true, false);
        assertTrue(OrbitUpdater.structurallyEligible(release, Prefs.CHANNEL_BETA));
        assertFalse("Stable must never see a prerelease",
                OrbitUpdater.structurallyEligible(release, Prefs.CHANNEL_STABLE));
    }

    @Test public void draftsAreIgnoredEverywhere() {
        assertFalse(OrbitUpdater.structurallyEligible(release("v0.9.9.9", false, true),
                Prefs.CHANNEL_STABLE));
        assertFalse(OrbitUpdater.structurallyEligible(release("v0.9.9.9-beta.1", true, true),
                Prefs.CHANNEL_BETA));
    }

    /**
     * The tag shape and GitHub's prerelease flag must agree. Either mismatch means somebody
     * published the wrong thing, and Orbit refuses rather than guessing which half was intended.
     */
    @Test public void aMislabelledReleaseIsRefusedInBothChannels() {
        JSONObject betaTagAsStable = release("v0.9.9.9-beta.1", false, false);
        assertFalse(OrbitUpdater.structurallyEligible(betaTagAsStable, Prefs.CHANNEL_BETA));
        assertFalse(OrbitUpdater.structurallyEligible(betaTagAsStable, Prefs.CHANNEL_STABLE));

        JSONObject stableTagAsPrerelease = release("v0.9.9.9", true, false);
        assertFalse(OrbitUpdater.structurallyEligible(stableTagAsPrerelease, Prefs.CHANNEL_BETA));
        assertFalse(OrbitUpdater.structurallyEligible(stableTagAsPrerelease, Prefs.CHANNEL_STABLE));
    }

    @Test public void malformedTagsAreRefused() {
        for (String tag : new String[]{"v0.9.9.9-beta", "v0.9.9.9-beta.0", "v0.9.9.9-beta.zero",
                "v0.9.9.9-test", "beta-v0.9.9.9", "0.9.9.9", "v"}) {
            assertFalse(tag + " must not be eligible",
                    OrbitUpdater.structurallyEligible(release(tag, true, false), Prefs.CHANNEL_BETA));
            assertFalse(tag + " must not be eligible",
                    OrbitUpdater.structurallyEligible(release(tag, false, false), Prefs.CHANNEL_STABLE));
        }
    }

    @Test public void aReleaseWithNoAssetsIsRefused() throws Exception {
        JSONObject release = release("v0.9.9.9", false, false);
        release.remove("assets");
        assertFalse(OrbitUpdater.structurallyEligible(release, Prefs.CHANNEL_STABLE));
    }

    // ---- full validation against the manifest -----------------------------------------------------

    @Test public void aWellFormedStableReleaseValidates() throws Exception {
        OrbitUpdater.Release parsed = OrbitUpdater.evaluateCandidate(
                release("v0.9.9.9", false, false), manifest("0.9.9.9", 99999L),
                Prefs.CHANNEL_STABLE);
        assertEquals("0.9.9.9", parsed.versionName);
        assertEquals(99999L, parsed.versionCode);
        assertEquals("Orbit-Assistant-v0.9.9.9.apk", parsed.apkAssetName);
        assertFalse(parsed.isBeta());
        assertEquals("0.9.9.9", parsed.displayName());
    }

    @Test public void aWellFormedBetaReleaseValidates() throws Exception {
        OrbitUpdater.Release parsed = OrbitUpdater.evaluateCandidate(
                release("v0.9.9.9-beta.2", true, false), manifest("0.9.9.9-beta.2", 99998L),
                Prefs.CHANNEL_BETA);
        assertEquals("0.9.9.9-beta.2", parsed.versionName);
        assertTrue(parsed.isBeta());
        assertEquals("0.9.9.9 Beta 2", parsed.displayName());
        assertEquals("Orbit-Assistant-v0.9.9.9-beta.2.apk", parsed.apkAssetName);
    }

    /** The manifest has to describe the release it is attached to, in both channels. */
    @Test public void aManifestThatDisagreesWithItsTagIsRejected() {
        rejects(release("v0.9.9.9", false, false), manifest("0.9.9.8", 99999L),
                Prefs.CHANNEL_STABLE, "a manifest naming a different version");
        rejects(release("v0.9.9.9-beta.2", true, false), manifest("0.9.9.9-beta.3", 99999L),
                Prefs.CHANNEL_BETA, "a Beta manifest naming a different beta number");
    }

    @Test public void aManifestWithTheWrongPackageIsRejected() throws Exception {
        JSONObject manifest = manifest("0.9.9.9", 99999L).put("packageName", "com.example.other");
        rejects(release("v0.9.9.9", false, false), manifest, Prefs.CHANNEL_STABLE,
                "a foreign package name");
    }

    @Test public void aManifestWithTheWrongCertificateIsRejected() throws Exception {
        JSONObject manifest = manifest("0.9.9.9", 99999L).put("certificateSha256",
                "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:"
                        + "00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF");
        rejects(release("v0.9.9.9", false, false), manifest, Prefs.CHANNEL_STABLE,
                "a certificate that is not Orbit's permanent one");
    }

    @Test public void anUnsupportedManifestSchemaIsRejected() throws Exception {
        rejects(release("v0.9.9.9", false, false), manifest("0.9.9.9", 99999L).put("schema", 2),
                Prefs.CHANNEL_STABLE, "an unsupported schema");
    }

    @Test public void aMalformedChecksumIsRejected() throws Exception {
        rejects(release("v0.9.9.9", false, false),
                manifest("0.9.9.9", 99999L).put("apkSha256", "not-a-checksum"),
                Prefs.CHANNEL_STABLE, "a malformed APK checksum");
    }

    @Test public void anUnexpectedApkNameIsRejected() throws Exception {
        JSONObject manifest = manifest("0.9.9.9", 99999L)
                .put("apkAssetName", "Orbit-Assistant-v0.9.9.9-modified.apk");
        rejects(release("v0.9.9.9", false, false), manifest, Prefs.CHANNEL_STABLE,
                "an APK name that is not the official one");
    }

    /** An asset served from anywhere but the official release URL is refused. */
    @Test public void anUnofficialAssetUrlIsRejected() throws Exception {
        JSONObject release = release("v0.9.9.9", false, false);
        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if ("orbit-update.json".equals(asset.getString("name"))) {
                asset.put("browser_download_url", "https://example.com/orbit-update.json");
            }
        }
        rejects(release, manifest("0.9.9.9", 99999L), Prefs.CHANNEL_STABLE,
                "an asset hosted somewhere other than the official release");
    }

    @Test public void duplicateAssetsAreRejected() throws Exception {
        JSONObject release = release("v0.9.9.9", false, false);
        release.getJSONArray("assets").put(asset("v0.9.9.9", "orbit-update.json", 900L));
        rejects(release, manifest("0.9.9.9", 99999L), Prefs.CHANNEL_STABLE,
                "a release carrying the manifest twice");
    }

    /** Channel rules are enforced by the full path too, not only by the cheap pre-filter. */
    @Test public void aBetaCandidateIsRefusedOnTheStableChannel() {
        rejects(release("v0.9.9.9-beta.1", true, false), manifest("0.9.9.9-beta.1", 99999L),
                Prefs.CHANNEL_STABLE, "a Beta prerelease offered to a Stable device");
    }

    // ---- choosing between candidates ---------------------------------------------------------------

    /**
     * Ordering is by {@code versionCode} and nothing else, which is what makes the Beta lifecycle
     * work: a Beta tester takes the finished Stable release when it lands, then the next Beta.
     */
    @Test public void theGreatestVersionCodeWins() throws Exception {
        OrbitUpdater.Release betaTwo = parse("v0.7.7.5-beta.2", true, "0.7.7.5-beta.2", 730L);
        OrbitUpdater.Release stable = parse("v0.7.7.5", false, "0.7.7.5", 731L);
        OrbitUpdater.Release nextBeta = parse("v0.7.7.6-beta.1", true, "0.7.7.6-beta.1", 732L);

        assertEquals(731L, OrbitUpdater.bestByVersionCode(list(betaTwo, stable)).versionCode);
        assertEquals(732L, OrbitUpdater.bestByVersionCode(list(betaTwo, stable, nextBeta)).versionCode);
        assertEquals("order of evaluation must not change the winner",
                732L, OrbitUpdater.bestByVersionCode(list(nextBeta, betaTwo, stable)).versionCode);
        assertNull(OrbitUpdater.bestByVersionCode(new ArrayList<>()));
        assertNull(OrbitUpdater.bestByVersionCode(null));
    }

    /** A newer version *name* never beats a higher versionCode. */
    @Test public void versionNamesDoNotDecideTheWinner() throws Exception {
        OrbitUpdater.Release higherCode = parse("v0.7.7.5", false, "0.7.7.5", 800L);
        OrbitUpdater.Release newerName = parse("v0.9.9.9-beta.1", true, "0.9.9.9-beta.1", 700L);
        assertEquals(800L, OrbitUpdater.bestByVersionCode(list(newerName, higherCode)).versionCode);
    }

    // ---- the bounded scan ---------------------------------------------------------------------------

    @Test public void theShortlistDropsEverythingIneligibleAndOrdersTheRest() {
        JSONArray releases = new JSONArray()
                .put(release("v0.7.7.4", false, false))
                .put(release("v0.7.7.6-beta.1", true, false))
                .put(release("v0.7.7.5", false, false))
                .put(release("v0.7.7.5-beta.2", true, false))
                .put(release("v0.7.7.9", false, true))            // draft
                .put(release("v0.7.7.8-beta.0", true, false))      // malformed beta counter
                .put(release("v0.7.7.7-beta.1", false, false));    // mislabelled

        List<JSONObject> shortlist = OrbitUpdater.shortlist(releases, Prefs.CHANNEL_BETA);
        assertEquals(4, shortlist.size());
        assertEquals("v0.7.7.6-beta.1", shortlist.get(0).optString("tag_name"));
        assertEquals("v0.7.7.5", shortlist.get(1).optString("tag_name"));
        assertEquals("v0.7.7.5-beta.2", shortlist.get(2).optString("tag_name"));
        assertEquals("v0.7.7.4", shortlist.get(3).optString("tag_name"));
    }

    @Test public void theStableShortlistContainsNoPrereleases() {
        JSONArray releases = new JSONArray()
                .put(release("v0.7.7.6-beta.1", true, false))
                .put(release("v0.7.7.5", false, false))
                .put(release("v0.7.7.4", false, false));

        List<JSONObject> shortlist = OrbitUpdater.shortlist(releases, Prefs.CHANNEL_STABLE);
        assertEquals(2, shortlist.size());
        for (JSONObject release : shortlist) {
            assertFalse(release.optBoolean("prerelease", true));
            assertTrue(OrbitVersion.isStableTag(release.optString("tag_name")));
        }
    }

    @Test public void anEmptyOrRubbishListYieldsNothing() {
        assertTrue(OrbitUpdater.shortlist(new JSONArray(), Prefs.CHANNEL_BETA).isEmpty());
        assertTrue(OrbitUpdater.shortlist(null, Prefs.CHANNEL_BETA).isEmpty());
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static List<OrbitUpdater.Release> list(OrbitUpdater.Release... releases) {
        List<OrbitUpdater.Release> out = new ArrayList<>();
        for (OrbitUpdater.Release release : releases) out.add(release);
        return out;
    }

    private static OrbitUpdater.Release parse(String tag, boolean prerelease, String versionName,
                                              long versionCode) throws Exception {
        return OrbitUpdater.evaluateCandidate(release(tag, prerelease, false),
                manifest(versionName, versionCode), Prefs.CHANNEL_BETA);
    }

    private static void rejects(JSONObject release, JSONObject manifest, String channel,
                                String why) {
        try {
            OrbitUpdater.evaluateCandidate(release, manifest, channel);
            fail("Orbit must reject " + why);
        } catch (Exception expected) {
            // Fail-closed is the whole point: an invalid release throws rather than returning
            // a partially trusted result.
        }
    }

    private static JSONObject release(String tag, boolean prerelease, boolean draft) {
        try {
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            JSONArray assets = new JSONArray()
                    .put(asset(tag, "orbit-update.json", 900L))
                    .put(asset(tag, "Orbit-Assistant-v" + version + ".apk", 52_000_000L))
                    .put(asset(tag, "Orbit-Assistant-v" + version + ".apk.sha256", 120L));
            return new JSONObject()
                    .put("tag_name", tag)
                    .put("prerelease", prerelease)
                    .put("draft", draft)
                    .put("body", "Release notes for " + tag)
                    .put("assets", assets);
        } catch (Exception e) {
            throw new AssertionError("could not build a release fixture", e);
        }
    }

    private static JSONObject asset(String tag, String name, long size) throws Exception {
        return new JSONObject()
                .put("name", name)
                .put("size", size)
                .put("browser_download_url", DOWNLOAD_BASE + tag + "/" + name);
    }

    private static JSONObject manifest(String versionName, long versionCode) {
        try {
            return new JSONObject()
                    .put("schema", 1)
                    .put("packageName", "com.orbit.assistant")
                    .put("versionName", versionName)
                    .put("versionCode", versionCode)
                    .put("apkAssetName", "Orbit-Assistant-v" + versionName + ".apk")
                    .put("apkSha256", APK_SHA256)
                    .put("certificateSha256", CERTIFICATE_SHA256);
        } catch (Exception e) {
            throw new AssertionError("could not build a manifest fixture", e);
        }
    }

    /** The manifest schema v0.7.7.3 shipped must keep working unchanged. */
    @Test public void theManifestSchemaIsUnchangedFromTheReleaseBefore() throws Exception {
        JSONObject manifest = manifest("0.9.9.9", 99999L);
        assertEquals(1, manifest.getInt("schema"));
        assertNotNull(OrbitUpdater.evaluateCandidate(
                release("v0.9.9.9", false, false), manifest, Prefs.CHANNEL_STABLE));
    }
}

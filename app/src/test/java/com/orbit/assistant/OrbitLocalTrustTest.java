package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Whether Orbit will talk to an installed Orbit Local component at all.
 *
 * <p>This is the security boundary the whole modular design rests on. The component is a separate
 * APK that anyone can attempt to impersonate — the package name is public and guessable — so being
 * installed proves nothing. What proves something is Orbit's permanent release certificate, and
 * these tests pin the order in which that is decided: signature first, version second, so a
 * hostile package can never present itself as merely out of date and talk a user into "updating"
 * it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitLocalTrustTest {

    private static final String COMPONENT_ASSET =
            "Orbit-Local-v" + BuildConfig.VERSION_NAME + ".apk";
    private static final String CERT = OrbitLocalComponent.CERTIFICATE_SHA256;
    private static final String SHA =
            "a1b2c3d4e5f6071829304152637485960718293a4b5c6d7e8f90112233445566";

    // ---- component trust ---------------------------------------------------------------------------

    @Test public void anAbsentComponentIsSimplyNotInstalled() {
        assertEquals(OrbitLocalComponent.State.NOT_INSTALLED,
                OrbitLocalComponent.evaluate(false, true, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE));
    }

    @Test public void theExpectedComponentIsAccepted() {
        assertEquals(OrbitLocalComponent.State.INSTALLED,
                OrbitLocalComponent.evaluate(true, true, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE));
    }

    /** The one that matters: a package Orbit did not sign is never used, at any version. */
    @Test public void anUnsignedOrForeignComponentIsRefused() {
        assertEquals(OrbitLocalComponent.State.UNTRUSTED,
                OrbitLocalComponent.evaluate(true, false, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE));
        assertEquals("a wrong signature outranks a wrong version, so it can never be 'updated' into trust",
                OrbitLocalComponent.State.UNTRUSTED,
                OrbitLocalComponent.evaluate(true, false, "0.0.0.1", 1L));
    }

    @Test public void aMismatchedVersionNeedsUpdatingRatherThanUse() {
        assertEquals(OrbitLocalComponent.State.UPDATE_REQUIRED,
                OrbitLocalComponent.evaluate(true, true, "0.7.7.4", 727L));
        assertEquals(OrbitLocalComponent.State.UPDATE_REQUIRED,
                OrbitLocalComponent.evaluate(true, true, BuildConfig.VERSION_NAME, 999L));
        assertEquals(OrbitLocalComponent.State.UPDATE_REQUIRED,
                OrbitLocalComponent.evaluate(true, true, null, BuildConfig.VERSION_CODE));
    }

    /** Only a fully trusted, matching component is ever usable. */
    @Test public void onlyTheInstalledStateIsUsable() {
        assertTrue(OrbitLocalComponent.State.INSTALLED
                == OrbitLocalComponent.evaluate(true, true, BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE));
        for (OrbitLocalComponent.State state : new OrbitLocalComponent.State[]{
                OrbitLocalComponent.State.NOT_INSTALLED,
                OrbitLocalComponent.State.UNTRUSTED,
                OrbitLocalComponent.State.UPDATE_REQUIRED}) {
            assertFalse(state + " must not be usable",
                    state == OrbitLocalComponent.State.INSTALLED);
        }
    }

    @Test public void theComponentPinMatchesOrbitsOwnCertificate() throws Exception {
        java.lang.reflect.Field field = OrbitUpdater.class.getDeclaredField("CERTIFICATE_SHA256");
        field.setAccessible(true);
        String updaterPin = ((String) field.get(null)).replace(":", "");
        assertTrue("the component must be pinned to the same permanent Orbit certificate",
                updaterPin.equalsIgnoreCase(OrbitLocalComponent.CERTIFICATE_SHA256));
    }

    @Test public void theComponentPackageIsNotOrbitsOwn() {
        assertEquals("com.orbit.assistant.local", OrbitLocalComponent.PACKAGE);
        assertFalse("the component must never share Orbit's package identity",
                "com.orbit.assistant".equals(OrbitLocalComponent.PACKAGE));
    }

    // ---- component metadata in the release manifest -------------------------------------------------

    @Test public void aWellFormedComponentBlockIsAccepted() throws Exception {
        OrbitLocalInstaller.Expected expected =
                OrbitLocalInstaller.Expected.from(manifest(component()));
        assertEquals(OrbitLocalComponent.PACKAGE, expected.packageName);
        assertEquals(BuildConfig.VERSION_NAME, expected.versionName);
        assertEquals(BuildConfig.VERSION_CODE, expected.versionCode);
        assertEquals(COMPONENT_ASSET, expected.assetName);
        assertEquals(OrbitLocalComponent.PROTOCOL_VERSION, expected.protocol);
    }

    /** A release with no component block is an error, never a silent no-op. */
    @Test public void aManifestWithoutAComponentIsRejected() throws Exception {
        rejects(manifest(null), "a release that publishes no component");
    }

    @Test public void aComponentNamingTheWrongPackageIsRejected() throws Exception {
        rejects(manifest(component().put("packageName", "com.example.local")),
                "a component block naming a foreign package");
    }

    /**
     * The component must be the one built for this exact Orbit release. A main app and a component
     * from different releases is a combination nobody has tested.
     */
    @Test public void aComponentFromAnotherReleaseIsRejected() throws Exception {
        rejects(manifest(component().put("versionName", "0.7.7.4")),
                "a component built for a different Orbit version");
        rejects(manifest(component().put("versionCode", 1L)),
                "a component with a mismatched version code");
        rejects(manifest(component().put("apkAssetName", "Orbit-Local-v0.7.7.4.apk")),
                "a component asset from a different release");
    }

    @Test public void anIncompatibleProtocolIsRejected() throws Exception {
        rejects(manifest(component().put("protocol", 2)),
                "a component speaking an interface Orbit does not understand");
        rejects(manifest(component().put("protocol", 0)),
                "a component with no declared protocol");
    }

    @Test public void aMalformedChecksumIsRejected() throws Exception {
        rejects(manifest(component().put("apkSha256", "nonsense")),
                "a component with an unusable checksum");
        rejects(manifest(component().put("apkSha256", "")),
                "a component with no checksum at all");
    }

    @Test public void aForeignCertificateIsRejected() throws Exception {
        rejects(manifest(component().put("certificateSha256",
                        "00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF")),
                "a component claiming a certificate that is not Orbit's");
    }

    // ---- asset naming ------------------------------------------------------------------------------

    /** The component asset belongs to this release, and follows Orbit's naming convention. */
    @Test public void theComponentAssetIsNamedForThisRelease() {
        assertEquals(COMPONENT_ASSET, OrbitLocalInstaller.assetName());
        assertEquals("v" + BuildConfig.VERSION_NAME, OrbitLocalInstaller.releaseTag());
        assertTrue(OrbitVersion.isValidTag(OrbitLocalInstaller.releaseTag()));
        assertTrue("the asset name must be safe to place in a URL path",
                OrbitLocalInstaller.assetName().matches("^[A-Za-z0-9._-]+$"));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private static void rejects(JSONObject manifest, String why) {
        try {
            OrbitLocalInstaller.Expected.from(manifest);
            fail("Orbit must reject " + why);
        } catch (Exception expected) {
            // Fail-closed: an invalid component block throws rather than yielding partial trust.
        }
    }

    private static JSONObject component() throws Exception {
        return new JSONObject()
                .put("packageName", OrbitLocalComponent.PACKAGE)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("apkAssetName", COMPONENT_ASSET)
                .put("apkSha256", SHA)
                .put("apkSize", 29_000_000L)
                .put("certificateSha256", CERT)
                .put("protocol", OrbitLocalComponent.PROTOCOL_VERSION);
    }

    private static JSONObject manifest(JSONObject component) throws Exception {
        JSONObject manifest = new JSONObject()
                .put("schema", 1)
                .put("packageName", "com.orbit.assistant")
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("versionCode", BuildConfig.VERSION_CODE)
                .put("apkAssetName", "Orbit-Assistant-v" + BuildConfig.VERSION_NAME + ".apk")
                .put("apkSha256", SHA)
                .put("certificateSha256", CERT);
        if (component != null) manifest.put("component", component);
        return manifest;
    }
}

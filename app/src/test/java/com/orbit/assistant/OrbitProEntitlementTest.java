package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Orbit Pro Phase 0: one entitlement boundary, and the rules that make it trustworthy.
 *
 * <p>This file is deliberately more interested in what must <em>not</em> happen than in what must.
 * The entitlement API itself is four methods and would be hard to get wrong. The failures worth
 * paying for tests are the ones that are invisible until they matter: a Stable build honoring a
 * preference a Beta wrote, a premium feature reaching past the boundary to ask Google Play
 * directly, and an existing free feature quietly acquiring a gate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class OrbitProEntitlementTest {

    private Context context;

    /** A provider that always entitles, standing in for the Google Play one that does not exist. */
    private static final class AlwaysPro implements OrbitProEntitlementProvider {
        @Override public String name() { return "Test Store"; }
        @Override public boolean hasPro(Context context) { return true; }
    }

    /** A registered provider that happens not to entitle this device. */
    private static final class NeverPro implements OrbitProEntitlementProvider {
        @Override public String name() { return "Test Store"; }
        @Override public boolean hasPro(Context context) { return false; }
    }

    private static final String STABLE_BUILD = "0.8.0.0";
    private static final String BETA_BUILD = "0.8.0.0-beta.1";

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- defaults ---------------------------------------------------------------------------------

    /** 1. Nothing about a fresh install is Pro. */
    @Test public void aFreshInstallIsFree() {
        assertEquals("Free", Prefs.proPreviewLabel(Prefs.proPreview(context)));
        assertFalse("no provider exists, so nothing can entitle a device",
                OrbitProEntitlement.resolve(false, false, OrbitProEntitlement.providers(), context));
        assertTrue("and the shipped provider list is genuinely empty",
                OrbitProEntitlement.providers().isEmpty());
    }

    /** 2. The preview selection starts at Free, even on a build that may honor it. */
    @Test public void theDefaultPreviewSelectionIsFree() {
        assertEquals(Prefs.PRO_PREVIEW_FREE, Prefs.proPreview(context));
        assertFalse(Prefs.proPreviewSelected(context));
        assertFalse("a debug build with nothing selected is Free", OrbitProEntitlement.hasPro(context));
    }

    /** An unrecognised or corrupted stored value can never be the unlocked one. */
    @Test public void anUnrecognisedStoredValueFallsBackToFree() {
        for (String junk : new String[]{"", "Pro", "PRO", "true", "pro_preview", "premium"}) {
            Prefs.get(context).edit().putString(Prefs.PRO_PREVIEW, junk).commit();
            assertEquals("'" + junk + "' must resolve to Free",
                    Prefs.PRO_PREVIEW_FREE, Prefs.proPreview(context));
            assertFalse(OrbitProEntitlement.hasPro(context));
        }
    }

    // ---- persistence ------------------------------------------------------------------------------

    /**
     * 3. The selection survives everything a tester will do to it.
     *
     * <p>Written with {@code commit}, so it is on disk before the call returns. Reading it back
     * through a freshly obtained preferences handle is what a relaunched process does, and an
     * in-place Beta upgrade keeps the same preferences file, so this is the same assertion for
     * process death, app restart, device restart and installing the next Beta over this one.
     */
    @Test public void theSelectionPersists() {
        assertTrue(Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO));
        assertEquals(Prefs.PRO_PREVIEW_PRO,
                context.getSharedPreferences("orbit_prefs", Context.MODE_PRIVATE)
                        .getString(Prefs.PRO_PREVIEW, Prefs.PRO_PREVIEW_FREE));
        assertTrue(Prefs.proPreviewSelected(context));

        assertTrue(Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE));
        assertEquals(Prefs.PRO_PREVIEW_FREE, Prefs.proPreview(context));
    }

    /**
     * A developer override must never travel to another phone in a backup.
     *
     * <p>Same reasoning as the update channel, which is also excluded: this is a statement about
     * the build on this device, and restoring somebody's backup must not hand them a Pro state
     * they never chose.
     */
    @Test public void theSelectionIsNeverIncludedInABackup() throws Exception {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        JSONObject snapshot = Prefs.backupSnapshot(context);
        assertFalse("the preview override must not be backed up",
                snapshot.has(Prefs.PRO_PREVIEW));
        assertFalse("and a backup claiming to carry one must be refused",
                Prefs.validBackupSnapshot(new JSONObject().put(Prefs.PRO_PREVIEW, "pro")));
    }

    // ---- the preview override ---------------------------------------------------------------------

    /** 4. Free forces Free through the public API. */
    @Test public void thePreviewFreeSelectionReportsNoPro() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertTrue("this is a debug test build, so the override is live",
                OrbitProEntitlement.previewAvailable());
        assertFalse(OrbitProEntitlement.hasPro(context));
        assertEquals("Free (preview override)", OrbitProEntitlement.status(context));
    }

    /** 5. Pro Preview forces Pro through the public API. */
    @Test public void theProPreviewSelectionReportsPro() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue(OrbitProEntitlement.hasPro(context));
        assertEquals("Pro Preview", OrbitProEntitlement.status(context));
    }

    /**
     * 6. A change is effective on the next question, with nothing restarted or invalidated.
     *
     * <p>The whole reason the entitlement is read rather than cached. A premium control that only
     * unlocked after a relaunch would make every future Pro feature untestable in one sitting.
     */
    @Test public void changingTheSelectionTakesEffectImmediately() {
        assertFalse(OrbitProEntitlement.hasPro(context));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue("no restart, no invalidation, no listener", OrbitProEntitlement.hasPro(context));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertFalse(OrbitProEntitlement.hasPro(context));
    }

    /**
     * The override beats a real entitlement, which is the point of it.
     *
     * <p>A developer device that owns Orbit Pro through a store must still be able to see the
     * locked experience, or nobody can check what everyone else sees. So when the preview is
     * allowed, the providers are not consulted at all.
     */
    @Test public void theOverrideBeatsAnEntitlingProvider() {
        List<OrbitProEntitlementProvider> owned = Collections.singletonList(new AlwaysPro());
        assertFalse("selecting Free must lock a device that genuinely owns Pro",
                OrbitProEntitlement.resolve(true, false, owned, context));
        assertTrue(OrbitProEntitlement.resolve(true, true, owned, context));
    }

    // ---- which builds may offer it ------------------------------------------------------------------

    /** 7. A debug build is eligible, whatever its version name says. */
    @Test public void debugBuildsAreEligibleForThePreview() {
        assertTrue(OrbitProEntitlement.previewAvailable(true, STABLE_BUILD));
        assertTrue(OrbitProEntitlement.previewAvailable(true, BETA_BUILD));
        assertTrue("and this test build is one", OrbitProEntitlement.previewAvailable());
    }

    /** 8. A Beta is eligible without being a debug build. */
    @Test public void betaBuildsAreEligibleForThePreview() {
        assertTrue(OrbitProEntitlement.previewAvailable(false, BETA_BUILD));
        assertTrue(OrbitProEntitlement.previewAvailable(false, "0.8.1.0-beta.4"));
    }

    /** 9. A Stable build is not, and neither is anything Orbit would not publish. */
    @Test public void stableBuildsAreNotEligibleForThePreview() {
        assertFalse(OrbitProEntitlement.previewAvailable(false, STABLE_BUILD));
        assertFalse(OrbitProEntitlement.previewAvailable(false, "0.7.8.5"));
        for (String malformed : new String[]{"0.8.0.0-beta", "0.8.0.0-beta.0", "0.8.0.0-beta.zero",
                "", null}) {
            assertFalse("a malformed version is not a Beta and must not unlock the override",
                    OrbitProEntitlement.previewAvailable(false, malformed));
        }
    }

    // ---- Stable safety ------------------------------------------------------------------------------

    /**
     * 10 and 11. The exact upgrade path that would be a security hole if it worked.
     *
     * <p>Install a Beta, select Pro Preview, install Stable over it. Android keeps the preferences
     * file across an in-place update, so the value is still there and still says Pro. The Stable
     * build must resolve Free anyway, because it never reads it.
     */
    @Test public void aStoredBetaPreviewCannotUnlockStable() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue("the Beta's choice really is stored", Prefs.proPreviewSelected(context));

        boolean stableEligible = OrbitProEntitlement.previewAvailable(false, STABLE_BUILD);
        assertFalse(stableEligible);
        assertFalse("Stable must ignore the stored Pro Preview entirely",
                OrbitProEntitlement.resolve(stableEligible, Prefs.proPreviewSelected(context),
                        OrbitProEntitlement.providers(), context));

        assertTrue("and the value stays for the next Beta rather than being erased",
                Prefs.proPreviewSelected(context));
    }

    /**
     * 12. Stable resolves through providers, and would report Pro if one entitled the device.
     *
     * <p>Without this the previous test would pass for the wrong reason: a Stable build that
     * always returned false regardless of providers would satisfy it, and would also break the
     * Google Play line the moment it shipped.
     */
    @Test public void stableFallsThroughToTheNormalProviders() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        List<OrbitProEntitlementProvider> owned = Collections.singletonList(new AlwaysPro());
        assertTrue("a genuine provider must entitle a Stable device",
                OrbitProEntitlement.resolve(false, true, owned, context));

        assertFalse("a registered provider that does not entitle leaves it Free",
                OrbitProEntitlement.resolve(false, true,
                        Collections.singletonList(new NeverPro()), context));
        assertFalse("and an empty list is Free, which is Orbit today",
                OrbitProEntitlement.resolve(false, true, Collections.emptyList(), context));
    }

    /** Any one entitling provider is enough, and the one that answered is the one named. */
    @Test public void theFirstEntitlingProviderIsTheOneReported() {
        List<OrbitProEntitlementProvider> mixed = new ArrayList<>();
        mixed.add(new NeverPro());
        mixed.add(new AlwaysPro());
        OrbitProEntitlementProvider granting =
                OrbitProEntitlement.grantingProvider(mixed, context);
        assertNotNull(granting);
        assertEquals("Test Store", granting.name());
        assertTrue(OrbitProEntitlement.resolve(false, false, mixed, context));
    }

    // ---- the boundary itself --------------------------------------------------------------------------

    /**
     * 13. Provider resolution stays centralized, and billing never leaks past the boundary.
     *
     * <p>This is the assertion the whole phase exists for. A Pro feature that reached for
     * BillingClient directly would work perfectly and would also make Orbit Pro impossible to
     * change later, which is exactly the kind of mistake no device test catches.
     */
    @Test public void nothingOutsideTheEntitlementLayerKnowsAboutBilling() {
        List<Path> sources = mainSources();
        assertTrue("the main source tree must have been found", sources.size() > 100);
        for (Path source : sources) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitProEntitlement.java")
                    || name.equals("OrbitProEntitlementProvider.java")) continue;
            String body = read(source);
            for (String billing : new String[]{"BillingClient", "com.android.billingclient",
                    "SkuDetails", "ProductDetails", "purchaseToken", "acknowledgePurchase"}) {
                assertFalse(name + " must not reach past the entitlement boundary for " + billing,
                        body.contains(billing));
            }
        }
    }

    /** And the stored override is read in exactly one place: the entitlement layer and its prefs. */
    @Test public void theStoredOverrideIsReadInOnlyOnePlace() {
        List<String> readers = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("Prefs.java")) continue;
            if (read(source).contains("proPreviewSelected(")) readers.add(name);
        }
        assertEquals("only OrbitProEntitlement may turn the stored override into entitlement: "
                + readers, Collections.singletonList("OrbitProEntitlement.java"), readers);
    }

    /**
     * 14. Only genuinely new capability is gated, and the list of gates is named.
     *
     * <p>Phase 0 asserted that nothing called {@link OrbitProEntitlement#hasPro} at all, which was
     * the right assertion while the entitlement unlocked nothing. Beta 2 gives it something to
     * unlock, so the inverse is what protects the product rule now: the callers are enumerated, and
     * adding one is a deliberate edit to this list rather than something that happens quietly.
     *
     * <p>All three are Theme Studio Pro, which is new capability that has never been free. Nothing
     * that existed before it appears here, and the free theme model, the file codec and the token
     * resolver are asserted elsewhere to not consult entitlement at all.
     */
    @Test public void onlyNewPremiumCapabilityIsGatedByEntitlement() {
        List<String> callers = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitProEntitlement.java")) continue;
            String body = read(source);
            if (body.contains("OrbitProEntitlement.hasPro(")) callers.add(name);
        }
        Collections.sort(callers);
        assertEquals("the entitlement gates exactly these, and they are all Theme Studio Pro: "
                        + callers,
                java.util.Arrays.asList("OrbitProStyle.java", "OrbitThemeStore.java",
                        "ThemeStudioActivity.java"),
                callers);
    }

    /**
     * And nothing that was free before this release acquired a gate.
     *
     * <p>The product rule stated the way it would actually be broken: not by the number of callers
     * growing, but by one of them appearing in a file that implements something people already
     * had. These are the free surfaces premium styling sits closest to.
     */
    @Test public void noPreviouslyFreeFeatureAcquiredAGate() {
        for (String free : new String[]{"OrbitTheme.java", "OrbitThemeFileCodec.java",
                "OrbitThemeTokens.java", "ThemePreviewView.java", "OrbitGlass.java",
                "ChatActivity.java", "OrbitSession.java", "MainActivity.java",
                "OrbitVaultActivity.java", "SettingsActivity.java", "OnboardingActivity.java"}) {
            Path source = null;
            for (Path candidate : mainSources()) {
                if (candidate.getFileName().toString().equals(free)) source = candidate;
            }
            assertNotNull(free + " must exist to be checked", source);
            assertFalse(free + " was free before Orbit Pro and must not gate on it",
                    read(source).contains("OrbitProEntitlement.hasPro("));
        }
    }

    /** And nothing in the app nags, badges or offers to sell anything. */
    @Test public void nothingOffersToSellOrbitPro() {
        for (Path source : mainSources()) {
            String body = read(source);
            for (String selling : new String[]{"\"Upgrade to Pro\"", "\"Unlock Pro\"",
                    "\"Restore purchase", "\"Buy Orbit Pro", "\"Subscribe"}) {
                assertFalse(source.getFileName() + " must not offer a purchase that does not exist",
                        body.contains(selling));
            }
        }
    }

    // ---- reading the source tree ------------------------------------------------------------------

    private static List<Path> mainSources() {
        Path start = Paths.get("").toAbsolutePath();
        Path root = null;
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) {
                root = directory;
                break;
            }
        }
        assertNotNull("repository root was not found above " + start, root);
        Path java = root.resolve("app/src/main/java/com/orbit/assistant");
        assertTrue("the main source directory is missing", Files.isDirectory(java));
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(java)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(found::add);
        } catch (Exception e) {
            throw new AssertionError("could not read the main source tree", e);
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + file, e);
        }
    }
}

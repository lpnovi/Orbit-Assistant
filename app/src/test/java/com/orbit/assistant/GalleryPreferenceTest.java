package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

/**
 * One Gallery preference has to produce one picker choice for both the full chat and the overlay
 * bridge, and reading it must never rewrite it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class GalleryPreferenceTest {
    private static final String GALLERY = "com.sec.android.gallery3d";

    private Context context;
    private ShadowPackageManager packageManager;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        packageManager = Shadows.shadowOf(context.getPackageManager());
    }

    /**
     * Makes a package answer ACTION_PICK for images, the way a real gallery app does. Registered
     * both with and without an explicit package, because resolution compares the package and the
     * discovery query does not set one.
     */
    private void installGallery(String packageName) {
        packageManager.addResolveInfoForIntent(imagePick(null), resolveInfo(packageName));
        packageManager.addResolveInfoForIntent(imagePick(packageName), resolveInfo(packageName));
    }

    private static Intent imagePick(String packageName) {
        Intent pick = new Intent(Intent.ACTION_PICK);
        pick.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        if (packageName != null) pick.setPackage(packageName);
        return pick;
    }

    private static ResolveInfo resolveInfo(String packageName) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = packageName + ".PickerActivity";
        return info;
    }

    @Test public void noStoredChoiceMeansTheSystemPicker() {
        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE,
                GalleryAppPreference.preferredPackage(context));
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());
    }

    @Test public void explicitlyChoosingTheSystemPickerIsHonoured() {
        installGallery(GALLERY);
        GalleryAppPreference.setPreferredPackage(context, GalleryAppPreference.SYSTEM_PACKAGE);

        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE,
                GalleryAppPreference.preferredPackage(context));
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());
    }

    @Test public void aChosenInstalledAppResolvesToThatPackage() {
        installGallery(GALLERY);
        GalleryAppPreference.setPreferredPackage(context, GALLERY);

        assertEquals(GALLERY, GalleryAppPreference.preferredPackage(context));
        Intent intent = GalleryAppPreference.createIntent(context);
        assertEquals("the chosen gallery must be launched, not the system picker",
                GALLERY, intent.getPackage());
        assertEquals(Intent.ACTION_PICK, intent.getAction());
    }

    @Test public void changingTheChoiceTakesEffectOnTheVeryNextRead() {
        installGallery(GALLERY);

        GalleryAppPreference.setPreferredPackage(context, GalleryAppPreference.SYSTEM_PACKAGE);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());

        // No restart, no session recreation: the next resolution already uses the new choice.
        GalleryAppPreference.setPreferredPackage(context, GALLERY);
        assertEquals(GALLERY, GalleryAppPreference.createIntent(context).getPackage());

        GalleryAppPreference.setPreferredPackage(context, GalleryAppPreference.SYSTEM_PACKAGE);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());
    }

    @Test public void readingTheChoiceNeverRewritesIt() {
        installGallery(GALLERY);
        GalleryAppPreference.setPreferredPackage(context, GALLERY);

        for (int i = 0; i < 5; i++) {
            GalleryAppPreference.preferredPackage(context);
            GalleryAppPreference.createIntent(context);
        }
        assertEquals("resolving must not mutate the stored choice",
                GALLERY, GalleryAppPreference.storedPackage(context));
    }

    @Test public void anUnresolvableChoiceFallsBackWithoutErasingTheSelection() {
        // Nothing is installed for this package in this test's package manager.
        GalleryAppPreference.setPreferredPackage(context, "com.example.absent");

        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE,
                GalleryAppPreference.preferredPackage(context));
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());
        // A single unresolvable lookup previously wiped the choice for every surface.
        assertEquals("the user's selection must survive a failed lookup",
                "com.example.absent", GalleryAppPreference.storedPackage(context));
    }

    @Test public void aChoiceRecoversOnceItsAppResolvesAgain() {
        GalleryAppPreference.setPreferredPackage(context, GALLERY);
        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE,
                GalleryAppPreference.preferredPackage(context));

        // Because the selection was kept, it starts working again by itself.
        installGallery(GALLERY);
        assertEquals(GALLERY, GalleryAppPreference.preferredPackage(context));
    }

    @Test public void theBridgeLaunchesExactlyTheTargetTheCallerResolved() {
        installGallery(GALLERY);
        GalleryAppPreference.setPreferredPackage(context, GALLERY);

        // What OrbitSession puts on the bridge Intent.
        String handedToBridge = GalleryAppPreference.preferredPackage(context);
        assertEquals(GALLERY, handedToBridge);

        // What the bridge then launches, without resolving the preference again.
        Intent bridgeIntent = GalleryAppPreference.intentForPackage(context, handedToBridge);
        assertNotNull(bridgeIntent);
        assertEquals(GALLERY, bridgeIntent.getPackage());
    }

    @Test public void bothSurfacesResolveToTheSameTarget() {
        installGallery(GALLERY);
        for (String choice : new String[]{GalleryAppPreference.SYSTEM_PACKAGE, GALLERY}) {
            GalleryAppPreference.setPreferredPackage(context, choice);

            Intent fullChat = GalleryAppPreference.createIntent(context);
            String resolved = GalleryAppPreference.preferredPackage(context);
            Intent overlay = resolved.isEmpty()
                    ? GalleryAppPreference.systemPickerIntent()
                    : GalleryAppPreference.intentForPackage(context, resolved);

            assertNotNull(overlay);
            assertEquals("the two surfaces disagreed for choice '" + choice + "'",
                    fullChat.getPackage(), overlay.getPackage());
            assertEquals(fullChat.getAction(), overlay.getAction());
        }
    }

    @Test public void aBridgeTargetThatCannotResolveYieldsNoIntentRatherThanAWrongOne() {
        // The bridge treats this as "fall back for this launch only", never as a reason to clear.
        assertNull(GalleryAppPreference.intentForPackage(context, "com.example.absent"));
        assertNull(GalleryAppPreference.intentForPackage(context, ""));
        assertNull(GalleryAppPreference.intentForPackage(context, null));
    }

    @Test public void theSystemPickerIntentIsAlwaysUsable() {
        Intent intent = GalleryAppPreference.systemPickerIntent();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertEquals("image/*", intent.getType());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertNull("the system picker must not be pinned to a package", intent.getPackage());
    }

    @Test public void theOptionsListAlwaysOffersTheSystemPicker() {
        assertTrue(GalleryAppPreference.options(context).size() >= 1);
        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE,
                GalleryAppPreference.options(context).get(0).packageName);
    }
}

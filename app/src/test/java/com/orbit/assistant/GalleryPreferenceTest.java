package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

import java.util.List;

/**
 * The Gallery choice is stored as an exact picker Activity so every surface, including the
 * Side-button bridge on a cold process, launches the same component.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class GalleryPreferenceTest {
    private static final String GALLERY = "com.sec.android.gallery3d";
    private static final String GALLERY_ACTIVITY = GALLERY + ".app.PickerActivity";

    private Context context;
    private ShadowPackageManager packageManager;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        packageManager = Shadows.shadowOf(context.getPackageManager());
    }

    private void installGallery(String packageName, String activityName) {
        packageManager.addResolveInfoForIntent(imagePick(null), resolveInfo(packageName, activityName));
        packageManager.addResolveInfoForIntent(imagePick(packageName), resolveInfo(packageName, activityName));
    }

    private static Intent imagePick(String packageName) {
        Intent pick = new Intent(Intent.ACTION_PICK);
        pick.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        if (packageName != null) pick.setPackage(packageName);
        return pick;
    }

    private static ResolveInfo resolveInfo(String packageName, String activityName) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = activityName;
        return info;
    }

    private GalleryAppPreference.Option optionFor(String packageName) {
        for (GalleryAppPreference.Option option : GalleryAppPreference.options(context)) {
            if (option.packageName.equals(packageName)) return option;
        }
        return null;
    }

    @Test public void noChoiceMeansTheSystemPicker() {
        assertTrue(GalleryAppPreference.storedTarget(context).isSystem());
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.createIntent(context).getAction());
    }

    @Test public void discoveryExposesTheExactPickerActivity() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        GalleryAppPreference.Option option = optionFor(GALLERY);

        assertNotNull("the gallery must be discoverable", option);
        assertEquals(GALLERY, option.packageName);
        assertEquals("the exact Activity is what makes the choice durable",
                GALLERY_ACTIVITY, option.className);
        assertEquals(Intent.ACTION_PICK, option.action);
    }

    @Test public void selectingAnAppPersistsPackageComponentAndAction() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        GalleryAppPreference.setPreferredOption(context, optionFor(GALLERY));

        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertFalse(target.isSystem());
        assertFalse(target.needsResolution());
        assertEquals(GALLERY, target.packageName);
        assertEquals(GALLERY_ACTIVITY, target.className);
        assertEquals(Intent.ACTION_PICK, target.action);
    }

    @Test public void theChoiceSurvivesWithoutAnyPackageLookup() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        GalleryAppPreference.setPreferredOption(context, optionFor(GALLERY));

        // A cold process where resolution answers differently must still reach the same target,
        // because nothing is resolved again - the component was already stored.
        ShadowPackageManager empty = Shadows.shadowOf(context.getPackageManager());
        empty.removeResolveInfosForIntent(imagePick(GALLERY), GALLERY);
        empty.removeResolveInfosForIntent(imagePick(null), GALLERY);

        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertEquals(GALLERY, target.packageName);
        assertEquals(GALLERY_ACTIVITY, target.className);

        Intent intent = GalleryAppPreference.intentForTarget(target);
        assertNotNull(intent);
        assertNotNull("the launch must name the exact component", intent.getComponent());
        assertEquals(GALLERY, intent.getComponent().getPackageName());
        assertEquals(GALLERY_ACTIVITY, intent.getComponent().getClassName());
    }

    @Test public void anOlderPackageOnlyChoiceMigratesToAnExactTarget() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        // Exactly what earlier Orbit versions stored.
        Prefs.get(context).edit().putString(Prefs.GALLERY_APP_PACKAGE, GALLERY).commit();

        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertEquals(GALLERY, target.packageName);
        assertEquals("the old choice must be upgraded, not discarded",
                GALLERY_ACTIVITY, target.className);

        // And the migration is persisted, so it only happens once.
        assertEquals(GALLERY_ACTIVITY,
                GalleryAppPreference.storedTarget(context).className);
    }

    @Test public void anUnresolvableOlderChoiceIsKeptRatherThanErased() {
        Prefs.get(context).edit().putString(Prefs.GALLERY_APP_PACKAGE, "com.example.absent").commit();

        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertFalse("it must not silently become the system picker", target.isSystem());
        assertTrue(target.needsResolution());
        assertEquals("com.example.absent", GalleryAppPreference.storedPackage(context));
        // Nothing can be launched for it, which the caller reports rather than papering over.
        assertNull(GalleryAppPreference.intentForTarget(target));
    }

    @Test public void anUnresolvedChoiceRecoversOnceItsAppAppears() {
        Prefs.get(context).edit().putString(Prefs.GALLERY_APP_PACKAGE, GALLERY).commit();
        assertTrue(GalleryAppPreference.storedTarget(context).needsResolution());

        installGallery(GALLERY, GALLERY_ACTIVITY);
        assertEquals(GALLERY_ACTIVITY, GalleryAppPreference.storedTarget(context).className);
    }

    @Test public void switchingChoicesNeverLeavesMixedFields() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        installGallery("com.other.gallery", "com.other.gallery.Pick");

        GalleryAppPreference.setPreferredOption(context, optionFor(GALLERY));
        GalleryAppPreference.setPreferredOption(context, optionFor("com.other.gallery"));
        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertEquals("com.other.gallery", target.packageName);
        assertEquals("the component must never belong to the previous app",
                "com.other.gallery.Pick", target.className);

        GalleryAppPreference.setPreferredOption(context, null);
        assertTrue(GalleryAppPreference.storedTarget(context).isSystem());
        assertEquals("", GalleryAppPreference.storedPackage(context));
    }

    @Test public void bothSurfacesLaunchTheSameComponent() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        GalleryAppPreference.setPreferredOption(context, optionFor(GALLERY));

        Intent fullChat = GalleryAppPreference.createIntent(context);
        // What the overlay puts on the bridge Intent, rebuilt on the far side.
        GalleryAppPreference.Target passed = GalleryAppPreference.storedTarget(context);
        Intent bridge = GalleryAppPreference.intentForTarget(
                new GalleryAppPreference.Target(passed.packageName, passed.className, passed.action));

        assertNotNull(bridge);
        assertEquals(fullChat.getComponent(), bridge.getComponent());
        assertEquals(fullChat.getAction(), bridge.getAction());
    }

    @Test public void systemModeIsCarriedAcrossExplicitly() {
        installGallery(GALLERY, GALLERY_ACTIVITY);
        GalleryAppPreference.setPreferredOption(context, null);

        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);
        assertTrue(target.isSystem());
        assertNull("system mode must not produce a component intent",
                GalleryAppPreference.intentForTarget(target));
        assertEquals(Intent.ACTION_OPEN_DOCUMENT,
                GalleryAppPreference.systemPickerIntent().getAction());
    }

    @Test public void anExplicitChoiceNeverResolvesToTheSystemPicker() {
        Prefs.get(context).edit().putString(Prefs.GALLERY_APP_PACKAGE, "com.example.absent").commit();
        GalleryAppPreference.Target target = GalleryAppPreference.storedTarget(context);

        // The bridge reports this as a failure instead of quietly opening the system picker.
        assertFalse(target.isSystem());
        assertNull(GalleryAppPreference.intentForTarget(target));
        assertEquals("com.example.absent", GalleryAppPreference.storedPackage(context));
    }

    @Test public void theOptionsListAlwaysOffersTheSystemPickerFirst() {
        List<GalleryAppPreference.Option> options = GalleryAppPreference.options(context);
        assertTrue(options.size() >= 1);
        assertEquals(GalleryAppPreference.SYSTEM_PACKAGE, options.get(0).packageName);
        assertEquals("System picker", options.get(0).label);
    }

    @Test public void aTokenIsPendingOnlyUntilItsResultArrives() {
        final int[] calls = {0};
        String token = AttachmentBridge.register((attachment, error) -> calls[0]++);
        assertTrue(AttachmentBridge.isPending(token));

        AttachmentBridge.deliver(token, null, "");
        assertEquals(1, calls[0]);
        assertFalse("a delivered token must not look in flight", AttachmentBridge.isPending(token));

        // A later lifecycle fallback cannot deliver a second time.
        AttachmentBridge.deliver(token, null, "");
        assertEquals(1, calls[0]);
    }

    @Test public void cancellingATokenAlsoEndsItsPendingState() {
        final int[] calls = {0};
        String token = AttachmentBridge.register((attachment, error) -> calls[0]++);
        AttachmentBridge.cancel(token);

        assertFalse(AttachmentBridge.isPending(token));
        AttachmentBridge.deliver(token, null, "");
        assertEquals("a cancelled token must never fire", 0, calls[0]);
    }

    @Test public void repeatedPickerFlowsEachCompleteCleanly() {
        // Gallery, cancel, Gallery again: no flow may leave the next one blocked.
        for (int i = 0; i < 5; i++) {
            final int[] calls = {0};
            String token = AttachmentBridge.register((attachment, error) -> calls[0]++);
            assertTrue(AttachmentBridge.isPending(token));
            AttachmentBridge.deliver(token, null, "");
            assertEquals(1, calls[0]);
            assertFalse(AttachmentBridge.isPending(token));
        }
    }

    @Test public void anUnknownTokenIsNeverPending() {
        assertFalse(AttachmentBridge.isPending(null));
        assertFalse(AttachmentBridge.isPending(""));
        assertFalse(AttachmentBridge.isPending("never-registered"));
    }
}

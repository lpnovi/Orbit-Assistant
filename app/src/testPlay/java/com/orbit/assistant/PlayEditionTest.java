package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Google Play edition, tested as the Play variant itself.
 *
 * <p>Runs only as testPlayUnitTest, so BuildConfig, OrbitEdition, the merged manifest, and the
 * resources are the real Play build's, not a simulation of them. Every assertion here is about what
 * a Play install can and cannot do, not about how the code is laid out.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayEditionTest {
    private Context context;
    private ActivityController<?> controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    // ---- identity -----------------------------------------------------------------------------------

    @Test public void thePlayBuildIdentifiesItselfAsGooglePlay() {
        assertEquals("play", OrbitEdition.ID);
        assertEquals(OrbitDistribution.Channel.PLAY, OrbitDistribution.current());
        assertTrue(OrbitDistribution.isPlay());
        assertFalse(OrbitDistribution.selfUpdates());
        assertFalse(OrbitDistribution.installsOrbitLocalComponent());
        assertEquals("Google Play", OrbitDistribution.label());
    }

    @Test public void thePlayBuildIsTheSameNonDebuggableApplication() {
        assertEquals("com.orbit.assistant", BuildConfig.APPLICATION_ID);
        assertEquals("com.orbit.assistant", context.getPackageName());
        assertEquals("play", BuildConfig.BUILD_TYPE);
        assertFalse("a Play bundle is never debuggable", BuildConfig.DEBUG);
        assertEquals(0, context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE);
    }

    // ---- no GitHub self-update ----------------------------------------------------------------------

    @Test public void theEditionHasNoGitHubEndpointOrInstallerHandOff() {
        expectRefusal(OrbitEdition::latestReleaseApi);
        expectRefusal(() -> OrbitEdition.releasesListApi(15));
        expectRefusal(OrbitEdition::releaseDownloadBase);
        expectRefusal(() -> OrbitEdition.apkInstallerIntent(Uri.parse("content://x/y.apk")));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        expectRefusal(() -> OrbitEdition.openUnknownSourcesSettings(activity));
        assertFalse(OrbitEdition.canRequestPackageInstalls(context));
        assertFalse(OrbitUpdater.canRequestPackageInstalls(context));
        assertNull("nothing may have been launched", shadowOf(activity).getNextStartedActivity());
    }

    @Test public void anUpdateCheckRefusesBeforeReachingTheNetwork() {
        try {
            OrbitUpdater.checkNow(context);
            fail("the Play edition must not check GitHub for Orbit updates");
        } catch (Exception e) {
            assertEquals(OrbitDistribution.PLAY_REFUSAL, e.getMessage());
        }
        assertEquals("a refused check is not recorded as a check", 0L, OrbitUpdater.lastCheckMs(context));
    }

    @Test public void aCachedGitHubReleaseIsNeverOffered() throws Exception {
        Prefs.get(context).edit().putString("orbit_update_cached_release", validRelease().toString())
                .commit();
        assertNull(OrbitUpdater.loadCachedAvailable(context));
    }

    @Test public void aDownloadIsRefusedSynchronouslyAndWritesNothing() throws Exception {
        AtomicReference<String> error = new AtomicReference<>();
        OrbitUpdater.downloadAsync(context, release(), new OrbitUpdater.DownloadCallback() {
            @Override public void onProgress(int percent) { fail("no download may start"); }
            @Override public void onVerifying() { fail("nothing to verify"); }
            @Override public void onReady(File apk) { fail("nothing may be ready"); }
            @Override public void onError(String message, boolean verificationFailure) {
                error.set(message);
            }
            @Override public void onCancelled() { fail("nothing to cancel"); }
        });
        assertEquals(OrbitDistribution.PLAY_REFUSAL, error.get());
        File updates = new File(context.getCacheDir(), "updates");
        String[] left = updates.list();
        assertTrue("no update file may exist", left == null || left.length == 0);
    }

    @Test public void theInstallerHandOffIsRefusedEvenForAFileInPlace() throws Exception {
        OrbitUpdater.Release release = release();
        File updates = new File(context.getCacheDir(), "updates");
        assertTrue(updates.isDirectory() || updates.mkdirs());
        File apk = new File(updates, release.apkAssetName);
        try (FileOutputStream out = new FileOutputStream(apk)) { out.write(new byte[]{1, 2, 3}); }

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            OrbitUpdater.launchPackageInstaller(activity, apk, release);
            fail("the Play edition must never hand an APK to Android's installer");
        } catch (Exception e) {
            assertEquals(OrbitDistribution.PLAY_REFUSAL, e.getMessage());
        }
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void theBackgroundUpdateCheckIsNeverScheduled() throws Exception {
        OrbitUpdateWorker.schedule(context);
        List<WorkInfo> work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork("orbit-stable-update-check").get();
        for (WorkInfo info : work) {
            assertTrue("the GitHub update check must not be pending in the Play edition",
                    info.getState().isFinished());
        }
    }

    // ---- no external Orbit Local install -----------------------------------------------------------

    @Test public void theOrbitLocalDownloadIsRefusedSynchronously() {
        AtomicReference<String> error = new AtomicReference<>();
        OrbitLocalInstaller.downloadAsync(context, new OrbitLocalInstaller.Callback() {
            @Override public void onProgress(int percent) { fail("no download may start"); }
            @Override public void onVerifying() { fail("nothing to verify"); }
            @Override public void onReady(File apk) { fail("nothing may be ready"); }
            @Override public void onError(String message) { error.set(message); }
        });
        assertEquals(OrbitDistribution.PLAY_REFUSAL, error.get());
    }

    @Test public void anOrbitLocalApkIsNeverHandedToAndroid() throws Exception {
        File directory = OrbitLocalInstaller.componentDirectory(context);
        File apk = new File(directory, OrbitLocalInstaller.assetName());
        try (FileOutputStream out = new FileOutputStream(apk)) { out.write(new byte[]{1, 2, 3}); }

        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try {
            OrbitLocalInstaller.launchInstaller(activity, apk);
            fail("the Play edition must never install Orbit Local from an APK");
        } catch (Exception e) {
            assertEquals(OrbitDistribution.PLAY_REFUSAL, e.getMessage());
        }
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test public void orbitLocalOffersNoSetupItCannotPerform() {
        ActivityController<LocalAiActivity> local = Robolectric.buildActivity(LocalAiActivity.class);
        controller = local.setup();
        View root = local.get().getWindow().getDecorView();
        assertNull(findText(root, "Set up Orbit Local"));
        assertNull(findText(root, "Install component"));
        assertNull(findText(root, "Update component"));
        assertNotNull(findContainingText(root, LocalAiActivity.PLAY_UNAVAILABLE));
    }

    // ---- About & updates ------------------------------------------------------------------------------

    @Test public void aboutAndUpdatesSaysGooglePlayDeliversUpdates() {
        ActivityController<UpdateActivity> updates = Robolectric.buildActivity(UpdateActivity.class);
        controller = updates.setup();
        UpdateActivity activity = updates.get();
        View root = activity.getWindow().getDecorView();

        assertNotNull(findText(root, OrbitDistribution.PLAY_UPDATES_MESSAGE));
        assertNull("the GitHub update channel belongs to the GitHub edition",
                findText(root, "Update channel"));
        assertNull(findText(root, "Update notifications"));
        assertNull(findText(root, "Check for updates"));
        assertNull(findContainingText(root, "lpnovi/Orbit-Assistant releases"));

        TextView open = findText(root, "Open in Google Play");
        assertNotNull(open);
        open.performClick();
        Intent started = shadowOf(activity).getNextStartedActivity();
        assertNotNull("the one action opens Orbit's Play listing", started);
        assertEquals(Intent.ACTION_VIEW, started.getAction());
        String target = String.valueOf(started.getData());
        assertTrue(target, target.equals("market://details?id=com.orbit.assistant")
                || target.equals("https://play.google.com/store/apps/details?id=com.orbit.assistant"));
    }

    // ---- Orbit Pro -----------------------------------------------------------------------------------

    @Test public void playNeverOffersProPreviewEvenWhenOneIsStored() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertFalse(OrbitProEntitlement.previewAvailable());
        assertFalse("a stored preview selection unlocks nothing on Play",
                OrbitProEntitlement.hasPro(context));
        assertEquals("Free", OrbitProEntitlement.status(context));
    }

    @Test public void playProMessagingNeverMentionsProPreview() {
        assertEquals("Orbit Pro isn't available for purchase yet.",
                OrbitProEntitlement.lockedGuidance("Enable Pro Preview in Diagnostics."));

        ActivityController<DeckActivity> deck = Robolectric.buildActivity(DeckActivity.class);
        controller = deck.setup();
        DeckLayoutStore.createBlankLayout(context, "Work");
        deck.get().openLayoutManagerForTest();
        View root = deck.get().getWindow().getDecorView();
        assertNotNull(findText(root, "Deck layouts · Orbit Pro"));
        assertNotNull(findContainingText(root, "Orbit Pro isn't available for purchase yet."));
        assertNull("Play and Stable have no Pro Preview to enable",
                findContainingText(root, "Pro Preview"));
        assertNull(findContainingText(root, "Diagnostics"));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private interface Call { void run() throws Exception; }

    private static void expectRefusal(Call call) {
        try {
            call.run();
            fail("the Play edition must refuse this");
        } catch (UnsupportedOperationException expected) {
            assertEquals(OrbitDistribution.PLAY_REFUSAL, expected.getMessage());
        } catch (Exception other) {
            throw new AssertionError("refused the wrong way", other);
        }
    }

    private static final String CERT =
            "7D:AD:61:93:85:DF:F1:1E:C7:31:AA:55:5F:2B:44:8A:94:3C:73:91:81:3D:1A:94:DF:1C:B4:23:2E:CD:41:E3";

    private static JSONObject validRelease() throws Exception {
        return new JSONObject()
                .put("tag", "v0.9.0.0")
                .put("versionName", "0.9.0.0")
                .put("versionCode", 900)
                .put("apkAssetName", "Orbit-Assistant-v0.9.0.0.apk")
                .put("apkSha256", "0000000000000000000000000000000000000000000000000000000000000000")
                .put("certificateSha256", CERT)
                .put("apkSize", 1000)
                .put("releaseNotes", "")
                .put("channel", Prefs.CHANNEL_STABLE);
    }

    private static OrbitUpdater.Release release() {
        return new OrbitUpdater.Release("v0.9.0.0", "0.9.0.0", 900L,
                "Orbit-Assistant-v0.9.0.0.apk",
                "0000000000000000000000000000000000000000000000000000000000000000",
                CERT, 1000L, "");
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findContainingText(View view, String text) {
        if (view instanceof TextView
                && String.valueOf(((TextView) view).getText()).contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findContainingText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}

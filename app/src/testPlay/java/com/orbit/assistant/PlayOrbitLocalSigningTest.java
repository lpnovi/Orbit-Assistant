package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * The Play edition and an Orbit Local component, under the dual-signature model.
 *
 * <p>The Play edition is signed with Google Play's key and the component serves only an Orbit
 * signed with the GitHub release key, so no installed component can ever work with it. These
 * tests prove the Play edition never presents one as usable and only ever offers to remove it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayOrbitLocalSigningTest {
    private Context context;
    private ActivityController<LocalAiActivity> controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        Shadows.shadowOf(context.getPackageManager()).removePackage(OrbitLocalComponent.PACKAGE);
    }

    @Test public void thePlayEditionNeverUsesOrbitLocal() {
        assertFalse(OrbitDistribution.supportsOrbitLocal());
        assertFalse(OrbitLocalComponent.isUsable(context));
    }

    /** The Play reason wins over any per-state wording, including "set it up". */
    @Test public void theRefusalReasonIsThePlayOne() {
        assertEquals(LocalAiActivity.PLAY_UNAVAILABLE, OrbitLocalClient.unavailableReason(context));
        installComponentPackage();
        assertEquals(LocalAiActivity.PLAY_UNAVAILABLE, OrbitLocalClient.unavailableReason(context));
    }

    @Test public void theProviderSaysItIsNotAvailableOnPlay() {
        assertEquals("Not available in the Google Play edition",
                new OrbitLocalProvider().statusDetail(context));
        assertFalse(new OrbitLocalProvider().status(context) == AiProvider.Status.READY);
    }

    /** Even a genuine GitHub component, were it installed, is described as unusable here. */
    @Test public void aGitHubComponentIsDescribedAsUnusableAndRemovable() {
        String genuine = LocalAiActivity.playComponentDetails(OrbitLocalComponent.State.INSTALLED);
        assertTrue(genuine.contains("GitHub edition"));
        assertTrue(genuine.contains("can't use it"));
        assertEquals(genuine,
                LocalAiActivity.playComponentDetails(OrbitLocalComponent.State.UPDATE_REQUIRED));
        assertEquals(LocalAiActivity.PLAY_UNAVAILABLE,
                LocalAiActivity.playComponentDetails(OrbitLocalComponent.State.NOT_INSTALLED));
        for (OrbitLocalComponent.State state : OrbitLocalComponent.State.values()) {
            String text = LocalAiActivity.playComponentDetails(state);
            assertFalse("never " + state + ": " + text, text.contains("install Orbit Local from here"));
            assertFalse(text.contains("Update it"));
        }
    }

    /** An installed package the Play edition cannot use: removal only, no setup or update. */
    @Test public void anInstalledComponentCanOnlyBeRemoved() {
        installComponentPackage();
        controller = Robolectric.buildActivity(LocalAiActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        assertNotNull(findText(root, "Remove untrusted component"));
        assertNull(findText(root, "Set up Orbit Local"));
        assertNull(findText(root, "Install component"));
        assertNull(findText(root, "Update component"));
        assertNull(findContaining(root, "install Orbit Local from here"));
    }

    /**
     * Robolectric cannot fabricate a signing certificate, so this package is unsigned, which is
     * the UNTRUSTED state. A genuine GitHub-signed component would be INSTALLED; the pure checks
     * above cover that wording, and isUsable refuses before the state is even read.
     */
    private void installComponentPackage() {
        PackageInfo info = new PackageInfo();
        info.packageName = OrbitLocalComponent.PACKAGE;
        info.versionName = BuildConfig.VERSION_NAME;
        info.setLongVersionCode(BuildConfig.VERSION_CODE);
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = OrbitLocalComponent.PACKAGE;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
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

    private static TextView findContaining(View view, String text) {
        if (view instanceof TextView && String.valueOf(((TextView) view).getText()).contains(text)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}

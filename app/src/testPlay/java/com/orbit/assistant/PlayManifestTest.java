package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Play variant's merged manifest, as Android reads it.
 *
 * <p>Robolectric loads the manifest the build actually merged for the play build type, so this is
 * the real result of main + the Play overlay, not a reading of either source file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayManifestTest {

    private static List<String> requested() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_PERMISSIONS);
        return Arrays.asList(info.requestedPermissions);
    }

    @Test public void thePlayManifestDoesNotRequestPackageInstalls() throws Exception {
        assertFalse(requested().contains("android.permission.REQUEST_INSTALL_PACKAGES"));
    }

    /** Only that one permission differs from the GitHub edition. Nothing else was lost. */
    @Test public void everyOtherPermissionIsKept() throws Exception {
        Matcher m = Pattern.compile("<uses-permission android:name=\"([^\"]+)\"")
                .matcher(DistributionBoundaryTest.read("app/src/main/AndroidManifest.xml"));
        List<String> expected = new ArrayList<>();
        while (m.find()) expected.add(m.group(1));
        expected.remove("android.permission.REQUEST_INSTALL_PACKAGES");
        assertTrue(expected.size() > 15);
        for (String permission : expected) {
            assertTrue("the Play edition must keep " + permission, requested().contains(permission));
        }
        assertTrue("removing an installed Orbit Local component still works on Play",
                requested().contains("android.permission.REQUEST_DELETE_PACKAGES"));
    }

    @Test public void thePlayEditionTargetsApi36() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals(36, context.getApplicationInfo().targetSdkVersion);
        assertEquals(29, context.getApplicationInfo().minSdkVersion);
    }

    @Test public void thePlayEditionHasTheSameVersionAsTheGitHubEdition() {
        String gradle = DistributionBoundaryTest.read("app/build.gradle");
        Matcher code = Pattern.compile("versionCode (\\d+)").matcher(gradle);
        Matcher name = Pattern.compile("versionName \"([^\"]+)\"").matcher(gradle);
        assertTrue(code.find());
        assertTrue(name.find());
        assertEquals(Integer.parseInt(code.group(1)), BuildConfig.VERSION_CODE);
        assertEquals(name.group(1), BuildConfig.VERSION_NAME);
    }
}

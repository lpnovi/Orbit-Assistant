package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * The Google Play edition of Orbit, where Google Play delivers every app update.
 *
 * <p>Same shape as the GitHub edition's {@code OrbitEdition}, so the rest of Orbit compiles
 * unchanged, and none of its behaviour. This build contains no GitHub release endpoint for Orbit's
 * own APKs and no way to hand an APK to Android's installer: every such method throws, and the
 * Play manifest does not request {@code REQUEST_INSTALL_PACKAGES}, so Android would refuse even if
 * something tried.
 *
 * <p>That covers both Orbit itself and the separate Orbit Local component. Google Play policy does
 * not allow an app it distributes to update itself, or to install executable code, from anywhere
 * other than Google Play.
 */
final class OrbitEdition {

    /** Read by {@link OrbitDistribution}. */
    static final String ID = "play";

    private OrbitEdition() {}

    static String latestReleaseApi() {
        throw refused();
    }

    static String releasesListApi(int perPage) {
        throw refused();
    }

    static String releaseDownloadBase() {
        throw refused();
    }

    /** Never: the Play edition does not request the permission this would report on. */
    static boolean canRequestPackageInstalls(Context context) {
        return false;
    }

    static void openUnknownSourcesSettings(Activity activity) {
        throw refused();
    }

    static Intent apkInstallerIntent(Uri uri) {
        throw refused();
    }

    private static UnsupportedOperationException refused() {
        return new UnsupportedOperationException(OrbitDistribution.PLAY_REFUSAL);
    }
}

package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/**
 * The GitHub edition of Orbit: the only code that can fetch Orbit APKs and install them.
 *
 * <p>This file is compiled into the debug and release builds and nowhere else. The Google Play
 * build compiles {@code src/play/.../OrbitEdition.java} instead, which has the same shape and
 * refuses every one of these operations. That is the whole distribution boundary: a Play bundle
 * does not carry a GitHub update path that has been switched off, it carries none.
 *
 * <p>Everything here is the mechanism {@link OrbitUpdater} and {@link OrbitLocalInstaller} have
 * always used, moved rather than changed: the same public GitHub endpoints, the same ACTION_VIEW
 * hand-off, the same APK MIME type and read grant. The verification that happens before any of it
 * stays with those two classes.
 */
final class OrbitEdition {

    /** Read by {@link OrbitDistribution}. */
    static final String ID = "github";

    private static final String REPOSITORY = "lpnovi/Orbit-Assistant";

    private OrbitEdition() {}

    /** GitHub's newest published non-prerelease, the Stable update channel's source. */
    static String latestReleaseApi() {
        return "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    }

    /** A bounded page of recent releases, the Beta update channel's source. */
    static String releasesListApi(int perPage) {
        return "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=" + perPage;
    }

    /** Where official release assets are downloaded from, tag and asset name appended. */
    static String releaseDownloadBase() {
        return "https://github.com/" + REPOSITORY + "/releases/download/";
    }

    /** Whether Android currently lets Orbit ask it to install an APK. */
    static boolean canRequestPackageInstalls(Context context) {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    /** Android's own "Install unknown apps" page for Orbit. */
    static void openUnknownSourcesSettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()));
        activity.startActivity(intent);
    }

    /**
     * The intent that hands a verified APK to Android's package installer.
     *
     * <p>Android still draws its own confirmation and the user still decides. The caller is
     * responsible for having verified the file and for {@code uri} being a FileProvider URI.
     */
    static Intent apkInstallerIntent(Uri uri) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
}

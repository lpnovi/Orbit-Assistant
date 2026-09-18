package com.orbit.assistant;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Which channel delivered this copy of Orbit, and what that channel allows.
 *
 * <p>Orbit is one application, {@code com.orbit.assistant}, signed with one permanent identity and
 * distributed two ways:
 *
 * <ul>
 *   <li><b>GitHub</b>: signed APKs from GitHub Releases, kept current by Orbit's own verified
 *       updater, with the optional Orbit Local component installed from the same release.
 *   <li><b>Google Play</b>: an App Bundle. Google Play delivers every update, and Orbit never
 *       downloads or installs an APK, neither its own nor Orbit Local's.
 * </ul>
 *
 * <p>The answer comes from {@link OrbitEdition}, which is a different source file in each build
 * (see app/build.gradle), never from a preference, a server, or the installer package name. A
 * setting could be changed and an installer name can be spoofed or missing; which file was
 * compiled cannot. An unrecognised value resolves to {@link Channel#PLAY}, the channel that allows
 * less, so a mistake here fails closed.
 */
public final class OrbitDistribution {

    public enum Channel { GITHUB, PLAY }

    /** Said wherever the Play edition refuses a GitHub operation. */
    static final String PLAY_REFUSAL =
            "Orbit from Google Play is updated by Google Play and never installs APKs itself.";

    /** What About & updates says in the Play edition. */
    static final String PLAY_UPDATES_MESSAGE =
            "Orbit updates are delivered by Google Play. Google Play installs new versions for you, "
                    + "the same way it updates your other apps.";

    private static final String PACKAGE_NAME = "com.orbit.assistant";

    private OrbitDistribution() {}

    /** The channel this build was made for. */
    public static Channel current() {
        return parse(OrbitEdition.ID);
    }

    public static boolean isPlay() {
        return current() == Channel.PLAY;
    }

    /** Whether Orbit may check GitHub for, download, and install its own updates. */
    public static boolean selfUpdates() {
        return selfUpdates(current());
    }

    /** Whether Orbit may download the Orbit Local component APK and hand it to Android. */
    public static boolean installsOrbitLocalComponent() {
        return installsOrbitLocalComponent(current());
    }

    /** A short name for Diagnostics. */
    public static String label() {
        return label(current());
    }

    // ---- the rules, separated from the build they run in --------------------------------------------

    static Channel parse(String id) {
        return "github".equals(id) ? Channel.GITHUB : Channel.PLAY;
    }

    static boolean selfUpdates(Channel channel) {
        return channel == Channel.GITHUB;
    }

    static boolean installsOrbitLocalComponent(Channel channel) {
        return channel == Channel.GITHUB;
    }

    /**
     * Whether the developer Free / Pro Preview override may exist in this channel at all.
     *
     * <p>Never on Google Play, whatever the version name says. Orbit Pro will be bought through
     * Google Play there, and a tester track must not carry a switch that unlocks it for free.
     */
    static boolean allowsProPreview(Channel channel) {
        return channel == Channel.GITHUB;
    }

    static String label(Channel channel) {
        return channel == Channel.GITHUB ? "GitHub" : "Google Play";
    }

    // ---- Google Play ------------------------------------------------------------------------------

    /**
     * Opens Orbit's Google Play listing, where Play offers any pending update.
     *
     * <p>Tries the Play Store app first and falls back to the web listing. Returns false only when
     * neither can be opened.
     */
    static boolean openPlayListing(Context context) {
        Intent store = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + PACKAGE_NAME))
                .setPackage("com.android.vending")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(store);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // No Play Store app. The web listing is the same page.
        }
        Intent web = new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + PACKAGE_NAME))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(web);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}

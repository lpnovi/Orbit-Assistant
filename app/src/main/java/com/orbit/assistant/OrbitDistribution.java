package com.orbit.assistant;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Which channel delivered this copy of Orbit, and what that channel allows.
 *
 * <p>Orbit is one application, {@code com.orbit.assistant}, distributed two ways as two separately
 * signed editions:
 *
 * <ul>
 *   <li><b>GitHub</b>: APKs from GitHub Releases, signed with Orbit's permanent GitHub release key,
 *       kept current by Orbit's own verified updater, with the optional Orbit Local component
 *       installed from the same release.
 *   <li><b>Google Play</b>: an App Bundle. Google Play signs it with its own Google-generated app
 *       signing key and delivers every update. Orbit never downloads or installs an APK, neither
 *       its own nor Orbit Local's.
 * </ul>
 *
 * <p>The two signatures differ on purpose, so neither edition can update the other. Moving between
 * them is export a backup, uninstall, install the other edition, import the backup.
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

    /**
     * Whether this edition can use an Orbit Local component at all.
     *
     * <p>The component is GitHub-only and serves only an Orbit signed with the GitHub release key:
     * its bind permission is signature-level, and it checks Orbit's certificate on every call. The
     * Play edition carries Google Play's signature instead, so a component left behind by a GitHub
     * install can never serve it, and Orbit must not present one as usable.
     */
    public static boolean supportsOrbitLocal() {
        return supportsOrbitLocal(current());
    }

    /**
     * Whether arrive/leave location triggers exist in this build.
     *
     * <p>They need background location, which the Google Play edition does not request. Its
     * manifest removes the permission, so Android could not grant it even if asked; this is the
     * rule every screen and the scheduler read so none of them offers, requests, or arms one.
     * Saved location triggers are never deleted or switched off here: a GitHub build finds them
     * exactly as they were.
     */
    public static boolean supportsLocationTriggers() {
        return supportsLocationTriggers(current());
    }

    /** What the Play edition says wherever a location trigger would otherwise be offered. */
    public static final String LOCATION_TRIGGERS_UNAVAILABLE =
            "Location-triggered Routines aren't currently available in the Google Play edition of "
                    + "Orbit. Time triggers work as usual.";

    /** The short state shown on a saved location trigger in the Play edition. */
    public static final String LOCATION_TRIGGER_UNAVAILABLE_STATE =
            "Not available in the Google Play edition";

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

    static boolean supportsOrbitLocal(Channel channel) {
        return channel == Channel.GITHUB;
    }

    static boolean supportsLocationTriggers(Channel channel) {
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

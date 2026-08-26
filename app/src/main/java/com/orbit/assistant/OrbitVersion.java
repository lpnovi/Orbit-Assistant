package com.orbit.assistant;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place Orbit understands its own version strings.
 *
 * <p>Orbit ships two kinds of build from one repository, one package, and one signing certificate:
 * a Stable release such as {@code 0.7.7.5}, and a Beta prerelease such as {@code 0.7.7.5-beta.1}.
 * Every screen that has to read, compare, or display a version goes through here, so the format is
 * defined once instead of being re-derived by a regex in each Activity.
 *
 * <p>Two rules matter more than the parsing itself. A malformed suffix is <em>not</em> a Beta —
 * {@code 0.7.7.5-beta}, {@code -beta.0} and {@code -beta.zero} are simply invalid, and treating
 * them as Beta would let an unofficial tag masquerade as an Orbit prerelease. And the ordering
 * here is for <em>display and candidate ranking only</em>: whether one build may replace another is
 * always decided by Android's {@code versionCode}, never by these strings.
 */
public final class OrbitVersion {
    private OrbitVersion() {}

    /** {@code 0.7.7.5} — two or more dot-separated numbers, nothing else. */
    private static final Pattern STABLE = Pattern.compile("^[0-9]+(?:\\.[0-9]+)+$");
    /**
     * {@code 0.7.7.5-beta.1} — a stable version, then a beta counter starting at one.
     *
     * <p>{@code [1-9][0-9]*} deliberately rejects {@code beta.0} and a zero-padded {@code beta.01}:
     * there is no beta zero, and one build must not be describable by two different tags.
     */
    private static final Pattern BETA =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)+)-beta\\.([1-9][0-9]*)$");

    public static boolean isStable(String versionName) {
        return versionName != null && STABLE.matcher(versionName.trim()).matches();
    }

    public static boolean isBeta(String versionName) {
        return versionName != null && BETA.matcher(versionName.trim()).matches();
    }

    /** Whether this is a version Orbit publishes at all, Stable or Beta. */
    public static boolean isValid(String versionName) {
        return isStable(versionName) || isBeta(versionName);
    }

    /** {@code 0.7.7.5-beta.2} → {@code 0.7.7.5}. Empty for anything unrecognised. */
    public static String baseVersion(String versionName) {
        if (versionName == null) return "";
        String value = versionName.trim();
        if (isStable(value)) return value;
        Matcher beta = BETA.matcher(value);
        return beta.matches() ? beta.group(1) : "";
    }

    /** The beta counter, or 0 when this is a Stable or invalid version. */
    public static int betaNumber(String versionName) {
        if (versionName == null) return 0;
        Matcher beta = BETA.matcher(versionName.trim());
        if (!beta.matches()) return 0;
        try {
            return Integer.parseInt(beta.group(2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * How a build is written for a person: {@code 0.7.7.5} or {@code 0.7.7.5 Beta 1}.
     *
     * <p>An unrecognised string is returned untouched rather than dressed up — Orbit would rather
     * show something odd than present an unknown build as a legitimate release.
     */
    public static String displayName(String versionName) {
        String value = versionName == null ? "" : versionName.trim();
        if (!isBeta(value)) return value;
        return baseVersion(value) + " Beta " + betaNumber(value);
    }

    /** The GitHub Release title, matching the release workflow exactly. */
    public static String releaseTitle(String versionName) {
        return "Orbit Assistant v" + displayName(versionName);
    }

    // ---- tags ---------------------------------------------------------------------------------

    public static boolean isStableTag(String tag) {
        return tag != null && tag.startsWith("v") && isStable(tag.substring(1));
    }

    public static boolean isBetaTag(String tag) {
        return tag != null && tag.startsWith("v") && isBeta(tag.substring(1));
    }

    public static boolean isValidTag(String tag) {
        return isStableTag(tag) || isBetaTag(tag);
    }

    /** The version a tag names, or empty when the tag is not one Orbit publishes. */
    public static String versionFromTag(String tag) {
        return isValidTag(tag) ? tag.substring(1) : "";
    }

    public static String tagFor(String versionName) {
        return "v" + (versionName == null ? "" : versionName.trim());
    }

    // ---- ordering -----------------------------------------------------------------------------

    /**
     * Orders two Orbit versions for display and candidate ranking.
     *
     * <p>A Stable release outranks every Beta of the same base version, because {@code 0.7.7.5} is
     * what {@code 0.7.7.5-beta.3} was working towards. Invalid strings sort below everything.
     *
     * <p>This never authorises an install. {@code versionCode} decides that, and only that.
     */
    public static int compareVersions(String left, String right) {
        boolean leftValid = isValid(left);
        boolean rightValid = isValid(right);
        if (!leftValid && !rightValid) return 0;
        if (!leftValid) return -1;
        if (!rightValid) return 1;

        int[] leftParts = numericParts(baseVersion(left));
        int[] rightParts = numericParts(baseVersion(right));
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            int a = i < leftParts.length ? leftParts[i] : 0;
            int b = i < rightParts.length ? rightParts[i] : 0;
            if (a != b) return Integer.compare(a, b);
        }
        // Same base version: a Beta is a step towards the Stable release, so it ranks below it.
        int leftBeta = betaNumber(left);
        int rightBeta = betaNumber(right);
        if (leftBeta == rightBeta) return 0;
        if (leftBeta == 0) return 1;
        if (rightBeta == 0) return -1;
        return Integer.compare(leftBeta, rightBeta);
    }

    private static int[] numericParts(String base) {
        if (base == null || base.isEmpty()) return new int[0];
        String[] pieces = base.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                out[i] = Integer.parseInt(pieces[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    // ---- this installation --------------------------------------------------------------------

    /**
     * Whether the APK currently running is itself a Beta build.
     *
     * <p>Deliberately independent of the selected update channel. Someone can install
     * {@code 0.7.7.5-beta.2} and then switch their channel back to Stable; the build they are
     * running is still a Beta, and About &amp; updates has to be able to say so.
     */
    public static boolean installedIsBeta() {
        return isBeta(BuildConfig.VERSION_NAME);
    }

    /** The running build, written the way a person reads it. */
    public static String installedDisplayName() {
        return displayName(BuildConfig.VERSION_NAME);
    }

    /** Lower-cased channel word for logs and diagnostics. */
    static String channelWord(boolean beta) {
        return (beta ? "beta" : "stable").toLowerCase(Locale.US);
    }
}

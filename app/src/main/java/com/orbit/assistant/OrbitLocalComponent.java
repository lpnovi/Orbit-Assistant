package com.orbit.assistant;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * What Orbit knows about the optional Orbit Local component, before it will talk to it.
 *
 * <p>The component is a separate APK, so Orbit treats it exactly like any other package it did
 * not build: nothing is assumed from the fact that it is installed. Before a single request
 * crosses the service boundary, Orbit checks the package name, that it carries Orbit's permanent
 * release certificate, and that its version is the one this Orbit release expects.
 *
 * <p>That certificate check is the load-bearing one. Anyone can install a package called
 * {@code com.orbit.assistant.local}; only Orbit's own release pipeline can sign one.
 */
public final class OrbitLocalComponent {
    private OrbitLocalComponent() {}

    /** The optional component's package. Deliberately a suffix of Orbit's own, never a clone of it. */
    public static final String PACKAGE = "com.orbit.assistant.local";
    /** The bind action the component's service publishes. */
    public static final String BIND_ACTION = "com.orbit.assistant.local.BIND";
    /**
     * The IPC contract version Orbit speaks.
     *
     * <p>Deliberately independent of the app's semantic version: Orbit and the component can be
     * released at different versions and still understand each other. A component reporting a
     * different protocol is treated as needing an update, never used through a guessed interface.
     *
     * <p>2 since v0.7.7.6: the model status vocabulary grew, and PAUSED narrowed to mean only a
     * pause the user asked for. See {@link OrbitLocalStatus}.
     *
     * <p>3 since v0.7.8.0 Beta 1: the component can hold a second model. The interface gained the
     * action model's download lifecycle and its own generation call, and the status Bundle gained
     * that model's keys.
     */
    public static final int PROTOCOL_VERSION = 3;
    /** Orbit's permanent release certificate, the same pin the updater carries. */
    static final String CERTIFICATE_SHA256 =
            "7DAD619385DFF11EC731AA555F2B448A943C7391813D1A94DF1CB4232ECD41E3";

    /** How usable the component is right now, from Orbit's point of view. */
    public enum State {
        /** Not installed at all. The normal state for anyone who never wanted local AI. */
        NOT_INSTALLED,
        /** Installed, but not signed by Orbit. Never used, and never silently trusted. */
        UNTRUSTED,
        /** Trusted, but built for a different Orbit release than this one. */
        UPDATE_REQUIRED,
        /** Installed, signed by Orbit, and the version this Orbit release expects. */
        INSTALLED
    }

    public static State state(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null) return State.NOT_INSTALLED;
        return evaluate(true, hasOrbitCertificate(info), info.versionName, versionCode(info));
    }

    /**
     * The trust decision itself, on plain values.
     *
     * <p>Separated from {@link PackageManager} so the rules can be exercised exactly rather than
     * inferred from a device. The order matters and is deliberate: an untrusted signature is
     * refused before the version is even looked at, so a hostile package can never present itself
     * as merely out of date and coax the user into "updating" it.
     */
    static State evaluate(boolean installed, boolean trustedCertificate,
                          String versionName, long versionCode) {
        if (!installed) return State.NOT_INSTALLED;
        if (!trustedCertificate) return State.UNTRUSTED;
        // For this release the component is versioned in lockstep with Orbit, so an exact match is
        // the honest requirement. Protocol-compatible flexibility is a deliberate later step, not
        // something to guess at now.
        if (!BuildConfig.VERSION_NAME.equals(versionName) || versionCode != BuildConfig.VERSION_CODE) {
            return State.UPDATE_REQUIRED;
        }
        return State.INSTALLED;
    }

    public static boolean isUsable(Context context) {
        return state(context) == State.INSTALLED;
    }

    /** True when a package with this name exists at all, whatever Orbit thinks of it. */
    public static boolean isInstalled(Context context) {
        return packageInfo(context) != null;
    }

    /** The installed component's version name, or "" when it is not installed. */
    public static String installedVersionName(Context context) {
        PackageInfo info = packageInfo(context);
        return info == null || info.versionName == null ? "" : info.versionName;
    }

    public static long installedVersionCode(Context context) {
        PackageInfo info = packageInfo(context);
        return info == null ? 0L : versionCode(info);
    }

    /**
     * The component APK's size on disk, or 0.
     *
     * <p>Read from the installed package rather than hardcoded, so the storage summary states a
     * real number instead of a guess that drifts from the shipped artifact.
     */
    public static long installedApkBytes(Context context) {
        PackageInfo info = packageInfo(context);
        if (info == null || info.applicationInfo == null) return 0L;
        try {
            String path = info.applicationInfo.sourceDir;
            if (path == null || path.isEmpty()) return 0L;
            return Math.max(0L, new java.io.File(path).length());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static PackageInfo packageInfo(Context context) {
        if (context == null) return null;
        try {
            return context.getPackageManager()
                    .getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long versionCode(PackageInfo info) {
        try {
            return info.getLongVersionCode();
        } catch (Throwable t) {
            return info.versionCode;
        }
    }

    /** Exactly one signer, and that signer is Orbit's permanent release certificate. */
    static boolean hasOrbitCertificate(PackageInfo info) {
        try {
            SigningInfo signing = info.signingInfo;
            if (signing == null || signing.hasMultipleSigners()) return false;
            Signature[] signers = signing.getApkContentsSigners();
            if (signers == null || signers.length != 1) return false;
            return CERTIFICATE_SHA256.equalsIgnoreCase(sha256(signers[0].toByteArray()));
        } catch (Throwable t) {
            return false;
        }
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.US, "%02X", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** A short line for the component card, matching the state Orbit actually observed. */
    public static String stateLabel(State state) {
        switch (state) {
            case INSTALLED: return "Installed";
            case UPDATE_REQUIRED: return "Update required";
            case UNTRUSTED: return "Not verified";
            default: return "Not installed";
        }
    }
}

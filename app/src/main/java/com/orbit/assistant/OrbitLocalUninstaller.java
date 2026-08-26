package com.orbit.assistant;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

/**
 * Asking Android to remove the Orbit Local component, and finding out what actually happened.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>In v0.7.7.5 "Remove Orbit Local" did nothing at all on a Galaxy S25 Ultra. Orbit's own
 * confirmation appeared, the user confirmed, Orbit's dialog closed — and Android's uninstall
 * confirmation never came. The package stayed installed and Orbit said nothing, because the old
 * implementation fired {@code ACTION_DELETE} and swallowed every {@link Throwable} it produced.
 *
 * <p>Nothing was thrown. Orbit never held {@code REQUEST_DELETE_PACKAGES}, which Android has
 * required of any app targeting API 28 or later before it may ask for an uninstall; Orbit targets
 * 35. The platform's uninstaller logs the refusal and finishes without a dialog, so
 * {@code startActivity} returns perfectly normally and the caller learns nothing. A silent
 * platform refusal met a silent catch, and the result was a button that did nothing.
 *
 * <p>Two things fix it. The permission is now declared in Orbit's manifest, and the request goes
 * through {@link PackageInstaller#uninstall}, the current documented API, whose status receiver
 * distinguishes "the user cancelled" from "it worked" from "this could not even be asked".
 * {@code ACTION_UNINSTALL_PACKAGE} — deprecated in favour of exactly that call — remains only as a
 * fallback for a device whose package installer refuses the modern path.
 *
 * <h2>What is trusted</h2>
 *
 * <p>Statuses are reported, not believed. The package manager is the only authority on whether the
 * component is gone, and {@link LocalAiActivity} re-reads it on resume before anything is cleaned
 * up. Nothing here deletes a single byte of anyone's data.
 */
public final class OrbitLocalUninstaller {
    private static final String TAG = "OrbitLocalUninstall";

    /** The broadcast Android sends back with the uninstall result. Orbit's own, never exported. */
    public static final String ACTION_UNINSTALL_STATUS =
            "com.orbit.assistant.action.ORBIT_LOCAL_UNINSTALL_STATUS";

    private OrbitLocalUninstaller() {}

    /**
     * How far the request got.
     *
     * <p>Recorded for Diagnostics at every step, because the failure this replaces was invisible
     * precisely because no step recorded anything.
     */
    public enum Stage {
        /** There was no component to remove. */
        NOT_INSTALLED,
        /** The request was handed to Android's package installer. */
        REQUESTED,
        /** Android asked for the user's confirmation and Orbit opened it. */
        CONFIRM_SHOWN,
        /** Android reported it needed confirmation but supplied no intent to show. */
        CONFIRM_MISSING,
        /** The modern API refused the request; the documented older Intent was tried instead. */
        FELL_BACK_TO_INTENT,
        /** Android reported the package was removed. */
        SUCCEEDED,
        /** Android reported the user backed out. */
        CANCELLED,
        /** Android reported a failure of its own. */
        REFUSED,
        /** Orbit could not get the request as far as Android at all. */
        NOT_LAUNCHED
    }

    /** What {@link #request} managed to do, in terms the screen can act on immediately. */
    public enum Launch {
        /** Android has the request. What happens next is the user's, and is read back on resume. */
        LAUNCHED,
        /** Nothing to remove; the package is already absent. */
        NOT_INSTALLED,
        /** Orbit could not ask. The user is told, and nothing is cleaned up. */
        FAILED
    }

    /**
     * Asks Android to uninstall the component. Android owns the confirmation and the outcome.
     *
     * <p>Never targets anything but {@link OrbitLocalComponent#PACKAGE}: the package name is a
     * constant here rather than a parameter, so there is no call shape that could aim this at
     * another app.
     */
    public static Launch request(Activity activity) {
        if (activity == null) return Launch.FAILED;
        Context app = activity.getApplicationContext();
        if (!OrbitLocalComponent.isInstalled(app)) {
            record(app, Stage.NOT_INSTALLED, null);
            return Launch.NOT_INSTALLED;
        }
        try {
            PackageInstaller installer = app.getPackageManager().getPackageInstaller();
            installer.uninstall(OrbitLocalComponent.PACKAGE, statusSender(app));
            record(app, Stage.REQUESTED, null);
            return Launch.LAUNCHED;
        } catch (Throwable modern) {
            // A device whose package installer refuses the current API. The documented predecessor
            // is tried once, and its own failure is reported rather than swallowed.
            record(app, Stage.FELL_BACK_TO_INTENT, modern);
            return requestThroughIntent(activity);
        }
    }

    /**
     * The status receiver Android reports back to.
     *
     * <p>Mutable on purpose. The system fills this PendingIntent in with the status extras, and an
     * immutable one would arrive stripped of the very fields that say what happened.
     */
    private static android.content.IntentSender statusSender(Context app) {
        Intent intent = new Intent(ACTION_UNINSTALL_STATUS)
                .setPackage(app.getPackageName())
                .setClass(app, OrbitLocalUninstallReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        return PendingIntent.getBroadcast(app, 0, intent, flags).getIntentSender();
    }

    /**
     * The documented predecessor, used only when {@link PackageInstaller#uninstall} refused.
     *
     * <p>Deprecated by Android in favour of the call above, and kept for exactly one reason: it is
     * still the contract every package installer implements, and a fallback that reports its own
     * failure is better than a removal path with no second chance.
     */
    @SuppressWarnings("deprecation")
    private static Launch requestThroughIntent(Activity activity) {
        Context app = activity.getApplicationContext();
        try {
            Intent uninstall = new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                    android.net.Uri.parse("package:" + OrbitLocalComponent.PACKAGE));
            if (uninstall.resolveActivity(activity.getPackageManager()) == null) {
                record(app, Stage.NOT_LAUNCHED, null);
                return Launch.FAILED;
            }
            activity.startActivity(uninstall);
            record(app, Stage.REQUESTED, null);
            return Launch.LAUNCHED;
        } catch (Throwable t) {
            record(app, Stage.NOT_LAUNCHED, t);
            return Launch.FAILED;
        }
    }

    /**
     * Whether Orbit holds the permission Android requires before it may ask for an uninstall.
     *
     * <p>The exact condition that was missing. Checked so its absence can be stated in Diagnostics
     * instead of being rediscovered from a device recording.
     */
    public static boolean canRequestUninstall(Context context) {
        if (context == null) return false;
        try {
            return context.getPackageManager().checkPermission(
                    android.Manifest.permission.REQUEST_DELETE_PACKAGES,
                    context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** True once the package manager confirms the component is genuinely gone. */
    public static boolean confirmedRemoved(Context context) {
        return !OrbitLocalComponent.isInstalled(context);
    }

    // ---- recording ---------------------------------------------------------------------------------

    /**
     * Notes where the request got to.
     *
     * <p>Stage names and exception class names only. No paths, no model bytes, nothing about any
     * conversation.
     */
    static void record(Context context, Stage stage, Throwable failure) {
        String detail = failure == null ? "" : failure.getClass().getSimpleName();
        Log.i(TAG, "component_uninstall stage=" + stage.name()
                + (detail.isEmpty() ? "" : " cause=" + detail));
        try {
            DiagnosticStore.recordComponentUninstall(
                    context.getApplicationContext(), stage.name(), detail);
        } catch (Throwable ignored) {
            // Diagnostics must never be the reason a removal fails.
        }
    }

    /** The short sentence the Orbit Local screen shows for an outcome the user should see. */
    static String message(Stage stage) {
        switch (stage) {
            case SUCCEEDED: return "Orbit Local was removed.";
            case CANCELLED: return "Orbit Local was not removed. Nothing was deleted.";
            case REFUSED: return "Android would not remove the Orbit Local component. Nothing was deleted.";
            case CONFIRM_MISSING:
            case NOT_LAUNCHED: return "Orbit could not open Android's uninstaller. Nothing was deleted. "
                    + "You can remove Orbit Local from Android Settings > Apps instead.";
            case NOT_INSTALLED: return "The Orbit Local component is not installed.";
            default: return "";
        }
    }
}

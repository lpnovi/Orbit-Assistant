package com.orbit.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/**
 * What Android reports back after an Orbit Local uninstall request.
 *
 * <p>The half of the flow v0.7.7.5 had no equivalent of. The old path fired an Intent and assumed;
 * this one is told, and records the difference between a user who backed out, a removal that
 * succeeded, and a request the platform refused.
 *
 * <p>Two responsibilities, and deliberately no more. It opens the confirmation Android asks it to
 * open, and it writes down the result. It deletes nothing: {@link LocalAiActivity} does the
 * Orbit-side cleanup on resume, and only after re-reading the real package state, so a status
 * broadcast that never arrives cannot leave anything half-removed.
 */
public final class OrbitLocalUninstallReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!OrbitLocalUninstaller.ACTION_UNINSTALL_STATUS.equals(intent.getAction())) return;

        // The status belongs to the package Orbit asked about, or it is not ours to act on.
        String target = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (target != null && !OrbitLocalComponent.PACKAGE.equals(target)) return;

        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                showConfirmation(context, intent);
                return;
            case PackageInstaller.STATUS_SUCCESS:
                OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.SUCCEEDED, null);
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.CANCELLED, null);
                return;
            default:
                OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.REFUSED, null);
        }
    }

    /**
     * Opens the system's own uninstall confirmation.
     *
     * <p>Android hands back the Intent to show rather than showing it itself, so this is the step
     * that puts Samsung's uninstall dialog on screen — the dialog that never appeared in
     * v0.7.7.5. A missing Intent is recorded rather than ignored, because a confirmation that
     * cannot be shown is a removal that cannot happen.
     */
    private void showConfirmation(Context context, Intent status) {
        Intent confirm = status.getParcelableExtra(Intent.EXTRA_INTENT);
        if (confirm == null) {
            OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.CONFIRM_MISSING, null);
            return;
        }
        try {
            // A broadcast has no task of its own to show an Activity in.
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(confirm);
            OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.CONFIRM_SHOWN, null);
        } catch (Throwable t) {
            OrbitLocalUninstaller.record(context, OrbitLocalUninstaller.Stage.NOT_LAUNCHED, t);
        }
    }
}

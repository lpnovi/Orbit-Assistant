package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Orbit-styled stable-release update UI. Installation always remains user-confirmed Android UI. */
public final class UpdateActivity extends Activity {
    private TextView status;
    private Button action;
    private LinearLayout downloadProgress;
    private ProgressBar downloadProgressBar;
    private TextView downloadPercent;
    private OrbitUpdater.Release availableRelease;
    private OrbitUpdater.Release readyRelease;
    private File readyApk;
    private OrbitUpdater.DownloadHandle download;
    private boolean waitingForInstallPermission;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        OrbitUpdateWorker.schedule(this);
        OrbitUpdater.cleanupAbandonedDownloads(this, null);

        availableRelease = OrbitUpdater.loadCachedAvailable(this);
        if (availableRelease != null) showAvailableState(availableRelease, false);
        else showIdleState();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (waitingForInstallPermission && readyApk != null && readyRelease != null &&
                OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = false;
            verifyAndInstall();
        }
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (download != null) download.cancel();
        super.onDestroy();
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 30), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(UiKit.TEXT);
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.TEXT, 18, this));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "About & updates", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Official Orbit releases", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView section = UiKit.text(this, "ORBIT ASSISTANT", 12, UiKit.MUTED, true);
        section.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 28), 0, UiKit.dp(this, 9));
        page.addView(section, sectionLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 18),
                UiKit.dp(this, 18), UiKit.dp(this, 18));
        card.setBackground(UiKit.outlined(
                UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 38), 24, this));
        card.setElevation(UiKit.dp(this, 2));
        card.addView(UiKit.text(this, "Orbit Assistant", 18, UiKit.TEXT, true));

        TextView current = UiKit.text(this,
                "Current version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
                13, UiKit.MUTED, false);
        current.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 11));
        card.addView(current);

        status = UiKit.text(this, "Ready to check for a stable Orbit release.", 14, UiKit.TEXT, false);
        status.setLineSpacing(0, 1.12f);
        status.setPadding(0, 0, 0, UiKit.dp(this, 14));
        card.addView(status);

        downloadProgress = new LinearLayout(this);
        downloadProgress.setOrientation(LinearLayout.VERTICAL);
        downloadProgress.setVisibility(View.GONE);
        downloadProgressBar = UiKit.horizontalProgress(this);
        downloadProgress.addView(downloadProgressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8)));
        downloadPercent = UiKit.text(this, "0%", 11, UiKit.MUTED, false);
        downloadPercent.setGravity(Gravity.END);
        downloadPercent.setPadding(0, UiKit.dp(this, 5), UiKit.dp(this, 1), 0);
        downloadProgress.addView(downloadPercent);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressLp.setMargins(0, 0, 0, UiKit.dp(this, 14));
        card.addView(downloadProgress, progressLp);

        action = primaryButton("Check for updates");
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        page.addView(card);

        TextView privacy = UiKit.text(this,
                "Orbit checks only the public lpnovi/Orbit-Assistant stable releases. It sends no account credentials, never downloads without your approval, verifies the APK checksum, package, version and permanent signing certificate, then uses Android's normal installer.",
                12, UiKit.MUTED, false);
        privacy.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 15), UiKit.dp(this, 3), 0);
        page.addView(privacy, privacyLp);
        UiKit.applyTypography(page);
        return scroll;
    }

    private void showIdleState() {
        hideDownloadProgress();
        status.setText("Ready to check for a stable Orbit release.");
        status.setTextColor(UiKit.TEXT);
        setAction("Check for updates", this::checkForUpdates, true);
    }

    private void checkForUpdates() {
        hideDownloadProgress();
        status.setText("Checking…");
        status.setTextColor(UiKit.TEXT);
        setAction("Checking…", null, false);
        OrbitUpdater.checkAsync(this, new OrbitUpdater.CheckCallback() {
            @Override public void onResult(OrbitUpdater.CheckResult result) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    if (result.updateAvailable && result.release != null) {
                        availableRelease = result.release;
                        showAvailableState(result.release, true);
                    } else {
                        availableRelease = null;
                        status.setText("You're up to date.");
                        status.setTextColor(UiKit.SUCCESS);
                        setAction("Check again", UpdateActivity.this::checkForUpdates, true);
                    }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    status.setText("Network/update service error\n" + message);
                    status.setTextColor(Color.rgb(239, 105, 105));
                    setAction("Try again", UpdateActivity.this::checkForUpdates, true);
                });
            }
        });
    }

    private void showAvailableState(OrbitUpdater.Release release, boolean showDialog) {
        hideDownloadProgress();
        status.setText("Update available: Orbit Assistant v" + release.versionName);
        status.setTextColor(UiKit.accent(this));
        setAction("View update", () -> showUpdateDialog(release), true);
        if (showDialog) showUpdateDialog(release);
    }

    private void showUpdateDialog(OrbitUpdater.Release release) {
        String notes = release.releaseNotes.isEmpty()
                ? "See the official GitHub Release for details."
                : release.releaseNotes;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 20),
                UiKit.dp(this, 24), UiKit.dp(this, 6));
        TextView title = UiKit.text(this,
                "Orbit Assistant v" + release.versionName, 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        content.addView(title);
        TextView body = UiKit.text(this, notes, 14, UiKit.TEXT, false);
        body.setLineSpacing(0, 1.12f);
        body.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(body);
        TextView size = UiKit.text(this,
                "Download size: " + formatSize(release.apkSize), 12, UiKit.MUTED, false);
        size.setPadding(0, UiKit.dp(this, 13), 0, 0);
        content.addView(size);
        UiKit.applyTypography(content);
        ScrollView dialogScroll = new ScrollView(this);
        dialogScroll.setFillViewport(true);
        dialogScroll.setForceDarkAllowed(false);
        dialogScroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogScroll)
                .setNegativeButton("Later", null)
                .setPositiveButton("Download & install", (d, which) -> startDownload(release))
                .create();
        styleOrbitDialog(dialog);
        dialog.show();
    }

    private void startDownload(OrbitUpdater.Release release) {
        availableRelease = release;
        status.setText("Downloading update");
        status.setTextColor(UiKit.TEXT);
        showDownloadProgress(-1);
        setAction("Cancel download", () -> {
            if (download != null) download.cancel();
        }, true);
        download = OrbitUpdater.downloadAsync(this, release, new OrbitUpdater.DownloadCallback() {
            @Override public void onProgress(int percent) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    showDownloadProgress(percent);
                });
            }

            @Override public void onVerifying() {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    hideDownloadProgress();
                    status.setText("Verifying…");
                    setAction("Verifying…", null, false);
                });
            }

            @Override public void onReady(File apk) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    hideDownloadProgress();
                    download = null;
                    readyApk = apk;
                    readyRelease = release;
                    status.setText("Ready to install");
                    status.setTextColor(UiKit.SUCCESS);
                    setAction("Install update", UpdateActivity.this::attemptInstall, true);
                    attemptInstall();
                });
            }

            @Override public void onError(String message, boolean verificationFailure) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    hideDownloadProgress();
                    download = null;
                    status.setText((verificationFailure ? "Verification failed\n" :
                            "Network/update service error\n") + message);
                    status.setTextColor(Color.rgb(239, 105, 105));
                    setAction("Try again", () -> startDownload(release), true);
                });
            }

            @Override public void onCancelled() {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    hideDownloadProgress();
                    download = null;
                    status.setText("Download cancelled.");
                    status.setTextColor(UiKit.TEXT);
                    setAction("Download & install", () -> startDownload(release), true);
                });
            }
        });
    }

    private void attemptInstall() {
        hideDownloadProgress();
        if (readyApk == null || readyRelease == null) return;
        if (!OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = true;
            status.setText("Android needs permission to install updates from Orbit. Allow this source, then return to Orbit.");
            status.setTextColor(UiKit.TEXT);
            setAction("Allow installation", () -> OrbitUpdater.openUnknownSourcesSettings(this), true);
            OrbitUpdater.openUnknownSourcesSettings(this);
            return;
        }
        verifyAndInstall();
    }

    private void verifyAndInstall() {
        if (readyApk == null || readyRelease == null) return;
        hideDownloadProgress();
        status.setText("Verifying…");
        setAction("Verifying…", null, false);
        File apk = readyApk;
        OrbitUpdater.Release release = readyRelease;
        OrbitUpdater.verifyAsync(this, apk, release, new OrbitUpdater.VerifyCallback() {
            @Override public void onVerified() {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    try {
                        status.setText("Ready to install");
                        status.setTextColor(UiKit.SUCCESS);
                        setAction("Install update", UpdateActivity.this::attemptInstall, true);
                        OrbitUpdater.launchPackageInstaller(UpdateActivity.this, apk);
                    } catch (Exception e) {
                        status.setText("Android could not open the package installer.");
                        status.setTextColor(Color.rgb(239, 105, 105));
                    }
                });
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (!alive()) return;
                    readyApk = null;
                    readyRelease = null;
                    status.setText("Verification failed\n" + message);
                    status.setTextColor(Color.rgb(239, 105, 105));
                    setAction("Check again", UpdateActivity.this::checkForUpdates, true);
                });
            }
        });
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(
                UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private void setAction(String text, Runnable runnable, boolean enabled) {
        action.setText(text);
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : 0.62f);
        action.setOnClickListener(runnable == null ? null : v -> runnable.run());
    }

    private void styleOrbitDialog(AlertDialog dialog) {
        UiKit.prepareOrbitDialog(dialog, UiKit.rounded(UiKit.SURFACE, 22, this));
        dialog.setOnShowListener(ignore -> {
            UiKit.applyDialogTypography(dialog);
            UiKit.applyOrbitDialogColors(dialog, this);
        });
    }

    private void showDownloadProgress(int percent) {
        if (downloadProgress == null || downloadProgressBar == null || downloadPercent == null) return;
        downloadProgress.setVisibility(View.VISIBLE);
        if (percent >= 0) {
            int safePercent = Math.max(0, Math.min(100, percent));
            downloadProgressBar.setProgress(safePercent, true);
            downloadPercent.setText(safePercent + "%");
        } else {
            downloadProgressBar.setProgress(0, false);
            downloadPercent.setText("Preparing download…");
        }
    }

    private void hideDownloadProgress() {
        if (downloadProgress != null) downloadProgress.setVisibility(View.GONE);
    }

    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0L) return "Unknown";
        return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
    }
}

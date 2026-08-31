package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
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
    private TextView channelValue;
    private Button action;
    private LinearLayout downloadProgress;
    private ProgressBar downloadProgressBar;
    private TextView downloadPercent;
    private OrbitUpdater.Release availableRelease;
    private OrbitUpdater.Release readyRelease;
    private File readyApk;
    private OrbitUpdater.DownloadHandle download;
    private boolean waitingForInstallPermission;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
        OrbitUpdateWorker.schedule(this);
        OrbitUpdater.reconcilePendingInstall(this);

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
        back.setOnClickListener(v -> navigation.performBack());
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

        // The name line carries a restrained Beta pill when the running APK is itself a Beta
        // build. That is a fact about the installed binary, not about the update channel, and the
        // two are deliberately shown separately.
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(UiKit.text(this, "Orbit Assistant", 18, UiKit.TEXT, true));
        if (OrbitVersion.installedIsBeta()) {
            TextView pill = betaPill("BETA BUILD");
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.setMargins(UiKit.dp(this, 9), UiKit.dp(this, 2), 0, 0);
            nameRow.addView(pill, pillLp);
        }
        card.addView(nameRow);

        TextView current = UiKit.text(this,
                "Current version: " + OrbitVersion.installedDisplayName()
                        + " (" + BuildConfig.VERSION_CODE + ")",
                13, UiKit.MUTED, false);
        current.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 11));
        card.addView(current);

        status = UiKit.text(this, idleMessage(), 14, UiKit.TEXT, false);
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

        Button whatsNew = secondaryButton("What's New");
        whatsNew.setOnClickListener(v -> startActivity(new Intent(this, WhatsNewActivity.class)));
        LinearLayout.LayoutParams whatsNewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        whatsNewLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        card.addView(whatsNew, whatsNewLp);

        card.addView(channelRow());

        OrbitSwitch updateNotifications = new OrbitSwitch(this);
        updateNotifications.setChecked(Prefs.updateNotifications(this), false);
        updateNotifications.setOnCheckedChangeListener((button, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.UPDATE_NOTIFICATIONS, checked).apply();
            if (!checked) OrbitUpdateNotifier.cancel(this);
        });
        card.addView(UiKit.switchRow(this, "Update notifications",
                "Notify me when a new Orbit version is available", updateNotifications));
        page.addView(card);

        TextView roadmapSection = UiKit.text(this, "ROADMAP", 12, UiKit.MUTED, true);
        roadmapSection.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams roadmapSectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        roadmapSectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 22), 0, UiKit.dp(this, 9));
        page.addView(roadmapSection, roadmapSectionLp);

        LinearLayout roadmap = new LinearLayout(this);
        roadmap.setOrientation(LinearLayout.HORIZONTAL);
        roadmap.setGravity(Gravity.CENTER_VERTICAL);
        roadmap.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        roadmap.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 22, this));
        roadmap.setElevation(UiKit.dp(this, 2));
        roadmap.setClickable(true);
        roadmap.setFocusable(true);
        LinearLayout roadmapCopy = new LinearLayout(this);
        roadmapCopy.setOrientation(LinearLayout.VERTICAL);
        roadmapCopy.addView(UiKit.text(this, "Roadmap", 17, UiKit.TEXT, true));
        TextView roadmapDescription = UiKit.text(this,
                "See what's next for Orbit", 12, UiKit.MUTED, false);
        roadmapDescription.setPadding(0, UiKit.dp(this, 3), 0, 0);
        roadmapCopy.addView(roadmapDescription);
        roadmap.addView(roadmapCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        roadmap.addView(UiKit.text(this, "›", 28, UiKit.accent(this), false));
        roadmap.setOnClickListener(v -> startActivity(new Intent(this, RoadmapActivity.class)));
        UiKit.pressScale(roadmap);
        page.addView(roadmap);

        TextView privacy = UiKit.text(this,
                "Orbit checks only the public lpnovi/Orbit-Assistant releases — stable releases, plus official Beta prereleases when you have joined the Beta channel. It sends no account credentials, never downloads without your approval, verifies the APK checksum, package, version and permanent signing certificate, then uses Android's normal installer. Every build is verified the same way, whichever channel it came from.",
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
        status.setText(idleMessage());
        status.setTextColor(UiKit.TEXT);
        setAction("Check for updates", this::checkForUpdates, true);
    }

    private String idleMessage() {
        return Prefs.betaChannel(this)
                ? "Ready to check for the newest Orbit release, including Betas."
                : "Ready to check for a stable Orbit release.";
    }

    /**
     * What Orbit says when there is nothing newer to install.
     *
     * <p>Three genuinely different situations. On Beta, Orbit has looked at prereleases too. On
     * Stable, it is simply current. And a Stable-channel user who is still running a Beta build is
     * neither of those: there is a newer build installed than the newest Stable release, and Orbit
     * will not downgrade it, so it says exactly that instead of pretending to be up to date.
     */
    private String upToDateMessage() {
        if (Prefs.betaChannel(this)) return "You're on the newest available Orbit build.";
        if (OrbitVersion.installedIsBeta()) {
            return "You're running a newer Beta build. Orbit will return to Stable updates when a "
                    + "newer Stable release becomes available.";
        }
        return "Orbit is up to date.";
    }

    // ---- update channel -------------------------------------------------------------------------

    private LinearLayout channelRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 16, this));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, "Update channel", 15, UiKit.TEXT, true));
        channelValue = UiKit.text(this, channelLabel(), 12, UiKit.accent(this), false);
        channelValue.setPadding(0, UiKit.dp(this, 3), 0, 0);
        copy.addView(channelValue);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, "›", 26, UiKit.accent(this), false));

        row.setOnClickListener(v -> showChannelSelector());
        UiKit.pressScale(row);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 2));
        row.setLayoutParams(rowLp);
        return row;
    }

    private String channelLabel() {
        return Prefs.betaChannel(this) ? "Beta" : "Stable";
    }

    /**
     * An Orbit-styled channel picker.
     *
     * <p>A dialog rather than the compact popup menu, because each option needs a second line
     * explaining what it commits the user to, and the popup's rows are a single fixed height.
     */
    private void showChannelSelector() {
        boolean beta = Prefs.betaChannel(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 22), UiKit.dp(this, 20),
                UiKit.dp(this, 22), UiKit.dp(this, 6));
        TextView title = UiKit.text(this, "Update channel", 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        content.addView(title);

        AlertDialog[] holder = new AlertDialog[1];
        content.addView(channelOption("Stable", "Recommended · tested releases", !beta,
                () -> {
                    if (holder[0] != null) holder[0].dismiss();
                    applyChannel(Prefs.CHANNEL_STABLE);
                }), optionLp(14));
        content.addView(channelOption("Beta", "Early access · may contain bugs", beta,
                () -> {
                    if (holder[0] != null) holder[0].dismiss();
                    // Already enrolled: choosing Beta again changes nothing and must not
                    // re-ask a question the user has already answered.
                    if (Prefs.betaChannel(this)) return;
                    confirmJoinBeta();
                }), optionLp(9));
        UiKit.applyTypography(content);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setForceDarkAllowed(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        holder[0] = dialog;
        styleOrbitDialog(dialog);
        dialog.show();
    }

    private LinearLayout.LayoutParams optionLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
    }

    private LinearLayout channelOption(String name, String description, boolean selected,
                                       Runnable onChosen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 15), UiKit.dp(this, 13),
                UiKit.dp(this, 13), UiKit.dp(this, 13));
        row.setBackground(UiKit.rippleOutlined(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.16f) : UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), selected ? 110 : 38),
                UiKit.accent(this), 16, this));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, name, 16, UiKit.TEXT, selected));
        TextView detail = UiKit.text(this, description, 12, UiKit.MUTED, false);
        detail.setLineSpacing(0, 1.1f);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        copy.addView(detail);
        row.addView(copy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, selected ? "●" : "", 15, UiKit.accent(this), true));

        row.setOnClickListener(v -> onChosen.run());
        UiKit.pressScale(row);
        return row;
    }

    /**
     * The one confirmation Orbit shows before enrolling a device in Beta.
     *
     * <p>Honest about the risk and honest about what is not at risk: a Beta build is less tested,
     * not less trusted, and it is signed and verified exactly like every Stable release. Shown
     * again if someone leaves Beta and later comes back, because that is a fresh decision to
     * accept the risk.
     */
    private void confirmJoinBeta() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 20),
                UiKit.dp(this, 24), UiKit.dp(this, 6));
        TextView title = UiKit.text(this, "Join the Beta channel?", 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        content.addView(title);
        TextView body = UiKit.text(this,
                "Beta builds give you new Orbit features earlier, but they may contain bugs, "
                        + "crashes, regressions, increased battery use, or unfinished behavior.\n\n"
                        + "Beta updates are still officially signed and verified by Orbit.\n\n"
                        + "You can return to Stable at any time. Orbit will never automatically "
                        + "downgrade your installed version.",
                14, UiKit.TEXT, false);
        body.setLineSpacing(0, 1.14f);
        body.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(body);
        UiKit.applyTypography(content);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setForceDarkAllowed(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Join Beta", (d, which) -> applyChannel(Prefs.CHANNEL_BETA))
                .create();
        styleOrbitDialog(dialog);
        dialog.show();
    }

    /**
     * Commits a channel change and puts the screen back into a clean, uncached state.
     *
     * <p>{@link Prefs#setUpdateChannel} drops the discovery state the old channel owned, so
     * anything still on screen from the previous channel is stale by definition and is cleared
     * here rather than left to be tapped.
     */
    private void applyChannel(String channel) {
        if (Prefs.normalizeChannel(channel).equals(Prefs.updateChannel(this))) return;
        Prefs.setUpdateChannel(this, channel);
        availableRelease = null;
        readyRelease = null;
        readyApk = null;
        if (channelValue != null) {
            channelValue.setText(channelLabel());
            channelValue.setTextColor(UiKit.accent(this));
        }
        showIdleState();
    }

    private TextView betaPill(String label) {
        TextView pill = UiKit.text(this, label, 10, UiKit.onAccent(this), true);
        pill.setLetterSpacing(0.09f);
        pill.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 4));
        pill.setBackground(UiKit.rounded(UiKit.accent(this), 9, this));
        return pill;
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
                        status.setText(upToDateMessage());
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
        status.setText("Update available: Orbit Assistant v" + release.displayName());
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
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = UiKit.text(this,
                "Orbit Assistant v" + release.displayName(), 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (release.isBeta()) {
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.setMargins(UiKit.dp(this, 10), UiKit.dp(this, 2), 0, 0);
            titleRow.addView(betaPill("BETA"), pillLp);
        }
        content.addView(titleRow);
        if (release.isBeta()) {
            // One short line, once, at the moment of installing. The user opted into Beta
            // already; the full warning is not repeated for every update.
            TextView betaNote = UiKit.text(this,
                    "Beta build · may be less stable than a normal Orbit release.",
                    12, UiKit.MUTED, false);
            betaNote.setLineSpacing(0, 1.1f);
            betaNote.setPadding(0, UiKit.dp(this, 7), 0, 0);
            content.addView(betaNote);
        }
        TextView body = UiKit.text(this, "", 14, UiKit.TEXT, false);
        body.setText(OrbitMarkdown.render(this, notes));
        body.setLinkTextColor(UiKit.linkColorOn(this, UiKit.SURFACE));
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setLinksClickable(true);
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
                        OrbitUpdater.launchPackageInstaller(UpdateActivity.this, apk, release);
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

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
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
        UiKit.styleOrbitDialog(dialog, this, false);
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

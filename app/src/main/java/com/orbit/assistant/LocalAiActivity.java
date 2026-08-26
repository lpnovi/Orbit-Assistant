package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

/**
 * Orbit Local: the on-device AI management screen, and the only one there is.
 *
 * <p>The optional component is a separate package, but it deliberately has no UI of its own —
 * no launcher icon, no settings, no onboarding. Everything about Orbit Local is managed from
 * here, so from a user's point of view there is still just Orbit with a feature that can be
 * installed and removed.
 *
 * <p>Four cards, in the order the user needs them: what this device can do, the component that
 * runs the AI, the model it runs, and what all of it is costing in storage. Nothing here touches
 * the main thread with file, network, or cross-process work.
 */
public final class LocalAiActivity extends Activity {
    private static final long REFRESH_MS = 700L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout cards;
    private String appearanceSignature;

    /** Last status read from the component, or null when it cannot be reached. */
    private OrbitLocalStatus status;
    private boolean installing;
    private int installPercent = -1;
    private String installMessage = "";
    private File readyComponentApk;
    private boolean waitingForInstallPermission;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            readStatus();
            rebuild();
            if (needsPolling()) main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        // Coming back from Android's installer or uninstaller: what actually happened is
        // whatever the package manager now reports, never what Orbit asked for.
        OrbitLocalProvider.invalidateStatus();
        reconcileProviderSelection();
        if (waitingForInstallPermission && readyComponentApk != null
                && OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = false;
            launchComponentInstaller(readyComponentApk);
        }
        main.removeCallbacks(refresh);
        refresh.run();
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        UiPresence.leave(this);
        super.onPause();
    }

    private boolean needsPolling() {
        if (installing) return true;
        return status != null && status.modelBusy();
    }

    /** Reads component status off the main thread, then redraws when it lands. */
    private void readStatus() {
        if (!OrbitLocalComponent.isUsable(this)) {
            status = null;
            return;
        }
        OrbitLocalClient.statusAsync(this, fresh -> main.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            boolean changed = describe(status) != describe(fresh);
            status = fresh;
            if (changed) rebuild();
        }));
    }

    private static String describe(OrbitLocalStatus status) {
        return status == null ? "none" : status.modelState + ":" + status.modelBytes;
    }

    /**
     * Orbit Local must never sit there claiming to be active when it cannot answer.
     *
     * <p>The component can be uninstalled from Android Settings at any moment, entirely outside
     * Orbit, so its absence is reconciled whenever this screen is shown rather than assumed.
     */
    private void reconcileProviderSelection() {
        if (!Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) return;
        if (AiProviders.byId(Prefs.PROVIDER_LOCAL).selectable(this)) return;
        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
    }

    // ---- layout -----------------------------------------------------------------------------------

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
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        back.setContentDescription("Back to AI Providers");
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> finish());
        UiKit.pressScale(back);
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "Orbit Local", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Private AI on your phone", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView intro = UiKit.text(this,
                "Orbit Local answers on the phone itself. Conversations never leave the device, and chat keeps working with no internet at all. It is optional: nothing is installed until you ask for it, and everything can be removed again.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), UiKit.dp(this, 4));
        page.addView(intro, introLp);

        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        page.addView(cards, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuild();

        UiKit.applyTypography(page);
        return scroll;
    }

    private void rebuild() {
        if (cards == null) return;
        cards.removeAllViews();
        cards.addView(deviceCard(), cardLp());
        cards.addView(componentCard(), cardLp());
        cards.addView(modelCard(), cardLp());
        cards.addView(storageCard(), cardLp());
        if (OrbitLocalComponent.isInstalled(this)) cards.addView(removeCard(), cardLp());
        UiKit.applyTypography(cards);
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private TextView pill(String label, boolean accent) {
        TextView pill = UiKit.text(this, label, 11,
                accent ? UiKit.onAccent(this) : UiKit.TEXT, true);
        pill.setBackground(accent
                ? UiKit.rounded(UiKit.accent(this), 99, this)
                : UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), 99, this));
        pill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
        return pill;
    }

    private LinearLayout titleRow(String title, String pillLabel, boolean accentPill) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        row.addView(UiKit.text(this, title, 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(pill(pillLabel, accentPill));
        return row;
    }

    // ---- this device ------------------------------------------------------------------------------

    private View deviceCard() {
        DeviceCapabilityCheck.Assessment assessment = DeviceCapabilityCheck.assess(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, "This device", 12, UiKit.MUTED, true));

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        View dot = new View(this);
        dot.setBackground(UiKit.rounded(tierColor(assessment.tier), 99, this));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 9), UiKit.dp(this, 9));
        dotLp.rightMargin = UiKit.dp(this, 8);
        row.addView(dot, dotLp);
        row.addView(UiKit.text(this, assessment.tierLabel(), 16, UiKit.TEXT, true));
        card.addView(row);

        TextView summary = UiKit.text(this, assessment.summary, 13, UiKit.MUTED, false);
        summary.setLineSpacing(0, 1.13f);
        summary.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(summary);

        long free = LocalModelStore.freeStorageBytes(this);
        if (free >= 0) {
            TextView storage = UiKit.text(this,
                    "Free storage: " + LocalModelStore.formatBytes(free), 12, UiKit.MUTED, false);
            storage.setPadding(0, UiKit.dp(this, 5), 0, 0);
            card.addView(storage);
        }
        return card;
    }

    private int tierColor(DeviceCapabilityCheck.Tier tier) {
        switch (tier) {
            case EXCELLENT:
            case SUPPORTED: return UiKit.SUCCESS;
            case LIMITED: return Color.rgb(240, 193, 100);
            default: return UiKit.DANGER;
        }
    }

    // ---- the component ----------------------------------------------------------------------------

    private View componentCard() {
        OrbitLocalComponent.State state = OrbitLocalComponent.state(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Orbit Local component", 12, UiKit.MUTED, true));
        card.addView(titleRow("On-device AI component",
                OrbitLocalComponent.stateLabel(state), state == OrbitLocalComponent.State.INSTALLED));

        TextView details = UiKit.text(this, componentDetails(state), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (installing) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(100);
            bar.setProgress(Math.max(0, installPercent));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);
            TextView progressText = UiKit.text(this,
                    installPercent < 0 ? "Preparing download…" : installPercent + "%",
                    12, UiKit.MUTED, false);
            progressText.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(progressText);
        }

        if (!installMessage.isEmpty()) {
            TextView message = UiKit.text(this, installMessage, 13, UiKit.DANGER, false);
            message.setLineSpacing(0, 1.13f);
            message.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(message);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildComponentActions(actions, state);
        if (actions.getChildCount() > 0) {
            card.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private String componentDetails(OrbitLocalComponent.State state) {
        String size = componentSizeText();
        switch (state) {
            case INSTALLED:
                return "Runs AI privately on this device · " + size
                        + "\nVersion " + OrbitVersion.displayName(
                                OrbitLocalComponent.installedVersionName(this));
            case UPDATE_REQUIRED:
                return "The installed component was built for a different version of Orbit. Update it to use Orbit Local again.";
            case UNTRUSTED:
                return "A package named Orbit Local is installed but was not published by Orbit, so it will not be used. Remove it, then install Orbit Local from here.";
            default:
                return "Required to run AI privately on this device · " + size;
        }
    }

    private String componentSizeText() {
        long bytes = OrbitLocalInstaller.knownComponentBytes(this);
        return bytes > 0L ? LocalModelStore.formatBytes(bytes) : "a small download";
    }

    private void buildComponentActions(LinearLayout actions, OrbitLocalComponent.State state) {
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(DeviceCapabilityCheck.assess(this));
        if (installing) {
            Button working = secondaryButton("Downloading…");
            working.setEnabled(false);
            working.setAlpha(0.62f);
            addPrimaryAction(actions, working);
            return;
        }
        switch (state) {
            case INSTALLED:
                return;
            case UPDATE_REQUIRED: {
                Button update = primaryButton("Update component");
                update.setOnClickListener(v -> startComponentDownload());
                addPrimaryAction(actions, update);
                return;
            }
            case UNTRUSTED: {
                Button remove = dangerButton("Remove untrusted component");
                remove.setOnClickListener(v -> OrbitLocalInstaller.requestUninstall(this));
                addPrimaryAction(actions, remove);
                return;
            }
            default: {
                // Nothing installed at all: present one Orbit Local setup, not two technical
                // downloads. The component always comes first, because a model without it cannot
                // run and downloading 1.6 GB into that situation would be indefensible.
                boolean firstTime = !LocalModelStore.hasLegacyModel(this);
                Button install = primaryButton(firstTime ? "Set up Orbit Local" : "Install component");
                install.setEnabled(allowed);
                if (!allowed) install.setAlpha(0.5f);
                install.setOnClickListener(v -> {
                    if (firstTime) showSetupSheet();
                    else startComponentDownload();
                });
                addPrimaryAction(actions, install);
            }
        }
    }

    /**
     * The one place a new user is told what Orbit Local actually costs.
     *
     * <p>Both downloads, both sizes, stated up front and in that order, with the plain fact that
     * they can be removed again. Nothing starts until Continue.
     */
    private void showSetupSheet() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 20),
                UiKit.dp(this, 24), UiKit.dp(this, 6));
        TextView title = UiKit.text(this, "Set up Orbit Local", 20, UiKit.TEXT, true);
        title.setAccessibilityHeading(true);
        content.addView(title);

        TextView lead = UiKit.text(this, "Orbit Local needs two optional downloads:",
                14, UiKit.TEXT, false);
        lead.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(lead);

        content.addView(setupItem("Orbit Local component · " + componentSizeText(),
                "Lets Orbit run AI privately on this device."));
        content.addView(setupItem("AI model · "
                        + LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES),
                "Contains the local AI model itself."));

        TextView footer = UiKit.text(this, "Both can be removed later.", 13, UiKit.MUTED, false);
        footer.setPadding(0, UiKit.dp(this, 12), 0, 0);
        content.addView(footer);
        UiKit.applyTypography(content);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setForceDarkAllowed(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> startComponentDownload())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private View setupItem(String title, String description) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 11),
                UiKit.dp(this, 14), UiKit.dp(this, 11));
        item.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 38), 16, this));
        item.addView(UiKit.text(this, title, 14, UiKit.TEXT, true));
        TextView detail = UiKit.text(this, description, 12, UiKit.MUTED, false);
        detail.setPadding(0, UiKit.dp(this, 3), 0, 0);
        detail.setLineSpacing(0, 1.1f);
        item.addView(detail);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 11), 0, 0);
        item.setLayoutParams(lp);
        return item;
    }

    private void startComponentDownload() {
        installing = true;
        installPercent = -1;
        installMessage = "";
        rebuild();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, REFRESH_MS);
        OrbitLocalInstaller.downloadAsync(this, new OrbitLocalInstaller.Callback() {
            @Override public void onProgress(int percent) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installPercent = percent;
                });
            }

            @Override public void onVerifying() {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installPercent = 100;
                    rebuild();
                });
            }

            @Override public void onReady(File apk) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installing = false;
                    readyComponentApk = apk;
                    rebuild();
                    launchComponentInstaller(apk);
                });
            }

            @Override public void onError(String message) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    installing = false;
                    installMessage = message;
                    rebuild();
                });
            }
        });
    }

    /** Android confirms every install. Orbit only ever hands it a fully verified file. */
    private void launchComponentInstaller(File apk) {
        if (!OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = true;
            installMessage = "Android needs permission to install the component from Orbit. Allow this source, then return to Orbit.";
            rebuild();
            OrbitUpdater.openUnknownSourcesSettings(this);
            return;
        }
        try {
            OrbitLocalInstaller.launchInstaller(this, apk);
        } catch (Exception e) {
            installMessage = "Android could not open the package installer.";
            rebuild();
        }
    }

    // ---- the model --------------------------------------------------------------------------------

    private View modelCard() {
        boolean componentReady = OrbitLocalComponent.isUsable(this);
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        LinearLayout card = card();
        if (active) {
            card.setBackground(UiKit.outlined(
                    UiKit.blend(UiKit.SURFACE, UiKit.accent(this), 0.93f),
                    UiKit.accent(this), 22, this));
        }
        card.addView(UiKit.text(this, "Local model", 12, UiKit.MUTED, true));

        String pillLabel;
        boolean accentPill;
        if (!componentReady) {
            pillLabel = "Requires Orbit Local component";
            accentPill = false;
        } else if (status == null) {
            pillLabel = "Checking…";
            accentPill = false;
        } else if (active) {
            pillLabel = "Active";
            accentPill = true;
        } else {
            pillLabel = status.stateLabel();
            accentPill = status.modelReady();
        }
        card.addView(titleRow(LocalModelStore.MODEL_DISPLAY_NAME, pillLabel, accentPill));

        TextView details = UiKit.text(this, modelDetails(componentReady), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (componentReady && status != null && (status.modelBusy()
                || OrbitLocalStatus.PAUSED.equals(status.modelState))) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(1000);
            bar.setProgress(OrbitLocalStatus.VALIDATING.equals(status.modelState)
                    ? 1000 : status.progressPerMille());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);

            String progressText;
            if (OrbitLocalStatus.VALIDATING.equals(status.modelState)) {
                progressText = "Verifying the model…";
            } else if (OrbitLocalStatus.IMPORTING.equals(status.modelState)) {
                progressText = "Moving your existing model · "
                        + LocalModelStore.formatBytes(status.modelBytes) + " of "
                        + LocalModelStore.formatBytes(status.modelSizeBytes);
            } else {
                progressText = LocalModelStore.formatBytes(status.modelBytes) + " of "
                        + LocalModelStore.formatBytes(status.modelSizeBytes)
                        + " · " + (status.progressPerMille() / 10) + "%";
            }
            TextView progress = UiKit.text(this, progressText, 12, UiKit.MUTED, false);
            progress.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(progress);
        }

        if (componentReady && status != null && !status.modelError.isEmpty()) {
            TextView error = UiKit.text(this, status.modelError, 13, UiKit.DANGER, false);
            error.setLineSpacing(0, 1.13f);
            error.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(error);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildModelActions(actions, componentReady);
        if (actions.getChildCount() > 0) {
            card.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    private String modelDetails(boolean componentReady) {
        String size = LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES);
        if (!componentReady) {
            String base = "Local AI model · about " + size;
            if (LocalModelStore.hasLegacyModel(this)) {
                return base + "\nYou already have this model on the phone. Install the Orbit Local "
                        + "component and Orbit can move it across instead of downloading it again.";
            }
            return base + "\nInstall the Orbit Local component first.";
        }
        if (status == null) return "Checking the Orbit Local component…";
        switch (status.modelState) {
            case OrbitLocalStatus.READY:
                return size + " installed · Works completely offline\n"
                        + "Good for private chat, drafting, and quick answers. Device actions and screen reading stay with cloud providers for now.";
            case OrbitLocalStatus.DOWNLOADING:
                return "Downloading in the background. You can leave this screen; the download continues and survives interruptions.";
            case OrbitLocalStatus.VALIDATING:
                return "Making sure every byte arrived intact before the model is enabled.";
            case OrbitLocalStatus.IMPORTING:
                return "Moving the model you already downloaded into Orbit Local. Your existing copy is kept until this finishes and verifies.";
            case OrbitLocalStatus.PAUSED:
                return "The download is paused part-way. Resume any time; already-downloaded data is kept.";
            case OrbitLocalStatus.ERROR:
                return "One-time download · " + size + " · stored only on this device.";
            default:
                if (LocalModelStore.hasLegacyModel(this)) {
                    return "You already have this model on the phone from an earlier version of Orbit. "
                            + "Orbit can move it into Orbit Local instead of downloading " + size + " again.";
                }
                return "One-time download · about " + size + " · stored only on this device. After that, Orbit Local needs no internet and no account.";
        }
    }

    private void buildModelActions(LinearLayout actions, boolean componentReady) {
        if (!componentReady || status == null) return;
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(DeviceCapabilityCheck.assess(this));
        switch (status.modelState) {
            case OrbitLocalStatus.NOT_INSTALLED:
            case OrbitLocalStatus.ERROR: {
                if (LocalModelStore.hasLegacyModel(this)) {
                    Button move = primaryButton("Move existing model");
                    move.setOnClickListener(v -> confirmMigration());
                    addPrimaryAction(actions, move);
                }
                Button download = primaryButton(
                        OrbitLocalStatus.ERROR.equals(status.modelState) ? "Try again" : "Download model");
                download.setEnabled(allowed);
                if (!allowed) download.setAlpha(0.5f);
                download.setOnClickListener(v -> {
                    OrbitLocalClient.startModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, download);
                break;
            }
            case OrbitLocalStatus.PAUSED: {
                Button resume = primaryButton("Resume download");
                resume.setOnClickListener(v -> {
                    OrbitLocalClient.startModelDownload(this);
                    bumpRefresh();
                });
                addPrimaryAction(actions, resume);
                Button discard = dangerButton("Remove downloaded data");
                discard.setOnClickListener(v -> confirmDeleteModel(true));
                addDestructiveAction(actions, discard);
                break;
            }
            case OrbitLocalStatus.DOWNLOADING: {
                Button pause = secondaryButton("Pause");
                pause.setOnClickListener(v -> {
                    OrbitLocalClient.pauseModelDownload(this);
                    bumpRefresh();
                });
                addCompactAction(actions, pause);
                break;
            }
            case OrbitLocalStatus.IMPORTING: {
                Button stop = secondaryButton("Stop moving");
                stop.setOnClickListener(v -> {
                    OrbitLocalClient.abortModelImport(this);
                    bumpRefresh();
                });
                addCompactAction(actions, stop);
                break;
            }
            case OrbitLocalStatus.VALIDATING:
                break;
            case OrbitLocalStatus.READY: {
                if (!Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id())) {
                    Button use = primaryButton("Use Orbit Local");
                    use.setOnClickListener(v -> {
                        if (AiProviders.select(this, Prefs.PROVIDER_LOCAL)) {
                            Toast.makeText(this, "Orbit Local is now the active provider",
                                    Toast.LENGTH_SHORT).show();
                            rebuild();
                        }
                    });
                    addPrimaryAction(actions, use);
                }
                if (LocalModelStore.hasLegacyModel(this)) {
                    // The component has its own verified copy now, so Orbit's old one is pure
                    // duplication. Offered, never done silently.
                    Button reclaim = secondaryButton("Free "
                            + LocalModelStore.formatBytes(LocalModelStore.legacyBytes(this))
                            + " of old model data");
                    reclaim.setOnClickListener(v -> confirmReclaimLegacy());
                    addPrimaryAction(actions, reclaim);
                }
                Button delete = dangerButton("Delete local model");
                delete.setOnClickListener(v -> confirmDeleteModel(false));
                addDestructiveAction(actions, delete);
                break;
            }
            default:
                break;
        }
    }

    private void bumpRefresh() {
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, 250L);
    }

    // ---- migration --------------------------------------------------------------------------------

    /**
     * Moving the existing model, or — when there is no room to hold two copies — replacing it.
     *
     * <p>Copying needs space for a second complete model until the original goes away, so on a
     * full device Orbit says so plainly and offers the honest alternative instead of starting a
     * transfer that cannot finish.
     */
    private void confirmMigration() {
        if (!LocalModelStore.enoughStorageToMigrate(this)) {
            confirmLowStorageReplace();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Existing Orbit Local model found")
                .setMessage("Orbit can move your existing local model into the new Orbit Local "
                        + "component instead of downloading it again.\n\nYour current copy is kept "
                        + "until the moved copy has been verified, so nothing is lost if this is "
                        + "interrupted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move existing model", (d, w) -> startMigration())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void confirmLowStorageReplace() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Not enough space to move the model")
                .setMessage("Moving the model needs room for a second copy until the first is "
                        + "removed, and this device does not have it.\n\nOrbit can instead delete "
                        + "the old model and download a fresh copy into Orbit Local. Local AI will "
                        + "be unavailable until that download finishes.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace with new copy", (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    OrbitLocalClient.startModelDownload(this);
                    Toast.makeText(this, "Downloading the model into Orbit Local",
                            Toast.LENGTH_SHORT).show();
                    bumpRefresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void startMigration() {
        File legacy = LocalModelStore.legacyModelFile(this);
        new Thread(() -> {
            boolean started = OrbitLocalClient.startModelImport(
                    this, legacy, LocalModelStore.MODEL_SIZE_BYTES);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!started) {
                    Toast.makeText(this, "Orbit Local could not start moving the model",
                            Toast.LENGTH_LONG).show();
                }
                bumpRefresh();
            });
        }, "orbit-local-migrate").start();
    }

    /** Removing Orbit's now-duplicate copy, only after the component verified its own. */
    private void confirmReclaimLegacy() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Free up the old copy?")
                .setMessage("Orbit Local now has its own verified copy of the model. The older copy "
                        + "stored by Orbit is no longer used, and removing it frees about "
                        + LocalModelStore.formatBytes(LocalModelStore.legacyBytes(this))
                        + ".\n\nOrbit Local keeps working exactly as it does now.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Free up space", (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    Toast.makeText(this, "Old model data removed", Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- storage ----------------------------------------------------------------------------------

    private View storageCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Orbit Local storage", 12, UiKit.MUTED, true));

        long componentBytes = OrbitLocalComponent.installedApkBytes(this);
        long modelBytes = status == null ? 0L : status.modelTotalBytes;
        long legacyBytes = LocalModelStore.legacyBytes(this);
        long total = componentBytes + modelBytes + legacyBytes;

        LinearLayout totalRow = new LinearLayout(this);
        totalRow.setGravity(Gravity.CENTER_VERTICAL);
        totalRow.setPadding(0, UiKit.dp(this, 7), 0, 0);
        totalRow.addView(UiKit.text(this, total > 0L ? LocalModelStore.formatBytes(total) : "Nothing installed",
                17, UiKit.TEXT, true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(totalRow);

        if (total <= 0L) {
            TextView none = UiKit.text(this,
                    "Orbit Local uses no storage on this device yet. The normal Orbit app is not counted here.",
                    13, UiKit.MUTED, false);
            none.setLineSpacing(0, 1.13f);
            none.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(none);
            return card;
        }

        if (componentBytes > 0L) {
            card.addView(storageLine("Component", LocalModelStore.formatBytes(componentBytes)));
        }
        if (modelBytes > 0L) {
            String label = status != null && status.modelReady()
                    ? LocalModelStore.MODEL_DISPLAY_NAME
                    : LocalModelStore.MODEL_DISPLAY_NAME + " (partial)";
            card.addView(storageLine(label, LocalModelStore.formatBytes(modelBytes)));
        }
        if (legacyBytes > 0L) {
            card.addView(storageLine(
                    LocalModelStore.hasLegacyModel(this) ? "Earlier Orbit copy" : "Earlier partial download",
                    LocalModelStore.formatBytes(legacyBytes)));
        }
        return card;
    }

    private View storageLine(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 7), 0, 0);
        row.addView(UiKit.text(this, "· " + label, 13, UiKit.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(UiKit.text(this, value, 13, UiKit.TEXT, false));
        return row;
    }

    // ---- removal ----------------------------------------------------------------------------------

    private View removeCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Remove Orbit Local", 12, UiKit.MUTED, true));
        TextView details = UiKit.text(this,
                "Removes the local AI model and the optional Orbit Local component from this device. "
                        + "Orbit itself, your conversations, memories, Routines, settings, and cloud "
                        + "providers are all untouched.",
                13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(details);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        Button remove = dangerButton("Remove Orbit Local");
        remove.setOnClickListener(v -> confirmRemoveOrbitLocal());
        addPrimaryAction(actions, remove);
        card.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private void confirmRemoveOrbitLocal() {
        long freed = OrbitLocalComponent.installedApkBytes(this)
                + (status == null ? 0L : status.modelTotalBytes)
                + LocalModelStore.legacyBytes(this);
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove Orbit Local?")
                .setMessage("This deletes the local AI model data and removes the optional Orbit "
                        + "Local component, freeing about " + LocalModelStore.formatBytes(freed)
                        + ".\n\nOrbit itself stays installed. Your conversations, memories, "
                        + "Routines, and settings are not affected, and your cloud providers keep "
                        + "working."
                        + (active ? " ChatGPT becomes the active provider." : "")
                        + "\n\nAndroid will ask you to confirm removing the component.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove Orbit Local", (d, w) -> removeOrbitLocal())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    /**
     * Model data first, then Android's own uninstall for the package.
     *
     * <p>Orbit never claims the component was removed: whether it actually went is reconciled from
     * the package manager when this screen resumes, so a cancelled uninstall simply leaves Orbit
     * Local installed and the card unchanged.
     */
    private void removeOrbitLocal() {
        OrbitLocalClient.deleteModel(this);
        LocalModelStore.deleteLegacy(this);
        OrbitLocalInstaller.cleanup(this);
        if (Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) {
            AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
        }
        OrbitLocalClient.disconnect(this);
        OrbitLocalProvider.invalidateStatus();
        OrbitLocalInstaller.requestUninstall(this);
    }

    private void confirmDeleteModel(boolean partialOnly) {
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        long bytes = status == null ? LocalModelStore.MODEL_SIZE_BYTES : status.modelTotalBytes;
        String title;
        String message;
        if (partialOnly) {
            title = "Remove downloaded data?";
            message = "About " + LocalModelStore.formatBytes(bytes)
                    + " of partly downloaded model data will be removed, freeing that storage. "
                    + "You can start the download again at any time.";
        } else {
            title = "Remove " + LocalModelStore.MODEL_DISPLAY_NAME + "?";
            message = "About " + LocalModelStore.formatBytes(bytes)
                    + " of downloaded model data will be removed, freeing that storage. "
                    + "The Orbit Local component stays installed, so another model can be "
                    + "downloaded without setting it up again."
                    + (active ? " ChatGPT becomes the active provider." : "")
                    + " Your conversations, settings, and other providers are not affected.";
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(partialOnly ? "Remove" : "Remove model", (d, w) -> {
                    boolean wasActive = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
                    OrbitLocalClient.deleteModel(this);
                    OrbitLocalProvider.invalidateStatus();
                    if (wasActive) {
                        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                        Toast.makeText(this, "Model removed. ChatGPT is the active provider again.",
                                Toast.LENGTH_LONG).show();
                    } else if (!partialOnly) {
                        Toast.makeText(this, "Local model removed", Toast.LENGTH_SHORT).show();
                    }
                    bumpRefresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- shared button layout ---------------------------------------------------------------------

    /** Full-width primary, in Orbit's stacked settings button language. */
    private void addPrimaryAction(LinearLayout actions, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 8);
        actions.addView(button, lp);
    }

    /** Compact right-aligned secondary action; never squeezed beside a primary. */
    private void addCompactAction(LinearLayout actions, Button button) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        row.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 8);
        actions.addView(row, lp);
    }

    /** Restrained destructive row, separated from the primary action by deliberate space. */
    private void addDestructiveAction(LinearLayout actions, Button button) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        if (actions.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 14);
        actions.addView(button, lp);
    }

    private Button primaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.onAccent(this));
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private Button secondaryButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    /** Restrained destructive treatment, matching the Extensions manager's Remove control. */
    private Button dangerButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.DANGER);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(
                UiKit.blend(UiKit.DANGER, UiKit.SURFACE_2, 0.08f),
                UiKit.withAlpha(UiKit.DANGER, 110), UiKit.DANGER, 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }
}

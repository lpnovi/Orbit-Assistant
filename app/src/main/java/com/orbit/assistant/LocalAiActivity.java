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
    /** How often live work is re-read while this screen is visible. */
    private static final long ACTIVE_REFRESH_MS = 700L;
    /** How often a settled component is re-read. Cheap, and keeps the screen honest. */
    private static final long IDLE_REFRESH_MS = 2500L;

    /**
     * Remembers which removal is waiting on Android's answer, if either is.
     *
     * <p>Persisted rather than held in a field because Android's uninstall confirmation can take
     * this Activity through a full destroy and recreate. A field would come back empty and the
     * Orbit-side cleanup would simply never happen.
     *
     * <p>Holds {@link #SCOPE_EVERYTHING}, {@link #SCOPE_COMPONENT}, or nothing at all. The two
     * scopes are genuinely different requests and are not allowed to borrow each other's cleanup:
     * uninstalling the component must not quietly delete a model an older Orbit still owns.
     */
    private static final String KEY_REMOVAL_PENDING = "orbit_local_removal_pending";
    /** Everything Orbit Local uses: component, its model, legacy model data, installer cache. */
    private static final String SCOPE_EVERYTHING = "everything";
    /** Only the component and the model Android takes with it. Legacy Orbit data is left alone. */
    private static final String SCOPE_COMPONENT = "component";

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
    /** What the last removal attempt came to, shown once and never invented. */
    private String removalMessage = "";
    /** True between kicking off a status read and its answer landing. */
    private boolean statusInFlight;
    /** True only while this screen is between onResume and onPause. */
    private boolean visible;

    /**
     * One tick of the live view of the component.
     *
     * <p>The v0.7.7.5 version read status asynchronously and then decided whether to keep polling
     * from the <em>previous</em> status, which had not been replaced yet. On a fresh download that
     * previous status was "nothing installed", so polling stopped one tick after it started: the
     * {@code .part} file kept growing on disk while the screen sat frozen on a stale megabyte
     * count. Scheduling now happens where the fresh status arrives, and nowhere else.
     */
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            // The component APK's own download percentage is Orbit's to draw; the model's comes
            // from the component and is drawn when its answer lands.
            if (installing) rebuild();
            readStatus();
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
        visible = true;
        // Coming back from Android's installer or uninstaller: what actually happened is
        // whatever the package manager now reports, never what Orbit asked for.
        reconcileComponentRemoval();
        reconcileComponentInstall();
        reconcileProviderSelection();
        if (waitingForInstallPermission && readyComponentApk != null
                && OrbitUpdater.canRequestPackageInstalls(this)) {
            waitingForInstallPermission = false;
            launchComponentInstaller(readyComponentApk);
        }
        main.removeCallbacks(refresh);
        rebuild();
        readStatus();
    }

    /**
     * Settles a "Remove Orbit Local" that was waiting on Android's own uninstall confirmation.
     *
     * <p>The ordering v0.7.7.5 got wrong. It deleted the model, the legacy copy, and the installer
     * cache <em>before</em> asking Android to remove the package — so cancelling Android's
     * confirmation left a component installed with its 1.6 GB model already thrown away, and Orbit
     * had no way to tell that from success.
     *
     * <p>Now nothing is deleted until the package manager says the component is genuinely gone.
     * The observed package is the whole decision; the status broadcast only supplies the sentence
     * shown to the user. A cancelled removal leaves the component, the model, the legacy data, and
     * the provider selection exactly as they were.
     */
    /** What resuming after an uninstall request must do. Nothing else decides this. */
    enum RemovalOutcome {
        /** No removal was in flight. Nothing to settle. */
        NOTHING_PENDING,
        /** The package is still there, so nothing of the user's may be touched. */
        CANCELLED,
        /** The component went. Orbit's own legacy model data was not part of the request. */
        REMOVED_COMPONENT,
        /** The component went, and everything Orbit still holds for Orbit Local goes with it. */
        REMOVED_EVERYTHING
    }

    /**
     * The removal decision, on plain values.
     *
     * <p>Separated out because it is the exact judgement v0.7.7.5 never made: it deleted first and
     * asked Android second, so a cancelled uninstall was indistinguishable from a successful one
     * and the model was already gone either way. A package that is still installed can only ever
     * produce {@link RemovalOutcome#CANCELLED}, and that outcome deletes nothing.
     */
    static RemovalOutcome removalOutcome(String pendingScope, boolean componentStillInstalled) {
        if (pendingScope == null || pendingScope.isEmpty()) return RemovalOutcome.NOTHING_PENDING;
        if (componentStillInstalled) return RemovalOutcome.CANCELLED;
        return SCOPE_COMPONENT.equals(pendingScope)
                ? RemovalOutcome.REMOVED_COMPONENT : RemovalOutcome.REMOVED_EVERYTHING;
    }

    private void reconcileComponentRemoval() {
        String scope = pendingRemovalScope(this);
        RemovalOutcome outcome = removalOutcome(scope, OrbitLocalComponent.isInstalled(this));
        if (outcome == RemovalOutcome.NOTHING_PENDING) return;
        clearRemovalPending();
        if (outcome == RemovalOutcome.CANCELLED) {
            // Still here. Either the user backed out, or Android refused — and in both cases the
            // honest thing is to change nothing and say so.
            String reported = OrbitLocalUninstaller.message(lastRemovalStage(this));
            removalMessage = reported.isEmpty()
                    ? "Orbit Local was not removed. Nothing was deleted." : reported;
            return;
        }
        // Confirmed absent. Android took the component and, with it, the model that lived in the
        // component's own private storage — so what is left is only what belongs to Orbit.
        finishOrbitSideRemoval(scope);
        removalMessage = outcome == RemovalOutcome.REMOVED_COMPONENT
                ? "The Orbit Local component was uninstalled."
                : "Orbit Local was removed.";
        // A complete removal takes the card that message would have been shown on with it, so the
        // confirmation is said out loud instead of being written somewhere that no longer exists.
        if (!hasAnythingToRemove()) {
            Toast.makeText(this, removalMessage, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The Orbit-side remainder, run only after the component is confirmed gone.
     *
     * <p>Deliberately small, because Android took the large part with it. What is left is the
     * cached component installer, a provider selection that can no longer answer, and — for the
     * full removal only — a model left behind by an Orbit from before Orbit Local was modular.
     * An "Uninstall component" must not reach that far: nobody asked it to.
     */
    private void finishOrbitSideRemoval(String scope) {
        if (SCOPE_EVERYTHING.equals(scope)) LocalModelStore.deleteLegacy(this);
        OrbitLocalInstaller.cleanup(this);
        OrbitLocalClient.disconnect(this);
        OrbitLocalProvider.invalidateStatus();
        status = null;
        if (Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) {
            AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
        }
    }

    /** Which removal, if any, is waiting on Android. Package-visible so tests can drive it. */
    static String pendingRemovalScope(android.content.Context context) {
        return Prefs.get(context).getString(KEY_REMOVAL_PENDING, "");
    }

    static OrbitLocalUninstaller.Stage lastRemovalStage(android.content.Context context) {
        try {
            return OrbitLocalUninstaller.Stage.valueOf(
                    DiagnosticStore.lastComponentUninstallStage(context));
        } catch (Exception e) {
            return OrbitLocalUninstaller.Stage.CANCELLED;
        }
    }

    /**
     * Written synchronously, because Android's uninstaller is opened in the very next breath.
     *
     * <p>An asynchronous write that lost its race with the process going away would leave a
     * component removed and Orbit's own side of the cleanup never run.
     */
    private void setRemovalPending(String scope) {
        Prefs.get(this).edit().putString(KEY_REMOVAL_PENDING, scope).commit();
    }

    private void clearRemovalPending() {
        Prefs.get(this).edit().putString(KEY_REMOVAL_PENDING, "").commit();
    }

    /**
     * Re-reads the component from the package manager, and settles everything that follows from it.
     *
     * <p>This is the fix for the v0.7.7.5-beta.2 device report where Android said "Install success"
     * and Orbit still said "Not installed". The manifest change is what lets the query see the
     * package at all; this is what makes Orbit ask again at the one moment it matters, instead of
     * carrying forward whatever it believed before the installer opened.
     *
     * <p>Deliberately keyed on the observed package, not on a remembered "install requested" flag.
     * A cancelled install and a successful one are indistinguishable from Orbit's side except by
     * asking Android, so Orbit asks: the cancelled case simply reports the component still missing
     * and keeps the verified download for Retry, and the successful case is the only thing that
     * ever clears the installer cache.
     */
    private void reconcileComponentInstall() {
        OrbitLocalProvider.invalidateStatus();
        if (installing) return; // A download owns the cache directory until it finishes.

        OrbitLocalComponent.State state = OrbitLocalComponent.state(this);
        if (state == OrbitLocalComponent.State.INSTALLED) {
            // Confirmed by the package manager, so the downloaded APK is now a duplicate of a
            // package Android is storing itself, and the pending install is genuinely over.
            waitingForInstallPermission = false;
            readyComponentApk = null;
            installMessage = "";
            OrbitLocalInstaller.cleanupAfterInstall(this);
            return;
        }
        // Still not usable: drop any stale binding to a component that changed underneath Orbit,
        // and clear out installers that can no longer be handed to Android — old Beta APKs and
        // interrupted downloads — while keeping this version's verified one for Retry.
        OrbitLocalClient.disconnect(this);
        OrbitLocalInstaller.prune(this, readyComponentApk);
    }

    /**
     * Leaving this screen stops watching the download. It never stops the download.
     *
     * <p>The work belongs to the component's own WorkManager job in its own process, so locking
     * the phone, opening another app, or destroying this Activity has no bearing on it. All that
     * ends here is Orbit looking.
     */
    @Override protected void onPause() {
        visible = false;
        main.removeCallbacks(refresh);
        UiPresence.leave(this);
        super.onPause();
    }

    /**
     * Reads component status off the main thread, redraws, then schedules the next read.
     *
     * <p>Single-flight: a tick that arrives while an answer is still outstanding is dropped rather
     * than starting a second reader, so no amount of time on this screen can accumulate overlapping
     * status threads.
     */
    private void readStatus() {
        if (!OrbitLocalComponent.isUsable(this)) {
            boolean changed = status != null;
            status = null;
            if (changed) rebuild();
            scheduleNextRefresh();
            return;
        }
        if (statusInFlight) return;
        statusInFlight = true;
        OrbitLocalClient.statusAsync(this, fresh -> main.post(() -> {
            statusInFlight = false;
            if (isFinishing() || isDestroyed()) return;
            boolean changed = !describe(status).equals(describe(fresh));
            status = fresh;
            if (changed) rebuild();
            // Scheduled here, from the status that just arrived, and never from the one it
            // replaced. Deciding this before the answer landed is what froze the progress bar.
            scheduleNextRefresh();
        }));
    }

    /**
     * Keeps watching for as long as this screen is visible.
     *
     * <p>Faster while something is actually moving, slower when nothing is, but never stopped: a
     * download that is queued, offline, or momentarily unreadable will start advancing again on
     * its own, and the screen has to be looking when it does.
     */
    private void scheduleNextRefresh() {
        if (!visible || isFinishing() || isDestroyed()) return;
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, activeNow() ? ACTIVE_REFRESH_MS : IDLE_REFRESH_MS);
    }

    private boolean activeNow() {
        if (installing) return true;
        return status != null && status.modelInFlight();
    }

    /**
     * What makes one reading different from the last.
     *
     * <p>Includes the byte count, so every advance of the download redraws the bar and the figure
     * beside it. Both are read straight from the component; Orbit keeps no counter of its own that
     * could drift away from the file on disk.
     */
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
        if (hasAnythingToRemove()) cards.addView(removeCard(), cardLp());
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
            case INSTALLED: {
                // The component is a real installed package, so removing just it is a real thing
                // to want — separate from Remove Orbit Local, which also clears what Orbit holds.
                Button uninstall = dangerButton("Uninstall component");
                uninstall.setOnClickListener(v -> confirmUninstallComponent());
                addDestructiveAction(actions, uninstall);
                return;
            }
            case UPDATE_REQUIRED: {
                Button update = primaryButton("Update component");
                update.setOnClickListener(v -> startComponentDownload());
                addPrimaryAction(actions, update);
                return;
            }
            case UNTRUSTED: {
                // A package pretending to be Orbit Local. Removing it goes through exactly the
                // same Android flow, and its outcome is read back from the package manager too.
                Button remove = dangerButton("Remove untrusted component");
                remove.setOnClickListener(v -> removeOrbitLocal(SCOPE_COMPONENT));
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
        scheduleNextRefresh();
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
            // The installer now reports which step of the handoff failed, so show that rather than
            // one message that was only ever accurate for the last of them.
            String message = e.getMessage();
            installMessage = message == null || message.trim().isEmpty()
                    ? "Android could not open the package installer." : message;
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

        if (componentReady && status != null && status.showsProgress()) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(1000);
            // Straight from the component's own byte count. Orbit keeps no second counter that
            // could drift away from the file actually on disk.
            bar.setProgress(OrbitLocalStatus.VALIDATING.equals(status.modelState)
                    ? 1000 : status.progressPerMille());
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);

            TextView progress = UiKit.text(this, progressText(), 12, UiKit.MUTED, false);
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

    /**
     * The line under the progress bar: how much has arrived, out of how much.
     *
     * <p>Both figures come from the component's real on-disk state, so during a download this
     * reads like "682 MB of 1.60 GB · 42%" and advances with the file rather than with a timer.
     */
    private String progressText() {
        String have = LocalModelStore.formatBytes(status.modelBytes);
        String total = LocalModelStore.formatBytes(status.modelSizeBytes);
        switch (status.modelState) {
            case OrbitLocalStatus.VALIDATING:
                return "Verifying the model…";
            case OrbitLocalStatus.IMPORTING:
                return "Moving your existing model · " + have + " of " + total;
            case OrbitLocalStatus.QUEUED:
                return have + " of " + total + " · starting…";
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                return have + " of " + total + " · waiting for a connection";
            default:
                return have + " of " + total + " · " + (status.progressPerMille() / 10) + "%";
        }
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
                return "Downloading in the background. You can leave this screen, lock the phone, or use other apps; the download keeps going and picks up where it left off after any interruption.";
            case OrbitLocalStatus.QUEUED:
                return "The download is starting. It runs in the background and does not need this screen to stay open.";
            case OrbitLocalStatus.WAITING_FOR_NETWORK:
                return "Waiting for a connection. The download continues on its own once this phone is back online; nothing already downloaded is lost.";
            case OrbitLocalStatus.VALIDATING:
                return "Making sure every byte arrived intact before the model is enabled.";
            case OrbitLocalStatus.IMPORTING:
                return "Moving the model you already downloaded into Orbit Local. Your existing copy is kept until this finishes and verifies.";
            case OrbitLocalStatus.PAUSED:
                return "You paused this download. Resume any time; already-downloaded data is kept.";
            case OrbitLocalStatus.INTERRUPTED:
                return "The download stopped before it finished. Tap Resume to continue from where it got to; already-downloaded data is kept.";
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
            case OrbitLocalStatus.PAUSED:
            case OrbitLocalStatus.INTERRUPTED: {
                // Whoever stopped it, the same two things are worth offering: continue from the
                // bytes already here, or give them up deliberately.
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
            case OrbitLocalStatus.DOWNLOADING:
            case OrbitLocalStatus.QUEUED:
            case OrbitLocalStatus.WAITING_FOR_NETWORK: {
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

    /** True when there is anything left of Orbit Local on this device to remove. */
    private boolean hasAnythingToRemove() {
        return OrbitLocalComponent.isInstalled(this) || LocalModelStore.legacyBytes(this) > 0L;
    }

    private View removeCard() {
        boolean componentInstalled = OrbitLocalComponent.isInstalled(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Remove Orbit Local", 12, UiKit.MUTED, true));
        TextView details = UiKit.text(this,
                componentInstalled
                        ? "Removes everything Orbit Local uses on this device: the component, the "
                                + "AI model it stores, any model data left by an earlier version of "
                                + "Orbit, and the downloaded component installer.\n\nOrbit itself, "
                                + "your conversations, memories, Routines, settings, and cloud "
                                + "providers are all untouched."
                        : "The Orbit Local component is not installed, but model data from an "
                                + "earlier version of Orbit is still stored on this device. This "
                                + "removes it.\n\nOrbit itself, your conversations, memories, "
                                + "Routines, settings, and cloud providers are all untouched.",
                13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(details);

        if (!removalMessage.isEmpty()) {
            TextView outcome = UiKit.text(this, removalMessage, 13, UiKit.MUTED, false);
            outcome.setLineSpacing(0, 1.13f);
            outcome.setPadding(0, UiKit.dp(this, 9), 0, 0);
            card.addView(outcome);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        Button remove = dangerButton(componentInstalled
                ? "Remove Orbit Local" : "Remove old local model data");
        remove.setOnClickListener(v -> {
            if (componentInstalled) confirmRemoveOrbitLocal();
            else confirmRemoveLegacyOnly();
        });
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
                .setMessage("This removes everything Orbit Local uses on this device — the "
                        + "component, the AI model, any model data left by an earlier version of "
                        + "Orbit, and the downloaded installer — freeing about "
                        + LocalModelStore.formatBytes(freed)
                        + ".\n\nOrbit itself stays installed. Your conversations, memories, "
                        + "Routines, and settings are not affected, and your cloud providers keep "
                        + "working."
                        + (active ? " ChatGPT becomes the active provider." : "")
                        + "\n\nAndroid asks you to confirm next. Nothing is deleted unless you "
                        + "confirm it there.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove Orbit Local", (d, w) -> removeOrbitLocal(SCOPE_EVERYTHING))
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    /**
     * Android's uninstall first. Nothing of the user's is deleted before it answers.
     *
     * <p>Orbit never claims the component was removed. Whether it actually went is read back from
     * the package manager when this screen resumes, and only a confirmed absence lets any cleanup
     * run at all.
     */
    private void removeOrbitLocal(String scope) {
        removalMessage = "";
        setRemovalPending(scope);
        OrbitLocalUninstaller.Launch launch = OrbitLocalUninstaller.request(this);
        if (launch == OrbitLocalUninstaller.Launch.LAUNCHED) return;

        clearRemovalPending();
        if (launch == OrbitLocalUninstaller.Launch.NOT_INSTALLED) {
            // Removed from Android Settings while this screen was open. Settle the rest.
            finishOrbitSideRemoval(scope);
            removalMessage = "Orbit Local was already removed.";
        } else {
            // Never silent again. The button did nothing, and the user is told so along with the
            // one thing they can do about it.
            removalMessage = OrbitLocalUninstaller.message(
                    OrbitLocalUninstaller.Stage.NOT_LAUNCHED);
        }
        rebuild();
    }

    /**
     * The case where a model outlived the component: nothing to uninstall, only data to delete.
     *
     * <p>Happens on a phone that downloaded the 1.6 GB model before Orbit Local was modular and
     * never installed the component, or one where the component was removed from Android Settings
     * with the migration never run. Android has no part to play, so this deletes directly — after
     * the same explicit confirmation.
     */
    private void confirmRemoveLegacyOnly() {
        long bytes = LocalModelStore.legacyBytes(this);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Remove old local model data?")
                .setMessage("About " + LocalModelStore.formatBytes(bytes)
                        + " of model data stored by an earlier version of Orbit will be deleted, "
                        + "freeing that storage.\n\nOrbit itself, your conversations, memories, "
                        + "Routines, settings, and cloud providers are not affected. You can set "
                        + "Orbit Local up again at any time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    LocalModelStore.deleteLegacy(this);
                    OrbitLocalInstaller.cleanup(this);
                    OrbitLocalProvider.invalidateStatus();
                    if (Prefs.PROVIDER_LOCAL.equals(Prefs.provider(this))) {
                        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                    }
                    removalMessage = "Old local model data removed.";
                    rebuild();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- removing just the component ---------------------------------------------------------------

    /**
     * Removing the component while leaving the rest of Orbit alone.
     *
     * <p>Separate from the global removal because they are genuinely different requests, and the
     * dialog says the one thing a person could not otherwise know: the model lives in the
     * component's own private app storage, so Android takes it along with the package. Pretending
     * the model could be kept would be a promise the platform's storage model cannot honour.
     */
    private void confirmUninstallComponent() {
        long modelBytes = status == null ? 0L : status.modelTotalBytes;
        String modelWarning = modelBytes > 0L
                ? "\n\nThe local AI model is stored inside the component, so Android removes the "
                        + LocalModelStore.formatBytes(modelBytes)
                        + " of model data with it. It cannot be kept separately."
                : "";
        boolean active = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Uninstall the component?")
                .setMessage("Orbit stops being able to run AI on this device until the component "
                        + "is installed again." + modelWarning
                        + (active ? "\n\nChatGPT becomes the active provider." : "")
                        + "\n\nAndroid asks you to confirm next. Nothing is deleted unless you "
                        + "confirm it there.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Uninstall component", (d, w) -> removeOrbitLocal(SCOPE_COMPONENT))
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
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

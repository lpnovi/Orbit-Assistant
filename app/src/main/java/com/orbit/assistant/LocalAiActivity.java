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

/**
 * Orbit Local: the on-device AI management screen.
 *
 * <p>Everything the user needs lives on two cards: an honest device assessment, and the model
 * itself with one clear primary action per state — download, resume, cancel, retry, or delete.
 * Progress refreshes on a light poll while a download runs; nothing here touches the main
 * thread with file or network work.
 */
public final class LocalAiActivity extends Activity {
    private static final long REFRESH_MS = 600L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout modelCardContainer;
    private String appearanceSignature;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            rebuildModelCard();
            LocalModelStore.State state = LocalModelStore.state(LocalAiActivity.this);
            if (state == LocalModelStore.State.DOWNLOADING
                    || state == LocalModelStore.State.VALIDATING) {
                main.postDelayed(this, REFRESH_MS);
            }
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
        main.removeCallbacks(refresh);
        refresh.run();
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        UiPresence.leave(this);
        super.onPause();
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
                "Orbit Local answers on the phone itself. Conversations never leave the device, and chat keeps working with no internet at all.",
                13, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 18), UiKit.dp(this, 3), UiKit.dp(this, 4));
        page.addView(intro, introLp);

        page.addView(deviceCard(), cardLp());

        modelCardContainer = new LinearLayout(this);
        modelCardContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(modelCardContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rebuildModelCard();

        UiKit.applyTypography(page);
        return scroll;
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

    private void rebuildModelCard() {
        if (modelCardContainer == null) return;
        modelCardContainer.removeAllViews();
        modelCardContainer.addView(modelCard(), cardLp());
        UiKit.applyTypography(modelCardContainer);
    }

    private View modelCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Local model", 12, UiKit.MUTED, true));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, UiKit.dp(this, 7), 0, 0);
        titleRow.addView(UiKit.text(this, LocalModelStore.MODEL_DISPLAY_NAME, 17, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LocalModelStore.State state = LocalModelStore.state(this);
        TextView statePill = UiKit.text(this, stateLabel(state), 11,
                state == LocalModelStore.State.READY ? UiKit.onAccent(this) : UiKit.TEXT, true);
        statePill.setBackground(state == LocalModelStore.State.READY
                ? UiKit.rounded(UiKit.accent(this), 99, this)
                : UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53, 58, 72), 99, this));
        statePill.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 4), UiKit.dp(this, 10), UiKit.dp(this, 4));
        titleRow.addView(statePill);
        card.addView(titleRow);

        TextView details = UiKit.text(this, modelDetails(state), 13, UiKit.MUTED, false);
        details.setLineSpacing(0, 1.13f);
        details.setPadding(0, UiKit.dp(this, 6), 0, 0);
        card.addView(details);

        if (state == LocalModelStore.State.DOWNLOADING
                || state == LocalModelStore.State.PAUSED
                || state == LocalModelStore.State.VALIDATING) {
            ProgressBar bar = UiKit.horizontalProgress(this);
            bar.setMax(1000);
            long done = LocalModelStore.downloadedBytes(this);
            int progress = (int) Math.min(1000L,
                    done * 1000L / Math.max(1L, LocalModelStore.MODEL_SIZE_BYTES));
            bar.setProgress(state == LocalModelStore.State.VALIDATING ? 1000 : progress);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 8));
            barLp.topMargin = UiKit.dp(this, 11);
            card.addView(bar, barLp);

            TextView progressText = UiKit.text(this,
                    state == LocalModelStore.State.VALIDATING
                            ? "Verifying the downloaded model…"
                            : LocalModelStore.formatBytes(done) + " of "
                                    + LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES),
                    12, UiKit.MUTED, false);
            progressText.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(progressText);
        }

        if (state == LocalModelStore.State.ERROR) {
            String message = LocalModelStore.errorMessage(this);
            if (!message.isEmpty()) {
                TextView error = UiKit.text(this, message, 13, UiKit.DANGER, false);
                error.setLineSpacing(0, 1.13f);
                error.setPadding(0, UiKit.dp(this, 8), 0, 0);
                card.addView(error);
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, UiKit.dp(this, 12), 0, 0);
        buildActions(actions, state);
        card.addView(actions);
        return card;
    }

    private String stateLabel(LocalModelStore.State state) {
        switch (state) {
            case READY: return "Ready";
            case DOWNLOADING: return "Downloading";
            case VALIDATING: return "Verifying";
            case PAUSED: return "Paused";
            case ERROR: return "Attention";
            default: return "Not installed";
        }
    }

    private String modelDetails(LocalModelStore.State state) {
        String size = LocalModelStore.formatBytes(LocalModelStore.MODEL_SIZE_BYTES);
        switch (state) {
            case READY:
                return "Installed · " + size + " on device · Works completely offline\n"
                        + "Good for private chat, drafting, and quick answers. Device actions and screen reading stay with cloud providers for now.";
            case DOWNLOADING:
                return "Downloading in the background. You can leave this screen; the download continues and survives interruptions.";
            case VALIDATING:
                return "Making sure every byte arrived intact before the model is enabled.";
            case PAUSED:
                return "The download is paused part-way. Resume any time; already-downloaded data is kept.";
            case ERROR:
                return "One-time download · " + size + " · stored only on this device.";
            default:
                return "One-time download · about " + size + " · stored only on this device. After that, Orbit Local needs no internet and no account.";
        }
    }

    private void buildActions(LinearLayout actions, LocalModelStore.State state) {
        DeviceCapabilityCheck.Assessment assessment = DeviceCapabilityCheck.assess(this);
        boolean allowed = DeviceCapabilityCheck.allowsLocalAi(assessment);
        switch (state) {
            case NOT_INSTALLED:
            case ERROR: {
                Button download = primaryButton(state == LocalModelStore.State.ERROR
                        ? "Try again" : "Download model");
                download.setEnabled(allowed);
                if (!allowed) download.setAlpha(0.5f);
                download.setOnClickListener(v -> {
                    LocalModelDownloadWorker.start(this);
                    main.removeCallbacks(refresh);
                    refresh.run();
                });
                actions.addView(download, actionLp(false));
                break;
            }
            case PAUSED: {
                Button delete = dangerButton("Remove download");
                delete.setOnClickListener(v -> confirmDelete(true));
                actions.addView(delete, actionLp(false));
                Button resume = primaryButton("Resume");
                resume.setOnClickListener(v -> {
                    LocalModelDownloadWorker.start(this);
                    main.removeCallbacks(refresh);
                    refresh.run();
                });
                actions.addView(resume, actionLp(true));
                break;
            }
            case DOWNLOADING:
            case VALIDATING: {
                Button cancel = secondaryButton("Pause");
                cancel.setEnabled(state == LocalModelStore.State.DOWNLOADING);
                cancel.setOnClickListener(v -> {
                    LocalModelDownloadWorker.cancel(this);
                    LocalModelStore.setState(this, LocalModelStore.State.PAUSED, "");
                    rebuildModelCard();
                });
                actions.addView(cancel, actionLp(false));
                break;
            }
            case READY: {
                Button delete = dangerButton("Delete model");
                delete.setOnClickListener(v -> confirmDelete(false));
                actions.addView(delete, actionLp(false));
                if (!Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id())) {
                    Button use = primaryButton("Use Orbit Local");
                    use.setOnClickListener(v -> {
                        if (AiProviders.select(this, Prefs.PROVIDER_LOCAL)) {
                            Toast.makeText(this, "Orbit Local is now the active provider",
                                    Toast.LENGTH_SHORT).show();
                            rebuildModelCard();
                        }
                    });
                    actions.addView(use, actionLp(true));
                }
                break;
            }
        }
    }

    private void confirmDelete(boolean partialOnly) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(partialOnly ? "Remove downloaded data?" : "Delete the local model?")
                .setMessage(partialOnly
                        ? "The partly downloaded model data is removed from this phone. You can start the download again later."
                        : "The model is removed from this phone and Orbit Local stops working until it is downloaded again. Conversations are not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(partialOnly ? "Remove" : "Delete", (d, w) -> {
                    boolean wasActive = Prefs.PROVIDER_LOCAL.equals(AiProviders.active(this).id());
                    LocalModelStore.delete(this);
                    if (wasActive) {
                        // Never leave chat pointed at a provider that can no longer answer.
                        AiProviders.select(this, Prefs.PROVIDER_CHATGPT);
                        Toast.makeText(this, "Model deleted. ChatGPT is the active provider again.",
                                Toast.LENGTH_LONG).show();
                    }
                    rebuildModelCard();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private LinearLayout.LayoutParams actionLp(boolean spaced) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (spaced) lp.leftMargin = UiKit.dp(this, 9);
        return lp;
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

    private Button dangerButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(UiKit.DANGER);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 110), UiKit.DANGER, 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }
}

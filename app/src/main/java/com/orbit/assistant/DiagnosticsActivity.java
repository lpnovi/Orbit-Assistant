package com.orbit.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** Hidden developer/support page opened by long-pressing the Settings version footer. */
public final class DiagnosticsActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        View content = build();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, false);
    }

    @Override protected void onResume() { super.onResume(); UiPresence.enter(this); }
    @Override protected void onPause() { UiPresence.leave(this); super.onPause(); }

    private View build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(UiKit.BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 12), UiKit.dp(this, 20), UiKit.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 90), UiKit.dp(this, 44)));
        TextView title = UiKit.text(this, "Orbit Diagnostics", 24, UiKit.TEXT, true);
        title.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        String report = report();
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));
        card.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 42), 20, this));
        TextView body = UiKit.text(this, report, 13, UiKit.TEXT, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.18f);
        card.addView(body);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(0, UiKit.dp(this, 18), 0, UiKit.dp(this, 14));
        page.addView(card, cardLp);

        Button copy = button("Copy diagnostic report");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Orbit diagnostics", report));
            Toast.makeText(this, "Diagnostic report copied", Toast.LENGTH_SHORT).show();
        });
        page.addView(copy, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        // Composer/input-method trace for diagnosing typing failures on real hardware. Records
        // state transitions only, never message content. Reproduce the problem, then copy this.
        Button copyTyping = button("Copy typing diagnostics");
        copyTyping.setOnClickListener(v -> {
            String trace = ComposerTrace.report();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Orbit typing diagnostics", trace));
            Toast.makeText(this, "Typing diagnostics copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams typingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        typingLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        page.addView(copyTyping, typingLp);

        // Persistent Side-button launch trace. Unlike the typing trace this survives process death
        // and reboots, because the failure it exists for may end the process before anyone can get
        // here. Lifecycle milestones and state only, never conversation or screen content.
        Button copyOverlay = button("Copy overlay launch diagnostics");
        copyOverlay.setOnClickListener(v -> {
            String trace = OverlayLaunchTrace.report(this);
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Orbit overlay launch diagnostics", trace));
            }
            Toast.makeText(this, "Overlay launch diagnostics copied", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams overlayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        overlayLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        page.addView(copyOverlay, overlayLp);
        return scroll;
    }

    private String report() {
        SharedPreferences d = DiagnosticStore.prefs(this);
        String version = "0.5.9";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception ignored) {}
        ChatGptAuth.AccountInfo account = ChatGptAuth.getAccountInfo(this);
        String accountStatus = account == null ? "Not signed in" : "Connected" + (account.plan.isEmpty() ? "" : " • " + account.plan);
        List<PendingRequestStore.Item> active = PendingRequestStore.active(this);
        String updated = d.getLong("screen_updated", 0) == 0 ? "Never" :
                DateFormat.getDateTimeInstance().format(new Date(d.getLong("screen_updated", 0)));
        String error = d.getString("last_error", "");
        if (error == null || error.trim().isEmpty()) error = "None recorded";

        long autoUpdatedMs = d.getLong("auto_updated", 0L);
        String autoUpdated = autoUpdatedMs == 0 ? "Never" :
                DateFormat.getDateTimeInstance().format(new Date(autoUpdatedMs));
        String autoMode = autoUpdatedMs == 0 ? "None yet" :
                Prefs.modeLabel(d.getString("auto_mode", Prefs.MODE_BALANCED));
        String autoReason = autoUpdatedMs == 0 ? "No Auto request recorded yet" :
                d.getString("auto_reason", "");
        String autoModel = autoUpdatedMs == 0 ? "" : d.getString("auto_model", "");
        String autoReasoning = autoUpdatedMs == 0 ? "" : d.getString("auto_reasoning", "");

        List<MemoryStore.Memory> memories = MemoryStore.list(this);
        int memoryEnabledCount = 0;
        int memoryPinnedCount = 0;
        for (MemoryStore.Memory memory : memories) {
            if (memory.enabled) memoryEnabledCount++;
            if (memory.pinned) memoryPinnedCount++;
        }

        return "Orbit version: " + version +
                "\nProvider: " + Prefs.provider(this) +
                "\nChatGPT: " + accountStatus +
                "\nGlobal default mode: " + Prefs.modeLabel(this) +
                "\nAMOLED background: " + Prefs.amoledMode(this) +
                "\nLast Auto decision: " + autoMode +
                (autoUpdatedMs == 0 ? "" :
                        "\nAuto routing confidence: " + d.getInt("auto_confidence", 0) + "%" +
                        "\nAuto routing reason: " + autoReason +
                        "\nAuto model: " + autoModel +
                        "\nAuto reasoning: " + autoReasoning +
                        "\nAuto routed at: " + autoUpdated) +
                "\nPending WorkManager requests: " + active.size() +
                "\nOrbit memories: " + memories.size() +
                        " (" + memoryEnabledCount + " active, " + memoryPinnedCount + " pinned)" +
                        (Prefs.memoryEnabled(this) ? "" : " · global use disabled") +
                "\nMemory suggestions: " + Prefs.memorySuggestions(this) +
                "\nUsed-memory indicator: " + Prefs.memoryUsageIndicator(this) +
                "\nCustom app profiles: " + AppProfileStore.list(this).size() +
                "\nApp behavior source: " + d.getString("app_profile_source", "Not resolved yet") +
                "\nApp privacy: " + d.getString("app_effective_privacy", "") +
                "\nApp screen policy: " + d.getString("app_effective_screen", "") +
                "\nApp screenshot policy: " + d.getString("app_effective_screenshot", "") +
                "\nApp AI strength: " + d.getString("app_effective_mode", "") +
                "\nApp quick actions: " + d.getString("app_effective_actions", "") +
                "\nForeground package: " + d.getString("foreground_package", "") +
                "\nForeground app label: " + d.getString("foreground_label", "") +
                "\nScreen text received: " + d.getBoolean("screen_text", false) +
                "\nScreenshot received: " + d.getBoolean("screenshot", false) +
                "\nDetected screen type: " + AppProfileStore.categoryLabel(
                        d.getString("context_category", AppProfileStore.CATEGORY_GENERIC)) +
                "\nContext confidence: " + d.getInt("context_confidence", 0) + "%" +
                "\nContext source: " + (d.getBoolean("context_profile_override", false)
                        ? "App profile override" : "Automatic classifier") +
                "\nContext signal: " + d.getString("context_reason", "") +
                "\nLast screen context: " + updated +
                "\nBackground completion notifications: " + Prefs.backgroundNotifications(this) +
                "\nVoice pause-friendly mode: " + Prefs.voicePauseFriendly(this) +
                "\nSpoken voice replies: " + Prefs.speak(this) +
                "\nHands-free voice follow-ups: " + Prefs.autoListen(this) +
                "\n0.6 capabilities dashboard: available" +
                "\nLelo mode: " + Prefs.leloMode(this) +
                "\nLast overlay launch: " + OverlayLaunchTrace.summary(this) +
                "\nLast error: " + error +
                orbitLocal(d) +
                lastRoutinePlan(d);
    }

    /**
     * Everything about Orbit Local that a Beta report needs and nothing that it does not.
     *
     * <p>Package state, versions, download state, byte counts, and the tokens the component uses
     * for "why did this stop". No model contents, no conversation, no file paths. It exists
     * because v0.7.7.5 shipped two failures that were invisible from the outside: a Remove button
     * that silently did nothing, and a Paused label that was covering for a WorkManager query
     * nobody could see the answer to.
     */
    private String orbitLocal(SharedPreferences d) {
        OrbitLocalComponent.State componentState = OrbitLocalComponent.state(this);
        String installedVersion = OrbitLocalComponent.installedVersionName(this);
        long uninstallUpdated = d.getLong("local_uninstall_updated", 0L);
        String uninstallDetail = d.getString("local_uninstall_detail", "");

        // Read from the component's own last reported snapshot, so this never blocks this screen
        // on a bind that may not be possible.
        OrbitLocalStatus status = OrbitLocalProvider.cachedStatus(this);

        return "\n\nOrbit Local" +
                "\n  Component: " + OrbitLocalComponent.stateLabel(componentState) +
                (installedVersion.isEmpty() ? "" : " · " + installedVersion
                        + " (" + OrbitLocalComponent.installedVersionCode(this) + ")") +
                "\n  Protocol Orbit speaks: " + OrbitLocalComponent.PROTOCOL_VERSION +
                (status == null ? "" : "\n  Protocol component speaks: " + status.protocol) +
                "\n  Can request uninstall: "
                        + OrbitLocalUninstaller.canRequestUninstall(this) +
                "\n  Last uninstall stage: " + (uninstallUpdated == 0L ? "none"
                        : d.getString("local_uninstall_stage", "")
                                + (uninstallDetail.isEmpty() ? "" : " (" + uninstallDetail + ")")) +
                (uninstallUpdated == 0L ? "" : "\n  Last uninstall at: "
                        + DateFormat.getDateTimeInstance().format(new Date(uninstallUpdated))) +
                "\n  Pending removal: " + (LocalAiActivity.pendingRemovalScope(this).isEmpty()
                        ? "none" : LocalAiActivity.pendingRemovalScope(this)) +
                (status == null
                        ? "\n  Model: component not reachable"
                        : "\n  Model state: " + status.modelState +
                          "\n  Model bytes present: " + status.modelBytes
                                  + " of " + status.modelSizeBytes +
                          "\n  Model bytes on disk: " + status.modelTotalBytes +
                          "\n  WorkManager state: " + (status.workState.isEmpty()
                                  ? "not reported" : status.workState) +
                          "\n  Explicit pause requested: " + status.pauseRequested +
                          "\n  Last download failure: " + (status.lastFailure.isEmpty()
                                  ? "none" : status.lastFailure)) +
                "\n  Earlier Orbit model data: " + LocalModelStore.legacyBytes(this) + " bytes";
    }

    /** Compact trace of the last Create with Orbit planning attempt. */
    private String lastRoutinePlan(SharedPreferences d) {
        long updated = d.getLong("plan_updated", 0L);
        if (updated == 0L) return "\n\nLast Routine plan: none yet";
        String rejected = d.getString("plan_rejected", "");
        String failure = d.getString("plan_failure", "");
        String raw = d.getString("plan_raw", "");
        return "\n\nLast Routine plan" +
                "\n  Provider: " + d.getString("plan_provider", "") +
                "\n  Parse: " + (d.getBoolean("plan_parsed", false) ? "succeeded" : "failed") +
                " (" + d.getString("plan_shape", "") + ")" +
                "\n  Steps returned: " + d.getInt("plan_steps_returned", 0) +
                "\n  Steps accepted: " + d.getInt("plan_steps_accepted", 0) +
                "\n  Types: " + d.getString("plan_types", "") +
                (rejected.isEmpty() ? "" : "\n  Rejected: " + rejected) +
                "\n  Trigger drafted: " + d.getBoolean("plan_trigger", false) +
                "\n  Repair attempted: " + d.getBoolean("plan_repair", false) +
                (failure.isEmpty() ? "" : "\n  Failure: " + failure) +
                "\n  Planned at: " + DateFormat.getDateTimeInstance().format(new Date(updated)) +
                (raw.isEmpty() ? "" : "\n  Raw planner response:\n" + raw);
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(UiKit.TEXT);
        b.setTextSize(14);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(54,59,73), UiKit.accent(this), 16, this));
        UiKit.pressScale(b);
        return b;
    }
}

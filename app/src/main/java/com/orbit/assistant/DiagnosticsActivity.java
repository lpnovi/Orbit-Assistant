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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hidden developer/support page opened by long-pressing the Settings version footer.
 *
 * <p>Rebuilt in v0.7.7.8 Beta 2 around progressive disclosure. Everything this screen has ever
 * reported is still here and still copyable; what changed is that opening it no longer means
 * reading all of it. An Overview answers the five questions someone actually arrives with — is
 * Orbit healthy, what is it using, what happened on the last request, is anything wrong, and where
 * do I look deeper — and every detailed block sits behind a collapsed section.
 *
 * <p>Two copy actions, because the two audiences are different. <b>Copy summary</b> is short enough
 * to paste into a conversation without burying it. <b>Copy full diagnostics</b> is the verbose
 * report, explicitly asked for.
 *
 * <p><b>Privacy.</b> Nothing here records prompts, answers, reasoning-summary text, attachments,
 * screenshots, credentials, or tokens, and that is a property of the stores this reads rather than
 * of the formatting below. The one exception found in the Beta 2 audit is the raw Routine planner
 * response, which is model output derived from a description the user typed and so can contain
 * their own words. It stays visible on this screen, where it is useful and stays on the device, but
 * it is excluded from both copy actions and given its own deliberate copy control instead.
 */
public final class DiagnosticsActivity extends Activity {

    /** One collapsible block: a heading, and the lines under it. */
    private static final class Section {
        final String title;
        final String body;
        Section(String title, String body) {
            this.title = title;
            this.body = body == null ? "" : body;
        }
    }

    /** Which sections the user has opened. Deliberately not persisted: the default is clean. */
    private final Set<String> expanded = new LinkedHashSet<>();
    private LinearLayout page;

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
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 12), UiKit.dp(this, 20), UiKit.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        populate();
        return scroll;
    }

    /** Rebuilds the page in place, so toggling a section keeps the user where they were. */
    private void populate() {
        page.removeAllViews();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("Back");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 90), UiKit.dp(this, 44)));
        TextView title = UiKit.text(this, "Orbit Diagnostics", 24, UiKit.TEXT, true);
        title.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        List<Section> sections = sections();

        // Always open, always short. This is the part someone reads without deciding to.
        page.addView(overviewCard(), cardLp());

        TextView sectionsCaption = UiKit.text(this, "DETAILS", 11, UiKit.MUTED, true);
        sectionsCaption.setLetterSpacing(0.09f);
        sectionsCaption.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 10), 0, UiKit.dp(this, 2));
        page.addView(sectionsCaption);

        boolean anyOpen = !expanded.isEmpty();
        Button toggleAll = quietButton(anyOpen ? "Collapse all" : "Expand all");
        toggleAll.setOnClickListener(v -> {
            if (expanded.isEmpty()) for (Section s : sections()) expanded.add(s.title);
            else expanded.clear();
            populate();
        });
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40));
        toggleLp.setMargins(0, 0, 0, UiKit.dp(this, 6));
        page.addView(toggleAll, toggleLp);

        for (Section section : sections) page.addView(sectionCard(section), cardLp());

        // Raw planner output lives on its own, below the sections, so that its exclusion from the
        // copy actions above is visible rather than something the reader has to know.
        View planner = rawPlannerCard();
        if (planner != null) page.addView(planner, cardLp());

        Button copySummary = button("Copy summary");
        copySummary.setOnClickListener(v ->
                copy("Orbit diagnostics summary", summaryReport(), "Summary copied"));
        page.addView(copySummary, buttonLp(0));

        Button copyFull = button("Copy full diagnostics");
        copyFull.setOnClickListener(v ->
                copy("Orbit diagnostics", fullReport(), "Full diagnostics copied"));
        page.addView(copyFull, buttonLp(10));

        // Composer/input-method trace for diagnosing typing failures on real hardware. Records
        // state transitions only, never message content. Reproduce the problem, then copy this.
        Button copyTyping = button("Copy typing diagnostics");
        copyTyping.setOnClickListener(v ->
                copy("Orbit typing diagnostics", ComposerTrace.report(), "Typing diagnostics copied"));
        page.addView(copyTyping, buttonLp(10));

        // Persistent Side-button launch trace. Unlike the typing trace this survives process death
        // and reboots, because the failure it exists for may end the process before anyone can get
        // here. Lifecycle milestones and state only, never conversation or screen content.
        Button copyOverlay = button("Copy overlay launch diagnostics");
        copyOverlay.setOnClickListener(v -> copy("Orbit overlay launch diagnostics",
                OverlayLaunchTrace.report(this), "Overlay launch diagnostics copied"));
        page.addView(copyOverlay, buttonLp(10));
    }

    private void copy(String label, String value, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    // ---- the always-visible part ------------------------------------------------------------------

    /**
     * The short answer to "is Orbit healthy and what is it doing".
     *
     * <p>Deliberately not a digest of every section below. Each line here is one a person can act
     * on without expanding anything; everything that only matters mid-investigation stays in its
     * own section.
     */
    private LinearLayout overviewCard() {
        LinearLayout card = card();
        card.addView(UiKit.text(this, "Overview", 16, UiKit.TEXT, true));
        TextView body = UiKit.text(this, overview(), 13, UiKit.TEXT, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(0, 1.18f);
        body.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(body);
        return card;
    }

    private String overview() {
        SharedPreferences d = DiagnosticStore.prefs(this);
        long autoUpdatedMs = d.getLong("auto_updated", 0L);
        String error = d.getString("last_error", "");
        boolean hasError = error != null && !error.trim().isEmpty();
        return "Orbit version: " + versionLabel() +
                "\nProvider: " + AiProviders.active(this).displayName() +
                "\nChatGPT: " + accountStatus() +
                "\nDefault mode: " + Prefs.modeLabel(this) +
                (autoUpdatedMs == 0 ? "" : "\nLast Auto route: "
                        + Prefs.modeLabel(d.getString("auto_mode", Prefs.MODE_BALANCED))
                        + " · " + d.getString("auto_model", "")
                        + " · " + d.getString("auto_reasoning", "")) +
                "\nPending requests: " + PendingRequestStore.active(this).size() +
                "\nThinking updates: " + (Prefs.thinkingUpdates(this) ? "enabled" : "disabled")
                        + " · " + ReasoningSummarySupport.lastSource(this) +
                "\nLast error: " + (hasError ? error : "None recorded");
    }

    // ---- the collapsible parts --------------------------------------------------------------------

    private List<Section> sections() {
        SharedPreferences d = DiagnosticStore.prefs(this);
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("Request flow", requestFlow(d)));
        sections.add(new Section("Thinking updates", thinkingUpdates()));
        sections.add(new Section("Auto routing", autoRouting(d)));
        sections.add(new Section("Screen & app context", screenContext(d)));
        sections.add(new Section("Memory", memory()));
        sections.add(new Section("Calendar", CalendarDiagnostics.body(this)));
        sections.add(new Section("Orbit Local", orbitLocal(d)));
        sections.add(new Section("Routines", routines(d)));
        sections.add(new Section("Advanced", advanced()));
        return sections;
    }

    private LinearLayout sectionCard(Section section) {
        boolean open = expanded.contains(section.title);
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 12, this));
        head.setContentDescription(section.title + (open ? ", expanded" : ", collapsed"));
        TextView label = UiKit.text(this, section.title, 15, UiKit.TEXT, true);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chevron = UiKit.text(this, open ? "▾" : "▸", 16, UiKit.accent(this), false);
        chevron.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 2), 0);
        head.addView(chevron);
        head.setOnClickListener(v -> {
            if (!expanded.remove(section.title)) expanded.add(section.title);
            populate();
        });
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 34)));

        if (open) {
            TextView body = UiKit.text(this, section.body.trim().isEmpty()
                    ? "Nothing recorded yet." : section.body, 13, UiKit.TEXT, false);
            body.setTextIsSelectable(true);
            body.setLineSpacing(0, 1.18f);
            body.setPadding(0, UiKit.dp(this, 8), 0, 0);
            card.addView(body);
        }
        return card;
    }

    /**
     * The raw Routine planner response, on its own and clearly separated.
     *
     * <p>This is the one field on the screen that can contain the user's own words: it is the
     * model's reply to a routine description they typed, so a routine described as "text Anna when
     * I leave work" can come back naming Anna. It is genuinely useful when a plan parses wrongly,
     * and on the device it is theirs to look at, so it stays visible. What changed in Beta 2 is
     * that it is no longer swept into a copied report by default: copying is the step that sends
     * it somewhere else, so it takes its own deliberate tap and says why.
     */
    private View rawPlannerCard() {
        String raw = DiagnosticStore.prefs(this).getString("plan_raw", "");
        if (raw == null || raw.trim().isEmpty()) return null;
        boolean open = expanded.contains(RAW_PLANNER);
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setClickable(true);
        head.setFocusable(true);
        head.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 12, this));
        head.setContentDescription(RAW_PLANNER + (open ? ", expanded" : ", collapsed"));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(UiKit.text(this, RAW_PLANNER, 15, UiKit.TEXT, true));
        TextView note = UiKit.text(this,
                "May contain your routine wording. Not included in either copied report.",
                11, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 2), 0, 0);
        labels.addView(note);
        head.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView chevron = UiKit.text(this, open ? "▾" : "▸", 16, UiKit.accent(this), false);
        chevron.setPadding(UiKit.dp(this, 8), 0, UiKit.dp(this, 2), 0);
        head.addView(chevron);
        head.setOnClickListener(v -> {
            if (!expanded.remove(RAW_PLANNER)) expanded.add(RAW_PLANNER);
            populate();
        });
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (open) {
            TextView body = UiKit.text(this, raw, 12, UiKit.TEXT, false);
            body.setTextIsSelectable(true);
            body.setLineSpacing(0, 1.15f);
            body.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 10));
            card.addView(body);
            Button copyRaw = button("Copy raw planner response");
            copyRaw.setOnClickListener(v -> copy("Orbit raw planner response", raw,
                    "Raw planner response copied"));
            card.addView(copyRaw, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        }
        return card;
    }

    /** Its own key rather than a Section, because it is never part of a copied report. */
    private static final String RAW_PLANNER = "Raw planner response";

    // ---- section bodies ---------------------------------------------------------------------------

    private String versionLabel() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private String accountStatus() {
        ChatGptAuth.AccountInfo account = ChatGptAuth.getAccountInfo(this);
        return account == null ? "Not signed in"
                : "Connected" + (account.plan.isEmpty() ? "" : " • " + account.plan);
    }

    /**
     * How Orbit's request pipeline is behaving, for the two duplication failures 0.7.7.7 exists
     * to close: one gesture producing two submissions, and one submission producing two answers.
     *
     * <p>Counts and identities only. Prompt text, reply text, conversation contents, and anything
     * else the user typed or heard never reach this store; the shortened request id is the only
     * thing that ties a line here to a specific turn, and it is Orbit's own random id.
     */
    private String requestFlow(SharedPreferences d) {
        long ignoredAt = d.getLong("completion_ignored_at", 0L);
        String ignoredDetail = ignoredAt == 0
                ? "None recorded"
                : d.getString("completion_ignored_detail", "") + " · "
                        + DateFormat.getTimeInstance().format(new Date(ignoredAt));
        long supersededAt = d.getLong("worker_superseded_at", 0L);
        String supersededDetail = supersededAt == 0
                ? "None recorded"
                : d.getString("worker_superseded_detail", "") + " · "
                        + DateFormat.getTimeInstance().format(new Date(supersededAt));
        return "\n  Accepted submissions: " + d.getInt("submissions_accepted", 0) +
                "\n  Suppressed duplicate submissions: " + d.getInt("submissions_suppressed", 0) +
                "\n  Last submission source: " + orNone(d.getString("submission_source", "")) +
                "\n  Last suppression reason: " + orNone(d.getString("submission_suppressed_reason", "")) +
                "\n  Completions committed: " + d.getInt("completions_committed", 0) +
                // Split apart deliberately. The old single line was labelled "already terminal"
                // but also counted every completion refused because the user pressed Stop, which
                // is ordinary. Only the duplicate count below means a second completion attempt
                // genuinely reached the guard.
                "\n  Completions refused: " + d.getInt("completions_ignored", 0) +
                "\n  Of those, already answered: "
                        + d.getInt("completions_ignored_duplicate", 0) +
                "\n  Last refused completion: " + ignoredDetail +
                // A stopped worker keeps running, so two executions of one request can overlap.
                // These are the ones that stood down instead of asking the model a second time,
                // which is what a refusal used to be the only evidence of.
                "\n  Superseded worker runs: " + d.getInt("worker_attempts_superseded", 0) +
                "\n  Last superseded run: " + supersededDetail +
                "\n  Active requests: " + PendingRequestStore.active(this).size();
    }

    /**
     * Enough to tell whether Thinking updates are working, and where an update came from.
     *
     * <p>Shape only, and deliberately so. What any update <em>said</em> is not here, is not stored
     * anywhere, and has no field to be stored in: reasoning summaries are ephemeral display text
     * and Orbit does not keep them.
     */
    private String thinkingUpdates() {
        AiProvider provider = AiProviders.active(this);
        long lastAt = ReasoningSummarySupport.lastUpdateAt(this);
        return "\n  Setting: " + (Prefs.thinkingUpdates(this) ? "enabled" : "disabled") +
                "\n  Provider carries reasoning summaries: "
                        + (provider.capabilities().reasoningSummaries ? "yes" : "no") +
                "\n  Backend has produced a summary: " + ReasoningSummarySupport.stateLabel(this) +
                "\n  Last thinking source: " + ReasoningSummarySupport.lastSource(this) +
                "\n  Thinking updates received: " + ReasoningSummarySupport.updatesReceived(this) +
                "\n  Last update at: " + (lastAt == 0 ? "Never"
                        : DateFormat.getDateTimeInstance().format(new Date(lastAt))) +
                "\n  Last status handed over to an answer: "
                        + (ReasoningSummarySupport.lastRequestReachedAnswer(this) ? "yes" : "no");
    }

    private String autoRouting(SharedPreferences d) {
        long autoUpdatedMs = d.getLong("auto_updated", 0L);
        if (autoUpdatedMs == 0) return "\n  No Auto request recorded yet";
        return "\n  Last Auto decision: "
                        + Prefs.modeLabel(d.getString("auto_mode", Prefs.MODE_BALANCED)) +
                "\n  Confidence: " + d.getInt("auto_confidence", 0) + "%" +
                "\n  Reason: " + d.getString("auto_reason", "") +
                "\n  Model: " + d.getString("auto_model", "") +
                "\n  Reasoning: " + d.getString("auto_reasoning", "") +
                "\n  Routed at: " + DateFormat.getDateTimeInstance().format(new Date(autoUpdatedMs));
    }

    private String screenContext(SharedPreferences d) {
        long screenUpdated = d.getLong("screen_updated", 0);
        return "\n  Foreground package: " + orNone(d.getString("foreground_package", "")) +
                "\n  Foreground app label: " + orNone(d.getString("foreground_label", "")) +
                "\n  Screen text received: " + d.getBoolean("screen_text", false) +
                "\n  Screenshot received: " + d.getBoolean("screenshot", false) +
                "\n  Detected screen type: " + AppProfileStore.categoryLabel(
                        d.getString("context_category", AppProfileStore.CATEGORY_GENERIC)) +
                "\n  Context confidence: " + d.getInt("context_confidence", 0) + "%" +
                "\n  Context source: " + (d.getBoolean("context_profile_override", false)
                        ? "App profile override" : "Automatic classifier") +
                "\n  Context signal: " + orNone(d.getString("context_reason", "")) +
                "\n  Last screen context: " + (screenUpdated == 0 ? "Never"
                        : DateFormat.getDateTimeInstance().format(new Date(screenUpdated))) +
                "\n  Custom app profiles: " + AppProfileStore.list(this).size() +
                "\n  App behavior source: " + orNone(d.getString("app_profile_source", "")) +
                "\n  App privacy: " + orNone(d.getString("app_effective_privacy", "")) +
                "\n  App screen policy: " + orNone(d.getString("app_effective_screen", "")) +
                "\n  App screenshot policy: " + orNone(d.getString("app_effective_screenshot", "")) +
                "\n  App AI strength: " + orNone(d.getString("app_effective_mode", "")) +
                "\n  App quick actions: " + orNone(d.getString("app_effective_actions", ""));
    }

    private String memory() {
        List<MemoryStore.Memory> memories = MemoryStore.list(this);
        int enabledCount = 0;
        int pinnedCount = 0;
        for (MemoryStore.Memory memory : memories) {
            if (memory.enabled) enabledCount++;
            if (memory.pinned) pinnedCount++;
        }
        return "\n  Orbit memories: " + memories.size()
                        + " (" + enabledCount + " active, " + pinnedCount + " pinned)" +
                "\n  Global memory use: " + (Prefs.memoryEnabled(this) ? "enabled" : "disabled") +
                "\n  Memory suggestions: " + Prefs.memorySuggestions(this) +
                "\n  Used-memory indicator: " + Prefs.memoryUsageIndicator(this);
    }

    /**
     * Everything about Orbit Local that a Beta report needs and nothing that it does not.
     *
     * <p>Package state, versions, download state, byte counts, and the tokens the component uses
     * for "why did this stop". No model contents, no conversation, no file paths.
     */
    private String orbitLocal(SharedPreferences d) {
        OrbitLocalComponent.State componentState = OrbitLocalComponent.state(this);
        String installedVersion = OrbitLocalComponent.installedVersionName(this);
        long uninstallUpdated = d.getLong("local_uninstall_updated", 0L);
        String uninstallDetail = d.getString("local_uninstall_detail", "");

        // Read from the component's own last reported snapshot, so this never blocks this screen
        // on a bind that may not be possible.
        OrbitLocalStatus status = OrbitLocalProvider.cachedStatus(this);

        return "\n  Component: " + OrbitLocalComponent.stateLabel(componentState) +
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

    /**
     * The last Create with Orbit planning attempt, as structure rather than content.
     *
     * <p>Every line here is Orbit's own vocabulary: action type names, counts, and its own failure
     * wording. The planner's raw reply, which is the part that can echo what the user described,
     * is not here; it has its own card and its own copy control.
     */
    private String routines(SharedPreferences d) {
        long updated = d.getLong("plan_updated", 0L);
        if (updated == 0L) return "\n  No Routine plan recorded yet";
        String rejected = d.getString("plan_rejected", "");
        String failure = d.getString("plan_failure", "");
        return "\n  Provider: " + orNone(d.getString("plan_provider", "")) +
                "\n  Parse: " + (d.getBoolean("plan_parsed", false) ? "succeeded" : "failed") +
                        " (" + d.getString("plan_shape", "") + ")" +
                "\n  Steps returned: " + d.getInt("plan_steps_returned", 0) +
                "\n  Steps accepted: " + d.getInt("plan_steps_accepted", 0) +
                "\n  Types: " + orNone(d.getString("plan_types", "")) +
                (rejected.isEmpty() ? "" : "\n  Rejected: " + rejected) +
                "\n  Trigger drafted: " + d.getBoolean("plan_trigger", false) +
                "\n  Repair attempted: " + d.getBoolean("plan_repair", false) +
                (failure.isEmpty() ? "" : "\n  Failure: " + failure) +
                "\n  Planned at: " + DateFormat.getDateTimeInstance().format(new Date(updated)) +
                "\n  Raw planner response: kept on device, see its own section";
    }

    private String advanced() {
        return "\n  AMOLED background: " + Prefs.amoledMode(this) +
                "\n  Background completion notifications: " + Prefs.backgroundNotifications(this) +
                "\n  Voice pause-friendly mode: " + Prefs.voicePauseFriendly(this) +
                "\n  Spoken voice replies: " + Prefs.speak(this) +
                "\n  Hands-free voice follow-ups: " + Prefs.autoListen(this) +
                "\n  0.6 capabilities dashboard: available" +
                "\n  Lelo mode: " + Prefs.leloMode(this) +
                "\n  Last overlay launch: " + OverlayLaunchTrace.summary(this);
    }

    private String orNone(String value) {
        return value == null || value.trim().isEmpty() ? "none" : value;
    }

    // ---- the two reports --------------------------------------------------------------------------

    /**
     * A snapshot short enough to paste into a conversation.
     *
     * <p>The selection is "what a first reply would ask for": which build, which provider, what the
     * last request did, and whether the guards that catch Orbit's historic duplication bugs have
     * fired. Anything that only matters once an investigation is already narrowed stays in the full
     * report.
     */
    String summaryReport() {
        SharedPreferences d = DiagnosticStore.prefs(this);
        long autoUpdatedMs = d.getLong("auto_updated", 0L);
        String error = d.getString("last_error", "");
        boolean hasError = error != null && !error.trim().isEmpty();
        int refused = d.getInt("completions_ignored", 0);
        int duplicates = d.getInt("completions_ignored_duplicate", 0);
        int superseded = d.getInt("worker_attempts_superseded", 0);
        int suppressed = d.getInt("submissions_suppressed", 0);
        return "Orbit diagnostics summary" +
                "\nVersion: " + versionLabel() +
                "\nProvider: " + AiProviders.active(this).displayName()
                        + " · " + accountStatus() +
                "\nDefault mode: " + Prefs.modeLabel(this) +
                (autoUpdatedMs == 0 ? "" : "\nLast Auto route: "
                        + Prefs.modeLabel(d.getString("auto_mode", Prefs.MODE_BALANCED))
                        + " · " + d.getString("auto_model", "")
                        + " · " + d.getString("auto_reasoning", "")
                        + " · " + d.getInt("auto_confidence", 0) + "%") +
                "\nPending requests: " + PendingRequestStore.active(this).size() +
                "\nRequests: " + d.getInt("submissions_accepted", 0) + " accepted, "
                        + suppressed + " suppressed, "
                        + d.getInt("completions_committed", 0) + " committed, "
                        + refused + " refused (" + duplicates + " already answered), "
                        + superseded + " superseded runs" +
                "\nThinking updates: " + (Prefs.thinkingUpdates(this) ? "enabled" : "disabled")
                        + " · backend summaries " + ReasoningSummarySupport.stateLabel(this)
                        + " · last source " + ReasoningSummarySupport.lastSource(this)
                        + " · " + ReasoningSummarySupport.updatesReceived(this) + " received" +
                "\nOrbit Local: " + OrbitLocalComponent.stateLabel(OrbitLocalComponent.state(this)) +
                "\nCalendar permission: "
                        + (OrbitCalendarStore.hasAccess(this) ? "granted" : "not granted") +
                "\nLast error: " + (hasError ? error : "None recorded");
    }

    /**
     * The verbose report, explicitly asked for.
     *
     * <p>Every section this screen can show, in the order it shows them, so nothing that has ever
     * helped diagnose a real failure is lost to the new layout. The raw planner response is the
     * one deliberate omission.
     */
    String fullReport() {
        StringBuilder out = new StringBuilder("Orbit diagnostics\n\nOverview\n");
        out.append(indent(overview()));
        for (Section section : sections()) {
            out.append("\n\n").append(section.title).append(section.body);
        }
        out.append("\n\nRaw planner response: excluded from copied diagnostics");
        return out.toString();
    }

    /** Indents the overview's flat lines so they read like the sections beneath them. */
    private static String indent(String block) {
        return "  " + block.replace("\n", "\n  ");
    }

    // ---- small shared pieces ----------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        card.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 42), 18, this));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        lp.setMargins(0, UiKit.dp(this, topMarginDp == 0 ? 16 : topMarginDp), 0, 0);
        return lp;
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

    private Button quietButton(String text) {
        Button b = button(text);
        b.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 13, this));
        b.setTextColor(UiKit.accent(this));
        b.setTextSize(13);
        return b;
    }
}

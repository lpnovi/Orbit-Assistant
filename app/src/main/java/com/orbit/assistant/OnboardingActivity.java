package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Native, resumable first-run setup. All durable configuration stays in existing stores. */
public final class OnboardingActivity extends Activity {
    private static final String EXTRA_MANUAL = "manual";
    private static final int REQ_ASSISTANT_SETTINGS = 940;
    private static final int REQ_RESTORE_BACKUP = 941;
    private static final int REQ_NOTIFICATIONS = 942;
    private static final int REQ_LOCATION = 943;

    private int step;
    private boolean manual;
    private boolean resumedOnce;
    private String appliedAppearance = "";

    private final ChatGptAuth.LoginCallback loginCallback = new ChatGptAuth.LoginCallback() {
        @Override public void onSuccess(ChatGptAuth.AccountInfo account) {
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                Toast.makeText(OnboardingActivity.this,
                        "ChatGPT connected to Orbit", Toast.LENGTH_LONG).show();
                render();
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> {
                if (!canUpdateUi()) return;
                render();
                showMessage("ChatGPT sign-in could not complete", message);
            });
        }
    };

    public static Intent freshInstallIntent(Context context) {
        return new Intent(context, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    public static Intent manualIntent(Context context) {
        return new Intent(context, OnboardingActivity.class).putExtra(EXTRA_MANUAL, true)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        manual = getIntent() != null && getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        if (savedInstanceState != null) step = savedInstanceState.getInt("step", 0);
        else if (manual) step = 0;
        else step = OnboardingState.currentStep(this);
        persistStep();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_MANUAL, false)) {
            manual = true;
            step = 0;
            persistStep();
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("step", step);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        String appearance = UiKit.appearanceSignature(this);
        if (resumedOnce || !appearance.equals(appliedAppearance)) render();
        resumedOnce = true;
        if (step == 1 && !ChatGptAuth.isSignedIn(this) &&
                ChatGptAuth.resumePendingDeviceCode(this, loginCallback)) {
            Toast.makeText(this, "Finishing secure ChatGPT sign-in…", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (step > 0) {
            step--;
            persistStep();
            render();
        } else if (manual) {
            finish();
        } else {
            confirmSkip();
        }
    }

    private void render() {
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View root = buildPage();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        appliedAppearance = UiKit.appearanceSignature(this);
    }

    private View buildPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int horizontal = UiKit.dp(this, 20);
        page.setPadding(horizontal, UiKit.dp(this, 22), horizontal, UiKit.dp(this, 38));
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int contentWidth = Math.min(screenWidth, UiKit.dp(this, 720));
        ScrollView.LayoutParams pageLp = new ScrollView.LayoutParams(
                Math.max(UiKit.dp(this, 280), contentWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
        pageLp.gravity = Gravity.CENTER_HORIZONTAL;
        scroll.addView(page, pageLp);

        page.addView(header());
        if (step == 0) buildWelcome(page);
        else if (step == 1) buildConnect(page);
        else if (step == 2) buildAssistant(page);
        else if (step == 3) buildQuickAccess(page);
        else if (step == 4) buildCapabilities(page);
        else if (step == 5) buildPersonalize(page);
        else if (step == 6) buildStarterRoutine(page);
        else buildFinish(page);

        addNavigation(page);
        UiKit.applyTypography(page);
        return scroll;
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View mark = UiKit.orbitMark(this, 38);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44));
        markLp.rightMargin = UiKit.dp(this, 10);
        header.addView(mark, markLp);

        LinearLayout progress = new LinearLayout(this);
        progress.setOrientation(LinearLayout.VERTICAL);
        progress.addView(UiKit.text(this, step < 7 ? (step + 1) + " of 7" : "SETUP COMPLETE",
                11, UiKit.accent(this), true));
        TextView bar = UiKit.text(this, progressDots(), 16, UiKit.MUTED, false);
        bar.setLetterSpacing(.08f);
        progress.addView(bar);
        header.addView(progress, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button skip = quietButton(manual ? "Exit setup" : "Skip setup");
        skip.setOnClickListener(v -> {
            if (manual) finish(); else confirmSkip();
        });
        header.addView(skip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 42)));
        return header;
    }

    private String progressDots() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 7; i++) {
            if (i > 0) out.append("  ");
            out.append(i <= Math.min(step, 6) ? "●" : "○");
        }
        return out.toString();
    }

    private void buildWelcome(LinearLayout page) {
        addTitle(page, "Welcome to Orbit",
                "A customizable Android assistant for quick answers, screen context, device actions, Routines, reminders, and more.");
        LinearLayout card = card();
        card.addView(feature("Ask quickly", "Use the app, Quick Settings, or your system assistant gesture."));
        card.addView(feature("Act locally", "Run device actions, reminders, and editable Routines."));
        card.addView(feature("Keep control", "Choose what Orbit can access and change it later in Settings."));
        page.addView(card, cardLp());

        Button restore = secondaryButton("Restore Orbit backup");
        restore.setOnClickListener(v -> chooseBackup());
        page.addView(restore, buttonLp());
        TextView note = UiKit.text(this,
                "A restored backup brings back supported local data and appearance. ChatGPT credentials, Android permissions, and default-assistant status are intentionally not restored.",
                12, UiKit.MUTED, false);
        note.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 3), 0);
        page.addView(note);
    }

    private void buildConnect(LinearLayout page) {
        addTitle(page, "Connect Orbit",
                "Connect your ChatGPT account using Orbit's secure Codex device-code flow.");
        boolean connected = ChatGptAuth.isSignedIn(this);
        LinearLayout card = card();
        TextView state = UiKit.text(this,
                connected ? "✓ Connected to ChatGPT" : "○ Not connected yet",
                16, connected ? UiKit.SUCCESS : UiKit.TEXT, true);
        card.addView(state);
        TextView help = UiKit.text(this, connected
                ? "Orbit will keep using the existing securely stored session."
                : "Your browser handles authorization. Orbit never asks for your ChatGPT password.",
                13, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        card.addView(help);
        Button action = primaryButton(connected ? "Connected" : "Sign in with ChatGPT");
        action.setEnabled(!connected);
        action.setAlpha(connected ? .68f : 1f);
        action.setOnClickListener(v -> startChatGptLogin());
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        page.addView(card, cardLp());
    }

    private void buildAssistant(LinearLayout page) {
        addTitle(page, "Make Orbit your assistant",
                "Choosing Orbit as Android's digital assistant lets supported buttons and gestures open it system-wide.");
        boolean active = OrbitSetupHelper.isOrbitAssistantActive(this);
        LinearLayout card = card();
        card.addView(UiKit.text(this, active ? "✓ Orbit is your default assistant" :
                "○ Orbit is not currently your default assistant", 16,
                active ? UiKit.SUCCESS : UiKit.TEXT, true));
        TextView help = UiKit.text(this,
                "Android controls this setting. Available button and gesture choices vary by device.",
                13, UiKit.MUTED, false);
        help.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        card.addView(help);
        Button action = primaryButton(active ? "Manage assistant settings" : "Open assistant settings");
        action.setOnClickListener(v -> {
            if (!OrbitSetupHelper.openAssistantSettings(this, REQ_ASSISTANT_SETTINGS, true))
                showMessage("Assistant settings unavailable",
                        "Android could not open a supported default-assistant settings screen on this device.");
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        page.addView(card, cardLp());
    }

    private void buildQuickAccess(LinearLayout page) {
        addTitle(page, "Choose how to open Orbit",
                "Pick the access methods that fit your phone. None are required to continue.");
        LinearLayout ask = card();
        ask.addView(UiKit.text(this, "Ask Orbit Quick Settings tile", 16, UiKit.TEXT, true));
        addCardDescription(ask, "Open Orbit instantly from Android Quick Settings.");
        Button add = primaryButton(OnboardingState.askTileConfirmed(this) ?
                "Orbit tile added" : "Add Orbit tile");
        add.setOnClickListener(v -> QuickSettingsTiles.requestAddAskTile(this, confirmed -> {
            if (confirmed) OnboardingState.setAskTileConfirmed(this, true);
            runOnUiThread(() -> { if (canUpdateUi()) render(); });
        }));
        ask.addView(add, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        page.addView(ask, cardLp());

        LinearLayout routine = card();
        routine.addView(UiKit.text(this, "Routine tile", 16, UiKit.TEXT, true));
        addCardDescription(routine,
                "A saved Routine can also be assigned to Quick Settings when you are ready.");
        Button later = secondaryButton("Configure Routine tile later");
        later.setOnClickListener(v -> startActivity(SettingsActivity.assistantSetupIntent(this)));
        routine.addView(later, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)));
        page.addView(routine, cardLp());

        LinearLayout side = card();
        side.addView(UiKit.text(this, "Side button and assistant gesture", 16, UiKit.TEXT, true));
        addCardDescription(side, OrbitSetupHelper.isOrbitAssistantActive(this)
                ? "Orbit is selected as your assistant. Your device's supported assistant gesture can now invoke it."
                : "After choosing Orbit as the default assistant, use whichever assistant button or gesture Android exposes on your device.");
        page.addView(side, cardLp());
    }

    private void buildCapabilities(LinearLayout page) {
        addTitle(page, "Choose what Orbit can do",
                "Set up only what you want now. Specialized access is requested later when a feature actually needs it.");
        LinearLayout card = card();
        card.addView(capabilityRow("Notifications",
                "Needed for reminders, Routine alerts, and update notifications.",
                ReminderNotifier.notificationsAllowed(this), "Set up", v -> setupNotifications()));
        card.addView(capabilityRow("Screen context",
                "Lets Orbit use enabled current-screen and screenshot context.",
                Prefs.screenContext(this) || Prefs.screenshot(this), "Manage", v -> openCapabilities()));
        boolean location = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED;
        card.addView(capabilityRow("Location",
                "Useful for weather and location-based Routines.", location, "Set up", v -> setupLocation()));
        card.addView(capabilityRow("Device controls",
                "DND, brightness, precise timing, and other special access stay progressive.",
                OrbitPermissionHelper.hasDndAccess(this) || OrbitPermissionHelper.canWriteSystemSettings(this),
                "Manage", v -> openCapabilities()));
        page.addView(card, cardLp());
        Button all = secondaryButton("Manage all permissions & capabilities");
        all.setOnClickListener(v -> openCapabilities());
        page.addView(all, buttonLp());
    }

    private void buildPersonalize(LinearLayout page) {
        addTitle(page, "Make Orbit yours",
                "These are Orbit's normal appearance settings and stay synchronized with Look & Feel.");
        LinearLayout accentCard = card();
        accentCard.addView(UiKit.text(this, "Accent", 16, UiKit.TEXT, true));
        addCardDescription(accentCard, "Choose one of Orbit's existing presets.");
        String[] keys = {"dynamic", "nova", "pastel_blue", "rose"};
        String[] labels = {"Dynamic", "Nova", "Pastel Blue", "Rose"};
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < keys.length; i++) {
            if (i % 2 == 0) {
                LinearLayout row = new LinearLayout(this);
                if (i > 0) {
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
                    rowLp.topMargin = UiKit.dp(this, 6);
                    choices.addView(row, rowLp);
                } else choices.addView(row);
            }
            LinearLayout row = (LinearLayout) choices.getChildAt(choices.getChildCount() - 1);
            final String key = keys[i];
            Button choice = secondaryButton(labels[i]);
            boolean selected = key.equals(Prefs.get(this).getString(Prefs.ACCENT, "dynamic"));
            choice.setTextColor(selected ? UiKit.onAccent(this) : UiKit.TEXT);
            if (selected) choice.setBackground(UiKit.ripple(
                    UiKit.accent(this), UiKit.onAccent(this), 14, this));
            choice.setOnClickListener(v -> setAccent(key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1);
            if (i % 2 == 1) lp.leftMargin = UiKit.dp(this, 6);
            row.addView(choice, lp);
        }
        accentCard.addView(choices);
        page.addView(accentCard, cardLp());

        LinearLayout fontCard = card();
        fontCard.addView(UiKit.text(this, "App font", 16, UiKit.TEXT, true));
        addCardDescription(fontCard, "Preview and choose any existing Orbit font.");
        String[] fontKeys = {"orbit_default", "times_new_roman", "light", "condensed", "monospace", "casual"};
        String[] fontLabels = {"Orbit Default", "Times New Roman", "Light", "Condensed", "Monospace", "Casual"};
        int selectedFont = indexOf(fontKeys, Prefs.appFont(this));
        Button selector = secondaryButton(fontLabels[selectedFont] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitFontMenu(this, selector,
                fontKeys, fontLabels, selectedFont, (index, label) -> {
                    Prefs.get(this).edit().putString(Prefs.APP_FONT, fontKeys[index]).apply();
                    UiKit.notifyAppearanceChanged(this);
                    render();
                }));
        fontCard.addView(selector, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        CheckBox amoled = new CheckBox(this);
        amoled.setText("Use true black AMOLED backgrounds");
        amoled.setTextColor(UiKit.TEXT);
        amoled.setTextSize(14);
        amoled.setButtonTintList(UiKit.accentControlTint(this));
        amoled.setChecked(Prefs.amoledMode(this));
        amoled.setPadding(0, UiKit.dp(this, 11), 0, 0);
        amoled.setOnCheckedChangeListener((button, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.AMOLED_MODE, checked).apply();
            UiKit.notifyAppearanceChanged(this);
            render();
        });
        fontCard.addView(amoled);
        page.addView(fontCard, cardLp());
    }

    private void buildStarterRoutine(LinearLayout page) {
        addTitle(page, "Start with something useful",
                "Templates create normal editable Routines. Nothing runs during setup.");
        for (String id : new String[]{"gaming", "bedtime", "focus", "morning"}) {
            RoutineTemplateCatalog.Template template = findTemplate(id);
            if (template == null) continue;
            LinearLayout card = card();
            card.addView(UiKit.text(this, template.displayName, 16, UiKit.TEXT, true));
            addCardDescription(card, template.description);
            if (!template.recommendedCommandPhrase.isEmpty()) {
                TextView phrase = UiKit.text(this,
                        "Suggested Custom Command: “" + template.recommendedCommandPhrase + "”",
                        11, UiKit.accent(this), true);
                phrase.setPadding(0, 0, 0, UiKit.dp(this, 9));
                card.addView(phrase);
            }
            Button use = secondaryButton("Use template");
            use.setOnClickListener(v -> {
                RoutineStore.Routine routine = RoutineTemplatesActivity.createAndEdit(this, template);
                if (routine != null) OnboardingState.setStarterRoutineId(this, routine.id);
            });
            card.addView(use, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 44)));
            page.addView(card, cardLp());
        }
        Button all = secondaryButton("See all templates");
        all.setOnClickListener(v -> startActivity(new Intent(this, RoutineTemplatesActivity.class)));
        page.addView(all, buttonLp());
    }

    private void buildFinish(LinearLayout page) {
        addTitle(page, "Orbit is ready",
                "Setup is complete. Everything here can be changed later in Settings.");
        LinearLayout summary = card();
        List<String> items = new ArrayList<>();
        if (ChatGptAuth.isSignedIn(this)) items.add("✓ ChatGPT connected");
        if (OrbitSetupHelper.isOrbitAssistantActive(this)) items.add("✓ Orbit set as assistant");
        if (OnboardingState.askTileConfirmed(this)) items.add("✓ Quick Settings tile added");
        RoutineStore.Routine starter = RoutineStore.findById(this,
                OnboardingState.starterRoutineId(this));
        if (starter != null) items.add("✓ " + starter.name + " Routine created");
        if (items.isEmpty()) items.add("Orbit is ready to configure at your pace");
        for (String item : items) {
            TextView row = UiKit.text(this, item, 14,
                    item.startsWith("✓") ? UiKit.SUCCESS : UiKit.TEXT, true);
            row.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 5));
            summary.addView(row);
        }
        page.addView(summary, cardLp());

        LinearLayout examples = card();
        examples.addView(UiKit.text(this, "Try asking", 16, UiKit.TEXT, true));
        for (String example : new String[]{"“What's on my screen?”",
                "“Remind me in 20 minutes to check the oven”",
                "“Run my Gaming mode Routine”"}) {
            TextView text = UiKit.text(this, example, 13, UiKit.MUTED, false);
            text.setPadding(0, UiKit.dp(this, 9), 0, 0);
            examples.addView(text);
        }
        page.addView(examples, cardLp());
    }

    private void addNavigation(LinearLayout page) {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(-1, -2);
        navLp.setMargins(0, UiKit.dp(this, 18), 0, 0);
        if (step > 0 && step < 7) {
            Button back = secondaryButton("Back");
            back.setOnClickListener(v -> onBackPressed());
            nav.addView(back, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), .36f));
        }
        Button next = primaryButton(step == 0 ? "Get started" : step == 7 ?
                "Start using Orbit" : "Continue");
        next.setOnClickListener(v -> {
            if (step == 7) completeAndExit();
            else {
                step++;
                persistStep();
                render();
            }
        });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1);
        if (step > 0 && step < 7) nextLp.leftMargin = UiKit.dp(this, 9);
        nav.addView(next, nextLp);
        page.addView(nav, navLp);

        if (step == 1 || step == 2 || step == 6) {
            Button skip = quietButton("Skip for now");
            skip.setOnClickListener(v -> {
                step++;
                persistStep();
                render();
            });
            page.addView(skip, buttonLp());
        }
    }

    private void addTitle(LinearLayout page, String title, String subtitle) {
        TextView heading = UiKit.text(this, title, 28, UiKit.TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.setMargins(0, UiKit.dp(this, 27), 0, 0);
        page.addView(heading, titleLp);
        TextView sub = UiKit.text(this, subtitle, 14, UiKit.MUTED, false);
        sub.setLineSpacing(0, 1.13f);
        sub.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 18));
        page.addView(sub);
    }

    private View feature(String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 8));
        row.addView(UiKit.text(this, title, 15, UiKit.TEXT, true));
        TextView sub = UiKit.text(this, description, 12, UiKit.MUTED, false);
        sub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        row.addView(sub);
        return row;
    }

    private View capabilityRow(String title, String description, boolean ready,
                               String actionLabel, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, title, 14, UiKit.TEXT, true));
        TextView descriptionView = UiKit.text(this, description, 11, UiKit.MUTED, false);
        descriptionView.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 3));
        copy.addView(descriptionView);
        copy.addView(UiKit.text(this, ready ? "Available / Granted" : "Not configured",
                11, ready ? UiKit.SUCCESS : UiKit.MUTED, true));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button action = quietButton(ready ? "Manage" : actionLabel);
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, UiKit.dp(this, 42));
        actionLp.leftMargin = UiKit.dp(this, 8);
        row.addView(action, actionLp);
        return row;
    }

    private void addCardDescription(LinearLayout card, String description) {
        TextView text = UiKit.text(this, description, 12, UiKit.MUTED, false);
        text.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 12));
        card.addView(text);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15),
                UiKit.dp(this, 17), UiKit.dp(this, 15));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 40), 20, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46));
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        return lp;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button quietButton(String text) {
        Button button = secondaryButton(text);
        button.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 13, this));
        button.setTextColor(UiKit.accent(this));
        return button;
    }

    private void setAccent(String key) {
        Prefs.get(this).edit().putString(Prefs.ACCENT, key).apply();
        UiKit.notifyAppearanceChanged(this);
        render();
    }

    private int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(target)) return i;
        return 0;
    }

    private RoutineTemplateCatalog.Template findTemplate(String id) {
        for (RoutineTemplateCatalog.Template template : RoutineTemplateCatalog.list())
            if (template.id.equals(id)) return template;
        return null;
    }

    private void openCapabilities() {
        startActivity(new Intent(this, CapabilitiesActivity.class));
    }

    private void setupNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        else openCapabilities();
    }

    private void setupLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        else openCapabilities();
    }

    private void startChatGptLogin() {
        ChatGptAuth.requestDeviceCode(this, new ChatGptAuth.StartCallback() {
            @Override public void onSuccess(ChatGptAuth.DeviceCode code) {
                runOnUiThread(() -> { if (canUpdateUi()) showDeviceCode(code); });
                ChatGptAuth.completeDeviceCode(OnboardingActivity.this, code, loginCallback);
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage("Could not start ChatGPT sign-in", message); });
            }
        });
    }

    private void showDeviceCode(ChatGptAuth.DeviceCode code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null)
            clipboard.setPrimaryClip(ClipData.newPlainText("Orbit ChatGPT code", code.userCode));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sign in with ChatGPT")
                .setMessage("Your one-time code is:\n\n" + code.userCode +
                        "\n\nIt has been copied. Complete authorization in your browser, then return to Orbit.")
                .setPositiveButton("Open ChatGPT sign-in", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))); }
                    catch (Exception e) { Toast.makeText(this, code.verificationUrl, Toast.LENGTH_LONG).show(); }
                })
                .setNeutralButton("Copy code", (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(
                            ClipData.newPlainText("Orbit ChatGPT code", code.userCode));
                })
                .setNegativeButton("Close", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void chooseBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_RESTORE_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ASSISTANT_SETTINGS) {
            getWindow().getDecorView().postDelayed(this::render, 200L);
            return;
        }
        if (requestCode != REQ_RESTORE_BACKUP || resultCode != RESULT_OK || data == null ||
                data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                OrbitBackupManager.PreparedRestore prepared =
                        OrbitBackupManager.prepareRestore(getApplicationContext(), uri);
                runOnUiThread(() -> { if (canUpdateUi()) confirmRestore(prepared); });
            } catch (Exception e) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage("Could not open backup", safeError(e)); });
            }
        }, "orbit-onboarding-backup-validate").start();
    }

    private void confirmRestore(OrbitBackupManager.PreparedRestore prepared) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Restore Orbit backup?")
                .setMessage(prepared.confirmationMessage())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, which) -> runRestore(prepared))
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void runRestore(OrbitBackupManager.PreparedRestore prepared) {
        Toast.makeText(this, "Restoring Orbit backup…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                OrbitBackupManager.restore(getApplicationContext(), prepared);
                runOnUiThread(() -> {
                    if (!canUpdateUi()) return;
                    UiKit.syncTheme(this);
                    Toast.makeText(this,
                            "Backup restored. Continue setup to reconnect account and Android access.",
                            Toast.LENGTH_LONG).show();
                    render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { if (canUpdateUi())
                    showMessage("Could not restore backup", safeError(e)); });
            }
        }, "orbit-onboarding-backup-restore").start();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && ReminderNotifier.notificationsAllowed(this))
            NotificationHelper.ensureChannel(this);
        render();
    }

    private void confirmSkip() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Skip setup?")
                .setMessage("You can finish setup anytime from Settings → Assistant setup.")
                .setNegativeButton("Keep setting up", null)
                .setPositiveButton("Skip setup", (d, which) -> completeAndExit())
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void showMessage(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title).setMessage(message).setPositiveButton("OK", null).create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    private void completeAndExit() {
        OnboardingState.markCompleted(this);
        finish();
    }

    private void persistStep() {
        OnboardingState.setCurrentStep(this, step);
    }

    private boolean canUpdateUi() {
        return !isFinishing() && !isDestroyed();
    }

    private String safeError(Exception error) {
        return error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? "Orbit could not complete this operation." : error.getMessage();
    }
}

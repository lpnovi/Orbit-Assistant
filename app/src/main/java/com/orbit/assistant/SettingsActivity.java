package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;


public class SettingsActivity extends Activity implements UiKit.AppearanceListener {
    private static final int REQ_AUDIO = 71;
    private static final int REQ_CAMERA = 72;
    private static final int REQ_CONTACTS = 73;
    private static final int REQ_NOTIFICATIONS = 74;
    private static final int REQ_LOCATION = 75;
    private static final int REQ_EXPORT_BACKUP = 76;
    private static final int REQ_RESTORE_BACKUP = 77;
    private static final int REQ_ASSISTANT_SETTINGS = 78;
    private static final String TAG_CARD = "orbit_card";
    private static final String TAG_SECTION_PREFIX = "orbit_settings_section:";
    static final String EXTRA_SECTION = "settings_section";
    private static final String SECTION_ASSISTANT = "assistant";
    static final String SECTION_AI = "ai";
    private static final String SECTION_VOICE = "voice";
    private static final String SECTION_DATA = "data";
    private static final String SECTION_CONVERSATIONS = "conversations";
    private static final String SECTION_ROUTINES = "routines";
    private static final String SECTION_EXTENSIONS = "extensions";
    private static final String SECTION_APPEARANCE = "appearance";
    private static final String SECTION_UPDATES = "updates";
    private static final String SECTION_ADVANCED = "advanced";

    public static Intent assistantSetupIntent(Context context) {
        return new Intent(context, SettingsActivity.class).putExtra(EXTRA_SECTION, SECTION_ASSISTANT);
    }
    private TextView assistantStatus;
    private Button assistantAction;
    private TextView quickRoutineSelection;
    private TextView chatGptStatus;
    private LinearLayout providerDetails;
    private TextView providerRowStatus;
    /** Which provider the details block is currently built for, so onResume can catch switches. */
    private String providerDetailsFor;
    private ScrollView settingsScroll;
    private String appliedAppearance = "";
    /** Accent/AMOLED only — the appearance that forces a rebuild. */
    private String appliedStructuralAppearance = "";
    private boolean rebuildingAppearance;
    private int leloTapCount = 0;
    private long lastLeloTapMs = 0L;
    private String settingsSection = "";
    private final ChatGptAuth.LoginCallback chatGptLoginCallback = new ChatGptAuth.LoginCallback() {
        @Override public void onSuccess(ChatGptAuth.AccountInfo account) {
            runOnUiThread(() -> {
                if (!canShowAuthResult()) return;
                updateChatGptStatus();
                Toast.makeText(SettingsActivity.this,
                        "ChatGPT connected to Orbit", Toast.LENGTH_LONG).show();
            });
        }

        @Override public void onError(String message) {
            runOnUiThread(() -> {
                if (!canShowAuthResult()) return;
                updateChatGptStatus();
                showOrbitMessageDialog("ChatGPT sign-in could not complete", message);
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        settingsSection = normalizeSection(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECTION));
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        // Paint the window itself in Orbit's surface colour. If a rebuild ever exposes the window
        // for a frame it shows Orbit's background rather than the default decor or a black frame.
        w.setBackgroundDrawable(new ColorDrawable(UiKit.BG));
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        appliedAppearance = UiKit.appearanceSignature(this);
        appliedStructuralAppearance = UiKit.structuralAppearanceSignature(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        UiKit.registerAppearanceListener(this);
    }

    @Override
    protected void onStop() {
        UiKit.unregisterAppearanceListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        applyAppearanceChange();
        updateAssistantStatus();
        refreshQuickRoutineSelection();
        refreshProviderSection();
        updateChatGptStatus();
        if (chatGptStatus != null && !ChatGptAuth.isSignedIn(this) &&
                ChatGptAuth.resumePendingDeviceCode(this, chatGptLoginCallback)) {
            chatGptStatus.setText("Finishing secure ChatGPT sign-in…");
            chatGptStatus.setTextColor(UiKit.TEXT);
        }
    }

    @Override
    protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private View buildContent() {
        return settingsSection.isEmpty() ? buildSettingsHub() : buildDetailContent();
    }

    private View buildSettingsHub() {
        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 26), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        View logo = UiKit.orbitMark(this, 42);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        logoLp.rightMargin = UiKit.dp(this, 10);
        brand.addView(logo, logoLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Settings", 28, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Orbit Power Assistant", 13, UiKit.MUTED, false));
        brand.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(brand);

        TextView intro = UiKit.text(this,
                "Choose a section. Orbit keeps the everyday controls easy to find while deeper options stay out of the way.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 10));
        page.addView(intro, introLp);

        page.addView(settingsCategoryCard(SECTION_ASSISTANT, "Assistant setup",
                "Default assistant, Side button and Quick Settings access"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_AI, "AI & account",
                "AI Providers, ChatGPT sign-in and intelligence modes"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_VOICE, "Voice, context & permissions",
                "Voice Beta, screen context and capabilities"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_DATA, "Personalization & data",
                "Weather, Gallery, reminders, saved places, Memory and backup"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_ROUTINES, "Routines",
                "Create, edit and run saved Action Engine chains"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_EXTENSIONS, "Extensions",
                "Add integrations and new actions to Orbit"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_CONVERSATIONS, "Conversations",
                "History, chat behavior and background notifications"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_APPEARANCE, "Look & Feel",
                "Accent, font, AMOLED, conversation colors and haptics"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_UPDATES, "About & updates",
                "Current version and verified official Orbit releases"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_ADVANCED, "Advanced",
                "Diagnostics and developer tools"), categoryLp());

        TextView footer = UiKit.text(this, "Orbit " + BuildConfig.VERSION_NAME + " • Power Assistant", 12, UiKit.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setOnClickListener(v -> handleLeloSecretTap());
        footer.setOnLongClickListener(v -> {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        });
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.setMargins(0, UiKit.dp(this, 24), 0, 0);
        page.addView(footer, footerLp);
        return scroll;
    }

    private LinearLayout settingsCategoryCard(String section, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 17), UiKit.dp(this, 15), UiKit.dp(this, 15), UiKit.dp(this, 15));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 20, this));
        row.setElevation(UiKit.dp(this, 2));
        row.setClickable(true);
        row.setFocusable(true);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(UiKit.text(this, title, 16, UiKit.TEXT, true));
        TextView sub = UiKit.text(this, subtitle, 12, UiKit.MUTED, false);
        sub.setPadding(0, UiKit.dp(this, 3), 0, 0);
        text.addView(sub);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = UiKit.text(this, "›", 28, UiKit.accent(this), false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        row.addView(arrow, new LinearLayout.LayoutParams(UiKit.dp(this, 34), ViewGroup.LayoutParams.MATCH_PARENT));

        row.setOnClickListener(v -> openSettingsSection(section));
        UiKit.pressScale(row);
        return row;
    }

    private LinearLayout.LayoutParams categoryLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        return lp;
    }

    private void openSettingsSection(String section) {
        if (SECTION_ROUTINES.equals(section)) {
            startActivity(new Intent(this, RoutinesActivity.class));
            return;
        }
        if (SECTION_EXTENSIONS.equals(section)) {
            startActivity(new Intent(this, ExtensionsActivity.class));
            return;
        }
        if (SECTION_UPDATES.equals(section)) {
            startActivity(new Intent(this, UpdateActivity.class));
            return;
        }
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(EXTRA_SECTION, section);
        startActivity(intent);
    }

    private String normalizeSection(String section) {
        if (SECTION_ASSISTANT.equals(section) || SECTION_AI.equals(section) ||
                SECTION_VOICE.equals(section) || SECTION_DATA.equals(section) || SECTION_CONVERSATIONS.equals(section) ||
                SECTION_APPEARANCE.equals(section) || SECTION_ADVANCED.equals(section)) return section;
        return "";
    }

    private String sectionDisplayName(String section) {
        if (SECTION_ASSISTANT.equals(section)) return "Assistant setup";
        if (SECTION_AI.equals(section)) return "AI & account";
        if (SECTION_VOICE.equals(section)) return "Voice, context & permissions";
        if (SECTION_DATA.equals(section)) return "Personalization & data";
        if (SECTION_CONVERSATIONS.equals(section)) return "Conversations";
        if (SECTION_APPEARANCE.equals(section)) return "Look & Feel";
        if (SECTION_ADVANCED.equals(section)) return "Advanced";
        return "Settings";
    }

    private String sectionDescription(String section) {
        if (SECTION_ASSISTANT.equals(section)) return "Set Orbit as your assistant and configure Side button and Quick Settings access.";
        if (SECTION_AI.equals(section)) return "Choose and configure Orbit's active AI provider, manage your ChatGPT connection, and set default intelligence.";
        if (SECTION_VOICE.equals(section)) return "Control Voice Beta, screen awareness and device permissions.";
        if (SECTION_DATA.equals(section)) return "Manage weather preferences and the local information Orbit uses to personalize and organize your assistant experience.";
        if (SECTION_CONVERSATIONS.equals(section)) return "Choose local chat storage and background completion behavior.";
        if (SECTION_APPEARANCE.equals(section)) return "Tune Orbit's colors, typography, AMOLED presentation and tactile feedback.";
        if (SECTION_ADVANCED.equals(section)) return "Inspect local diagnostics and developer troubleshooting information.";
        return "Orbit settings.";
    }

    private String sectionSubtitle(String section) {
        if (SECTION_ASSISTANT.equals(section)) return "Core setup";
        if (SECTION_AI.equals(section)) return "Models & access";
        if (SECTION_VOICE.equals(section)) return "Input & awareness";
        if (SECTION_DATA.equals(section)) return "Local context";
        if (SECTION_CONVERSATIONS.equals(section)) return "Chat behavior";
        if (SECTION_APPEARANCE.equals(section)) return "Style & feedback";
        if (SECTION_ADVANCED.equals(section)) return "Developer tools";
        return "Orbit";
    }

    private View buildDetailContent() {
        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p = UiKit.dp(this, 20);
        page.setPadding(p, UiKit.dp(this, 30), p, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        back.setContentDescription("Back to Settings");
        back.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        back.setOnClickListener(v -> finish());
        UiKit.pressScale(back);
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        brand.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, sectionDisplayName(settingsSection), 24, UiKit.TEXT, true);
        TextView subtitle = UiKit.text(this, sectionSubtitle(settingsSection), 12, UiKit.MUTED, false);
        titles.addView(title);
        titles.addView(subtitle);
        brand.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(brand);

        TextView intro = UiKit.text(this, sectionDescription(settingsSection), 14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.14f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 16), UiKit.dp(this, 2), UiKit.dp(this, 8));
        page.addView(intro, introLp);

        page.addView(sectionTitle("SETUP", "setup"));
        LinearLayout setupCard = card();
        tagSectionCard(setupCard, "setup");
        assistantStatus = UiKit.text(this, "Checking assistant status…", 15, UiKit.TEXT, true);
        setupCard.addView(assistantStatus);
        TextView desc = UiKit.text(this, "Make Orbit the default Digital assistant app, then map Side button → Long press → Digital assistant in Samsung Settings.", 13, UiKit.MUTED, false);
        desc.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 14));
        setupCard.addView(desc);
        assistantAction = primaryButton("Make Orbit default assistant");
        assistantAction.setOnClickListener(v -> openAssistantSettings(true));
        setupCard.addView(assistantAction);
        Button rerunSetup = secondaryButton("Run setup again");
        rerunSetup.setOnClickListener(v ->
                startActivity(OnboardingActivity.manualIntent(this)));
        LinearLayout.LayoutParams rerunLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        rerunLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        setupCard.addView(rerunSetup, rerunLp);
        page.addView(setupCard);

        page.addView(sectionTitle("QUICK ACCESS", "setup"));
        LinearLayout quickCard = card();
        tagSectionCard(quickCard, "setup");
        quickCard.addView(UiKit.text(this, "Quick Settings tiles", 16, UiKit.TEXT, true));
        TextView quickNote = UiKit.text(this,
                "Open Orbit instantly, or assign one saved Routine to a Quick Settings tile.",
                12, UiKit.MUTED, false);
        quickNote.setLineSpacing(0, 1.12f);
        quickNote.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 12));
        quickCard.addView(quickNote);

        LinearLayout tileButtons = new LinearLayout(this);
        tileButtons.setGravity(Gravity.CENTER_VERTICAL);
        Button addOrbit = secondaryButton("Add Orbit tile");
        addOrbit.setOnClickListener(v -> QuickSettingsTiles.requestAddAskTile(this));
        tileButtons.addView(addOrbit, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1));
        Button addRoutine = secondaryButton("Add Routine tile");
        addRoutine.setOnClickListener(v -> QuickSettingsTiles.requestAddRoutineTile(this));
        LinearLayout.LayoutParams addRoutineLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1);
        addRoutineLp.leftMargin = UiKit.dp(this, 9);
        tileButtons.addView(addRoutine, addRoutineLp);
        quickCard.addView(tileButtons);

        TextView addHint = UiKit.text(this,
                Build.VERSION.SDK_INT >= 33
                        ? "Android will ask before adding either tile."
                        : "Add the Orbit tiles manually from Android's Quick Settings editor.",
                11, UiKit.MUTED, false);
        addHint.setPadding(0, UiKit.dp(this, 9), 0, UiKit.dp(this, 12));
        quickCard.addView(addHint);

        quickRoutineSelection = UiKit.text(this, "Quick Settings Routine: None", 13, UiKit.TEXT, true);
        quickRoutineSelection.setPadding(0, 0, 0, UiKit.dp(this, 8));
        quickCard.addView(quickRoutineSelection);
        Button chooseRoutine = secondaryButton("Choose Quick Settings Routine");
        chooseRoutine.setOnClickListener(v -> showQuickRoutineChooser());
        quickCard.addView(chooseRoutine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));
        refreshQuickRoutineSelection();
        page.addView(quickCard);

        page.addView(sectionTitle("HOME-SCREEN WIDGETS", "setup"));
        LinearLayout widgetCard = card();
        tagSectionCard(widgetCard, "setup");
        widgetCard.addView(UiKit.text(this, "Orbit widgets", 16, UiKit.TEXT, true));
        TextView widgetNote = UiKit.text(this,
                "Add Orbit widgets from your launcher's widget picker, or ask Android to pin one below.",
                12, UiKit.MUTED, false);
        widgetNote.setLineSpacing(0, 1.12f);
        widgetNote.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 12));
        widgetCard.addView(widgetNote);

        TextView widgetEditNote = UiKit.text(this,
                "The buttons below add new widgets. To change one later, touch and hold it on your Home screen, then choose Settings, Edit, or Configure. The exact label depends on your launcher.",
                11, UiKit.MUTED, false);
        widgetEditNote.setLineSpacing(0, 1.12f);
        widgetEditNote.setPadding(0, 0, 0, UiKit.dp(this, 12));
        widgetCard.addView(widgetEditNote);

        Button pinAskWidget = secondaryButton("Add Ask Orbit widget");
        pinAskWidget.setOnClickListener(v -> OrbitWidgets.requestPin(this,
                AskOrbitWidgetProvider.class, "Ask Orbit"));
        widgetCard.addView(pinAskWidget, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44)));

        Button pinRoutineWidget = secondaryButton("Add Run Routine widget");
        pinRoutineWidget.setOnClickListener(v -> OrbitWidgets.requestPin(this,
                RunRoutineWidgetProvider.class, "Run Routine"));
        LinearLayout.LayoutParams pinRoutineWidgetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        pinRoutineWidgetLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        widgetCard.addView(pinRoutineWidget, pinRoutineWidgetLp);

        Button pinQuickWidget = secondaryButton("Add Quick Actions widget");
        pinQuickWidget.setOnClickListener(v -> OrbitWidgets.requestPin(this,
                QuickActionsWidgetProvider.class, "Quick Actions"));
        LinearLayout.LayoutParams pinQuickWidgetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        pinQuickWidgetLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        widgetCard.addView(pinQuickWidget, pinQuickWidgetLp);
        page.addView(widgetCard);

        TextView remindersSection = sectionTitle("REMINDERS", "data");
        LinearLayout remindersCard = card();
        tagSectionCard(remindersCard, "data");
        TextView remindersHelp = UiKit.text(this,
                "Orbit reminders are scheduled locally on this device and appear as notifications at the time you choose.",
                13, UiKit.MUTED, false);
        remindersHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
        remindersCard.addView(remindersHelp);
        Button manageReminders = secondaryButton("Manage Orbit reminders");
        manageReminders.setOnClickListener(v -> startActivity(new Intent(this, RemindersActivity.class)));
        remindersCard.addView(manageReminders, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        // Added with the other Personalization & data sections below.

        TextView personalizationSection = sectionTitle("PERSONALIZATION & CONTEXT", "data");
        LinearLayout personalDataCard = card();
        tagSectionCard(personalDataCard, "data");
        TextView personalDataHelp = UiKit.text(this,
                "Open Orbit's dedicated managers for saved places, remembered information, per-app behavior and notification context.",
                13, UiKit.MUTED, false);
        personalDataHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
        personalDataCard.addView(personalDataHelp);

        Button managePlaces = secondaryButton("Manage saved places");
        managePlaces.setOnClickListener(v -> startActivity(new Intent(this, SavedPlacesActivity.class)));
        personalDataCard.addView(managePlaces, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button manageMemory = secondaryButton("Manage Orbit Memory");
        manageMemory.setOnClickListener(v -> startActivity(new Intent(this, MemoryActivity.class)));
        LinearLayout.LayoutParams manageMemoryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageMemoryLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(manageMemory, manageMemoryLp);

        Button manageApps = secondaryButton("Manage app profiles");
        manageApps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        LinearLayout.LayoutParams manageAppsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageAppsLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(manageApps, manageAppsLp);

        Button manageNotifications = secondaryButton("Manage notification intelligence");
        manageNotifications.setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
        LinearLayout.LayoutParams manageNotificationsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageNotificationsLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(manageNotifications, manageNotificationsLp);
        // Added with the other Personalization & data sections below.

        TextView backupSection = sectionTitle("BACKUP & RESTORE", "data");
        LinearLayout backupCard = card();
        tagSectionCard(backupCard, "data");
        TextView backupHelp = UiKit.text(this,
                "Orbit backups stay in the file you choose. They include local chats, Memory, Routines, safe extension manifests, reminders, saved places and personalization. Sensitive account credentials are not included. Android permissions and default-assistant status are not included and may need to be granted again after reinstalling Orbit.",
                13, UiKit.MUTED, false);
        backupHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
        backupCard.addView(backupHelp);
        TextView backupPrivacy = UiKit.text(this,
                "Backup files contain personal data and are not encrypted, so keep them somewhere private.",
                12, UiKit.MUTED, false);
        backupPrivacy.setPadding(0, 0, 0, UiKit.dp(this, 12));
        backupCard.addView(backupPrivacy);

        Button exportBackup = secondaryButton("Export Orbit backup");
        exportBackup.setOnClickListener(v -> chooseBackupDestination());
        backupCard.addView(exportBackup, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button restoreBackup = secondaryButton("Restore Orbit backup");
        restoreBackup.setOnClickListener(v -> chooseBackupToRestore());
        LinearLayout.LayoutParams restoreBackupLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        restoreBackupLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        backupCard.addView(restoreBackup, restoreBackupLp);
        // Added last in Personalization & data below.

        page.addView(sectionTitle("CONNECTION & PROVIDER", "account"));
        LinearLayout accountCard = card();
        tagSectionCard(accountCard, "account");
        AiProvider activeProvider = AiProviders.active(this);
        LinearLayout providersRow = new LinearLayout(this);
        providersRow.setGravity(Gravity.CENTER_VERTICAL);
        providersRow.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        providersRow.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 16, this));
        providersRow.setClickable(true);
        providersRow.setFocusable(true);
        LinearLayout providersText = new LinearLayout(this);
        providersText.setOrientation(LinearLayout.VERTICAL);
        providersText.addView(UiKit.text(this, "AI Providers", 15, UiKit.TEXT, true));
        providerRowStatus = UiKit.text(this,
                "Active: " + activeProvider.displayName() + " · " + activeProvider.statusDetail(this),
                12, UiKit.MUTED, false);
        providerRowStatus.setPadding(0, UiKit.dp(this, 3), 0, 0);
        providersText.addView(providerRowStatus);
        providersRow.addView(providersText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView providersArrow = UiKit.text(this, "›", 24, UiKit.accent(this), false);
        providersArrow.setPadding(UiKit.dp(this, 10), 0, UiKit.dp(this, 4), 0);
        providersRow.addView(providersArrow);
        providersRow.setOnClickListener(v ->
                startActivity(new Intent(this, AiProvidersActivity.class)));
        UiKit.pressScale(providersRow);
        accountCard.addView(providersRow, selectorLp());
        providerDetails = new LinearLayout(this);
        providerDetails.setOrientation(LinearLayout.VERTICAL);
        providerDetails.setPadding(0, UiKit.dp(this, 13), 0, 0);
        populateProviderDetails(providerDetails);
        accountCard.addView(providerDetails);
        page.addView(accountCard);

        page.addView(sectionTitle("VOICE & CONTEXT", "voice"));
        LinearLayout voiceCard = card();
        tagSectionCard(voiceCard, "voice");
        Button micPermission = secondaryButton("Grant microphone permission");
        micPermission.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO));
        voiceCard.addView(micPermission, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        Button contactPermission = secondaryButton("Allow contact-name lookup");
        contactPermission.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_CONTACTS));
        LinearLayout.LayoutParams contactLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        contactLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        voiceCard.addView(contactPermission, contactLp);
        Button cameraPermission = secondaryButton("Allow flashlight control");
        cameraPermission.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA));
        LinearLayout.LayoutParams cameraLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        cameraLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        voiceCard.addView(cameraPermission, cameraLp);
        voiceCard.addView(toggle("Allow Orbit to read current-screen text", Prefs.SCREEN_CONTEXT, true));
        voiceCard.addView(toggle("Allow Orbit to receive screenshots",
                "Visual context, previews, and screen-region selection.",
                Prefs.SCREENSHOT, true));
        voiceCard.addView(toggle("Attach current screen by default", Prefs.ATTACH_SCREEN_BY_DEFAULT, false));
        voiceCard.addView(toggle("Show contextual screen-action chips when attached", Prefs.CONTEXT_CHIPS, true));
        voiceCard.addView(toggle("Speak replies to voice requests", Prefs.SPEAK, true));
        voiceCard.addView(toggle("Allow longer pauses while speaking",
                "Voice Beta gives you more time to pause and think before Orbit decides you are finished. Tap the mic again if you want to finish sooner.",
                Prefs.VOICE_PAUSE_FRIENDLY, true));
        voiceCard.addView(toggle("Start listening when overlay opens",
                "Automatically activate the microphone when the assistant overlay appears.",
                Prefs.AUTO_LISTEN_ON_OPEN, false));
        voiceCard.addView(toggle("Hands-free voice follow-ups",
                "Reopen the microphone after Orbit speaks.",
                Prefs.AUTO_LISTEN, false));
        voiceCard.addView(toggle("Smart follow-ups",
                "Only reopen the microphone when Orbit is waiting for your answer.",
                Prefs.SMART_FOLLOW_UPS, true));
        voiceCard.addView(toggle("Keyboard-aware assistant invocation", Prefs.KEYBOARD_AWARE_ASSISTANT, true));

        Button capabilities = secondaryButton("Permissions & capabilities");
        capabilities.setOnClickListener(v -> startActivity(new Intent(this, CapabilitiesActivity.class)));
        LinearLayout.LayoutParams capabilitiesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        capabilitiesLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        voiceCard.addView(capabilities, capabilitiesLp);
        page.addView(voiceCard);

        TextView weatherSection = sectionTitle("WEATHER", "data");
        LinearLayout weatherCard = card();
        tagSectionCard(weatherCard, "data");
        TextView weatherHelp = UiKit.text(this,
                "Orbit can answer current weather and forecasts directly in chat using Open-Meteo. You can set a default city or allow approximate device location. Weather questions do not need to open a browser.",
                13, UiKit.MUTED, false);
        weatherHelp.setPadding(0, 0, 0, UiKit.dp(this, 10));
        weatherCard.addView(weatherHelp);
        weatherCard.addView(weatherLocationToggle());
        weatherCard.addView(label("Default weather location (optional)"));
        EditText weatherLocation = field("Naples, Florida", Prefs.weatherLocation(this), false);
        weatherCard.addView(weatherLocation);
        weatherCard.addView(label("Weather units"));
        weatherCard.addView(weatherUnitsSelector(), selectorLp());
        Button saveWeather = secondaryButton("Save weather location");
        LinearLayout.LayoutParams weatherSaveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        weatherSaveLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        weatherCard.addView(saveWeather, weatherSaveLp);
        saveWeather.setOnClickListener(v -> {
            Prefs.get(this).edit().putString(Prefs.WEATHER_LOCATION, weatherLocation.getText().toString().trim()).apply();
            Toast.makeText(this, weatherLocation.getText().toString().trim().isEmpty() ? "Default weather location cleared" : "Weather location saved", Toast.LENGTH_SHORT).show();
        });
        TextView weatherNote = UiKit.text(this,
                "If device location is off, Orbit uses the saved city. If neither is available, it will ask for a city and remember the last city you use for weather.",
                12, UiKit.MUTED, false);
        weatherNote.setPadding(0, UiKit.dp(this, 8), 0, 0);
        weatherCard.addView(weatherNote);

        TextView gallerySection = sectionTitle("GALLERY", "data");
        LinearLayout galleryCard = card();
        tagSectionCard(galleryCard, "data");
        galleryCard.addView(label("Gallery app"));
        galleryCard.addView(galleryAppSelector(), selectorLp());
        TextView galleryHelp = UiKit.text(this,
                "Choose which compatible installed app Orbit uses when you attach an image. System picker remains the safe fallback if that app becomes unavailable.",
                12, UiKit.MUTED, false);
        galleryCard.addView(galleryHelp);

        page.addView(weatherSection);
        page.addView(weatherCard);
        page.addView(gallerySection);
        page.addView(galleryCard);
        page.addView(personalizationSection);
        page.addView(personalDataCard);
        page.addView(remindersSection);
        page.addView(remindersCard);
        page.addView(backupSection);
        page.addView(backupCard);

        page.addView(sectionTitle("INTELLIGENCE", "intelligence"));
        LinearLayout aiCard = card();
        tagSectionCard(aiCard, "intelligence");
        aiCard.addView(label("Default mode for new chats"));
        String[] modeLabels = {"Auto", "Fast", "Balanced", "Deep", "Custom"};
        String modeValue = Prefs.intelligenceMode(this);
        int modePos = Prefs.MODE_AUTO.equals(modeValue) ? 0 : Prefs.MODE_FAST.equals(modeValue) ? 1 :
                Prefs.MODE_DEEP.equals(modeValue) ? 3 : Prefs.MODE_CUSTOM.equals(modeValue) ? 4 : 2;
        LinearLayout mode = menuSelector(modeLabels, modePos, (pos, selectedLabel) -> {
            String value = pos == 0 ? Prefs.MODE_AUTO : pos == 1 ? Prefs.MODE_FAST :
                    pos == 3 ? Prefs.MODE_DEEP : pos == 4 ? Prefs.MODE_CUSTOM : Prefs.MODE_BALANCED;
            Prefs.get(this).edit().putString(Prefs.INTELLIGENCE_MODE, value).apply();
        });
        aiCard.addView(mode, selectorLp());
        TextView modeHelp = UiKit.text(this,
                "Fast favors Luna, Balanced favors Terra, and Deep favors Sol. Auto chooses per request. This is the default for new chats; changing the mode inside a conversation now stays with that chat.",
                12, UiKit.MUTED, false);
        modeHelp.setPadding(0, 0, 0, UiKit.dp(this, 10));
        aiCard.addView(modeHelp);

        aiCard.addView(label("Custom model"));
        String[] modelLabels = {"gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol"};
        int modelPos = indexOf(modelLabels, Prefs.model(this));
        LinearLayout model = menuSelector(modelLabels, modelPos,
                (pos, selectedLabel) -> Prefs.get(this).edit().putString(Prefs.MODEL, selectedLabel).apply());
        aiCard.addView(model, selectorLp());

        aiCard.addView(label("Custom reasoning"));
        String[] reasoningLabels = {"none", "low", "medium", "high", "xhigh", "max"};
        int rPos = indexOf(reasoningLabels, Prefs.reasoning(this));
        LinearLayout reasoning = menuSelector(reasoningLabels, rPos,
                (pos, selectedLabel) -> Prefs.get(this).edit().putString(Prefs.REASONING, selectedLabel).apply());
        aiCard.addView(reasoning, selectorLp());
        TextView cost = UiKit.text(this, "ChatGPT-account mode uses your account-backed allowance. Orbit never silently switches to the separately metered API-relay fallback.", 12, UiKit.MUTED, false);
        cost.setPadding(0, UiKit.dp(this, 8), 0, 0);
        aiCard.addView(cost);
        page.addView(aiCard);

        page.addView(sectionTitle("DIAGNOSTICS", "diagnostics"));
        LinearLayout diagnosticsCard = card();
        tagSectionCard(diagnosticsCard, "diagnostics");
        diagnosticsCard.addView(UiKit.text(this, "Orbit diagnostics", 16, UiKit.TEXT, true));
        TextView diagnosticsHelp = UiKit.text(this,
                "View local routing, context, account, capability, and troubleshooting information.",
                13, UiKit.MUTED, false);
        diagnosticsHelp.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        diagnosticsCard.addView(diagnosticsHelp);
        Button diagnostics = secondaryButton("Open Orbit diagnostics");
        diagnosticsCard.addView(diagnostics, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        page.addView(diagnosticsCard);

        page.addView(sectionTitle("CONVERSATIONS", "conversations"));
        LinearLayout conversationCard = card();
        tagSectionCard(conversationCard, "conversations");
        conversationCard.addView(toggle("Start a new chat each time Orbit opens", Prefs.NEW_CHAT_ON_OPEN, true));
        conversationCard.addView(toggle("Save recent chats on this device", Prefs.HISTORY_ENABLED, true));
        conversationCard.addView(toggle("Save screen attachment thumbnails in chat history", Prefs.SAVE_SCREEN_THUMBNAILS, false));
        conversationCard.addView(toggle("Show Stop button while replying",
                "Replace Send with Stop while Orbit is generating a reply.",
                Prefs.SHOW_STOP_BUTTON, true));
        conversationCard.addView(notificationToggle());
        TextView conversationNote = UiKit.text(this,
                "Orbit keeps up to 100 recent chats locally when history is enabled. The full Orbit app can search, rename, reopen, and delete them. Screen thumbnails are stored only in Orbit's private app storage when that option is enabled.",
                12, UiKit.MUTED, false);
        conversationNote.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 10));
        conversationCard.addView(conversationNote);
        Button clearHistory = secondaryButton("Clear Orbit conversation history");
        conversationCard.addView(clearHistory, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        clearHistory.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Clear Orbit history?")
                    .setMessage("This deletes Orbit's locally saved recent chats. It does not affect your ChatGPT account.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear", (d, which) -> {
                        ConversationStore.clear(this);
                        Toast.makeText(this, "Orbit conversation history cleared", Toast.LENGTH_SHORT).show();
                    })
                    .create();
            styleOrbitDialog(dialog, true);
            dialog.show();
        });
        page.addView(conversationCard);

        page.addView(sectionTitle("LOOK & FEEL", "appearance"));
        LinearLayout styleCard = card();
        tagSectionCard(styleCard, "appearance");
        styleCard.addView(label("Accent"));
        styleCard.addView(themeSelector());
        TextView note = UiKit.text(this,
                "Choose an Orbit accent from the menu. Dynamic follows your Samsung/Material system accent; Nova is #4C00FF.",
                12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        styleCard.addView(note);
        styleCard.addView(amoledToggle());

        TextView fontLabel = label("App font");
        fontLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 14), 0, UiKit.dp(this, 6));
        styleCard.addView(fontLabel);
        styleCard.addView(fontSelector());
        TextView fontNote = UiKit.text(this,
                "Orbit Default is the current app font. Times New Roman uses Android's built-in serif family for a similar classic look without adding a font file to Orbit.",
                12, UiKit.MUTED, false);
        fontNote.setPadding(0, 0, 0, UiKit.dp(this, 8));
        styleCard.addView(fontNote);

        styleCard.addView(toggle("Haptic feedback", Prefs.HAPTICS, true));
        TextView hapticNote = UiKit.text(this,
                "Uses light tactile ticks for Orbit controls and Settings interactions. Turn this off to disable those haptics.",
                12, UiKit.MUTED, false);
        hapticNote.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 1), 0, UiKit.dp(this, 6));
        styleCard.addView(hapticNote);

        TextView bubbleLabel = label("Conversation colors");
        bubbleLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 18), 0, UiKit.dp(this, 6));
        styleCard.addView(bubbleLabel);
        TextView bubbleNote = UiKit.text(this,
                "Choose your message-bubble color and Orbit's response-bubble color independently. Classic preserves the original Orbit look; Accent follows the current app accent, while colors such as Nova can be chosen independently.",
                12, UiKit.MUTED, false);
        bubbleNote.setPadding(0, 0, 0, UiKit.dp(this, 10));
        styleCard.addView(bubbleNote);

        styleCard.addView(label("Your bubbles"));
        styleCard.addView(bubbleColorSelector(Prefs.USER_BUBBLE_COLOR, false));
        styleCard.addView(label("Orbit bubbles"));
        styleCard.addView(bubbleColorSelector(Prefs.ASSISTANT_BUBBLE_COLOR, true));
        TextView chatSizeLabel = label("Chat text size");
        chatSizeLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 12), 0, UiKit.dp(this, 6));
        styleCard.addView(chatSizeLabel);
        styleCard.addView(chatTextSizeSelector());
        TextView chatSizeNote = UiKit.text(this,
                "Changes conversation content only, including rich Markdown in full chat and the Side-button assistant.",
                12, UiKit.MUTED, false);
        styleCard.addView(chatSizeNote);

        TextView advancedLabel = label("Advanced");
        advancedLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 20), 0, UiKit.dp(this, 6));
        styleCard.addView(advancedLabel);
        styleCard.addView(label("Page transitions"));
        styleCard.addView(pageTransitionSelector());
        TextView transitionNote = UiKit.text(this,
                "How full-screen Orbit pages move when you open and leave them. Slide brings a page in from the right and sends it back out on the way back. Fade & settle uses a short fade with a small upward settle. None changes pages immediately. Android's own animation settings still apply.",
                12, UiKit.MUTED, false);
        transitionNote.setPadding(0, UiKit.dp(this, 8), 0, 0);
        styleCard.addView(transitionNote);

        page.addView(styleCard);

        TextView footer = UiKit.text(this, "Orbit " + BuildConfig.VERSION_NAME + " • Power Assistant", 12, UiKit.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setOnClickListener(v -> handleLeloSecretTap());
        footer.setOnLongClickListener(v -> {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        });
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerLp.setMargins(0, UiKit.dp(this, 28), 0, 0);
        page.addView(footer, footerLp);
        applySettingsSectionFilter(page, settingsSection);
        return scroll;
    }

    private void startChatGptLogin() {
        if (chatGptStatus != null) {
            chatGptStatus.setText("Starting secure ChatGPT sign-in…");
            chatGptStatus.setTextColor(UiKit.TEXT);
        }
        ChatGptAuth.requestDeviceCode(this, new ChatGptAuth.StartCallback() {
            @Override public void onSuccess(ChatGptAuth.DeviceCode code) {
                runOnUiThread(() -> {
                    if (canShowAuthResult()) showDeviceCode(code);
                });
                ChatGptAuth.completeDeviceCode(
                        SettingsActivity.this, code, chatGptLoginCallback);
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    if (!canShowAuthResult()) return;
                    updateChatGptStatus();
                    showOrbitMessageDialog("Could not start ChatGPT sign-in", message);
                });
            }
        });
    }

    private boolean canShowAuthResult() {
        return !isFinishing() && !isDestroyed();
    }

    private void showDeviceCode(ChatGptAuth.DeviceCode code) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Orbit ChatGPT code", code.userCode));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Sign in with ChatGPT")
                .setMessage("Your one-time code is:\n\n" + code.userCode +
                        "\n\nIt has been copied to your clipboard. Open OpenAI's sign-in page, sign into your normal ChatGPT account, and enter this code. Orbit will finish connecting automatically.")
                .setPositiveButton("Open ChatGPT sign-in", (d, which) -> {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl))); }
                    catch (Exception e) { Toast.makeText(this, code.verificationUrl, Toast.LENGTH_LONG).show(); }
                })
                .setNeutralButton("Copy code", (d, which) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Orbit ChatGPT code", code.userCode));
                    Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private void showOrbitMessageDialog(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private void styleOrbitDialog(AlertDialog dialog, boolean destructivePositive) {
        UiKit.styleOrbitDialog(dialog, this, destructivePositive);
    }

    private void tintDialogText(View view) {
        if (view == null) return;
        if (view instanceof TextView && !(view instanceof Button)) {
            ((TextView) view).setTextColor(UiKit.TEXT);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintDialogText(group.getChildAt(i));
            }
        }
    }

    private void updateChatGptStatus() {
        if (chatGptStatus == null) return;
        ChatGptAuth.AccountInfo info = ChatGptAuth.getAccountInfo(this);
        if (info == null) {
            chatGptStatus.setText("○ Not signed in with ChatGPT");
            chatGptStatus.setTextColor(UiKit.TEXT);
            return;
        }
        StringBuilder status = new StringBuilder("✓ Connected to ChatGPT");
        if (!info.plan.isEmpty()) status.append(" • ").append(info.plan);
        if (!info.email.isEmpty()) status.append("\n").append(info.email);
        chatGptStatus.setText(status.toString());
        chatGptStatus.setTextColor(UiKit.SUCCESS);
    }

    private void populateProviderDetails(LinearLayout container) {
        if (container == null) return;
        chatGptStatus = null;
        providerDetailsFor = Prefs.provider(this);
        if (Prefs.PROVIDER_LOCAL.equals(providerDetailsFor)) {
            TextView localHelp = UiKit.text(this,
                    "Orbit Local answers on this phone with no account and no internet. Your ChatGPT sign-in, if present, stays securely saved but inactive while Orbit Local is selected.",
                    13, UiKit.MUTED, false);
            localHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
            container.addView(localHelp);
            Button manageLocal = secondaryButton("Manage Orbit Local");
            manageLocal.setOnClickListener(v ->
                    startActivity(new Intent(this, LocalAiActivity.class)));
            container.addView(manageLocal, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
            return;
        }
        if (Prefs.PROVIDER_RELAY.equals(providerDetailsFor)) {
            TextView relayHelp = UiKit.text(this,
                    "Advanced fallback: Orbit calls a private HTTPS relay you control. Your OpenAI API key stays on that server and is never stored in Orbit. ChatGPT credentials, if present, remain securely saved but inactive while this provider is selected.",
                    13, UiKit.MUTED, false);
            relayHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
            container.addView(relayHelp);

            container.addView(label("HTTPS relay URL"));
            EditText url = field("https://your-relay.example.com", Prefs.backendUrl(this), false);
            container.addView(url);
            container.addView(label("Relay access token (optional)"));
            EditText token = field("Relay access token (optional)", Prefs.token(this), true);
            container.addView(token);

            Button save = primaryButton("Save relay settings");
            LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
            saveLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
            container.addView(save, saveLp);
            save.setOnClickListener(v -> {
                try {
                    String error = Prefs.saveRelaySettings(this, url.getText().toString(),
                            token.getText().toString());
                    if (!error.isEmpty()) {
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(this, "Relay settings saved securely", Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    showOrbitMessageDialog("Could not save relay settings",
                            "Orbit could not securely save the optional relay access token.");
                }
            });

            TextView future = UiKit.text(this,
                    "More provider options, including Claude and Gemini, are planned for future Orbit versions.",
                    11, UiKit.MUTED, false);
            future.setPadding(0, UiKit.dp(this, 10), 0, 0);
            container.addView(future);
            return;
        }

        chatGptStatus = UiKit.text(this, "Checking ChatGPT sign-in…", 15, UiKit.TEXT, true);
        container.addView(chatGptStatus);
        TextView accountHelp = UiKit.text(this,
                "Recommended: sign in with your normal ChatGPT account using OpenAI's Codex device-code flow. This path uses your account-backed Codex/ChatGPT allowance and does not require an OpenAI API key.",
                13, UiKit.MUTED, false);
        accountHelp.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        container.addView(accountHelp);

        Button signIn = primaryButton("Sign in with ChatGPT");
        signIn.setOnClickListener(v -> startChatGptLogin());
        container.addView(signIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button signOut = secondaryButton("Sign out of ChatGPT");
        LinearLayout.LayoutParams signOutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        signOutLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        container.addView(signOut, signOutLp);
        signOut.setOnClickListener(v -> {
            ChatGptAuth.logout(this);
            updateChatGptStatus();
            Toast.makeText(this, "Signed out of ChatGPT in Orbit", Toast.LENGTH_SHORT).show();
        });

        TextView experimental = UiKit.text(this,
                "Orbit uses the public Codex OAuth protocol for ChatGPT-account mode. Because OpenAI does not document the Codex backend as a general third-party mobile API, this integration is experimental and the API relay remains available as an explicit fallback.",
                12, UiKit.MUTED, false);
        experimental.setPadding(0, UiKit.dp(this, 8), 0, 0);
        container.addView(experimental);
        updateChatGptStatus();
    }

    /** Reflects a provider switch made in AI Providers when the user returns here. */
    private void refreshProviderSection() {
        if (providerRowStatus != null) {
            AiProvider active = AiProviders.active(this);
            providerRowStatus.setText(
                    "Active: " + active.displayName() + " · " + active.statusDetail(this));
        }
        if (providerDetails != null && providerDetailsFor != null
                && !providerDetailsFor.equals(Prefs.provider(this))) {
            swapProviderDetails();
        }
    }

    private void swapProviderDetails() {
        if (providerDetails == null) return;
        providerDetails.animate().cancel();
        providerDetails.animate()
                .alpha(0f)
                .translationY(-UiKit.dp(this, 3))
                .setDuration(85L)
                .withEndAction(() -> {
                    if (providerDetails == null || isFinishing() || isDestroyed()) return;
                    providerDetails.removeAllViews();
                    populateProviderDetails(providerDetails);
                    UiKit.applyTypography(providerDetails);
                    providerDetails.setTranslationY(UiKit.dp(this, 4));
                    providerDetails.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(150L)
                            .start();
                })
                .start();
    }

    private void openAssistantSettings(boolean explain) {
        if (!OrbitSetupHelper.openAssistantSettings(this, REQ_ASSISTANT_SETTINGS, explain))
            showOrbitMessageDialog("Assistant settings unavailable",
                    "Android could not open a supported default-assistant settings screen on this device.");
    }

    private void refreshQuickRoutineSelection() {
        if (quickRoutineSelection == null) return;
        RoutineStore.Routine assigned = QuickSettingsTiles.assignedRoutine(this);
        quickRoutineSelection.setText("Quick Settings Routine: " +
                (assigned == null ? "None" : assigned.name));
    }

    private void showQuickRoutineChooser() {
        List<RoutineStore.Routine> routines = RoutineStore.list(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        addQuickRoutineChoice(choices, "None", "");
        for (RoutineStore.Routine routine : routines) {
            addQuickRoutineChoice(choices, routine.name, routine.id);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 8));
        scroll.addView(choices, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Quick Settings Routine")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        for (int i = 0; i < choices.getChildCount(); i++) {
            choices.getChildAt(i).setTag(dialog);
        }
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private void addQuickRoutineChoice(LinearLayout choices, String label, String routineId) {
        String selectedId = Prefs.quickSettingsRoutineId(this);
        boolean selected = selectedId.equals(routineId);
        TextView row = UiKit.text(this, (selected ? "●  " : "○  ") + label,
                14, selected ? UiKit.accent(this) : UiKit.TEXT, selected);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 13), 0, UiKit.dp(this, 13), 0);
        row.setBackground(UiKit.ripple(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.16f) : UiKit.SURFACE_2,
                UiKit.accent(this), 13, this));
        row.setOnClickListener(v -> {
            if (Prefs.setQuickSettingsRoutineId(this, routineId)) {
                QuickSettingsTiles.refreshRoutineTile(this);
                refreshQuickRoutineSelection();
                Object tag = v.getTag();
                if (tag instanceof AlertDialog) ((AlertDialog) tag).dismiss();
            }
        });
        UiKit.pressScale(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        if (choices.getChildCount() > 0) lp.topMargin = UiKit.dp(this, 3);
        choices.addView(row, lp);
    }

    private void chooseBackupDestination() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, OrbitBackupManager.defaultFileName());
        startActivityForResult(intent, REQ_EXPORT_BACKUP);
    }

    private void chooseBackupToRestore() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_RESTORE_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ASSISTANT_SETTINGS) {
            getWindow().getDecorView().postDelayed(this::updateAssistantStatus, 200L);
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_BACKUP) {
            new Thread(() -> {
                try {
                    OrbitBackupManager.exportTo(getApplicationContext(), uri);
                    runOnUiThread(() -> Toast.makeText(this, "Orbit backup saved", Toast.LENGTH_LONG).show());
                } catch (Exception e) {
                    runOnUiThread(() -> showBackupError("Could not export backup", e));
                }
            }, "orbit-backup-export").start();
        } else if (requestCode == REQ_RESTORE_BACKUP) {
            new Thread(() -> {
                try {
                    OrbitBackupManager.PreparedRestore prepared =
                            OrbitBackupManager.prepareRestore(getApplicationContext(), uri);
                    runOnUiThread(() -> confirmRestore(prepared));
                } catch (Exception e) {
                    runOnUiThread(() -> showBackupError("Could not open backup", e));
                }
            }, "orbit-backup-validate").start();
        }
    }

    private void confirmRestore(OrbitBackupManager.PreparedRestore prepared) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Restore Orbit backup?")
                .setMessage(prepared.confirmationMessage())
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, which) -> runRestore(prepared))
                .create();
        styleOrbitDialog(dialog, true);
        dialog.show();
    }

    private void runRestore(OrbitBackupManager.PreparedRestore prepared) {
        Toast.makeText(this, "Restoring Orbit backup…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                OrbitBackupManager.restore(getApplicationContext(), prepared);
                runOnUiThread(() -> {
                    UiKit.syncTheme(this);
                    Toast.makeText(this,
                            "Orbit backup restored. Review Android permissions and default-assistant status.",
                            Toast.LENGTH_LONG).show();
                    recreate();
                });
            } catch (Exception e) {
                runOnUiThread(() -> showBackupError("Could not restore backup", e));
            }
        }, "orbit-backup-restore").start();
    }

    private void showBackupError(String title, Exception error) {
        String message = error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()
                ? "Orbit could not complete this backup operation." : error.getMessage();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .create();
        styleOrbitDialog(dialog, false);
        dialog.show();
    }

    private void updateAssistantStatus() {
        if (assistantStatus == null) return;
        boolean active = OrbitSetupHelper.isOrbitAssistantActive(this);
        assistantStatus.setText(active ? "✓ Orbit is your default assistant" : "○ Orbit is not the active assistant yet");
        assistantStatus.setTextColor(active ? UiKit.SUCCESS : UiKit.TEXT);
        if (assistantAction != null) {
            assistantAction.setText(active ? "Manage default assistant" : "Make Orbit default assistant");
            assistantAction.setEnabled(true);
            assistantAction.setAlpha(1f);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Prefs.get(this).edit().putBoolean(Prefs.WEATHER_USE_DEVICE_LOCATION, granted).apply();
            Toast.makeText(this, granted ? "Approximate location enabled for weather" : "Location permission not granted", Toast.LENGTH_SHORT).show();
            View content = buildContent();
            setContentView(content);
            UiKit.applyActivityInsets(this, content, true);
            updateAssistantStatus();
            updateChatGptStatus();
            return;
        }
        if (requestCode == REQ_NOTIFICATIONS) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Prefs.get(this).edit().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, granted).apply();
            if (granted) NotificationHelper.ensureChannel(this);
            Toast.makeText(this, granted ? "Background response notifications enabled" : "Notification permission not granted", Toast.LENGTH_SHORT).show();
            // Rebuild so the checkbox reflects the permission result without leaving Settings.
            View content = buildContent();
            setContentView(content);
            UiKit.applyActivityInsets(this, content, true);
            updateAssistantStatus();
            updateChatGptStatus();
        }
    }

    private TextView sectionTitle(String s, String key) {
        TextView t = UiKit.text(this, s, 12, UiKit.MUTED, true);
        t.setLetterSpacing(0.13f);
        t.setTag(TAG_SECTION_PREFIX + key);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 22), 0, UiKit.dp(this, 9));
        t.setLayoutParams(lp);
        return t;
    }

    private void tagSectionCard(View view, String key) {
        if (view != null) view.setTag(TAG_CARD + ":" + key);
    }

    private void applySettingsSectionFilter(LinearLayout page, String section) {
        if (page == null || section == null || section.isEmpty()) return;
        for (int i = 0; i < page.getChildCount(); i++) {
            View child = page.getChildAt(i);
            Object rawTag = child.getTag();
            if (!(rawTag instanceof String)) continue;
            String tag = (String) rawTag;
            String key = "";
            if (tag.startsWith(TAG_SECTION_PREFIX)) key = tag.substring(TAG_SECTION_PREFIX.length());
            else if (tag.startsWith(TAG_CARD + ":")) key = tag.substring((TAG_CARD + ":").length());
            if (!key.isEmpty()) child.setVisibility(sectionAllows(section, key) ? View.VISIBLE : View.GONE);
        }
    }

    private boolean sectionAllows(String section, String key) {
        if (SECTION_ASSISTANT.equals(section)) return "setup".equals(key);
        if (SECTION_AI.equals(section)) return "account".equals(key) || "intelligence".equals(key);
        if (SECTION_VOICE.equals(section)) return "voice".equals(key);
        if (SECTION_DATA.equals(section)) return "data".equals(key);
        if (SECTION_CONVERSATIONS.equals(section)) return "conversations".equals(key);
        if (SECTION_APPEARANCE.equals(section)) return "appearance".equals(key);
        if (SECTION_ADVANCED.equals(section)) return "diagnostics".equals(key);
        return false;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 18), UiKit.dp(this, 18), UiKit.dp(this, 18));
        c.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 38), 24, this));
        c.setTag(TAG_CARD);
        c.setElevation(UiKit.dp(this, 2));
        return c;
    }

    private TextView label(String s) {
        TextView t = UiKit.text(this, s, 13, UiKit.MUTED, true);
        t.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 4), 0, UiKit.dp(this, 6));
        return t;
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
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), UiKit.accent(this), 15, this));
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private View toggle(String label, String key, boolean def) {
        return toggle(label, null, key, def);
    }

    private View toggle(String label, String description, String key, boolean def) {
        OrbitSwitch control = new OrbitSwitch(this);
        control.setChecked(Prefs.get(this).getBoolean(key, def), false);
        // The confirmation tick lives in OrbitSwitch now, so every switch in the app feels the
        // same and one tap can only produce one tick.
        control.setOnCheckedChangeListener((button, checked) ->
                Prefs.get(this).edit().putBoolean(key, checked).apply());
        return UiKit.switchRow(this, label, description, control);
    }

    private View weatherLocationToggle() {
        OrbitSwitch control = new OrbitSwitch(this);
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        control.setChecked(Prefs.weatherUseDeviceLocation(this) && granted, false);
        control.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !granted) {
                // setChecked never re-enters this listener, so the rollback is a plain correction.
                button.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.WEATHER_USE_DEVICE_LOCATION, checked).apply();
        });
        return UiKit.switchRow(this, "Use approximate device location for local weather", null, control);
    }

    private View amoledToggle() {
        OrbitSwitch control = new OrbitSwitch(this);
        control.setChecked(Prefs.amoledMode(this), false);
        control.setOnCheckedChangeListener((button, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.AMOLED_MODE, checked).apply();
            UiKit.notifyAppearanceChanged(this);
        });
        return UiKit.switchRow(this, "Use true black AMOLED backgrounds", null, control);
    }

    private View notificationToggle() {
        OrbitSwitch control = new OrbitSwitch(this);
        control.setChecked(Prefs.backgroundNotifications(this), false);
        control.setOnCheckedChangeListener((button, checked) -> {
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Prefs.get(this).edit().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).apply();
                button.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, checked).apply();
            if (checked) NotificationHelper.ensureChannel(this);
        });
        return UiKit.switchRow(this, "Notify me when a background response finishes", null, control);
    }

    private void performSettingsHaptic(View view) {
        if (view == null || !Prefs.haptics(this)) return;
        try { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        catch (Exception ignored) {}
    }

    private View themeSelector() {
        String[] keys = UiKit.accentKeys();
        String[] labels = UiKit.accentLabels();
        String selected = Prefs.get(this).getString(Prefs.ACCENT, "dynamic");
        return colorMenuSelector(keys, labels, selected, false, true, Prefs.ACCENT);
    }

    private View fontSelector() {
        String[] keys = new String[]{"orbit_default", "times_new_roman", "light", "condensed", "monospace", "casual"};
        String[] labels = new String[]{"Orbit Default", "Times New Roman", "Light", "Condensed", "Monospace", "Casual"};
        String selected = Prefs.appFont(this);
        int selectedIndex = indexOf(keys, selected);
        LinearLayout selector = fontMenuSelector(keys, labels, selectedIndex, (position, label) -> {
            String key = keys[Math.max(0, Math.min(keys.length - 1, position))];
            if (!key.equals(Prefs.appFont(this))) {
                Prefs.get(this).edit().putString(Prefs.APP_FONT, key).apply();
                UiKit.notifyAppearanceChanged(this);
            }
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View bubbleColorSelector(String prefKey, boolean assistant) {
        String[] keys = UiKit.bubbleColorKeys();
        String[] labels = UiKit.bubbleColorLabels();
        String selected = Prefs.get(this).getString(prefKey, "classic");
        return colorMenuSelector(keys, labels, selected, assistant, false, prefKey);
    }

    private View pageTransitionSelector() {
        String[] keys = new String[]{Prefs.PAGE_TRANSITION_SLIDE, Prefs.PAGE_TRANSITION_FADE,
                Prefs.PAGE_TRANSITION_NONE};
        String[] labels = new String[]{"Slide", "Fade & settle", "None"};
        int selected = indexOf(keys, Prefs.pageTransition(this));
        LinearLayout selector = menuSelector(labels, selected, (position, label) -> {
            String key = keys[Math.max(0, Math.min(keys.length - 1, position))];
            Prefs.get(this).edit().putString(Prefs.PAGE_TRANSITION, key).apply();
            // Retarget this window straight away so leaving Settings already uses the new style.
            // Nothing on the page is rebuilt, so there is no flash and no scroll movement.
            UiKit.applyPageTransition(this);
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View chatTextSizeSelector() {
        String[] keys = new String[]{
                Prefs.CHAT_TEXT_SMALL, Prefs.CHAT_TEXT_DEFAULT,
                Prefs.CHAT_TEXT_LARGE, Prefs.CHAT_TEXT_EXTRA_LARGE};
        String[] labels = new String[]{"Small", "Default", "Large", "Extra large"};
        int selected = indexOf(keys, Prefs.chatTextSize(this));
        LinearLayout selector = menuSelector(labels, selected, (position, label) -> {
            String key = keys[Math.max(0, Math.min(keys.length - 1, position))];
            Prefs.get(this).edit().putString(Prefs.CHAT_TEXT_SIZE, key).apply();
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View weatherUnitsSelector() {
        String[] keys = new String[]{Prefs.WEATHER_UNITS_SYSTEM,
                Prefs.WEATHER_UNITS_FAHRENHEIT, Prefs.WEATHER_UNITS_CELSIUS};
        String[] labels = new String[]{"System default", "Fahrenheit (°F)", "Celsius (°C)"};
        int selected = indexOf(keys, Prefs.weatherUnits(this));
        LinearLayout selector = menuSelector(labels, selected, (position, label) -> {
            String key = keys[Math.max(0, Math.min(keys.length - 1, position))];
            Prefs.get(this).edit().putString(Prefs.WEATHER_UNITS, key).apply();
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View galleryAppSelector() {
        List<GalleryAppPreference.Option> options = GalleryAppPreference.options(this);
        String preferred = GalleryAppPreference.storedPackage(this);
        String[] labels = new String[options.size()];
        int selected = 0;
        for (int i = 0; i < options.size(); i++) {
            GalleryAppPreference.Option option = options.get(i);
            labels[i] = option.label;
            if (option.packageName.equals(preferred)) selected = i;
        }
        LinearLayout selector = menuSelector(labels, selected, (position, label) -> {
            int safe = Math.max(0, Math.min(options.size() - 1, position));
            // Saves the exact Activity and action discovered here, so every surface can launch it
            // later without resolving anything again.
            GalleryAppPreference.setPreferredOption(this, options.get(safe));
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View colorMenuSelector(String[] keys, String[] labels, String selectedKey,
                                   boolean assistant, boolean accentSelector, String prefKey) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 16, this));

        int initialIndex = indexOf(keys, selectedKey);
        final int[] current = {initialIndex};

        TextView value = UiKit.text(this, labels[initialIndex], 15, UiKit.TEXT, false);
        value.setSingleLine(true);
        field.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = UiKit.text(this, "▾", 18, UiKit.MUTED, true);
        arrow.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        field.addView(arrow);

        field.setOnClickListener(v -> {
            int[] colors = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                if (accentSelector) {
                    colors[i] = UiKit.accentForName(this, keys[i]);
                } else {
                    colors[i] = bubblePreviewColor(keys[i], assistant);
                }
            }
            UiKit.showOrbitColorMenu(this, field, labels, colors, current[0], (index, label) -> {
                current[0] = index;
                value.setText(label);
                String key = keys[index];
                String existing = Prefs.get(this).getString(prefKey, accentSelector ? "dynamic" : "classic");
                if (!key.equals(existing)) {
                    Prefs.get(this).edit().putString(prefKey, key).apply();
                    UiKit.notifyAppearanceChanged(this);
                }
            });
        });
        UiKit.pressScale(field);
        field.setLayoutParams(selectorLp());
        return field;
    }

    private int bubblePreviewColor(String key, boolean assistant) {
        int classic = assistant ? UiKit.SURFACE : UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.46f);
        if ("classic".equals(key)) return classic;
        if ("accent".equals(key)) return UiKit.accent(this);
        return UiKit.accentForName(this, key);
    }

    private void handleLeloSecretTap() {
        long now = System.currentTimeMillis();
        if (now - lastLeloTapMs > 2200L) leloTapCount = 0;
        lastLeloTapMs = now;
        leloTapCount++;
        if (leloTapCount >= 7) {
            leloTapCount = 0;
            boolean enabled = !Prefs.leloMode(this);
            Prefs.get(this).edit().putBoolean(Prefs.LELO_MODE, enabled).apply();
            Toast.makeText(this, enabled ? "Lelo mode unlocked ✨" : "Lelo mode hidden again", Toast.LENGTH_SHORT).show();
        }
    }

    private void applyFontInPlace() {
        View rootView = getWindow().getDecorView().findViewById(android.R.id.content);
        if (rootView != null) UiKit.applyTypography(rootView);
    }

    @Override
    public void onOrbitAppearanceChanged() {
        applyAppearanceChange();
    }

    /**
     * Applies an appearance change to the screen already on display.
     *
     * <p>Only accent and AMOLED are baked into built views, so only those rebuild. App font is
     * re-applied to the existing hierarchy, and conversation bubble colours change nothing that
     * Settings draws — their selectors already update their own labels. Replacing the content view
     * for those was what briefly emptied the content frame and flashed the window through.
     */
    private void applyAppearanceChange() {
        if (rebuildingAppearance) return;
        if (refreshAppearanceIfNeeded()) return;
        String desired = UiKit.appearanceSignature(this);
        if (desired.equals(appliedAppearance)) return;
        // Font or bubble colours changed: update in place, no rebuild and no flash.
        UiKit.syncTheme(this);
        applyFontInPlace();
        appliedAppearance = desired;
    }

    /** Rebuilds only for appearance the built views cannot pick up any other way. */
    private boolean refreshAppearanceIfNeeded() {
        String desiredStructural = UiKit.structuralAppearanceSignature(this);
        if (rebuildingAppearance || desiredStructural.equals(appliedStructuralAppearance)) return false;
        rebuildingAppearance = true;
        final int oldScrollY = settingsScroll == null ? 0 : settingsScroll.getScrollY();
        try {
            UiKit.syncTheme(this);
            Window window = getWindow();
            window.setStatusBarColor(UiKit.BG);
            window.setNavigationBarColor(UiKit.BG);
            window.setBackgroundDrawable(new ColorDrawable(UiKit.BG));
            View content = buildContent();
            setContentView(content);
            UiKit.applyActivityInsets(this, content, true);
            appliedAppearance = UiKit.appearanceSignature(this);
            appliedStructuralAppearance = desiredStructural;
            updateAssistantStatus();
            updateChatGptStatus();
            restoreScrollBeforeFirstDraw(oldScrollY);
            return true;
        } finally {
            rebuildingAppearance = false;
        }
    }

    /**
     * Puts the rebuilt page back at its previous offset before it is ever drawn, so the new
     * hierarchy never appears at the top and then jumps.
     */
    private void restoreScrollBeforeFirstDraw(int scrollY) {
        final ScrollView target = settingsScroll;
        if (target == null || scrollY <= 0) return;
        target.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        ViewTreeObserver observer = target.getViewTreeObserver();
                        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                        int maximum = Math.max(0, (target.getChildCount() == 0 ? 0
                                : target.getChildAt(0).getHeight()) - target.getHeight());
                        target.scrollTo(0, Math.min(scrollY, maximum));
                        return true;
                    }
                });
    }

    private EditText field(String hint, String value, boolean secret) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(115,120,135));
        e.setText(value);
        e.setTextColor(UiKit.TEXT);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        e.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), 15, this));
        if (secret) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));
        return e;
    }

    private interface MenuSelectionCallback {
        void selected(int position, String label);
    }

    private LinearLayout fontMenuSelector(String[] keys, String[] labels, int selectedIndex,
                                          MenuSelectionCallback callback) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 16, this));

        int safeIndex = Math.max(0, Math.min(labels.length - 1, selectedIndex));
        final int[] current = {safeIndex};

        TextView value = UiKit.text(this, labels[safeIndex], 15, UiKit.TEXT, false);
        value.setMaxLines(2);
        UiKit.applyFontPreview(value, keys[safeIndex], Typeface.NORMAL);
        field.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = UiKit.text(this, "▾", 18, UiKit.MUTED, true);
        arrow.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        field.addView(arrow);

        field.setOnClickListener(v -> UiKit.showOrbitFontMenu(this, field, keys, labels,
                current[0], (index, label) -> {
                    current[0] = index;
                    value.setText(label);
                    UiKit.applyFontPreview(value, keys[index], Typeface.NORMAL);
                    if (callback != null) callback.selected(index, label);
                }));
        UiKit.pressScale(field);
        return field;
    }

    private LinearLayout menuSelector(String[] labels, int selectedIndex,
                                      MenuSelectionCallback callback) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 16, this));

        int safeIndex = Math.max(0, Math.min(labels.length - 1, selectedIndex));
        final int[] current = {safeIndex};

        TextView value = UiKit.text(this, labels[safeIndex], 15, UiKit.TEXT, false);
        value.setMaxLines(2);
        field.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView arrow = UiKit.text(this, "▾", 18, UiKit.MUTED, true);
        arrow.setPadding(UiKit.dp(this, 12), 0, 0, 0);
        field.addView(arrow);

        field.setOnClickListener(v -> UiKit.showOrbitMenu(this, field, labels,
                current[0], (index, label) -> {
                    current[0] = index;
                    value.setText(label);
                    if (callback != null) callback.selected(index, label);
                }));
        UiKit.pressScale(field);
        return field;
    }

    private int indexOf(String[] values, String target) {
        if (values == null || values.length == 0) return 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(target)) return i;
        }
        return 0;
    }

    private LinearLayout.LayoutParams selectorLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 54));
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }
}

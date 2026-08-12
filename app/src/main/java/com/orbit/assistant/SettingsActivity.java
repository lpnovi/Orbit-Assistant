package com.orbit.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;


public class SettingsActivity extends Activity {
    private static final int REQ_ASSISTANT = 70;
    private static final int REQ_AUDIO = 71;
    private static final int REQ_CAMERA = 72;
    private static final int REQ_CONTACTS = 73;
    private static final int REQ_NOTIFICATIONS = 74;
    private static final int REQ_LOCATION = 75;
    private static final int REQ_EXPORT_BACKUP = 76;
    private static final int REQ_RESTORE_BACKUP = 77;
    private static final String TAG_CARD = "orbit_card";
    private static final String TAG_PRIMARY = "orbit_primary";
    private static final String TAG_SECONDARY = "orbit_secondary";
    private static final String TAG_ACCENT_TEXT = "orbit_accent_text";
    private static final String TAG_ACCENT_ICON = "orbit_accent_icon";
    private static final String TAG_THEME_PREFIX = "orbit_theme:";
    private static final String TAG_BUBBLE_PREFIX = "orbit_bubble:";
    private static final String TAG_SECTION_PREFIX = "orbit_settings_section:";
    private static final String EXTRA_SECTION = "settings_section";
    private static final String SECTION_ASSISTANT = "assistant";
    private static final String SECTION_AI = "ai";
    private static final String SECTION_VOICE = "voice";
    private static final String SECTION_DATA = "data";
    private static final String SECTION_CONVERSATIONS = "conversations";
    private static final String SECTION_ROUTINES = "routines";
    private static final String SECTION_APPEARANCE = "appearance";
    private static final String SECTION_ADVANCED = "advanced";
    private TextView assistantStatus;
    private TextView chatGptStatus;
    private int leloTapCount = 0;
    private long lastLeloTapMs = 0L;
    private String settingsSection = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        settingsSection = normalizeSection(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECTION));
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        // The Settings hub remains underneath a detail page. Refresh typography
        // every time it resumes so a font changed in Look & Feel is visible on
        // the parent Settings page immediately after pressing Back.
        applyFontInPlace();
        updateAssistantStatus();
        updateChatGptStatus();
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
                "Default assistant, Side button and core device access"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_AI, "AI & account",
                "ChatGPT sign-in, provider and intelligence modes"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_VOICE, "Voice, context & permissions",
                "Voice Beta, screen context, weather and capabilities"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_DATA, "Personalization & data",
                "Backup, reminders, saved places, Memory, app profiles and notification intelligence"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_ROUTINES, "Routines",
                "Create, edit and run saved Action Engine chains"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_CONVERSATIONS, "Conversations",
                "History, chat behavior and background notifications"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_APPEARANCE, "Look & Feel",
                "Accent, font, AMOLED, conversation colors and haptics"), categoryLp());
        page.addView(settingsCategoryCard(SECTION_ADVANCED, "Advanced",
                "API relay fallback and diagnostics"), categoryLp());

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
        if (SECTION_ASSISTANT.equals(section)) return "Set Orbit as your assistant and configure Samsung's Side button behavior.";
        if (SECTION_AI.equals(section)) return "Manage your ChatGPT connection and how much intelligence Orbit uses by default.";
        if (SECTION_VOICE.equals(section)) return "Control Voice Beta, screen awareness, weather context and device permissions.";
        if (SECTION_DATA.equals(section)) return "Manage the local information and tools Orbit uses to personalize and organize your assistant experience.";
        if (SECTION_CONVERSATIONS.equals(section)) return "Choose how Orbit stores chats and handles background completions.";
        if (SECTION_APPEARANCE.equals(section)) return "Tune Orbit's colors, typography, AMOLED presentation and tactile feedback.";
        if (SECTION_ADVANCED.equals(section)) return "Optional fallback infrastructure and developer diagnostics.";
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
        back.setTag(TAG_ACCENT_ICON);
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
        Button makeDefault = primaryButton("Make Orbit default assistant");
        makeDefault.setOnClickListener(v -> requestAssistantRole());
        setupCard.addView(makeDefault);
        Button samsungSettings = secondaryButton("Open default-app settings");
        LinearLayout.LayoutParams secondLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        secondLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        setupCard.addView(samsungSettings, secondLp);
        samsungSettings.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        page.addView(setupCard);

        page.addView(sectionTitle("REMINDERS", "data"));
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
        page.addView(remindersCard);

        page.addView(sectionTitle("PERSONALIZATION & CONTEXT", "data"));
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
        page.addView(personalDataCard);

        page.addView(sectionTitle("BACKUP & RESTORE", "data"));
        LinearLayout backupCard = card();
        tagSectionCard(backupCard, "data");
        TextView backupHelp = UiKit.text(this,
                "Orbit backups stay in the file you choose. They include local chats, Memory, Routines, reminders, saved places and personalization. Sensitive account credentials are not included. Android permissions and default-assistant status are not included and may need to be granted again after reinstalling Orbit.",
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
        page.addView(backupCard);

        page.addView(sectionTitle("CHATGPT ACCOUNT", "account"));
        LinearLayout accountCard = card();
        tagSectionCard(accountCard, "account");
        chatGptStatus = UiKit.text(this, "Checking ChatGPT sign-in…", 15, UiKit.TEXT, true);
        accountCard.addView(chatGptStatus);
        TextView accountHelp = UiKit.text(this,
                "Recommended: sign in with your normal ChatGPT account using OpenAI's Codex device-code flow. This path uses your account-backed Codex/ChatGPT allowance and does not require an OpenAI API key.",
                13, UiKit.MUTED, false);
        accountHelp.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        accountCard.addView(accountHelp);

        Button signIn = primaryButton("Sign in with ChatGPT");
        signIn.setOnClickListener(v -> startChatGptLogin());
        accountCard.addView(signIn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button signOut = secondaryButton("Sign out of ChatGPT");
        LinearLayout.LayoutParams signOutLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        signOutLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        accountCard.addView(signOut, signOutLp);
        signOut.setOnClickListener(v -> {
            ChatGptAuth.logout(this);
            updateChatGptStatus();
            Toast.makeText(this, "Signed out of ChatGPT in Orbit", Toast.LENGTH_SHORT).show();
        });

        accountCard.addView(label("Provider"));
        String[] providerLabels = {"ChatGPT account (recommended)", "OpenAI API relay (fallback)"};
        LinearLayout provider = menuSelector(providerLabels,
                Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)) ? 1 : 0,
                (pos, selectedLabel) -> Prefs.get(this).edit().putString(
                        Prefs.PROVIDER, pos == 0 ? Prefs.PROVIDER_CHATGPT : Prefs.PROVIDER_RELAY).apply());
        accountCard.addView(provider, selectorLp());
        TextView experimental = UiKit.text(this,
                "Orbit uses the public Codex OAuth protocol for ChatGPT-account mode. Because OpenAI does not document the Codex backend as a general third-party mobile API, this integration is experimental and the API relay remains available as an explicit fallback.",
                12, UiKit.MUTED, false);
        experimental.setPadding(0, UiKit.dp(this, 7), 0, 0);
        accountCard.addView(experimental);
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
        voiceCard.addView(toggle("Allow Orbit to receive screenshots", Prefs.SCREENSHOT, true));
        voiceCard.addView(toggle("Attach current screen by default", Prefs.ATTACH_SCREEN_BY_DEFAULT, false));
        voiceCard.addView(toggle("Show contextual screen-action chips when attached", Prefs.CONTEXT_CHIPS, true));
        voiceCard.addView(toggle("Speak replies to voice requests", Prefs.SPEAK, true));
        voiceCard.addView(toggle("Allow longer pauses while speaking", Prefs.VOICE_PAUSE_FRIENDLY, true));
        TextView pauseNote = UiKit.text(this,
                "Voice Beta gives you more time to pause and think before Orbit decides you are finished. Tap the mic again if you want to finish sooner.",
                12, UiKit.MUTED, false);
        pauseNote.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 1), 0, UiKit.dp(this, 6));
        voiceCard.addView(pauseNote);
        voiceCard.addView(toggle("Hands-free voice follow-ups", Prefs.AUTO_LISTEN, false));
        voiceCard.addView(toggle("Keyboard-aware assistant invocation", Prefs.KEYBOARD_AWARE_ASSISTANT, true));

        Button capabilities = secondaryButton("Permissions & capabilities");
        capabilities.setOnClickListener(v -> startActivity(new Intent(this, CapabilitiesActivity.class)));
        LinearLayout.LayoutParams capabilitiesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        capabilitiesLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        voiceCard.addView(capabilities, capabilitiesLp);
        page.addView(voiceCard);

        page.addView(sectionTitle("WEATHER", "weather"));
        LinearLayout weatherCard = card();
        tagSectionCard(weatherCard, "weather");
        TextView weatherHelp = UiKit.text(this,
                "Orbit can answer current weather and forecasts directly in chat using Open-Meteo. You can set a default city or allow approximate device location. Weather questions do not need to open a browser.",
                13, UiKit.MUTED, false);
        weatherHelp.setPadding(0, 0, 0, UiKit.dp(this, 10));
        weatherCard.addView(weatherHelp);
        weatherCard.addView(weatherLocationToggle());
        weatherCard.addView(label("Default weather location (optional)"));
        EditText weatherLocation = field("Ann Arbor, Michigan", Prefs.weatherLocation(this), false);
        weatherCard.addView(weatherLocation);
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
        page.addView(weatherCard);

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

        page.addView(sectionTitle("API RELAY FALLBACK", "relay"));
        LinearLayout relayCard = card();
        tagSectionCard(relayCard, "relay");
        TextView relayHelp = UiKit.text(this, "Optional fallback only. If ChatGPT-account mode is unavailable, Orbit can call a small HTTPS relay you control. Your OpenAI API key stays on that server and is never embedded in the APK. Orbit never silently switches to this paid path.", 13, UiKit.MUTED, false);
        relayHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
        relayCard.addView(relayHelp);
        EditText url = field("https://your-relay.example.com", Prefs.backendUrl(this), false);
        relayCard.addView(url);
        EditText token = field("Relay access token (optional)", Prefs.token(this), true);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52));
        tokenLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        relayCard.addView(token, tokenLp);
        Button save = primaryButton("Save relay settings");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        saveLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        relayCard.addView(save, saveLp);
        save.setOnClickListener(v -> {
            String u = url.getText().toString().trim();
            if (!u.isEmpty() && !u.startsWith("https://")) {
                Toast.makeText(this, "Use an HTTPS relay URL.", Toast.LENGTH_LONG).show();
                return;
            }
            Prefs.get(this).edit().putString(Prefs.BACKEND_URL, u).apply();
            SecureStore.saveRelayToken(this, token.getText().toString().trim());
            Toast.makeText(this, "Relay settings saved securely", Toast.LENGTH_SHORT).show();
        });
        Button diagnostics = secondaryButton("Open Orbit diagnostics");
        LinearLayout.LayoutParams diagnosticsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        diagnosticsLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        relayCard.addView(diagnostics, diagnosticsLp);
        diagnostics.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        page.addView(relayCard);

        page.addView(sectionTitle("CONVERSATIONS", "conversations"));
        LinearLayout conversationCard = card();
        tagSectionCard(conversationCard, "conversations");
        conversationCard.addView(toggle("Start a new chat each time Orbit opens", Prefs.NEW_CHAT_ON_OPEN, true));
        conversationCard.addView(toggle("Save recent chats on this device", Prefs.HISTORY_ENABLED, true));
        conversationCard.addView(toggle("Save screen attachment thumbnails in chat history", Prefs.SAVE_SCREEN_THUMBNAILS, false));
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

        TextView fontLabel = label("App font");
        fontLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 14), 0, UiKit.dp(this, 6));
        styleCard.addView(fontLabel);
        styleCard.addView(fontSelector());
        TextView fontNote = UiKit.text(this,
                "Orbit Default is the current app font. Times New Roman uses Android's built-in serif family for a similar classic look without adding a font file to Orbit.",
                12, UiKit.MUTED, false);
        fontNote.setPadding(0, 0, 0, UiKit.dp(this, 8));
        styleCard.addView(fontNote);

        styleCard.addView(amoledToggle());
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
        ChatGptAuth.requestDeviceCode(new ChatGptAuth.StartCallback() {
            @Override public void onSuccess(ChatGptAuth.DeviceCode code) {
                runOnUiThread(() -> showDeviceCode(code));
                ChatGptAuth.completeDeviceCode(SettingsActivity.this, code, new ChatGptAuth.LoginCallback() {
                    @Override public void onSuccess(ChatGptAuth.AccountInfo account) {
                        runOnUiThread(() -> {
                            updateChatGptStatus();
                            Toast.makeText(SettingsActivity.this, "ChatGPT connected to Orbit", Toast.LENGTH_LONG).show();
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            updateChatGptStatus();
                            showOrbitMessageDialog("ChatGPT sign-in did not finish", message);
                        });
                    }
                });
            }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    updateChatGptStatus();
                    showOrbitMessageDialog("Could not start ChatGPT sign-in", message);
                });
            }
        });
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
        UiKit.prepareOrbitDialog(dialog,
                UiKit.rounded(UiKit.SURFACE, 22, SettingsActivity.this));
        dialog.setOnShowListener(ignore -> {
            UiKit.applyDialogTypography(dialog);
            if (dialog.getWindow() != null) {
                tintDialogText(dialog.getWindow().getDecorView());
            }
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            if (positive != null) positive.setTextColor(destructivePositive
                    ? Color.rgb(239, 105, 105) : UiKit.accent(this));
            if (negative != null) negative.setTextColor(UiKit.accent(this));
            if (neutral != null) neutral.setTextColor(UiKit.accent(this));
        });
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

    private void requestAssistantRole() {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    Toast.makeText(this, "Orbit is already your assistant", Toast.LENGTH_SHORT).show();
                } else {
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), REQ_ASSISTANT);
                }
                return;
            }
        }
        try { startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
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
        ComponentName c = new ComponentName(this, OrbitVoiceInteractionService.class);
        boolean active = VoiceInteractionService.isActiveService(this, c);
        assistantStatus.setText(active ? "✓ Orbit is your active assistant" : "○ Orbit is not the active assistant yet");
        assistantStatus.setTextColor(active ? UiKit.SUCCESS : UiKit.TEXT);
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
        if (SECTION_VOICE.equals(section)) return "voice".equals(key) || "weather".equals(key);
        if (SECTION_DATA.equals(section)) return "data".equals(key);
        if (SECTION_CONVERSATIONS.equals(section)) return "conversations".equals(key);
        if (SECTION_APPEARANCE.equals(section)) return "appearance".equals(key);
        if (SECTION_ADVANCED.equals(section)) return "relay".equals(key);
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
        b.setTag(TAG_PRIMARY);
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
        b.setTag(TAG_SECONDARY);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setStateListAnimator(null);
        UiKit.pressScale(b);
        return b;
    }

    private CheckBox toggle(String label, String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(UiKit.TEXT);
        cb.setTextSize(14);
        cb.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{UiKit.accent(this), Color.rgb(90,94,105)}));
        cb.setChecked(Prefs.get(this).getBoolean(key, def));
        cb.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 2));
        cb.setOnCheckedChangeListener((button, checked) -> {
            boolean wasHapticsEnabled = Prefs.haptics(this);
            if (Prefs.HAPTICS.equals(key) && !checked && wasHapticsEnabled) {
                performSettingsHaptic(button);
            } else if (!Prefs.HAPTICS.equals(key) && wasHapticsEnabled) {
                performSettingsHaptic(button);
            }
            Prefs.get(this).edit().putBoolean(key, checked).apply();
            if (Prefs.HAPTICS.equals(key) && checked) performSettingsHaptic(button);
        });
        return cb;
    }

    private CheckBox weatherLocationToggle() {
        CheckBox cb = new CheckBox(this);
        cb.setText("Use approximate device location for local weather");
        cb.setTextColor(UiKit.TEXT);
        cb.setTextSize(14);
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        cb.setChecked(Prefs.weatherUseDeviceLocation(this) && granted);
        cb.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 8));
        cb.setOnCheckedChangeListener((button, checked) -> {
            if (checked && !granted) {
                button.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.WEATHER_USE_DEVICE_LOCATION, checked).apply();
        });
        return cb;
    }

    private CheckBox amoledToggle() {
        CheckBox cb = new CheckBox(this);
        cb.setText("AMOLED black background");
        cb.setTextColor(UiKit.TEXT);
        cb.setTextSize(14);
        cb.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{UiKit.accent(this), Color.rgb(90,94,105)}));
        cb.setChecked(Prefs.amoledMode(this));
        cb.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 2));
        cb.setOnCheckedChangeListener((button, checked) -> {
            performSettingsHaptic(button);
            Prefs.get(this).edit().putBoolean(Prefs.AMOLED_MODE, checked).apply();
            UiKit.syncTheme(this);
            applyThemeInPlace();
        });
        return cb;
    }

    private CheckBox notificationToggle() {
        CheckBox cb = new CheckBox(this);
        cb.setText("Notify me when a background response finishes");
        cb.setTextColor(UiKit.TEXT);
        cb.setTextSize(14);
        cb.setButtonTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{UiKit.accent(this), Color.rgb(90,94,105)}));
        cb.setChecked(Prefs.backgroundNotifications(this));
        cb.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 2));
        cb.setOnCheckedChangeListener((button, checked) -> {
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Prefs.get(this).edit().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).apply();
                button.setChecked(false);
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                return;
            }
            Prefs.get(this).edit().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, checked).apply();
            if (checked) NotificationHelper.ensureChannel(this);
        });
        return cb;
    }

    private void performSettingsHaptic(View view) {
        if (view == null || !Prefs.haptics(this)) return;
        try { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        catch (Exception ignored) {}
    }

    private View themeSelector() {
        String[] keys = new String[]{"dynamic", "blurple", "violet", "blue", "mint", "rose", "nova", "pastel_pink", "pastel_blue"};
        String[] labels = new String[]{"Dynamic", "Blurple", "Violet", "Blue", "Mint", "Rose", "Nova", "Pastel Pink", "Pastel Blue"};
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
                applyFontInPlace();
            }
        });
        selector.setLayoutParams(selectorLp());
        return selector;
    }

    private View bubbleColorSelector(String prefKey, boolean assistant) {
        String[] keys = new String[]{"classic", "accent", "blurple", "violet", "blue", "mint", "rose", "nova", "pastel_pink", "pastel_blue"};
        String[] labels = new String[]{"Classic", "Accent", "Blurple", "Violet", "Blue", "Mint", "Rose", "Nova", "Pastel Pink", "Pastel Blue"};
        String selected = Prefs.get(this).getString(prefKey, "classic");
        return colorMenuSelector(keys, labels, selected, assistant, false, prefKey);
    }

    private View colorMenuSelector(String[] keys, String[] labels, String selectedKey,
                                   boolean assistant, boolean accentSelector, String prefKey) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), UiKit.accent(this), 16, this));
        field.setTag(TAG_SECONDARY);

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
                    if (accentSelector) applyThemeInPlace();
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

    private void applyThemeInPlace() {
        UiKit.syncTheme(this);
        int accent = UiKit.accent(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);

        View rootView = w.getDecorView().findViewById(android.R.id.content);
        if (rootView != null) applyThemeRecursive(rootView, accent);
    }

    private void applyThemeRecursive(View view, int accent) {
        if (view instanceof ScrollView) view.setBackgroundColor(UiKit.BG);
        Object rawTag = view.getTag();
        String tag = rawTag instanceof String ? (String) rawTag : "";

        if (TAG_CARD.equals(tag) || tag.startsWith(TAG_CARD + ":")) {
            view.setBackground(UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(accent, 38), 24, this));
        } else if (TAG_PRIMARY.equals(tag)) {
            view.setBackground(UiKit.ripple(accent, UiKit.onAccent(accent), 15, this));
            if (view instanceof Button) ((Button) view).setTextColor(UiKit.onAccent(accent));
        } else if (TAG_SECONDARY.equals(tag)) {
            view.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2, Color.rgb(53,58,72), accent, 15, this));
        } else if (TAG_ACCENT_TEXT.equals(tag) && view instanceof TextView) {
            ((TextView) view).setTextColor(accent);
        } else if (TAG_ACCENT_ICON.equals(tag) && view instanceof ImageButton) {
            ImageButton icon = (ImageButton) view;
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(accent));
            icon.setBackground(UiKit.ripple(UiKit.SURFACE_2, accent, 18, this));
        } else if ("orbit_mark".equals(tag)) {
            // The current Orbit mark is custom-drawn. Force an immediate redraw
            // after the accent preference changes instead of waiting for re-entry.
            view.invalidate();
        } else if ("orbit_mark_shell".equals(tag)) {
            view.setBackground(UiKit.rounded(UiKit.orbitShellColor(), 999, this));
        } else if ("orbit_mark_ring".equals(tag)) {
            view.setBackground(UiKit.rounded(accent, 999, this));
        } else if ("orbit_mark_core".equals(tag)) {
            view.setBackground(UiKit.rounded(UiKit.orbitCoreColor(), 999, this));
        } else if ("orbit_mark_dot".equals(tag)) {
            view.setBackground(UiKit.rounded(UiKit.orbitSatelliteColor(this), 999, this));
        } else if (tag.startsWith(TAG_THEME_PREFIX) && view instanceof Button) {
            String key = tag.substring(TAG_THEME_PREFIX.length());
            int color = UiKit.accentForName(this, key);
            boolean active = key.equals(Prefs.get(this).getString(Prefs.ACCENT, "dynamic"));
            Button swatch = (Button) view;
            swatch.setTextColor(active ? Color.WHITE : UiKit.TEXT);
            int fill = active ? UiKit.blend(color, UiKit.SURFACE_2, 0.30f) : UiKit.SURFACE_2;
            int stroke = active ? color : Color.rgb(55, 60, 74);
            swatch.setBackground(UiKit.rippleOutlined(fill, stroke, color, 16, this));
        } else if (tag.startsWith(TAG_BUBBLE_PREFIX) && view instanceof Button) {
            String payload = tag.substring(TAG_BUBBLE_PREFIX.length());
            String[] parts = payload.split(":", 3);
            if (parts.length == 3) {
                String prefKey = parts[0];
                String key = parts[1];
                boolean assistant = "assistant".equals(parts[2]);
                int color = bubblePreviewColor(key, assistant);
                boolean active = key.equals(Prefs.get(this).getString(prefKey, "classic"));
                Button swatch = (Button) view;
                swatch.setTextColor(active ? UiKit.onBubble(color) : UiKit.TEXT);
                int fill = active ? color : UiKit.blend(color, UiKit.SURFACE_2, 0.18f);
                swatch.setBackground(UiKit.rippleOutlined(fill, color, color, 15, this));
            }
        }

        if (view instanceof CheckBox) {
            ((CheckBox) view).setButtonTintList(new android.content.res.ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{accent, Color.rgb(90,94,105)}));
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeRecursive(group.getChildAt(i), accent);
            }
        }
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
        e.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(53,58,72), 15, this));
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

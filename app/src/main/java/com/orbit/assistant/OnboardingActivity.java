package com.orbit.assistant;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private static final int REQ_MICROPHONE = 944;
    private static final int REQ_CAMERA = 945;
    private static final int REQ_CONTACTS = 946;
    private static final int REQ_TRIGGER_ALERTS = 947;
    private static final int REQ_ROUTINE_FINE_LOCATION = 948;
    private static final int REQ_ROUTINE_BACKGROUND_LOCATION = 949;

    private int step;
    private boolean manual;
    private boolean resumedOnce;
    private boolean relayExpanded;
    private boolean completionCelebrationPlayed;
    private String appliedAppearance = "";
    private FrameLayout completionMarkHost;
    private View completionMark;

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
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", 0);
            relayExpanded = savedInstanceState.getBoolean("relayExpanded", false);
            completionCelebrationPlayed = savedInstanceState.getBoolean(
                    "completionCelebrationPlayed", false);
        }
        else if (manual) step = 0;
        else step = OnboardingState.currentStep(this);
        if (savedInstanceState == null && Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)))
            relayExpanded = true;
        persistStep();
        render();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_MANUAL, false)) {
            manual = true;
            step = 0;
            completionCelebrationPlayed = false;
            persistStep();
            render();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putInt("step", step);
        outState.putBoolean("relayExpanded", relayExpanded);
        outState.putBoolean("completionCelebrationPlayed", completionCelebrationPlayed);
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
            if (step == 7) completionCelebrationPlayed = false;
            step--;
            persistStep();
            render();
        } else if (manual) {
            confirmExitSetup();
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
        if (step == 7 && !completionCelebrationPlayed) {
            completionCelebrationPlayed = true;
            root.post(this::playCompletionCelebration);
        }
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
        FrameLayout markHost = new FrameLayout(this);
        markHost.setClipChildren(false);
        markHost.setClipToPadding(false);
        View mark = UiKit.orbitMark(this, 38);
        markHost.addView(mark, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int markBoxSize = UiKit.dp(this, step == 7 ? 64 : 44);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                markBoxSize, markBoxSize);
        markLp.rightMargin = UiKit.dp(this, 10);
        header.addView(markHost, markLp);
        completionMarkHost = step == 7 ? markHost : null;
        completionMark = step == 7 ? mark : null;

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
            if (manual) confirmExitSetup(); else confirmSkip();
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
                "ChatGPT is the recommended connection. Advanced users can instead use Orbit's existing private HTTPS-relay fallback.");
        boolean connected = ChatGptAuth.isSignedIn(this);
        boolean chatGptActive = Prefs.PROVIDER_CHATGPT.equals(Prefs.provider(this));
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
        Button action = primaryButton(connected
                ? (chatGptActive ? "ChatGPT account active" : "Use ChatGPT account")
                : "Sign in with ChatGPT");
        action.setEnabled(!connected || !chatGptActive);
        action.setAlpha(connected && chatGptActive ? .68f : 1f);
        action.setOnClickListener(v -> {
            if (connected) {
                Prefs.get(this).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_CHATGPT).apply();
                render();
            } else startChatGptLogin();
        });
        card.addView(action, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 48)));
        page.addView(card, cardLp());

        Button fallback = quietButton(relayExpanded ? "Hide OpenAI API fallback" :
                "Configure OpenAI API fallback");
        fallback.setOnClickListener(v -> {
            relayExpanded = !relayExpanded;
            render();
        });
        page.addView(fallback, buttonLp());

        if (relayExpanded) buildRelayFallback(page);
    }

    private void buildRelayFallback(LinearLayout page) {
        LinearLayout relayCard = card();
        relayCard.addView(UiKit.text(this, "OpenAI API relay fallback", 16, UiKit.TEXT, true));
        addCardDescription(relayCard,
                "For advanced users. Orbit can use a private HTTPS relay connected to the OpenAI API. Your OpenAI API key remains on your relay server rather than inside Orbit.");
        TextView status = UiKit.text(this,
                Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)) && Prefs.relayConfigured(this)
                        ? "✓ API fallback is the active provider"
                        : Prefs.relayConfigured(this) ? "Relay saved; ChatGPT remains active"
                        : "Relay not configured",
                12, Prefs.relayConfigured(this) ? UiKit.SUCCESS : UiKit.MUTED, true);
        status.setPadding(0, 0, 0, UiKit.dp(this, 10));
        relayCard.addView(status);

        EditText url = field("https://your-relay.example.com", Prefs.backendUrl(this), false);
        relayCard.addView(url, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52)));
        EditText token = field("Relay access token (optional)", Prefs.token(this), true);
        LinearLayout.LayoutParams tokenLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 52));
        tokenLp.topMargin = UiKit.dp(this, 9);
        relayCard.addView(token, tokenLp);

        Button save = secondaryButton("Save and use API fallback");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46));
        saveLp.topMargin = UiKit.dp(this, 11);
        relayCard.addView(save, saveLp);
        save.setOnClickListener(v -> {
            try {
                String requestedUrl = url.getText().toString().trim();
                if (requestedUrl.isEmpty()) {
                    Toast.makeText(this, "Enter an HTTPS relay URL first.", Toast.LENGTH_LONG).show();
                    return;
                }
                String error = Prefs.saveRelaySettings(this, requestedUrl,
                        token.getText().toString());
                if (!error.isEmpty()) {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    return;
                }
                Prefs.get(this).edit().putString(Prefs.PROVIDER, Prefs.PROVIDER_RELAY).apply();
                Toast.makeText(this, "API fallback saved and selected", Toast.LENGTH_SHORT).show();
                render();
            } catch (Exception error) {
                showMessage("Could not save relay settings",
                        "Orbit could not securely save the optional relay access token.");
            }
        });

        TextView future = UiKit.text(this,
                "More provider options, including Claude and Gemini, are planned for future Orbit versions.",
                11, UiKit.MUTED, false);
        future.setPadding(0, UiKit.dp(this, 11), 0, 0);
        relayCard.addView(future);
        page.addView(relayCard, cardLp());
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
                "You can assign a saved Routine to the Routine Quick Settings tile later from Settings → Assistant setup.");
        page.addView(routine, cardLp());

        TextView widgetNote = UiKit.text(this,
                "Orbit also supports home-screen widgets. Add them later from your launcher or Orbit Settings.",
                12, UiKit.MUTED, false);
        widgetNote.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams widgetNoteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        widgetNoteLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 2), UiKit.dp(this, 4),
                UiKit.dp(this, 10));
        page.addView(widgetNote, widgetNoteLp);

        LinearLayout side = card();
        side.addView(UiKit.text(this, "Side button and assistant gesture", 16, UiKit.TEXT, true));
        addCardDescription(side, OrbitSetupHelper.isOrbitAssistantActive(this)
                ? "Orbit is selected as your assistant. Your device's supported assistant gesture can now invoke it."
                : "After choosing Orbit as the default assistant, use whichever assistant button or gesture Android exposes on your device.");
        page.addView(side, cardLp());
    }

    private void buildCapabilities(LinearLayout page) {
        addTitle(page, "Choose what Orbit can do",
                "Set up only the access you want. Every action stays on this setup step when you return from Android Settings.");

        LinearLayout recommended = capabilityCard(page, "RECOMMENDED ACCESS");
        addCapability(recommended, "Notifications",
                "Reminders, Routine alerts, updates, and background results.",
                ReminderNotifier.notificationsAllowed(this), v ->
                        CapabilityAccessHelper.requestOrManageNotifications(this, REQ_NOTIFICATIONS));
        addRuntimeCapability(recommended, "Microphone", "Used for Orbit Voice.",
                Manifest.permission.RECORD_AUDIO, REQ_MICROPHONE);
        addRuntimeCapability(recommended, "Approximate location",
                "Local weather and location-aware features.",
                Manifest.permission.ACCESS_COARSE_LOCATION, REQ_LOCATION);
        addRuntimeCapability(recommended, "Flashlight control",
                "Android Camera access lets Orbit control the flashlight.",
                Manifest.permission.CAMERA, REQ_CAMERA);

        LinearLayout context = capabilityCard(page, "CONTEXT");
        context.addView(capabilityToggle("Allow Orbit to read current-screen text",
                Prefs.SCREEN_CONTEXT, Prefs.screenContext(this)));
        context.addView(capabilityToggle("Allow screenshots for visual context and screen selection",
                Prefs.SCREENSHOT, Prefs.screenshot(this)));
        addCapability(context, "Notification intelligence",
                "Understand notifications only when this access is enabled.",
                NotificationAccess.enabled(this), v ->
                        CapabilityAccessHelper.openNotificationIntelligence(this));

        LinearLayout controls = capabilityCard(page, "DEVICE CONTROLS");
        addCapability(controls, "Brightness control",
                "Allows Orbit to change system brightness.",
                OrbitPermissionHelper.canWriteSystemSettings(this), v ->
                        CapabilityAccessHelper.openWriteSettings(this));
        addCapability(controls, "Do Not Disturb control",
                "Allows Orbit to control Do Not Disturb.",
                OrbitPermissionHelper.hasDndAccess(this), v ->
                        CapabilityAccessHelper.openDndAccess(this));
        addRuntimeCapability(controls, "Contact lookup",
                "Resolve saved contact names for voice and device commands.",
                Manifest.permission.READ_CONTACTS, REQ_CONTACTS);

        LinearLayout automation = capabilityCard(page, "AUTOMATION");
        boolean exact = RoutineTriggerScheduler.canScheduleExact(this);
        addCapability(automation, "Precise timing",
                "Accurate reminders and Routine time triggers.", exact, v ->
                        CapabilityAccessHelper.openExactAlarmAccess(this));
        boolean locationReady = RoutineLocationTriggerScheduler.ready(this);
        automation.addView(capabilityRow("Location automation",
                "Arrival and leave Routine triggers.",
                CapabilityAccessHelper.locationAutomationStatus(this), locationReady, v ->
                        CapabilityAccessHelper.setupLocationAutomation(this,
                                REQ_ROUTINE_FINE_LOCATION, REQ_ROUTINE_BACKGROUND_LOCATION)));
        addCapability(automation, "Trigger alerts",
                "Visible handoffs when an automatic Routine needs attention.",
                RoutineTriggerNotifier.notificationsAllowed(this), v ->
                        CapabilityAccessHelper.requestOrManageTriggerAlerts(this, REQ_TRIGGER_ALERTS));

        Button all = secondaryButton("View full capabilities dashboard");
        all.setOnClickListener(v -> openCapabilities());
        page.addView(all, buttonLp());
    }

    private void buildPersonalize(LinearLayout page) {
        addTitle(page, "Make Orbit yours",
                "These are Orbit's normal appearance settings and stay synchronized with Look & Feel.");
        LinearLayout accentCard = card();
        accentCard.addView(UiKit.text(this, "Accent", 16, UiKit.TEXT, true));
        addCardDescription(accentCard, "Choose one of Orbit's existing presets.");
        accentCard.addView(appearanceColorSelector(UiKit.accentKeys(), UiKit.accentLabels(),
                Prefs.ACCENT, false, true));
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
        accentCard.addView(amoled);
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
        page.addView(fontCard, cardLp());

        LinearLayout conversationCard = card();
        conversationCard.addView(UiKit.text(this, "Conversation style", 16, UiKit.TEXT, true));
        addCardDescription(conversationCard,
                "Choose how conversation bubbles and chat content look in full chat and the Side-button assistant.");
        conversationCard.addView(UiKit.text(this, "Your bubbles", 12, UiKit.MUTED, true));
        conversationCard.addView(appearanceColorSelector(UiKit.bubbleColorKeys(),
                UiKit.bubbleColorLabels(), Prefs.USER_BUBBLE_COLOR, false, false));
        TextView orbitLabel = UiKit.text(this, "Orbit bubbles", 12, UiKit.MUTED, true);
        orbitLabel.setPadding(0, UiKit.dp(this, 12), 0, 0);
        conversationCard.addView(orbitLabel);
        conversationCard.addView(appearanceColorSelector(UiKit.bubbleColorKeys(),
                UiKit.bubbleColorLabels(), Prefs.ASSISTANT_BUBBLE_COLOR, true, false));
        TextView chatSizeLabel = UiKit.text(this, "Chat text size", 12, UiKit.MUTED, true);
        chatSizeLabel.setPadding(0, UiKit.dp(this, 12), 0, 0);
        conversationCard.addView(chatSizeLabel);
        String[] chatSizeKeys = {Prefs.CHAT_TEXT_SMALL, Prefs.CHAT_TEXT_DEFAULT,
                Prefs.CHAT_TEXT_LARGE, Prefs.CHAT_TEXT_EXTRA_LARGE};
        String[] chatSizeLabels = {"Small", "Default", "Large", "Extra large"};
        conversationCard.addView(preferenceSelector(chatSizeKeys, chatSizeLabels,
                Prefs.chatTextSize(this), Prefs.CHAT_TEXT_SIZE));
        page.addView(conversationCard, cardLp());

        LinearLayout everydayCard = card();
        everydayCard.addView(UiKit.text(this, "Everyday preferences", 16, UiKit.TEXT, true));
        addCardDescription(everydayCard,
                "Choose the apps and units Orbit uses for everyday requests.");
        everydayCard.addView(UiKit.text(this, "Gallery app", 12, UiKit.MUTED, true));
        everydayCard.addView(galleryAppSelector());
        TextView weatherUnitsLabel = UiKit.text(this, "Weather units", 12, UiKit.MUTED, true);
        weatherUnitsLabel.setPadding(0, UiKit.dp(this, 12), 0, 0);
        everydayCard.addView(weatherUnitsLabel);
        String[] weatherUnitKeys = {Prefs.WEATHER_UNITS_SYSTEM,
                Prefs.WEATHER_UNITS_FAHRENHEIT, Prefs.WEATHER_UNITS_CELSIUS};
        String[] weatherUnitLabels = {"System default", "Fahrenheit (°F)", "Celsius (°C)"};
        everydayCard.addView(preferenceSelector(weatherUnitKeys, weatherUnitLabels,
                Prefs.weatherUnits(this), Prefs.WEATHER_UNITS));
        page.addView(everydayCard, cardLp());
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
                "You're all set. Orbit is ready when you are.");
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

    /** Decorative, non-blocking finish-page moment using the current Orbit accent. */
    private void playCompletionCelebration() {
        FrameLayout host = completionMarkHost;
        View mark = completionMark;
        if (host == null || mark == null || !mark.isAttachedToWindow()) return;

        if (Prefs.haptics(this)) {
            int feedback = Build.VERSION.SDK_INT >= 30
                    ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK;
            try { mark.performHapticFeedback(feedback); }
            catch (Exception ignored) { }
        }
        if (!ValueAnimator.areAnimatorsEnabled()) return;

        List<Animator> animations = new ArrayList<>();
        ObjectAnimator pulse = ObjectAnimator.ofPropertyValuesHolder(mark,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, .90f, 1.16f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, .90f, 1.16f, 1f));
        pulse.setDuration(920L);
        pulse.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animations.add(pulse);

        int accent = UiKit.accent(this);
        int hostSize = UiKit.dp(this, 64);
        int ringSize = UiKit.dp(this, 44);
        View ring = new View(this);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setShape(GradientDrawable.OVAL);
        ringShape.setColor(Color.TRANSPARENT);
        ringShape.setStroke(UiKit.dp(this, 2), UiKit.withAlpha(accent, 190));
        ring.setBackground(ringShape);
        ring.setAlpha(0f);
        ring.setScaleX(.62f);
        ring.setScaleY(.62f);
        ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(ringSize, ringSize);
        ringLp.leftMargin = (hostSize - ringSize) / 2;
        ringLp.topMargin = (hostSize - ringSize) / 2;
        host.addView(ring, 0, ringLp);
        ObjectAnimator ringBurst = ObjectAnimator.ofPropertyValuesHolder(ring,
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f, .82f, .52f, 0f),
                PropertyValuesHolder.ofFloat(View.SCALE_X, .62f, .88f, 1.38f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, .62f, .88f, 1.38f));
        ringBurst.setDuration(920L);
        ringBurst.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
        animations.add(ringBurst);

        List<View> particles = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double angle = (Math.PI * 2d * i / 8d) - Math.PI / 2d;
            int dotSize = UiKit.dp(this, i % 2 == 0 ? 6 : 5);
            int center = hostSize / 2 - dotSize / 2;
            int initialRadius = UiKit.dp(this, 10);
            int travel = UiKit.dp(this, i % 2 == 0 ? 19 : 18);
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i % 2 == 0 ? accent : UiKit.orbitSatelliteColor(this));
            dot.setBackground(shape);
            dot.setAlpha(0f);
            dot.setScaleX(.58f);
            dot.setScaleY(.58f);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dotSize, dotSize);
            dotLp.leftMargin = center + (int) Math.round(Math.cos(angle) * initialRadius);
            dotLp.topMargin = center + (int) Math.round(Math.sin(angle) * initialRadius);
            host.addView(dot, 1, dotLp);
            particles.add(dot);

            PropertyValuesHolder x = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f,
                    (float) (Math.cos(angle) * travel));
            PropertyValuesHolder y = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f,
                    (float) (Math.sin(angle) * travel));
            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat(
                    View.ALPHA, 0f, 1f, .92f, 0f);
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(
                    View.SCALE_X, .58f, 1.18f, .35f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(
                    View.SCALE_Y, .58f, 1.18f, .35f);
            ObjectAnimator particle = ObjectAnimator.ofPropertyValuesHolder(
                    dot, x, y, alpha, scaleX, scaleY);
            particle.setStartDelay(80L + i * 10L);
            particle.setDuration(820L);
            particle.setInterpolator(new PathInterpolator(.2f, 0f, 0f, 1f));
            animations.add(particle);
        }

        AnimatorSet ignition = new AnimatorSet();
        ignition.playTogether(animations);
        ignition.addListener(new AnimatorListenerAdapter() {
            private boolean cleaned;

            private void cleanUp() {
                if (cleaned) return;
                cleaned = true;
                host.removeView(ring);
                for (View particle : particles) host.removeView(particle);
                mark.setScaleX(1f);
                mark.setScaleY(1f);
            }

            @Override public void onAnimationEnd(Animator animation) {
                cleanUp();
            }

            @Override public void onAnimationCancel(Animator animation) {
                cleanUp();
            }
        });
        ignition.start();
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
                "Start using Orbit" : adaptiveNavigationLabel());
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
    }

    private String adaptiveNavigationLabel() {
        if (step == 1 && !connectionConfigured()) return "Skip for now";
        if (step == 2 && !OrbitSetupHelper.isOrbitAssistantActive(this)) return "Skip for now";
        if (step == 6 && RoutineStore.findById(this,
                OnboardingState.starterRoutineId(this)) == null) return "Skip for now";
        return "Continue";
    }

    private boolean connectionConfigured() {
        return ChatGptAuth.isSignedIn(this) ||
                (Prefs.PROVIDER_RELAY.equals(Prefs.provider(this)) && Prefs.relayConfigured(this));
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

    private LinearLayout capabilityCard(LinearLayout page, String title) {
        TextView heading = UiKit.text(this, title, 11, UiKit.MUTED, true);
        heading.setLetterSpacing(.11f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 6), 0, UiKit.dp(this, 7));
        page.addView(heading, headingLp);
        LinearLayout group = card();
        page.addView(group, cardLp());
        return group;
    }

    private void addRuntimeCapability(LinearLayout group, String title, String description,
                                      String permission, int requestCode) {
        boolean ready = CapabilityAccessHelper.permissionGranted(this, permission);
        addCapability(group, title, description, ready, v ->
                CapabilityAccessHelper.requestOrManageRuntimePermission(
                        this, permission, requestCode));
    }

    private void addCapability(LinearLayout group, String title, String description,
                               boolean ready, View.OnClickListener listener) {
        group.addView(capabilityRow(title, description, ready ? "Ready" : "Needs access",
                ready, listener));
    }

    private View capabilityRow(String title, String description, String status, boolean ready,
                               View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, title, 14, UiKit.TEXT, true));
        TextView descriptionView = UiKit.text(this, description, 11, UiKit.MUTED, false);
        descriptionView.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 3));
        copy.addView(descriptionView);
        copy.addView(UiKit.text(this, status,
                11, ready ? UiKit.SUCCESS : UiKit.MUTED, true));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        Button action = quietButton(ready ? "Manage" : "Set up");
        action.setOnClickListener(listener);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, UiKit.dp(this, 42));
        actionLp.leftMargin = UiKit.dp(this, 8);
        row.addView(action, actionLp);
        return row;
    }

    private View capabilityToggle(String label, String prefKey, boolean checked) {
        CheckBox toggle = new CheckBox(this);
        toggle.setText(label);
        toggle.setTextColor(UiKit.TEXT);
        toggle.setTextSize(13);
        toggle.setButtonTintList(UiKit.accentControlTint(this));
        toggle.setChecked(checked);
        toggle.setMinHeight(UiKit.dp(this, 52));
        toggle.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        toggle.setOnCheckedChangeListener((button, enabled) ->
                Prefs.get(this).edit().putBoolean(prefKey, enabled).apply());
        return toggle;
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

    private EditText field(String hint, String value, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setHintTextColor(Color.rgb(115, 120, 135));
        field.setText(value);
        field.setTextColor(UiKit.TEXT);
        field.setTextSize(14);
        field.setSingleLine(true);
        field.setPadding(UiKit.dp(this, 15), 0, UiKit.dp(this, 15), 0);
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), 15, this));
        if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private View appearanceColorSelector(String[] keys, String[] labels, String prefKey,
                                         boolean assistantBubble, boolean accentSelector) {
        String fallback = accentSelector ? "dynamic" : "classic";
        String selected = Prefs.get(this).getString(prefKey, fallback);
        int selectedIndex = indexOf(keys, selected);
        Button selector = secondaryButton(labels[selectedIndex] + "  ▾");
        selector.setOnClickListener(v -> {
            int[] colors = new int[keys.length];
            for (int i = 0; i < keys.length; i++) {
                if (accentSelector) colors[i] = UiKit.accentForName(this, keys[i]);
                else colors[i] = bubblePreviewColor(keys[i], assistantBubble);
            }
            UiKit.showOrbitColorMenu(this, selector, labels, colors, selectedIndex,
                    (index, label) -> {
                        String key = keys[index];
                        Prefs.get(this).edit().putString(prefKey, key).apply();
                        UiKit.notifyAppearanceChanged(this);
                        render();
                    });
        });
        selector.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        return selector;
    }

    private View preferenceSelector(String[] keys, String[] labels, String selectedKey,
                                    String prefKey) {
        int selectedIndex = indexOf(keys, selectedKey);
        Button selector = secondaryButton(labels[selectedIndex] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitMenu(this, selector, labels,
                selectedIndex, (index, label) -> {
                    Prefs.get(this).edit().putString(prefKey, keys[index]).apply();
                    render();
                }));
        selector.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
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
        final int selectedIndex = selected;
        Button selector = secondaryButton(labels[selectedIndex] + "  ▾");
        selector.setOnClickListener(v -> UiKit.showOrbitMenu(this, selector, labels,
                selectedIndex, (index, label) -> {
                    // Saves the exact discovered picker, matching Settings.
                    GalleryAppPreference.setPreferredOption(this, options.get(index));
                    render();
                }));
        selector.setLayoutParams(new LinearLayout.LayoutParams(-1, UiKit.dp(this, 46)));
        return selector;
    }

    private int bubblePreviewColor(String key, boolean assistant) {
        int classic = assistant ? UiKit.SURFACE :
                UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.46f);
        if ("classic".equals(key)) return classic;
        if ("accent".equals(key)) return UiKit.accent(this);
        return UiKit.accentForName(this, key);
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
        if ((requestCode == REQ_NOTIFICATIONS || requestCode == REQ_TRIGGER_ALERTS) &&
                CapabilityAccessHelper.permissionGranted(this,
                        Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationHelper.ensureChannel(this);
        }
        if (requestCode == REQ_TRIGGER_ALERTS || requestCode == REQ_ROUTINE_FINE_LOCATION ||
                requestCode == REQ_ROUTINE_BACKGROUND_LOCATION) {
            RoutineTriggerScheduler.rescheduleAll(this);
        }
        render();
    }

    private void confirmSkip() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Skip setup?")
                .setMessage("You can finish setup anytime from Settings → Assistant setup.")
                .setNegativeButton("Keep setting up", null)
                .setPositiveButton("Skip setup", (d, which) -> completeAndExit())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void confirmExitSetup() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Exit setup?")
                .setMessage("Your current Orbit settings are already saved. You can return to setup anytime from Assistant setup.")
                .setNegativeButton("Keep setting up", null)
                .setPositiveButton("Exit setup", (d, which) -> finish())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
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

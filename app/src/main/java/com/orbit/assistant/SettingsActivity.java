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
    /**
     * The control a detail page should scroll to and pulse when it opens, or absent.
     *
     * <p>What turns a search result from "took me to Look &amp; Feel" into "took me to AMOLED".
     * Carried as a control key rather than a position, because positions change every time a
     * control is added and a stale one would land somebody in the wrong place silently.
     */
    static final String EXTRA_FOCUS = "settings_focus";
    private static final String SECTION_ASSISTANT = "assistant";
    static final String SECTION_AI = "ai";
    private static final String SECTION_VOICE = "voice";
    static final String SECTION_DATA = "data";
    private static final String SECTION_DECK = "deck";
    private static final String SECTION_CONVERSATIONS = "conversations";
    static final String SECTION_ROUTINES = "routines";
    private static final String SECTION_EXTENSIONS = "extensions";
    static final String SECTION_APPEARANCE = "appearance";
    private static final String SECTION_UPDATES = "updates";
    static final String SECTION_ADVANCED = "advanced";

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
    private TextView chatGptHelp;
    private Button chatGptSignIn;
    private Button chatGptSignOut;
    private ScrollView settingsScroll;
    private String appliedAppearance = "";
    /** Accent/AMOLED only — the appearance that forces a rebuild. */
    private String appliedStructuralAppearance = "";
    private boolean rebuildingAppearance;
    private int leloTapCount = 0;
    private long lastLeloTapMs = 0L;
    private String settingsSection = "";
    /**
     * Where each searchable control ended up in the tree Orbit just built.
     *
     * <p>Rebuilt with the page, so an appearance change that rebuilds Settings in place cannot
     * leave the map pointing at detached views. Empty on the hub, which has no controls of its own.
     */
    private final java.util.Map<String, View> searchTargets = new java.util.HashMap<>();
    /** The control this page was opened to show, consumed once it has been shown. */
    private String pendingFocusKey = "";
    /** The hub's search field and its results, or null on a detail page. */
    private EditText searchField;
    private LinearLayout searchResults;
    private ImageButton searchClear;
    /** The hub's category cards, put aside while results are showing. */
    private LinearLayout categoryList;
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

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        settingsSection = normalizeSection(getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SECTION));
        String focus = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_FOCUS);
        pendingFocusKey = focus == null ? "" : focus.trim();
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        // Paint the window itself in Orbit's surface colour. If a rebuild ever exposes the window
        // for a frame it shows Orbit's background rather than the default decor or a black frame.
        w.setBackgroundDrawable(new ColorDrawable(UiKit.BG));
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
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
        // Rebuilt with the page, so an in-place appearance rebuild can never leave the search map
        // pointing at views that are no longer on screen.
        searchTargets.clear();
        searchField = null;
        searchResults = null;
        searchClear = null;
        categoryList = null;
        View content = settingsSection.isEmpty() ? buildSettingsHub() : buildDetailContent();
        focusPendingControl();
        return content;
    }

    private View buildSettingsHub() {
        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);

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
                "Search for a control, or choose a section. Orbit keeps the everyday controls easy to find while deeper options stay out of the way.",
                14, UiKit.MUTED, false);
        intro.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        introLp.setMargins(UiKit.dp(this, 2), UiKit.dp(this, 18), UiKit.dp(this, 2), UiKit.dp(this, 10));
        page.addView(intro, introLp);

        page.addView(buildSearchField(), searchFieldLp());
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        searchResults.setVisibility(View.GONE);
        page.addView(searchResults, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The category cards live in a container of their own so searching can put them aside in
        // one move. Hiding eleven cards individually would work and would also mean the next card
        // added to this screen quietly failing to disappear.
        LinearLayout categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.VERTICAL);
        categoryList = categories;
        page.addView(categories, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        categories.addView(settingsCategoryCard(SECTION_ASSISTANT, "Assistant setup",
                "Default assistant, Side button and Quick Settings access"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_AI, "AI & account",
                "AI Providers, ChatGPT sign-in and intelligence modes"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_VOICE, "Voice, context & permissions",
                "Voice Beta, screen context and capabilities"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_DATA, "Personalization & data",
                "Weather, Gallery, reminders, saved places, Memory and backup"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_ROUTINES, "Routines",
                "Create, edit and run saved Action Engine chains"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_EXTENSIONS, "Extensions",
                "Add integrations and new actions to Orbit"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_DECK, "Orbit Deck",
                "Customize your personal Orbit shortcuts"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_CONVERSATIONS, "Conversations",
                "History, chat behavior and background notifications"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_APPEARANCE, "Look & Feel",
                "Theme Studio, font, chat text size and haptics"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_UPDATES, "About & updates",
                "Current version and verified official Orbit releases"), categoryLp());
        categories.addView(settingsCategoryCard(SECTION_ADVANCED, "Advanced",
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

    /**
     * The Settings search field.
     *
     * <p>One compact row rather than another card. Search is a way through the page, not a section
     * of it, and giving it a full card with a heading would make the screen longer for everybody in
     * order to make it shorter for the person who types in it.
     */
    private View buildSearchField() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 8), 0);
        row.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 62), 16, this));

        ImageButton icon = new ImageButton(this);
        icon.setImageResource(R.drawable.ic_search);
        icon.setBackground(null);
        icon.setColorFilter(UiKit.MUTED);
        icon.setPadding(0, 0, UiKit.dp(this, 10), 0);
        // Decoration beside a labelled field: a screen reader reads the field, not the picture.
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setClickable(false);
        icon.setFocusable(false);
        row.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(this, 30), UiKit.dp(this, 22)));

        // Orbit's own field, with its surface removed: the row around it is already the outlined
        // box, and a second one inside it would read as a field inside a field.
        EditText field = UiKit.input(this, "Search settings", false);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setMinHeight(0);
        field.setSingleLine(true);
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setContentDescription("Search settings");
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                showSearchResults(s == null ? "" : s.toString());
            }
        });
        searchField = field;
        row.addView(field, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton clear = new ImageButton(this);
        clear.setImageResource(R.drawable.ic_close);
        clear.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 18, this));
        clear.setColorFilter(UiKit.MUTED);
        clear.setContentDescription("Clear settings search");
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> {
            field.setText("");
            UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
        });
        searchClear = clear;
        row.addView(clear, new LinearLayout.LayoutParams(UiKit.dp(this, 36), UiKit.dp(this, 36)));
        return row;
    }

    private LinearLayout.LayoutParams searchFieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        lp.setMargins(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 12));
        return lp;
    }

    /**
     * Draws the results for what has been typed, and puts the category cards aside while it does.
     *
     * <p>Results replace the categories rather than sitting above them. Leaving eleven cards below
     * a list of three answers would mean the person who searched still has to scroll past
     * everything they were trying to skip, which is the problem search exists to solve.
     *
     * <p>Entirely local and synchronous. The index is forty entries in memory, so this runs on
     * every keystroke without a debounce, a thread, or a request to anything.
     */
    private void showSearchResults(String query) {
        if (searchResults == null || categoryList == null) return;
        String text = query == null ? "" : query.trim();
        if (searchClear != null) {
            searchClear.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        searchResults.removeAllViews();
        if (text.isEmpty()) {
            searchResults.setVisibility(View.GONE);
            categoryList.setVisibility(View.VISIBLE);
            return;
        }
        categoryList.setVisibility(View.GONE);
        searchResults.setVisibility(View.VISIBLE);

        List<SettingsSearchIndex.Entry> matches = SettingsSearchIndex.search(text);
        if (matches.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, "No settings found", 16, UiKit.TEXT, true));
            TextView hint = UiKit.text(this,
                    "Try a shorter word, or the name of the thing you want to change.",
                    13, UiKit.MUTED, false);
            hint.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(hint);
            searchResults.addView(empty, categoryLp());
            return;
        }
        for (SettingsSearchIndex.Entry entry : matches) {
            searchResults.addView(searchResultRow(entry), categoryLp());
        }
    }

    /** One result: the control's own words, and which part of Settings it lives in. */
    private View searchResultRow(SettingsSearchIndex.Entry entry) {
        LinearLayout row = card();
        row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 13), UiKit.dp(this, 16), UiKit.dp(this, 13));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 20, this));
        row.addView(UiKit.text(this, entry.title, 15, UiKit.TEXT, true));
        TextView where = UiKit.text(this, entry.subtitle(), 12, UiKit.MUTED, false);
        where.setPadding(0, UiKit.dp(this, 4), 0, 0);
        row.addView(where);
        if (!entry.description.isEmpty()) {
            TextView what = UiKit.text(this, entry.description, 12, UiKit.MUTED, false);
            what.setLineSpacing(0, 1.1f);
            what.setPadding(0, UiKit.dp(this, 4), 0, 0);
            row.addView(what);
        }
        row.setContentDescription(entry.title + ", in " + entry.subtitle());
        row.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            openSearchResult(entry);
        });
        UiKit.pressScale(row);
        return row;
    }

    /**
     * Takes the user to the control they picked.
     *
     * <p>Through the same {@link #openSettingsSection} every category card uses, plus the control
     * key when there is one. A result whose setting lives on a screen of its own - Routines,
     * Extensions, updates - opens that screen, which is exactly where the user was heading; there
     * is no row to pulse because there is no row.
     */
    private void openSearchResult(SettingsSearchIndex.Entry entry) {
        if (entry == null) return;
        if (searchField != null) {
            // Cleared before leaving, so coming back with Back shows the settings page rather than
            // a stale result list for a control the user has already reached.
            searchField.setText("");
            searchField.clearFocus();
        }
        openSettingsSection(entry.section, entry.controlKey);
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
        openSettingsSection(section, "");
    }

    private void openSettingsSection(String section, String focusKey) {
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
        if (focusKey != null && !focusKey.isEmpty()) intent.putExtra(EXTRA_FOCUS, focusKey);
        startActivity(intent);
    }

    /** The shared Back navigation this page installed. For tests. */
    OrbitPredictiveBack navigationForTest() { return navigation; }

    /** Opens a section exactly as tapping its card does. For tests. */
    void openSectionForTest(String section) { openSettingsSection(section); }

    /** For tests: the results currently drawn for a query, without touching the field. */
    java.util.List<String> searchResultTitlesForTest(String query) {
        java.util.List<String> titles = new java.util.ArrayList<>();
        for (SettingsSearchIndex.Entry entry : SettingsSearchIndex.search(query)) {
            titles.add(entry.title);
        }
        return titles;
    }

    /** For tests: type into the hub's search field exactly as a person would. */
    void typeSearchForTest(String query) {
        if (searchField != null) searchField.setText(query == null ? "" : query);
    }

    /** For tests: how many result rows are on screen. */
    int searchResultCountForTest() {
        return searchResults == null || searchResults.getVisibility() != View.VISIBLE
                ? 0 : searchResults.getChildCount();
    }

    /** For tests: the result rows themselves, so one can be chosen the way a person would. */
    java.util.List<View> searchResultsForTest() {
        java.util.List<View> rows = new java.util.ArrayList<>();
        if (searchResults == null) return rows;
        for (int i = 0; i < searchResults.getChildCount(); i++) {
            rows.add(searchResults.getChildAt(i));
        }
        return rows;
    }

    /** For tests: whether the category cards are currently put aside. */
    boolean categoriesHiddenForTest() {
        return categoryList != null && categoryList.getVisibility() != View.VISIBLE;
    }

    /** For tests: whether a detail page found and settled on the control it was opened for. */
    boolean focusedControlForTest(String key) {
        View target = searchTargets.get(key);
        return target != null && target.isAttachedToWindow();
    }

    /** For tests: which control keys this page registered. */
    java.util.Set<String> searchTargetKeysForTest() { return searchTargets.keySet(); }

    private String normalizeSection(String section) {
        if (SECTION_ASSISTANT.equals(section) || SECTION_AI.equals(section) ||
                SECTION_VOICE.equals(section) || SECTION_DATA.equals(section) || SECTION_CONVERSATIONS.equals(section) ||
                SECTION_APPEARANCE.equals(section) || SECTION_ADVANCED.equals(section) ||
                SECTION_DECK.equals(section)) return section;
        return "";
    }

    private String sectionDisplayName(String section) {
        if (SECTION_ASSISTANT.equals(section)) return "Assistant setup";
        if (SECTION_AI.equals(section)) return "AI & account";
        if (SECTION_VOICE.equals(section)) return "Voice, context & permissions";
        if (SECTION_DATA.equals(section)) return "Personalization & data";
        if (SECTION_DECK.equals(section)) return "Orbit Deck";
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
        if (SECTION_DECK.equals(section)) return "Choose how Orbit Deck is reached and whether it may suggest shortcuts.";
        if (SECTION_CONVERSATIONS.equals(section)) return "Choose local chat storage and background completion behavior.";
        if (SECTION_APPEARANCE.equals(section)) return "Design Orbit's theme, then set typography and tactile feedback.";
        if (SECTION_ADVANCED.equals(section)) return "Inspect local diagnostics and developer troubleshooting information.";
        return "Orbit settings.";
    }

    private String sectionSubtitle(String section) {
        if (SECTION_ASSISTANT.equals(section)) return "Core setup";
        if (SECTION_AI.equals(section)) return "Models & access";
        if (SECTION_VOICE.equals(section)) return "Input & awareness";
        if (SECTION_DATA.equals(section)) return "Local context";
        if (SECTION_DECK.equals(section)) return "Shortcuts";
        if (SECTION_CONVERSATIONS.equals(section)) return "Chat behavior";
        if (SECTION_APPEARANCE.equals(section)) return "Style & feedback";
        if (SECTION_ADVANCED.equals(section)) return "Developer tools";
        return "Orbit";
    }

    private View buildDetailContent() {
        ScrollView scroll = new ScrollView(this);
        settingsScroll = scroll;
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);

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
        back.setOnClickListener(v -> navigation.performBack());
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
        page.addView(target(SettingsSearchIndex.KEY_DEFAULT_ASSISTANT, setupCard));

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
        page.addView(target(SettingsSearchIndex.KEY_QUICK_TILES, quickCard));

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
        page.addView(target(SettingsSearchIndex.KEY_WIDGETS, widgetCard));

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
                "Open Orbit's dedicated managers for saved places, remembered information, your Vault, per-app behavior and notification context.",
                13, UiKit.MUTED, false);
        personalDataHelp.setPadding(0, 0, 0, UiKit.dp(this, 12));
        personalDataCard.addView(personalDataHelp);

        Button managePlaces = secondaryButton("Manage saved places");
        managePlaces.setOnClickListener(v -> startActivity(new Intent(this, SavedPlacesActivity.class)));
        target(SettingsSearchIndex.KEY_PLACES, managePlaces);
        personalDataCard.addView(managePlaces, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button manageMemory = secondaryButton("Manage Orbit Memory");
        manageMemory.setOnClickListener(v -> startActivity(new Intent(this, MemoryActivity.class)));
        LinearLayout.LayoutParams manageMemoryLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageMemoryLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(target(SettingsSearchIndex.KEY_MEMORY, manageMemory), manageMemoryLp);

        Button manageApps = secondaryButton("Manage app profiles");
        manageApps.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        LinearLayout.LayoutParams manageAppsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageAppsLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(target(SettingsSearchIndex.KEY_APP_PROFILES, manageApps), manageAppsLp);

        Button manageNotifications = secondaryButton("Manage notification intelligence");
        manageNotifications.setOnClickListener(v -> startActivity(new Intent(this, NotificationsActivity.class)));
        LinearLayout.LayoutParams manageNotificationsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        manageNotificationsLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        personalDataCard.addView(target(SettingsSearchIndex.KEY_NOTIFICATIONS, manageNotifications), manageNotificationsLp);
        // Added with the other Personalization & data sections below.

        // Orbit Vault gets its own small section rather than a row inside Personalization, because
        // Beta 2 gives it three genuinely different controls: whether the feature exists, a way in,
        // and an erase. Those are the answers to three different questions, and burying the erase
        // among the "Manage ..." rows would put a permanent action next to six reversible ones.
        TextView vaultSection = sectionTitle("ORBIT VAULT", "data");
        LinearLayout vaultCard = card();
        tagSectionCard(vaultCard, "data");
        buildVaultCard(vaultCard);
        // Added with the other Personalization & data sections below.

        TextView backupSection = sectionTitle("BACKUP & RESTORE", "data");
        LinearLayout backupCard = card();
        tagSectionCard(backupCard, "data");
        TextView backupHelp = UiKit.text(this,
                "Orbit backups stay in the file you choose. They include local chats, Memory, your Vault, Routines, safe extension manifests, reminders, saved places and personalization. Sensitive account credentials are not included. Android permissions and default-assistant status are not included and may need to be granted again after reinstalling Orbit.",
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
        // Deliberately not selectorLp(): that is a fixed 54dp meant for one-line dropdown rows,
        // and this row holds a title plus a status line that may wrap. The row measures itself.
        LinearLayout.LayoutParams providersLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        providersLp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        accountCard.addView(providersRow, providersLp);
        providerDetails = new LinearLayout(this);
        providerDetails.setOrientation(LinearLayout.VERTICAL);
        providerDetails.setPadding(0, UiKit.dp(this, 13), 0, 0);
        populateProviderDetails(providerDetails);
        accountCard.addView(providerDetails);
        page.addView(target(SettingsSearchIndex.KEY_PROVIDER, accountCard));
        // The provider block and the ChatGPT sign-in inside it are two things people look for
        // separately, so both are reachable; the second lands inside the first, which is correct.
        if (providerDetails != null) target(SettingsSearchIndex.KEY_CHATGPT_ACCOUNT, providerDetails);

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
        voiceCard.addView(target(SettingsSearchIndex.KEY_SCREEN_TEXT,
                toggle("Allow Orbit to read current-screen text", Prefs.SCREEN_CONTEXT, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_SCREENSHOTS,
                toggle("Allow Orbit to receive screenshots",
                "Visual context, previews, and screen-region selection.",
                Prefs.SCREENSHOT, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_ATTACH_SCREEN,
                toggle("Attach current screen by default", Prefs.ATTACH_SCREEN_BY_DEFAULT, false)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_CONTEXT_CHIPS,
                toggle("Show contextual screen-action chips when attached", Prefs.CONTEXT_CHIPS, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_SPEAK,
                toggle("Speak replies to voice requests", Prefs.SPEAK, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_VOICE_PAUSE,
                toggle("Allow longer pauses while speaking",
                "Voice Beta gives you more time to pause and think before Orbit decides you are finished. Tap the mic again if you want to finish sooner.",
                Prefs.VOICE_PAUSE_FRIENDLY, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_AUTO_LISTEN,
                toggle("Start listening when overlay opens",
                "Automatically activate the microphone when the assistant overlay appears.",
                Prefs.AUTO_LISTEN_ON_OPEN, false)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_FOLLOW_UPS,
                toggle("Hands-free voice follow-ups",
                "Reopen the microphone after Orbit speaks.",
                Prefs.AUTO_LISTEN, false)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_SMART_FOLLOW_UPS,
                toggle("Smart follow-ups",
                "Only reopen the microphone when Orbit is waiting for your answer.",
                Prefs.SMART_FOLLOW_UPS, true)));
        voiceCard.addView(target(SettingsSearchIndex.KEY_KEYBOARD_AWARE,
                toggle("Keyboard-aware assistant invocation", Prefs.KEYBOARD_AWARE_ASSISTANT, true)));

        Button capabilities = secondaryButton("Permissions & capabilities");
        capabilities.setOnClickListener(v -> startActivity(new Intent(this, CapabilitiesActivity.class)));
        LinearLayout.LayoutParams capabilitiesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        capabilitiesLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        voiceCard.addView(target(SettingsSearchIndex.KEY_PERMISSIONS, capabilities), capabilitiesLp);
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
        page.addView(target(SettingsSearchIndex.KEY_WEATHER, weatherCard));
        page.addView(gallerySection);
        page.addView(target(SettingsSearchIndex.KEY_GALLERY, galleryCard));
        page.addView(personalizationSection);
        page.addView(personalDataCard);
        page.addView(vaultSection);
        page.addView(target(SettingsSearchIndex.KEY_VAULT, vaultCard));
        page.addView(remindersSection);
        page.addView(target(SettingsSearchIndex.KEY_REMINDERS, remindersCard));
        page.addView(backupSection);
        page.addView(target(SettingsSearchIndex.KEY_BACKUP, backupCard));

        page.addView(sectionTitle("INTELLIGENCE", "intelligence"));
        LinearLayout aiCard = card();
        tagSectionCard(aiCard, "intelligence");
        TextView modeLabel = label("Default mode for new chats");
        String[] modeLabels = {"Auto", "Fast", "Balanced", "Deep", "Custom"};
        String modeValue = Prefs.intelligenceMode(this);
        int modePos = Prefs.MODE_AUTO.equals(modeValue) ? 0 : Prefs.MODE_FAST.equals(modeValue) ? 1 :
                Prefs.MODE_DEEP.equals(modeValue) ? 3 : Prefs.MODE_CUSTOM.equals(modeValue) ? 4 : 2;
        LinearLayout mode = menuSelector(modeLabels, modePos, (pos, selectedLabel) -> {
            String value = pos == 0 ? Prefs.MODE_AUTO : pos == 1 ? Prefs.MODE_FAST :
                    pos == 3 ? Prefs.MODE_DEEP : pos == 4 ? Prefs.MODE_CUSTOM : Prefs.MODE_BALANCED;
            Prefs.get(this).edit().putString(Prefs.INTELLIGENCE_MODE, value).apply();
        });
        aiCard.addView(controlGroup(SettingsSearchIndex.KEY_MODE, modeLabel, mode, selectorLp()));
        TextView modeHelp = UiKit.text(this,
                "Fast favors Luna, Balanced favors Terra, and Deep favors Sol. Auto chooses per request. This is the default for new chats; changing the mode inside a conversation now stays with that chat.",
                12, UiKit.MUTED, false);
        modeHelp.setPadding(0, 0, 0, UiKit.dp(this, 10));
        aiCard.addView(modeHelp);

        TextView modelLabelView = label("Custom model");
        // Which models are offered follows the active provider, because a picker entry is a promise
        // that choosing it works. Astra has been validated against the account-backed ChatGPT path
        // and nowhere else, so only that provider offers it.
        String[] modelLabels = OrbitModelCatalog.modelsFor(Prefs.provider(this));
        int modelPos = indexOf(modelLabels, Prefs.model(this));
        LinearLayout model = menuSelector(modelLabels, modelPos,
                (pos, selectedLabel) -> Prefs.get(this).edit().putString(Prefs.MODEL, selectedLabel).apply());
        aiCard.addView(controlGroup(SettingsSearchIndex.KEY_MODEL, modelLabelView, model, selectorLp()));
        if (OrbitModelCatalog.supports(Prefs.provider(this), OrbitModelCatalog.ASTRA)) {
            TextView astraNote = UiKit.text(this,
                    OrbitModelCatalog.settingsLabel(OrbitModelCatalog.ASTRA)
                            + ": most capable. Availability depends on your ChatGPT/Codex account, "
                            + "and Astra uses its allowance faster. Auto, Fast, Balanced and Deep "
                            + "are unchanged and never route to Astra on their own.",
                    12, UiKit.MUTED, false);
            astraNote.setLineSpacing(0, 1.12f);
            astraNote.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 4));
            aiCard.addView(astraNote);
        }

        TextView reasoningLabel = label("Custom reasoning");
        String[] reasoningLabels = {"none", "low", "medium", "high", "xhigh", "max"};
        int rPos = indexOf(reasoningLabels, Prefs.reasoning(this));
        LinearLayout reasoning = menuSelector(reasoningLabels, rPos,
                (pos, selectedLabel) -> Prefs.get(this).edit().putString(Prefs.REASONING, selectedLabel).apply());
        aiCard.addView(controlGroup(SettingsSearchIndex.KEY_REASONING, reasoningLabel, reasoning, selectorLp()));
        TextView cost = UiKit.text(this, "ChatGPT-account mode uses your account-backed allowance. Orbit never silently switches to the separately metered API-relay fallback.", 12, UiKit.MUTED, false);
        cost.setPadding(0, UiKit.dp(this, 8), 0, 0);
        aiCard.addView(cost);
        // Sits with Intelligence because it is about how a request behaves while it runs, and it
        // takes effect on the next request with no restart and no extra setup step.
        // On unless the user says otherwise, and the switch stays so they always can. The default
        // comes from Prefs rather than a literal here, so the two cannot drift apart.
        aiCard.addView(target(SettingsSearchIndex.KEY_THINKING_UPDATES,
                toggle("Thinking updates",
                "Show brief updates about what Orbit is working on while it prepares an answer. "
                        + "Never shows private chain-of-thought.",
                Prefs.THINKING_UPDATES, Prefs.THINKING_UPDATES_DEFAULT)));
        // Sits with Intelligence because it is about what an answer contains. The copy names the
        // one thing somebody would want to know before leaving it on: which addresses Orbit
        // contacts, and that it is only the pages the answer already used.
        aiCard.addView(target(SettingsSearchIndex.KEY_RICH_ANSWERS,
                toggle("Sourced images in answers",
                "Show a useful picture inside an answer when one genuinely helps. Orbit only reads "
                        + "the preview image a page it already cited declares about itself, and "
                        + "never sends your chat, prompt or Vault anywhere to do it.",
                Prefs.RICH_ANSWERS, Prefs.RICH_ANSWERS_DEFAULT)));
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
        page.addView(target(SettingsSearchIndex.KEY_DIAGNOSTICS, diagnosticsCard));

        page.addView(sectionTitle("ORBIT DECK", "deck"));
        LinearLayout deckCard = card();
        tagSectionCard(deckCard, "deck");
        deckCard.addView(toggle("Show Deck shortcut on Chats",
                "Puts the Deck control in the Chats header. Deck stays reachable from here either way.",
                Prefs.DECK_SHORTCUT, true));
        deckCard.addView(toggle("Smart suggestions",
                "Show relevant shortcuts from recent Orbit context.",
                Prefs.DECK_SUGGESTIONS, true));
        TextView deckNote = UiKit.text(this,
                "Deck arranges shortcuts to things Orbit already does. Suggestions are worked out on "
                        + "this phone from context Orbit already has, and opening Deck never sends "
                        + "anything to your AI provider. Which tiles you keep, and how they are "
                        + "arranged, is edited in Deck itself.",
                12, UiKit.MUTED, false);
        deckNote.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 12));
        deckCard.addView(deckNote);
        Button openDeck = secondaryButton("Open Orbit Deck");
        deckCard.addView(openDeck, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        openDeck.setOnClickListener(v -> startActivity(new Intent(this, DeckActivity.class)));
        page.addView(target(SettingsSearchIndex.KEY_DECK, deckCard));

        page.addView(sectionTitle("CONVERSATIONS", "conversations"));
        LinearLayout conversationCard = card();
        tagSectionCard(conversationCard, "conversations");
        conversationCard.addView(target(SettingsSearchIndex.KEY_NEW_CHAT,
                toggle("Start a new chat each time Orbit opens", Prefs.NEW_CHAT_ON_OPEN, true)));
        conversationCard.addView(target(SettingsSearchIndex.KEY_HISTORY,
                toggle("Save recent chats on this device", Prefs.HISTORY_ENABLED, true)));
        conversationCard.addView(target(SettingsSearchIndex.KEY_THUMBNAILS,
                toggle("Save screen attachment thumbnails in chat history", Prefs.SAVE_SCREEN_THUMBNAILS, false)));
        conversationCard.addView(target(SettingsSearchIndex.KEY_STOP_BUTTON,
                toggle("Show Stop button while replying",
                "Replace Send with Stop while Orbit is generating a reply.",
                Prefs.SHOW_STOP_BUTTON, true)));
        conversationCard.addView(notificationToggle());
        TextView conversationNote = UiKit.text(this,
                "Orbit keeps up to 100 recent chats locally when history is enabled. The full Orbit app can search, rename, reopen, and delete them. Screen thumbnails are stored only in Orbit's private app storage when that option is enabled.",
                12, UiKit.MUTED, false);
        conversationNote.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 10));
        conversationCard.addView(conversationNote);
        Button clearHistory = secondaryButton("Clear Orbit conversation history");
        conversationCard.addView(target(SettingsSearchIndex.KEY_CLEAR_HISTORY, clearHistory),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
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

        // Look & Feel has one color destination, and one typography-and-feedback card beneath it.
        // Until this release it had both: Theme Studio at the top, and directly under it a second
        // set of controls for accent, AMOLED and the two bubble colors. They wrote the same
        // preferences, so they could not contradict each other, but they could and did leave a
        // person wondering which one was the real place to change a color. There is one answer
        // now, and the controls that used to ask the question are gone rather than duplicated.
        page.addView(sectionTitle("LOOK & FEEL", "appearance"));
        LinearLayout studioCard = card();
        tagSectionCard(studioCard, "appearance");
        studioCard.addView(themeStudioRow());
        page.addView(target(SettingsSearchIndex.KEY_THEME_STUDIO, studioCard));

        // A heading of its own rather than a bare second card. It says what the card below is for,
        // and it carries the same spacing every other section break on this page has, which is what
        // stops the two surfaces from appearing fused into one.
        page.addView(sectionTitle("TYPOGRAPHY & FEEDBACK", "appearance"));
        LinearLayout styleCard = card();
        tagSectionCard(styleCard, "appearance");

        styleCard.addView(controlGroup(SettingsSearchIndex.KEY_FONT,
                label("App font"), fontSelector(), null));
        TextView fontNote = UiKit.text(this,
                "Orbit Default is the current app font. Times New Roman uses Android's built-in serif family for a similar classic look without adding a font file to Orbit.",
                12, UiKit.MUTED, false);
        fontNote.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
        styleCard.addView(fontNote);

        TextView chatSizeLabel = label("Chat text size");
        chatSizeLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 12), 0, UiKit.dp(this, 6));
        styleCard.addView(controlGroup(SettingsSearchIndex.KEY_CHAT_TEXT_SIZE,
                chatSizeLabel, chatTextSizeSelector(), null));
        TextView chatSizeNote = UiKit.text(this,
                "Changes conversation content only, including rich Markdown in full chat and the Side-button assistant.",
                12, UiKit.MUTED, false);
        chatSizeNote.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 14));
        styleCard.addView(chatSizeNote);

        // Haptics stays here on purpose. It is not a color, it is not saved in a theme, and
        // applying a Theme Studio preset must never silently change how the phone feels.
        styleCard.addView(target(SettingsSearchIndex.KEY_HAPTICS,
                toggle("Haptic feedback", Prefs.HAPTICS, true)));
        TextView hapticNote = UiKit.text(this,
                "Uses light tactile ticks for Orbit controls and Settings interactions. Turn this off to disable those haptics.",
                12, UiKit.MUTED, false);
        hapticNote.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 1), 0, UiKit.dp(this, 6));
        styleCard.addView(hapticNote);

        TextView advancedLabel = label("Advanced");
        advancedLabel.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 20), 0, UiKit.dp(this, 6));
        styleCard.addView(advancedLabel);
        styleCard.addView(controlGroup(SettingsSearchIndex.KEY_PAGE_TRANSITIONS,
                label("Page transitions"), pageTransitionSelector(), null));
        TextView transitionNote = UiKit.text(this,
                "How full-screen Orbit pages move when you open and leave them. Slide brings a page in from the right and sends it back out on the way back. Fade & settle uses a short fade with a small upward settle. None changes pages immediately. Android's own animation settings still apply.",
                12, UiKit.MUTED, false);
        transitionNote.setPadding(0, UiKit.dp(this, 8), 0, 0);
        styleCard.addView(transitionNote);

        page.addView(styleCard);

        page.addView(sectionTitle("GESTURES", "appearance"));
        LinearLayout gestureCard = card();
        tagSectionCard(gestureCard, "appearance");
        gestureCard.addView(target(SettingsSearchIndex.KEY_SWIPE_BACK,
                toggle("Swipe to go back",
                "Use Orbit's interactive edge gesture when returning to the previous screen. The "
                        + "page follows your finger and the screen you came from appears behind it, "
                        + "across conversations, Settings and Orbit's other pages. Turn this off to "
                        + "use Orbit's page transition instead. Back itself always works either "
                        + "way, from the gesture, the navigation buttons, and every Back control.",
                Prefs.ENHANCED_CHAT_BACK, true)));
        gestureCard.addView(target(SettingsSearchIndex.KEY_CHAT_SWIPE,
                toggle("Chat swipe actions",
                "Swipe a chat left to delete it, or right to pin and unpin it. Deleting always "
                        + "offers Undo. With this off, chats scroll and open as normal and both "
                        + "actions stay on the chat's own menu.",
                Prefs.CHAT_SWIPE_ACTIONS, true)));
        page.addView(gestureCard);

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

    /**
     * The connected and signed-out states show different actions, not just different text: an
     * already-connected account never presents a bright "Sign in" as the dominant action, and a
     * signed-out one never offers "Sign out".
     */
    private void updateChatGptStatus() {
        if (chatGptStatus == null) return;
        ChatGptAuth.AccountInfo info = ChatGptAuth.getAccountInfo(this);
        boolean connected = info != null;
        if (chatGptSignIn != null) chatGptSignIn.setVisibility(connected ? View.GONE : View.VISIBLE);
        if (chatGptSignOut != null) chatGptSignOut.setVisibility(connected ? View.VISIBLE : View.GONE);
        if (chatGptHelp != null) {
            chatGptHelp.setText(connected
                    ? "Orbit is using this account's ChatGPT/Codex allowance. No API key is needed."
                    : "Recommended: sign in with your normal ChatGPT account using OpenAI's Codex device-code flow. This path uses your account-backed Codex/ChatGPT allowance and does not require an OpenAI API key.");
        }
        if (!connected) {
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
        chatGptHelp = null;
        chatGptSignIn = null;
        chatGptSignOut = null;
        // The effective provider, not the raw stored one: if a stored Orbit Local selection lost
        // its model, requests already fall back to ChatGPT, and this block must describe the
        // provider that actually answers.
        providerDetailsFor = AiProviders.active(this).id();
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
        chatGptHelp = UiKit.text(this, "", 13, UiKit.MUTED, false);
        chatGptHelp.setLineSpacing(0, 1.13f);
        chatGptHelp.setPadding(0, UiKit.dp(this, 7), 0, UiKit.dp(this, 13));
        container.addView(chatGptHelp);

        chatGptSignIn = primaryButton("Sign in with ChatGPT");
        chatGptSignIn.setOnClickListener(v -> startChatGptLogin());
        container.addView(chatGptSignIn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        // Signing out is disruptive rather than dangerous, so it stays a restrained outlined
        // action with a confirmation, never a bright primary competing with the connected state.
        chatGptSignOut = dangerOutlineButton("Sign out of ChatGPT");
        LinearLayout.LayoutParams signOutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        signOutLp.setMargins(0, UiKit.dp(this, 8), 0, 0);
        container.addView(chatGptSignOut, signOutLp);
        chatGptSignOut.setOnClickListener(v -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("Sign out of ChatGPT?")
                    .setMessage("Orbit forgets this ChatGPT connection on this phone. You can sign in again at any time.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Sign out", (d, w) -> {
                        ChatGptAuth.logout(this);
                        updateChatGptStatus();
                        Toast.makeText(this, "Signed out of ChatGPT in Orbit", Toast.LENGTH_SHORT).show();
                    })
                    .create();
            styleOrbitDialog(dialog, true);
            dialog.show();
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
                && !providerDetailsFor.equals(AiProviders.active(this).id())) {
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

    /**
     * Registers one view as the thing a search result for {@code key} should land on.
     *
     * <p>Returns the view so it can be wrapped around an {@code addView} argument at the point the
     * control is created, which is the only place that knows which control it is. Keeping the map
     * beside the tree rather than inside it means a lookup is a hash rather than a walk, and a
     * rebuild starts from an empty map rather than from stale entries.
     */
    private View target(String key, View view) {
        if (view != null && key != null && !key.isEmpty()) searchTargets.put(key, view);
        return view;
    }

    /**
     * Groups a label and its control so a search result lands on both.
     *
     * <p>Several Orbit settings are a caption above a selector, and neither half is the setting on
     * its own: scrolling to "Custom model" alone puts the control it names just below the fold, and
     * scrolling to the selector alone hides the words that say what it is. One container is the
     * whole setting, so that is what a result targets and what pulses.
     */
    private View controlGroup(String key, View labelView, View control,
                              LinearLayout.LayoutParams controlLp) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        if (labelView != null) group.addView(labelView);
        // A null lp means the control already carries the layout it wants, which is true of the
        // selectors that build their own; passing null to addView would throw rather than default.
        if (control != null) {
            if (controlLp == null) group.addView(control);
            else group.addView(control, controlLp);
        }
        target(key, group);
        return group;
    }

    /**
     * Scrolls to the control this page was opened for and pulses it, once.
     *
     * <p>Posted rather than run inline, because the page has just been built and nothing has been
     * laid out yet - a scroll computed against zero heights lands at the top, which is exactly the
     * "took me to the section" outcome this feature exists to replace. The key is consumed on the
     * first successful settle, so a rotation or an appearance rebuild does not re-pulse a control
     * the user has already been shown.
     */
    private void focusPendingControl() {
        if (pendingFocusKey.isEmpty() || settingsScroll == null) return;
        View target = searchTargets.get(pendingFocusKey);
        if (target == null) { pendingFocusKey = ""; return; }
        final ScrollView scroll = settingsScroll;
        scroll.post(() -> {
            int top = 0;
            View walk = target;
            while (walk != null && walk != scroll && walk.getParent() instanceof View) {
                top += walk.getTop();
                walk = (View) walk.getParent();
            }
            // A little above the control rather than exactly at it: a row flush against the top
            // edge of the viewport reads as the page having scrolled past it.
            scroll.smoothScrollTo(0, Math.max(0, top - UiKit.dp(this, 72)));
            UiKit.enterContent(target);
            pulse(target);
        });
        pendingFocusKey = "";
    }

    /**
     * A brief accent wash over one control, so the eye lands where the scroll did.
     *
     * <p>Drawn as the view's foreground and animated by alpha, so nothing is resized, no text
     * reflows and no neighbouring control moves. It respects the system animation scale through
     * {@link UiKit#animationsEnabled()}: a device set to reduced motion gets the scroll and no
     * flashing, which is still an answer to "where is it".
     */
    private void pulse(View target) {
        if (target == null) return;
        if (!UiKit.animationsEnabled()) return;
        final android.graphics.drawable.GradientDrawable wash =
                UiKit.rounded(UiKit.withAlpha(UiKit.accent(this), 90), 18, this);
        target.setForeground(wash);
        wash.setAlpha(0);
        android.animation.ValueAnimator animator =
                android.animation.ValueAnimator.ofInt(0, 255, 0, 200, 0);
        animator.setDuration(900L);
        animator.addUpdateListener(a -> wash.setAlpha((Integer) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                target.setForeground(null);
            }
        });
        animator.start();
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
        if (SECTION_DECK.equals(section)) return "deck".equals(key);
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

    /** Orbit's restrained destructive treatment: outlined, never a filled bright surface. */
    private Button dangerOutlineButton(String text) {
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

    /** What the Vault's switch and its erase control say, in the words the screen shows. */
    static final String VAULT_TOGGLE_LABEL = "Use Orbit Vault";
    static final String VAULT_TOGGLE_HELP =
            "Save text, links, images and useful Orbit answers for later. Everything stays on this "
                    + "device, and Orbit uses a saved item only when you choose it yourself.";
    static final String VAULT_OPEN_LABEL = "Open Orbit Vault";
    static final String VAULT_DELETE_LABEL = "Delete Vault data";
    static final String VAULT_DELETE_TITLE = "Delete all Vault data?";
    static final String VAULT_DELETE_MESSAGE =
            "All items saved in Orbit Vault and their local media will be removed from this "
                    + "device. This cannot be undone.\n\nYour chats, Orbit Memory, your providers "
                    + "and the rest of your settings are not affected.";

    /**
     * The Vault's three controls, and the difference between two of them.
     *
     * <p>Switching the Vault off and erasing what is in it are separate on purpose, because they
     * are separate intentions with very different costs. Off is a preference: the Vault leaves the
     * app, nothing is deleted, and one tap brings all of it back. Delete is permanent, so it is its
     * own control with Orbit's destructive treatment and its own confirmation - and it stays
     * available while the Vault is off, because somebody who has stopped using the feature should
     * not have to switch it back on in order to be rid of the contents.
     */
    private void buildVaultCard(LinearLayout card) {
        TextView help = UiKit.text(this, VAULT_TOGGLE_HELP, 13, UiKit.MUTED, false);
        help.setPadding(0, 0, 0, UiKit.dp(this, 12));
        card.addView(help);

        Button openVault = secondaryButton(VAULT_OPEN_LABEL);
        openVault.setOnClickListener(v -> startActivity(new Intent(this, OrbitVaultActivity.class)));
        LinearLayout.LayoutParams openVaultLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        openVaultLp.setMargins(0, UiKit.dp(this, 4), 0, 0);

        OrbitSwitch control = new OrbitSwitch(this);
        control.setChecked(Prefs.vaultEnabled(this), false);
        control.setOnCheckedChangeListener((button, checked) -> {
            Prefs.get(this).edit().putBoolean(Prefs.VAULT_ENABLED, checked).apply();
            // The way in disappears with the feature rather than staying as a control that opens a
            // page saying it is switched off. Corrected here and now, because a switch whose
            // consequences only appear after leaving Settings does not read as connected to it.
            openVault.setVisibility(checked ? View.VISIBLE : View.GONE);
        });
        card.addView(UiKit.switchRow(this, VAULT_TOGGLE_LABEL, null, control));

        openVault.setVisibility(Prefs.vaultEnabled(this) ? View.VISIBLE : View.GONE);
        card.addView(openVault, openVaultLp);

        Button deleteVault = dangerOutlineButton(VAULT_DELETE_LABEL);
        deleteVault.setOnClickListener(v -> confirmDeleteVaultData());
        LinearLayout.LayoutParams deleteVaultLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        deleteVaultLp.setMargins(0, UiKit.dp(this, 9), 0, 0);
        card.addView(deleteVault, deleteVaultLp);
    }

    /**
     * Asks before erasing the Vault, and says exactly what is and is not included.
     *
     * <p>Naming what survives is as much a part of an irreversible confirmation as naming what
     * goes. "Delete Vault data" beside a Memory control and a Backup control is precisely the kind
     * of button somebody presses while wondering how much of their Orbit it reaches.
     */
    private void confirmDeleteVaultData() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(VAULT_DELETE_TITLE)
                .setMessage(VAULT_DELETE_MESSAGE)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete all", (d, w) -> {
                    int removed = OrbitVaultStore.deleteAllData(this);
                    Toast.makeText(this, removed == 0
                                    ? "Your Vault was already empty"
                                    : "Deleted " + removed
                                            + (removed == 1 ? " saved item" : " saved items"),
                            Toast.LENGTH_SHORT).show();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
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

    /**
     * The way into Theme Studio, and the only place in Settings that shows Orbit's colors.
     *
     * <p>It used to sit above a second set of color controls that wrote the same preferences. That
     * was defensible while Theme Studio was new and unproven, and stopped being defensible the
     * moment it worked: two editors for one set of values is a question the app asks the user and
     * cannot answer. So the row now states what the theme currently is, shows it, and is the way to
     * change it.
     *
     * <p>The summary is read from {@link OrbitThemeTokens}, the same resolver the app itself draws
     * from, so the strip here is the theme rather than an illustration of it.
     */
    private View themeStudioRow() {
        OrbitTheme active = OrbitThemeStore.active(this);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, active);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 16, this));
        row.setClickable(true);
        row.setFocusable(true);
        UiKit.pressScale(row);

        row.addView(themeSummarySwatch(tokens), new LinearLayout.LayoutParams(
                UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(UiKit.text(this, "Theme Studio", 15, UiKit.TEXT, true));
        TextView status = UiKit.text(this, themeSummaryLine(active), 12, UiKit.MUTED, false);
        status.setPadding(0, UiKit.dp(this, 3), 0, 0);
        text.addView(status);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textLp.leftMargin = UiKit.dp(this, 13);
        row.addView(text, textLp);

        TextView arrow = UiKit.text(this, "›", 24, UiKit.accent(this), false);
        arrow.setPadding(UiKit.dp(this, 10), 0, UiKit.dp(this, 4), 0);
        row.addView(arrow);

        row.setContentDescription("Theme Studio. " + themeSummaryLine(active)
                + ". Opens Orbit's color designer.");
        row.setOnClickListener(v -> startActivity(new Intent(this, ThemeStudioActivity.class)));

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        holder.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView note = UiKit.text(this,
                "Design Orbit's colors with live previews, presets and your own saved themes.",
                12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 10), 0, 0);
        holder.addView(note);
        return holder;
    }

    /** "Nova AMOLED, Nova, AMOLED" - the theme's name, its accent, and whether it is true black. */
    private String themeSummaryLine(OrbitTheme active) {
        String accent = OrbitPalette.labelFor(active.accent);
        String line = active.name;
        if (!accent.equalsIgnoreCase(active.name)) line += " · " + accent;
        return active.amoled ? line + " · AMOLED" : line;
    }

    /**
     * Three bands of the active theme on its own page colour: accent, the raised card step,
     * and the card itself.
     *
     * <p>Every one of these is part of the structural appearance signature, so the strip is refreshed
     * by the same rebuild that refreshes the rest of the page. Showing a bubble colour here would
     * look better and be wrong: bubble colours are deliberately outside that signature, and this
     * swatch would then be the one stale thing on an otherwise current screen.
     */
    private View themeSummarySwatch(OrbitThemeTokens tokens) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setBackground(UiKit.outlined(tokens.background,
                UiKit.withAlpha(UiKit.TEXT, 60), 12, this));
        strip.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4), UiKit.dp(this, 4));
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int[] bands = {tokens.accent, tokens.surface2, tokens.surface};
        for (int i = 0; i < bands.length; i++) {
            View band = new View(this);
            band.setBackground(UiKit.rounded(bands[i], 3, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (i > 0) lp.topMargin = UiKit.dp(this, 3);
            strip.addView(band, lp);
        }
        return strip;
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

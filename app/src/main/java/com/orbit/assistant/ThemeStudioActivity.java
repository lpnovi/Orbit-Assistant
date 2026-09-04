package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Theme Studio: Orbit's visual design surface.
 *
 * <p>The screen has one idea behind it, and everything else follows from it. Editing a theme and
 * <em>being in</em> a theme are different states. Every control here changes a draft; the app keeps
 * drawing what it was drawing until Apply is pressed. That is why a slider does not write a
 * preference, why backing out asks before discarding, and why the preview exists at all — without
 * the draft model the preview would be pointless, because the app itself would already have
 * changed and there would be nothing to preview.
 *
 * <p>What it edits is the appearance Orbit already had. The accent, AMOLED and the two bubble
 * colours are the same preferences Settings has always written; surface and background are the two
 * this release adds. There is no second theme engine underneath this screen — see
 * {@link OrbitThemeStore} for why that mattered more than anything visual here.
 */
public final class ThemeStudioActivity extends Activity {

    /** Wider than this and the preview sits beside the controls instead of above them. */
    private static final int TWO_PANE_WIDTH_DP = 720;
    /** The widest the content is allowed to draw, so a large tablet centres rather than stretches. */
    private static final int MAX_CONTENT_WIDTH_DP = 1040;

    /**
     * The gap between two stacked major cards.
     *
     * <p>One value, used everywhere two surfaces sit above one another, so the Colors card and the
     * Presets card are separated by the same distance on a phone and a tablet and neither pair can
     * be tightened by accident. Settings uses the same figure between its own cards.
     */
    static final int CARD_GAP_DP = 20;

    /**
     * How far the scrolling content clears the fixed action bar at the bottom.
     *
     * <p>Revert and Apply are pinned below the scroll rather than over it, so nothing is hidden;
     * what this prevents is the last preset card finishing hard against the bar with no gap, which
     * on the device read as the gallery having been cut off. The system gesture inset is added
     * beneath the bar by {@code applyActivityInsets}, so this is purely the space Orbit owes its own
     * content and does not need to know how the phone is navigated.
     */
    private static final int SCROLL_BOTTOM_CLEARANCE_DP = 28;

    /** The appearance in force when this screen opened, and the one Cancel returns to. */
    private OrbitTheme applied;
    /** What the preview shows and what Apply would commit. Never written to storage on its own. */
    private OrbitTheme draft;

    private ThemePreviewView preview;
    private LinearLayout warningStrip;
    private LinearLayout colourRows;
    private LinearLayout presetGrid;
    private ScrollView contentScroll;
    private View actionBar;
    private OrbitSwitch amoledSwitch;
    private Button applyButton;
    private Button revertButton;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiKit.syncTheme(this);
        applied = OrbitThemeStore.active(this);
        draft = state == null ? applied : restore(state);

        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        applySystemBarIcons();

        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);

        // An editor: the page may only slide away while there is nothing to lose. Once the draft
        // differs from what is applied, Back reaches this screen's own discard confirmation by the
        // same route the button does, rather than being animated past it.
        navigation = OrbitPredictiveBack.install(this, new OrbitPredictiveBack.Screen() {
            @Override public boolean canNavigate() { return !isDirty(); }
            @Override public void navigateBack() { leave(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(ThemeStudioActivity.class);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    /**
     * Rotation and folding rebuild the layout without recreating the Activity.
     *
     * <p>Handled rather than left to a recreate because the choice between one column and two is
     * made from the width, and because an in-progress draft is the one thing on this screen that
     * must not be at the mercy of a configuration change.
     */
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("draft_accent", draft.accent);
        out.putString("draft_user", draft.userBubble);
        out.putString("draft_assistant", draft.assistantBubble);
        out.putString("draft_surface", draft.surface);
        out.putString("draft_background", draft.background);
        out.putBoolean("draft_amoled", draft.amoled);
        out.putString("draft_id", draft.id);
        out.putString("draft_name", draft.name);
    }

    private OrbitTheme restore(Bundle state) {
        String id = state.getString("draft_id", Prefs.THEME_ID_CUSTOM);
        return new OrbitTheme(id, state.getString("draft_name", ""),
                OrbitTheme.isBuiltInId(id),
                state.getString("draft_accent", OrbitTheme.DYNAMIC),
                state.getString("draft_user", OrbitTheme.CLASSIC),
                state.getString("draft_assistant", OrbitTheme.CLASSIC),
                state.getString("draft_surface", OrbitTheme.CLASSIC),
                state.getString("draft_background", OrbitTheme.CLASSIC),
                state.getBoolean("draft_amoled", false));
    }

    // ---- draft state ----------------------------------------------------------------------------

    /** True while leaving would lose an edit the user has not applied. */
    boolean isDirty() {
        return draft != null && applied != null && !draft.sameColours(applied);
    }

    /**
     * Applies one edit to the draft and re-labels which preset it now is.
     *
     * <p>Re-labelling matters more than it looks. Editing a colour while "Nebula" is selected does
     * not produce a modified Nebula — Nebula is immutable and still exists — it produces a theme of
     * the user's own that started there. Landing exactly back on a preset's colours re-binds to it,
     * so the gallery's selected state always tells the truth about what the draft actually is.
     */
    private void edit(OrbitTheme next) {
        draft = next;
        OrbitTheme match = null;
        for (OrbitTheme preset : OrbitThemeStore.allPresets(this)) {
            if (preset.sameColours(draft)) { match = preset; break; }
        }
        if (match != null) {
            draft = match;
        } else if (draft.builtIn || OrbitTheme.isBuiltInId(draft.id)) {
            draft = OrbitTheme.custom("Your theme", draft.accent, draft.userBubble,
                    draft.assistantBubble, draft.surface, draft.background, draft.amoled);
        } else if (!Prefs.THEME_ID_CUSTOM.equals(draft.id)) {
            draft = new OrbitTheme(Prefs.THEME_ID_CUSTOM, "Your theme", false, draft.accent,
                    draft.userBubble, draft.assistantBubble, draft.surface, draft.background,
                    draft.amoled);
        }
        refreshDraftSurfaces();
    }

    /** Selecting a preset loads it into the draft. It does not become the app's theme. */
    private void selectPreset(OrbitTheme preset) {
        if (preset == null) return;
        draft = preset;
        refreshDraftSurfaces();
    }

    /**
     * Updates everything that depends on the draft, without rebuilding the screen.
     *
     * <p>Preview updates are local drawing and nothing else: no preference is written, no Activity
     * is recreated, and no other screen is touched. That is what makes dragging inside the colour
     * picker cheap enough to follow a finger.
     */
    private void refreshDraftSurfaces() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, draft);
        if (preview != null) preview.render(tokens);
        renderWarnings(tokens);
        renderColourRows();
        renderPresets();
        // Selecting an AMOLED preset has to move the switch too, or the screen shows a true-black
        // preview above a control saying AMOLED is off. setChecked never calls the listener, so
        // this cannot loop back into another edit.
        if (amoledSwitch != null) amoledSwitch.setChecked(draft.amoled);
        updateActionState();
    }

    private void updateActionState() {
        boolean dirty = isDirty();
        if (revertButton != null) {
            revertButton.setEnabled(dirty);
            revertButton.setAlpha(dirty ? 1f : 0.45f);
        }
        if (applyButton != null) {
            applyButton.setText(dirty ? "Apply theme" : "Applied");
            applyButton.setEnabled(dirty);
            applyButton.setAlpha(dirty ? 1f : 0.55f);
            applyButton.setContentDescription(dirty
                    ? "Apply " + draft.name + " to Orbit"
                    : draft.name + " is already applied");
        }
    }

    // ---- actions ---------------------------------------------------------------------------------

    private void applyDraft() {
        if (!isDirty()) return;
        OrbitThemeStore.applyActive(this, draft);
        applied = OrbitThemeStore.active(this);
        draft = applied;
        // The rest of the app picks this up the way it always has: screens that can be sitting
        // underneath this one re-read the appearance signature when they resume, and everything
        // else reads the new canvas when it is next built.
        UiKit.notifyAppearanceChanged(this);
        rebuildInNewTheme();
        Toast.makeText(this, applied.name + " applied", Toast.LENGTH_SHORT).show();
    }

    /** Rebuilds this screen in the theme it just applied, keeping the reading position. */
    private void rebuildInNewTheme() {
        final int scrollY = contentScroll == null ? 0 : contentScroll.getScrollY();
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        applySystemBarIcons();
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        restoreScrollBeforeFirstDraw(scrollY);
    }

    private void restoreScrollBeforeFirstDraw(int scrollY) {
        final ScrollView target = contentScroll;
        if (target == null || scrollY <= 0) return;
        target.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        target.getViewTreeObserver().removeOnPreDrawListener(this);
                        target.scrollTo(0, scrollY);
                        return true;
                    }
                });
    }

    private void revertDraft() {
        if (!isDirty()) return;
        draft = applied;
        refreshDraftSurfaces();
        Toast.makeText(this, "Changes discarded", Toast.LENGTH_SHORT).show();
    }

    private void resetToOrbitDefault() {
        selectPreset(OrbitTheme.orbitDefault());
        Toast.makeText(this, "Orbit Default loaded. Apply to use it.", Toast.LENGTH_SHORT).show();
    }

    private void leave() {
        if (!isDirty()) { finish(); return; }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Discard theme changes?")
                .setMessage("Orbit will keep using " + applied.name + ".")
                .setNegativeButton("Keep editing", null)
                .setPositiveButton("Discard", (d, which) -> finish())
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- content ---------------------------------------------------------------------------------

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        root.setForceDarkAllowed(false);

        int inset = horizontalInset();
        LinearLayout header = buildHeader();
        header.setPadding(UiKit.dp(this, 20) + inset, UiKit.dp(this, 26),
                UiKit.dp(this, 20) + inset, UiKit.dp(this, 6));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = new ThemePreviewView(this);
        warningStrip = new LinearLayout(this);
        warningStrip.setOrientation(LinearLayout.VERTICAL);
        colourRows = new LinearLayout(this);
        colourRows.setOrientation(LinearLayout.VERTICAL);
        presetGrid = new LinearLayout(this);
        presetGrid.setOrientation(LinearLayout.VERTICAL);

        root.addView(twoPane() ? buildWideBody(inset) : buildNarrowBody(inset),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        actionBar = buildActionBar(inset);
        root.addView(actionBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshDraftSurfaces();
        return root;
    }

    /**
     * Phone: one column, preview first.
     *
     * <p>The preview scrolls with the controls rather than being pinned. A pinned preview on a
     * phone leaves roughly one card's worth of room for everything else, which turns the editor
     * into a peephole.
     */
    private View buildNarrowBody(int inset) {
        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(UiKit.dp(this, 20) + inset, UiKit.dp(this, 10),
                UiKit.dp(this, 20) + inset, UiKit.dp(this, SCROLL_BOTTOM_CLEARANCE_DP));
        column.addView(preview, matchWrap(0));
        column.addView(warningStrip, matchWrap(12));
        column.addView(coloursCard(), matchWrap(CARD_GAP_DP));
        column.addView(presetsCard(), matchWrap(CARD_GAP_DP));
        contentScroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return contentScroll;
    }

    /**
     * Tablet: the preview holds still on the left while the controls scroll on the right.
     *
     * <p>The extra width goes into a second pane rather than into wider rows. A colour row stretched
     * across a Tab S9 Plus is a swatch, an acre of nothing, and a chevron.
     */
    private View buildWideBody(int inset) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(UiKit.dp(this, 22) + inset, UiKit.dp(this, 10),
                UiKit.dp(this, 22) + inset, UiKit.dp(this, SCROLL_BOTTOM_CLEARANCE_DP));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(preview, matchWrap(0));
        left.addView(warningStrip, matchWrap(12));
        row.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(coloursCard(), matchWrap(0));
        right.addView(presetsCard(), matchWrap(CARD_GAP_DP));
        contentScroll.addView(right, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.15f);
        scrollLp.leftMargin = UiKit.dp(this, 22);
        row.addView(contentScroll, scrollLp);
        return row;
    }

    private boolean twoPane() {
        return getResources().getConfiguration().screenWidthDp >= TWO_PANE_WIDTH_DP;
    }

    private int horizontalInset() {
        int screen = getResources().getConfiguration().screenWidthDp;
        return UiKit.dp(this, Math.max(0, screen - MAX_CONTENT_WIDTH_DP) / 2f);
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            if (navigation != null) navigation.performBack(); else leave();
        });
        LinearLayout.LayoutParams backLp =
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 12);
        header.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Theme Studio", 26, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Make Orbit yours.", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // File work lives here rather than in a card of its own. Import and Export are two things
        // a person does rarely and deliberately, and a card for them would sit above the presets
        // taking the same weight as the colours, which is not the weight they have.
        ImageButton options = iconButton(R.drawable.ic_more, "Theme options");
        options.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            showThemeOptions(v);
        });
        header.addView(options,
                new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        return header;
    }

    private void showThemeOptions(View anchor) {
        String[] labels = {"Import theme", "Export theme"};
        UiKit.showOrbitMenu(this, anchor, labels, -1, (index, label) -> {
            if (index == 0) chooseThemeFile(); else chooseExportDestination();
        });
    }

    /**
     * The widest the Revert and Apply pair is allowed to be.
     *
     * <p>Only reached on a tablet, where the bar spans the full content width and two buttons
     * stretched across nine hundred points read as a footer rather than as the two things this
     * screen does. Capped and aligned to the end, they sit under the pane whose edits they act on.
     * A phone is never this wide and keeps the full-width bar it already had.
     */
    private static final int ACTION_BAR_MAX_WIDTH_DP = 460;

    private View buildActionBar(int inset) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(twoPane() ? (Gravity.END | Gravity.CENTER_VERTICAL)
                : Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(UiKit.BG);
        bar.setPadding(UiKit.dp(this, 20) + inset, UiKit.dp(this, 12),
                UiKit.dp(this, 20) + inset, UiKit.dp(this, 14));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        revertButton = secondaryButton("Revert");
        revertButton.setContentDescription("Discard unapplied theme changes");
        revertButton.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            revertDraft();
        });
        actions.addView(revertButton, new LinearLayout.LayoutParams(0, UiKit.dp(this, 50), 1f));

        applyButton = primaryButton("Apply theme");
        applyButton.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.CONFIRM);
            applyDraft();
        });
        LinearLayout.LayoutParams applyLp =
                new LinearLayout.LayoutParams(0, UiKit.dp(this, 50), 1.5f);
        applyLp.leftMargin = UiKit.dp(this, 12);
        actions.addView(applyButton, applyLp);

        bar.addView(actions, new LinearLayout.LayoutParams(
                twoPane() ? UiKit.dp(this, ACTION_BAR_MAX_WIDTH_DP)
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ---- colours ----------------------------------------------------------------------------------

    private View coloursCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Colors"));
        card.addView(cardNote("Choose Orbit's main colors. Supporting colors, text, links and "
                + "surfaces adapt automatically."));
        card.addView(colourRows, matchWrap(10));

        amoledSwitch = new OrbitSwitch(this);
        amoledSwitch.setChecked(draft.amoled, false);
        amoledSwitch.setOnCheckedChangeListener((view, checked) -> edit(draft.withAmoled(checked)));
        LinearLayout amoledRow = UiKit.switchRow(this, "True black AMOLED background",
                "Uses pure black for the page. Cards keep their own color so they stay visible.",
                amoledSwitch);
        card.addView(amoledRow, matchWrap(14));
        return card;
    }

    private void renderColourRows() {
        if (colourRows == null) return;
        colourRows.removeAllViews();
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, draft);

        colourRows.addView(colourRow("Accent", "Icons, chips, controls and highlights",
                tokens.accent, draft.accent, accentSummary(),
                anchor -> chooseAccent(anchor, tokens)), matchWrap(0));
        colourRows.addView(colourRow("Your messages", "The bubble your own messages use",
                tokens.userBubble, draft.userBubble, bubbleSummary(draft.userBubble),
                anchor -> chooseBubble(anchor, true, tokens)), matchWrap(10));
        colourRows.addView(colourRow("Orbit's replies", "The bubble Orbit answers in",
                tokens.assistantBubble, draft.assistantBubble, bubbleSummary(draft.assistantBubble),
                anchor -> chooseBubble(anchor, false, tokens)), matchWrap(10));
        colourRows.addView(colourRow("Cards", "Panels, sheets and Deck tiles",
                tokens.surface, draft.surface, surfaceSummary(draft.surface),
                anchor -> chooseSurface(anchor, true, tokens)), matchWrap(10));
        colourRows.addView(colourRow("Background", "The page behind everything",
                tokens.background, draft.background, backgroundSummary(),
                anchor -> chooseSurface(anchor, false, tokens)), matchWrap(10));
    }

    /**
     * One colour: a swatch, what it controls, and what it is currently set to in words.
     *
     * <p>The value is spelled out — "Deep violet, custom" — rather than left to the swatch. A row
     * whose only content is a coloured square says nothing to a screen reader and nothing to
     * anyone who cannot separate two similar hues.
     */
    private View colourRow(String title, String description, int resolved, String token,
                           String summary, View.OnClickListener onOpen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 9), UiKit.dp(this, 12), UiKit.dp(this, 9));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 60), UiKit.accent(this), 16, this));
        row.setMinimumHeight(UiKit.dp(this, 62));
        row.setContentDescription(title + ". " + description + ". Currently "
                + OrbitColorName.describe("", resolved) + ", " + summary + ". Opens a color editor.");
        UiKit.pressScale(row);
        row.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            onOpen.onClick(v);
        });

        View swatch = new View(this);
        swatch.setBackground(UiKit.outlined(resolved,
                UiKit.withAlpha(OrbitContrast.inkOn(resolved), 70), 13, this));
        swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams swatchLp =
                new LinearLayout.LayoutParams(UiKit.dp(this, 42), UiKit.dp(this, 42));
        swatchLp.rightMargin = UiKit.dp(this, 13);
        row.addView(swatch, swatchLp);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(UiKit.text(this, title, 14.5f, UiKit.TEXT, true));
        labels.addView(UiKit.text(this, OrbitColorName.of(resolved) + " · " + summary,
                12, UiKit.MUTED, false));
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = UiKit.text(this, UiKit.SELECTOR_CHEVRON, 16, UiKit.accent(this), false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        return row;
    }

    /**
     * What a token is called, in words.
     *
     * <p>One lookup for all five rows, because {@link OrbitPalette} is where a colour's name lives
     * and the editor should not carry its own idea of what to call one. This is also why Nebula's
     * accent now reads "Violet" instead of "custom #8B7CFF": the value never changed, only whether
     * anything recognised it.
     */
    private String accentSummary() {
        return OrbitPalette.labelFor(draft.accent);
    }

    private String bubbleSummary(String token) {
        return OrbitPalette.labelFor(token);
    }

    private String surfaceSummary(String token) {
        return OrbitTheme.isHexToken(token) ? "custom " + token : "Orbit default";
    }

    private String backgroundSummary() {
        if (draft.amoled) return "AMOLED true black";
        return surfaceSummary(draft.background);
    }

    // ---- colour choosers ---------------------------------------------------------------------------

    private void chooseAccent(View anchor, OrbitThemeTokens tokens) {
        String[] keys = UiKit.accentKeys();
        List<String> labels = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            labels.add(UiKit.accentLabels()[i]);
            colours.add(UiKit.accentForName(this, keys[i]));
        }
        labels.add("Custom color…");
        colours.add(tokens.accent);

        int selected = indexOfToken(keys, draft.accent);
        showColourChoice(anchor, "Accent", labels, colours, selected, index -> {
            if (index < keys.length) {
                edit(draft.withAccent(keys[index]));
            } else {
                pickCustom("Accent", tokens.accent, accentSuggestions(),
                        colour -> edit(draft.withAccent(OrbitPalette.tokenFor(colour))));
            }
        });
    }

    private void chooseBubble(View anchor, boolean user, OrbitThemeTokens tokens) {
        String[] keys = UiKit.bubbleColorKeys();
        List<String> labels = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        int classic = user
                ? OrbitThemeTokens.classicUserBubble(tokens.accent, tokens.surface2)
                : tokens.surface;
        for (int i = 0; i < keys.length; i++) {
            labels.add(UiKit.bubbleColorLabels()[i]);
            colours.add(OrbitTheme.CLASSIC.equals(keys[i]) ? classic
                    : OrbitTheme.ACCENT.equals(keys[i]) ? tokens.accent
                    : UiKit.accentForName(this, keys[i]));
        }
        labels.add("Custom color…");
        colours.add(user ? tokens.userBubble : tokens.assistantBubble);

        String token = user ? draft.userBubble : draft.assistantBubble;
        String role = user ? "Your messages" : "Orbit's replies";
        showColourChoice(anchor, role, labels, colours, indexOfToken(keys, token), index -> {
            if (index < keys.length) {
                edit(user ? draft.withUserBubble(keys[index]) : draft.withAssistantBubble(keys[index]));
            } else {
                pickCustom(role, user ? tokens.userBubble : tokens.assistantBubble,
                        bubbleSuggestions(tokens),
                        colour -> {
                            String value = OrbitTheme.colorToken(colour);
                            edit(user ? draft.withUserBubble(value) : draft.withAssistantBubble(value));
                        });
            }
        });
    }

    private void chooseSurface(View anchor, boolean surface, OrbitThemeTokens tokens) {
        String role = surface ? "Cards" : "Background";
        List<String> labels = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        labels.add("Orbit default");
        colours.add(surface ? UiKit.classicSurface() : UiKit.classicBackground());
        labels.add("Custom color…");
        colours.add(surface ? tokens.surface : tokens.background);

        String token = surface ? draft.surface : draft.background;
        int selected = OrbitTheme.isHexToken(token) ? 1 : 0;
        showColourChoice(anchor, role, labels, colours, selected, index -> {
            if (index == 0) {
                edit(surface ? draft.withSurface(OrbitTheme.CLASSIC)
                        : draft.withBackground(OrbitTheme.CLASSIC));
            } else {
                pickCustom(role, surface ? tokens.surface : tokens.background,
                        surfaceSuggestions(surface),
                        colour -> {
                            String value = OrbitTheme.colorToken(colour);
                            edit(surface ? draft.withSurface(value) : draft.withBackground(value));
                        });
            }
        });
    }

    private interface IndexChoice { void onIndex(int index); }

    private void showColourChoice(View anchor, String role, List<String> labels, List<Integer> colours,
                                   int selected, IndexChoice choice) {
        int[] colourArray = new int[colours.size()];
        for (int i = 0; i < colours.size(); i++) colourArray[i] = colours.get(i);
        UiKit.showOrbitColorMenu(this, anchor, contentScroll, actionBar,
                labels.toArray(new String[0]), colourArray, selected,
                (index, label) -> choice.onIndex(index));
    }

    private void pickCustom(String role, int initial, List<Integer> suggestions,
                            OrbitColorPicker.Listener listener) {
        OrbitColorPicker.show(this, role, initial, suggestions, listener);
    }

    private List<Integer> accentSuggestions() {
        List<Integer> out = new ArrayList<>();
        for (String key : UiKit.accentKeys()) {
            if (OrbitTheme.DYNAMIC.equals(key)) continue;
            out.add(UiKit.accentForName(this, key));
        }
        return out;
    }

    private List<Integer> bubbleSuggestions(OrbitThemeTokens tokens) {
        List<Integer> out = new ArrayList<>();
        out.add(tokens.accent);
        out.add(OrbitContrast.blend(tokens.accent, tokens.surface2, 0.46f));
        out.add(tokens.surface);
        out.add(tokens.surface3);
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            if (OrbitTheme.isHexToken(preset.userBubble)) {
                out.add(OrbitTheme.hexTokenColor(preset.userBubble));
            }
        }
        return out;
    }

    private List<Integer> surfaceSuggestions(boolean surface) {
        List<Integer> out = new ArrayList<>();
        out.add(surface ? UiKit.classicSurface() : UiKit.classicBackground());
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            String token = surface ? preset.surface : preset.background;
            if (OrbitTheme.isHexToken(token)) out.add(OrbitTheme.hexTokenColor(token));
        }
        return out;
    }

    private int indexOfToken(String[] keys, String token) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(token)) return i;
        return -1;
    }

    // ---- readability ------------------------------------------------------------------------------

    /**
     * The low-contrast warning.
     *
     * <p>It names the pairing rather than saying "low contrast" and leaving the user to find it,
     * and it never blocks anything. Orbit's job here is to tell the truth about a combination, not
     * to refuse a taste it disagrees with.
     */
    private void renderWarnings(OrbitThemeTokens tokens) {
        if (warningStrip == null) return;
        warningStrip.removeAllViews();
        List<OrbitThemeTokens.Check> failing = tokens.lowContrastChecks();
        if (failing.isEmpty()) {
            warningStrip.setVisibility(View.GONE);
            return;
        }
        warningStrip.setVisibility(View.VISIBLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 10),
                UiKit.dp(this, 13), UiKit.dp(this, 11));
        box.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 110), 14, this));

        StringBuilder spoken = new StringBuilder("Low contrast. ");
        box.addView(UiKit.text(this, "Low contrast", 13, UiKit.DANGER, true));
        for (OrbitThemeTokens.Check check : failing) {
            // One ratio format everywhere, and no em dash: "Orbit's replies: 3.0:1".
            String line = check.label + ": "
                    + String.format(Locale.US, "%.1f", check.ratio) + ":1";
            TextView row = UiKit.text(this, line, 12, UiKit.MUTED, false);
            row.setPadding(0, UiKit.dp(this, 3), 0, 0);
            box.addView(row);
            spoken.append(check.label).append(". ");
        }
        box.setContentDescription(spoken.toString()
                + "This theme can still be applied, but some text will be hard to read.");
        warningStrip.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ---- presets ----------------------------------------------------------------------------------

    private View presetsCard() {
        LinearLayout card = card();
        card.addView(cardTitle("Presets"));
        card.addView(cardNote("Selecting one loads it into the preview. Nothing changes in Orbit "
                + "until you apply it."));
        card.addView(presetGrid, matchWrap(8));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = secondaryButton("Save as preset");
        save.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            promptSavePreset();
        });
        actions.addView(save, new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1f));

        Button reset = secondaryButton("Orbit Default");
        reset.setContentDescription("Reset the preview to Orbit Default");
        reset.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            resetToOrbitDefault();
        });
        LinearLayout.LayoutParams resetLp =
                new LinearLayout.LayoutParams(0, UiKit.dp(this, 48), 1f);
        resetLp.leftMargin = UiKit.dp(this, 10);
        actions.addView(reset, resetLp);
        card.addView(actions, matchWrap(14));
        return card;
    }

    private void renderPresets() {
        if (presetGrid == null) return;
        presetGrid.removeAllViews();
        List<OrbitTheme> presets = OrbitThemeStore.allPresets(this);
        int columns = presetColumns();
        LinearLayout row = null;
        for (int i = 0; i < presets.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                presetGrid.addView(row, matchWrap(i == 0 ? 0 : 12));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % columns != 0) lp.leftMargin = UiKit.dp(this, 10);
            row.addView(presetCard(presets.get(i)), lp);
        }
        // A short final row must not stretch its cards across the full width.
        int remainder = presets.size() % columns;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < columns; i++) {
                View filler = new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.leftMargin = UiKit.dp(this, 10);
                row.addView(filler, lp);
            }
        }
    }

    private int presetColumns() {
        int width = getResources().getConfiguration().screenWidthDp;
        if (twoPane()) return width >= 1000 ? 3 : 2;
        return width >= 480 ? 3 : 2;
    }

    private View presetCard(OrbitTheme preset) {
        boolean selected = draft != null && draft.sameColours(preset);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, preset);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 11));
        card.setBackground(UiKit.rippleOutlined(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.18f) : UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), selected ? 190 : 54),
                UiKit.accent(this), 16, this));
        card.setMinimumHeight(UiKit.dp(this, 96));
        // Selection is stated, not only drawn. A ring around a card of colours is exactly the kind
        // of state that disappears for anyone who cannot rely on colour to carry it.
        card.setContentDescription(preset.name + (preset.builtIn ? ", Orbit preset" : ", your theme")
                + (preset.note().isEmpty() ? "" : ", " + preset.note())
                + (selected ? ", selected" : "") + ". Background " + OrbitColorName.of(tokens.background)
                + ", accent " + OrbitColorName.of(tokens.accent) + ".");
        UiKit.pressScale(card);
        card.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            selectPreset(preset);
        });
        if (!preset.builtIn) {
            card.setOnLongClickListener(v -> {
                UiKit.haptic(v, HapticFeedbackConstants.LONG_PRESS);
                showPresetMenu(v, preset);
                return true;
            });
        }

        card.addView(swatchStrip(tokens), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 34)));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = UiKit.text(this, preset.name, 13, UiKit.TEXT, selected);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            titleRow.addView(UiKit.text(this, "✓", 14, UiKit.accent(this), true));
        }
        LinearLayout.LayoutParams titleLp = matchWrap(8);
        card.addView(titleRow, titleLp);

        // The one built-in note Orbit ships sits on this line rather than becoming a badge, so a
        // card stays a swatch strip, a name and one quiet line whatever theme it describes.
        String note = preset.note();
        String kindText = preset.builtIn
                ? (note.isEmpty() ? "Orbit preset" : "Orbit preset · " + note)
                : "Your theme · hold to manage";
        TextView kind = UiKit.text(this, kindText, 10.5f, UiKit.MUTED, false);
        kind.setSingleLine(true);
        kind.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(kind);
        card.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        for (int i = 0; i < card.getChildCount(); i++) {
            card.getChildAt(i).setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        return card;
    }

    /** Four bands of one theme: page, card, accent, and the user's bubble. */
    private View swatchStrip(OrbitThemeTokens tokens) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setBackground(UiKit.rounded(tokens.background, 10, this));
        strip.setPadding(UiKit.dp(this, 5), UiKit.dp(this, 5),
                UiKit.dp(this, 5), UiKit.dp(this, 5));
        int[] bands = {tokens.surface, tokens.userBubble, tokens.assistantBubble, tokens.accent};
        for (int i = 0; i < bands.length; i++) {
            View band = new View(this);
            band.setBackground(UiKit.rounded(bands[i], 6, this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.leftMargin = UiKit.dp(this, 4);
            strip.addView(band, lp);
        }
        return strip;
    }

    private void showPresetMenu(View anchor, OrbitTheme preset) {
        String[] labels = {"Apply to preview", "Rename", "Duplicate", "Delete"};
        UiKit.showOrbitMenu(this, anchor, labels, -1, (index, label) -> {
            if (index == 0) selectPreset(preset);
            else if (index == 1) promptRename(preset);
            else if (index == 2) duplicate(preset);
            else confirmDelete(preset);
        });
    }

    private void duplicate(OrbitTheme preset) {
        OrbitTheme copy = OrbitThemeStore.duplicatePreset(this, preset.id);
        if (copy == null) {
            Toast.makeText(this, "Orbit could not duplicate that theme.", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshDraftSurfaces();
        Toast.makeText(this, "Saved as " + copy.name, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete(OrbitTheme preset) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Delete " + preset.name + "?")
                .setMessage("This removes the saved theme. Orbit's own appearance does not change.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, which) -> {
                    if (!OrbitThemeStore.deletePreset(this, preset.id)) {
                        Toast.makeText(this, "Orbit could not delete that theme.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    refreshDraftSurfaces();
                    Toast.makeText(this, preset.name + " deleted", Toast.LENGTH_SHORT).show();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private void promptRename(OrbitTheme preset) {
        nameDialog("Rename theme", preset.name, "Save", name -> {
            if (!OrbitThemeStore.renamePreset(this, preset.id, name)) {
                Toast.makeText(this, "Orbit could not rename that theme.", Toast.LENGTH_SHORT).show();
                return;
            }
            refreshDraftSurfaces();
        });
    }

    private void promptSavePreset() {
        String suggestion = draft.builtIn ? draft.name + " copy" : draft.name;
        nameDialog("Save theme", suggestion, "Save", name -> {
            OrbitTheme saved = OrbitThemeStore.savePreset(this, draft.asCustomNamed(name));
            if (saved == null) {
                Toast.makeText(this, "Orbit could not save that theme.", Toast.LENGTH_SHORT).show();
                return;
            }
            // The draft now *is* that preset, so the gallery shows it selected. Saving still does
            // not change what Orbit is drawing: Apply is the only thing that does.
            draft = saved;
            refreshDraftSurfaces();
            Toast.makeText(this, "Saved " + saved.name, Toast.LENGTH_SHORT).show();
        });
    }

    private interface NameChoice { void onName(String name); }

    private void nameDialog(String title, String initial, String confirm, NameChoice choice) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 6), UiKit.dp(this, 20), 0);

        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(OrbitTheme.MAX_NAME_LENGTH)});
        field.setText(OrbitTheme.normalizeName(initial));
        field.setSelection(field.length());
        field.setTextColor(UiKit.TEXT);
        field.setHintTextColor(UiKit.MUTED);
        field.setHint("Theme name");
        field.setContentDescription("Theme name");
        field.setTextSize(15);
        field.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 72), 14, this));
        form.addView(field, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 52)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(confirm, null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false,
                UiKit.outlined(UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 55), 22, this),
                .66f, () -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    // A blank name is a name Orbit supplies rather than an error to argue about.
                    String name = OrbitTheme.normalizeName(field.getText().toString());
                    dialog.dismiss();
                    choice.onName(name);
                }));
        dialog.show();
    }

    // ---- theme files ------------------------------------------------------------------------------

    private static final int REQ_IMPORT_THEME = 8811;
    private static final int REQ_EXPORT_THEME = 8812;

    /**
     * The theme the user asked to export, captured when they asked.
     *
     * <p>Android's document creator is another app's window and the user may spend a while in it.
     * Reading the draft again when it returns would write whatever the preview happens to hold by
     * then, which after a rotation and a colour change is not the theme they chose to export.
     */
    private OrbitTheme pendingExport;

    /**
     * Import: Android's own picker, and no storage permission.
     *
     * <p>{@code ACTION_OPEN_DOCUMENT} hands back one document the user pointed at, for as long as
     * it takes to read it. Orbit never asks to see the rest of their files, and never keeps the
     * grant.
     */
    private void chooseThemeFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(OrbitThemeFileCodec.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, OrbitThemeFileCodec.IMPORT_MIME_TYPES);
        try {
            startActivityForResult(intent, REQ_IMPORT_THEME);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available on this device.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Export: the theme currently in the preview, written where the user says.
     *
     * <p>That is the draft, which may be a built-in preset, a saved theme, or an edit of either
     * that has not been applied. All three are the same thing to this: a complete set of appearance
     * decisions the person is looking at. Exporting one does not save it, apply it, or alter it, so
     * the file is a copy of what is on screen and nothing about Orbit changes.
     */
    private void chooseExportDestination() {
        pendingExport = draft;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(OrbitThemeFileCodec.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, OrbitThemeFileCodec.fileNameFor(pendingExport));
        try {
            startActivityForResult(intent, REQ_EXPORT_THEME);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker is available on this device.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_THEME) readThemeFile(uri);
        else if (requestCode == REQ_EXPORT_THEME) writeThemeFile(uri, pendingExport);
    }

    /** Reads and validates off the main thread; every outcome comes back to it. */
    private void readThemeFile(Uri uri) {
        new Thread(() -> {
            OrbitTheme imported = null;
            String failure = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                imported = OrbitThemeFileCodec.read(input);
            } catch (OrbitThemeFileCodec.ThemeFileException e) {
                failure = e.getMessage();
            } catch (Exception e) {
                // Anything the content provider itself did — a revoked grant, a file that vanished,
                // a provider that threw. The user gets the same sentence as a bad file, because
                // from where they are standing it is the same outcome.
                failure = "Orbit could not open that file.";
            }
            final OrbitTheme result = imported;
            final String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null) showThemeFileError("Theme not imported", message);
                else showImportReview(result);
            });
        }, "orbit-theme-import").start();
    }

    private void writeThemeFile(Uri uri, OrbitTheme theme) {
        pendingExport = null;
        final OrbitTheme subject = theme == null ? draft : theme;
        final String document = OrbitThemeFileCodec.encode(subject);
        if (document.isEmpty()) {
            showThemeFileError("Theme not exported", "Orbit could not export that theme.");
            return;
        }
        new Thread(() -> {
            boolean ok = false;
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output != null) {
                    output.write(document.getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    ok = true;
                }
            } catch (Exception ignored) {
            }
            final boolean saved = ok;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saved) {
                    Toast.makeText(this, subject.name + " exported", Toast.LENGTH_SHORT).show();
                } else {
                    showThemeFileError("Theme not exported", "Orbit could not write that file.");
                }
            });
        }, "orbit-theme-export").start();
    }

    private void showThemeFileError(String title, String message) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "This isn't a supported Orbit theme." : message)
                .setPositiveButton("OK", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    /**
     * What an imported theme looks like, before it is anything.
     *
     * <p>Nothing has been written at this point: the file has been read and turned into a value,
     * and that value is drawn by the same {@link ThemePreviewView} the editor uses. A theme is a
     * visual object and the only honest way to ask "is this the one you meant" is to show it.
     *
     * <p>What the buttons do depends on whether there is an unapplied edit in progress. With a
     * clean draft, importing adds the theme and loads it into the preview, which is what somebody
     * who just picked a theme file wants next. With edits pending, loading it would throw those
     * away, so the theme is added to the gallery and the draft is left alone unless the user says
     * otherwise. Neither path applies anything: Apply is still the only thing that changes Orbit.
     */
    private void showImportReview(OrbitTheme imported) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20), 0);

        ThemePreviewView sample = new ThemePreviewView(this);
        sample.render(OrbitThemeTokens.resolve(this, imported));
        body.addView(sample, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = UiKit.text(this, imported.name, 15, UiKit.TEXT, true);
        name.setPadding(0, UiKit.dp(this, 13), 0, 0);
        body.addView(name);
        body.addView(UiKit.text(this, OrbitThemeFileCodec.describe(imported), 12, UiKit.MUTED, false));

        boolean dirty = isDirty();
        String note = dirty
                ? "Orbit will add this to your themes. Your unapplied changes stay in the preview."
                : "Orbit will add this to your themes and load it into the preview. Your current "
                        + "appearance does not change until you apply it.";
        TextView explanation = UiKit.text(this, note, 12, UiKit.MUTED, false);
        explanation.setPadding(0, UiKit.dp(this, 10), 0, 0);
        body.addView(explanation);

        ScrollView wrapper = new ScrollView(this);
        wrapper.addView(body);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Import theme")
                .setView(wrapper)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Import", (d, which) -> importTheme(imported, !dirty));
        if (dirty) {
            builder.setNeutralButton("Import and preview",
                    (d, which) -> importTheme(imported, true));
        }
        AlertDialog dialog = builder.create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> constrainDialogWidth(dialog));
        dialog.show();
    }

    /**
     * Adds an imported theme to the user's own presets.
     *
     * <p>{@code imported} already arrived from the codec as a custom theme with a fresh id, so this
     * cannot land on a built-in or replace a saved one however the file was written. Duplicate
     * names are allowed through untouched: importing the same theme twice gives two entries, which
     * is what a file the user chose twice should do.
     */
    void importTheme(OrbitTheme imported, boolean loadIntoPreview) {
        OrbitTheme saved = OrbitThemeStore.savePreset(this, imported);
        if (saved == null) {
            showThemeFileError("Theme not imported",
                    "Orbit could not save that theme. You may have reached the limit of "
                            + OrbitThemeStore.MAX_CUSTOM_PRESETS + " saved themes.");
            return;
        }
        if (loadIntoPreview) selectPreset(saved); else refreshDraftSurfaces();
        Toast.makeText(this, "Imported " + saved.name, Toast.LENGTH_SHORT).show();
    }

    /**
     * Keeps a dialog that contains a preview from spreading across a tablet.
     *
     * <p>An {@code AlertDialog} takes most of the width it is given, and on a Tab S9 Plus that is a
     * miniature conversation stretched to nine hundred points with its bubbles turned into strips.
     * The cap is the width the same preview has in the editor's own pane.
     */
    private void constrainDialogWidth(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        int screen = getResources().getConfiguration().screenWidthDp;
        int target = Math.min(screen - 48, 400);
        if (target <= 0 || target >= screen) return;
        window.setLayout(UiKit.dp(this, target), ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // ---- system bars ------------------------------------------------------------------------------

    private void applySystemBarIcons() {
        UiKit.applySystemBarIcons(getWindow());
    }

    // ---- shared chrome ----------------------------------------------------------------------------

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16),
                UiKit.dp(this, 18), UiKit.dp(this, 18));
        card.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), 24, this));
        card.setElevation(UiKit.dp(this, 2));
        return card;
    }

    private TextView cardTitle(String value) {
        TextView title = UiKit.text(this, value, 12, UiKit.MUTED, true);
        title.setLetterSpacing(0.13f);
        title.setAllCaps(true);
        return title;
    }

    private TextView cardNote(String value) {
        TextView note = UiKit.text(this, value, 12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 7), 0, 0);
        return note;
    }

    private LinearLayout.LayoutParams matchWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, topDp);
        return lp;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(res);
        button.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        button.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        button.setContentDescription(description);
        int pad = UiKit.dp(this, 11);
        button.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(button);
        return button;
    }

    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }
}

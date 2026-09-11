package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
    /**
     * The Orbit Pro card's contents. Refilled when entitlement changes, and at no other time.
     *
     * <p>Beta 2 refilled it on every draft update, which is how dragging one slider came to
     * destroy and rebuild the other four. Free to Pro and back is a structural change to what this
     * card contains and legitimately rebuilds it; a slider moving is not.
     */
    private LinearLayout proBody;

    // The five premium controls, their value words, and the two samples that sit above them. Held
    // from the moment the Pro section is built so that every later update is an assignment. A
    // control that is replaced in order to show a different number is a control that loses its
    // touch state, its accessibility focus and its position mid-gesture.
    private OrbitSlider bubbleRadiusSlider;
    private OrbitSlider bubbleOutlineSlider;
    private OrbitSlider glassOpacitySlider;
    private OrbitSlider glassTintSlider;
    private OrbitSlider glassEdgeSlider;
    private TextView bubbleRadiusValue;
    private TextView bubbleOutlineValue;
    private TextView glassOpacityValue;
    private TextView glassTintValue;
    private TextView glassEdgeValue;
    private MessageStylePreview messagePreview;
    private GlassStylePreview glassPreview;

    // The Background tool, held on exactly the same terms as the five controls above it. The two
    // containers are the one place this tool differs: showing every possible background control at
    // once would be a wall of settings most of which do not apply, so the Linear and Glow groups are
    // built once and then shown or hidden. Their visibility changes; their contents never do, which is
    // what keeps a mode change from rebuilding the card the user is working in.
    private BackgroundStylePreview backgroundPreview;
    private OrbitSegmented backgroundModeSegment;
    private LinearLayout linearControls;
    private LinearLayout glowControls;
    private View effectColourSwatch;
    private TextView effectColourSummary;
    private TextView directionValue;
    private LinearLayout directionRow;
    private OrbitSlider glowStrengthSlider;
    private OrbitSlider glowSizeSlider;
    private TextView glowStrengthValue;
    private TextView glowSizeValue;
    private OrbitSegmented glowPositionSegment;
    private TextView amoledBackgroundNote;

    /**
     * The preset cards currently on screen, so selection can be re-marked without rebuilding them.
     *
     * <p>The gallery is rebuilt when its contents change - a theme saved, renamed, deleted or
     * imported - and never merely because the selected one moved.
     */
    private final List<PresetCard> presetCards = new ArrayList<>();
    /**
     * The entitlement the Pro section was last drawn for.
     *
     * <p>Held so {@link #onResume} can notice a change rather than re-render unconditionally, and
     * deliberately not held as a substitute for asking: every decision still calls
     * {@link OrbitProEntitlement#hasPro}, and this only records what the screen currently shows.
     */
    private Boolean proWhenRendered;
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
        // The base theme colour, not a sample of whatever the page effect is doing at the top of
        // the screen. A system bar cannot carry a gradient without Orbit reimplementing one inside
        // an inset it does not control, and one tinted to the effect would visibly disagree with the
        // page the moment anything moved. OrbitBackground owns that decision for every screen.
        window.setStatusBarColor(OrbitBackground.systemBarColor(this));
        window.setNavigationBarColor(OrbitBackground.systemBarColor(this));
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

    /**
     * Entitlement can change while this screen is sitting in the background.
     *
     * <p>Orbit Pro Preview is switched in Diagnostics, which is a different Activity, so a tester's
     * normal loop is to leave Theme Studio, change it, and come back. Coming back to controls that
     * still reflect the old answer would make the whole feature look broken, so the section is
     * re-drawn when the answer has moved. The draft itself is untouched: an edit made under Pro is
     * still there under Free, it simply stops being drawn.
     */
    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        boolean pro = OrbitProEntitlement.hasPro(this);
        if (proWhenRendered != null && proWhenRendered != pro) {
            // A structural change to what the Pro card contains, so this one rebuilds it. It is
            // the only thing that may, and it happens once per entitlement change.
            rebuildProSection();
            syncAllToDraft();
        }
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
        // The premium half of the draft travels with the rest of it. An unapplied edit is the one
        // thing on this screen that cannot be recovered from storage, whichever half it is in.
        out.putInt("draft_pro_radius", draft.pro.bubbleRadiusDp);
        out.putInt("draft_pro_outline", draft.pro.bubbleOutline);
        out.putInt("draft_pro_glass_opacity", draft.pro.glassOpacity);
        out.putInt("draft_pro_glass_tint", draft.pro.glassTint);
        out.putInt("draft_pro_glass_edge", draft.pro.glassEdge);
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
                state.getBoolean("draft_amoled", false),
                OrbitProStyle.of(
                        state.getInt("draft_pro_radius", OrbitProStyle.BUBBLE_RADIUS_DEFAULT),
                        state.getInt("draft_pro_outline", OrbitProStyle.OUTLINE_DEFAULT),
                        state.getInt("draft_pro_glass_opacity", OrbitProStyle.GLASS_OPACITY_DEFAULT),
                        state.getInt("draft_pro_glass_tint", OrbitProStyle.GLASS_TINT_DEFAULT),
                        state.getInt("draft_pro_glass_edge", OrbitProStyle.GLASS_EDGE_DEFAULT)));
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
        // The rule itself lives in OrbitThemeStore, because onboarding writes an appearance too
        // and the two must label the same colours the same way.
        draft = OrbitThemeStore.canonicalIdentity(this, next);
        syncAllToDraft();
    }

    /**
     * Selecting a preset loads it into the draft. It does not become the app's theme.
     *
     * <p>Worth stating plainly, because on the device this was the behaviour that looked wrong:
     * selecting Signal Violet moves five sliders, and moving five sliders looks like something
     * being applied. It is not. The draft changes, every control is synchronized to it once, and
     * Orbit goes on drawing whatever it was drawing until Apply is pressed. A premium preset and an
     * ordinary one follow exactly the same rule - the premium one is simply the first preset that
     * carries values the Pro controls display, so it is the first one where anybody could see the
     * synchronization happen.
     */
    private void selectPreset(OrbitTheme preset) {
        if (preset == null) return;
        // An Orbit Pro preset is refused before it reaches the draft, not after. Loading it and
        // then refusing to apply it would put a theme in the preview that the person watching
        // cannot have, which is a worse answer than saying so.
        if (!OrbitThemeStore.canApply(this, preset)) {
            explainPremiumPreset(preset);
            return;
        }
        // One assignment, then one synchronization pass. Nothing here is animated and nothing runs
        // twice, which is the difference between the sliders arriving at the preset's values and
        // the jitter Beta 2 showed while it rebuilt them.
        draft = preset;
        syncAllToDraft();
    }

    /**
     * What a locked premium preset says when it is tapped.
     *
     * <p>No price, no checkout, no purchase, because there is nothing to buy. On a build eligible
     * for the developer override it says where the override is, which is the only actionable thing
     * that is true; on any other build it says what the preset belongs to and stops there.
     */
    private void explainPremiumPreset(OrbitTheme preset) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(preset.name)
                .setMessage("An Orbit Pro preset. " + proUnavailableNote())
                .setPositiveButton("OK", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false);
        dialog.show();
    }

    /**
     * The one sentence Orbit says when premium styling is not available.
     *
     * <p>Two versions, and which one is used is {@link OrbitProEntitlement}'s decision rather than
     * this screen's. A tester on a Beta needs to be told where the override is; a Stable build must
     * never mention a developer preview that it would refuse to honour anyway.
     */
    private String proUnavailableNote() {
        return OrbitProEntitlement.previewAvailable()
                ? "Orbit Pro Preview is off. Enable Pro Preview in Orbit Diagnostics to test "
                        + "advanced styling."
                : "Orbit Pro is not available on this device.";
    }

    // ---- synchronizing the screen to the draft ------------------------------------------------
    //
    // Beta 2 had one method for this, and it rebuilt everything: the colour rows, the whole preset
    // gallery (which reads the saved-theme file from disk), and the entire Orbit Pro section,
    // sliders included. That was tolerable while the only things that called it were a menu
    // selection and a switch. It became the source of most of this release's reported faults the
    // moment a premium slider started calling it, because destroying and recreating five sliders
    // and a preview sixty times a second is visible: unrelated thumbs move, labels flicker as the
    // typography watcher meets new views, and the gallery reloads under the user's finger.
    //
    // So the screen is now synchronized in named pieces, and each interaction calls only the ones
    // it actually affects. None of them create or destroy a control.

    /**
     * The three views that show what the draft looks like, plus the readability warning.
     *
     * <p>This is the whole of the per-frame drag path. Each of the three previews builds its
     * hierarchy once and assigns into it here, so a drag touches drawables and colours and never
     * the view tree.
     */
    /**
     * Brings every control onto the draft, without creating or destroying one.
     *
     * <p>What a preset selection, a colour edit and Revert all need: the draft changed wholesale,
     * so everything that displays part of it is told the new value once. The gallery's contents are
     * deliberately not rebuilt here, only its selection mark.
     */
    private void syncAllToDraft() {
        renderColourRows();
        syncAmoledSwitch();
        syncProValues();
        syncPreviews();
        syncPresetSelection();
    }

    /**
     * The saved-theme library changed, so the gallery genuinely has to be rebuilt.
     *
     * <p>Saving, renaming, duplicating, deleting and importing. These are the only things that
     * change which cards exist, and they are all deliberate one-off actions rather than anything
     * that happens under a moving finger.
     */
    private void onThemeLibraryChanged() {
        rebuildPresetGallery();
        syncPresetSelection();
    }

    private void syncPreviews() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, draft);
        OrbitProStyle style = OrbitProStyle.resolve(this, draft.pro);
        if (preview != null) preview.render(tokens);
        if (messagePreview != null) messagePreview.render(tokens, style);
        if (glassPreview != null) glassPreview.render(tokens, style);
        if (backgroundPreview != null) backgroundPreview.render(tokens, style);
        renderWarnings(tokens);
        updateActionState();
    }

    /**
     * Moves the five premium controls onto the draft's values.
     *
     * <p>{@link OrbitSlider#setValue} never notifies its listener, so this cannot loop back into an
     * edit, and a slider already holding the right value ignores the call entirely. That second
     * part is what makes it safe to run at the end of a drag: the control the user just released
     * is told to be where it already is, and does nothing.
     */
    private void syncProValues() {
        if (bubbleRadiusSlider == null) return;
        OrbitProStyle style = draft.pro;
        bubbleRadiusSlider.setValue(style.bubbleRadiusDp);
        bubbleOutlineSlider.setValue(style.bubbleOutline);
        glassOpacitySlider.setValue(style.glassOpacity);
        glassTintSlider.setValue(style.glassTint);
        glassEdgeSlider.setValue(style.glassEdge);
        glowStrengthSlider.setValue(style.glowStrength);
        glowSizeSlider.setValue(style.glowSize);
        // Neither segmented control notifies from setSelected, for the same reason OrbitSlider does
        // not: this runs after a preset selection, and a control that reported its own
        // synchronization as a user choice would edit the draft it was being synchronized to.
        backgroundModeSegment.setSelected(style.backgroundMode);
        glowPositionSegment.setSelected(style.glowPosition);
        syncProLabels();
        syncBackgroundVisibility();
    }

    /** The value word beside each premium control. */
    private void syncProLabels() {
        if (bubbleRadiusValue == null) return;
        OrbitProStyle style = draft.pro;
        bubbleRadiusValue.setText(style.bubbleRadiusLabel());
        bubbleOutlineValue.setText(style.bubbleOutlineLabel());
        glassOpacityValue.setText(style.glassOpacityLabel());
        glassTintValue.setText(style.glassTintLabel());
        glassEdgeValue.setText(style.glassEdgeLabel());
        glowStrengthValue.setText(style.glowStrengthLabel());
        glowSizeValue.setText(style.glowSizeLabel());
        directionValue.setText(style.directionLabel());

        int effect = style.backgroundEffectColor(this, OrbitThemeTokens.resolve(this, draft).accent);
        effectColourSwatch.setBackground(UiKit.outlined(effect,
                UiKit.withAlpha(OrbitContrast.inkOn(effect), 70), 11, this));
        effectColourSummary.setText(OrbitTheme.ACCENT.equals(style.backgroundEffectColor)
                ? OrbitColorName.of(effect) + " · theme accent"
                : OrbitColorName.of(effect) + " · " + OrbitPalette.labelFor(style.backgroundEffectColor));
    }

    /**
     * Which background controls apply to the mode that is selected.
     *
     * <p>Visibility, not construction. A mode change hides the group that no longer applies and shows
     * the one that does, and neither group is ever rebuilt - so switching from Linear to Glow and back
     * returns to the same direction row, holding the same value, with the same sliders in it.
     *
     * <p>The AMOLED note appears whenever an effect is configured and AMOLED is suppressing it, in
     * either mode. It says the settings are kept because that is the question it exists to answer:
     * a person who turns AMOLED on and sees their gradient vanish needs to know Orbit has not thrown
     * it away.
     */
    private void syncBackgroundVisibility() {
        if (backgroundModeSegment == null) return;
        OrbitProStyle style = draft.pro;
        linearControls.setVisibility(
                style.backgroundMode == OrbitProStyle.BACKGROUND_LINEAR ? View.VISIBLE : View.GONE);
        glowControls.setVisibility(
                style.backgroundMode == OrbitProStyle.BACKGROUND_GLOW ? View.VISIBLE : View.GONE);
        boolean suppressed = draft.amoled && style.hasBackgroundEffect();
        amoledBackgroundNote.setVisibility(suppressed ? View.VISIBLE : View.GONE);
    }

    /**
     * Selecting an AMOLED preset has to move the switch too, or the screen shows a true-black
     * preview above a control saying AMOLED is off. setChecked never calls the listener, so this
     * cannot loop back into another edit.
     */
    private void syncAmoledSwitch() {
        if (amoledSwitch != null) amoledSwitch.setChecked(draft.amoled);
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
        // The store is the authority on whether this theme may be applied, and it is asked rather
        // than assumed: the draft cannot normally hold a premium preset on a Free device, but a
        // saved state restored across an entitlement change is exactly the case where it could.
        if (!OrbitThemeStore.applyActive(this, draft)) {
            explainPremiumPreset(draft);
            return;
        }
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
        // The base theme colour, not a sample of whatever the page effect is doing at the top of
        // the screen. A system bar cannot carry a gradient without Orbit reimplementing one inside
        // an inset it does not control, and one tinted to the effect would visibly disagree with the
        // page the moment anything moved. OrbitBackground owns that decision for every screen.
        window.setStatusBarColor(OrbitBackground.systemBarColor(this));
        window.setNavigationBarColor(OrbitBackground.systemBarColor(this));
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
        syncAllToDraft();
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
        OrbitBackground.applyPage(root);
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
        proBody = new LinearLayout(this);
        proBody.setOrientation(LinearLayout.VERTICAL);
        presetGrid = new LinearLayout(this);
        presetGrid.setOrientation(LinearLayout.VERTICAL);

        root.addView(twoPane() ? buildWideBody(inset) : buildNarrowBody(inset),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        actionBar = buildActionBar(inset);
        root.addView(actionBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rebuildProSection();
        rebuildPresetGallery();
        renderColourRows();
        syncAmoledSwitch();
        syncProValues();
        syncPreviews();
        syncPresetSelection();
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
        column.addView(proCard(), matchWrap(CARD_GAP_DP));
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
        right.addView(proCard(), matchWrap(CARD_GAP_DP));
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
        // Chrome pinned below the scroll, not the page canvas, so it is opaque in the page own
        // colour rather than a second slice of the page effect. The scrolling content above it
        // carries the background; this bar is what that content passes behind.
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

    // ---- Orbit Pro ---------------------------------------------------------------------------------

    /**
     * The advanced styling card.
     *
     * <p>A card in Theme Studio's own language, sitting below Colors, and one restrained Orbit Pro
     * treatment rather than a lock icon on every row. Everything above it stays free and untouched:
     * this release adds a section, it does not move a single control into one.
     */
    /**
     * The Orbit Pro card, which sits below everything Theme Studio already had.
     *
     * <p>Its position is the point. Beta 2 put it between Colors and Presets, where a large locked
     * panel stood between a free user and the gallery they were trying to reach, and made the
     * screen read as though Pro were the middle of the workflow rather than an addition to the end
     * of it. Free Theme Studio comes first, complete and uninterrupted, and this follows it.
     */
    private View proCard() {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(cardTitle("Orbit Pro"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        heading.addView(proMark());
        card.addView(heading);
        card.addView(proBody, matchWrap(10));
        return card;
    }

    /** The single Orbit Pro marker on this screen. A quiet chip, not a badge on every row. */
    private View proMark() {
        TextView mark = UiKit.text(this, "ORBIT PRO", 10, UiKit.accent(this), true);
        mark.setLetterSpacing(0.1f);
        mark.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 4));
        mark.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 110), 9, this));
        return mark;
    }

    /**
     * Builds the Pro card's contents for the entitlement in force.
     *
     * <p>Called when the screen is built and when entitlement changes, and at no other time. That
     * restriction is the whole of this release's interaction fix: Free to Pro is a structural
     * change to what this card contains and has to rebuild it, while a slider moving is not and
     * must not. Everything between those two events is an assignment to a control that already
     * exists.
     */
    private void rebuildProSection() {
        if (proBody == null) return;
        proBody.removeAllViews();
        clearProReferences();
        boolean pro = OrbitProEntitlement.hasPro(this);
        proWhenRendered = pro;
        if (pro) buildProControls(); else buildProTeaser();
    }

    /** Dropped together, so a stale reference into a removed hierarchy is not possible. */
    private void clearProReferences() {
        bubbleRadiusSlider = null;
        bubbleOutlineSlider = null;
        glassOpacitySlider = null;
        glassTintSlider = null;
        glassEdgeSlider = null;
        bubbleRadiusValue = null;
        bubbleOutlineValue = null;
        glassOpacityValue = null;
        glassTintValue = null;
        glassEdgeValue = null;
        messagePreview = null;
        glassPreview = null;
        backgroundPreview = null;
        backgroundModeSegment = null;
        linearControls = null;
        glowControls = null;
        effectColourSwatch = null;
        effectColourSummary = null;
        directionValue = null;
        directionRow = null;
        glowStrengthSlider = null;
        glowSizeSlider = null;
        glowStrengthValue = null;
        glowSizeValue = null;
        glowPositionSegment = null;
        amoledBackgroundNote = null;
    }

    /**
     * What Free sees: two lines and a short note.
     *
     * <p>Beta 2 put a bordered panel in here, inside the card, carrying a heading, a two-line
     * inventory of premium features and an explanation. On the device that was most of a screen
     * spent describing things the person could not use, sitting in the middle of the editor they
     * were trying to use, and it was the clearest single piece of feedback from testing. So it is
     * a teaser now: what Pro adds, in one line, at the bottom of the page, with no nested card, no
     * lock icons, no disabled controls and nothing resembling a purchase.
     *
     * <p>The state is still carried in words as well as in styling, because a treatment that only
     * differs in colour says nothing to somebody using a screen reader.
     */
    private void buildProTeaser() {
        TextView what = UiKit.text(this,
                "Advanced message styling, Liquid Glass and advanced backgrounds", 13.5f,
                UiKit.TEXT, false);
        what.setLineSpacing(0, 1.15f);
        proBody.addView(what, matchWrap(0));

        TextView note = UiKit.text(this, proUnavailableNote(), 12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.15f);
        proBody.addView(note, matchWrap(6));

        proBody.setContentDescription("Orbit Pro. Advanced message styling, Liquid Glass and "
                + "advanced backgrounds, locked. " + proUnavailableNote());
        proBody.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        what.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        note.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    /**
     * What Pro sees: two tools rather than one form.
     *
     * <p>Message style, Liquid Glass and Background are separate panels, each led by the sample it
     * changes.
     * Beta 2 stacked all five sliders in one column with the only demonstration of their effect at
     * the top of a scrolling page, so tuning a corner radius meant dragging, scrolling up to look,
     * and scrolling back down. Putting each sample directly above its own controls is most of what
     * makes this read as a tool rather than a settings form.
     */
    private void buildProControls() {
        proBody.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        proBody.setContentDescription(null);

        TextView intro = UiKit.text(this,
                "Fine detail on top of your theme. Nothing changes in Orbit until you apply.",
                12, UiKit.MUTED, false);
        proBody.addView(intro, matchWrap(0));

        LinearLayout messages = toolPanel();
        messages.addView(toolTitle("Message style"), matchWrap(0));
        messagePreview = new MessageStylePreview(this);
        messages.addView(messagePreview, matchWrap(10));

        bubbleRadiusValue = valueLabel(draft.pro.bubbleRadiusLabel());
        bubbleRadiusSlider = new OrbitSlider(this);
        messages.addView(proControl("Bubble roundness", bubbleRadiusValue, bubbleRadiusSlider,
                OrbitProStyle.BUBBLE_RADIUS_MIN, OrbitProStyle.BUBBLE_RADIUS_MAX,
                draft.pro.bubbleRadiusDp,
                v -> OrbitProStyle.DEFAULT.withBubbleRadiusDp(v).bubbleRadiusLabel(),
                (v, settled) -> editPro(draft.pro.withBubbleRadiusDp(v), settled)), matchWrap(14));

        bubbleOutlineValue = valueLabel(draft.pro.bubbleOutlineLabel());
        bubbleOutlineSlider = new OrbitSlider(this);
        messages.addView(proControl("Bubble outline", bubbleOutlineValue, bubbleOutlineSlider,
                OrbitProStyle.OUTLINE_OFF, OrbitProStyle.OUTLINE_DEFINED,
                draft.pro.bubbleOutline,
                OrbitProStyle::outlineLabel,
                (v, settled) -> editPro(draft.pro.withBubbleOutline(v), settled)), matchWrap(12));
        proBody.addView(messages, matchWrap(14));

        LinearLayout glass = toolPanel();
        glass.addView(toolTitle("Liquid Glass"), matchWrap(0));
        glassPreview = new GlassStylePreview(this);
        glass.addView(glassPreview, matchWrap(10));

        glassOpacityValue = valueLabel(draft.pro.glassOpacityLabel());
        glassOpacitySlider = new OrbitSlider(this);
        glass.addView(proControl("Glass opacity", glassOpacityValue, glassOpacitySlider,
                OrbitProStyle.GLASS_OPACITY_MIN, OrbitProStyle.GLASS_OPACITY_MAX,
                draft.pro.glassOpacity,
                v -> OrbitProStyle.DEFAULT.withGlassOpacity(v).glassOpacityLabel(),
                (v, settled) -> editPro(draft.pro.withGlassOpacity(v), settled)), matchWrap(14));

        glassTintValue = valueLabel(draft.pro.glassTintLabel());
        glassTintSlider = new OrbitSlider(this);
        glass.addView(proControl("Glass tint", glassTintValue, glassTintSlider,
                OrbitProStyle.GLASS_TINT_MIN, OrbitProStyle.GLASS_TINT_MAX,
                draft.pro.glassTint,
                OrbitProStyle::strengthLabel,
                (v, settled) -> editPro(draft.pro.withGlassTint(v), settled)), matchWrap(12));

        glassEdgeValue = valueLabel(draft.pro.glassEdgeLabel());
        glassEdgeSlider = new OrbitSlider(this);
        glass.addView(proControl("Glass edge", glassEdgeValue, glassEdgeSlider,
                OrbitProStyle.GLASS_EDGE_MIN, OrbitProStyle.GLASS_EDGE_MAX,
                draft.pro.glassEdge,
                OrbitProStyle::strengthLabel,
                (v, settled) -> editPro(draft.pro.withGlassEdge(v), settled)), matchWrap(12));
        proBody.addView(glass, matchWrap(14));

        proBody.addView(buildBackgroundTool(), matchWrap(14));
    }

    /**
     * The third tool: what the page itself looks like.
     *
     * <p>Below Message style and Liquid Glass because that is the order of increasing scope - a
     * bubble, then the controls floating over the page, then the page - and because it is the newest
     * of the three and moving the two a person already knows would be the wrong kind of surprise.
     *
     * <p>The free Background colour control stays exactly where it has always been, in the Colors
     * card at the top of this screen. Nothing here replaces it: Linear runs it into a premium colour
     * and Glow lays light over it, so a person without Pro loses nothing and a person with Pro is
     * building on the choice they already made rather than making it again somewhere else.
     */
    private View buildBackgroundTool() {
        LinearLayout panel = toolPanel();
        panel.addView(toolTitle("Background"), matchWrap(0));

        backgroundPreview = new BackgroundStylePreview(this);
        panel.addView(backgroundPreview, matchWrap(10));

        backgroundModeSegment = new OrbitSegmented(this);
        backgroundModeSegment.setTitle("Background");
        backgroundModeSegment.setOptions(new String[]{
                OrbitProStyle.backgroundModeLabel(OrbitProStyle.BACKGROUND_SOLID),
                OrbitProStyle.backgroundModeLabel(OrbitProStyle.BACKGROUND_LINEAR),
                OrbitProStyle.backgroundModeLabel(OrbitProStyle.BACKGROUND_GLOW)});
        backgroundModeSegment.setSelected(draft.pro.backgroundMode);
        backgroundModeSegment.setOnSelectListener(
                (view, index) -> editPro(draft.pro.withBackgroundMode(index), true));
        panel.addView(backgroundModeSegment, matchWrap(12));

        // The note that explains why a configured effect is not on screen. Held rather than added and
        // removed, so turning AMOLED on does not change the height of this panel.
        amoledBackgroundNote = UiKit.text(this,
                "Background effects are hidden while AMOLED is on. Your settings are kept.",
                12, UiKit.MUTED, false);
        amoledBackgroundNote.setLineSpacing(0, 1.15f);
        panel.addView(amoledBackgroundNote, matchWrap(10));

        linearControls = new LinearLayout(this);
        linearControls.setOrientation(LinearLayout.VERTICAL);
        linearControls.addView(effectColourRow("Gradient color",
                "The color the background runs into"), matchWrap(0));
        directionValue = UiKit.text(this, draft.pro.directionLabel(), 14, UiKit.TEXT, true);
        directionRow = selectorRow("Direction", directionValue, v -> chooseDirection(v));
        linearControls.addView(directionRow, matchWrap(10));
        panel.addView(linearControls, matchWrap(10));

        glowControls = new LinearLayout(this);
        glowControls.setOrientation(LinearLayout.VERTICAL);
        glowControls.addView(effectColourRow("Glow color", "The color of the light"), matchWrap(0));

        glowStrengthValue = valueLabel(draft.pro.glowStrengthLabel());
        glowStrengthSlider = new OrbitSlider(this);
        glowControls.addView(proControl("Glow strength", glowStrengthValue, glowStrengthSlider,
                OrbitProStyle.GLOW_STRENGTH_MIN, OrbitProStyle.GLOW_STRENGTH_MAX,
                draft.pro.glowStrength,
                OrbitProStyle::glowStrengthLabel,
                (v, settled) -> editPro(draft.pro.withGlowStrength(v), settled)), matchWrap(12));

        glowSizeValue = valueLabel(draft.pro.glowSizeLabel());
        glowSizeSlider = new OrbitSlider(this);
        glowControls.addView(proControl("Glow size", glowSizeValue, glowSizeSlider,
                OrbitProStyle.GLOW_SIZE_MIN, OrbitProStyle.GLOW_SIZE_MAX,
                draft.pro.glowSize,
                OrbitProStyle::glowSizeLabel,
                (v, settled) -> editPro(draft.pro.withGlowSize(v), settled)), matchWrap(12));

        glowControls.addView(UiKit.text(this, "Position", 13.5f, UiKit.TEXT, false), matchWrap(12));
        glowPositionSegment = new OrbitSegmented(this);
        glowPositionSegment.setTitle("Glow position");
        glowPositionSegment.setOptions(new String[]{
                OrbitProStyle.glowPositionLabel(OrbitProStyle.GLOW_TOP),
                OrbitProStyle.glowPositionLabel(OrbitProStyle.GLOW_CENTER),
                OrbitProStyle.glowPositionLabel(OrbitProStyle.GLOW_BOTTOM)});
        glowPositionSegment.setSelected(draft.pro.glowPosition);
        glowPositionSegment.setOnSelectListener(
                (view, index) -> editPro(draft.pro.withGlowPosition(index), true));
        glowControls.addView(glowPositionSegment, matchWrap(4));
        panel.addView(glowControls, matchWrap(10));

        syncBackgroundVisibility();
        return panel;
    }

    /**
     * The premium effect colour, in the same row shape the Colors card uses.
     *
     * <p>Deliberately not a second colour picker. The row opens {@link UiKit#showOrbitColorMenu} with
     * Orbit's own palette and hands a custom choice to {@link OrbitColorPicker}, which is exactly what
     * the Accent and Background rows above do, so a colour chosen here is stored in the same token
     * vocabulary and recognised by the same name everywhere in the app.
     *
     * <p>One row's worth of references is held rather than two, because Linear and Glow each show one
     * of these and only one of them is ever visible. Both call this, both end up pointing at the same
     * held swatch and label, and whichever is on screen is the one that gets updated.
     */
    private View effectColourRow(String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_3,
                UiKit.withAlpha(UiKit.accent(this), 60), UiKit.accent(this), 14, this));
        row.setMinimumHeight(UiKit.dp(this, 54));
        UiKit.pressScale(row);
        row.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            chooseEffectColour(v);
        });

        View swatch = new View(this);
        swatch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams swatchLp =
                new LinearLayout.LayoutParams(UiKit.dp(this, 34), UiKit.dp(this, 34));
        swatchLp.rightMargin = UiKit.dp(this, 12);
        row.addView(swatch, swatchLp);
        effectColourSwatch = swatch;

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        labels.addView(UiKit.text(this, title, 13.5f, UiKit.TEXT, false));
        TextView summary = UiKit.text(this, "", 12, UiKit.MUTED, false);
        labels.addView(summary);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        effectColourSummary = summary;

        TextView chevron = UiKit.text(this, UiKit.SELECTOR_CHEVRON, 15, UiKit.accent(this), false);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);

        row.setContentDescription(title + ". " + description + ". Opens a color editor.");
        return row;
    }

    /** A label, its current value in words, and a chevron. Used by Direction. */
    private LinearLayout selectorRow(String title, TextView value, View.OnClickListener onOpen) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 11), UiKit.dp(this, 12), UiKit.dp(this, 12));
        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_3,
                UiKit.withAlpha(UiKit.accent(this), 60), UiKit.accent(this), 14, this));
        row.setMinimumHeight(UiKit.dp(this, 48));
        UiKit.pressScale(row);
        row.setOnClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.VIRTUAL_KEY);
            onOpen.onClick(v);
        });
        row.addView(UiKit.text(this, title, 13.5f, UiKit.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(value);
        TextView chevron = UiKit.text(this, UiKit.SELECTOR_CHEVRON, 15, UiKit.accent(this), false);
        chevron.setPadding(UiKit.dp(this, 8), 0, 0, 0);
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chevron);
        return row;
    }

    /**
     * Orbit's own palette, plus a custom colour, for the one premium effect colour.
     *
     * <p>{@code accent} leads the list rather than sitting in it, because an effect that follows the
     * theme's accent is not one colour among twelve - it is the answer that goes on being right after
     * the accent changes, and it is the default for that reason.
     */
    private void chooseEffectColour(View anchor) {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, draft);
        String[] keys = UiKit.accentKeys();
        List<String> labels = new ArrayList<>();
        List<Integer> colours = new ArrayList<>();
        List<String> tokensForIndex = new ArrayList<>();

        labels.add("Theme accent");
        colours.add(tokens.accent);
        tokensForIndex.add(OrbitTheme.ACCENT);
        for (String key : keys) {
            if (OrbitTheme.DYNAMIC.equals(key)) continue;
            labels.add(OrbitPalette.labelFor(key));
            colours.add(UiKit.accentForName(this, key));
            tokensForIndex.add(key);
        }
        int current = draft.pro.backgroundEffectColor(this, tokens.accent);
        labels.add("Custom color…");
        colours.add(current);

        int selected = tokensForIndex.indexOf(draft.pro.backgroundEffectColor);
        final int customIndex = labels.size() - 1;
        showColourChoice(anchor, "Background effect", labels, colours, selected, index -> {
            if (index == customIndex) {
                pickCustom("Background effect", current, effectColourSuggestions(tokens),
                        colour -> editPro(draft.pro.withBackgroundEffectColor(
                                OrbitTheme.colorToken(colour)), true));
            } else {
                editPro(draft.pro.withBackgroundEffectColor(tokensForIndex.get(index)), true);
            }
        });
    }

    /**
     * Starting points a background effect actually looks good at.
     *
     * <p>The theme's own colours first, then the deep tones Orbit's shipped presets are built from.
     * A background effect is the one place a mid-bright colour is usually wrong, so what is offered
     * here leans towards the dark end rather than towards the accent palette.
     */
    private List<Integer> effectColourSuggestions(OrbitThemeTokens tokens) {
        List<Integer> out = new ArrayList<>();
        out.add(tokens.accent);
        out.add(OrbitContrast.blend(tokens.accent, tokens.background, 0.4f));
        out.add(tokens.surface3);
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            if (OrbitTheme.isHexToken(preset.surface)) {
                out.add(OrbitTheme.hexTokenColor(preset.surface));
            }
        }
        return out;
    }

    private void chooseDirection(View anchor) {
        String[] labels = new String[OrbitProStyle.DIRECTION_COUNT];
        for (int i = 0; i < labels.length; i++) labels[i] = OrbitProStyle.directionLabel(i);
        UiKit.showOrbitMenu(this, anchor, labels, draft.pro.gradientDirection,
                (index, label) -> editPro(draft.pro.withGradientDirection(index), true));
    }

    /** One of the two tools. A quiet inner surface, not a second card with its own border. */
    private LinearLayout toolPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(UiKit.dp(this, 13), UiKit.dp(this, 12),
                UiKit.dp(this, 13), UiKit.dp(this, 14));
        panel.setBackground(UiKit.rounded(UiKit.SURFACE_2, 18, this));
        return panel;
    }

    private TextView toolTitle(String value) {
        return UiKit.text(this, value, 13.5f, UiKit.TEXT, true);
    }

    /** The current value, in words, in the accent. Restrained: it is a reading, not a heading. */
    private TextView valueLabel(String value) {
        return UiKit.text(this, value, 12, UiKit.accent(this), false);
    }

    private interface ProEdit { void apply(int value, boolean settled); }

    /**
     * One advanced control: its name, its value in words, and the slider.
     *
     * <p>The hierarchy is deliberately flatter than Beta 2's. The title was bold and nearly the
     * size of a section heading, and every control carried a sentence of explanation underneath, so
     * five settings read as five headings with paragraphs between them. The title is normal weight
     * now, the value word beside it carries the accent, and the sentence is gone: "Bubble
     * roundness" followed by "Rounded" already says what it was saying.
     *
     * <p>The slider is passed in already constructed so the caller keeps a reference to it. That is
     * the mechanical half of this release: a control is created once and afterwards only ever told
     * what value to show.
     */
    private View proControl(String title, TextView reading, OrbitSlider slider,
                            int min, int max, int value,
                            OrbitSlider.Labeller labeller, ProEdit edit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.HORIZONTAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(UiKit.text(this, title, 13.5f, UiKit.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        labels.addView(reading);
        labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.addView(labels, matchWrap(0));

        slider.setTitle(title);
        slider.setLabeller(labeller);
        slider.setRange(min, max, value);
        slider.setOnValueChangeListener((view, next, settled) -> edit.apply(next, settled));
        row.addView(slider, matchWrap(2));
        return row;
    }

    /**
     * Applies one premium edit to the draft.
     *
     * <p>The draft, never a preference. Dragging a slider changes what the previews show and
     * nothing else in Orbit; Apply is still the only thing that commits, Revert still returns to
     * the applied theme, and leaving with an unapplied change still meets the same guarded Back
     * this screen has always had.
     *
     * <p>The two paths differ only in how much of the screen they touch, and that split is what
     * keeps a drag smooth. While the finger is down nothing re-derives the draft's identity, so
     * nothing reads the saved-theme file and nothing touches the gallery. On release that happens
     * once. Neither path creates or destroys a control, so the four sliders the user is not
     * touching cannot move and the one they are touching cannot be replaced underneath them.
     */
    private void editPro(OrbitProStyle next, boolean settled) {
        draft = draft.withPro(next);
        syncProLabels();
        if (!settled) {
            syncPreviews();
            return;
        }
        // Re-label which preset this now is. canonicalIdentity carries the premium styling
        // through, so settling cannot change the value the user just chose - which it did in
        // Beta 2, and which is what made the thumb appear to snap back the moment it was released.
        draft = OrbitThemeStore.canonicalIdentity(this, draft);
        syncProLabels();
        // Which background controls apply can only have changed on a settled edit - a mode or a
        // position is chosen, never dragged - and this changes visibility rather than creating
        // anything, so the slider that was just released is untouched by it.
        syncBackgroundVisibility();
        syncPreviews();
        syncPresetSelection();
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

    /**
     * Rebuilds the gallery, which is a disk read and a card per theme.
     *
     * <p>Called when the set of themes changes - saved, renamed, duplicated, deleted, imported -
     * and never merely because a different one became selected. Beta 2 called this from the same
     * method a slider called, so releasing a slider reloaded the saved-theme file and rebuilt every
     * card in the gallery. {@link #syncPresetSelection} is what selection changes use instead.
     */
    private void rebuildPresetGallery() {
        if (presetGrid == null) return;
        presetGrid.removeAllViews();
        presetCards.clear();
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
            PresetCard card = presetCard(presets.get(i));
            presetCards.add(card);
            row.addView(card.view, lp);
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

    /**
     * One preset card, plus everything needed to re-mark it as selected without rebuilding it.
     *
     * <p>Selection is the only thing about a card that changes while the screen is up, and in
     * Beta 2 the only way to change it was to rebuild the whole gallery. Holding the three views
     * it actually affects turns that into three assignments.
     */
    private static final class PresetCard {
        final OrbitTheme preset;
        final LinearLayout view;
        final TextView name;
        final TextView tick;
        /** What the card says before the selection state, and what it says after it. */
        final String spokenBase;
        final String spokenTail;

        PresetCard(OrbitTheme preset, LinearLayout view, TextView name, TextView tick,
                   String spokenBase, String spokenTail) {
            this.preset = preset;
            this.view = view;
            this.name = name;
            this.tick = tick;
            this.spokenBase = spokenBase;
            this.spokenTail = spokenTail;
        }
    }

    /**
     * Re-marks which preset card is selected.
     *
     * <p>No disk read, no card construction, no layout change: the three things that say "this one"
     * are reassigned and nothing else moves. This is what runs when a slider settles or a colour
     * changes, which is why neither of those disturbs the gallery any more.
     */
    private void syncPresetSelection() {
        for (PresetCard card : presetCards) {
            boolean selected = draft != null && draft.sameColours(card.preset);
            card.view.setBackground(presetCardBackground(selected));
            // Through UiKit, so the new weight is recorded as the label's intent and the next
            // typography pass re-applies it instead of reverting it.
            UiKit.setTextWeight(card.name, selected);
            card.tick.setVisibility(selected ? View.VISIBLE : View.GONE);
            card.view.setContentDescription(
                    card.spokenBase + (selected ? ", selected" : "") + card.spokenTail);
        }
    }

    private Drawable presetCardBackground(boolean selected) {
        return UiKit.rippleOutlined(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.18f) : UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), selected ? 190 : 54),
                UiKit.accent(this), 16, this);
    }

    private PresetCard presetCard(OrbitTheme preset) {
        boolean locked = !OrbitThemeStore.canApply(this, preset);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(this, preset);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10),
                UiKit.dp(this, 10), UiKit.dp(this, 11));
        card.setMinimumHeight(UiKit.dp(this, 96));
        // Selection is stated, not only drawn. A ring around a card of colours is exactly the kind
        // of state that disappears for anyone who cannot rely on colour to carry it.
        String spokenBase = preset.name
                + (preset.premium() ? ", Orbit Pro preset"
                        : preset.builtIn ? ", Orbit preset" : ", your theme")
                + (locked ? ", locked" : "")
                + (preset.note().isEmpty() ? "" : ", " + preset.note());
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
        // Built unselected. The tick is created once and hidden rather than added and removed,
        // so marking a different card selected changes visibility instead of the view tree.
        TextView name = UiKit.text(this, preset.name, 13, UiKit.TEXT, false);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tick = UiKit.text(this, "✓", 14, UiKit.accent(this), true);
        tick.setVisibility(View.GONE);
        titleRow.addView(tick);
        card.addView(titleRow, matchWrap(8));

        // The one built-in note Orbit ships sits on this line rather than becoming a badge, so a
        // card stays a swatch strip, a name and one quiet line whatever theme it describes.
        String note = preset.note();
        // A premium card says what it is on the line every card already has, rather than growing a
        // badge. Locked is stated in words here and in the card's spoken description, so the state
        // never depends on noticing a colour.
        String kindText = preset.premium()
                ? (locked ? "Orbit Pro · locked" : "Orbit Pro preset")
                : preset.builtIn
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

        String spokenTail = ". Background " + OrbitColorName.of(tokens.background)
                + ", accent " + OrbitColorName.of(tokens.accent) + ".";
        // The selected state itself is left to syncPresetSelection, so there is exactly one place
        // that decides what a selected card looks like and says.
        return new PresetCard(preset, card, name, tick, spokenBase, spokenTail);
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
        onThemeLibraryChanged();
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
                    onThemeLibraryChanged();
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
            onThemeLibraryChanged();
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
            onThemeLibraryChanged();
            syncAllToDraft();
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
        if (loadIntoPreview) { selectPreset(saved); onThemeLibraryChanged(); }
        else onThemeLibraryChanged();
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

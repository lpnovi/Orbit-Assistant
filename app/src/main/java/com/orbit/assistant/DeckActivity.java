package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orbit Deck: the user's own arrangement of the Orbit they actually use.
 *
 * <p>Chats is still Orbit's home. Deck is one tap from it and otherwise stays out of the way — it
 * is not a tab, it does not open at launch, and nothing about Chats changes when it exists.
 *
 * <h2>What this file is and is not</h2>
 *
 * <p>It draws a grid and runs an editor. It contains no idea of what a tile <i>does</i>: taps go to
 * {@link DeckActionExecutor}, truth comes from {@link DeckTileResolver}, the list of possible tiles
 * comes from {@link DeckTileRegistry}, and every change is written by {@link DeckLayoutStore} the
 * moment it happens. That last part is deliberate: because each edit is one atomic commit, Deck has
 * no unsaved state, so Back stays ordinary predictive navigation instead of needing a dirty-editor
 * guard, and leaving mid-drag can never lose an arrangement.
 *
 * <h2>Cost of opening it</h2>
 *
 * <p>Nothing here touches the network or a provider. The first frame is drawn from local storage;
 * live state (torch, media) arrives afterwards and only while the screen is actually visible. The
 * torch is watched with the camera service's own callback rather than polled, and media is read
 * once per resume on a background thread, so Deck has no timer and no background worker.
 */
public final class DeckActivity extends Activity {

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private OrbitPredictiveBack predictiveBack;
    private OrbitBackHandler backHandler;

    private ScrollView scroll;
    private LinearLayout page;
    private LinearLayout headerControls;
    private TextView headerSubtitle;
    private LinearLayout suggestedBlock;
    private LinearLayout deckHeadingRow;
    private DeckGridLayout grid;
    private LinearLayout emptyBlock;
    private TextView firstRunHint;
    private FrameLayout root;
    private FrameLayout sheetHost;
    private ScrollView sheetScroll;
    private View sheetPanel;
    private DeckGridLayout folderGrid;
    private String openFolderId;
    private String activeLayoutId = DeckLayout.PRIMARY_ID;
    private String sheetLayoutId = DeckLayout.PRIMARY_ID;
    private long sheetGeneration;

    private boolean editing;
    private DeckTileView carriedTile;
    private String appliedAppearance = "";
    private DeckTileResolver.LiveState live = DeckTileResolver.LiveState.unknown();

    private CameraManager cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private String torchCameraId;

    // ---- lifecycle --------------------------------------------------------------------------------

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window w = getWindow();
        w.setStatusBarColor(UiKit.BG);
        w.setNavigationBarColor(UiKit.BG);
        setContentView(buildContent());
        UiKit.applyActivityInsets(this, root, true);
        appliedAppearance = UiKit.structuralAppearanceSignature(this);
        installBackHandling();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        // Accent or AMOLED may have changed in Settings while Deck sat underneath it.
        String appearance = UiKit.structuralAppearanceSignature(this);
        if (!appearance.equals(appliedAppearance)) {
            appliedAppearance = appearance;
            UiKit.syncTheme(this);
            getWindow().setStatusBarColor(UiKit.BG);
            getWindow().setNavigationBarColor(UiKit.BG);
            setContentView(buildContent());
            UiKit.applyActivityInsets(this, root, true);
        }
        refresh();
        startWatchingTorch();
        readMediaState();
    }

    @Override protected void onPause() {
        // Live state is only ever read while the screen is in front of somebody.
        cancelActiveDrag();
        stopWatchingTorch();
        UiPresence.leave(this);
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (backHandler != null && backHandler.consumeLegacyBack()) return;
        super.onBackPressed();
    }

    /**
     * Back belongs to exactly one owner at a time.
     *
     * <p>The same shape {@code ChatActivity} uses for its attachment chooser. A sheet or edit mode
     * is something Deck can close itself, so while either is open Deck consumes Back; the rest of
     * the time nothing is registered and the platform runs its real predictive transition back to
     * Chats. Edit mode has nothing unsaved in it, so closing it discards nothing.
     */
    private void installBackHandling() {
        backHandler = OrbitBackHandler.attach(this, () -> {
            if (sheetOpen()) closeSheet();
            else if (editing) setEditing(false);
            syncBackHandler();
        });
        predictiveBack = OrbitPredictiveBack.attach(this, new OrbitPredictiveBack.Screen() {
            @Override public void navigateBack() { finish(); }
            @Override public String screenName() {
                return OrbitNavigation.labelFor(DeckActivity.class);
            }
        });
        syncBackHandler();
    }

    private void syncBackHandler() {
        boolean ownsBack = sheetOpen() || editing;
        if (ownsBack) {
            if (predictiveBack != null) predictiveBack.setArmed(false);
            if (backHandler != null) backHandler.setArmed(true);
        } else {
            if (backHandler != null) backHandler.setArmed(false);
            if (predictiveBack != null) predictiveBack.setArmed(true);
        }
    }

    // ---- page -------------------------------------------------------------------------------------

    private View buildContent() {
        root = new FrameLayout(this);
        OrbitBackground.applyPage(root);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // A tile has a size it wants to be. Letting the grid use every pixel of a large tablet
        // would keep the column count and simply stretch each tile into a wide bar, so the content
        // is capped and centred instead: a phone at 480dp and a Tab S9 Plus in landscape end up
        // with tiles of about the same size, and the tablet spends its extra width on more columns.
        int side = UiKit.dp(this, 18) + horizontalInset();
        page.setPadding(side, UiKit.dp(this, 24), side, UiKit.dp(this, 44));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(buildHeader());

        firstRunHint = UiKit.text(this, "Long-press a tile to customize your Deck.",
                12.5f, UiKit.MUTED, false);
        firstRunHint.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 14), 0, 0);
        firstRunHint.setVisibility(View.GONE);
        page.addView(firstRunHint);

        suggestedBlock = new LinearLayout(this);
        suggestedBlock.setOrientation(LinearLayout.VERTICAL);
        suggestedBlock.setVisibility(View.GONE);
        LinearLayout.LayoutParams suggestedLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        suggestedLp.topMargin = UiKit.dp(this, 22);
        page.addView(suggestedBlock, suggestedLp);

        deckHeadingRow = new LinearLayout(this);
        deckHeadingRow.setOrientation(LinearLayout.VERTICAL);
        deckHeadingRow.addView(sectionLabel("MY DECK"));
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.topMargin = UiKit.dp(this, 22);
        page.addView(deckHeadingRow, headingLp);

        grid = new DeckGridLayout(this);
        grid.setSpacing(UiKit.dp(this, 12));
        grid.setMinRowHeight(UiKit.dp(this, 128));
        grid.setColumns(DeckGridLayout.columnsForWidth(contentWidthDp()));
        page.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        emptyBlock = buildEmptyState();
        emptyBlock.setVisibility(View.GONE);
        page.addView(emptyBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        sheetHost = new FrameLayout(this);
        sheetHost.setVisibility(View.GONE);
        root.addView(sheetHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        UiKit.watchTypography(root);
        return root;
    }

    /**
     * The widest Deck should ever draw its content.
     *
     * <p>Chosen so a large tablet gains columns rather than width: at this cap a four-column grid
     * has tiles almost exactly the size a phone's two-column grid does, which is what stops the
     * same layout reading as a premium grid on one device and a stretched settings page on another.
     */
    static final int MAX_CONTENT_WIDTH_DP = 1000;

    /** The width the grid actually gets, in dp, after the cap. */
    private int contentWidthDp() {
        return Math.min(getResources().getConfiguration().screenWidthDp, MAX_CONTENT_WIDTH_DP);
    }

    /** Padding that centres the capped content on a screen wider than the cap. */
    private int horizontalInset() {
        int screen = getResources().getConfiguration().screenWidthDp;
        int extra = Math.max(0, screen - MAX_CONTENT_WIDTH_DP);
        return UiKit.dp(this, extra / 2f);
    }

    private LinearLayout buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> {
            if (backHandler != null) backHandler.performBack();
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 48), UiKit.dp(this, 48));
        backLp.rightMargin = UiKit.dp(this, 10);
        header.addView(back, backLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Orbit Deck", 26, UiKit.TEXT, true));
        headerSubtitle = UiKit.text(this, "Your shortcuts", 12, UiKit.MUTED, false);
        titles.addView(headerSubtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        headerControls = new LinearLayout(this);
        headerControls.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(headerControls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildHeaderControls();
        return header;
    }

    /** The header's right-hand controls, which differ between browsing and editing. */
    private void buildHeaderControls() {
        headerControls.removeAllViews();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 46), UiKit.dp(this, 46));
        lp.leftMargin = UiKit.dp(this, 2);

        if (editing) {
            ImageButton options = iconButton(R.drawable.ic_more, "Deck options");
            options.setOnClickListener(this::showDeckOptions);
            headerControls.addView(options, lp);

            Button done = new Button(this);
            done.setText("Done");
            done.setAllCaps(false);
            done.setTextSize(14);
            done.setTextColor(UiKit.onAccent(this));
            done.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 16, this));
            done.setMinHeight(0);
            done.setMinimumHeight(0);
            done.setStateListAnimator(null);
            done.setPadding(UiKit.dp(this, 16), 0, UiKit.dp(this, 16), 0);
            UiKit.pressScale(done);
            done.setOnClickListener(v -> setEditing(false));
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 38));
            doneLp.leftMargin = UiKit.dp(this, 6);
            headerControls.addView(done, doneLp);
            return;
        }

        ImageButton customize = iconButton(R.drawable.ic_tune, "Customize Deck");
        customize.setOnClickListener(v -> setEditing(true));
        headerControls.addView(customize, lp);

        ImageButton add = iconButton(R.drawable.ic_add, "Add tile");
        add.setOnClickListener(v -> openAddSheet());
        headerControls.addView(add, lp);
    }

    private LinearLayout buildEmptyState() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.CENTER_HORIZONTAL);
        block.setBackground(UiKit.rounded(UiKit.blend(UiKit.SURFACE, UiKit.BG, 0.75f), 22, this));
        int pad = UiKit.dp(this, 26);
        block.setPadding(pad, UiKit.dp(this, 32), pad, UiKit.dp(this, 32));

        TextView title = UiKit.text(this, "Build your Deck", 19, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        block.addView(title);

        TextView note = UiKit.text(this, "Add the Orbit tools you want one tap away.",
                13.5f, UiKit.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 20));
        block.addView(note);

        Button add = new Button(this);
        add.setText("Add tile");
        add.setAllCaps(false);
        add.setTextSize(15);
        add.setTextColor(UiKit.onAccent(this));
        add.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        add.setMinHeight(0);
        add.setMinimumHeight(0);
        add.setStateListAnimator(null);
        add.setPadding(UiKit.dp(this, 26), 0, UiKit.dp(this, 26), 0);
        UiKit.pressScale(add);
        add.setOnClickListener(v -> openAddSheet());
        block.addView(add, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 46)));
        return block;
    }

    // ---- rendering --------------------------------------------------------------------------------

    /** Rebuilds the grid from storage. Cheap, local, and the only path that draws tiles. */
    private void refresh() {
        if (grid == null) return;
        DeckLayout deck = DeckLayoutStore.deck(this);
        activeLayoutId = deck.id;
        String renderedLayoutId = deck.id;
        grid.setOnReorderListener(ordered -> persistOrder(renderedLayoutId, ordered));

        grid.removeAllViews();
        for (DeckItem item : deck.items) {
            View view;
            if (item instanceof DeckTileItem) view = tileView(((DeckTileItem) item).tile);
            else if (item instanceof DeckSection) view = sectionView((DeckSection) item);
            else if (item instanceof DeckFolder) view = folderView((DeckFolder) item);
            else continue;
            view.setTag(item.id);
            grid.addView(view, gridParams(item));
        }

        boolean empty = deck.items.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        deckHeadingRow.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBlock.setVisibility(empty ? View.VISIBLE : View.GONE);
        // Shown until the Deck has been touched at all, then never again.
        firstRunHint.setVisibility(!empty && !DeckLayoutStore.configured(this)
                ? View.VISIBLE : View.GONE);

        updateHeaderSubtitle(deck);
        renderSuggestions(deck.allTiles());
    }

    private void updateHeaderSubtitle(DeckLayout deck) {
        boolean switchable = OrbitProEntitlement.hasPro(this)
                && DeckLayoutStore.layoutCount(this) > 1;
        String text = switchable ? deck.name : "Your shortcuts";
        if (editing) text = switchable ? "Editing · " + deck.name : "Editing";
        headerSubtitle.setText(text);
        headerSubtitle.setOnClickListener(switchable ? v -> openLayoutSwitcher() : null);
        headerSubtitle.setClickable(switchable);
        headerSubtitle.setFocusable(switchable);
        headerSubtitle.setContentDescription(switchable
                ? "Switch Deck layout. Active layout " + deck.name : text);
        headerSubtitle.setPadding(0, UiKit.dp(this, 2), switchable ? UiKit.dp(this, 8) : 0,
                switchable ? UiKit.dp(this, 3) : 0);
        headerSubtitle.setBackground(switchable
                ? UiKit.ripple(Color.TRANSPARENT, UiKit.accent(this), 10, this) : null);
    }

    private DeckGridLayout.LayoutParams gridParams(DeckItem item) {
        if (item instanceof DeckSection) return new DeckGridLayout.LayoutParams(grid.columns(), 0);
        if (item instanceof DeckFolder) return new DeckGridLayout.LayoutParams(1, 1);
        DeckTile tile = ((DeckTileItem) item).tile;
        if (tile.size == DeckTile.Size.LARGE) return new DeckGridLayout.LayoutParams(2, 2);
        return new DeckGridLayout.LayoutParams(tile.size == DeckTile.Size.WIDE ? 2 : 1, 1);
    }

    private DeckTileView tileView(DeckTile tile) {
        DeckTileView view = new DeckTileView(this, tile,
                DeckTileResolver.resolve(this, tile, live), tileListener());
        view.setEditing(editing);
        // Installed in both modes so the original normal-mode long press can become a pickup
        // without releasing and touching the rebuilt tile a second time.
        installDrag(view, activeLayoutId);
        return view;
    }

    private View sectionView(DeckSection section) {
        TextView view = sectionLabel(section.title.toUpperCase(Locale.US));
        view.setPadding(UiKit.dp(this, 3), UiKit.dp(this, 12), UiKit.dp(this, 3), UiKit.dp(this, 8));
        view.setAccessibilityHeading(true);
        view.setContentDescription(section.title + ", section heading"
                + (editing ? ", editing. Double tap for options." : ""));
        view.setFocusable(true);
        view.setOnClickListener(v -> { if (editing) showStructuralOptions(section, v); });
        view.setOnLongClickListener(v -> { if (!editing) setEditing(true); showStructuralOptions(section, v); return true; });
        return view;
    }

    private View folderView(DeckFolder folder) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(OrbitFloatingSurface.interactive(this, 22));
        int pad = UiKit.dp(this, 14);
        card.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(card);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_deck);
        icon.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(this, 38), UiKit.dp(this, 38)));
        TextView title = UiKit.text(this, folder.title, 15, UiKit.TEXT, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, UiKit.dp(this, 12), 0, 0);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title);
        String count = folder.tiles.size() == 1 ? "1 tile" : folder.tiles.size() + " tiles";
        TextView meta = UiKit.text(this, count, 12, UiKit.MUTED, false);
        meta.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(meta);
        card.setContentDescription(folder.title + ", folder, " + count
                + (editing ? ", editing. Double tap for options." : ""));
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            if (editing) showStructuralOptions(folder, v); else openFolder(folder.id);
        });
        card.setOnLongClickListener(v -> {
            if (!editing) setEditing(true);
            showStructuralOptions(folder, v);
            return true;
        });
        return card;
    }

    private DeckTileView.Listener tileListener() {
        return new DeckTileView.Listener() {
            @Override public void onTileTapped(DeckTile tile, DeckTileView view) {
                if (editing) showTileOptions(tile, view);
                else runTile(tile, view);
            }

            @Override public void onTileLongPressed(DeckTile tile, DeckTileView view) {
                if (!editing) {
                    UiKit.haptic(view, HapticFeedbackConstants.LONG_PRESS);
                    setEditing(true);
                }
            }

            @Override public void onTileRemoveTapped(DeckTile tile, DeckTileView view) {
                removeTile(tile);
            }
        };
    }

    private TextView sectionLabel(String text) {
        TextView label = UiKit.text(this, text, 11, UiKit.MUTED, true);
        label.setLetterSpacing(0.13f);
        label.setPadding(UiKit.dp(this, 3), 0, 0, UiKit.dp(this, 10));
        return label;
    }

    // ---- suggestions ------------------------------------------------------------------------------

    /**
     * Draws Suggested, or draws nothing at all.
     *
     * <p>There is deliberately no empty state here. When there is nothing genuinely useful the
     * section does not exist, which is the difference between a hint and a widget that always has
     * to say something.
     */
    private void renderSuggestions(List<DeckTile> deck) {
        suggestedBlock.removeAllViews();
        List<DeckSuggestionEngine.Suggestion> suggestions = editing
                ? Collections.emptyList()
                : DeckSuggestionEngine.suggestions(this, live, deck,
                        DeckSuggestionEngine.maxFor(grid.columns()), System.currentTimeMillis());
        if (suggestions.isEmpty()) {
            suggestedBlock.setVisibility(View.GONE);
            return;
        }

        suggestedBlock.setVisibility(View.VISIBLE);
        suggestedBlock.addView(sectionLabel("SUGGESTED"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        suggestedBlock.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int i = 0; i < suggestions.size(); i++) {
            DeckSuggestionEngine.Suggestion suggestion = suggestions.get(i);
            View card = suggestionCard(suggestion);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = UiKit.dp(this, 12);
            row.addView(card, lp);
        }
        if (UiKit.animationsEnabled()) UiKit.enterBlock(suggestedBlock);
    }

    /**
     * A suggestion, tonally lifted above an ordinary tile so it reads as temporary.
     *
     * <p>It cannot be dragged and it is not part of My Deck. Long-pressing offers to make it
     * permanent, which is the only route from Suggested into the user's own layout: Deck never
     * promotes anything on its own.
     */
    private View suggestionCard(DeckSuggestionEngine.Suggestion suggestion) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiKit.ripple(
                UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.14f),
                UiKit.accent(this), 20, this));
        int pad = UiKit.dp(this, 13);
        card.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(card);

        ImageView icon = new ImageView(this);
        icon.setImageResource(suggestion.iconRes);
        icon.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(icon, new LinearLayout.LayoutParams(UiKit.dp(this, 20), UiKit.dp(this, 20)));

        TextView title = UiKit.text(this, suggestion.title, 14.5f, UiKit.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, UiKit.dp(this, 10), 0, 0);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title);

        if (!suggestion.subtitle.isEmpty()) {
            TextView sub = UiKit.text(this, suggestion.subtitle, 11.5f, UiKit.MUTED, false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(sub);
        }

        card.setContentDescription(suggestion.contentDescription);
        card.setFocusable(true);
        card.setOnClickListener(v -> runTile(suggestion.action, null));
        if (suggestion.addable != null) {
            card.setOnLongClickListener(v -> {
                UiKit.haptic(v, HapticFeedbackConstants.LONG_PRESS);
                offerAddSuggestion(suggestion);
                return true;
            });
        }
        return card;
    }

    private void offerAddSuggestion(DeckSuggestionEngine.Suggestion suggestion) {
        String expectedLayoutId = activeLayoutId;
        UiKit.showOrbitMenu(this, suggestedBlock, new String[]{"Add to Deck"}, -1, (index, label) -> {
            if (DeckLayoutStore.wouldDuplicate(this, suggestion.addable)) {
                toast("That is already on your Deck.");
                return;
            }
            if (DeckLayoutStore.add(this, expectedLayoutId, suggestion.addable)) {
                toast("Added to Deck");
                refresh();
            }
        });
    }

    // ---- saved layouts ---------------------------------------------------------------------------

    private interface LayoutNameSaver { boolean save(String name); }

    private void openLayoutSwitcher() {
        if (!OrbitProEntitlement.hasPro(this) || DeckLayoutStore.layoutCount(this) < 2) return;
        DeckCollection collection = DeckLayoutStore.collection(this);
        LinearLayout column = openSheet("Switch Deck", "Active layout: " + collection.active().name);
        for (DeckLayout layout : collection.layouts) {
            column.addView(layoutRow(layout, false), rowLp());
        }
        Button manage = primaryButton("Manage layouts");
        manage.setOnClickListener(v -> openLayoutManager());
        column.addView(manage, fieldLp(UiKit.dp(this, 48)));
    }

    private void openLayoutManager() {
        if (!OrbitProEntitlement.hasPro(this)) {
            LinearLayout column = openSheet("Deck layouts · Orbit Pro",
                    "Multiple saved Decks and layout templates are part of Orbit Pro.");
            column.addView(emptyNote("Your active Deck and all Free organization tools remain fully available. Enable Pro Preview in Diagnostics to try saved layouts."));
            return;
        }
        DeckCollection collection = DeckLayoutStore.collection(this);
        String count = collection.layouts.size() == 1 ? "1 saved layout"
                : collection.layouts.size() + " saved layouts";
        LinearLayout column = openSheet("Deck layouts", count);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button create = primaryButton("New layout");
        Button templates = primaryButton("Templates");
        boolean room = collection.layouts.size() < DeckLayoutStore.MAX_LAYOUTS;
        create.setEnabled(room);
        templates.setEnabled(room);
        create.setOnClickListener(v -> openNewLayoutSheet());
        templates.setOnClickListener(v -> openTemplateSheet());
        actions.addView(create, new LinearLayout.LayoutParams(0, UiKit.dp(this, 46), 1f));
        LinearLayout.LayoutParams templateLp = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 46), 1f);
        templateLp.leftMargin = UiKit.dp(this, 8);
        actions.addView(templates, templateLp);
        column.addView(actions, rowLp());
        if (!room) column.addView(emptyNote("You have reached the maximum of 10 saved layouts."));

        column.addView(sectionLabel("SAVED LAYOUTS"));
        for (DeckLayout layout : collection.layouts) {
            column.addView(layoutRow(layout, true), rowLp());
        }
    }

    private View layoutRow(DeckLayout layout, boolean management) {
        DeckCollection collection = DeckLayoutStore.collection(this);
        boolean active = layout.id.equals(collection.activeLayoutId);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.ripple(active
                ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.20f)
                : UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        int pad = UiKit.dp(this, 13);
        row.setPadding(pad, pad, pad, pad);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiKit.text(this, layout.name, 15, UiKit.TEXT, true);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);
        int tiles = layout.allTiles().size();
        String metadata = (active ? "Active · " : "") + tiles
                + (tiles == 1 ? " tile" : " tiles");
        text.addView(UiKit.text(this, metadata, 12, UiKit.MUTED, false));
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (management) {
            ImageButton options = iconButton(R.drawable.ic_more,
                    "Options for " + layout.name);
            options.setOnClickListener(v -> showLayoutOptions(layout, v));
            row.addView(options, new LinearLayout.LayoutParams(
                    UiKit.dp(this, 44), UiKit.dp(this, 44)));
        }
        row.setContentDescription(layout.name + ", " + metadata
                + (active ? "" : ". Double tap to switch."));
        row.setFocusable(true);
        if (!active) row.setOnClickListener(v -> switchToLayout(layout.id));
        return row;
    }

    private void showLayoutOptions(DeckLayout layout, View anchor) {
        DeckCollection collection = DeckLayoutStore.collection(this);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (!layout.id.equals(collection.activeLayoutId)) {
            labels.add("Switch");
            actions.add(() -> switchToLayout(layout.id));
        }
        labels.add("Rename");
        actions.add(() -> openLayoutNameSheet("Rename layout", layout.name,
                name -> DeckLayoutStore.renameLayout(this, layout.id, name)));
        labels.add("Duplicate");
        actions.add(() -> duplicateLayout(layout));
        if (collection.layouts.size() > 1) {
            labels.add("Delete");
            actions.add(() -> confirmDeleteLayout(layout));
        }
        UiKit.showOrbitMenu(this, anchor, labels.toArray(new String[0]), -1,
                (index, label) -> {
                    if (index >= 0 && index < actions.size()) actions.get(index).run();
                });
    }

    private void openNewLayoutSheet() {
        if (!ensureLayoutRoom()) return;
        DeckLayout active = DeckLayoutStore.deck(this);
        LinearLayout column = openSheet("New layout", "Choose a simple starting point");
        column.addView(pickerRow(R.drawable.ic_add, "Blank", "Start with an empty Deck", null,
                () -> openLayoutNameSheet("Name blank layout", "New Deck", name ->
                        DeckLayoutStore.createBlankLayout(this, name) != null)), rowLp());
        column.addView(pickerRow(R.drawable.ic_deck, "Duplicate current",
                "Copy " + active.name + " with independent items", null,
                () -> duplicateLayout(active)), rowLp());
        column.addView(pickerRow(R.drawable.ic_deck_sparkle, "Template",
                "Start from a curated Orbit layout", null, this::openTemplateSheet), rowLp());
    }

    private void duplicateLayout(DeckLayout source) {
        if (!ensureLayoutRoom()) return;
        String suggested = DeckLayout.sanitizeName(source.name + " copy");
        openLayoutNameSheet("Name duplicate", suggested, name ->
                DeckLayoutStore.duplicateLayout(this, source.id, name) != null);
    }

    private void openTemplateSheet() {
        if (!ensureLayoutRoom()) return;
        LinearLayout column = openSheet("Layout templates",
                "Each template creates a new saved Deck");
        for (DeckLayoutTemplates.Template template : DeckLayoutTemplates.all()) {
            column.addView(pickerRow(R.drawable.ic_deck_sparkle, template.name,
                    template.description, null,
                    () -> openLayoutNameSheet("Name layout", template.name, name ->
                            DeckLayoutStore.createTemplateLayout(
                                    this, template.id, name) != null)), rowLp());
        }
    }

    private void openLayoutNameSheet(String title, String initial, LayoutNameSaver saver) {
        LinearLayout column = openSheet(title, "Up to 30 characters");
        EditText name = new EditText(this);
        name.setHint("Layout name");
        name.setText(initial);
        name.selectAll();
        styleField(name, false);
        column.addView(name, fieldLp(UiKit.dp(this, 48)));
        Button save = primaryButton("Save");
        column.addView(save, fieldLp(UiKit.dp(this, 48)));
        bindSingleLineSubmit(name, save, () -> {
            String clean = DeckLayout.sanitizeName(name.getText().toString());
            if (clean.isEmpty()) {
                toast("Give this layout a name.");
                return;
            }
            if (!saver.save(clean)) {
                toast(DeckLayoutStore.layoutCount(this) >= DeckLayoutStore.MAX_LAYOUTS
                        ? "You have reached the maximum of 10 saved layouts."
                        : "That layout could not be saved.");
                return;
            }
            closeSheet();
            syncBackHandler();
            refresh();
            readMediaState();
        });
    }

    private boolean ensureLayoutRoom() {
        if (!OrbitProEntitlement.hasPro(this)) {
            openLayoutManager();
            return false;
        }
        if (DeckLayoutStore.layoutCount(this) >= DeckLayoutStore.MAX_LAYOUTS) {
            toast("You have reached the maximum of 10 saved layouts.");
            return false;
        }
        return true;
    }

    private void switchToLayout(String layoutId) {
        if (!OrbitProEntitlement.hasPro(this)) return;
        cancelActiveDrag();
        closeSheet();
        syncBackHandler();
        if (!DeckLayoutStore.switchLayout(this, layoutId)) return;
        activeLayoutId = layoutId;
        refresh();
        readMediaState();
    }

    private void confirmDeleteLayout(DeckLayout layout) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Delete " + layout.name + "?")
                .setMessage("This permanently deletes only this saved Deck. Other Orbit data is not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    cancelActiveDrag();
                    if (DeckLayoutStore.deleteLayout(this, layout.id)) {
                        closeSheet();
                        syncBackHandler();
                        refresh();
                        readMediaState();
                    }
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- running ----------------------------------------------------------------------------------

    private void runTile(DeckTile tile, DeckTileView view) {
        if (view != null) UiKit.haptic(view, HapticFeedbackConstants.VIRTUAL_KEY);
        DeckActionExecutor.execute(this, tile, outcome -> {
            if (outcome.needsConfiguration) {
                offerRepair(tile);
                return;
            }
            if (!outcome.message.isEmpty()) toast(outcome.message);
            if (outcome.stateChanged) readMediaState();
        });
    }

    /** What an unresolved tile offers when tapped: fix it, or take it off the Deck. */
    private void offerRepair(DeckTile tile) {
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
        boolean configurable = definition != null && definition.configurable;
        String[] options = configurable
                ? new String[]{"Set up", "Remove from Deck"}
                : new String[]{"Remove from Deck"};
        UiKit.showOrbitMenu(this, grid, options, -1, (index, label) -> {
            if (configurable && index == 0) configure(tile);
            else removeTile(tile);
        });
    }

    // ---- edit mode --------------------------------------------------------------------------------

    private void setEditing(boolean value) {
        if (editing == value) return;
        editing = value;
        buildHeaderControls();
        updateHeaderSubtitle(DeckLayoutStore.deck(this));
        for (View child : grid.orderedChildren()) {
            if (child instanceof DeckTileView) ((DeckTileView) child).setEditing(editing);
        }
        renderSuggestions(DeckLayoutStore.allTiles(this));
        syncBackHandler();
    }

    private void showDeckOptions(View anchor) {
        String expectedLayoutId = activeLayoutId;
        UiKit.showOrbitMenu(this, anchor,
                new String[]{"Deck layouts", "Add tile", "Add section", "Add folder", "Reset Deck"}, -1, (index, label) -> {
            if (index == 0) openLayoutManager();
            else if (index == 1) openAddSheet();
            else if (index == 2) createSection(expectedLayoutId);
            else if (index == 3) createFolder(expectedLayoutId);
            else confirmReset(expectedLayoutId);
        });
    }

    private void confirmReset(String expectedLayoutId) {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Reset Deck")
                .setMessage("Reset only this saved Deck to the default tiles. Your other saved "
                        + "Decks, Routines, reminders, memories and chats are not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> {
                    DeckLayoutStore.reset(this, expectedLayoutId);
                    refresh();
                    toast("Deck reset");
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    /**
     * Everything one tile can have done to it, in one place.
     *
     * <p>This is also how Deck stays usable without the drag gesture: Move before and Move after
     * are here, so reordering never depends on being able to hold and drag a tile.
     */
    private void showTileOptions(DeckTile tile, DeckTileView view) {
        String expectedLayoutId = activeLayoutId;
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (definition != null && definition.configurable) {
            labels.add("Edit");
            actions.add(() -> configure(tile, expectedLayoutId));
        }
        if (definition != null && definition.sizes.size() > 1) {
            labels.add("Size");
            actions.add(() -> showSizeChooser(tile, view, expectedLayoutId));
        }
        labels.add(OrbitProEntitlement.hasPro(this) ? "Appearance · Pro" : "Appearance");
        actions.add(() -> openAppearanceEditor(tile, expectedLayoutId));
        labels.add("Rename");
        actions.add(() -> openRenameSheet(tile, expectedLayoutId));

        String folderId = folderForTile(tile.instanceId);
        int index = indexOf(tile);
        int containerSize = folderId == null ? DeckLayoutStore.deck(this).items.size()
                : DeckLayoutStore.deck(this).folder(folderId).tiles.size();
        if (index > 0) {
            labels.add("Move before");
            actions.add(() -> move(tile, index - 1, expectedLayoutId));
        }
        if (index >= 0 && index < containerSize - 1) {
            labels.add("Move after");
            actions.add(() -> move(tile, index + 1, expectedLayoutId));
        }
        if (!folders().isEmpty() || folderId != null) {
            labels.add("Move to folder");
            actions.add(() -> openMoveTileSheet(tile, expectedLayoutId));
        }
        labels.add("Remove");
        actions.add(() -> removeTile(tile, expectedLayoutId));

        UiKit.showOrbitMenu(this, view, labels.toArray(new String[0]), -1,
                (choice, label) -> { if (choice >= 0 && choice < actions.size()) actions.get(choice).run(); });
    }

    private int indexOf(DeckTile tile) {
        DeckLayout deck = DeckLayoutStore.deck(this);
        String folderId = folderForTile(tile.instanceId);
        if (folderId != null) {
            List<DeckTile> tiles = deck.folder(folderId).tiles;
            for (int i = 0; i < tiles.size(); i++) if (tiles.get(i).instanceId.equals(tile.instanceId)) return i;
            return -1;
        }
        for (int i = 0; i < deck.items.size(); i++) if (deck.items.get(i).id.equals(tile.instanceId)) return i;
        return -1;
    }

    private void move(DeckTile tile, int target, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        DeckLayout deck = DeckLayoutStore.deck(this);
        String folderId = folderForTile(tile.instanceId);
        int from = indexOf(tile);
        if (folderId != null) {
            List<DeckTile> tiles = new ArrayList<>(deck.folder(folderId).tiles);
            if (from < 0 || target < 0 || target >= tiles.size()) return;
            DeckTile moved = tiles.remove(from);
            tiles.add(target, moved);
            List<String> order = new ArrayList<>();
            for (DeckTile item : tiles) order.add(item.instanceId);
            DeckLayoutStore.applyFolderOrder(this, expectedLayoutId, folderId, order);
        } else {
            if (from < 0 || target < 0 || target >= deck.items.size()) return;
            List<DeckItem> items = new ArrayList<>(deck.items);
            DeckItem moved = items.remove(from);
            items.add(target, moved);
            List<String> order = new ArrayList<>();
            for (DeckItem item : items) order.add(item.id);
            DeckLayoutStore.applyItemOrder(this, expectedLayoutId, order);
        }
        refresh();
        grid.animateNextLayout();
    }

    private void resize(DeckTile tile, DeckTile.Size size, String expectedLayoutId) {
        if (!DeckLayoutStore.resize(this, expectedLayoutId, tile.instanceId, size)) {
            toast("That tile cannot be that size.");
            return;
        }
        refresh();
        grid.animateNextLayout();
    }

    private void removeTile(DeckTile tile) {
        removeTile(tile, activeLayoutId);
    }

    private void removeTile(DeckTile tile, String expectedLayoutId) {
        if (!DeckLayoutStore.remove(this, expectedLayoutId, tile.instanceId)) return;
        if (sheetOpen()) closeSheet();
        refresh();
        grid.animateNextLayout();
    }

    private List<DeckFolder> folders() {
        List<DeckFolder> out = new ArrayList<>();
        for (DeckItem item : DeckLayoutStore.deck(this).items) if (item instanceof DeckFolder) out.add((DeckFolder) item);
        return out;
    }

    private String folderForTile(String tileId) {
        for (DeckFolder folder : folders()) {
            for (DeckTile tile : folder.tiles) if (tile.instanceId.equals(tileId)) return folder.id;
        }
        return null;
    }

    private void createSection(String expectedLayoutId) {
        if (!DeckLayoutStore.addSection(this, expectedLayoutId, "New section")) { toast("Your Deck has enough sections."); return; }
        DeckSection added = null;
        for (DeckItem item : DeckLayoutStore.deck(this).items) if (item instanceof DeckSection) added = (DeckSection) item;
        refresh();
        if (added != null) openStructuralRenameSheet(added, expectedLayoutId);
    }

    private void createFolder(String expectedLayoutId) {
        if (!DeckLayoutStore.addFolder(this, expectedLayoutId, "New folder")) { toast("Your Deck has enough folders."); return; }
        DeckFolder added = null;
        for (DeckFolder folder : folders()) added = folder;
        refresh();
        if (added != null) openStructuralRenameSheet(added, expectedLayoutId);
    }

    private void showStructuralOptions(DeckItem item, View anchor) {
        String expectedLayoutId = activeLayoutId;
        DeckLayout deck = DeckLayoutStore.deck(this);
        int index = -1;
        for (int i = 0; i < deck.items.size(); i++) if (deck.items.get(i).id.equals(item.id)) index = i;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (item instanceof DeckFolder) {
            labels.add("Open folder"); actions.add(() -> openFolder(item.id, expectedLayoutId));
            labels.add("Add from Deck"); actions.add(() -> openAddToFolderSheet(item.id, expectedLayoutId));
        }
        labels.add("Rename"); actions.add(() -> openStructuralRenameSheet(item, expectedLayoutId));
        if (index > 0) { int target = index - 1; labels.add("Move before"); actions.add(() -> moveItem(item.id, target, expectedLayoutId)); }
        if (index >= 0 && index < deck.items.size() - 1) { int target = index + 1; labels.add("Move after"); actions.add(() -> moveItem(item.id, target, expectedLayoutId)); }
        labels.add("Remove");
        actions.add(() -> {
            if (item instanceof DeckFolder) confirmRemoveFolder((DeckFolder) item, expectedLayoutId);
            else { DeckLayoutStore.removeSection(this, expectedLayoutId, item.id); refresh(); }
        });
        UiKit.showOrbitMenu(this, anchor, labels.toArray(new String[0]), -1,
                (choice, label) -> { if (choice >= 0 && choice < actions.size()) actions.get(choice).run(); });
    }

    private void moveItem(String id, int target, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        List<DeckItem> items = new ArrayList<>(DeckLayoutStore.deck(this).items);
        int from = -1;
        for (int i = 0; i < items.size(); i++) if (items.get(i).id.equals(id)) from = i;
        if (from < 0 || target < 0 || target >= items.size()) return;
        DeckItem moved = items.remove(from);
        items.add(target, moved);
        List<String> ids = new ArrayList<>();
        for (DeckItem item : items) ids.add(item.id);
        DeckLayoutStore.applyItemOrder(this, expectedLayoutId, ids);
        refresh();
        grid.animateNextLayout();
    }

    private void openStructuralRenameSheet(DeckItem item, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        String current = item instanceof DeckSection ? ((DeckSection) item).title : ((DeckFolder) item).title;
        LinearLayout column = openSheet(item instanceof DeckSection ? "Rename section" : "Rename folder", "Changes save immediately");
        EditText name = new EditText(this);
        name.setText(current);
        name.setHint(item instanceof DeckSection ? "Section name" : "Folder name");
        styleField(name, false);
        column.addView(name, fieldLp(UiKit.dp(this, 48)));
        Button save = primaryButton("Save");
        column.addView(save, fieldLp(UiKit.dp(this, 48)));
        bindSingleLineSubmit(name, save, () -> {
            DeckLayoutStore.renameItem(this, expectedLayoutId, item.id, name.getText().toString());
            closeSheet(); syncBackHandler(); refresh();
        });
    }

    private void confirmRemoveFolder(DeckFolder folder, String expectedLayoutId) {
        if (folder.tiles.isEmpty()) {
            DeckLayoutStore.removeFolderMovingChildren(this, expectedLayoutId, folder.id);
            refresh();
            return;
        }
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Remove " + folder.title + "?")
                .setMessage("Move its " + folder.tiles.size() + " tiles back to your Deck. No tile will be deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Move tiles and remove", (d, w) -> {
                    DeckLayoutStore.removeFolderMovingChildren(this, expectedLayoutId, folder.id);
                    refresh();
                }).create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    private Button primaryButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(UiKit.onAccent(this));
        button.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        button.setMinHeight(0); button.setMinimumHeight(0); button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private void persistOrder(String expectedLayoutId, List<View> ordered) {
        List<String> ids = new ArrayList<>();
        for (View child : ordered) if (child.getTag() instanceof String) ids.add((String) child.getTag());
        DeckLayoutStore.applyItemOrder(this, expectedLayoutId, ids);
    }

    // ---- dragging ---------------------------------------------------------------------------------

    /**
     * Drag to reorder from either mode.
     *
     * <p>In normal mode the long-press timeout enters Edit and picks up this same view while the
     * finger is still down. In Edit, crossing touch slop picks it up immediately. Returning true
     * from DOWN is essential: a listener that declines DOWN is not guaranteed the rest of the
     * gesture, which is why attaching a new listener after Beta 1's long-click could never turn the
     * already-running press into a drag.
     */
    private void installDrag(DeckTileView view, String expectedLayoutId) {
        final float[] down = new float[2];
        final boolean[] pressed = {false};
        final boolean[] dragging = {false};
        final boolean[] longPressed = {false};
        final boolean[] movedBeforeLongPress = {false};
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Runnable[] pickup = new Runnable[1];
        pickup[0] = () -> {
            if (!pressed[0] || dragging[0] || carriedTile != null
                    || !expectedLayoutId.equals(activeLayoutId)) return;
            longPressed[0] = true;
            if (!editing) setEditing(true);
            beginTileDrag(view, dragging);
        };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    pressed[0] = true;
                    dragging[0] = false;
                    longPressed[0] = false;
                    movedBeforeLongPress[0] = false;
                    view.removeCallbacks(pickup[0]);
                    if (!editing) view.postDelayed(pickup[0],
                            ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!editing && Math.hypot(dx, dy) > slop) {
                        movedBeforeLongPress[0] = true;
                        view.removeCallbacks(pickup[0]);
                    } else if (editing && !dragging[0] && Math.hypot(dx, dy) > slop) {
                        beginTileDrag(view, dragging);
                    }
                    if (dragging[0]) grid.updateDrag(dx, dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    pressed[0] = false;
                    view.removeCallbacks(pickup[0]);
                    if (dragging[0]) {
                        dragging[0] = false;
                        view.setCarried(false);
                        carriedTile = null;
                        boolean changed = grid.endDrag();
                        scroll.requestDisallowInterceptTouchEvent(false);
                        if (changed) UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
                        // Consumed, so the drop does not also register as a tap on the tile.
                        return true;
                    }
                    if (!longPressed[0] && !movedBeforeLongPress[0]) v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed[0] = false;
                    view.removeCallbacks(pickup[0]);
                    if (dragging[0]) {
                        dragging[0] = false;
                        view.setCarried(false);
                        carriedTile = null;
                        grid.cancelDrag();
                        scroll.requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void beginTileDrag(DeckTileView view, boolean[] dragging) {
        if (view == null || dragging[0] || carriedTile != null) return;
        dragging[0] = true;
        carriedTile = view;
        UiKit.haptic(view, HapticFeedbackConstants.LONG_PRESS);
        view.setCarried(true);
        grid.beginDrag(view);
        scroll.requestDisallowInterceptTouchEvent(true);
    }

    private void cancelActiveDrag() {
        if (carriedTile == null || grid == null) return;
        carriedTile.setCarried(false);
        carriedTile = null;
        if (folderGrid != null && folderGrid.isDragging()) folderGrid.cancelDrag();
        else grid.cancelDrag();
        if (scroll != null) scroll.requestDisallowInterceptTouchEvent(false);
    }

    // ---- live state -------------------------------------------------------------------------------

    /**
     * Watches the torch rather than polling it.
     *
     * <p>{@code registerTorchCallback} reports the current state immediately and then only when it
     * changes, so a flashlight tile stays truthful for free. Registered on resume and released on
     * pause, so nothing observes the camera while Deck is not on screen.
     */
    private void startWatchingTorch() {
        if (torchCallback != null) return;
        try {
            if (!getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)) return;
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) return;
            torchCameraId = null;
            for (String id : cameraManager.getCameraIdList()) {
                Boolean available = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (Boolean.TRUE.equals(available)) { torchCameraId = id; break; }
            }
            if (torchCameraId == null) return;
            torchCallback = new CameraManager.TorchCallback() {
                @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
                    if (!cameraId.equals(torchCameraId)) return;
                    applyLive(new DeckTileResolver.LiveState(
                            enabled, live.mediaPlaying, live.mediaApp));
                }
                @Override public void onTorchModeUnavailable(String cameraId) {
                    if (!cameraId.equals(torchCameraId)) return;
                    // Unknown, not "off". A tile never claims a state Orbit cannot read.
                    applyLive(new DeckTileResolver.LiveState(
                            null, live.mediaPlaying, live.mediaApp));
                }
            };
            cameraManager.registerTorchCallback(torchCallback, MAIN);
        } catch (Exception e) {
            torchCallback = null;
        }
    }

    private void stopWatchingTorch() {
        if (cameraManager != null && torchCallback != null) {
            try { cameraManager.unregisterTorchCallback(torchCallback); } catch (Exception ignored) {}
        }
        torchCallback = null;
    }

    /** Reads playback state once, off the UI thread, because it is a binder call. */
    private void readMediaState() {
        Context app = getApplicationContext();
        EXEC.execute(() -> {
            Boolean playing = null;
            String label = "";
            try {
                MediaController controller = MediaControl.activeController(app);
                if (controller != null) {
                    playing = MediaControl.isPlaying(controller);
                    label = MediaControl.appLabel(app, controller.getPackageName());
                }
            } catch (Exception ignored) {}
            Boolean finalPlaying = playing;
            String finalLabel = label;
            MAIN.post(() -> applyLive(new DeckTileResolver.LiveState(
                    live.flashlightOn, finalPlaying, finalLabel)));
        });
    }

    /**
     * Applies new live state without rebuilding the page.
     *
     * <p>Each tile is re-dressed in place, so a score of a flashlight turning on updates one line of
     * text: no view is replaced, the grid is not re-laid out, and the scroll position cannot move.
     */
    private void applyLive(DeckTileResolver.LiveState next) {
        live = next;
        if (grid == null || isFinishing()) return;
        for (View child : grid.orderedChildren()) {
            if (!(child instanceof DeckTileView)) continue;
            DeckTileView view = (DeckTileView) child;
            view.apply(DeckTileResolver.resolve(this, view.tile(), live));
        }
        if (!editing) renderSuggestions(DeckLayoutStore.allTiles(this));
    }

    // ---- folders ---------------------------------------------------------------------------------

    private void openFolder(String folderId) {
        openFolder(folderId, activeLayoutId);
    }

    private void openFolder(String folderId, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        DeckFolder folder = DeckLayoutStore.deck(this).folder(folderId);
        if (folder == null) return;
        openFolderId = folderId;
        LinearLayout column = openSheet(folder.title,
                folder.tiles.size() == 1 ? "1 tile" : folder.tiles.size() + " tiles");

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        Button add = primaryButton("Add from Deck");
        add.setOnClickListener(v -> openAddToFolderSheet(folderId, expectedLayoutId));
        actions.addView(add, new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1f));
        Button rename = primaryButton("Rename");
        rename.setOnClickListener(v -> openStructuralRenameSheet(folder, expectedLayoutId));
        LinearLayout.LayoutParams renameLp = new LinearLayout.LayoutParams(0, UiKit.dp(this, 44), 1f);
        renameLp.leftMargin = UiKit.dp(this, 8);
        actions.addView(rename, renameLp);
        column.addView(actions, rowLp());

        if (folder.tiles.isEmpty()) {
            column.addView(emptyNote("This folder is empty. Add an existing tile from your Deck."));
            return;
        }
        folderGrid = new DeckGridLayout(this);
        folderGrid.setColumns(Math.min(3, DeckGridLayout.columnsForWidth(contentWidthDp())));
        folderGrid.setSpacing(UiKit.dp(this, 10));
        folderGrid.setMinRowHeight(UiKit.dp(this, 112));
        folderGrid.setOnReorderListener(ordered -> {
            List<String> ids = new ArrayList<>();
            for (View child : ordered) if (child instanceof DeckTileView) ids.add(((DeckTileView) child).tile().instanceId);
            DeckLayoutStore.applyFolderOrder(this, expectedLayoutId, folderId, ids);
        });
        for (DeckTile tile : folder.tiles) {
            DeckTileView view = folderTileView(tile, expectedLayoutId);
            folderGrid.addView(view, tileGridParams(tile));
        }
        column.addView(folderGrid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private DeckGridLayout.LayoutParams tileGridParams(DeckTile tile) {
        if (tile.size == DeckTile.Size.LARGE) return new DeckGridLayout.LayoutParams(2, 2);
        return new DeckGridLayout.LayoutParams(tile.size == DeckTile.Size.WIDE ? 2 : 1, 1);
    }

    private DeckTileView folderTileView(DeckTile tile, String expectedLayoutId) {
        DeckTileView view = new DeckTileView(this, tile, DeckTileResolver.resolve(this, tile, live),
                new DeckTileView.Listener() {
                    @Override public void onTileTapped(DeckTile value, DeckTileView tileView) {
                        if (editing) showTileOptions(value, tileView); else runTile(value, tileView);
                    }
                    @Override public void onTileLongPressed(DeckTile value, DeckTileView tileView) {
                        if (!editing) setEditing(true);
                        showTileOptions(value, tileView);
                    }
                    @Override public void onTileRemoveTapped(DeckTile value, DeckTileView tileView) { removeTile(value); }
                });
        view.setEditing(editing);
        installFolderDrag(view, expectedLayoutId);
        return view;
    }

    /** The existing Deck drag interaction, scoped to the open folder's own grid. */
    private void installFolderDrag(DeckTileView view, String expectedLayoutId) {
        final float[] down = new float[2];
        final boolean[] pressed = {false};
        final boolean[] dragging = {false};
        final boolean[] longPressed = {false};
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Runnable[] pickup = new Runnable[1];
        pickup[0] = () -> {
            if (!pressed[0] || dragging[0] || carriedTile != null || folderGrid == null
                    || !expectedLayoutId.equals(activeLayoutId)) return;
            longPressed[0] = true;
            if (!editing) { setEditing(true); view.setEditing(true); }
            dragging[0] = true;
            carriedTile = view;
            view.setCarried(true);
            folderGrid.beginDrag(view);
            view.getParent().requestDisallowInterceptTouchEvent(true);
            UiKit.haptic(view, HapticFeedbackConstants.LONG_PRESS);
        };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX(); down[1] = event.getRawY();
                    pressed[0] = true; dragging[0] = false; longPressed[0] = false;
                    if (!editing) view.postDelayed(pickup[0], ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (editing && !dragging[0] && Math.hypot(dx, dy) > slop) pickup[0].run();
                    if (dragging[0] && folderGrid != null) folderGrid.updateDrag(dx, dy);
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed[0] = false; view.removeCallbacks(pickup[0]);
                    if (dragging[0] && folderGrid != null) {
                        dragging[0] = false; view.setCarried(false); carriedTile = null;
                        boolean changed = folderGrid.endDrag();
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (changed) UiKit.haptic(view, HapticFeedbackConstants.CLOCK_TICK);
                        return true;
                    }
                    if (!longPressed[0]) v.performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed[0] = false; view.removeCallbacks(pickup[0]);
                    if (dragging[0] && folderGrid != null) {
                        dragging[0] = false; folderGrid.cancelDrag(); view.setCarried(false); carriedTile = null;
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                default: return true;
            }
        });
    }

    private void openAddToFolderSheet(String folderId, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        DeckFolder folder = DeckLayoutStore.deck(this).folder(folderId);
        if (folder == null) return;
        LinearLayout column = openSheet("Add to " + folder.title, "Move an existing root tile into this folder");
        List<DeckTile> rootTiles = DeckLayoutStore.layout(this);
        if (rootTiles.isEmpty()) { column.addView(emptyNote("There are no root tiles to move.")); return; }
        for (DeckTile tile : rootTiles) {
            DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(this, tile, live);
            column.addView(pickerRow(resolved.iconRes, resolved.title, resolved.subtitle,
                    resolved.appIcon, () -> {
                        if (!DeckLayoutStore.moveTile(this, expectedLayoutId,
                                tile.instanceId, folderId)) {
                            toast("That folder is full."); return;
                        }
                        closeSheet(); refresh(); openFolder(folderId, expectedLayoutId);
                    }), rowLp());
        }
    }

    private void openMoveTileSheet(DeckTile tile, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        String currentFolder = folderForTile(tile.instanceId);
        LinearLayout column = openSheet("Move tile", "Moving keeps the same tile and settings");
        if (currentFolder != null) {
            column.addView(pickerRow(R.drawable.ic_deck, "My Deck", "Move out of the folder", null, () -> {
                DeckLayoutStore.moveTile(this, expectedLayoutId, tile.instanceId, null);
                closeSheet(); syncBackHandler(); refresh();
            }), rowLp());
        }
        for (DeckFolder folder : folders()) {
            if (folder.id.equals(currentFolder)) continue;
            String count = folder.tiles.size() == 1 ? "1 tile" : folder.tiles.size() + " tiles";
            column.addView(pickerRow(R.drawable.ic_deck, folder.title, count, null, () -> {
                if (!DeckLayoutStore.moveTile(this, expectedLayoutId,
                        tile.instanceId, folder.id)) { toast("That folder is full."); return; }
                closeSheet(); syncBackHandler(); refresh();
            }), rowLp());
        }
    }

    private void showSizeChooser(DeckTile tile, View anchor, String expectedLayoutId) {
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
        if (definition == null) return;
        List<DeckTile.Size> sizes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DeckTile.Size size : DeckTile.Size.values()) {
            if (!definition.supports(size)) continue;
            sizes.add(size);
            labels.add(size == DeckTile.Size.STANDARD ? "Standard" : size == DeckTile.Size.WIDE ? "Wide" : "Large");
        }
        int selected = sizes.indexOf(tile.size);
        UiKit.showOrbitMenu(this, anchor, labels.toArray(new String[0]), selected,
                (index, label) -> { if (index >= 0 && index < sizes.size()) resize(tile, sizes.get(index), expectedLayoutId); });
    }

    // ---- Orbit Pro tile appearance ---------------------------------------------------------------

    private void openAppearanceEditor(DeckTile tile, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        if (!OrbitProEntitlement.hasPro(this)) {
            LinearLayout column = openSheet("Tile appearance · Orbit Pro",
                    "Per-tile accent, material, icon, and label choices are part of Orbit Pro.");
            column.addView(emptyNote("Enable Pro Preview in Diagnostics to try these controls. Your Deck organization remains Free."));
            return;
        }
        DeckTile current = tileById(expectedLayoutId, tile.instanceId);
        if (current == null) return;
        LinearLayout column = openSheet("Tile appearance · Pro", "Changes save immediately");

        FrameLayout preview = new FrameLayout(this);
        preview.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 14));
        column.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, current.size == DeckTile.Size.LARGE
                        ? UiKit.dp(this, 260) : UiKit.dp(this, 150)));
        renderAppearancePreview(preview, current.instanceId);

        View accentRow = pickerRow(R.drawable.ic_tune, "Tile accent",
                DeckTileAppearance.INHERIT.equals(current.appearance.accent)
                        ? "Inherit" : OrbitPalette.labelFor(current.appearance.accent), null,
                () -> chooseTileAccent(current.instanceId, preview, expectedLayoutId));
        column.addView(accentRow, rowLp());

        column.addView(sectionLabel("SURFACE TREATMENT"));
        OrbitSegmented material = new OrbitSegmented(this);
        material.setTitle("Surface treatment");
        material.setOptions(new String[]{"Inherit", "Solid", "Frosted", "Liquid"});
        String[] materialKeys = {DeckTileAppearance.INHERIT, OrbitTheme.MATERIAL_SOLID,
                OrbitTheme.MATERIAL_FROSTED, OrbitTheme.MATERIAL_LIQUID};
        material.setSelected(indexOf(materialKeys, current.appearance.material));
        material.setOnSelectListener((v, index) -> {
            DeckTile latest = tileById(expectedLayoutId, current.instanceId);
            if (latest != null) updateTileAppearance(current.instanceId,
                    latest.appearance.withMaterial(materialKeys[index]), preview, expectedLayoutId);
        });
        column.addView(material, rowLp());

        column.addView(sectionLabel("ICON TREATMENT"));
        OrbitSegmented icon = new OrbitSegmented(this);
        icon.setTitle("Icon treatment");
        icon.setOptions(new String[]{"Inherit", "Accent", "Monochrome"});
        String[] iconKeys = {DeckTileAppearance.INHERIT, DeckTileAppearance.ICON_ACCENT,
                DeckTileAppearance.ICON_MONOCHROME};
        icon.setSelected(indexOf(iconKeys, current.appearance.iconTreatment));
        icon.setOnSelectListener((v, index) -> {
            DeckTile latest = tileById(expectedLayoutId, current.instanceId);
            if (latest != null) updateTileAppearance(current.instanceId,
                    latest.appearance.withIconTreatment(iconKeys[index]), preview, expectedLayoutId);
        });
        column.addView(icon, rowLp());

        column.addView(sectionLabel("LABEL VISIBILITY"));
        OrbitSegmented label = new OrbitSegmented(this);
        label.setTitle("Label visibility");
        label.setOptions(new String[]{"Show", "Hide"});
        label.setSelected(current.appearance.showLabel ? 0 : 1);
        label.setOnSelectListener((v, index) -> {
            DeckTile latest = tileById(expectedLayoutId, current.instanceId);
            if (latest != null) updateTileAppearance(current.instanceId,
                    latest.appearance.withShowLabel(index == 0), preview, expectedLayoutId);
        });
        column.addView(label, rowLp());
    }

    private void chooseTileAccent(String tileId, View anchor, String expectedLayoutId) {
        DeckTile tile = tileById(expectedLayoutId, tileId);
        if (tile == null) return;
        String[] keys = UiKit.accentKeys();
        List<String> labels = new ArrayList<>();
        List<Integer> colors = new ArrayList<>();
        labels.add("Inherit"); colors.add(UiKit.accent(this));
        for (int i = 0; i < keys.length; i++) { labels.add(UiKit.accentLabels()[i]); colors.add(UiKit.accentForName(this, keys[i])); }
        labels.add("Custom color…"); colors.add(DeckTileAppearance.INHERIT.equals(tile.appearance.accent)
                ? UiKit.accent(this) : UiKit.accentForName(this, tile.appearance.accent));
        int selected = DeckTileAppearance.INHERIT.equals(tile.appearance.accent) ? 0 : indexOf(keys, tile.appearance.accent) + 1;
        int[] values = new int[colors.size()];
        for (int i = 0; i < colors.size(); i++) values[i] = colors.get(i);
        UiKit.showOrbitColorMenu(this, anchor, labels.toArray(new String[0]), values, selected, (index, label) -> {
            DeckTile latest = tileById(expectedLayoutId, tileId);
            if (latest == null) return;
            if (index == 0) updateTileAppearance(tileId, latest.appearance.withAccent(DeckTileAppearance.INHERIT), anchor, expectedLayoutId);
            else if (index <= keys.length) updateTileAppearance(tileId, latest.appearance.withAccent(keys[index - 1]), anchor, expectedLayoutId);
            else {
                List<Integer> suggestions = new ArrayList<>();
                for (String key : keys) suggestions.add(UiKit.accentForName(this, key));
                OrbitColorPicker.show(this, "Tile accent", values[values.length - 1], suggestions,
                        color -> {
                            DeckTile current = tileById(expectedLayoutId, tileId);
                            if (current != null) updateTileAppearance(tileId,
                                    current.appearance.withAccent(OrbitPalette.tokenFor(color)),
                                    anchor, expectedLayoutId);
                        });
            }
        });
    }

    private int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return 0;
    }

    private DeckTile tileById(String id) {
        for (DeckTile tile : DeckLayoutStore.allTiles(this)) if (tile.instanceId.equals(id)) return tile;
        return null;
    }

    private DeckTile tileById(String expectedLayoutId, String id) {
        return expectedLayoutId.equals(activeLayoutId) ? tileById(id) : null;
    }

    private void updateTileAppearance(String tileId, DeckTileAppearance appearance, View preview,
                                      String expectedLayoutId) {
        if (!OrbitProEntitlement.hasPro(this) || appearance == null) return;
        if (!DeckLayoutStore.updateAppearance(this, expectedLayoutId, tileId, appearance)) return;
        refresh();
        if (preview instanceof FrameLayout) renderAppearancePreview((FrameLayout) preview, tileId);
    }

    private void renderAppearancePreview(FrameLayout preview, String tileId) {
        DeckTile tile = tileById(tileId);
        if (tile == null) return;
        preview.removeAllViews();
        DeckTileView view = new DeckTileView(this, tile, DeckTileResolver.resolve(this, tile, live), null);
        view.setContentDescription("Live preview. " + view.getContentDescription());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                tile.size == DeckTile.Size.STANDARD ? UiKit.dp(this, 170) : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        preview.addView(view, lp);
    }

    // ---- sheets -----------------------------------------------------------------------------------

    private boolean sheetOpen() {
        return sheetHost != null && sheetHost.getVisibility() == View.VISIBLE;
    }

    /**
     * Opens Deck's own modal surface and returns the column to fill.
     *
     * <p>An in-page sheet rather than a dialog or another Activity: it keeps Deck's typography and
     * accent, it can be closed by Back through the same handler that closes edit mode, and it does
     * not add an exported component or a second screen to the navigation table for what is really
     * a step within this one.
     */
    private LinearLayout openSheet(String title, String subtitle) {
        long generation = ++sheetGeneration;
        sheetLayoutId = activeLayoutId;
        sheetHost.removeAllViews();
        sheetHost.setVisibility(View.VISIBLE);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        scrim.setOnClickListener(v -> { closeSheet(); syncBackHandler(); });
        scrim.setContentDescription("Close");
        sheetHost.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        sheetPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(UiKit.rounded(UiKit.SURFACE, 26, this));
        panel.setClickable(true);
        int pad = UiKit.dp(this, 20);
        panel.setPadding(pad, UiKit.dp(this, 18), pad, UiKit.dp(this, 24));

        View handle = new View(this);
        handle.setBackground(UiKit.rounded(UiKit.blend(UiKit.MUTED, UiKit.SURFACE, 0.4f), 3, this));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 38), UiKit.dp(this, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.bottomMargin = UiKit.dp(this, 14);
        panel.addView(handle, handleLp);

        panel.addView(UiKit.text(this, title, 20, UiKit.TEXT, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = UiKit.text(this, subtitle, 12.5f, UiKit.MUTED, false);
            sub.setPadding(0, UiKit.dp(this, 4), 0, 0);
            panel.addView(sub);
        }

        ScrollView body = new ScrollView(this);
        sheetScroll = body;
        body.setFillViewport(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, UiKit.dp(this, 14), 0, 0);
        body.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        panel.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        body.setMinimumHeight(0);
        panel.setMinimumHeight(0);
        body.post(() -> {
            if (generation != sheetGeneration) return;
            if (body.getHeight() > maxHeight) {
                ViewGroup.LayoutParams lp = body.getLayoutParams();
                lp.height = maxHeight;
                body.setLayoutParams(lp);
            }
        });

        int panelWidth = Math.min(getResources().getDisplayMetrics().widthPixels,
                UiKit.dp(this, 640));
        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                panelWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sheetHost.addView(panel, panelLp);

        if (UiKit.animationsEnabled()) {
            panel.setTranslationY(UiKit.dp(this, 40));
            panel.setAlpha(0f);
            panel.animate().translationY(0f).alpha(1f)
                    .setDuration(190L).setInterpolator(UiKit.motionEasing()).start();
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(160L).start();
        }
        syncBackHandler();
        return column;
    }

    private void closeSheet() {
        if (sheetHost == null) return;
        sheetGeneration++;
        sheetHost.removeAllViews();
        sheetHost.setVisibility(View.GONE);
        sheetScroll = null;
        sheetPanel = null;
        folderGrid = null;
        openFolderId = null;
    }

    // ---- add flow ---------------------------------------------------------------------------------

    private void openAddSheet() {
        LinearLayout column = openSheet("Add tile", "Choose what this tile should do");
        for (DeckTileRegistry.Category category : DeckTileRegistry.categories()) {
            List<DeckTileRegistry.Definition> offered = new ArrayList<>();
            for (DeckTileRegistry.Definition definition : DeckTileRegistry.inCategory(category)) {
                // A tile whose feature is switched off is not offered as something new to add.
                // Tiles the user already placed are untouched by this: their layout is theirs.
                if (DeckTileRegistry.isOfferable(this, definition)) offered.add(definition);
            }
            if (offered.isEmpty()) continue;
            column.addView(sectionLabel(category.label.toUpperCase(Locale.US)));
            for (DeckTileRegistry.Definition definition : offered) {
                boolean placed = definition.singleton && DeckLayoutStore.contains(this, definition.type);
                column.addView(addRow(definition, placed), rowLp());
            }
            View gap = new View(this);
            column.addView(gap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 10)));
        }
    }

    private View addRow(DeckTileRegistry.Definition definition, boolean alreadyPlaced) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        int pad = UiKit.dp(this, 13);
        row.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(row);

        ImageView icon = new ImageView(this);
        icon.setImageResource(definition.iconRes);
        icon.setImageTintList(ColorStateList.valueOf(
                alreadyPlaced ? UiKit.MUTED : UiKit.accent(this)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 22), UiKit.dp(this, 22));
        iconLp.rightMargin = UiKit.dp(this, 14);
        row.addView(icon, iconLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, definition.title, 15,
                alreadyPlaced ? UiKit.MUTED : UiKit.TEXT, true);
        text.addView(title);
        TextView desc = UiKit.text(this,
                alreadyPlaced ? "Already on your Deck" : definition.description,
                12, UiKit.MUTED, false);
        desc.setMaxLines(2);
        text.addView(desc);
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setContentDescription(definition.title + ", " + (alreadyPlaced
                ? "already on your Deck" : definition.description));
        row.setFocusable(true);
        row.setEnabled(!alreadyPlaced);
        if (!alreadyPlaced) row.setOnClickListener(v -> chooseTile(definition));
        return row;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.dp(this, 8);
        return lp;
    }

    /** A definition chosen from the Add sheet: either place it, or ask what it should point at. */
    private void chooseTile(DeckTileRegistry.Definition definition) {
        if (!definition.configurable) {
            place(DeckTile.of(definition.type, definition.defaultSize()));
            return;
        }
        if (DeckTileRegistry.TYPE_ROUTINE.equals(definition.type)) { openRoutinePicker(null); return; }
        if (DeckTileRegistry.TYPE_APP.equals(definition.type)) { openAppPicker(null); return; }
        if (DeckTileRegistry.TYPE_PROMPT.equals(definition.type)) { openPromptEditor(null); return; }
        place(DeckTile.of(definition.type, definition.defaultSize()));
    }

    private void place(DeckTile tile) {
        place(tile, sheetLayoutId);
    }

    private void place(DeckTile tile, String expectedLayoutId) {
        if (DeckLayoutStore.wouldDuplicate(this, tile)) {
            toast("That is already on your Deck.");
            closeSheet();
            syncBackHandler();
            return;
        }
        if (!DeckLayoutStore.add(this, expectedLayoutId, tile)) {
            toast("Your Deck is full.");
            return;
        }
        closeSheet();
        syncBackHandler();
        refresh();
        if (UiKit.animationsEnabled()) grid.animateNextLayout();
    }

    /** Re-opens the right configuration surface for an existing tile. */
    private void configure(DeckTile tile) {
        configure(tile, activeLayoutId);
    }

    private void configure(DeckTile tile, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        if (DeckTileRegistry.TYPE_ROUTINE.equals(tile.type)) openRoutinePicker(tile);
        else if (DeckTileRegistry.TYPE_APP.equals(tile.type)) openAppPicker(tile);
        else if (DeckTileRegistry.TYPE_PROMPT.equals(tile.type)) openPromptEditor(tile);
    }

    // ---- routine picker ---------------------------------------------------------------------------

    private void openRoutinePicker(DeckTile existing) {
        LinearLayout column = openSheet("Choose a Routine",
                "The tile runs it exactly as the Routines screen does");
        String expectedLayoutId = sheetLayoutId;
        List<RoutineStore.Routine> routines = RoutineStore.list(this);
        if (routines.isEmpty()) {
            column.addView(emptyNote("You have no saved Routines yet."));
            return;
        }
        for (RoutineStore.Routine routine : routines) {
            int steps = routine.actions == null ? 0 : routine.actions.size();
            column.addView(pickerRow(R.drawable.ic_routine_tile, routine.name,
                    steps == 1 ? "1 action" : steps + " actions",
                    null, () -> {
                        if (existing == null) {
                            place(DeckTile.of(DeckTileRegistry.TYPE_ROUTINE, DeckTile.Size.STANDARD)
                                    .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id), expectedLayoutId);
                        } else {
                            DeckLayoutStore.configure(this, expectedLayoutId, existing.instanceId,
                                    DeckTile.CONFIG_ROUTINE_ID, routine.id);
                            closeSheet();
                            syncBackHandler();
                            refresh();
                        }
                    }), rowLp());
        }
    }

    // ---- app picker -------------------------------------------------------------------------------

    /**
     * The installed-app chooser.
     *
     * <p>Enumerating launchable packages and loading their icons is the one genuinely expensive
     * thing Deck ever does, so none of it happens on the UI thread and none of it happens until the
     * user asks for this sheet. Opening Deck itself never touches PackageManager for a list.
     */
    private void openAppPicker(DeckTile existing) {
        LinearLayout column = openSheet("Choose an app", "Launches the app from your Deck");
        String expectedLayoutId = sheetLayoutId;
        long expectedSheetGeneration = sheetGeneration;

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Color.rgb(113, 119, 135));
        search.setTextColor(UiKit.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(47, 52, 66), 16, this));
        search.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        installKeyboardSafeField(search);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        searchLp.bottomMargin = UiKit.dp(this, 12);
        column.addView(search, searchLp);

        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        column.addView(results);

        TextView loading = emptyNote("Loading apps…");
        results.addView(loading);

        EXEC.execute(() -> {
            List<LaunchableApp> apps = launchableApps();
            MAIN.post(() -> {
                if (!sheetOpen() || expectedSheetGeneration != sheetGeneration
                        || !expectedLayoutId.equals(activeLayoutId)) return;
                results.removeAllViews();
                renderApps(results, apps, "", existing, expectedLayoutId);
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        results.removeAllViews();
                        renderApps(results, apps, s.toString(), existing, expectedLayoutId);
                    }
                });
            });
        });
    }

    private void renderApps(LinearLayout into, List<LaunchableApp> apps, String query,
                            DeckTile existing, String expectedLayoutId) {
        String needle = query.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (LaunchableApp app : apps) {
            if (!needle.isEmpty() && !app.label.toLowerCase(Locale.US).contains(needle)) continue;
            into.addView(pickerRow(0, app.label, "", app.icon, () -> {
                if (existing == null) {
                    place(DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                            .withConfig(DeckTile.CONFIG_PACKAGE, app.packageName), expectedLayoutId);
                } else {
                    DeckLayoutStore.configure(this, expectedLayoutId, existing.instanceId,
                            DeckTile.CONFIG_PACKAGE, app.packageName);
                    closeSheet();
                    syncBackHandler();
                    refresh();
                }
            }), rowLp());
            shown++;
        }
        if (shown == 0) into.addView(emptyNote("No apps match that."));
    }

    private static final class LaunchableApp {
        final String packageName;
        final String label;
        final Drawable icon;
        LaunchableApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private List<LaunchableApp> launchableApps() {
        List<LaunchableApp> out = new ArrayList<>();
        try {
            Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(main, 0);
            List<String> seen = new ArrayList<>();
            for (ResolveInfo info : resolved) {
                if (info == null || info.activityInfo == null) continue;
                String packageName = info.activityInfo.packageName;
                if (packageName == null || seen.contains(packageName)) continue;
                seen.add(packageName);
                String label = String.valueOf(info.loadLabel(getPackageManager()));
                Drawable icon = null;
                try { icon = info.loadIcon(getPackageManager()); } catch (Exception ignored) {}
                out.add(new LaunchableApp(packageName, label, icon));
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) {}
        return out;
    }

    // ---- prompt editor ----------------------------------------------------------------------------

    /**
     * The Prompt tile's configuration.
     *
     * <p>Everything typed here stays on the phone. Saving writes it to Deck's own storage and
     * nothing else: no provider is contacted, no usage is spent, and the text never appears in
     * Diagnostics. It is sent only when the user opens the tile and presses send themselves.
     */
    private void openPromptEditor(DeckTile existing) {
        LinearLayout column = openSheet(existing == null ? "New prompt tile" : "Edit prompt tile",
                "Opens a new chat with this text ready to send");
        String expectedLayoutId = sheetLayoutId;

        EditText name = new EditText(this);
        name.setHint("Tile name");
        name.setText(existing == null ? "" : existing.config(DeckTile.CONFIG_TITLE));
        styleField(name, false);
        column.addView(name, fieldLp(UiKit.dp(this, 48)));

        EditText prompt = new EditText(this);
        prompt.setHint("Prompt text, for example: Explain this clearly and concisely:");
        prompt.setText(existing == null ? "" : existing.config(DeckTile.CONFIG_PROMPT));
        styleField(prompt, true);
        name.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_NEXT) return false;
            prompt.requestFocus();
            ensureFieldVisible(prompt);
            return true;
        });
        column.addView(prompt, fieldLp(UiKit.dp(this, 112)));

        column.addView(sectionLabel("ICON"));
        final String[] chosen = {existing == null
                ? DeckIcons.DEFAULT_KEY : existing.config(DeckTile.CONFIG_ICON)};
        if (!DeckIcons.knows(chosen[0])) chosen[0] = DeckIcons.DEFAULT_KEY;
        column.addView(iconPicker(chosen));

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(15);
        save.setTextColor(UiKit.onAccent(this));
        save.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        save.setMinHeight(0);
        save.setMinimumHeight(0);
        save.setStateListAnimator(null);
        UiKit.pressScale(save);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        saveLp.topMargin = UiKit.dp(this, 18);
        column.addView(save, saveLp);

        save.setOnClickListener(v -> {
            String text = DeckTile.sanitizePrompt(prompt.getText().toString());
            if (text.isEmpty()) { toast("Give the tile some prompt text."); return; }
            String label = DeckTile.sanitizeTitle(name.getText().toString());
            if (label.isEmpty()) label = "Prompt";
            if (existing == null) {
                place(DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                        .withConfig(DeckTile.CONFIG_PROMPT, text)
                        .withConfig(DeckTile.CONFIG_TITLE, label)
                        .withConfig(DeckTile.CONFIG_ICON, chosen[0]), expectedLayoutId);
            } else {
                DeckLayoutStore.configure(this, expectedLayoutId, existing.instanceId,
                        DeckTile.CONFIG_PROMPT, text);
                DeckLayoutStore.configure(this, expectedLayoutId, existing.instanceId,
                        DeckTile.CONFIG_TITLE, label);
                DeckLayoutStore.configure(this, expectedLayoutId, existing.instanceId,
                        DeckTile.CONFIG_ICON, chosen[0]);
                closeSheet();
                syncBackHandler();
                refresh();
            }
        });
    }

    private View iconPicker(String[] chosen) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalRow row = new HorizontalRow(this);
        List<String> keys = DeckIcons.keys();
        List<View> cells = new ArrayList<>();
        for (String key : keys) {
            ImageView cell = new ImageView(this);
            cell.setImageResource(DeckIcons.resFor(key));
            cell.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = UiKit.dp(this, 10);
            cell.setPadding(pad, pad, pad, pad);
            cell.setContentDescription(DeckIcons.labelFor(key));
            cell.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 44), UiKit.dp(this, 44));
            lp.rightMargin = UiKit.dp(this, 8);
            row.content().addView(cell, lp);
            cells.add(cell);
            cell.setOnClickListener(v -> {
                chosen[0] = key;
                paintIconCells(keys, cells, chosen[0]);
            });
        }
        paintIconCells(keys, cells, chosen[0]);
        wrap.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private void paintIconCells(List<String> keys, List<View> cells, String selected) {
        for (int i = 0; i < cells.size(); i++) {
            boolean active = keys.get(i).equals(selected);
            ImageView cell = (ImageView) cells.get(i);
            cell.setBackground(UiKit.rounded(active
                    ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE_2, 0.30f)
                    : UiKit.SURFACE_2, 14, this));
            cell.setImageTintList(ColorStateList.valueOf(active ? UiKit.accent(this) : UiKit.MUTED));
            cell.setSelected(active);
        }
    }

    /** A horizontally scrolling strip, used only by the icon picker. */
    private static final class HorizontalRow extends android.widget.HorizontalScrollView {
        private final LinearLayout content;
        HorizontalRow(Context c) {
            super(c);
            setHorizontalScrollBarEnabled(false);
            content = new LinearLayout(c);
            content.setOrientation(LinearLayout.HORIZONTAL);
            addView(content, new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout content() { return content; }
    }

    // ---- rename -----------------------------------------------------------------------------------

    private void openRenameSheet(DeckTile tile, String expectedLayoutId) {
        if (!expectedLayoutId.equals(activeLayoutId)) return;
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(this, tile, live);
        LinearLayout column = openSheet("Rename tile", "Leave it empty to use the original name");

        EditText name = new EditText(this);
        name.setHint(resolved.title);
        name.setText(tile.config(DeckTile.CONFIG_TITLE));
        styleField(name, false);
        column.addView(name, fieldLp(UiKit.dp(this, 48)));

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextSize(15);
        save.setTextColor(UiKit.onAccent(this));
        save.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        save.setMinHeight(0);
        save.setMinimumHeight(0);
        save.setStateListAnimator(null);
        UiKit.pressScale(save);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        lp.topMargin = UiKit.dp(this, 16);
        column.addView(save, lp);

        bindSingleLineSubmit(name, save, () -> {
            DeckLayoutStore.configure(this, expectedLayoutId, tile.instanceId, DeckTile.CONFIG_TITLE,
                    DeckTile.sanitizeTitle(name.getText().toString()));
            closeSheet();
            syncBackHandler();
            refresh();
        });
    }

    // ---- small helpers ----------------------------------------------------------------------------

    private void styleField(EditText field, boolean multiline) {
        field.setHintTextColor(Color.rgb(113, 119, 135));
        field.setTextColor(UiKit.TEXT);
        field.setTextSize(14.5f);
        field.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(47, 52, 66), 16, this));
        int pad = UiKit.dp(this, 13);
        field.setPadding(pad, pad, pad, pad);
        if (multiline) {
            field.setGravity(Gravity.TOP | Gravity.START);
            field.setSingleLine(false);
            field.setMaxLines(6);
        } else {
            field.setSingleLine(true);
            field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        }
        installKeyboardSafeField(field);
    }

    private void installKeyboardSafeField(EditText field) {
        field.setOnFocusChangeListener((v, focused) -> {
            if (focused) ensureFieldVisible(field);
        });
        field.addOnLayoutChangeListener((v, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) -> {
            if (field.hasFocus()) ensureFieldVisible(field);
        });
    }

    private void ensureFieldVisible(EditText field) {
        if (field == null) return;
        long generation = sheetGeneration;
        Runnable reveal = () -> {
            if (generation != sheetGeneration || !field.hasFocus() || sheetScroll == null) return;
            Rect rect = new Rect();
            field.getDrawingRect(rect);
            int margin = UiKit.dp(this, 12);
            rect.inset(0, -margin);
            field.requestRectangleOnScreen(rect, false);
        };
        field.post(reveal);
        field.postDelayed(reveal, 180L);
    }

    private void bindSingleLineSubmit(EditText field, Button button, Runnable submit) {
        final boolean[] submitting = {false};
        Runnable guarded = () -> {
            if (submitting[0]) return;
            submitting[0] = true;
            submit.run();
            button.post(() -> submitting[0] = false);
        };
        button.setOnClickListener(v -> guarded.run());
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_DONE) return false;
            guarded.run();
            return true;
        });
    }

    private LinearLayout.LayoutParams fieldLp(int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.bottomMargin = UiKit.dp(this, 12);
        return lp;
    }

    private View pickerRow(int iconRes, String title, String subtitle, Drawable drawable,
                           Runnable onChosen) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 18, this));
        int pad = UiKit.dp(this, 12);
        row.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(row);

        ImageView icon = new ImageView(this);
        if (drawable != null) {
            icon.setImageDrawable(drawable);
        } else {
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        }
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 26), UiKit.dp(this, 26));
        iconLp.rightMargin = UiKit.dp(this, 14);
        row.addView(icon, iconLp);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView label = UiKit.text(this, title, 15, UiKit.TEXT, false);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(label);
        if (subtitle != null && !subtitle.isEmpty()) {
            text.addView(UiKit.text(this, subtitle, 12, UiKit.MUTED, false));
        }
        text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(text, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setContentDescription(subtitle == null || subtitle.isEmpty()
                ? title : title + ", " + subtitle);
        row.setFocusable(true);
        row.setOnClickListener(v -> onChosen.run());
        return row;
    }

    private TextView emptyNote(String text) {
        TextView note = UiKit.text(this, text, 13, UiKit.MUTED, false);
        note.setPadding(UiKit.dp(this, 4), UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
        return note;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        b.setContentDescription(description);
        int pad = UiKit.dp(this, 12);
        b.setPadding(pad, pad, pad, pad);
        UiKit.pressScale(b);
        return b;
    }

    private void toast(String message) {
        if (message == null || message.isEmpty()) return;
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ---- test seams -------------------------------------------------------------------------------

    /** The grid, so layout structure can be asserted without reaching through the view tree. */
    DeckGridLayout gridForTest() { return grid; }

    /** Whether Deck is in edit mode. */
    boolean editingForTest() { return editing; }

    /** Puts Deck into edit mode the way a long press does. */
    void setEditingForTest(boolean value) { setEditing(value); }

    /** The Suggested container, visible only when it has something to show. */
    View suggestedForTest() { return suggestedBlock; }

    /** The empty-Deck panel. */
    View emptyForTest() { return emptyBlock; }

    /** The one-time customization hint. */
    View firstRunHintForTest() { return firstRunHint; }

    /** Applies live state as the torch callback and media read do. */
    void applyLiveForTest(DeckTileResolver.LiveState state) { applyLive(state); }

    /** Rebuilds from storage, as a resume does. */
    void refreshForTest() { refresh(); }

    void openFolderForTest(String id) { openFolder(id); }
    DeckGridLayout folderGridForTest() { return folderGrid; }
    boolean sheetOpenForTest() { return sheetOpen(); }
    View rootForTest() { return root; }
    TextView headerSubtitleForTest() { return headerSubtitle; }
    ScrollView sheetScrollForTest() { return sheetScroll; }
    View sheetPanelForTest() { return sheetPanel; }
    void openLayoutManagerForTest() { openLayoutManager(); }
    void openLayoutNameForTest() {
        openLayoutNameSheet("Name layout", "New Deck", name -> false);
    }
    void openStructuralRenameForTest(DeckItem item) {
        openStructuralRenameSheet(item, activeLayoutId);
    }
    void openTileRenameForTest(DeckTile tile) { openRenameSheet(tile, activeLayoutId); }
    void openPromptEditorForTest(DeckTile tile) { openPromptEditor(tile); }
}

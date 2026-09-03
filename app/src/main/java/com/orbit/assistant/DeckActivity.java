package com.orbit.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.view.ViewGroup;
import android.view.Window;
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

    private boolean editing;
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
        UiKit.applyActivityInsets(this, root, false);
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
            UiKit.applyActivityInsets(this, root, false);
        }
        refresh();
        startWatchingTorch();
        readMediaState();
    }

    @Override protected void onPause() {
        // Live state is only ever read while the screen is in front of somebody.
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
        root.setBackgroundColor(UiKit.BG);

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
        grid.setOnReorderListener(this::persistOrder);
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

        ImageButton customize = iconButton(R.drawable.ic_edit, "Customize Deck");
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
        List<DeckTile> tiles = DeckLayoutStore.layout(this);

        grid.removeAllViews();
        for (DeckTile tile : tiles) grid.addView(tileView(tile), gridParams(tile));

        boolean empty = tiles.isEmpty();
        grid.setVisibility(empty ? View.GONE : View.VISIBLE);
        deckHeadingRow.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyBlock.setVisibility(empty ? View.VISIBLE : View.GONE);
        // Shown until the Deck has been touched at all, then never again.
        firstRunHint.setVisibility(!empty && !DeckLayoutStore.configured(this)
                ? View.VISIBLE : View.GONE);

        headerSubtitle.setText(editing ? "Editing" : "Your shortcuts");
        renderSuggestions(tiles);
    }

    private DeckGridLayout.LayoutParams gridParams(DeckTile tile) {
        return new DeckGridLayout.LayoutParams(tile.size == DeckTile.Size.WIDE ? 2 : 1);
    }

    private DeckTileView tileView(DeckTile tile) {
        DeckTileView view = new DeckTileView(this, tile,
                DeckTileResolver.resolve(this, tile, live), tileListener());
        view.setEditing(editing);
        if (editing) installDrag(view);
        return view;
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
        UiKit.showOrbitMenu(this, suggestedBlock, new String[]{"Add to Deck"}, -1, (index, label) -> {
            if (DeckLayoutStore.wouldDuplicate(this, suggestion.addable)) {
                toast("That is already on your Deck.");
                return;
            }
            if (DeckLayoutStore.add(this, suggestion.addable)) {
                toast("Added to Deck");
                refresh();
            }
        });
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
        headerSubtitle.setText(editing ? "Editing" : "Your shortcuts");
        for (View child : grid.orderedChildren()) {
            if (!(child instanceof DeckTileView)) continue;
            DeckTileView view = (DeckTileView) child;
            view.setEditing(editing);
            if (editing) installDrag(view);
            else view.setOnTouchListener(null);
        }
        renderSuggestions(DeckLayoutStore.layout(this));
        syncBackHandler();
    }

    private void showDeckOptions(View anchor) {
        UiKit.showOrbitMenu(this, anchor, new String[]{"Add tile", "Reset Deck"}, -1, (index, label) -> {
            if (index == 0) openAddSheet();
            else confirmReset();
        });
    }

    private void confirmReset() {
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Reset Deck")
                .setMessage("Put the default tiles back. Your Routines, reminders, memories and "
                        + "chats are not affected.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset", (d, w) -> {
                    DeckLayoutStore.reset(this);
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
        DeckTileRegistry.Definition definition = DeckTileRegistry.definition(tile.type);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (definition != null && definition.configurable) {
            labels.add("Edit");
            actions.add(() -> configure(tile));
        }
        if (definition != null && definition.sizes.size() > 1) {
            boolean wide = tile.size == DeckTile.Size.WIDE;
            labels.add(wide ? "Make standard" : "Make wide");
            actions.add(() -> resize(tile, wide ? DeckTile.Size.STANDARD : DeckTile.Size.WIDE));
        }
        labels.add("Rename");
        actions.add(() -> openRenameSheet(tile));

        int index = indexOf(tile);
        if (index > 0) {
            labels.add("Move before");
            actions.add(() -> move(tile, index - 1));
        }
        if (index >= 0 && index < DeckLayoutStore.layout(this).size() - 1) {
            labels.add("Move after");
            actions.add(() -> move(tile, index + 1));
        }
        labels.add("Remove");
        actions.add(() -> removeTile(tile));

        UiKit.showOrbitMenu(this, view, labels.toArray(new String[0]), -1,
                (choice, label) -> { if (choice >= 0 && choice < actions.size()) actions.get(choice).run(); });
    }

    private int indexOf(DeckTile tile) {
        List<DeckTile> tiles = DeckLayoutStore.layout(this);
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).instanceId.equals(tile.instanceId)) return i;
        }
        return -1;
    }

    private void move(DeckTile tile, int target) {
        List<DeckTile> tiles = new ArrayList<>(DeckLayoutStore.layout(this));
        int from = indexOf(tile);
        if (from < 0 || target < 0 || target >= tiles.size()) return;
        DeckTile moved = tiles.remove(from);
        tiles.add(target, moved);
        List<String> order = new ArrayList<>();
        for (DeckTile item : tiles) order.add(item.instanceId);
        DeckLayoutStore.applyOrder(this, order);
        refresh();
        grid.animateNextLayout();
    }

    private void resize(DeckTile tile, DeckTile.Size size) {
        if (!DeckLayoutStore.resize(this, tile.instanceId, size)) {
            toast("That tile cannot be that size.");
            return;
        }
        refresh();
        grid.animateNextLayout();
    }

    private void removeTile(DeckTile tile) {
        if (!DeckLayoutStore.remove(this, tile.instanceId)) return;
        refresh();
        grid.animateNextLayout();
    }

    private void persistOrder(List<View> ordered) {
        List<String> ids = new ArrayList<>();
        for (View child : ordered) {
            if (child instanceof DeckTileView) ids.add(((DeckTileView) child).tile().instanceId);
        }
        DeckLayoutStore.applyOrder(this, ids);
    }

    // ---- dragging ---------------------------------------------------------------------------------

    /**
     * Drag to reorder, while editing only.
     *
     * <p>A press-and-hold on a tile that is already in edit mode picks it up; the same gesture
     * outside edit mode is what entered edit mode in the first place. The scroll view is asked to
     * stand aside for the duration so a vertical drag moves the tile rather than the page.
     */
    private void installDrag(DeckTileView view) {
        final float[] down = new float[2];
        final boolean[] dragging = {false};
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = event.getRawX();
                    down[1] = event.getRawY();
                    dragging[0] = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (!editing) return false;
                    float dx = event.getRawX() - down[0];
                    float dy = event.getRawY() - down[1];
                    if (!dragging[0]) {
                        if (Math.hypot(dx, dy) < UiKit.dp(this, 10)) return false;
                        dragging[0] = true;
                        // One pickup tick, and nothing after it: no haptic per pixel crossed.
                        UiKit.haptic(v, HapticFeedbackConstants.LONG_PRESS);
                        view.setCarried(true);
                        grid.beginDrag(view);
                        scroll.requestDisallowInterceptTouchEvent(true);
                    }
                    grid.updateDrag(dx, dy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging[0]) {
                        dragging[0] = false;
                        view.setCarried(false);
                        grid.endDrag();
                        scroll.requestDisallowInterceptTouchEvent(false);
                        // Consumed, so the drop does not also register as a tap on the tile.
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        });
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
        if (!editing) renderSuggestions(DeckLayoutStore.layout(this));
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
        sheetHost.removeAllViews();
        sheetHost.setVisibility(View.VISIBLE);

        View scrim = new View(this);
        scrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        scrim.setOnClickListener(v -> { closeSheet(); syncBackHandler(); });
        scrim.setContentDescription("Close");
        sheetHost.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
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
            if (body.getHeight() > maxHeight) {
                ViewGroup.LayoutParams lp = body.getLayoutParams();
                lp.height = maxHeight;
                body.setLayoutParams(lp);
            }
        });

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
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
        sheetHost.removeAllViews();
        sheetHost.setVisibility(View.GONE);
    }

    // ---- add flow ---------------------------------------------------------------------------------

    private void openAddSheet() {
        LinearLayout column = openSheet("Add tile", "Choose what this tile should do");
        for (DeckTileRegistry.Category category : DeckTileRegistry.categories()) {
            column.addView(sectionLabel(category.label.toUpperCase(Locale.US)));
            for (DeckTileRegistry.Definition definition : DeckTileRegistry.inCategory(category)) {
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
        if (DeckLayoutStore.wouldDuplicate(this, tile)) {
            toast("That is already on your Deck.");
            closeSheet();
            syncBackHandler();
            return;
        }
        if (!DeckLayoutStore.add(this, tile)) {
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
        if (DeckTileRegistry.TYPE_ROUTINE.equals(tile.type)) openRoutinePicker(tile);
        else if (DeckTileRegistry.TYPE_APP.equals(tile.type)) openAppPicker(tile);
        else if (DeckTileRegistry.TYPE_PROMPT.equals(tile.type)) openPromptEditor(tile);
    }

    // ---- routine picker ---------------------------------------------------------------------------

    private void openRoutinePicker(DeckTile existing) {
        LinearLayout column = openSheet("Choose a Routine",
                "The tile runs it exactly as the Routines screen does");
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
                                    .withConfig(DeckTile.CONFIG_ROUTINE_ID, routine.id));
                        } else {
                            DeckLayoutStore.configure(this, existing.instanceId,
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

        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setHintTextColor(Color.rgb(113, 119, 135));
        search.setTextColor(UiKit.TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(UiKit.outlined(UiKit.SURFACE_2, Color.rgb(47, 52, 66), 16, this));
        search.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
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
                if (!sheetOpen()) return;
                results.removeAllViews();
                renderApps(results, apps, "", existing);
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        results.removeAllViews();
                        renderApps(results, apps, s.toString(), existing);
                    }
                });
            });
        });
    }

    private void renderApps(LinearLayout into, List<LaunchableApp> apps, String query,
                            DeckTile existing) {
        String needle = query.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (LaunchableApp app : apps) {
            if (!needle.isEmpty() && !app.label.toLowerCase(Locale.US).contains(needle)) continue;
            into.addView(pickerRow(0, app.label, "", app.icon, () -> {
                if (existing == null) {
                    place(DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                            .withConfig(DeckTile.CONFIG_PACKAGE, app.packageName));
                } else {
                    DeckLayoutStore.configure(this, existing.instanceId,
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

        EditText name = new EditText(this);
        name.setHint("Tile name");
        name.setText(existing == null ? "" : existing.config(DeckTile.CONFIG_TITLE));
        styleField(name, false);
        column.addView(name, fieldLp(UiKit.dp(this, 48)));

        EditText prompt = new EditText(this);
        prompt.setHint("Prompt text, for example: Explain this clearly and concisely:");
        prompt.setText(existing == null ? "" : existing.config(DeckTile.CONFIG_PROMPT));
        styleField(prompt, true);
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
                        .withConfig(DeckTile.CONFIG_ICON, chosen[0]));
            } else {
                DeckLayoutStore.configure(this, existing.instanceId, DeckTile.CONFIG_PROMPT, text);
                DeckLayoutStore.configure(this, existing.instanceId, DeckTile.CONFIG_TITLE, label);
                DeckLayoutStore.configure(this, existing.instanceId, DeckTile.CONFIG_ICON, chosen[0]);
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

    private void openRenameSheet(DeckTile tile) {
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

        save.setOnClickListener(v -> {
            DeckLayoutStore.configure(this, tile.instanceId, DeckTile.CONFIG_TITLE,
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
        }
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
}

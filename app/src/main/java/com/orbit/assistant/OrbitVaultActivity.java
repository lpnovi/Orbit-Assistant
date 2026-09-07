package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
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
import java.util.List;

/**
 * Orbit Vault: the things the user deliberately kept, and the fastest way to add another.
 *
 * <p>Not a notes app and not a second chat list. The loop this screen has to make excellent is
 * capture, save, find, reopen - so it is one column of plain cards, one search field, and one
 * capture control.
 *
 * <p>Beta 4 adds the smallest organization that a growing collection genuinely needs, and stops
 * there. Type, an optional source, and a pin: three questions asked about one flat list, none of
 * which moves, copies, files or renames anything. There are deliberately still no folders, no
 * tags, no collections and no automatic categorization - a saved item lives in exactly one place,
 * and organizing the Vault must never become a second job the user has to do before it is useful.
 *
 * <p>Beta 5 changes none of that and changes how all of it is operated. Beta 4 spent those three
 * questions on a horizontally scrolling row of eight controls, which was correct and unreadable:
 * six type chips filled the phone, Source was parked off the right edge where the user had to be
 * told it existed, and turning one choice off meant reaching for Clear filters and losing the other
 * two with it. It is now two selectors on one fixed row - what is it, and where did I save it from
 * - each of which resets itself, and the cards below behave like every other list in Orbit: swipe
 * left to delete, swipe right to pin.
 *
 * <p>Nothing on this screen contacts a provider. Opening the Vault, searching it, saving into it
 * and reading it back all work with the phone in flight mode and no account signed in, which is the
 * promise the feature is built on.
 */
public final class OrbitVaultActivity extends Activity {

    /**
     * Opens this screen with Quick Capture already showing.
     *
     * <p>How Orbit Deck's Quick Capture tile reaches the capture flow, and deliberately the only
     * way it does. A Deck tile is a shortcut to Orbit's own behaviour rather than a second copy
     * of it, so the tile opens this screen and asks it to do the thing its own button does.
     *
     * <p>Consumed once. The extra is removed as it is read, so rotating the phone or coming back
     * to the screen later does not reopen the menu over a Vault the user is trying to read.
     */
    public static final String EXTRA_QUICK_CAPTURE = "orbit_vault_quick_capture";

    /** Quick Capture's three ways in, in the order the menu lists them. */
    static final String CAPTURE_WRITE = "Write text";
    static final String CAPTURE_PASTE = "Paste clipboard";
    static final String CAPTURE_IMAGE = "Add image";

    static final String EMPTY_TITLE = "Your Vault is empty";
    static final String EMPTY_BODY =
            "Save text, images, links, and useful Orbit answers here.";

    /**
     * The three ways a list can be empty, said apart rather than together.
     *
     * <p>"Your Vault is empty" in front of a Vault holding forty things is simply a lie, and it is
     * the exact moment somebody decides a filter is broken. A collection with nothing in it, a
     * search that found nothing, and a filter that matches nothing are three different situations
     * with three different next steps, so the screen says which one it is.
     */
    static final String NO_RESULTS_TITLE = "No matching items";
    static final String NO_RESULTS_SEARCH =
            "Search looks at titles, text, links, your own notes and where an item came from.";
    static final String NO_RESULTS_FILTER =
            "Nothing in your Vault matches this filter. Try another one, or clear filters to see "
                    + "everything.";
    static final String NO_RESULTS_BOTH =
            "Nothing matches this search and this filter together. Try clearing one of them.";

    /**
     * The small trailing control that puts both selectors back at once.
     *
     * <p>Secondary on purpose, and shown only while <em>both</em> selectors are narrowing. Beta 4
     * made this the practical way to turn any single choice off, because a selected type chip could
     * not be deselected by tapping it again; each selector now carries its own way back, so with
     * one constraint in force this control would be a second button that does what the first one
     * already does. It resets Type and Source and deliberately leaves the search field alone -
     * words the user typed are their own question, and the field has its own way to clear.
     */
    static final String CLEAR_FILTERS = "Clear filters";
    /** The Saved-from selector's resting value: no source chosen. */
    static final String SOURCE_ANY = "Any source";
    /** What the Saved-from selector is asking, in the spoken description and nowhere else. */
    static final String SOURCE_QUESTION = "Saved from";
    /** And what the Type selector is asking. */
    static final String TYPE_QUESTION = "Type";
    /** The heading above the items the user asked to keep near the top. */
    static final String PINNED_HEADING = "Pinned";
    /** And the one above everything else, shown only when there is a pinned section above it. */
    static final String OTHERS_HEADING = "Everything else";

    static final String ACTION_PIN = "Pin";
    static final String ACTION_UNPIN = "Unpin";

    /** The optional note field, in the same words wherever something is being saved. */
    static final String NOTE_FIELD_HINT = "Note (optional)";

    /** What the Vault says about itself when the user has switched it off in Settings. */
    static final String OFF_TITLE = "Orbit Vault is turned off";
    static final String OFF_BODY =
            "Nothing has been deleted. Turn Orbit Vault back on in Settings to save new items "
                    + "and to see everything you already saved.";

    /** What a swiped-away card says while it can still be brought back. */
    static final String UNDO_MESSAGE = "Item deleted";
    /** What the spoken swipe actions call one Vault card. */
    static final String SWIPE_SUBJECT = "saved item";

    /**
     * How wide the two selectors together are allowed to become.
     *
     * <p>Never reached on a phone, where the row is simply the width of the screen. A Tab S9 Plus
     * does reach it, and two controls stretched across a whole tablet stop reading as a compact
     * question and start reading as a toolbar. The search field above keeps the full width, because
     * a field genuinely uses it.
     */
    static final int FILTERS_MAX_WIDTH_DP = 520;

    /** How long a swiped-away item can be taken back before the deletion is carried out. */
    private static final long UNDO_WINDOW_MS = 5200L;

    private static final int REQ_PICK_IMAGE = 8401;

    private LinearLayout list;
    private ScrollView listScroller;
    private TextView subtitle;
    private EditText searchInput;
    private LinearLayout filterBar;
    private Button capture;
    private LinearLayout undoBar;
    private boolean quickCapturePending;
    private String appearanceSignature = "";

    /**
     * The item that is being deleted but has not been yet.
     *
     * <p>Held by id rather than by a copy, exactly as a deleted chat is, and for a much stronger
     * reason here: a Vault item can own a private picture. Nothing is removed from the store and no
     * file is touched while the offer stands, so Undo is not a restore that has to reassemble an
     * id, a note, two timestamps, a pin, document metadata and a media path without dropping one of
     * them - it is Orbit forgetting it was asked. A snapshot-and-rebuild scheme would have to copy
     * or re-adopt the picture, and would silently lose whichever field somebody forgot to add to it
     * later.
     *
     * <p>It is also what makes process death safe. If Orbit is killed inside the window the item is
     * simply still there, complete, with its picture where it always was. There is no half-deleted
     * state to recover from and no orphaned file to clean up, because nothing was ever deleted.
     */
    private String pendingDeletionId;
    private final Runnable undoTimeout = this::commitPendingDeletion;
    /**
     * Where the Undo window is counted.
     *
     * <p>Not on the bar itself, which is replaced whenever the page is rebuilt; a callback posted
     * to a view that has gone cannot be taken off again, which would leave a deletion counting down
     * with nothing on screen offering to stop it.
     */
    private final android.os.Handler undoTimer =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        navigation = OrbitPredictiveBack.install(this);
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_QUICK_CAPTURE, false)) {
            intent.removeExtra(EXTRA_QUICK_CAPTURE);
            quickCapturePending = true;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        // Accent, AMOLED, font and Theme Studio changes reach this page the way they reach every
        // other one: the appearance is re-read on the way back in, and the page is rebuilt only
        // when it has genuinely changed.
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        refresh();
        if (quickCapturePending) {
            quickCapturePending = false;
            // Only when the Vault is actually on. A tile tapped while it is off has already
            // said so, and opening a capture menu over the turned-off notice would offer a
            // control that cannot write anything.
            if (capture != null && OrbitVaultStore.enabled(this)) {
                capture.post(() -> showCaptureMenu(capture));
            }
        }
    }

    @Override protected void onPause() {
        // Leaving the Vault ends the offer. A deletion the user walked away from is a deletion they
        // meant, and leaving it pending would make it depend on this process staying alive.
        commitPendingDeletion();
        OrbitSwipeRow.resetActive();
        UiPresence.leave(this);
        super.onPause();
    }

    // ---- the page --------------------------------------------------------------------------------

    /**
     * The Vault, with the transient Undo surface floating over it rather than sharing the page.
     *
     * <p>The same frame Chats uses, for the same reason. A bar that took its height out of the
     * weighted list would give the list a new, higher bottom the moment something was deleted, and
     * whichever card straddled it would be sliced flat against the window background. The list
     * keeps its full height whether the bar is there or not.
     */
    private View build() {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(UiKit.BG);
        int side = UiKit.dp(this, 18);
        host.setPadding(side, UiKit.dp(this, 10), side, 0);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> navigation.performBack());
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Vault", 24, UiKit.TEXT, true));
        subtitle = UiKit.text(this, "", 12, UiKit.MUTED, false);
        titles.addView(subtitle);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this, 14), 0, 0, 0);
        top.addView(titles, titleLp);

        ImageButton sort = iconButton(R.drawable.ic_tune, "Sort saved items");
        sort.setOnClickListener(this::showSortMenu);
        top.addView(sort, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));
        root.addView(top);

        capture = new Button(this);
        capture.setText("+  Save to Vault");
        capture.setTextColor(UiKit.onAccent(this));
        capture.setTextSize(15);
        capture.setAllCaps(false);
        capture.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        capture.setMinHeight(0);
        capture.setMinimumHeight(0);
        capture.setStateListAnimator(null);
        capture.setContentDescription("Quick Capture: save something to your Vault");
        capture.setOnClickListener(this::showCaptureMenu);
        UiKit.pressScale(capture);
        LinearLayout.LayoutParams captureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        captureLp.setMargins(0, UiKit.dp(this, 16), 0, UiKit.dp(this, 12));
        root.addView(capture, captureLp);

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("Search your Vault");
        searchInput.setContentDescription("Search your Vault");
        searchInput.setTextColor(UiKit.TEXT);
        searchInput.setHintTextColor(UiKit.MUTED);
        searchInput.setTextSize(14);
        searchInput.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 14), 0);
        searchInput.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 52), 16, this));
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 46));
        searchLp.setMargins(0, 0, 0, UiKit.dp(this, 8));
        root.addView(searchInput, searchLp);

        // Two selectors on one row that always fits, rather than a conveyor belt of chips the user
        // had to drag sideways to discover. Both questions are on screen at once, both are the same
        // height as one line of text, and neither pushes the collection down the page.
        filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams filterLp = new LinearLayout.LayoutParams(
                filterBarWidth(), ViewGroup.LayoutParams.WRAP_CONTENT);
        filterLp.setMargins(0, 0, 0, UiKit.dp(this, 8));
        root.addView(filterBar, filterLp);

        listScroller = new ScrollView(this);
        listScroller.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 36));
        listScroller.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listScroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        host.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        barLp.bottomMargin = UiKit.dp(this, 12);
        host.addView(buildUndoBar(), barLp);
        return host;
    }

    /**
     * How wide the selector row is allowed to be on this device.
     *
     * <p>The whole width on a phone, and capped on a tablet. Left-aligned rather than centred when
     * it is capped, so it starts under the start of the search field instead of floating in the
     * middle of the page away from everything it belongs to.
     */
    private int filterBarWidth() {
        int available = getResources().getDisplayMetrics().widthPixels;
        int capped = UiKit.dp(this, FILTERS_MAX_WIDTH_DP);
        return capped >= available ? ViewGroup.LayoutParams.MATCH_PARENT : capped;
    }

    /**
     * The short window in which a swiped-away item can be brought back.
     *
     * <p>Built once and kept, floating over the bottom of the list the card just left, with a real
     * focusable Undo control. It occupies no space at all until there is something to undo.
     */
    private View buildUndoBar() {
        undoBar = new LinearLayout(this);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setVisibility(View.GONE);
        undoBar.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 12), UiKit.dp(this, 10),
                UiKit.dp(this, 12));
        undoBar.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 46), 18, this));
        undoBar.setElevation(UiKit.dp(this, 8));

        undoBar.addView(UiKit.text(this, UNDO_MESSAGE, 14, UiKit.TEXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button undo = new Button(this);
        undo.setText("Undo");
        undo.setAllCaps(false);
        undo.setTextSize(14);
        undo.setTextColor(UiKit.accent(this));
        undo.setMinHeight(0);
        undo.setMinimumHeight(0);
        undo.setStateListAnimator(null);
        undo.setBackground(UiKit.ripple(UiKit.SURFACE_3, UiKit.accent(this), 14, this));
        undo.setContentDescription("Undo deleting this saved item");
        undo.setOnClickListener(v -> undoPendingDeletion());
        UiKit.pressScale(undo);
        LinearLayout.LayoutParams undoLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 40));
        undoLp.leftMargin = UiKit.dp(this, 10);
        undoBar.addView(undo, undoLp);
        return undoBar;
    }

    /**
     * How many cards sit side by side at this width.
     *
     * <p>One responsive rule rather than a separate tablet screen, in the same shape Orbit Deck
     * already uses: a phone gets one readable column, a Tab S9 Plus earns two, and a genuinely wide
     * landscape tablet earns three. Past that a saved note would be a postage stamp.
     */
    static int columnsForWidth(int widthDp) {
        if (widthDp >= 1000) return 3;
        if (widthDp >= 640) return 2;
        return 1;
    }

    private int columns() {
        return columnsForWidth(getResources().getConfiguration().screenWidthDp);
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();

        // Every ordinary way in is gone while the Vault is off, so reaching this page at all means
        // arriving from somewhere that outlived the preference - a task the user left open, a
        // recents entry. Saying what happened, and that nothing was lost, is the only useful thing
        // the screen can do; offering Quick Capture would be offering a control that cannot work.
        boolean enabled = OrbitVaultStore.enabled(this);
        if (capture != null) capture.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (searchInput != null) searchInput.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (filterBar != null) filterBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (!enabled) {
            subtitle.setText("Turned off in Settings");
            LinearLayout off = card();
            off.addView(UiKit.text(this, OFF_TITLE, 16, UiKit.TEXT, true));
            TextView body = UiKit.text(this, OFF_BODY, 13, UiKit.MUTED, false);
            body.setPadding(0, UiKit.dp(this, 6), 0, 0);
            off.addView(body);
            list.addView(off, cardLp());
            return;
        }

        OrbitVaultStore.Sort sort = Prefs.vaultSort(this);
        String query = searchInput == null ? "" : searchInput.getText().toString().trim();
        // The selectors carry the type and the source, the field carries the words, and the three
        // are one question from here on. Nothing below asks any of them separately.
        OrbitVaultFilter filter = Prefs.vaultFilter(this).withQuery(query);
        rebuildFilterBar(filter);
        List<OrbitVaultItem> shown = OrbitVaultStore.browse(this, filter, sort);
        // An item waiting to be deleted is out of the list but still in storage, which is what
        // makes Undo complete rather than a reconstruction. It is out of the count as well, so the
        // header does not go on claiming something the user has just watched leave.
        int total = OrbitVaultStore.count(this);
        if (pendingDeletionId != null) {
            int before = shown.size();
            shown.removeIf(item -> pendingDeletionId.equals(item.id));
            if (before != shown.size() || OrbitVaultStore.get(this, pendingDeletionId) != null) {
                total = Math.max(0, total - 1);
            }
        }

        String counted = total + (total == 1 ? " saved item" : " saved items");
        if (total == 0) {
            subtitle.setText("Nothing saved yet");
        } else if (filter.isNarrowed()) {
            // What is on screen, and how much of the collection that is, so a short list after a
            // filter never reads as a Vault that has lost things.
            subtitle.setText(shown.size() + " of " + counted + " · " + sort.label);
        } else {
            subtitle.setText(counted + " · " + sort.label);
        }

        if (total == 0) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, EMPTY_TITLE, 16, UiKit.TEXT, true));
            TextView body = UiKit.text(this, EMPTY_BODY, 13, UiKit.MUTED, false);
            body.setPadding(0, UiKit.dp(this, 6), 0, UiKit.dp(this, 2));
            empty.addView(body);
            TextView how = UiKit.text(this,
                    "Use Save to Vault above, share something to Orbit from another app, or "
                            + "hold an Orbit answer in a chat and choose Save to Vault.",
                    12, UiKit.MUTED, false);
            how.setPadding(0, UiKit.dp(this, 8), 0, 0);
            empty.addView(how);
            list.addView(empty, cardLp());
            return;
        }

        if (shown.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(UiKit.text(this, NO_RESULTS_TITLE, 15, UiKit.TEXT, true));
            // Which of the three situations this actually is. The Vault is not empty here - it
            // has items and the current question has no answers - so it must never say it is.
            String body = filter.hasTypeOrSource()
                    ? (filter.hasQuery() ? NO_RESULTS_BOTH : NO_RESULTS_FILTER)
                    : NO_RESULTS_SEARCH;
            TextView hint = UiKit.text(this, body, 12, UiKit.MUTED, false);
            hint.setPadding(0, UiKit.dp(this, 5), 0, 0);
            empty.addView(hint);
            list.addView(empty, cardLp());
            return;
        }

        // Pinned first, then everything else, with each item in exactly one of the two. Both
        // sections are drawn from the same filtered, searched, sorted list, so pinning a document
        // page does not make it appear while the user is looking at links.
        List<OrbitVaultItem> pinned = new ArrayList<>();
        List<OrbitVaultItem> rest = new ArrayList<>();
        for (OrbitVaultItem item : shown) (item.pinned ? pinned : rest).add(item);

        if (!pinned.isEmpty()) {
            list.addView(sectionHeading(PINNED_HEADING, false));
            addCards(pinned);
            if (!rest.isEmpty()) list.addView(sectionHeading(OTHERS_HEADING, true));
        }
        addCards(rest);
    }

    /**
     * One group of cards, in the current column layout.
     *
     * <p>Shared by the pinned group and the ordinary one rather than written twice, so the
     * responsive rule, the gutters and the part-filled last row cannot drift apart between the two
     * halves of the same screen.
     */
    private void addCards(List<OrbitVaultItem> items) {
        if (items.isEmpty()) return;
        int columns = columns();
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (columns == 1) {
                list.addView(itemCard(items.get(i)), cardLp());
                continue;
            }
            if (i % columns == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                list.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            cellLp.setMargins(i % columns == 0 ? 0 : UiKit.dp(this, 5), 0,
                    i % columns == columns - 1 ? 0 : UiKit.dp(this, 5), UiKit.dp(this, 10));
            row.addView(itemCard(items.get(i)), cellLp);
        }
        // A part-filled last row keeps its cards at column width instead of stretching one of them
        // across the whole tablet.
        if (columns > 1 && row != null) {
            int missing = (columns - (items.size() % columns)) % columns;
            for (int i = 0; i < missing; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
            }
        }
    }

    /** A small muted label above a group of cards. Never a card of its own. */
    private TextView sectionHeading(String name, boolean spacedAbove) {
        TextView heading = UiKit.text(this, name, 11, UiKit.MUTED, true);
        heading.setLetterSpacing(0.12f);
        heading.setPadding(UiKit.dp(this, 3), UiKit.dp(this, spacedAbove ? 8 : 1), 0,
                UiKit.dp(this, 8));
        return heading;
    }

    // ---- narrowing the collection -----------------------------------------------------------------

    /**
     * Redraws the two selectors for the filter that is currently in force.
     *
     * <p>Rebuilt rather than toggled, because each selector's closed state <em>is</em> the current
     * answer: the row reads "Images / Screen selection" when that is what is on screen, so the
     * screen needs no separate banner saying what is being shown. Rebuilding is also what keeps the
     * accent, the AMOLED surfaces and the selected treatment correct after a theme change.
     *
     * <p>Both controls are always present. Beta 4 showed the source choice only when the Vault
     * already held more than one source, which is exactly the shape of thing a person cannot
     * discover: it was absent when they first looked, appeared later without being announced, and
     * sat behind six chips when it did. A selector that is always there and sometimes has one
     * answer costs a line of nothing and can be found.
     */
    private void rebuildFilterBar(OrbitVaultFilter filter) {
        if (filterBar == null) return;
        filterBar.removeAllViews();

        boolean typed = filter.type != OrbitVaultFilter.Type.ALL;
        filterBar.addView(selector(filter.type.label, typed,
                        TYPE_QUESTION + ": " + filter.type.label
                                + ". Tap to choose what kind of saved item to show.",
                        v -> showTypeMenu(v, filter)),
                selectorLp(true));

        boolean sourced = !filter.source.isEmpty();
        String from = sourced ? OrbitVaultSource.displayLabel(filter.source) : SOURCE_ANY;
        filterBar.addView(selector(from, sourced,
                        SOURCE_QUESTION + ": " + from
                                + ". Tap to choose where an item was saved from.",
                        v -> showSourceMenu(v, filter)),
                selectorLp(false));

        // Only once both questions are narrowing at the same time. With one in force, its own
        // selector already offers the way back in one tap, and a second control doing the same
        // thing is how Beta 4's row grew.
        if (typed && sourced) {
            ImageButton clear = iconButton(R.drawable.ic_close, CLEAR_FILTERS);
            clear.setOnClickListener(v -> clearFilters());
            LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 40), UiKit.dp(this, 40));
            clearLp.setMargins(UiKit.dp(this, 6), 0, 0, 0);
            filterBar.addView(clear, clearLp);
        }
    }

    /**
     * One filter selector: its current answer, and a caret saying there are others.
     *
     * <p>Weighted rather than sized by its words, so the row is the same shape whichever answers
     * are showing and a long one - "Screen selection", "Orbit Documents" - shortens itself instead
     * of pushing its neighbour off the screen. The chosen state is the accent fill with its own
     * contrast colour, and it is written into the spoken description as well, so which selector is
     * narrowing the Vault is never carried by colour alone.
     */
    private View selector(String label, boolean chosen, String description,
                          View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(UiKit.dp(this, 14), 0, UiKit.dp(this, 10), 0);

        TextView value = UiKit.text(this, label, 13,
                chosen ? UiKit.onAccent(this) : UiKit.TEXT, chosen);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        box.addView(value, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView caret = new ImageView(this);
        caret.setImageResource(R.drawable.ic_chevron_down);
        caret.setImageTintList(ColorStateList.valueOf(
                chosen ? UiKit.onAccent(this) : UiKit.accent(this)));
        caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        caretLp.setMargins(UiKit.dp(this, 6), 0, 0, 0);
        box.addView(caret, caretLp);

        box.setBackground(chosen
                ? UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 16, this)
                : UiKit.rippleOutlined(UiKit.SURFACE,
                        UiKit.withAlpha(UiKit.accent(this), 46), UiKit.accent(this), 16, this));
        box.setContentDescription(description + (chosen ? " Currently filtering." : ""));
        box.setOnClickListener(onClick);
        UiKit.pressScale(box);
        return box;
    }

    private LinearLayout.LayoutParams selectorLp(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 42), 1);
        lp.setMargins(first ? 0 : UiKit.dp(this, 8), 0, 0, 0);
        return lp;
    }

    /**
     * What kind of thing to show, including the choice that shows everything.
     *
     * <p>"All items" is an ordinary row in this list rather than a separate control, which is the
     * whole of the independent-reset fix: turning a type filter off is choosing the first answer to
     * the question that set it, and it reaches {@code withType} - so the source and the search text
     * are carried straight through untouched.
     */
    private void showTypeMenu(View anchor, OrbitVaultFilter filter) {
        OrbitVaultFilter.Type[] types = OrbitVaultFilter.Type.values();
        String[] labels = new String[types.length];
        int selected = 0;
        for (int i = 0; i < types.length; i++) {
            labels[i] = types[i].label;
            if (types[i] == filter.type) selected = i;
        }
        UiKit.showOrbitMenu(this, anchor, labels, selected, (index, label) -> {
            if (index < 0 || index >= types.length) return;
            applyFilter(filter.withType(types[index]));
        });
    }

    /**
     * Where an item was saved from, offered only for the doors this Vault has actually seen.
     *
     * <p>The list is built from canonical {@link OrbitVaultSource} values and the labels are only
     * drawn from them, so choosing a row applies the stored word rather than the shown one. A
     * source that is currently in force but no longer present in the collection is still listed:
     * otherwise a filter could survive its last item and leave the user unable to turn it off from
     * the control that set it.
     */
    private void showSourceMenu(View anchor, OrbitVaultFilter filter) {
        List<String> sources = new ArrayList<>(OrbitVaultStore.sourcesPresent(this));
        if (!filter.source.isEmpty() && !sources.contains(filter.source)) {
            sources.add(0, filter.source);
        }
        String[] labels = new String[sources.size() + 1];
        labels[0] = SOURCE_ANY;
        int selected = 0;
        for (int i = 0; i < sources.size(); i++) {
            labels[i + 1] = OrbitVaultSource.displayLabel(sources.get(i));
            if (sources.get(i).equals(filter.source)) selected = i + 1;
        }
        UiKit.showOrbitMenu(this, anchor, labels, selected, (index, label) ->
                applyFilter(filter.withSource(index <= 0 ? "" : sources.get(index - 1))));
    }

    private void applyFilter(OrbitVaultFilter filter) {
        Prefs.setVaultFilter(this, filter);
        OrbitSwipeRow.resetActive();
        refresh();
    }

    /**
     * Puts both selectors back to their resting answers, and leaves the search field alone.
     *
     * <p>Beta 4's version also emptied the search box, because it was the one control that could
     * undo a type chip and so had to promise the whole Vault to be worth reaching for. It is no
     * longer that control. Words the user typed are a separate question with its own way to clear,
     * and silently deleting them because somebody reset a type filter is the kind of surprise that
     * makes people stop trusting a screen.
     */
    private void clearFilters() {
        Prefs.setVaultFilter(this, OrbitVaultFilter.NONE);
        OrbitSwipeRow.resetActive();
        refresh();
    }

    // ---- one saved thing --------------------------------------------------------------------------

    /**
     * One saved item, wrapped in the gesture the rest of Orbit already uses.
     *
     * <p>The card is wrapped rather than replaced, so everything the list draws is the card it has
     * always been, and the wrapper is Chats' own {@link OrbitSwipeRow} rather than a second gesture
     * detector written for the Vault. Two detectors would have been within a few pixels of each
     * other on the day this shipped and would have drifted afterwards; the commit threshold, the
     * resistance past it, the scroll arbitration and the settle are the same code here as there,
     * so a Vault card and a chat card feel like the same object.
     *
     * <p>Swiping is a shortcut, never the only route. Everything it does is also on the hold menu,
     * and both directions are exposed as accessibility actions by the wrapper itself.
     */
    private View itemCard(OrbitVaultItem item) {
        OrbitSwipeRow swipe = new OrbitSwipeRow(this, buildItemCard(item));
        final String id = item.id;
        swipe.configure(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, item.pinned,
                SWIPE_SUBJECT,
                // Identity, never position and never the visible title: the list rebuilds under a
                // gesture and two saved items are allowed to be called the same thing.
                (row, action) -> {
                    if (action == OrbitSwipeRow.ACTION_DELETE) deleteWithUndo(id);
                    else togglePin(id);
                });
        return swipe;
    }

    private View buildItemCard(OrbitVaultItem item) {
        LinearLayout card = card();
        card.setOrientation(LinearLayout.VERTICAL);

        if (item.isImage()) {
            Bitmap thumbnail = OrbitVaultMedia.load(item.mediaPath);
            if (thumbnail != null) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(thumbnail);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setAdjustViewBounds(false);
                image.setBackground(UiKit.rounded(UiKit.SURFACE_2, 14, this));
                image.setClipToOutline(true);
                image.setContentDescription("Saved image: " + item.displayTitle());
                LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 140));
                imageLp.setMargins(0, 0, 0, UiKit.dp(this, 10));
                card.addView(image, imageLp);
            } else {
                TextView missing = UiKit.text(this, "This image is no longer on this device",
                        12, UiKit.MUTED, false);
                missing.setPadding(0, 0, 0, UiKit.dp(this, 8));
                card.addView(missing);
            }
        }

        // The title, and beside it the smallest possible mark that this is one of the items the
        // user asked to keep near the top. The word "Pinned" is in the spoken description and in
        // the metadata line below, so nothing here depends on seeing a small accent glyph.
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.TOP);
        titleRow.addView(UiKit.text(this, item.displayTitle(), 15, UiKit.TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (item.pinned) {
            ImageView mark = new ImageView(this);
            mark.setImageResource(R.drawable.ic_pin);
            mark.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                    UiKit.dp(this, 15), UiKit.dp(this, 15));
            markLp.setMargins(UiKit.dp(this, 8), UiKit.dp(this, 2), 0, 0);
            titleRow.addView(mark, markLp);
        }
        card.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String preview = item.isLink() ? item.body : item.preview();
        if (!preview.isEmpty()) {
            TextView body = UiKit.text(this, preview, 13, UiKit.MUTED, false);
            body.setMaxLines(item.isImage() ? 2 : 3);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setPadding(0, UiKit.dp(this, 5), 0, 0);
            card.addView(body);
        }

        // The user's own words, on the card, because they are usually the fastest way to recognise
        // something in a list. Marked as theirs rather than run together with the saved content,
        // which is the same distinction the item screen makes.
        if (item.hasNote()) {
            TextView note = UiKit.text(this, "Your note: " + item.note, 12, UiKit.MUTED, false);
            note.setMaxLines(2);
            note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            note.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(note);
        }

        // The kind of item is written out, never signalled by colour alone, so it reads the same
        // for someone who cannot tell the accent from the muted text.
        StringBuilder meta = new StringBuilder();
        if (item.pinned) meta.append(PINNED_HEADING).append(" · ");
        meta.append(item.typeLabel());
        if (item.isLink() && !item.hostLabel().isEmpty()) meta.append(" · ").append(item.hostLabel());
        if (!item.source.isEmpty() && !item.source.equals(item.typeLabel())) {
            meta.append(" · ").append(item.source);
        }
        meta.append(" · ").append(item.savedLabel());
        TextView metaText = UiKit.text(this, meta.toString(), 11, UiKit.MUTED, false);
        metaText.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(metaText);

        card.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), UiKit.accent(this), 20, this));
        card.setContentDescription((item.pinned ? PINNED_HEADING + " " : "")
                + item.typeLabel() + ": " + item.displayTitle() + ". " + item.savedLabel());
        card.setOnClickListener(v -> open(item));
        card.setOnLongClickListener(v -> {
            UiKit.haptic(v, android.view.HapticFeedbackConstants.LONG_PRESS);
            showItemMenu(v, item);
            return true;
        });
        UiKit.pressScale(card);
        return card;
    }

    private void open(OrbitVaultItem item) {
        startActivity(new Intent(this, OrbitVaultItemActivity.class)
                .putExtra(OrbitVaultItemActivity.EXTRA_ITEM_ID, item.id));
        UiKit.applyPageTransition(this);
    }

    /**
     * What can be done to one card without opening it.
     *
     * <p>Pinning lives here, and deliberately not as a control on every card. A pin is used rarely
     * and on a handful of items; a permanent button for it on forty cards would cost the list its
     * scannability to serve the least frequent thing anybody does with a saved item.
     */
    private void showItemMenu(View anchor, OrbitVaultItem item) {
        String pin = item.pinned ? ACTION_UNPIN : ACTION_PIN;
        String[] labels = {"Open", pin, "Copy", "Delete"};
        int[] icons = {R.drawable.ic_document, R.drawable.ic_pin, R.drawable.ic_copy,
                R.drawable.ic_delete};
        UiKit.showOrbitActionMenu(this, anchor, labels, icons, (index, label) -> {
            if ("Open".equals(label)) open(item);
            else if (pin.equals(label)) togglePin(item.id);
            else if ("Copy".equals(label)) copy(item);
            else if ("Delete".equals(label)) confirmDelete(item);
        });
    }

    /**
     * Pins or unpins one item, and puts it where it now belongs.
     *
     * <p>Nothing else moves. The item keeps its content, its note, its picture and both its
     * timestamps; only which section it is drawn in changes, and it stays subject to whatever
     * filter and search are currently in force - so pinning a link while looking at documents does
     * not make it appear.
     *
     * <p>Rebuilding the list rather than moving the card is also what clears the drag: a card that
     * changed section while still translated would arrive in its new group holding the offset the
     * finger left it at. The result is announced rather than the movement, so nothing is said while
     * the finger is still on the screen.
     */
    private void togglePin(String id) {
        OrbitVaultItem existing = OrbitVaultStore.get(this, id);
        if (existing == null) return;
        boolean wanted = !existing.pinned;
        if (!OrbitVaultStore.setPinned(this, id, wanted)) {
            Toast.makeText(this, "Orbit could not update that item", Toast.LENGTH_SHORT).show();
            return;
        }
        OrbitSwipeRow.resetActive();
        refresh();
        if (list != null) {
            UiKit.haptic(list, android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            list.announceForAccessibility(wanted ? "Item pinned" : "Item unpinned");
        }
    }

    /**
     * Removes an item from the list and gives the user a moment to take it back.
     *
     * <p>Nothing is destroyed here, and that is the whole safety argument. The item is held aside
     * by id and stays exactly where it is in storage, so its picture is never orphaned, never
     * copied, and never has to be re-adopted; Undo is Orbit forgetting it was asked rather than a
     * restore that could quietly drop a field somebody added later. The deletion is carried out
     * when the window ends, when another item is deleted, or when the Vault leaves the foreground,
     * so it is never left indefinitely pending and never depends on this process still being alive.
     */
    private void deleteWithUndo(String id) {
        if (id == null || id.trim().isEmpty()) return;
        // A second delete while the first is still undoable commits the first rather than
        // discarding it, so the offer always belongs to the newest action.
        commitPendingDeletion();
        pendingDeletionId = id;
        OrbitSwipeRow.resetActive();
        refresh();
        showUndoBar();
    }

    private void showUndoBar() {
        if (undoBar == null) return;
        undoBar.setVisibility(View.VISIBLE);
        undoBar.setAlpha(1f);
        undoBar.setTranslationY(0f);
        undoBar.announceForAccessibility(UNDO_MESSAGE + ". Undo is available.");
        undoTimer.removeCallbacks(undoTimeout);
        undoTimer.postDelayed(undoTimeout, UNDO_WINDOW_MS);
        applyUndoRoom(true);
        if (!UiKit.animationsEnabled()) return;
        undoBar.animate().cancel();
        undoBar.setAlpha(0f);
        undoBar.setTranslationY(UiKit.dp(this, 14));
        undoBar.animate().alpha(1f).translationY(0f)
                .setDuration(170L)
                .setInterpolator(UiKit.motionEasing())
                .start();
    }

    private void hideUndoBar() {
        if (undoBar == null) return;
        undoTimer.removeCallbacks(undoTimeout);
        applyUndoRoom(false);
        if (undoBar.getVisibility() != View.VISIBLE) {
            undoBar.setVisibility(View.GONE);
            return;
        }
        final LinearLayout bar = undoBar;
        bar.animate().cancel();
        if (!UiKit.animationsEnabled()) {
            bar.setVisibility(View.GONE);
            return;
        }
        bar.animate().alpha(0f).translationY(UiKit.dp(this, 6))
                .setDuration(UiKit.MOTION_FAST)
                .setInterpolator(UiKit.motionEasing())
                .withEndAction(() -> {
                    bar.setVisibility(View.GONE);
                    bar.setAlpha(1f);
                    bar.setTranslationY(0f);
                })
                .start();
    }

    /**
     * Lets the last card still be scrolled clear of the floating bar, and takes the room back after.
     *
     * <p>Padding at the bottom of the scrolled content, not height taken from the viewport, so the
     * list is never resized and no card is ever clipped against a new bottom edge.
     */
    private void applyUndoRoom(boolean room) {
        if (list == null || listScroller == null) return;
        int wanted = UiKit.dp(this, 36) + (room ? UiKit.dp(this, 76) : 0);
        if (list.getPaddingBottom() == wanted) return;
        int scrollY = listScroller.getScrollY();
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), wanted);
        if (!room) listScroller.post(() -> listScroller.scrollTo(0, scrollY));
    }

    private void undoPendingDeletion() {
        if (pendingDeletionId == null) {
            hideUndoBar();
            return;
        }
        // Nothing to restore, because nothing was removed. The item comes back complete because it
        // never stopped existing: its id, its note, its pin, both timestamps, its document metadata
        // and the picture it owns are all exactly as they were.
        pendingDeletionId = null;
        hideUndoBar();
        refresh();
        if (list != null) {
            UiKit.haptic(list, android.view.HapticFeedbackConstants.CONTEXT_CLICK);
            list.announceForAccessibility("Item restored");
        }
    }

    /** Carries out a deletion the user did not take back. Uses the ordinary delete path. */
    private void commitPendingDeletion() {
        String id = pendingDeletionId;
        pendingDeletionId = null;
        hideUndoBar();
        if (id == null) return;
        // The store removes the row and then the picture it owned, and only when no remaining item
        // still refers to that file. Deferring the call cannot reach anything the ordinary path
        // would not have reached.
        OrbitVaultStore.delete(this, id);
    }

    private void copy(OrbitVaultItem item) {
        String text = item.isImage() ? item.displayTitle() : item.body;
        MessageActions.copy(this, "Orbit Vault", text,
                () -> Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show());
    }

    private void confirmDelete(OrbitVaultItem item) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(OrbitVaultItemActivity.DELETE_TITLE)
                .setMessage(OrbitVaultItemActivity.DELETE_MESSAGE)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    OrbitVaultStore.delete(this, item.id);
                    refresh();
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- sorting ---------------------------------------------------------------------------------

    private void showSortMenu(View anchor) {
        OrbitVaultStore.Sort[] options = OrbitVaultStore.Sort.values();
        String[] labels = new String[options.length];
        int selected = 0;
        OrbitVaultStore.Sort current = Prefs.vaultSort(this);
        for (int i = 0; i < options.length; i++) {
            labels[i] = options[i].label;
            if (options[i] == current) selected = i;
        }
        UiKit.showOrbitMenu(this, anchor, labels, selected, (index, label) -> {
            if (index < 0 || index >= options.length) return;
            Prefs.setVaultSort(this, options[index]);
            refresh();
        });
    }

    // ---- Quick Capture ---------------------------------------------------------------------------

    private void showCaptureMenu(View anchor) {
        String[] labels = {CAPTURE_WRITE, CAPTURE_PASTE, CAPTURE_IMAGE};
        int[] icons = {R.drawable.ic_edit, R.drawable.ic_copy, R.drawable.ic_image};
        UiKit.showOrbitActionMenu(this, anchor, labels, icons, (index, label) -> {
            if (CAPTURE_WRITE.equals(label)) writeText();
            else if (CAPTURE_PASTE.equals(label)) pasteClipboard();
            else if (CAPTURE_IMAGE.equals(label)) addImage();
        });
    }

    private void writeText() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20), UiKit.dp(this, 14));
        form.setBackgroundColor(UiKit.SURFACE);

        EditText title = field("Title (optional)", false);
        form.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText body = field("What do you want to keep?", true);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        form.addView(body, bodyLp);

        // The moment somebody is most likely to know why they are keeping something is while they
        // are keeping it. Optional, one line high, and never in the way of a fast save.
        EditText userNote = field(NOTE_FIELD_HINT, false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        form.addView(userNote, noteLp);

        TextView note = UiKit.text(this,
                "Saved on this device only. Orbit does not send it anywhere.", 12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 12), 0, 0);
        form.addView(note);

        TextView customTitle = UiKit.text(this, "Save to Vault", 20, UiKit.TEXT, true);
        customTitle.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 18), UiKit.dp(this, 20),
                UiKit.dp(this, 8));
        customTitle.setBackgroundColor(UiKit.SURFACE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save == null) return;
            save.setOnClickListener(v -> {
                String text = body.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(this, "Write something to save", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (OrbitVaultStore.saveText(this, title.getText().toString(), text,
                        OrbitVaultSource.QUICK_CAPTURE,
                        userNote.getText().toString()) == null) {
                    Toast.makeText(this, "Orbit could not save that", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                saved();
            });
        });
        dialog.show();
    }

    /**
     * Reads the clipboard once, because the user just asked for it.
     *
     * <p>Orbit never watches the clipboard. There is no listener, no background check, and no
     * automatic capture: this is the only line in the Vault that reads it at all, it runs only from
     * this tap, and what it finds is shown as a saved item rather than sent anywhere.
     */
    private void pasteClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = manager == null ? null : manager.getPrimaryClip();
        CharSequence value = clip == null || clip.getItemCount() == 0
                ? null : clip.getItemAt(0).coerceToText(this);
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Your clipboard has no text to save", Toast.LENGTH_SHORT).show();
            return;
        }
        confirmClipboard(text);
    }

    /**
     * Shows what is about to be saved, and offers a title and a note before it is.
     *
     * <p>Beta 1 saved the clipboard the instant it was tapped, which was fast and slightly blind:
     * the user found out what they had kept by opening it afterwards. Two optional fields and a
     * preview is not a wizard - it is one dialog with one button, and the content is already there
     * - and it turns a paste into something the user can see and label while they still remember
     * why they copied it.
     */
    private void confirmClipboard(String text) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20), UiKit.dp(this, 14));
        form.setBackgroundColor(UiKit.SURFACE);

        TextView preview = UiKit.text(this, text, 13, UiKit.MUTED, false);
        preview.setMaxLines(4);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 10), UiKit.dp(this, 12),
                UiKit.dp(this, 10));
        preview.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 34), 14, this));
        form.addView(preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText title = field("Title (optional)", false);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        form.addView(title, titleLp);

        EditText userNote = field(NOTE_FIELD_HINT, false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
        form.addView(userNote, noteLp);

        TextView where = UiKit.text(this,
                "Saved on this device only. Orbit does not send it anywhere.", 12, UiKit.MUTED, false);
        where.setPadding(0, UiKit.dp(this, 12), 0, 0);
        form.addView(where);

        TextView customTitle = UiKit.text(this, "Save from clipboard", 20, UiKit.TEXT, true);
        customTitle.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 18), UiKit.dp(this, 20),
                UiKit.dp(this, 8));
        customTitle.setBackgroundColor(UiKit.SURFACE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save == null) return;
            save.setOnClickListener(v -> {
                if (OrbitVaultStore.saveText(this, title.getText().toString(), text,
                        OrbitVaultSource.CLIPBOARD,
                        userNote.getText().toString()) == null) {
                    Toast.makeText(this, "Orbit could not save that", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                saved();
            });
        });
        dialog.show();
    }

    private void addImage() {
        try {
            // The user's own Gallery choice, through the picker preference Orbit already owns, so
            // Vault opens the same app the composer does. No storage permission is involved.
            startActivityForResult(GalleryAppPreference.createIntent(this, 1), REQ_PICK_IMAGE);
        } catch (Exception ignored) {
            Toast.makeText(this, "No compatible gallery picker is available",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK) return;
        List<Uri> picked = AttachmentUriCollector.fromPickerResult(data);
        if (picked.isEmpty()) {
            Toast.makeText(this, "Orbit could not read that image", Toast.LENGTH_SHORT).show();
            return;
        }
        saveImageFrom(picked.get(0));
    }

    /**
     * Turns a picked image into a Vault item.
     *
     * <p>Decoded through the reader Orbit already uses for attachments, which bounds the size
     * before a single pixel is allocated, and copied into Orbit's own storage so the item survives
     * the grant ending and the original being deleted.
     */
    private void saveImageFrom(Uri uri) {
        Bitmap image;
        try {
            image = AttachmentLoader.decodeImage(this, uri, OrbitVaultMedia.MAX_PIXELS);
        } catch (Exception ignored) {
            image = null;
        }
        if (image == null) {
            Toast.makeText(this, "Orbit could not read that image", Toast.LENGTH_SHORT).show();
            return;
        }
        OrbitVaultItem item = OrbitVaultStore.saveImage(this, image, "", OrbitVaultSource.PHOTO);
        image.recycle();
        if (item == null) {
            Toast.makeText(this, "Orbit could not save that image", Toast.LENGTH_SHORT).show();
            return;
        }
        saved();
    }

    private void saved() {
        Toast.makeText(this, "Saved to Vault", Toast.LENGTH_SHORT).show();
        if (searchInput != null && searchInput.getText().length() > 0) searchInput.setText("");
        refresh();
    }

    // ---- shared furniture -------------------------------------------------------------------------

    /**
     * One capture field, on Orbit's own input surface.
     *
     * <p>The same surface the item screen's note editor uses, for the same reason: a multi-line
     * field with a platform underline puts a bright rule a long way below the words it belongs to,
     * and a person reasonably reads that as a meter. Every field in the Vault's dialogs is a box.
     */
    private EditText field(String hint, boolean multiline) {
        return UiKit.input(this, hint, multiline);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 20, this));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        return lp;
    }

    private ImageButton iconButton(int res, String description) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        b.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        b.setContentDescription(description);
        b.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11));
        UiKit.pressScale(b);
        return b;
    }
}

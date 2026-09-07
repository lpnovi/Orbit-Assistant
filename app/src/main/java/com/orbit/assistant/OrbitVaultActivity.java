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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Orbit Vault: the things the user deliberately kept, and the fastest way to add another.
 *
 * <p>Not a notes app and not a second chat list. The loop this screen has to make excellent is
 * capture, save, find, reopen - so it is one column of plain cards, one search field, and one
 * capture control, and it deliberately has no folders, tags, filters, or dashboard.
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

    /** The optional note field, in the same words wherever something is being saved. */
    static final String NOTE_FIELD_HINT = "Note (optional)";

    /** What the Vault says about itself when the user has switched it off in Settings. */
    static final String OFF_TITLE = "Orbit Vault is turned off";
    static final String OFF_BODY =
            "Nothing has been deleted. Turn Orbit Vault back on in Settings to save new items "
                    + "and to see everything you already saved.";

    private static final int REQ_PICK_IMAGE = 8401;

    private LinearLayout list;
    private TextView subtitle;
    private EditText searchInput;
    private Button capture;
    private boolean quickCapturePending;
    private String appearanceSignature = "";

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
        UiPresence.leave(this);
        super.onPause();
    }

    // ---- the page --------------------------------------------------------------------------------

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
        int side = UiKit.dp(this, 18);
        root.setPadding(side, UiKit.dp(this, 10), side, 0);

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
        searchLp.setMargins(0, 0, 0, UiKit.dp(this, 10));
        root.addView(searchInput, searchLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, UiKit.dp(this, 2), 0, UiKit.dp(this, 36));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
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
        int total = OrbitVaultStore.count(this);
        String query = searchInput == null ? "" : searchInput.getText().toString().trim();
        List<OrbitVaultItem> shown = query.isEmpty()
                ? OrbitVaultStore.list(this, sort)
                : OrbitVaultStore.search(this, query, sort);

        String counted = total + (total == 1 ? " saved item" : " saved items");
        subtitle.setText(total == 0 ? "Nothing saved yet" : counted + " · " + sort.label);

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
            empty.addView(UiKit.text(this, "No matching items", 15, UiKit.TEXT, true));
            TextView hint = UiKit.text(this,
                    "Search looks at titles, text, links, your own notes and where an item "
                            + "came from.",
                    12, UiKit.MUTED, false);
            hint.setPadding(0, UiKit.dp(this, 5), 0, 0);
            empty.addView(hint);
            list.addView(empty, cardLp());
            return;
        }

        int columns = columns();
        LinearLayout row = null;
        for (int i = 0; i < shown.size(); i++) {
            if (columns == 1) {
                list.addView(itemCard(shown.get(i)), cardLp());
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
            row.addView(itemCard(shown.get(i)), cellLp);
        }
        // A part-filled last row keeps its cards at column width instead of stretching one of them
        // across the whole tablet.
        if (columns > 1 && row != null) {
            int missing = (columns - (shown.size() % columns)) % columns;
            for (int i = 0; i < missing; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(
                        0, 1, 1));
            }
        }
    }

    // ---- one saved thing --------------------------------------------------------------------------

    private View itemCard(OrbitVaultItem item) {
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

        card.addView(UiKit.text(this, item.displayTitle(), 15, UiKit.TEXT, true));

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
        StringBuilder meta = new StringBuilder(item.typeLabel());
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
        card.setContentDescription(item.typeLabel() + ": " + item.displayTitle()
                + ". " + item.savedLabel());
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

    private void showItemMenu(View anchor, OrbitVaultItem item) {
        String[] labels = {"Open", "Copy", "Delete"};
        int[] icons = {R.drawable.ic_document, R.drawable.ic_copy, R.drawable.ic_delete};
        UiKit.showOrbitActionMenu(this, anchor, labels, icons, (index, label) -> {
            if ("Open".equals(label)) open(item);
            else if ("Copy".equals(label)) copy(item);
            else if ("Delete".equals(label)) confirmDelete(item);
        });
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

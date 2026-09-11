package com.orbit.assistant;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Choosing saved items to attach to the message being written.
 *
 * <p>A picker rather than a second Vault. It searches and it lists, and a tap marks an item for
 * whichever composer opened it; there is no editing, no capture, no sorting and no deletion here,
 * because the user is in the middle of writing a message and every one of those would take them
 * somewhere else.
 *
 * <p>As many items as the message can still hold, and not one more. The composer's attachment
 * limit is Orbit's one number and the Vault has no business bending it, so the picker is told
 * how much room is left and stops accepting at exactly that point. What it hands back is a list
 * of ids; the composer appends them the way it appends four photos from Gallery, through the
 * same {@link ComposerAttachments} collection, the same tray and the same Send.
 *
 * <p>Selecting is not attaching. A tap marks an item and a second tap unmarks it; nothing
 * reaches the composer until Attach, and closing this screen without pressing it leaves the
 * message exactly as it was.
 *
 * <p>Opening this screen sends nothing. It reads the local store, draws it, and returns ids; a
 * saved item does not reach a provider until the user presses Send on a message they wrote
 * themselves.
 */
public final class OrbitVaultPickerActivity extends Activity {

    /** The ids of the chosen items, in the order they were chosen. */
    public static final String EXTRA_PICKED_IDS = "orbit_vault_picked_ids";

    /**
     * How many more items the message that opened this can still take.
     *
     * <p>Passed in rather than worked out here, because the composer owns the count and already
     * holds whatever else the user has attached. Absent or nonsensical means the whole per-turn
     * limit, which is what a caller that has staged nothing would have anyway.
     */
    public static final String EXTRA_REMAINING = "orbit_vault_remaining";

    static final String EMPTY_TITLE = "Nothing saved yet";
    static final String EMPTY_BODY =
            "Items you save to Orbit Vault can be attached to a message from here.";

    private LinearLayout list;
    private EditText searchInput;
    private Button attachButton;
    private TextView countLine;
    private String appearanceSignature = "";
    /** The items marked so far, in the order the user marked them. */
    private final List<String> picked = new ArrayList<>();
    private int remaining = ComposerAttachments.MAX_PER_TURN;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        int room = getIntent() == null ? 0
                : getIntent().getIntExtra(EXTRA_REMAINING, ComposerAttachments.MAX_PER_TURN);
        remaining = room <= 0 ? ComposerAttachments.MAX_PER_TURN
                : Math.min(room, ComposerAttachments.MAX_PER_TURN);
        if (savedInstanceState != null) {
            String[] restored = savedInstanceState.getStringArray(EXTRA_PICKED_IDS);
            if (restored != null) Collections.addAll(picked, restored);
        }
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View root = build();
        setContentView(root);
        UiKit.applyActivityInsets(this, root, true);
        navigation = OrbitPredictiveBack.install(this);
        refresh();
        refreshSelectionState();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        refresh();
        refreshSelectionState();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putStringArray(EXTRA_PICKED_IDS, picked.toArray(new String[0]));
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        OrbitBackground.applyPage(root);
        int side = UiKit.dp(this, 18);
        root.setPadding(side, UiKit.dp(this, 10), side, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        back.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 18, this));
        back.setContentDescription("Back to the message");
        back.setPadding(UiKit.dp(this, 11), UiKit.dp(this, 11), UiKit.dp(this, 11),
                UiKit.dp(this, 11));
        UiKit.pressScale(back);
        back.setOnClickListener(v -> navigation.performBack());
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, "Attach from Vault", 22, UiKit.TEXT, true));
        countLine = UiKit.text(this, "", 12, UiKit.MUTED, false);
        titles.addView(countLine);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this, 14), 0, 0, 0);
        top.addView(titles, titleLp);
        root.addView(top);

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
        searchLp.setMargins(0, UiKit.dp(this, 14), 0, UiKit.dp(this, 10));
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

        // The one control that ends this screen. It is disabled rather than hidden while nothing
        // is marked, so the screen says up front how a selection is finished.
        attachButton = new Button(this);
        attachButton.setAllCaps(false);
        attachButton.setTextSize(15);
        attachButton.setTextColor(UiKit.onAccent(this));
        attachButton.setMinHeight(0);
        attachButton.setMinimumHeight(0);
        attachButton.setStateListAnimator(null);
        attachButton.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 18, this));
        attachButton.setOnClickListener(v -> attachPicked());
        UiKit.pressScale(attachButton);
        LinearLayout.LayoutParams attachLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 50));
        attachLp.setMargins(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 12));
        root.addView(attachButton, attachLp);
        return root;
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();

        String query = searchInput == null ? "" : searchInput.getText().toString().trim();
        List<OrbitVaultItem> shown = query.isEmpty()
                ? OrbitVaultStore.list(this, Prefs.vaultSort(this))
                : OrbitVaultStore.search(this, query, Prefs.vaultSort(this));

        if (shown.isEmpty()) {
            LinearLayout empty = card();
            boolean searching = !query.isEmpty();
            empty.addView(UiKit.text(this, searching ? "No matching items" : EMPTY_TITLE,
                    15, UiKit.TEXT, true));
            TextView body = UiKit.text(this, searching
                            ? "Search looks at titles, text, links and your own notes."
                            : EMPTY_BODY,
                    12, UiKit.MUTED, false);
            body.setPadding(0, UiKit.dp(this, 6), 0, 0);
            empty.addView(body);
            list.addView(empty, rowLp());
            return;
        }
        for (OrbitVaultItem item : shown) list.addView(row(item), rowLp());
    }

    /** The header count and the Attach control, kept in step with what is marked. */
    private void refreshSelectionState() {
        int count = picked.size();
        if (countLine != null) {
            countLine.setText(count == 0
                    ? (remaining == 1 ? "Choose one saved item"
                            : "Choose up to " + remaining + " saved items")
                    : count + (count == 1 ? " item selected" : " items selected"));
        }
        if (attachButton == null) return;
        attachButton.setText(count <= 1 ? "Attach" : "Attach " + count);
        attachButton.setEnabled(count > 0);
        attachButton.setAlpha(count > 0 ? 1f : 0.5f);
        attachButton.setContentDescription(count == 0
                ? "Attach, nothing selected yet"
                : "Attach " + count + (count == 1 ? " saved item" : " saved items"));
    }

    /**
     * Marks or unmarks one item.
     *
     * <p>Refusing past the message's remaining room happens here rather than at the composer,
     * because being told what cannot be attached while there is still time to choose differently
     * is better than being told afterwards.
     */
    private void toggle(OrbitVaultItem item) {
        if (picked.remove(item.id)) {
            refresh();
            refreshSelectionState();
            return;
        }
        if (picked.size() >= remaining) {
            Toast.makeText(this, remaining == 1
                            ? "This message has room for one more attachment"
                            : "This message has room for " + remaining + " more attachments",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        picked.add(item.id);
        refresh();
        refreshSelectionState();
    }

    /**
     * Hands the marked ids back and leaves.
     *
     * <p>Ids rather than the items, because the composer can read the store itself and decoded
     * pictures have no business crossing a Binder transaction. Nothing is attached here and
     * nothing is sent: the composer stages them, and the user decides what to ask.
     */
    private void attachPicked() {
        if (picked.isEmpty()) return;
        setResult(RESULT_OK, new Intent()
                .putExtra(EXTRA_PICKED_IDS, picked.toArray(new String[0])));
        finish();
        UiKit.applyPageTransition(this);
    }

    /** One compact row: a thumbnail where there is one, the title, and what kind of thing it is. */
    private View row(OrbitVaultItem item) {
        LinearLayout row = card();
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        if (item.isImage()) {
            Bitmap thumbnail = OrbitVaultMedia.load(item.mediaPath);
            if (thumbnail != null) {
                ImageView image = new ImageView(this);
                image.setImageBitmap(thumbnail);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setBackground(UiKit.rounded(UiKit.SURFACE_2, 10, this));
                image.setClipToOutline(true);
                image.setContentDescription(null);
                LinearLayout.LayoutParams imageLp = new LinearLayout.LayoutParams(
                        UiKit.dp(this, 44), UiKit.dp(this, 44));
                imageLp.setMargins(0, 0, UiKit.dp(this, 12), 0);
                row.addView(image, imageLp);
            }
        }

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView title = UiKit.text(this, item.displayTitle(), 14, UiKit.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);

        StringBuilder meta = new StringBuilder(item.typeLabel());
        if (item.isLink() && !item.hostLabel().isEmpty()) meta.append(" · ").append(item.hostLabel());
        if (item.hasNote()) meta.append(" · Has a note");
        TextView metaText = UiKit.text(this, meta.toString(), 11, UiKit.MUTED, false);
        metaText.setMaxLines(1);
        metaText.setEllipsize(TextUtils.TruncateAt.END);
        metaText.setPadding(0, UiKit.dp(this, 3), 0, 0);
        words.addView(metaText);
        row.addView(words, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView mark = UiKit.text(this, picked.contains(item.id) ? "\u2713" : "", 16,
                UiKit.accent(this), true);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                UiKit.dp(this, 22), ViewGroup.LayoutParams.WRAP_CONTENT);
        markLp.setMargins(UiKit.dp(this, 8), 0, 0, 0);
        row.addView(mark, markLp);

        boolean selected = picked.contains(item.id);
        // Selection is stated as well as drawn: a brighter outline is exactly the kind of state
        // that disappears for anyone who cannot rely on colour to carry it.
        row.setBackground(UiKit.rippleOutlined(
                selected ? UiKit.blend(UiKit.accent(this), UiKit.SURFACE, 0.16f) : UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), selected ? 190 : 34),
                UiKit.accent(this), 18, this));
        row.setContentDescription(item.typeLabel() + ": " + item.displayTitle()
                + (selected ? ", selected" : ", not selected"));
        row.setOnClickListener(v -> toggle(item));
        UiKit.pressScale(row);
        return row;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14),
                UiKit.dp(this, 12));
        c.setBackground(UiKit.outlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), 18, this));
        return c;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, UiKit.dp(this, 8));
        return lp;
    }
}

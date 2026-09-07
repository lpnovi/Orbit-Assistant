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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Choosing one saved item to attach to the message being written.
 *
 * <p>A picker rather than a second Vault. It searches and it lists, and a tap returns one id to
 * whichever composer opened it; there is no editing, no capture, no sorting and no deletion here,
 * because the user is in the middle of writing a message and every one of those would take them
 * somewhere else.
 *
 * <p>One selection, deliberately. The composer's attachment limit is Orbit's one number and the
 * Vault has no business bending it, so this hands back exactly one id and the composer appends it
 * the way it appends a photo - refusing it, with the same message, when the turn is already full.
 *
 * <p>Opening this screen sends nothing. It reads the local store, draws it, and returns an id;
 * the saved item does not reach a provider until the user presses Send on a message they wrote
 * themselves.
 */
public final class OrbitVaultPickerActivity extends Activity {

    /** The id of the chosen item, returned to the composer that asked. */
    public static final String EXTRA_PICKED_ID = "orbit_vault_picked_id";

    static final String EMPTY_TITLE = "Nothing saved yet";
    static final String EMPTY_BODY =
            "Items you save to Orbit Vault can be attached to a message from here.";

    private LinearLayout list;
    private EditText searchInput;
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
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        refresh();
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.BG);
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
        titles.addView(UiKit.text(this, "Choose one saved item", 12, UiKit.MUTED, false));
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

        row.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 34), UiKit.accent(this), 18, this));
        row.setContentDescription("Attach " + item.typeLabel() + ": " + item.displayTitle());
        row.setOnClickListener(v -> pick(item));
        UiKit.pressScale(row);
        return row;
    }

    /**
     * Hands one id back and leaves.
     *
     * <p>An id rather than the item, because the composer can read the store itself and a decoded
     * picture has no business crossing a Binder transaction. Nothing is attached here and nothing
     * is sent: the composer decides whether it still has room, and the user decides what to ask.
     */
    private void pick(OrbitVaultItem item) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_PICKED_ID, item.id));
        finish();
        UiKit.applyPageTransition(this);
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

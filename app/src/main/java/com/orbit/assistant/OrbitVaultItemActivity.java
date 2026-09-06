package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
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

/**
 * One saved item, opened.
 *
 * <p>Deliberately a reader with a few actions rather than a document editor. What the user gets is
 * the thing they saved at a comfortable size, when they saved it, and the handful of things worth
 * doing to it: rename, edit if it is theirs to edit, copy, open a link, share plain text, delete.
 *
 * <p>Content here is inert. Text is text: it is never parsed for commands, never executed, never
 * allowed to start an Intent, and never able to touch Routines, providers, Settings, Memory or
 * Deck. A link opens only because the user pressed Open link, and only after Orbit has re-checked
 * that it is still an ordinary http or https address.
 */
public final class OrbitVaultItemActivity extends Activity {

    public static final String EXTRA_ITEM_ID = "orbit_vault_item_id";

    static final String DELETE_TITLE = "Delete from Vault?";
    static final String DELETE_MESSAGE = "This item will be removed from this device.";

    private String itemId = "";
    private String appearanceSignature = "";
    private LinearLayout page;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        appearanceSignature = UiKit.appearanceSignature(this);
        itemId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_ITEM_ID);
        if (itemId == null) itemId = "";
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.BG);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int side = UiKit.dp(this, 18);
        page.setPadding(side, UiKit.dp(this, 10), side, UiKit.dp(this, 40));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
        UiKit.applyActivityInsets(this, scroll, true);
        navigation = OrbitPredictiveBack.install(this);
        rebuild();
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        if (!UiKit.appearanceSignature(this).equals(appearanceSignature)) {
            recreate();
            return;
        }
        rebuild();
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }

    // ---- the page --------------------------------------------------------------------------------

    private void rebuild() {
        if (page == null) return;
        page.removeAllViews();

        OrbitVaultItem item = OrbitVaultStore.get(this, itemId);
        page.addView(header(item));

        if (item == null) {
            // Reached by opening an item that has since been deleted, including from another
            // Orbit screen. Saying so is better than an empty page or a crash.
            LinearLayout gone = card();
            gone.addView(UiKit.text(this, "This item is no longer in your Vault", 15,
                    UiKit.TEXT, true));
            page.addView(gone, cardLp());
            return;
        }

        if (item.isImage()) page.addView(imageCard(item), cardLp());
        if (item.isLink()) page.addView(linkCard(item), cardLp());
        else if (!item.body.isEmpty()) page.addView(bodyCard(item), cardLp());

        page.addView(detailsCard(item), cardLp());
        page.addView(actions(item));
    }

    private View header(OrbitVaultItem item) {
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        ImageButton back = iconButton(R.drawable.ic_back, "Back to Vault");
        back.setOnClickListener(v -> navigation.performBack());
        top.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 48), UiKit.dp(this, 48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(UiKit.text(this, item == null ? "Vault item" : item.displayTitle(),
                22, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, item == null ? "Vault" : item.typeLabel(),
                12, UiKit.MUTED, false));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleLp.setMargins(UiKit.dp(this, 14), 0, 0, UiKit.dp(this, 6));
        top.addView(titles, titleLp);
        return top;
    }

    private View imageCard(OrbitVaultItem item) {
        LinearLayout card = card();
        Bitmap picture = OrbitVaultMedia.load(item.mediaPath);
        if (picture == null) {
            card.addView(UiKit.text(this, "This image is no longer on this device", 14,
                    UiKit.TEXT, false));
            TextView why = UiKit.text(this,
                    "Its file was removed. The saved details below are still here.",
                    12, UiKit.MUTED, false);
            why.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(why);
            return card;
        }
        ImageView view = new ImageView(this);
        view.setImageBitmap(picture);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setContentDescription("Saved image: " + item.displayTitle());
        card.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View bodyCard(OrbitVaultItem item) {
        LinearLayout card = card();
        TextView body = UiKit.text(this, item.body, Prefs.chatTextSp(this, 15), UiKit.TEXT, false);
        body.setLineSpacing(0, UiKit.CHAT_LINE_SPACING);
        body.setTextIsSelectable(true);
        card.addView(body);
        return card;
    }

    private View linkCard(OrbitVaultItem item) {
        LinearLayout card = card();
        card.addView(UiKit.text(this, item.body, 14, UiKit.TEXT, false));
        String host = item.hostLabel();
        if (!host.isEmpty()) {
            TextView where = UiKit.text(this, host, 12, UiKit.MUTED, false);
            where.setPadding(0, UiKit.dp(this, 6), 0, 0);
            card.addView(where);
        }
        TextView note = UiKit.text(this,
                "Orbit has not opened or read this address. It opens only when you choose to.",
                12, UiKit.MUTED, false);
        note.setPadding(0, UiKit.dp(this, 8), 0, 0);
        card.addView(note);
        return card;
    }

    private View detailsCard(OrbitVaultItem item) {
        LinearLayout card = card();
        StringBuilder details = new StringBuilder(item.typeLabel());
        if (!item.source.isEmpty() && !item.source.equals(item.typeLabel())) {
            details.append(" · ").append(item.source);
        }
        card.addView(UiKit.text(this, details.toString(), 12, UiKit.MUTED, false));
        TextView saved = UiKit.text(this, item.savedLabel(), 12, UiKit.MUTED, false);
        saved.setPadding(0, UiKit.dp(this, 4), 0, 0);
        card.addView(saved);
        String edited = item.modifiedLabel();
        if (!edited.isEmpty()) {
            TextView modified = UiKit.text(this, edited, 12, UiKit.MUTED, false);
            modified.setPadding(0, UiKit.dp(this, 4), 0, 0);
            card.addView(modified);
        }
        return card;
    }

    // ---- what can be done to it -------------------------------------------------------------------

    private View actions(OrbitVaultItem item) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        column.addView(action(item.bodyIsEditable() ? "Edit" : "Rename", v -> edit(item)),
                actionLp(0));

        if (item.isLink()) {
            column.addView(action("Open link", v -> openLink(item)), actionLp(9));
        }
        if (!item.isImage()) {
            column.addView(action("Copy", v -> MessageActions.copy(this, "Orbit Vault", item.body,
                    () -> Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show())), actionLp(9));
            column.addView(action("Share", v -> shareText(item)), actionLp(9));
        }

        Button delete = action("Delete", v -> confirmDelete(item));
        delete.setTextColor(UiKit.DANGER);
        delete.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 90), UiKit.DANGER, 15, this));
        column.addView(delete, actionLp(18));
        return column;
    }

    private void edit(OrbitVaultItem item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20), UiKit.dp(this, 14));
        form.setBackgroundColor(UiKit.SURFACE);

        EditText title = new EditText(this);
        title.setHint("Title");
        title.setContentDescription("Title");
        title.setText(item.title);
        title.setSingleLine(true);
        title.setTextColor(UiKit.TEXT);
        title.setHintTextColor(UiKit.MUTED);
        title.setTextSize(14);
        title.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(this)));
        form.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText body;
        if (item.bodyIsEditable()) {
            body = new EditText(this);
            body.setHint("Text");
            body.setContentDescription("Text");
            body.setText(item.body);
            body.setSingleLine(false);
            body.setMinLines(4);
            body.setMaxLines(12);
            body.setGravity(Gravity.TOP | Gravity.START);
            body.setTextColor(UiKit.TEXT);
            body.setHintTextColor(UiKit.MUTED);
            body.setTextSize(14);
            body.setBackgroundTintList(ColorStateList.valueOf(UiKit.accent(this)));
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bodyLp.setMargins(0, UiKit.dp(this, 12), 0, 0);
            form.addView(body, bodyLp);
        } else {
            body = null;
            // Said plainly rather than hidden behind a disabled field: a saved answer, a link and a
            // picture are records, and the title is the part that belongs to the user.
            TextView why = UiKit.text(this, item.isOrbitReply()
                            ? "The answer itself stays exactly as Orbit wrote it."
                            : "Only the title can be changed for this kind of item.",
                    12, UiKit.MUTED, false);
            why.setPadding(0, UiKit.dp(this, 12), 0, 0);
            form.addView(why);
        }

        TextView customTitle = UiKit.text(this, item.bodyIsEditable() ? "Edit item" : "Rename item",
                20, UiKit.TEXT, true);
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
                boolean ok;
                if (body == null) {
                    ok = OrbitVaultStore.updateTitle(this, item.id, title.getText().toString());
                } else {
                    String text = body.getText().toString().trim();
                    if (text.isEmpty()) {
                        Toast.makeText(this, "Text cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ok = OrbitVaultStore.updateText(this, item.id, title.getText().toString(), text);
                }
                if (!ok) {
                    Toast.makeText(this, "Orbit could not save that change",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                rebuild();
            });
        });
        dialog.show();
    }

    /**
     * Opens a link, having checked it again first.
     *
     * <p>Re-validated rather than trusted, because a stored address is data that could have come
     * from a share, a paste, or a restored backup. Only an ordinary http or https address is handed
     * to Android, and only from this tap.
     */
    private void openLink(OrbitVaultItem item) {
        String url = OrbitVaultItem.singleLinkOrEmpty(item.body);
        if (url.isEmpty()) {
            Toast.makeText(this, "That is not a web address Orbit can open",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Hands the text to Android's own share sheet.
     *
     * <p>Text only, and deliberately. Sharing a stored picture would mean granting another app a
     * URI into Orbit's private files, and Orbit's FileProvider is configured to expose three
     * specific cache directories and nothing else. Widening that for a convenience is not a trade
     * worth making in Beta 1, so an image is kept and viewed rather than passed on.
     */
    private void shareText(OrbitVaultItem item) {
        String text = item.body.isEmpty() ? item.displayTitle() : item.body;
        if (text.trim().isEmpty()) return;
        try {
            Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(send, "Share saved item"));
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not share that item", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(OrbitVaultItem item) {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(DELETE_TITLE)
                .setMessage(DELETE_MESSAGE)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    OrbitVaultStore.delete(this, item.id);
                    finish();
                    UiKit.applyPageTransition(this);
                })
                .create();
        UiKit.styleOrbitDialog(dialog, this, true);
        dialog.show();
    }

    // ---- shared furniture -------------------------------------------------------------------------

    private Button action(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(UiKit.TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 90), UiKit.accent(this), 15, this));
        b.setOnClickListener(listener);
        UiKit.pressScale(b);
        return b;
    }

    private LinearLayout.LayoutParams actionLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48));
        lp.setMargins(0, UiKit.dp(this, topDp), 0, 0);
        return lp;
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
        lp.setMargins(0, UiKit.dp(this, 12), 0, 0);
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

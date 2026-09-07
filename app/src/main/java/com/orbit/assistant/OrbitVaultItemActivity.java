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

import java.util.ArrayList;
import java.util.List;

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

    /** The note section's two states, in the words the screen shows. */
    static final String NOTE_HEADING = "Your note";
    static final String NOTE_EMPTY = "Add a note";

    /** The actions this screen offers, as the labels a person reads. */
    static final String ACTION_ASK = "Ask Orbit";
    static final String ACTION_OPEN_LINK = "Open link";
    /** What a saved picture that remembers its page offers, and only such an item. */
    static final String ACTION_OPEN_SOURCE = "Open source";
    static final String ACTION_COPY = "Copy";
    static final String ACTION_SHARE = "Share";
    static final String ACTION_EDIT = "Edit";
    static final String ACTION_RENAME = "Rename";
    static final String ACTION_DELETE = "Delete";
    /** The utility-row toggle, in the same two words the Vault list uses. */
    static final String ACTION_PIN = "Pin";
    static final String ACTION_UNPIN = "Unpin";

    /**
     * How wide the action area is allowed to become.
     *
     * <p>A Galaxy S25 Ultra never reaches it and is laid out exactly as it would be without this.
     * A Tab S9 Plus does, and the rule exists for that case: three small utility controls spread
     * across a whole tablet stop reading as a group and start reading as a toolbar with enormous
     * gaps in it. The content above keeps the full width, because content genuinely uses it.
     */
    static final int ACTIONS_MAX_WIDTH_DP = 560;

    /**
     * The gap between the last content card and the first action.
     *
     * <p>Beta 2 gave the action area no top margin at all, so the primary row began exactly where
     * the details card ended. On a Galaxy S25 Ultra that read as Open link and Ask Orbit clipping
     * into the card above them: two rounded outlines a hairline apart look like one broken shape
     * rather than two controls. This is the same 14dp rhythm the content cards already keep between
     * themselves, so the actions become the next thing down the page rather than a fifth card
     * pressed against the fourth.
     */
    static final int ACTIONS_TOP_GAP_DP = 14;

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

        // The order is the point of this screen: what was saved, then what the user said about it,
        // then when it arrived, then what can be done with it. Beta 1 had the content and the
        // details right and then finished with a column of five identical full-width buttons,
        // which read as a Settings page rather than as something the user had kept.
        // A saved page shows the page first and its words underneath, which is the order somebody
        // reading a document experiences them in. It is deliberately not a second document viewer:
        // one page was saved, so one page is what this screen has to show.
        if (item.isImage() || (item.isDocumentPage() && !item.mediaPath.isEmpty())) {
            page.addView(mediaCard(item), cardLp());
        }
        if (item.isLink()) page.addView(linkCard(item), cardLp());
        else if (!item.body.isEmpty()) page.addView(bodyCard(item), cardLp());

        page.addView(noteCard(item), cardLp());
        page.addView(detailsCard(item), cardLp());
        page.addView(actions(item));
    }

    /**
     * The user's own note, under the saved content and visibly not part of it.
     *
     * <p>Drawn as its own quieter card with its own heading, because that separation is the whole
     * idea. A saved address is what a site published; "look at this later for the animation idea"
     * is what the user was thinking, and folding one into the other would lose which is which
     * here, in search, in a backup, and in what Orbit is told when they ask about it.
     */
    private View noteCard(OrbitVaultItem item) {
        LinearLayout card = card();
        card.addView(UiKit.text(this, NOTE_HEADING, 12, UiKit.MUTED, true));

        TextView value = UiKit.text(this,
                item.hasNote() ? item.note : NOTE_EMPTY,
                item.hasNote() ? Prefs.chatTextSp(this, 14) : 14,
                item.hasNote() ? UiKit.TEXT : UiKit.MUTED, false);
        value.setLineSpacing(0, UiKit.CHAT_LINE_SPACING);
        value.setPadding(0, UiKit.dp(this, 7), 0, 0);
        card.addView(value);

        card.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), item.hasNote() ? 34 : 22),
                UiKit.accent(this), 20, this));
        card.setContentDescription(item.hasNote()
                ? "Your note: " + item.note + ". Tap to edit."
                : "Add a note about why you saved this");
        card.setOnClickListener(v -> editNote(item));
        UiKit.pressScale(card);
        return card;
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
        // Nothing sits to the right of the title. Beta 4 put a 48dp Pin button there on the
        // argument that a pin is a statement about the item rather than something done with its
        // content, which is true and cost the title a sixth of the screen: a saved PDF page, whose
        // name is a filename and a page number, wrapped and crammed against a control used once in
        // its life. The title is the thing this screen is about, so it gets the width, and Pin
        // moved down to the utility row where the other one-tap actions already are.
        return top;
    }

    /**
     * Pins or unpins this item, and redraws the page.
     *
     * <p>The store carries every other field across untouched, including both timestamps, so this
     * screen shows the same saved date and the same edited line afterwards as it did before.
     */
    private void togglePin(OrbitVaultItem item) {
        boolean wanted = !item.pinned;
        if (!OrbitVaultStore.setPinned(this, item.id, wanted)) {
            Toast.makeText(this, "Orbit could not update that item", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, wanted ? "Pinned" : "Unpinned", Toast.LENGTH_SHORT).show();
        rebuild();
    }

    /**
     * The picture this item owns, whatever kind of item it is.
     *
     * <p>A saved photo and a saved document page draw the same card, because the user is looking at
     * the same thing: the image Orbit copied into its own storage when they saved it. A file that
     * has gone says so rather than leaving a blank; for a saved page the rest of the item - its
     * text, its document, its page number - is genuinely still there, which is what the second line
     * is for.
     */
    private View mediaCard(OrbitVaultItem item) {
        LinearLayout card = card();
        Bitmap picture = OrbitVaultMedia.load(item.mediaPath);
        boolean page = item.isDocumentPage();
        if (picture == null) {
            card.addView(UiKit.text(this, page
                            ? "This saved page image is no longer on this device"
                            : "This image is no longer on this device",
                    14, UiKit.TEXT, false));
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
        view.setContentDescription((page ? "Saved page: " : "Saved image: ") + item.displayTitle());
        card.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private View bodyCard(OrbitVaultItem item) {
        LinearLayout card = card();
        // A saved page's body is text Orbit extracted from a document rather than words the user
        // wrote, so it is labelled. Everything else is the thing itself and needs no heading.
        if (item.isDocumentPage()) {
            card.addView(UiKit.text(this, "Text on this page", 12, UiKit.MUTED, true));
        }
        TextView body = UiKit.text(this, item.body, Prefs.chatTextSp(this, 15), UiKit.TEXT, false);
        body.setLineSpacing(0, UiKit.CHAT_LINE_SPACING);
        body.setTextIsSelectable(true);
        if (item.isDocumentPage()) body.setPadding(0, UiKit.dp(this, 7), 0, 0);
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
        // The document's own name, once, in the details rather than glued into the source line.
        // A filename can be very long and the source line has to stay one readable phrase.
        if (item.isDocumentPage() && !item.documentName.isEmpty()) {
            TextView from = UiKit.text(this, "From " + item.documentName, 12, UiKit.MUTED, false);
            from.setPadding(0, UiKit.dp(this, 4), 0, 0);
            card.addView(from);
        }
        // The page behind a saved picture, as its host rather than its full address. A hostname is
        // what somebody recognises; a URL with a tracking query on the end is a wall of characters
        // that pushes the dates off the card. The whole address is still what Open source opens.
        String sourceHost = item.sourceHostLabel();
        if (!sourceHost.isEmpty()) {
            TextView from = UiKit.text(this, "From " + sourceHost, 12, UiKit.MUTED, false);
            from.setPadding(0, UiKit.dp(this, 4), 0, 0);
            card.addView(from);
        }
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

    /**
     * The action area, in three tiers rather than one stack.
     *
     * <p>The most relevant thing to do with this kind of item is filled with the accent and sits on
     * its own row; the everyday utilities share compact rows below it; deletion is separated from
     * both. Beta 1 gave Rename, Open link, Copy, Share and Delete exactly the same size and weight,
     * which meant the screen never said what a saved link is actually for.
     */
    private View actions(OrbitVaultItem item) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);

        LinearLayout primary = new LinearLayout(this);
        primary.setOrientation(LinearLayout.HORIZONTAL);
        if (item.isLink()) {
            // Open link keeps the priority it earned in Beta 1: it is what a saved address is for,
            // it is the action the device testing singled out as good, and it stays the one filled
            // control. Ask Orbit sits beside it, obvious without competing.
            primary.addView(filledAction(ACTION_OPEN_LINK, v -> openLink(item)), primaryCellLp(0));
            primary.addView(outlinedAction(ACTION_ASK, v -> askOrbit(item)), primaryCellLp(9));
        } else if (item.hasSourceUrl()) {
            // A saved picture that remembers its page. Ask Orbit stays the filled control, because
            // the picture is the thing the user kept; Open source sits beside it for the times they
            // want to go back and read where it came from.
            primary.addView(filledAction(ACTION_ASK, v -> askOrbit(item)), primaryCellLp(0));
            primary.addView(outlinedAction(ACTION_OPEN_SOURCE, v -> openSource(item)), primaryCellLp(9));
        } else {
            primary.addView(filledAction(ACTION_ASK, v -> askOrbit(item)), primaryCellLp(0));
        }
        column.addView(primary, actionRowLp(ACTIONS_TOP_GAP_DP));

        // The everyday utilities, gathered before they are laid out, because how many there are is
        // what decides the shape of the rows below.
        List<View> utilities = new ArrayList<>();
        utilities.add(compactAction(item.bodyIsEditable() ? ACTION_EDIT : ACTION_RENAME,
                R.drawable.ic_edit, v -> edit(item)));
        // An image has no words to copy or share. A saved page has the text Orbit extracted
        // from it, which is exactly the thing somebody wants out of a page they kept.
        if (!item.isImage() && !item.body.isEmpty()) {
            utilities.add(compactAction(ACTION_COPY, R.drawable.ic_copy,
                    v -> MessageActions.copy(this, "Orbit Vault", item.body,
                            () -> Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show())));
            utilities.add(compactAction(ACTION_SHARE, R.drawable.ic_share,
                    v -> shareText(item)));
        }
        // Pin arrives here from the header. It is still one tap and still without leaving the
        // page, and it now sits beside the other one-tap actions instead of eating the title.
        String pinLabel = item.pinned ? ACTION_UNPIN : ACTION_PIN;
        utilities.add(compactAction(pinLabel, R.drawable.ic_pin,
                pinLabel + " this saved item", v -> togglePin(item)));
        addUtilityRows(column, utilities);

        Button delete = outlinedAction(ACTION_DELETE, v -> confirmDelete(item));
        delete.setTextColor(UiKit.DANGER);
        delete.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.DANGER, 90), UiKit.DANGER, 15, this));
        LinearLayout deleteRow = new LinearLayout(this);
        deleteRow.setOrientation(LinearLayout.HORIZONTAL);
        deleteRow.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));
        // Real separation rather than a slightly larger gap, so deletion never sits in the rhythm
        // of the controls somebody uses every day.
        column.addView(deleteRow, actionRowLp(26));
        return column;
    }

    /**
     * Hands one saved item to a new conversation, and does not ask anything.
     *
     * <p>This is the whole of Ask Orbit on this side: build an Intent naming this item, open the
     * chat, and leave. No provider is chosen here, no request is built, no prompt is written, and
     * nothing is uploaded. The composer stages the item as an ordinary attachment and waits for
     * the user to type a question and press Send, which is the only moment anything is sent.
     */
    private void askOrbit(OrbitVaultItem item) {
        try {
            startActivity(new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, ConversationStore.newId())
                    .putExtra(ChatActivity.EXTRA_VAULT_ITEM_ID, item.id)
                    .putExtra(ChatActivity.EXTRA_FOCUS_COMPOSER, true));
            UiKit.applyPageTransition(this);
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not open a conversation", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Writes, changes, or clears the note.
     *
     * <p>Clearing needs no confirmation of its own. It removes one sentence the user wrote a moment
     * ago from a field they are looking at, and it destroys nothing that was saved - which is
     * exactly the distinction Orbit's destructive confirmations exist to protect.
     */
    private void editNote(OrbitVaultItem item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20),
                UiKit.dp(this, 14));
        form.setBackgroundColor(UiKit.SURFACE);

        // One clearly bounded Orbit surface rather than a tall blank area with a rule under it.
        // The box is where the note goes, it starts two lines high, and it grows as the user
        // writes; nothing about it can be mistaken for a length indicator.
        EditText note = UiKit.input(this, "Why did you save this?", true);
        note.setContentDescription("Your note");
        note.setText(item.note);
        note.setSelection(note.length());
        form.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView why = UiKit.text(this,
                "Your note is yours. It does not change what was saved, and it stays on this "
                        + "device until you attach this item to a message yourself.",
                12, UiKit.MUTED, false);
        why.setPadding(0, UiKit.dp(this, 12), 0, 0);
        form.addView(why);

        TextView customTitle = UiKit.text(this, item.hasNote() ? "Edit your note" : "Add a note",
                20, UiKit.TEXT, true);
        customTitle.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 18), UiKit.dp(this, 20),
                UiKit.dp(this, 8));
        customTitle.setBackgroundColor(UiKit.SURFACE);

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null);
        // Offered only when there is something to remove, so the dialog for a first note has two
        // buttons rather than a third that would do nothing.
        if (item.hasNote()) {
            builder.setNeutralButton("Remove", (d, w) -> {
                OrbitVaultStore.updateNote(this, item.id, "");
                rebuild();
            });
        }
        AlertDialog dialog = builder.create();
        UiKit.styleOrbitDialog(dialog, this, false, () -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (save == null) return;
            save.setOnClickListener(v -> {
                if (!OrbitVaultStore.updateNote(this, item.id, note.getText().toString())) {
                    Toast.makeText(this, "Orbit could not save that note",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.dismiss();
                rebuild();
            });
        });
        dialog.show();
    }

    private void edit(OrbitVaultItem item) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 4), UiKit.dp(this, 20), UiKit.dp(this, 14));
        form.setBackgroundColor(UiKit.SURFACE);

        EditText title = UiKit.input(this, "Title", false);
        title.setText(item.title);
        form.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText body;
        if (item.bodyIsEditable()) {
            body = UiKit.input(this, "Text", true);
            body.setText(item.body);
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
     * Opens the page a saved picture came from.
     *
     * <p>Validated again here rather than trusted from storage, for the same reason Open link is:
     * the address has been sitting in a store since the day it was saved, and a store can be
     * restored from a backup, edited by hand, or written by a build that checked something else.
     * Only an ordinary http or https address is handed to Android, and only from this tap.
     */
    private void openSource(OrbitVaultItem item) {
        if (item == null || !RichAnswerUrlPolicy.isOpenableWebUrl(item.sourceUrl)) {
            Toast.makeText(this, "That is not a web address Orbit can open",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(item.sourceUrl)));
        } catch (Exception ignored) {
            Toast.makeText(this, "Could not open this source", Toast.LENGTH_SHORT).show();
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

    /** The one action this kind of item is really for: accent-filled, and the only one that is. */
    private Button filledAction(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(UiKit.onAccent(this));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.ripple(UiKit.accent(this), UiKit.onAccent(this), 16, this));
        b.setOnClickListener(listener);
        UiKit.pressScale(b);
        return b;
    }

    private Button outlinedAction(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(UiKit.TEXT);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 90), UiKit.accent(this), 16, this));
        b.setOnClickListener(listener);
        UiKit.pressScale(b);
        return b;
    }

    /**
     * Lays the utility actions out in rows that stay readable at this width.
     *
     * <p>Four controls across a phone is the failure this avoids: at roughly a quarter of 412dp
     * each, "Rename" and "Unpin" are already at the edge of fitting, and one step up in the system
     * font size finishes them. So a phone takes them two at a time and a tablet takes the row it
     * has the width for. Every cell in the grid is weighted equally whichever shape is chosen, and
     * a row that is short of a full pair is padded with empty weight rather than being allowed to
     * stretch one control across the page.
     */
    private void addUtilityRows(LinearLayout column, List<View> actions) {
        if (actions.isEmpty()) return;
        int perRow = actions.size() <= 2 || wideEnoughForOneUtilityRow()
                ? actions.size() : 2;
        LinearLayout row = null;
        for (int i = 0; i < actions.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                column.addView(row, actionRowLp(i == 0 ? 10 : 9));
            }
            row.addView(actions.get(i), utilityCellLp(i % perRow == 0 ? 0 : 9));
        }
        int missing = (perRow - (actions.size() % perRow)) % perRow;
        for (int i = 0; i < missing; i++) {
            row.addView(new View(this), utilityCellLp(9));
        }
    }

    /** Whether this display can carry every utility on one row without cramping any of them. */
    static boolean fitsOneUtilityRow(int widthDp, int actions) {
        return actions <= 2 || widthDp >= UTILITY_ROW_MIN_WIDTH_DP;
    }

    /**
     * The width at which four utility cells stop being cramped.
     *
     * <p>Above a Galaxy S25 Ultra and below a Tab S9 Plus, which is exactly the line this rule is
     * drawn for: the phone takes two rows of two, the tablet takes one row of four.
     */
    static final int UTILITY_ROW_MIN_WIDTH_DP = 600;

    private boolean wideEnoughForOneUtilityRow() {
        return getResources().getConfiguration().screenWidthDp >= UTILITY_ROW_MIN_WIDTH_DP;
    }

    /**
     * One everyday utility: an accent icon above its own word, in a card the size of a fingertip.
     *
     * <p>Icon and label together rather than either alone. An icon by itself is a guess, and a word
     * by itself at this width is what produced Beta 1's wall of identical buttons; the label is
     * always drawn, so the control stays readable for someone who does not recognise the glyph and
     * keeps working when the system font is large.
     */
    private View compactAction(String label, int icon, View.OnClickListener listener) {
        return compactAction(label, icon, label, listener);
    }

    private View compactAction(String label, int icon, String description,
                               View.OnClickListener listener) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 9), UiKit.dp(this, 6), UiKit.dp(this, 9));

        ImageView glyph = new ImageView(this);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(UiKit.accent(this)));
        cell.addView(glyph, new LinearLayout.LayoutParams(UiKit.dp(this, 20), UiKit.dp(this, 20)));

        TextView word = UiKit.text(this, label, 12, UiKit.TEXT, false);
        word.setGravity(Gravity.CENTER);
        word.setPadding(0, UiKit.dp(this, 6), 0, 0);
        cell.addView(word);

        cell.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(this), 70), UiKit.accent(this), 15, this));
        cell.setContentDescription(description);
        cell.setOnClickListener(listener);
        UiKit.pressScale(cell);
        return cell;
    }

    private LinearLayout.LayoutParams primaryCellLp(int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, UiKit.dp(this, 50), 1);
        lp.setMargins(UiKit.dp(this, startDp), 0, 0, 0);
        return lp;
    }

    private LinearLayout.LayoutParams utilityCellLp(int startDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(UiKit.dp(this, startDp), 0, 0, 0);
        return lp;
    }

    /**
     * One row of the action area, width-capped and centred.
     *
     * <p>The cap only ever bites on a tablet. It is the difference between three utility controls
     * that read as a group and three controls a hand's width apart at the far edges of a Tab S9
     * Plus.
     */
    private LinearLayout.LayoutParams actionRowLp(int topDp) {
        int available = getResources().getDisplayMetrics().widthPixels;
        int capped = Math.min(available, UiKit.dp(this, ACTIONS_MAX_WIDTH_DP));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                capped >= available ? ViewGroup.LayoutParams.MATCH_PARENT : capped,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
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

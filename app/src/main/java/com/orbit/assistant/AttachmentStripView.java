package com.orbit.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * The row of things staged on the message being written.
 *
 * <p>Horizontal on purpose. A composer that grows a card per attachment eats the conversation
 * above it and pushes the editor off a phone screen by the third photo; a strip that scrolls
 * sideways costs the same height whether it holds one item or ten. One attachment is drawn as the
 * single labelled card Orbit has always shown, so the common case did not change appearance when
 * the uncommon one became possible.
 *
 * <p>Shared by full chat and the Side-button overlay rather than written twice, which is what stops
 * the two surfaces drifting into different ideas of what an attachment looks like. It draws only:
 * it holds no attachment state, decides nothing about limits, and reports a removal by id to
 * whoever owns the collection.
 */
public final class AttachmentStripView extends HorizontalScrollView {

    /** Told which attachment the user asked to remove. */
    public interface OnRemove {
        void onRemove(String attachmentId);
    }

    private final LinearLayout row;
    private final boolean compact;
    private OnRemove onRemove;

    public AttachmentStripView(Context context) {
        this(context, false);
    }

    /** {@code compact} is the overlay's tighter sizing; the layout is otherwise identical. */
    public AttachmentStripView(Context context, boolean compact) {
        super(context);
        this.compact = compact;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        // The strip is a horizontal scroller sitting directly above a text editor. Without this a
        // sideways drag that starts on a thumbnail can be claimed by an ancestor - the overlay
        // sheet's own vertical gestures, or a chat page transition - and the strip stops scrolling.
        setOverScrollMode(OVER_SCROLL_NEVER);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setVisibility(GONE);
    }

    public void setOnRemove(OnRemove listener) { this.onRemove = listener; }

    /**
     * Redraws the strip for the given ordered attachments.
     *
     * <p>A full rebuild rather than a diff. The list is at most ten items and is only rebuilt on a
     * deliberate user action, so the simpler code is worth more here than the saved views - and a
     * rebuild cannot leave a stale remove button wired to an attachment that has already gone.
     */
    public void bind(List<ComposerAttachment> attachments) {
        row.removeAllViews();
        if (attachments == null || attachments.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        Context c = getContext();
        int total = attachments.size();
        for (int i = 0; i < total; i++) {
            ComposerAttachment attachment = attachments.get(i);
            if (attachment == null) continue;
            View card = buildCard(c, attachment, i + 1, total);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(UiKit.dp(c, 6));
            row.addView(card, lp);
        }
        // A new batch is appended to the end, so that is where the user is shown.
        post(() -> fullScroll(FOCUS_RIGHT));
    }

    private View buildCard(Context c, ComposerAttachment attachment, int position, int total) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(UiKit.dp(c, compact ? 8 : 10), UiKit.dp(c, compact ? 5 : 6),
                UiKit.dp(c, 4), UiKit.dp(c, compact ? 5 : 6));
        card.setBackground(UiKit.outlined(UiKit.SURFACE_2,
                UiKit.withAlpha(UiKit.accent(c), 90), compact ? 13 : 14, c));

        if (attachment.image != null) {
            ImageView preview = new ImageView(c);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackground(UiKit.rounded(UiKit.SURFACE_3, 8, c));
            preview.setClipToOutline(true);
            preview.setImageBitmap(attachment.image);
            // Decorative: the card's own content description already names the attachment, so a
            // screen reader is not made to announce the same thing twice.
            preview.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                    UiKit.dp(c, compact ? 34 : 40), UiKit.dp(c, compact ? 30 : 34));
            previewLp.setMarginEnd(UiKit.dp(c, 7));
            card.addView(preview, previewLp);
        } else {
            // A document is a small filled tile with a dot rather than a picture, so a text file
            // and a photo are told apart at a glance without either needing a caption.
            View tile = new View(c);
            tile.setBackground(UiKit.rounded(UiKit.withAlpha(UiKit.accent(c), 54), 8, c));
            tile.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(
                    UiKit.dp(c, compact ? 22 : 26), UiKit.dp(c, compact ? 22 : 26));
            tileLp.setMarginEnd(UiKit.dp(c, 7));
            card.addView(tile, tileLp);
        }

        TextView label = UiKit.text(c, attachment.label, compact ? 11.5f : 12, UiKit.TEXT, true);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(UiKit.dp(c, compact ? 128 : 156));
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageButton remove = new ImageButton(c);
        remove.setImageResource(com.orbit.assistant.R.drawable.ic_close);
        remove.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        remove.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.withAlpha(UiKit.accent(c), 60),
                99, c));
        remove.setColorFilter(UiKit.MUTED);
        remove.setPadding(UiKit.dp(c, 7), UiKit.dp(c, 7), UiKit.dp(c, 7), UiKit.dp(c, 7));
        remove.setContentDescription(total == 1
                ? "Remove " + attachment.label
                : "Remove " + attachment.label + ", attachment " + position + " of " + total);
        final String id = attachment.id;
        remove.setOnClickListener(v -> {
            if (Prefs.haptics(c)) UiKit.haptic(v, HapticFeedbackConstants.CLOCK_TICK);
            if (onRemove != null) onRemove.onRemove(id);
        });
        // A 36dp control is comfortably above the platform's 32dp minimum even in the overlay's
        // tighter layout, and it stays that size whatever the chat text size is set to.
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                UiKit.dp(c, 36), UiKit.dp(c, 36));
        removeLp.setMarginStart(UiKit.dp(c, 2));
        card.addView(remove, removeLp);

        // The card itself carries the description a screen reader reads before reaching the
        // remove control, so the order is "what this is" then "how to remove it".
        card.setContentDescription(total == 1
                ? "Attachment: " + attachment.label
                : "Attachment " + position + " of " + total + ": " + attachment.label);
        card.setFocusable(true);
        return card;
    }
}

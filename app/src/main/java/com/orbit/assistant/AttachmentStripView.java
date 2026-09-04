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

import java.util.ArrayList;
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
 * whoever owns the collection. The one thing it does remember is what it drew last time, which is
 * a fact about the picture on screen rather than about the attachments, and is only used to decide
 * where the strip should be looking after a redraw.
 */
public final class AttachmentStripView extends HorizontalScrollView {

    /** Told which attachment the user asked to remove. */
    public interface OnRemove {
        void onRemove(String attachmentId);
    }

    /**
     * Told which attachment the user asked to look at.
     *
     * <p>Optional, and unset means the cards are not tappable at all. That is deliberate rather
     * than an oversight: the Side-button overlay draws this same strip while lying over another
     * app, and opening a full-screen Activity from underneath a voice-interaction session would
     * fight the session's own dismissal rather than show a photo. Full chat wires it; the overlay
     * keeps the strip it already had.
     */
    public interface OnOpen {
        void onOpen(String attachmentId);
    }

    /**
     * Where a redraw should leave the strip looking.
     *
     * <p>Kept as a plain decision, separate from the drawing, because "the strip jumped somewhere
     * surprising" is a question about which case the redraw was - not about views. Every rebuild
     * is one of four things and only one of them is a reason to move the viewport at all.
     */
    public static final class ScrollPlan {
        /** Leave the viewport where the user left it. A retheme, a rebuild, a removal. */
        public static final int KEEP = 0;
        /** Show the beginning. The strip was empty and now is not. */
        public static final int START = 1;
        /** Bring a newly appended item into view. */
        public static final int REVEAL = 2;

        public final int mode;
        /** For {@link #REVEAL}, the index of the first item that was not there before. */
        public final int revealIndex;

        private ScrollPlan(int mode, int revealIndex) {
            this.mode = mode;
            this.revealIndex = revealIndex;
        }
    }

    private final LinearLayout row;
    private final boolean compact;
    private OnRemove onRemove;
    private OnOpen onOpen;

    /**
     * The ids drawn by the previous {@link #bind}, in order.
     *
     * <p>View-only, and never a second copy of the composer's collection: it holds ids and nothing
     * else, it is written only by {@code bind}, and nothing reads it to answer a question about
     * what is attached. {@link ComposerAttachments} remains the one place that knows that.
     */
    private final List<String> previousIds = new ArrayList<>();

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

    public void setOnOpen(OnOpen listener) { this.onOpen = listener; }

    /**
     * What a redraw from {@code previous} to {@code next} should do with the viewport.
     *
     * <p>Written as a pure function of the two id lists so the rule can be stated and tested
     * without a laid-out view, and so the drawing below has nothing to decide.
     *
     * <p>The rule is that movement has to be earned. Before this, every rebuild ended with a jump
     * to the far right, which is right for exactly one case and wrong for the rest: attaching a
     * first batch of three photos slammed the strip to Photo 3 with Photo 1 off-screen, removing
     * an item threw the user to the end of a list they were reading the middle of, and a theme
     * change moved the strip for no reason at all. So: a strip that was empty starts at the
     * beginning, a strip that grew by items appended after everything it already had shows the
     * first of them, and anything else - a removal, a rebind of the same list, a redraw - is left
     * exactly where it was.
     */
    public static ScrollPlan planScroll(List<String> previous, List<String> next) {
        int nextSize = next == null ? 0 : next.size();
        if (nextSize == 0) return new ScrollPlan(ScrollPlan.START, 0);
        int previousSize = previous == null ? 0 : previous.size();
        if (previousSize == 0) return new ScrollPlan(ScrollPlan.START, 0);
        if (nextSize <= previousSize) return new ScrollPlan(ScrollPlan.KEEP, 0);
        for (int i = 0; i < previousSize; i++) {
            String was = previous.get(i);
            String now = next.get(i);
            boolean same = was == null ? now == null : was.equals(now);
            // Not an append: the items the user already had are not still where they were, so this
            // is a reordering or a replacement and guessing at a destination would be worse than
            // staying put.
            if (!same) return new ScrollPlan(ScrollPlan.KEEP, 0);
        }
        return new ScrollPlan(ScrollPlan.REVEAL, previousSize);
    }

    /**
     * Redraws the strip for the given ordered attachments.
     *
     * <p>A full rebuild rather than a diff. The list is at most ten items and is only rebuilt on a
     * deliberate user action, so the simpler code is worth more here than the saved views - and a
     * rebuild cannot leave a stale remove button wired to an attachment that has already gone.
     */
    public void bind(List<ComposerAttachment> attachments) {
        List<String> nextIds = new ArrayList<>();
        if (attachments != null) {
            for (ComposerAttachment attachment : attachments) {
                if (attachment != null) nextIds.add(attachment.id);
            }
        }
        ScrollPlan plan = planScroll(previousIds, nextIds);
        // Read before the rebuild, because removing the children resets it.
        int previousScrollX = getScrollX();
        previousIds.clear();
        previousIds.addAll(nextIds);

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
        applyScrollPlan(plan, previousScrollX);
    }

    /**
     * Carries out the plan once the rebuilt row has a width.
     *
     * <p>Posted rather than applied inline: every destination here is expressed in the new
     * content's coordinates, and immediately after {@code addView} the row has not been measured,
     * so a scroll issued now would be clamped against a width of zero and land on nothing.
     */
    private void applyScrollPlan(ScrollPlan plan, int previousScrollX) {
        post(() -> {
            switch (plan.mode) {
                case ScrollPlan.START:
                    fullScroll(isRtl() ? FOCUS_RIGHT : FOCUS_LEFT);
                    break;
                case ScrollPlan.REVEAL:
                    revealChild(plan.revealIndex);
                    break;
                default:
                    // HorizontalScrollView clamps for us, so a strip that became shorter than the
                    // old offset settles at its new end rather than scrolling into empty space.
                    scrollTo(previousScrollX, 0);
                    break;
            }
        });
    }

    /**
     * Brings the first newly appended card to the leading edge of the viewport.
     *
     * <p>Deliberately not "scroll to the end". Appending two photos to three should show Photo 4
     * with Photo 5 beside it, which is what the user just did; landing on Photo 5 alone hides one
     * of the two things that just arrived. The scroll view's own clamping handles the case where
     * there is not that much content to the right.
     */
    private void revealChild(int index) {
        if (index < 0 || index >= row.getChildCount()) {
            fullScroll(isRtl() ? FOCUS_LEFT : FOCUS_RIGHT);
            return;
        }
        View child = row.getChildAt(index);
        int lead = UiKit.dp(getContext(), 6);
        int target = isRtl()
                ? child.getRight() - getWidth() + lead
                : child.getLeft() - lead;
        if (UiKit.animationsEnabled()) smoothScrollTo(Math.max(0, target), 0);
        else scrollTo(Math.max(0, target), 0);
    }

    private boolean isRtl() {
        return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
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

        // A photo's caption is its position; a document's is its real name. The cap is tighter for
        // a name than the strip used to allow, because one long filename taking a third of the
        // strip is the same problem in a smaller form.
        boolean photo = AttachmentLabels.isPhoto(attachment);
        int maxLabelWidth = UiKit.dp(c, photo ? 96 : (compact ? 104 : 124));
        TextView label = UiKit.text(c, AttachmentLabels.displayLabel(attachment, position),
                compact ? 11.5f : 12, UiKit.TEXT, true);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        label.setMaxWidth(maxLabelWidth);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (attachment.hasDetail()) {
            // Two facts, two lines. A page attachment has a document to name and a place inside it
            // to state, and joining those with a separator ellipsizes away whichever half the user
            // needed — which is what made "Health behavior theory … · Page 5" read as an abstract
            // label rather than as a page that is genuinely attached.
            LinearLayout stack = new LinearLayout(c);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            stack.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView detail = UiKit.text(c, attachment.detail, compact ? 10.5f : 11,
                    UiKit.MUTED, false);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            detail.setMaxWidth(maxLabelWidth);
            detail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            stack.addView(detail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(stack, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            card.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        ImageButton remove = new ImageButton(c);
        remove.setImageResource(com.orbit.assistant.R.drawable.ic_close);
        remove.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        remove.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.withAlpha(UiKit.accent(c), 60),
                99, c));
        remove.setColorFilter(UiKit.MUTED);
        remove.setPadding(UiKit.dp(c, 7), UiKit.dp(c, 7), UiKit.dp(c, 7), UiKit.dp(c, 7));
        remove.setContentDescription(
                AttachmentLabels.removeDescription(attachment, position, total));
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
        // remove control, so the order is "what this is" then "how to remove it". The shortened
        // caption never reaches this: a screen reader is told the kind and the position, and a
        // document is still told its name.
        String description = AttachmentLabels.cardDescription(attachment, position, total);
        // A card opens the full-screen viewer only when there is something full screen to see. A
        // PDFs now open their own native, page-aware viewer. They still never enter the image
        // viewer: the rendered bitmap on this card is only a compact attachment preview.
        boolean openable = onOpen != null && (AttachmentViewerModel.isViewable(attachment)
                || attachment.isDocument());
        if (openable) {
            card.setContentDescription(description + (attachment.isDocument()
                    ? ", opens document viewer" : ", opens full screen"));
            card.setOnClickListener(v -> onOpen.onOpen(attachment.id));
            // pressScale carries Orbit's press feedback and its own haptic tick, so the card is
            // not made to fire a second one on the same touch.
            UiKit.pressScale(card);
        } else {
            card.setContentDescription(description);
        }
        card.setFocusable(true);
        return card;
    }
}

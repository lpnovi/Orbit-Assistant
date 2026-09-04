package com.orbit.assistant;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * One Deck tile, drawn.
 *
 * <p>Deliberately has no idea what any tile does. It is handed a {@link DeckTileResolver.Resolved}
 * and reports taps and long-presses upwards; deciding what a tap means belongs to
 * {@link DeckActionExecutor}, and deciding what is true belongs to the resolver. That separation is
 * what keeps the file that draws a tile from becoming the file that knows about Routines.
 *
 * <h2>Shape</h2>
 *
 * <p>A 22dp card on a tonal surface, 14dp of padding, a 40dp rounded icon well, and the text
 * pushed to the bottom so that every tile in a row shares a baseline whatever its icon. The tile is
 * the button: there is no inner control to hit, the whole surface ripples, and the only thing that
 * ever appears on top of it is the remove badge in edit mode.
 *
 * <p>Nothing here sets a fixed height. The grid measures each tile against its own content, so a
 * large font scale grows the row rather than clipping the title.
 */
public final class DeckTileView extends FrameLayout {

    public interface Listener {
        void onTileTapped(DeckTile tile, DeckTileView view);
        void onTileLongPressed(DeckTile tile, DeckTileView view);
        void onTileRemoveTapped(DeckTile tile, DeckTileView view);
    }

    private final DeckTile tile;
    private final LinearLayout content;
    private final ImageView icon;
    private final TextView title;
    private final TextView subtitle;
    private final View removeBadge;

    private boolean editing;
    /** The pick-up lift, kept apart from the grid's slide. See {@link #setCarried(boolean)}. */
    private ValueAnimator liftAnimator;

    public DeckTileView(Context c, DeckTile tile, DeckTileResolver.Resolved resolved,
                        Listener listener) {
        super(c);
        this.tile = tile;

        int radius = 22;
        boolean usable = resolved.usable();
        int fill = usable
                ? UiKit.blend(UiKit.accent(c), UiKit.SURFACE_2, 0.05f)
                // An unusable tile recedes rather than shouting. It is still legible, still
                // readable by TalkBack, and still exactly where the user put it.
                : UiKit.blend(UiKit.SURFACE, UiKit.BG, 0.6f);
        setBackground(UiKit.ripple(fill, UiKit.accent(c), radius, c));
        setClipToOutline(false);
        UiKit.pressScale(this);

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(c, 14);
        content.setPadding(pad, pad, pad, pad);
        // Fills the tile rather than wrapping its text. The tile's height is decided by the row, so
        // wrapping would leave the label floating in the middle of a tall tile with dead space under
        // it; filling lets the weighted spacer below push the text onto the tile's bottom edge.
        addView(content, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- icon well ----------------------------------------------------------------------
        FrameLayout well = new FrameLayout(c);
        int wellTone = usable
                ? UiKit.blend(UiKit.accent(c), UiKit.SURFACE_3, 0.16f)
                : UiKit.blend(UiKit.MUTED, UiKit.SURFACE_2, 0.10f);
        well.setBackground(UiKit.rounded(wellTone, 13, c));
        icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                UiKit.dp(c, 22), UiKit.dp(c, 22), Gravity.CENTER);
        well.addView(icon, iconLp);
        LinearLayout.LayoutParams wellLp = new LinearLayout.LayoutParams(
                UiKit.dp(c, 40), UiKit.dp(c, 40));
        content.addView(well, wellLp);

        // Absorbs whatever height the row has beyond the icon and the text, which is what puts the
        // icon at the top and the label on the bottom edge and keeps every tile in a row sharing a
        // text baseline. It never shrinks below 12dp, so a title can never crowd the icon.
        View spacer = new View(c);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 12), 1f));

        title = UiKit.text(c, resolved.title, 15, usable ? UiKit.TEXT : UiKit.MUTED, true);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        subtitle = UiKit.text(c, resolved.subtitle, 12,
                usable ? UiKit.MUTED : UiKit.withAlpha(UiKit.MUTED, 190), false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = UiKit.dp(c, 2);
        content.addView(subtitle, subLp);

        // ---- remove badge, edit mode only ---------------------------------------------------
        ImageView remove = new ImageView(c);
        remove.setImageResource(R.drawable.ic_close);
        remove.setImageTintList(ColorStateList.valueOf(UiKit.TEXT));
        remove.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int inset = UiKit.dp(c, 7);
        remove.setPadding(inset, inset, inset, inset);
        remove.setBackground(UiKit.rounded(
                UiKit.blend(UiKit.DANGER, UiKit.SURFACE_3, 0.55f), 13, c));
        remove.setContentDescription("Remove " + resolved.title);
        LayoutParams removeLp = new LayoutParams(
                UiKit.dp(c, 26), UiKit.dp(c, 26), Gravity.TOP | Gravity.END);
        int edge = UiKit.dp(c, 6);
        removeLp.setMargins(0, edge, edge, 0);
        remove.setVisibility(GONE);
        addView(remove, removeLp);
        removeBadge = remove;
        remove.setOnClickListener(v -> {
            if (listener != null) listener.onTileRemoveTapped(tile, this);
        });

        apply(resolved);

        setOnClickListener(v -> { if (listener != null) listener.onTileTapped(tile, this); });
        setOnLongClickListener(v -> {
            if (listener == null) return false;
            listener.onTileLongPressed(tile, this);
            return true;
        });
    }

    /**
     * Re-dresses this tile in place.
     *
     * <p>Used when live state changes. Nothing is rebuilt and nothing is re-laid out unless the
     * text genuinely changed, which is what lets a flashlight going on update the tile without the
     * grid flashing or the page jumping.
     */
    public void apply(DeckTileResolver.Resolved resolved) {
        if (resolved.appIcon != null) {
            // A third-party icon keeps its own colours, so it is never tinted, and it sits inside
            // the same 22dp well as everything else rather than taking over the tile.
            icon.setImageDrawable(resolved.appIcon);
            icon.setImageTintList(null);
        } else {
            icon.setImageResource(resolved.iconRes);
            icon.setImageTintList(ColorStateList.valueOf(resolved.usable()
                    ? UiKit.accent(getContext()) : UiKit.MUTED));
        }

        UiKit.swapText(title, resolved.title);

        boolean showSubtitle = !resolved.subtitle.isEmpty()
                && (tile.size == DeckTile.Size.WIDE || resolved.liveState);
        subtitle.setVisibility(showSubtitle ? VISIBLE : GONE);
        if (showSubtitle) UiKit.swapText(subtitle, resolved.subtitle);

        // One node, one sentence. The icon and the two labels are the same tile the description
        // already names, so TalkBack should not walk them as three separate things.
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        title.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        subtitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentDescription(editing
                ? resolved.contentDescription + ", editing. Double tap for options."
                : resolved.contentDescription);
        setFocusable(true);
    }

    /** Switches this tile between ordinary and editing presentation. */
    public void setEditing(boolean value) {
        if (editing == value) return;
        editing = value;
        removeBadge.setVisibility(value ? VISIBLE : GONE);
        // A restrained lift rather than a continuous wobble: edit mode should read as a state, not
        // as an animation somebody has to wait out.
        float target = value ? 0.97f : 1f;
        if (UiKit.animationsEnabled()) {
            content.animate().scaleX(target).scaleY(target)
                    .setDuration(140L).setInterpolator(UiKit.motionEasing()).start();
        } else {
            content.setScaleX(target);
            content.setScaleY(target);
        }
    }

    public boolean isEditing() { return editing; }

    public DeckTile tile() { return tile; }

    /**
     * Visually picks the tile up for a drag.
     *
     * <p>The lift runs on an animator of its own rather than on {@code animate()}, because the grid
     * uses this view's {@link android.view.ViewPropertyAnimator} to slide it between slots, and
     * that animator has no per-property cancel — cancelling a stale slide would take the lift with
     * it. Sharing the two left a picked-up tile stuck part-scaled, and a dropped one stuck at 1.04
     * for the rest of the session.
     */
    public void setCarried(boolean carried) {
        float scale = carried ? 1.04f : 1f;
        setElevation(carried ? UiKit.dp(getContext(), 8) : 0f);
        if (liftAnimator != null) {
            liftAnimator.cancel();
            liftAnimator = null;
        }
        if (!UiKit.animationsEnabled()) {
            setScaleX(scale);
            setScaleY(scale);
            return;
        }
        ValueAnimator lift = ValueAnimator.ofFloat(getScaleX(), scale);
        lift.setDuration(120L);
        lift.setInterpolator(UiKit.motionEasing());
        lift.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            setScaleX(value);
            setScaleY(value);
        });
        lift.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                // Settled exactly, so a cancelled or interrupted drag can never leave a tile a
                // fraction larger than its neighbours.
                setScaleX(scale);
                setScaleY(scale);
                if (liftAnimator == animation) liftAnimator = null;
            }
        });
        liftAnimator = lift;
        lift.start();
    }

    /** The title currently rendered. For tests. */
    public CharSequence titleText() { return title.getText(); }

    /** The secondary line, or empty when it is not being shown. */
    public CharSequence subtitleText() {
        return subtitle.getVisibility() == VISIBLE ? subtitle.getText() : "";
    }

    /** Whether the remove affordance is on screen. For tests. */
    public boolean removeVisible() { return removeBadge.getVisibility() == VISIBLE; }

    /** The icon view, so its geometry can be asserted. */
    public ImageView iconView() { return icon; }

    /** The image currently in the icon well, app icons included. */
    public Drawable iconDrawable() { return icon.getDrawable(); }

}

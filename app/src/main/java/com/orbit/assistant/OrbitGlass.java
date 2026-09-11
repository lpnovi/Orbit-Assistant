package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * Orbit's floating chrome: the control surfaces that sit above a scrolling list.
 *
 * <p>This exists because of one recurring complaint about Chats and the Vault, and it was never
 * really about the divider. Both screens ended with a band of controls, then a horizontal line, and
 * then a list whose first card was sliced flat against the window background the moment anything
 * scrolled. Every attempt to soften that boundary treated it as a spacing problem - a thinner rule,
 * a wrapping panel, a taller gap - and each one made the screen read as more segmented, because the
 * boundary itself was still being drawn. A page reads as one continuous surface only when there is
 * no boundary to soften.
 *
 * <p>So the treatment here is layered rather than divided. The controls are given a translucent
 * surface with a lit top edge, a hairline border and a little real depth, so they read as glass
 * lying <em>over</em> the page. Beneath them a scrim is laid over the top of the scroller: opaque in
 * the page's own background colour where the list is clipped, dissolving through a whisper of the
 * theme's accent, and gone entirely within {@link #SCRIM_DEPTH_DP}. Content does not stop at a line
 * any more; it fades out underneath the chrome, which is what makes the two regions read as one
 * surface at two depths.
 *
 * <p><b>There is no blur, and that is a decision rather than an omission.</b> Android has no
 * backdrop blur a View can use: {@code RenderEffect} (API 31) blurs a view's own content, not what
 * is behind it, and {@code Window#setBackgroundBlurRadius} (also API 31) applies to whole windows,
 * is not supported on every device, and cannot be aimed at a control inside an Activity. The only
 * remaining route on Orbit's {@code minSdk 29} floor is to capture the screen behind the control
 * and blur the bitmap, which means allocating and blurring every frame of every scroll. A flawless
 * translucent scrim is worth more than a janky blur, so Orbit draws two hardware-accelerated
 * gradient drawables and nothing else. Every supported API level gets exactly the same treatment,
 * so there is no fallback path to keep working either.
 *
 * <p>Every number the treatment depends on is here rather than at the call sites, so Chats and the
 * Vault cannot slowly stop agreeing about what Orbit's glass looks like. All of the colour is
 * derived from the live {@link UiKit} canvas and the active accent, so Theme Studio, AMOLED and a
 * custom theme all get their own glass rather than a violet one.
 */
public final class OrbitGlass {

    private OrbitGlass() {}

    // ---- the numbers ------------------------------------------------------------------------------

    /** Corner radius of a floating control. Matches Orbit's search and primary-button rhythm. */
    public static final float RADIUS_DP = 18f;
    /**
     * How much of the page shows through the glass. Translucent, never washed out.
     *
     * <p>Orbit's own value, and now also the default of the Pro glass opacity control. A surface is
     * drawn at {@code OrbitProStyle.glassOpacity}, which is this number for everybody who has not
     * deliberately moved it.
     */
    public static final int FILL_ALPHA = 214;
    /** The hairline around a floating control, at Orbit's own edge strength. */
    public static final int BORDER_ALPHA = 64;
    public static final float BORDER_WIDTH_DP = 1f;
    /** Resting depth of a floating control, and the depth it takes when content is underneath. */
    public static final float RESTING_ELEVATION_DP = 2f;
    public static final float RAISED_ELEVATION_DP = 6f;
    /** How far the scrim reaches below the last floating control before it is completely gone. */
    public static final float SCRIM_DEPTH_DP = 30f;
    /** Between two floating controls in the same cluster. */
    public static final float CONTROL_GAP_DP = 8f;
    /** Between the last floating control and the top of the scrim. */
    public static final float CHROME_GAP_DP = 6f;
    /** Padding inside the scrolled column, so the first heading rests clear of the scrim. */
    public static final float FEED_INSET_DP = 18f;
    /** A floating control stops widening past this, so a tablet does not get an absurd search box. */
    public static final int MAX_CONTROL_WIDTH_DP = 520;

    /**
     * The four shares the shipped treatment is built from, before Orbit Pro scales any of them.
     *
     * <p>These were private constants with no reason to be anything else until Theme Studio Pro
     * gave a person a way to adjust the glass. They are now the 100% mark of those controls rather
     * than the only numbers Orbit can draw: {@link OrbitProStyle} multiplies each one, a Pro user
     * sitting at every default lands back on these exact values, and Free never leaves them. That
     * is what makes "identical to the previous release unless you changed something" a property of
     * the arithmetic rather than a promise.
     */
    static final float BASE_HIGHLIGHT_SHARE = 0.14f;
    /** How much of the accent's own colour is in the lit top edge, before it meets the surface. */
    static final float BASE_FILL_ACCENT_SHARE = 0.42f;
    /** How much of the accent is in the hairline. */
    static final float BASE_BORDER_ACCENT_SHARE = 0.38f;
    /**
     * How much accent is in the scrim's haze. Deliberately a whisper, never a colour wash.
     *
     * <p>A tenth of the accent against a true-black page is a difference of a handful of values per
     * channel: enough that the transition reads as light coming off the glass rather than as
     * nothing, and nowhere near enough to be seen as a gradient somebody added.
     */
    static final float BASE_HAZE_SHARE = 0.10f;
    /** How opaque the hairline is at Orbit's own edge strength. */
    static final int BASE_BORDER_ALPHA = BORDER_ALPHA;
    /** How far the bottom of the glass settles back towards the page behind it. */
    private static final float DEPTH_SHARE = 0.34f;
    /** The scrim's falloff, from the clipped edge of the list down to nothing. */
    private static final int[] SCRIM_ALPHAS = {255, 214, 140, 58, 0};

    /** Scrim strength with nothing behind it, and with the list scrolled underneath. */
    private static final float SCRIM_RESTING_ALPHA = 0.78f;
    private static final float SCRIM_RAISED_ALPHA = 1f;
    /** How far the list has to move before the chrome is treated as having content beneath it. */
    private static final float RAISE_THRESHOLD_DP = 2f;

    // ---- what the glass is made of ----------------------------------------------------------------

    /**
     * The three colours and one styling value every glass surface is derived from.
     *
     * <p>This exists for the same reason {@link OrbitThemeTokens} does. Every colour below used to
     * be read straight off {@code UiKit}'s live canvas, which is correct for the app and useless
     * for the Theme Studio preview: a preview has to show a theme that is <em>not</em> applied, and
     * a resolver that can only read the applied one forces the preview to grow its own copy of the
     * arithmetic. That copy is the thing that drifts. So the inputs are named here, the app passes
     * {@link #live}, the preview passes {@link #of}, and there is still exactly one implementation
     * of what Orbit's glass looks like.
     */
    public static final class Palette {
        final int accent;
        final int background;
        final int surface;
        final OrbitProStyle style;

        private Palette(int accent, int background, int surface, OrbitProStyle style) {
            this.accent = accent;
            this.background = background;
            this.surface = surface;
            this.style = style == null ? OrbitProStyle.DEFAULT : style;
        }

        /** The glass of the theme Orbit is drawing with, at the styling it is entitled to draw. */
        public static Palette live(Context c) {
            return new Palette(UiKit.accent(c), UiKit.BG, UiKit.SURFACE, OrbitProStyle.live(c));
        }

        /** The glass a resolved theme would have. Used by the preview, which has no live canvas. */
        public static Palette of(OrbitThemeTokens tokens, OrbitProStyle style) {
            if (tokens == null) return new Palette(UiKit.DEFAULT_ACCENT, UiKit.BG, UiKit.SURFACE, style);
            return new Palette(tokens.accent, tokens.background, tokens.surface, style);
        }
    }

    // ---- colour, derived from whatever theme is live ----------------------------------------------

    /**
     * The lit top edge of the glass: the card surface with a little of the accent's own light in it.
     *
     * <p>The accent is mixed towards white first, so the highlight reads as light falling on the
     * control rather than as the control being tinted. On a warm accent it is warm, on a violet one
     * it is violet, and on a light Theme Studio surface it stays a highlight rather than a stain.
     *
     * <p>Orbit Pro's tint control moves how much accent survives that mix and its edge control
     * moves how much of the result reaches the surface. At the shipped values both are one, and the
     * expression reduces to exactly what it was.
     */
    public static int fillTop(Context c) {
        return fillTop(Palette.live(c));
    }

    public static int fillTop(Palette p) {
        int lit = UiKit.blend(p.accent, Color.WHITE, p.style.glassFillAccentShare());
        return UiKit.blend(lit, p.surface, p.style.glassHighlightShare());
    }

    /** The foot of the glass, settled back towards the page it is floating over. */
    public static int fillBottom(Context c) {
        return fillBottom(Palette.live(c));
    }

    public static int fillBottom(Palette p) {
        return UiKit.blend(p.background, p.surface, DEPTH_SHARE);
    }

    /** The hairline. Accent-derived and very quiet, so it defines an edge without drawing one. */
    public static int borderColor(Context c) {
        return borderColor(Palette.live(c));
    }

    public static int borderColor(Palette p) {
        return UiKit.withAlpha(
                UiKit.blend(p.accent, Color.WHITE, p.style.glassBorderAccentShare()),
                p.style.glassBorderAlpha());
    }

    /** The scrim's haze: the page's own background carrying a whisper of the accent. */
    public static int hazeColor(Context c) {
        return hazeColor(Palette.live(c));
    }

    public static int hazeColor(Palette p) {
        return UiKit.blend(p.accent, p.background, p.style.glassHazeShare());
    }

    /**
     * What text on a floating control is actually read against.
     *
     * <p>The glass is translucent, so its apparent colour is the fill composited over the page. This
     * is the answer contrast has to be measured against, and it is exposed rather than recomputed
     * in a test, so the check and the drawable can never disagree about what is on screen.
     */
    public static int effectiveFill(Context c) {
        return effectiveFill(Palette.live(c));
    }

    public static int effectiveFill(Palette p) {
        int mid = UiKit.blend(fillTop(p), fillBottom(p), 0.5f);
        float share = p.style.glassOpacity / 255f;
        return UiKit.blend(mid, p.background, share);
    }

    // ---- the surfaces -----------------------------------------------------------------------------

    /** A floating control's background: translucent, lit at the top, with a hairline around it. */
    public static GradientDrawable surfaceDrawable(Context c, float radiusDp) {
        return surfaceDrawable(c, Palette.live(c), radiusDp);
    }

    public static GradientDrawable surfaceDrawable(Context c, Palette p, float radiusDp) {
        GradientDrawable glass = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{
                        UiKit.withAlpha(fillTop(p), p.style.glassOpacity),
                        UiKit.withAlpha(fillBottom(p), p.style.glassOpacity)
                });
        glass.setCornerRadius(UiKit.dp(c, radiusDp));
        glass.setStroke(UiKit.dp(c, BORDER_WIDTH_DP), borderColor(p));
        return glass;
    }

    public static GradientDrawable surfaceDrawable(Context c) {
        return surfaceDrawable(c, RADIUS_DP);
    }

    /** The same surface for something that is tapped, with Orbit's own ripple over it. */
    public static Drawable interactive(Context c, float radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(UiKit.withAlpha(UiKit.accent(c), 56)),
                surfaceDrawable(c, radiusDp),
                UiKit.rounded(Color.WHITE, radiusDp, c));
    }

    public static Drawable interactive(Context c) {
        return interactive(c, RADIUS_DP);
    }

    /**
     * Dresses a control as floating glass: the surface, and a little real depth under it.
     *
     * <p>The elevation is deliberately small. It is there so the control has a shadow to sit on in
     * a theme that has anywhere for one to fall, and on a true-black page it simply costs nothing
     * and the hairline carries the edge instead.
     */
    public static void floatControl(View control, float radiusDp) {
        if (control == null) return;
        control.setBackground(surfaceDrawable(control.getContext(), radiusDp));
        control.setElevation(UiKit.dp(control.getContext(), RESTING_ELEVATION_DP));
    }

    public static void floatControl(View control) {
        floatControl(control, RADIUS_DP);
    }

    // ---- the scrim --------------------------------------------------------------------------------

    /**
     * The gradient that replaces every divider Orbit used to draw here.
     *
     * <p>It starts as the page's own background at full opacity, exactly where the list is clipped,
     * so there is nothing to see at the boundary itself. From there it falls away through the haze
     * and is completely gone {@link #SCRIM_DEPTH_DP} lower down. Nothing in it is a line: the first
     * stop is indistinguishable from the page above it and the last stop is not there at all.
     */
    public static GradientDrawable scrimDrawable(Context c) {
        return scrimDrawable(Palette.live(c));
    }

    public static GradientDrawable scrimDrawable(Palette p) {
        int haze = hazeColor(p);
        int[] colors = new int[SCRIM_ALPHAS.length];
        for (int i = 0; i < SCRIM_ALPHAS.length; i++) {
            colors[i] = UiKit.withAlpha(i == 0 ? p.background : haze, SCRIM_ALPHAS[i]);
        }
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    /**
     * The scrim as a view, ready to be laid over the top of a list.
     *
     * <p>Not clickable, not focusable and not in the accessibility tree, so the list underneath
     * keeps every touch and TalkBack never meets a decorative rectangle.
     */
    public static View scrim(Context c) {
        View scrim = new View(c);
        scrim.setBackground(scrimDrawable(c));
        scrim.setClickable(false);
        scrim.setFocusable(false);
        scrim.setAlpha(SCRIM_RESTING_ALPHA);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return scrim;
    }

    /** Where the scrim goes inside the frame that hosts a list: across the top, and no taller. */
    public static android.widget.FrameLayout.LayoutParams scrimLayout(Context c) {
        return new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(c, SCRIM_DEPTH_DP),
                android.view.Gravity.TOP);
    }

    // ---- how wide a floating control is allowed to be ---------------------------------------------

    /**
     * The whole width on a phone, capped on a tablet.
     *
     * <p>Left-aligned rather than centred when it is capped, so a control starts where the page's
     * content starts instead of floating in the middle away from everything it belongs to.
     */
    public static int controlWidth(Context c) {
        int available = c.getResources().getDisplayMetrics().widthPixels;
        int capped = UiKit.dp(c, MAX_CONTROL_WIDTH_DP);
        return capped >= available ? ViewGroup.LayoutParams.MATCH_PARENT : capped;
    }

    // ---- scroll-linked depth ----------------------------------------------------------------------

    /**
     * One screen's floating chrome, and whether the list is currently underneath it.
     *
     * <p>Two states, not a continuous function of scroll position. A per-frame interpolation would
     * mean touching view properties on every scroll callback for a difference nobody can see, so
     * this crosses a threshold once and settles: the scrim reaches full strength and the controls
     * take a little more depth. Coming back to the top is a short fade; going away from it is
     * immediate, because that is the direction where something is about to pass underneath and the
     * scrim has to already be at full strength when it does.
     */
    public static final class Chrome {

        private final android.widget.FrameLayout host;
        private final View scrim;
        private View[] controls;
        private final int threshold;
        private final float lift;
        private boolean raised;

        Chrome(android.widget.FrameLayout host, View scrim, View... controls) {
            this.host = host;
            this.scrim = scrim;
            this.controls = controls == null ? new View[0] : controls;
            Context c = scrim.getContext();
            this.threshold = UiKit.dp(c, RAISE_THRESHOLD_DP);
            this.lift = UiKit.dp(c, RAISED_ELEVATION_DP - RESTING_ELEVATION_DP);
        }

        /**
         * Replaces the set of floating controls this chrome gives depth to.
         *
         * <p>The Vault rebuilds its two selectors whenever the filter changes, so the chrome has to
         * be told about the new ones or half the cluster would sit at a different depth from the
         * other half. Replaced wholesale rather than added to, so a rebuilt screen cannot leave the
         * chrome holding views that are no longer on it.
         */
        public void setControls(View... floating) {
            controls = floating == null ? new View[0] : floating;
            for (View control : controls) {
                if (control != null) control.setTranslationZ(raised ? lift : 0f);
            }
        }

        /** The frame holding the list and the scrim over it. This is what the page adds. */
        public View host() {
            return host;
        }

        /** The scrim itself, for the screens and tests that need to look at it. */
        public View scrim() {
            return scrim;
        }

        /** Follows one list. Safe to call again after a rebuild; the state is recomputed. */
        public void follow(ScrollView scroller) {
            if (scroller == null) return;
            scroller.setOnScrollChangeListener(
                    (view, x, y, oldX, oldY) -> setRaised(y > threshold));
            apply(false);
        }

        public void setRaised(boolean value) {
            if (value == raised) return;
            raised = value;
            apply(true);
        }

        public boolean raised() {
            return raised;
        }

        private void apply(boolean animate) {
            float target = raised ? SCRIM_RAISED_ALPHA : SCRIM_RESTING_ALPHA;
            float depth = raised ? lift : 0f;
            // Immediate on the way up, so the scrim is already at full strength by the time the
            // first card reaches it; a short settle on the way back down, where nothing is hidden.
            boolean settle = animate && !raised && UiKit.animationsEnabled();
            if (settle) {
                scrim.animate().cancel();
                scrim.animate().alpha(target)
                        .setDuration(UiKit.MOTION_STANDARD)
                        .setInterpolator(UiKit.motionEasing())
                        .start();
            } else {
                scrim.animate().cancel();
                scrim.setAlpha(target);
            }
            for (View control : controls) {
                if (control == null) continue;
                control.animate().cancel();
                if (settle) {
                    control.animate().translationZ(depth)
                            .setDuration(UiKit.MOTION_STANDARD)
                            .setInterpolator(UiKit.motionEasing())
                            .start();
                } else {
                    control.setTranslationZ(depth);
                }
            }
        }
    }

    /**
     * Lays a scrim over the top of a list and returns the chrome that keeps the two in step.
     *
     * <p>The list is re-parented into a frame so the scrim can be drawn over it rather than fitted
     * above it. That is the whole difference between content that stops at a line and content that
     * passes underneath something.
     */
    public static Chrome install(ScrollView scroller, View content, View... controls) {
        Context c = scroller.getContext();
        View scrim = scrim(c);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(c);
        frame.addView(scroller, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addView(scrim, scrimLayout(c));
        if (content != null) {
            content.setPadding(content.getPaddingLeft(), UiKit.dp(c, FEED_INSET_DP),
                    content.getPaddingRight(), content.getPaddingBottom());
        }
        Chrome chrome = new Chrome(frame, scrim, controls);
        chrome.follow(scroller);
        return chrome;
    }
}

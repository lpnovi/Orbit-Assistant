package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
 * <p>So the treatment here is layered rather than divided. The controls are given Liquid Orbit
 * Glass - a translucent body you can see into, light pooling where it faces the light, accent
 * gathering at its thick edges and a hairline holding the whole thing - so they read as glass lying
 * <em>over</em> the page rather than as a panel fitted into it. Beneath them a scrim is laid over the
 * top of the scroller: opaque in the page's own background colour where the list is clipped,
 * dissolving through a whisper of the theme's accent, and gone entirely within
 * {@link #SCRIM_DEPTH_DP}. Content does not stop at a line any more; it fades out underneath the
 * chrome, which is what makes the two regions read as one surface at two depths.
 *
 * <p><b>There is no blur, and that is a decision rather than an omission.</b> Android has no
 * backdrop blur a View can use: {@code RenderEffect} (API 31) blurs a view's own content, not what
 * is behind it, and {@code Window#setBackgroundBlurRadius} (also API 31) applies to whole windows,
 * is not supported on every device, and cannot be aimed at a control inside an Activity. The only
 * remaining route on Orbit's {@code minSdk 29} floor is to capture the screen behind the control
 * and blur the bitmap, which means allocating and blurring every frame of every scroll. A flawless
 * translucent scrim is worth more than a janky blur, so Orbit draws hardware-accelerated gradient
 * drawables and nothing else. Every supported API level gets exactly the same treatment, so there is
 * no fallback path to keep working either.
 *
 * <p>v0.8.0.0-beta.4 made the material considerably richer without touching any of that. Depth comes
 * from layering light rather than from sampling what is behind the control: a multi-stop interior, a
 * directional specular sweep, a lit upper rim, a faint reflection at the foot and accent refraction
 * at the sides. All of it is built once per control and all of it holds still afterwards, so the
 * answer to "how does this look on an API 29 phone" is still "exactly like this".
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

    /**
     * The four shares the liquid layers are built from, at Orbit's own strength.
     *
     * <p>Added in v0.8.0.0-beta.4, when the material stopped being one translucent fill. The
     * complaint the four of them answer was specific and correct: the old treatment read as a
     * slightly see-through rounded rectangle, because that is all it was. A translucent fill is not
     * glass. Glass is a body you can see into, light pooling on the part of it facing the light, a
     * fainter bounce off the surface below it, and colour gathering where the material is thickest -
     * which on a rounded rectangle is at its sides.
     *
     * <p>Each is a share, held under a ceiling in {@link OrbitProStyle}, and driven by one of the
     * two controls that already existed rather than by three new ones. There is no new number a
     * person has to understand: Glass edge is how lit the material is, and Glass tint is how much of
     * the theme is in it.
     */
    static final float BASE_SPECULAR_ALPHA = 0.15f;
    /** The lit upper rim, where the light actually meets the curve. The brightest of the four. */
    static final float BASE_RIM_LIGHT_ALPHA = 0.24f;
    /** The bounce off whatever is beneath the glass. Present at the foot, and easy to miss. */
    static final float BASE_REFLECTION_ALPHA = 0.07f;
    /** How much accent gathers at the side edges, where a pane of glass is thickest. */
    static final float BASE_REFRACTION_ALPHA = 0.20f;
    /** How far the body's interior travels between its lit top and its settled foot. */
    static final float BASE_INTERIOR_LIFT = 0.13f;

    /**
     * Where the primary specular sweep stops, as a share of the surface's height.
     *
     * <p>Expressed as gradient stops rather than as a layer inset, because a stop is a fraction of
     * whatever the surface turns out to be and an inset is a number of pixels. The same material
     * therefore looks right on a 46dp selector and on a 52dp search field without either of them
     * telling it how tall they are.
     */
    private static final float[] SPECULAR_FALLOFF = {1f, 0.42f, 0.10f, 0f};
    /** The rim light, concentrated hard against the top edge and gone a fifth of the way down. */
    private static final float[] RIM_FALLOFF = {1f, 0.22f, 0f, 0f, 0f};
    /** The secondary reflection, rising from the foot. Read bottom-up. */
    private static final float[] REFLECTION_FALLOFF = {1f, 0.30f, 0f};
    /** Refraction across the width: strong at both sides, absent through the middle. */
    private static final float[] REFRACTION_ACROSS = {1f, 0.18f, 0f, 0f, 0.18f, 1f};
    /**
     * The body's own interior, top to foot, as shares of the lift between its two ends.
     *
     * <p>Six stops rather than two. {@code GradientDrawable} spaces its colours evenly, so the only
     * way to put the tonal turn where glass actually has one - a little above the middle, then a
     * longer settle towards the foot - is to name the intermediate values. This is what stops the
     * interior reading as a flat field at high opacity and as a plastic sheen at low.
     */
    private static final float[] INTERIOR_RAMP = {1f, 0.82f, 0.55f, 0.3f, 0.12f, 0f};
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

        /**
         * The same glass, read against a page that is not simply the theme's Background colour.
         *
         * <p>Added with advanced backgrounds, and it exists because {@link #effectiveFill} is the one
         * honest answer to "what is this label being read against" and a gradient or a glow changes
         * that answer. Without this, a readability check would go on measuring translucent glass
         * against the base colour while the brightest part of a glow sat behind it.
         */
        public static Palette over(OrbitThemeTokens tokens, OrbitProStyle style, int pageBehind) {
            if (tokens == null) {
                return new Palette(UiKit.DEFAULT_ACCENT, pageBehind, UiKit.SURFACE, style);
            }
            return new Palette(tokens.accent, pageBehind, tokens.surface, style);
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
     *
     * <p>Deliberately the body alone, with none of the light on top of it. Every one of those layers
     * lifts the surface towards white, so including them would raise the measured contrast of light
     * ink on a dark theme and the check would be reporting the brightest part of the control rather
     * than the dimmest. The conservative answer is the useful one.
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

    /**
     * A floating control's background: Liquid Orbit Glass.
     *
     * <p>Five layers, all of them {@code GradientDrawable}s, all at the same corner radius. That
     * count is the design rather than an accident of implementation, so it is worth naming what each
     * one is for.
     *
     * <ol>
     *   <li><b>The body.</b> Translucent, at {@code glassOpacity}, and no longer a two-stop fill:
     *       six stops carry the interior from a lit top through a turn a little above the middle to
     *       a foot that has settled back towards the page. That is what you are looking <em>into</em>.
     *   <li><b>Refraction.</b> The theme's accent gathered at the left and right edges and absent
     *       through the middle, which is where a pane of glass is thickest and where its colour
     *       actually shows. Driven by Glass tint.
     *   <li><b>The specular sweep.</b> White, from the upper left, falling away diagonally. One
     *       coherent light direction rather than a highlight on every edge.
     *   <li><b>The rim light.</b> The same light where it meets the top curve, held hard against
     *       that edge. This is the layer that reads as a surface rather than as an outline, which is
     *       why it is inset by the hairline instead of replacing it.
     *   <li><b>The reflection.</b> A faint bounce off whatever is beneath the glass, rising from the
     *       foot. Understated to the point where its absence is more noticeable than its presence.
     * </ol>
     *
     * <p>Only the body scales with opacity. The four layers above it are light on the surface, and
     * light does not get fainter because the material got thinner - that is precisely how the low end
     * of the opacity control stays unmistakably glass instead of fading towards nothing.
     *
     * <p>Still no blur, still nothing captured, still no API-level branch. Five hardware-accelerated
     * gradients cost what one did to within noise, they are built once when the control is built, and
     * they hold still for the life of it.
     */
    public static LayerDrawable surfaceDrawable(Context c, float radiusDp) {
        return surfaceDrawable(c, Palette.live(c), radiusDp);
    }

    public static LayerDrawable surfaceDrawable(Context c, Palette p, float radiusDp) {
        float radius = UiKit.dp(c, radiusDp);
        int hairline = UiKit.dp(c, BORDER_WIDTH_DP);

        GradientDrawable body = bodyDrawable(c, p, radiusDp);

        GradientDrawable refraction = layer(GradientDrawable.Orientation.LEFT_RIGHT, radius,
                ramp(p.accent, p.style.glassRefractionAlpha(), REFRACTION_ACROSS));
        GradientDrawable specular = layer(GradientDrawable.Orientation.TL_BR, radius,
                ramp(Color.WHITE, p.style.glassSpecularAlpha(), SPECULAR_FALLOFF));
        GradientDrawable rim = layer(GradientDrawable.Orientation.TOP_BOTTOM, radius,
                ramp(Color.WHITE, p.style.glassRimLightAlpha(), RIM_FALLOFF));
        GradientDrawable reflection = layer(GradientDrawable.Orientation.BOTTOM_TOP, radius,
                ramp(Color.WHITE, p.style.glassReflectionAlpha(), REFLECTION_FALLOFF));

        LayerDrawable glass = new LayerDrawable(
                new Drawable[]{body, refraction, specular, rim, reflection});
        // Everything above the body sits inside the hairline. Without this the rim light would run
        // along the outside of the stroke and the material would read as two concentric outlines,
        // which is the exact thing the old treatment was accused of being.
        for (int i = 1; i < glass.getNumberOfLayers(); i++) {
            glass.setLayerInset(i, hairline, hairline, hairline, hairline);
        }
        return glass;
    }

    public static LayerDrawable surfaceDrawable(Context c) {
        return surfaceDrawable(c, RADIUS_DP);
    }

    /**
     * The translucent body on its own, without the light on top of it.
     *
     * <p>Exposed because it is the layer that answers "what colour is this control", which is a
     * question contrast checks and tests genuinely need answered, and because pulling it back out of
     * a {@code LayerDrawable} by index at every call site would be a way for that index to be wrong
     * somewhere.
     */
    public static GradientDrawable bodyDrawable(Context c, Palette p, float radiusDp) {
        // Mixed opaque and given its alpha afterwards, in that order and deliberately.
        // UiKit.blend answers "what colour is a over b", which is a question about hue and has no
        // alpha in it, so blending two already-translucent colours returns an opaque one. Doing it
        // the other way round is how the body came out solid at every opacity setting.
        int top = fillTop(p);
        // The foot settles further back towards the page the more lit the material is, which is what
        // gives the interior somewhere to travel rather than a single flat field.
        int foot = UiKit.blend(fillBottom(p), p.background, 1f - p.style.glassInteriorLift());
        int[] interior = new int[INTERIOR_RAMP.length];
        for (int i = 0; i < INTERIOR_RAMP.length; i++) {
            interior[i] = UiKit.withAlpha(
                    UiKit.blend(top, foot, INTERIOR_RAMP[i]), p.style.glassOpacity);
        }
        GradientDrawable body = layer(GradientDrawable.Orientation.TOP_BOTTOM,
                UiKit.dp(c, radiusDp), interior);
        body.setStroke(UiKit.dp(c, BORDER_WIDTH_DP), borderColor(p));
        return body;
    }

    /** The body of a surface built by {@link #surfaceDrawable}, for anything that has one already. */
    public static GradientDrawable bodyOf(LayerDrawable glass) {
        return (GradientDrawable) glass.getDrawable(0);
    }

    private static GradientDrawable layer(GradientDrawable.Orientation orientation, float radius,
                                          int[] colors) {
        GradientDrawable layer = new GradientDrawable(orientation, colors);
        layer.setCornerRadius(radius);
        return layer;
    }

    /** One colour at a peak alpha, faded down a named set of stops. */
    private static int[] ramp(int color, float peak, float[] falloff) {
        int alpha = Math.round(Math.max(0f, Math.min(1f, peak)) * 255f);
        int[] colors = new int[falloff.length];
        for (int i = 0; i < falloff.length; i++) {
            colors[i] = UiKit.withAlpha(color, Math.round(alpha * falloff[i]));
        }
        return colors;
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

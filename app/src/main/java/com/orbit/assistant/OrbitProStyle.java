package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

/**
 * The advanced styling layer Orbit Pro adds on top of a theme.
 *
 * <p>Every free appearance decision Orbit has ever had lives on {@link OrbitTheme}: an accent, two
 * bubble colours, a surface, a background and AMOLED. None of them move here, and none of them
 * ever will. This is the second, smaller set of decisions that Orbit Pro unlocks, and it is
 * deliberately part of the theme rather than a scattering of unrelated preferences: a theme is one
 * complete appearance, it is saved as one object, exported as one file and applied in one act, and
 * a premium styling value that lived outside it would be none of those things.
 *
 * <h2>Defaults are the shipped appearance, exactly</h2>
 *
 * <p>Every field's default is the number Orbit already draws with. {@link #BUBBLE_RADIUS_DEFAULT}
 * is {@code UiKit.RADIUS_BUBBLE}, {@link #GLASS_OPACITY_DEFAULT} is {@code OrbitGlass.FILL_ALPHA},
 * and the two glass strengths are expressed as a percentage <em>of what Orbit already does</em>, so
 * 100 reproduces the shipped treatment rather than approximating it. A user who never touches any
 * of this cannot see a difference, which is the compatibility promise this release is held to.
 *
 * <h2>Entitlement is resolved here, not at the controls</h2>
 *
 * <p>{@link #resolve} is the one place a stored premium value becomes a drawn one, and it is the
 * only place {@link OrbitProEntitlement} is consulted for styling. Gating the Theme Studio controls
 * alone would leave three ways for premium rendering to happen without Pro: a tester switching
 * Pro Preview back to Free, a Beta being replaced by Stable with the values still stored, and a
 * theme file that carries premium fields being imported on a Free device. All three resolve to
 * {@link #DEFAULT} here, and none of them destroy anything - the stored values stay exactly where
 * they were and come back the moment entitlement does.
 */
public final class OrbitProStyle {

    // ---- bubble roundness ----------------------------------------------------------------------

    /**
     * How square a conversation bubble may be made.
     *
     * <p>Not zero, and that is a taste decision rather than a technical one. A sharp rectangle is
     * not a restrained Orbit bubble, it is a different app; the bottom of this range is a bubble
     * that reads as deliberately squared off while still belonging to Orbit.
     */
    public static final int BUBBLE_RADIUS_MIN = 8;
    /** And the top: noticeably rounded, still a bubble rather than a pill. */
    public static final int BUBBLE_RADIUS_MAX = 28;
    /** Orbit's own bubble corner, and therefore the value nobody can accidentally leave. */
    public static final int BUBBLE_RADIUS_DEFAULT = 18;

    // ---- bubble outline ------------------------------------------------------------------------

    /** No outline at all. What Orbit has always drawn, and the default. */
    public static final int OUTLINE_OFF = 0;
    /** An edge you notice only once you look for it. */
    public static final int OUTLINE_SUBTLE = 1;
    /** An edge that defines the bubble against its background. Still a hairline. */
    public static final int OUTLINE_DEFINED = 2;
    public static final int OUTLINE_DEFAULT = OUTLINE_OFF;

    // ---- glass ---------------------------------------------------------------------------------

    /**
     * How much of the page shows through Orbit's floating chrome.
     *
     * <p>Bounded on both sides for the same reason. Below the floor the controls stop being
     * readable against whatever happens to scroll under them; above the ceiling the treatment stops
     * reading as glass and becomes an opaque bar, which is the thing {@link OrbitGlass} exists to
     * replace.
     */
    public static final int GLASS_OPACITY_MIN = 170;
    public static final int GLASS_OPACITY_MAX = 245;
    /** {@code OrbitGlass.FILL_ALPHA}. Asserted equal to it by test, not merely intended to be. */
    public static final int GLASS_OPACITY_DEFAULT = 214;

    /**
     * How strongly the theme's accent colours the glass, as a percentage of Orbit's own treatment.
     *
     * <p>Zero is not "no glass", it is neutral glass: the surface still lifts off the page, it
     * simply carries no colour of its own. Two hundred is roughly twice Orbit's accent share, which
     * is still a tint on a translucent surface rather than a block of accent.
     */
    public static final int GLASS_TINT_MIN = 0;
    public static final int GLASS_TINT_MAX = 200;
    public static final int GLASS_TINT_DEFAULT = 100;

    /** How lit the top edge and hairline of the glass are, on the same percentage scale. */
    public static final int GLASS_EDGE_MIN = 0;
    public static final int GLASS_EDGE_MAX = 200;
    public static final int GLASS_EDGE_DEFAULT = 100;

    // ---- ceilings the percentages cannot pass ---------------------------------------------------

    /** The most accent the glass fill may carry, whatever the tint is set to. */
    static final float MAX_FILL_ACCENT_SHARE = 0.84f;
    /** The most accent the hairline may carry. */
    static final float MAX_BORDER_ACCENT_SHARE = 0.80f;
    /** The most accent the scrim's haze may carry. Past this it reads as a coloured band. */
    static final float MAX_HAZE_SHARE = 0.22f;
    /** The most light the top edge may take from the card surface. */
    static final float MAX_HIGHLIGHT_SHARE = 0.30f;
    /** The most opaque the hairline may become. */
    static final int MAX_BORDER_ALPHA = 150;

    // ---- the value ------------------------------------------------------------------------------

    public final int bubbleRadiusDp;
    public final int bubbleOutline;
    public final int glassOpacity;
    public final int glassTint;
    public final int glassEdge;

    /** Orbit exactly as it shipped. What Free resolves to, and what an older theme file becomes. */
    public static final OrbitProStyle DEFAULT = new OrbitProStyle(
            BUBBLE_RADIUS_DEFAULT, OUTLINE_DEFAULT,
            GLASS_OPACITY_DEFAULT, GLASS_TINT_DEFAULT, GLASS_EDGE_DEFAULT);

    private OrbitProStyle(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                          int glassTint, int glassEdge) {
        this.bubbleRadiusDp = clamp(bubbleRadiusDp, BUBBLE_RADIUS_MIN, BUBBLE_RADIUS_MAX);
        this.bubbleOutline = clamp(bubbleOutline, OUTLINE_OFF, OUTLINE_DEFINED);
        this.glassOpacity = clamp(glassOpacity, GLASS_OPACITY_MIN, GLASS_OPACITY_MAX);
        this.glassTint = clamp(glassTint, GLASS_TINT_MIN, GLASS_TINT_MAX);
        this.glassEdge = clamp(glassEdge, GLASS_EDGE_MIN, GLASS_EDGE_MAX);
    }

    /** Always valid: every value out of range is pulled inside it rather than refused. */
    public static OrbitProStyle of(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                                   int glassTint, int glassEdge) {
        return new OrbitProStyle(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge);
    }

    public OrbitProStyle withBubbleRadiusDp(int value) {
        return of(value, bubbleOutline, glassOpacity, glassTint, glassEdge);
    }

    public OrbitProStyle withBubbleOutline(int value) {
        return of(bubbleRadiusDp, value, glassOpacity, glassTint, glassEdge);
    }

    public OrbitProStyle withGlassOpacity(int value) {
        return of(bubbleRadiusDp, bubbleOutline, value, glassTint, glassEdge);
    }

    public OrbitProStyle withGlassTint(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, value, glassEdge);
    }

    public OrbitProStyle withGlassEdge(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, value);
    }

    /** True when this asks for nothing beyond the appearance Orbit ships to everybody. */
    public boolean isDefault() {
        return same(DEFAULT);
    }

    public boolean same(OrbitProStyle other) {
        return other != null
                && bubbleRadiusDp == other.bubbleRadiusDp
                && bubbleOutline == other.bubbleOutline
                && glassOpacity == other.glassOpacity
                && glassTint == other.glassTint
                && glassEdge == other.glassEdge;
    }

    // ---- entitlement -----------------------------------------------------------------------------

    /**
     * The styling Orbit is actually allowed to draw with right now.
     *
     * <p>Asked fresh every time. {@link OrbitProEntitlement#hasPro} reads live state and costs
     * nothing, and caching the answer for the life of the process is precisely what would leave a
     * tester's Pro Preview switch appearing to do nothing until they killed the app.
     *
     * <p>The stored value is returned untouched, never rewritten, when entitlement is absent - this
     * returns {@link #DEFAULT} for drawing and leaves storage alone.
     */
    public static OrbitProStyle resolve(Context c, OrbitProStyle stored) {
        if (stored == null) return DEFAULT;
        return OrbitProEntitlement.hasPro(c) ? stored : DEFAULT;
    }

    /** The effective styling of the theme Orbit is currently drawing with. */
    public static OrbitProStyle live(Context c) {
        if (c == null) return DEFAULT;
        return resolve(c, OrbitThemeStore.activeProStyle(c));
    }

    // ---- bubble derivations ------------------------------------------------------------------------

    /** The hairline width a bubble outline is drawn at, in pixels. Zero when it is off. */
    public int bubbleOutlineWidthPx(Context c) {
        if (c == null || bubbleOutline == OUTLINE_OFF) return 0;
        return Math.max(1, UiKit.dp(c, bubbleOutline == OUTLINE_DEFINED ? 1.5f : 1f));
    }

    /** How opaque the outline is drawn. */
    public int bubbleOutlineAlpha() {
        if (bubbleOutline == OUTLINE_DEFINED) return 112;
        if (bubbleOutline == OUTLINE_SUBTLE) return 54;
        return 0;
    }

    /**
     * The colour a bubble outline is drawn in, derived rather than chosen.
     *
     * <p>Two things have to be true at once. It has to carry the theme, or an outline is just a
     * grey rectangle around every message; and it has to be visible against the bubble it borders,
     * whichever way round the two are. So the accent is pulled towards whatever ink that bubble
     * already reads with - light ink on a dark bubble, dark ink on a light one - which keeps the
     * accent recognisable and keeps the edge from vanishing on either.
     *
     * <p>There is deliberately no separate outline colour picker. An outline is a property of the
     * theme, and a theme with an unrelated outline colour bolted onto it is not a theme.
     */
    public int bubbleOutlineColor(int accent, int fill) {
        int ink = UiKit.onBubble(fill);
        return UiKit.withAlpha(UiKit.blend(accent, ink, 0.62f), bubbleOutlineAlpha());
    }

    // ---- glass derivations -------------------------------------------------------------------------

    private float tintFactor() { return glassTint / 100f; }

    private float edgeFactor() { return glassEdge / 100f; }

    /** How much accent is in the lit top of the glass. Orbit's own share at tint 100. */
    public float glassFillAccentShare() {
        return Math.min(MAX_FILL_ACCENT_SHARE, OrbitGlass.BASE_FILL_ACCENT_SHARE * tintFactor());
    }

    /** How much accent is in the hairline. */
    public float glassBorderAccentShare() {
        return Math.min(MAX_BORDER_ACCENT_SHARE, OrbitGlass.BASE_BORDER_ACCENT_SHARE * tintFactor());
    }

    /** How much accent is in the scrim's haze. */
    public float glassHazeShare() {
        return Math.min(MAX_HAZE_SHARE, OrbitGlass.BASE_HAZE_SHARE * tintFactor());
    }

    /** How much light the top edge of the glass carries. */
    public float glassHighlightShare() {
        return Math.min(MAX_HIGHLIGHT_SHARE, OrbitGlass.BASE_HIGHLIGHT_SHARE * edgeFactor());
    }

    /** How opaque the hairline around a floating control is. */
    public int glassBorderAlpha() {
        return clamp(Math.round(OrbitGlass.BASE_BORDER_ALPHA * edgeFactor()), 0, MAX_BORDER_ALPHA);
    }

    // ---- how each value reads to a person ------------------------------------------------------------

    public String bubbleRadiusLabel() {
        if (bubbleRadiusDp <= 11) return "Squared";
        if (bubbleRadiusDp <= 15) return "Restrained";
        if (bubbleRadiusDp == BUBBLE_RADIUS_DEFAULT) return "Orbit default";
        if (bubbleRadiusDp <= 22) return "Soft";
        return "Rounded";
    }

    public static String outlineLabel(int value) {
        if (value == OUTLINE_DEFINED) return "Defined";
        if (value == OUTLINE_SUBTLE) return "Subtle";
        return "Off";
    }

    public String bubbleOutlineLabel() {
        return outlineLabel(bubbleOutline);
    }

    public String glassOpacityLabel() {
        if (glassOpacity == GLASS_OPACITY_DEFAULT) return "Orbit default";
        return glassOpacity < GLASS_OPACITY_DEFAULT ? "More see-through" : "More solid";
    }

    /** Shared by tint and edge, which are the same percentage scale with the same three landmarks. */
    public static String strengthLabel(int value) {
        if (value == 100) return "Orbit default";
        if (value == 0) return "Neutral";
        return value < 100 ? "Subtle" : "Stronger";
    }

    public String glassTintLabel() { return strengthLabel(glassTint); }

    public String glassEdgeLabel() { return strengthLabel(glassEdge); }

    // ---- serialisation ----------------------------------------------------------------------------

    /**
     * The premium block written inside a theme document.
     *
     * <p>A nested object rather than five loose keys, so an older Orbit reading a newer file sees
     * one key it does not know instead of five, and so {@link #fromJson} can tell "this file
     * predates premium styling" from "this file says premium styling is at its defaults". Both
     * produce {@link #DEFAULT}; only one of them is a file that could have said otherwise.
     */
    public JSONObject toJson() throws Exception {
        JSONObject out = new JSONObject();
        out.put("bubbleRadius", bubbleRadiusDp);
        out.put("bubbleOutline", bubbleOutline);
        out.put("glassOpacity", glassOpacity);
        out.put("glassTint", glassTint);
        out.put("glassEdge", glassEdge);
        return out;
    }

    /**
     * Reads a premium block, or Orbit's defaults when there is not one.
     *
     * <p>Every missing key falls back to the shipped value individually, so a file written by a
     * build that knew about four of these and not the fifth still imports as the appearance its
     * author saw, plus Orbit's own default for the one it could not have set.
     */
    public static OrbitProStyle fromJson(JSONObject json) {
        if (json == null) return DEFAULT;
        return of(
                json.optInt("bubbleRadius", BUBBLE_RADIUS_DEFAULT),
                json.optInt("bubbleOutline", OUTLINE_DEFAULT),
                json.optInt("glassOpacity", GLASS_OPACITY_DEFAULT),
                json.optInt("glassTint", GLASS_TINT_DEFAULT),
                json.optInt("glassEdge", GLASS_EDGE_DEFAULT));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}

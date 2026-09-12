package com.orbit.assistant;

import android.content.Context;

import org.json.JSONObject;

/**
 * The advanced styling layer Orbit Pro adds on top of a theme.
 *
 * <p>Every free appearance decision Orbit has ever had lives on {@link OrbitTheme}: an accent, two
 * bubble colours, a surface, a background and AMOLED. None of them move here, and none of them
 * ever will - including the Background colour, which advanced backgrounds build on rather than
 * replace: Linear runs the free colour into a premium one and Glow lays light over it, so the free
 * control is still the thing that decides what colour the page is. This is the second, smaller set
 * of decisions that Orbit Pro unlocks, and it is
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
     *
     * <p>The floor dropped from 170 to 130 in v0.8.0.0-beta.4, and that is the whole of the
     * migration for this control. A stored value from an earlier Beta is inside the new range and
     * means precisely what it meant before; what changed is that there is now somewhere below it to
     * go. At 170 the page behind the glass was influencing about two thirds of what you saw, which
     * is why the minimum never really read as translucent - the control had a floor, not a low end.
     * The four light layers above the body do not scale with this at all, so thin glass is thin and
     * still unmistakably glass.
     */
    public static final int GLASS_OPACITY_MIN = 130;
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

    // ---- the page's own background -------------------------------------------------------------

    /**
     * No premium background effect. The page is the theme's Background color and nothing else.
     *
     * <p>Zero deliberately, and the default, because it is the only value that reproduces the
     * release before this one. Orbit's free Background color control is untouched by any of this:
     * it still chooses the color of the page, and these modes decide what is layered over it.
     */
    public static final int BACKGROUND_SOLID = 0;
    /** The theme's Background color running into one premium color, in a chosen direction. */
    public static final int BACKGROUND_LINEAR = 1;
    /** A diffused radial light laid over the theme's Background color. */
    public static final int BACKGROUND_GLOW = 2;
    public static final int BACKGROUND_MODE_DEFAULT = BACKGROUND_SOLID;

    /**
     * The one premium color the advanced background effects are built from.
     *
     * <p>A token in exactly the vocabulary every other Orbit color uses - {@code accent}, a named
     * palette entry, or {@code #RRGGBB} - so the existing picker chooses it and the existing
     * normaliser validates it. {@code accent} rather than a hex default, because an effect that
     * follows the theme's own accent is the one starting point that looks deliberate in every theme.
     */
    public static final String EFFECT_COLOR_DEFAULT = OrbitTheme.ACCENT;

    /**
     * The eight directions a linear background may run, in {@code GradientDrawable.Orientation}
     * order.
     *
     * <p>Stored as this index rather than as the platform enum's {@code name()}. The stored value
     * has to go on meaning the same thing whatever Android calls it, and a theme file written by one
     * build has to be read the same way by another.
     */
    public static final int DIRECTION_TOP_BOTTOM = 0;
    public static final int DIRECTION_TR_BL = 1;
    public static final int DIRECTION_RIGHT_LEFT = 2;
    public static final int DIRECTION_BR_TL = 3;
    public static final int DIRECTION_BOTTOM_TOP = 4;
    public static final int DIRECTION_BL_TR = 5;
    public static final int DIRECTION_LEFT_RIGHT = 6;
    public static final int DIRECTION_TL_BR = 7;
    public static final int DIRECTION_COUNT = 8;
    /** Light from above, which is the direction every other Orbit surface is already lit from. */
    public static final int GRADIENT_DIRECTION_DEFAULT = DIRECTION_TOP_BOTTOM;

    /**
     * How strong the radial glow is.
     *
     * <p>Floored above zero, because a mode that draws nothing is already
     * {@link #BACKGROUND_SOLID}, and having two ways to say that is how somebody ends up with Glow
     * selected and no glow. Ceilinged well below opaque: the glow is illumination laid over the
     * page, and the page is what Orbit derives its text readability from.
     */
    public static final int GLOW_STRENGTH_MIN = 10;
    public static final int GLOW_STRENGTH_MAX = 100;
    public static final int GLOW_STRENGTH_DEFAULT = 45;

    /**
     * How far a linear background travels from the theme's Background colour towards the effect one.
     *
     * <p>Added in v0.8.0.0-beta.5 because the Beta 4 control was all or nothing: a linear background
     * ran the whole way to the premium colour, which is the right maximum and a poor only option.
     * Most pages want a colour cast rather than a two-colour sweep.
     *
     * <p>The default is the maximum, and that is a compatibility decision rather than a taste one. A
     * theme written by Beta 4 has no such key, so it has to read back as the full gradient its author
     * chose; anything else would quietly weaken every gradient a tester has already built.
     */
    public static final int GRADIENT_STRENGTH_MIN = 15;
    public static final int GRADIENT_STRENGTH_MAX = 100;
    public static final int GRADIENT_STRENGTH_DEFAULT = GRADIENT_STRENGTH_MAX;

    /** How far the glow spreads, as a share of the longer edge of the page. */
    public static final int GLOW_SIZE_MIN = 25;
    public static final int GLOW_SIZE_MAX = 100;
    public static final int GLOW_SIZE_DEFAULT = 60;

    /** Where the light comes from. Three answers, because a page has a top, a middle and a foot. */
    public static final int GLOW_TOP = 0;
    public static final int GLOW_CENTER = 1;
    public static final int GLOW_BOTTOM = 2;
    public static final int GLOW_POSITION_DEFAULT = GLOW_TOP;

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

    // ---- ceilings on the liquid layers ----------------------------------------------------------
    //
    // The Liquid Orbit Glass material is five layers rather than one fill, and each of the four
    // above the body is bounded here for the same reason the older shares are: Glass edge reaching
    // its maximum has to read as reflective material catching light, and there is a point past
    // which every one of these stops doing that and becomes a sticker instead.

    /** The most white the primary specular sweep may carry at its brightest point. */
    static final float MAX_SPECULAR_ALPHA = 0.34f;
    /** The most white the lit upper rim may carry. Past this it is an outline, not a highlight. */
    static final float MAX_RIM_LIGHT_ALPHA = 0.44f;
    /** The most white the secondary reflection near the foot may carry. Understated by design. */
    static final float MAX_REFLECTION_ALPHA = 0.15f;
    /** The most accent that may pool at the side edges as refraction. */
    static final float MAX_REFRACTION_ALPHA = 0.40f;
    /** The most the body's interior may vary from top to foot. Depth, never a glossy stripe. */
    static final float MAX_INTERIOR_LIFT = 0.22f;

    /**
     * The most of the page's own light the glow may add, at {@link #GLOW_STRENGTH_MAX}.
     *
     * <p>Just over half. A page whose decoration could reach full opacity would be a page whose
     * text colour no longer follows from the theme's Background color, and that is the one thing
     * advanced backgrounds are not allowed to take away.
     */
    static final float MAX_GLOW_ALPHA = 0.55f;
    /** The narrowest and widest the glow's falloff may be, as a share of the page's longer edge. */
    static final float MIN_GLOW_RADIUS_SHARE = 0.38f;
    static final float MAX_GLOW_RADIUS_SHARE = 1.30f;

    // ---- the value ------------------------------------------------------------------------------

    public final int bubbleRadiusDp;
    public final int bubbleOutline;
    public final int glassOpacity;
    public final int glassTint;
    public final int glassEdge;
    public final int backgroundMode;
    /** A colour token, in Orbit's existing vocabulary. Never a resolved colour. */
    public final String backgroundEffectColor;
    public final int gradientDirection;
    public final int glowStrength;
    public final int glowSize;
    public final int glowPosition;
    public final int gradientStrength;

    /** Orbit exactly as it shipped. What Free resolves to, and what an older theme file becomes. */
    public static final OrbitProStyle DEFAULT = new OrbitProStyle(
            BUBBLE_RADIUS_DEFAULT, OUTLINE_DEFAULT,
            GLASS_OPACITY_DEFAULT, GLASS_TINT_DEFAULT, GLASS_EDGE_DEFAULT,
            BACKGROUND_MODE_DEFAULT, EFFECT_COLOR_DEFAULT, GRADIENT_DIRECTION_DEFAULT,
            GLOW_STRENGTH_DEFAULT, GLOW_SIZE_DEFAULT, GLOW_POSITION_DEFAULT,
            GRADIENT_STRENGTH_DEFAULT);

    private OrbitProStyle(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                          int glassTint, int glassEdge, int backgroundMode,
                          String backgroundEffectColor, int gradientDirection,
                          int glowStrength, int glowSize, int glowPosition,
                          int gradientStrength) {
        this.bubbleRadiusDp = clamp(bubbleRadiusDp, BUBBLE_RADIUS_MIN, BUBBLE_RADIUS_MAX);
        this.bubbleOutline = clamp(bubbleOutline, OUTLINE_OFF, OUTLINE_DEFINED);
        this.glassOpacity = clamp(glassOpacity, GLASS_OPACITY_MIN, GLASS_OPACITY_MAX);
        this.glassTint = clamp(glassTint, GLASS_TINT_MIN, GLASS_TINT_MAX);
        this.glassEdge = clamp(glassEdge, GLASS_EDGE_MIN, GLASS_EDGE_MAX);
        this.backgroundMode = clamp(backgroundMode, BACKGROUND_SOLID, BACKGROUND_GLOW);
        this.backgroundEffectColor = normalizeEffectColor(backgroundEffectColor);
        this.gradientDirection = clamp(gradientDirection, 0, DIRECTION_COUNT - 1);
        this.glowStrength = clamp(glowStrength, GLOW_STRENGTH_MIN, GLOW_STRENGTH_MAX);
        this.glowSize = clamp(glowSize, GLOW_SIZE_MIN, GLOW_SIZE_MAX);
        this.glowPosition = clamp(glowPosition, GLOW_TOP, GLOW_BOTTOM);
        this.gradientStrength =
                clamp(gradientStrength, GRADIENT_STRENGTH_MIN, GRADIENT_STRENGTH_MAX);
    }

    /**
     * The five styling values that existed before advanced backgrounds, at background defaults.
     *
     * <p>Kept as the short way in rather than widened to eleven parameters. Every caller that has
     * one of these is describing message and glass styling, and the background fields it does not
     * mention are the ones that reproduce the previous release - so the shorter call is also the
     * more honest one. Anything that wants a background says so with {@link #withBackgroundMode}
     * and the builders beside it.
     */
    public static OrbitProStyle of(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                                   int glassTint, int glassEdge) {
        return new OrbitProStyle(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge,
                BACKGROUND_MODE_DEFAULT, EFFECT_COLOR_DEFAULT, GRADIENT_DIRECTION_DEFAULT,
                GLOW_STRENGTH_DEFAULT, GLOW_SIZE_DEFAULT, GLOW_POSITION_DEFAULT,
                GRADIENT_STRENGTH_DEFAULT);
    }

    /**
     * The eleven values that existed before gradient strength, at its default.
     *
     * <p>Kept so that a caller describing a Beta 4 appearance can still say so in one call and get the
     * Beta 4 result, which is a full-strength gradient.
     */
    public static OrbitProStyle of(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                                   int glassTint, int glassEdge, int backgroundMode,
                                   String backgroundEffectColor, int gradientDirection,
                                   int glowStrength, int glowSize, int glowPosition) {
        return new OrbitProStyle(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge,
                backgroundMode, backgroundEffectColor, gradientDirection,
                glowStrength, glowSize, glowPosition, GRADIENT_STRENGTH_DEFAULT);
    }

    /** Every value at once. Used by storage and by the theme file codec, which have all of them. */
    public static OrbitProStyle of(int bubbleRadiusDp, int bubbleOutline, int glassOpacity,
                                   int glassTint, int glassEdge, int backgroundMode,
                                   String backgroundEffectColor, int gradientDirection,
                                   int glowStrength, int glowSize, int glowPosition,
                                   int gradientStrength) {
        return new OrbitProStyle(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge,
                backgroundMode, backgroundEffectColor, gradientDirection,
                glowStrength, glowSize, glowPosition, gradientStrength);
    }

    public OrbitProStyle withBubbleRadiusDp(int value) {
        return of(value, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withBubbleOutline(int value) {
        return of(bubbleRadiusDp, value, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlassOpacity(int value) {
        return of(bubbleRadiusDp, bubbleOutline, value, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlassTint(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, value, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlassEdge(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, value, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withBackgroundMode(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, value,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withBackgroundEffectColor(String value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                value, gradientDirection, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGradientDirection(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, value, glowStrength, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlowStrength(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, value, glowSize,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlowSize(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, value,
                glowPosition, gradientStrength);
    }

    public OrbitProStyle withGlowPosition(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                value, gradientStrength);
    }

    public OrbitProStyle withGradientStrength(int value) {
        return of(bubbleRadiusDp, bubbleOutline, glassOpacity, glassTint, glassEdge, backgroundMode,
                backgroundEffectColor, gradientDirection, glowStrength, glowSize,
                glowPosition, value);
    }

    /**
     * A colour token Orbit recognises, or the accent when it is not one.
     *
     * <p>Deliberately the same rule the theme's own colour fields are held to. A stored value that
     * cannot be resolved is not a reason to draw nothing, and it is certainly not a reason to throw:
     * it falls back to the accent, which is a background effect that still looks like the theme.
     */
    private static String normalizeEffectColor(String value) {
        if (value == null) return EFFECT_COLOR_DEFAULT;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return EFFECT_COLOR_DEFAULT;
        if (OrbitTheme.ACCENT.equals(trimmed)) return OrbitTheme.ACCENT;
        String hex = OrbitTheme.parseHexToken(trimmed);
        if (hex != null) return hex;
        for (String key : OrbitPalette.accentKeys()) {
            if (key.equals(trimmed)) return key;
        }
        return EFFECT_COLOR_DEFAULT;
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
                && glassEdge == other.glassEdge
                && backgroundMode == other.backgroundMode
                && backgroundEffectColor.equals(other.backgroundEffectColor)
                && gradientDirection == other.gradientDirection
                && glowStrength == other.glowStrength
                && glowSize == other.glowSize
                && glowPosition == other.glowPosition
                && gradientStrength == other.gradientStrength;
    }

    /** True when this theme asks for a background effect at all, entitlement aside. */
    public boolean hasBackgroundEffect() {
        return backgroundMode != BACKGROUND_SOLID;
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

    // ---- the liquid layers -------------------------------------------------------------------------
    //
    // Four of these, and between them they are the difference between a translucent rounded
    // rectangle and glass. Each is a share of white or of accent, each is driven by one of the two
    // existing controls, and each is bounded.
    //
    // Edge drives the three light layers rather than the hairline alone, which is what finally makes
    // that control worth having: at its minimum the material is nearly flat, at its default it is
    // polished, and at its maximum light is visibly pooling on a curved surface. Tint drives the
    // refraction, so the accent shows up at the edges of the glass the way colour shows up at the
    // edges of a real pane rather than as a wash across the whole face of it.

    /**
     * The brightest point of the primary specular sweep, as a share of white.
     *
     * <p>Floored above zero on purpose. Edge at zero means subdued, not absent: a surface with no
     * light on it at all stops being glass, and there is no setting in Orbit that should turn a
     * material into a flat fill.
     */
    public float glassSpecularAlpha() {
        return bounded(OrbitGlass.BASE_SPECULAR_ALPHA, MAX_SPECULAR_ALPHA, edgeFactor(), 0.30f);
    }

    /** The lit upper rim: the light that catches the top curve of the glass. */
    public float glassRimLightAlpha() {
        return bounded(OrbitGlass.BASE_RIM_LIGHT_ALPHA, MAX_RIM_LIGHT_ALPHA, edgeFactor(), 0.26f);
    }

    /** The secondary reflection near the foot. Understated, and the first thing to go. */
    public float glassReflectionAlpha() {
        return bounded(OrbitGlass.BASE_REFLECTION_ALPHA, MAX_REFLECTION_ALPHA, edgeFactor(), 0f);
    }

    /**
     * How much accent pools at the side edges of the glass, as refraction rather than tinting.
     *
     * <p>Zero at tint zero, which is the whole point of neutral glass: the material still lifts off
     * the page and still catches light, it simply has no colour of its own anywhere in it.
     */
    public float glassRefractionAlpha() {
        return Math.min(MAX_REFRACTION_ALPHA, OrbitGlass.BASE_REFRACTION_ALPHA * tintFactor());
    }

    /**
     * How far the body's interior varies between its lit top and its settled foot.
     *
     * <p>This is what stops the fill being one flat field at any opacity. Only half-driven by Edge,
     * because interior depth is a property of the material rather than of how brightly it is lit.
     */
    public float glassInteriorLift() {
        return Math.min(MAX_INTERIOR_LIFT,
                OrbitGlass.BASE_INTERIOR_LIFT * (0.55f + 0.45f * edgeFactor()));
    }

    /** A share scaled by one control, held under a ceiling, and never allowed below its floor. */
    private static float bounded(float base, float ceiling, float factor, float floorShare) {
        return Math.max(base * floorShare, Math.min(ceiling, base * factor));
    }

    // ---- background derivations ---------------------------------------------------------------------

    /**
     * The premium effect colour, resolved against the theme it belongs to.
     *
     * <p>Takes the theme's accent rather than the live one, so the Theme Studio previews resolve a
     * draft's effect colour exactly as an applied theme would.
     */
    public int backgroundEffectColor(Context c, int themeAccent) {
        if (OrbitTheme.ACCENT.equals(backgroundEffectColor)) return themeAccent;
        if (OrbitTheme.isHexToken(backgroundEffectColor)) {
            return OrbitTheme.hexTokenColor(backgroundEffectColor);
        }
        return UiKit.accentForName(c, backgroundEffectColor);
    }

    /**
     * The colour the far end of a linear background actually reaches.
     *
     * <p>Strength is expressed as how far the effect colour travels from the base rather than as an
     * opacity, and that distinction is the whole reason this control is worth having. Fading a finished
     * gradient towards transparent would put the window behind the page into it, would disagree with
     * the system bars, and would make a low setting look like a mistake. Pulling the far endpoint back
     * towards the theme's Background colour instead keeps the page a solid, coherent thing at every
     * setting: low is a colour cast on the user's own background, high is the two-colour sweep.
     */
    public int gradientEndColor(Context c, int themeAccent, int base) {
        int effect = backgroundEffectColor(c, themeAccent);
        return UiKit.blend(effect, base, gradientStrength / (float) GRADIENT_STRENGTH_MAX);
    }

    /** How opaque the radial glow is at its centre. Bounded, so the page stays readable. */
    public float glowAlpha() {
        return MAX_GLOW_ALPHA * (glowStrength / (float) GLOW_STRENGTH_MAX);
    }

    /** How far the glow reaches, as a share of the longer edge of whatever it is drawn into. */
    public float glowRadiusShare() {
        float span = (glowSize - GLOW_SIZE_MIN) / (float) (GLOW_SIZE_MAX - GLOW_SIZE_MIN);
        return MIN_GLOW_RADIUS_SHARE + span * (MAX_GLOW_RADIUS_SHARE - MIN_GLOW_RADIUS_SHARE);
    }

    /**
     * Where the centre of the glow sits down the page, as a fraction of its height.
     *
     * <p>A fraction rather than a pixel coordinate, so one stored theme is correct on a phone in
     * portrait, on a tablet in landscape and across a rotation. Top and bottom are pulled inside the
     * page rather than sitting on its edge, which is the difference between light coming from
     * somewhere and half a circle stuck to a border.
     */
    public float glowCenterY() {
        if (glowPosition == GLOW_BOTTOM) return 0.82f;
        if (glowPosition == GLOW_CENTER) return 0.5f;
        return 0.18f;
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

    /** What each background mode is called. The control shows these; storage never does. */
    public static String backgroundModeLabel(int mode) {
        if (mode == BACKGROUND_LINEAR) return "Linear";
        if (mode == BACKGROUND_GLOW) return "Glow";
        return "Solid";
    }

    public String backgroundModeLabel() {
        return backgroundModeLabel(backgroundMode);
    }

    /**
     * The eight directions, in words a person can match to what they are seeing.
     *
     * <p>Named by where the light starts rather than by an angle, because "Top left" is something
     * you can look at the preview and verify and "135 degrees" is not.
     */
    public static String directionLabel(int direction) {
        switch (direction) {
            case DIRECTION_TR_BL: return "Top right";
            case DIRECTION_RIGHT_LEFT: return "Right";
            case DIRECTION_BR_TL: return "Bottom right";
            case DIRECTION_BOTTOM_TOP: return "Bottom";
            case DIRECTION_BL_TR: return "Bottom left";
            case DIRECTION_LEFT_RIGHT: return "Left";
            case DIRECTION_TL_BR: return "Top left";
            default: return "Top";
        }
    }

    public String directionLabel() {
        return directionLabel(gradientDirection);
    }

    public static String glowPositionLabel(int position) {
        if (position == GLOW_CENTER) return "Center";
        if (position == GLOW_BOTTOM) return "Bottom";
        return "Top";
    }

    public String glowPositionLabel() {
        return glowPositionLabel(glowPosition);
    }

    public static String glowStrengthLabel(int value) {
        if (value <= 20) return "Faint";
        if (value <= 40) return "Subtle";
        if (value == GLOW_STRENGTH_DEFAULT) return "Orbit default";
        if (value <= 70) return "Clear";
        return "Dramatic";
    }

    public String glowStrengthLabel() {
        return glowStrengthLabel(glowStrength);
    }

    public static String gradientStrengthLabel(int value) {
        if (value == GRADIENT_STRENGTH_MAX) return "Full";
        if (value <= 30) return "Whisper";
        if (value <= 55) return "Subtle";
        return "Balanced";
    }

    public String gradientStrengthLabel() {
        return gradientStrengthLabel(gradientStrength);
    }

    public static String glowSizeLabel(int value) {
        if (value <= 35) return "Focused";
        if (value == GLOW_SIZE_DEFAULT) return "Orbit default";
        if (value <= 75) return "Wide";
        return "Atmospheric";
    }

    public String glowSizeLabel() {
        return glowSizeLabel(glowSize);
    }

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
        // Added in v0.8.0.0-beta.4, additively rather than behind a schema bump. Every one of these
        // keys has a default that reproduces the release before it, so a file that predates them is
        // read as a theme whose author never asked for a background effect - which is exactly what
        // they were. That is what keeps the theme document at schema 2 and keeps every Beta 1, 2 and
        // 3 file importable without a migration step.
        out.put("backgroundMode", backgroundMode);
        out.put("backgroundEffectColor", backgroundEffectColor);
        out.put("gradientDirection", gradientDirection);
        out.put("glowStrength", glowStrength);
        out.put("glowSize", glowSize);
        out.put("glowPosition", glowPosition);
        // Added in v0.8.0.0-beta.5, additively like the six before it. Absent in a Beta 4 file, where
        // it reads back as the maximum, which is the gradient that file's author actually saw.
        out.put("gradientStrength", gradientStrength);
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
                json.optInt("glassEdge", GLASS_EDGE_DEFAULT),
                json.optInt("backgroundMode", BACKGROUND_MODE_DEFAULT),
                json.optString("backgroundEffectColor", EFFECT_COLOR_DEFAULT),
                json.optInt("gradientDirection", GRADIENT_DIRECTION_DEFAULT),
                json.optInt("glowStrength", GLOW_STRENGTH_DEFAULT),
                json.optInt("glowSize", GLOW_SIZE_DEFAULT),
                json.optInt("glowPosition", GLOW_POSITION_DEFAULT),
                json.optInt("gradientStrength", GRADIENT_STRENGTH_DEFAULT));
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }
}

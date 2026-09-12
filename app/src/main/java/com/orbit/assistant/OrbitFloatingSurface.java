package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

/**
 * What Orbit's floating controls are made of, decided in one place.
 *
 * <p>Until v0.8.0.0-beta.5 that question had one answer, so it did not need a class: every floating
 * control was glass and {@link OrbitGlass} drew it. Physical testing on a Galaxy S25 Ultra made the
 * case for a choice. Glass is right for some themes and wrong for others, the same treatment that
 * looks considered on a compact pill can look like polished metal on a full-width search field, and a
 * person who simply wants clean chrome had no way to ask for it.
 *
 * <p>So there are three materials now, and this is the only thing that knows which one is in force:
 *
 * <ul>
 *   <li>{@link OrbitTheme#MATERIAL_SOLID} is not glass. An opaque themed surface with a hairline and a
 *       little tonal depth, which is what Orbit's floating chrome looked like before any of this.
 *   <li>{@link OrbitTheme#MATERIAL_FROSTED} is soft translucent glass, drawn by {@code OrbitGlass}.
 *   <li>{@link OrbitTheme#MATERIAL_LIQUID} is the richer dimensional glass, also {@code OrbitGlass}.
 * </ul>
 *
 * <h2>The material is free. The tuning is not.</h2>
 *
 * <p>Orbit had floating glass before Orbit Pro existed, so the glass cannot move behind an entitlement
 * without taking away something that already shipped free. Choosing the material is therefore free for
 * everyone and lives on {@link OrbitTheme}; the advanced opacity, tint and edge controls stay in
 * {@link OrbitProStyle}, which resolves to its defaults on a Free device. A Free person gets all three
 * materials at Orbit's own settings, which is a complete feature rather than a teaser.
 *
 * <h2>One switch, not forty</h2>
 *
 * <p>Screens call {@link #floatControl}, {@link #surfaceDrawable} or {@link #interactive} and never
 * learn that a material exists. The alternative - each screen asking which material is selected and
 * assembling the matching drawable - is the shape of the bug this class exists to prevent, because it
 * only has to be got wrong on one screen to be wrong for good.
 */
public final class OrbitFloatingSurface {

    private OrbitFloatingSurface() {}

    /**
     * How far a Solid surface's interior travels from its lit top to its foot.
     *
     * <p>Small, and present at all. Solid is meant to read as a clean surface rather than a flat
     * rectangle, and a floating control with no tonal depth at all looks like a stock Android view
     * dropped onto an Orbit page. This is one step of Orbit's own card ramp, not a highlight.
     */
    private static final float SOLID_DEPTH_SHARE = 0.55f;

    // ---- the surfaces ------------------------------------------------------------------------------

    /** The background of a floating control, in whatever material the live theme asks for. */
    public static Drawable surfaceDrawable(Context c, float radiusDp) {
        return surfaceDrawable(c, OrbitGlass.Palette.live(c), radiusDp);
    }

    public static Drawable surfaceDrawable(Context c) {
        return surfaceDrawable(c, OrbitGlass.RADIUS_DP);
    }

    /** The same, for a resolved theme that is not applied. What the Theme Studio samples draw. */
    public static Drawable surfaceDrawable(Context c, OrbitGlass.Palette p, float radiusDp) {
        if (OrbitTheme.MATERIAL_SOLID.equals(p.material())) return solidDrawable(c, p, radiusDp);
        return OrbitGlass.surfaceDrawable(c, p, radiusDp);
    }

    /**
     * Solid: an opaque Orbit surface that is plainly floating over the page.
     *
     * <p>No translucency, no glint, no refraction and no haze, because Solid exists precisely for
     * people who do not want those. What it does have is Orbit's corner, one step of tonal depth so it
     * reads as a surface rather than a fill, and the same accent hairline every other floating control
     * has - which between them are what stop it from looking like an unstyled panel.
     *
     * <p>The lit end is the card ramp's next step up rather than a mix with white. A floating control
     * in Orbit is a raised surface, and Orbit already has a defined answer for what a raised surface
     * looks like; inventing a second one here would be how Solid slowly stopped matching the cards
     * underneath it.
     */
    public static GradientDrawable solidDrawable(Context c, OrbitGlass.Palette p, float radiusDp) {
        int lit = p.surface2();
        int foot = UiKit.blend(p.surface(), lit, SOLID_DEPTH_SHARE);
        GradientDrawable solid = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{lit, foot});
        solid.setCornerRadius(UiKit.dp(c, radiusDp));
        solid.setStroke(UiKit.dp(c, OrbitGlass.BORDER_WIDTH_DP), OrbitGlass.borderColor(p));
        return solid;
    }

    /** The same surface for something that is tapped, with Orbit's own ripple over it. */
    public static Drawable interactive(Context c, float radiusDp) {
        return new RippleDrawable(
                ColorStateList.valueOf(UiKit.withAlpha(UiKit.accent(c), 56)),
                surfaceDrawable(c, radiusDp),
                UiKit.rounded(Color.WHITE, radiusDp, c));
    }

    public static Drawable interactive(Context c) {
        return interactive(c, OrbitGlass.RADIUS_DP);
    }

    /**
     * Dresses a control as a floating surface: the material, and a little real depth under it.
     *
     * <p>The elevation is the same for all three materials. It is not a property of glass, it is what
     * makes a control read as being <em>over</em> the page, and a Solid control that sat flat on the
     * page would be a different thing rather than a plainer version of the same thing.
     */
    public static void floatControl(View control, float radiusDp) {
        if (control == null) return;
        Context c = control.getContext();
        control.setBackground(surfaceDrawable(c, radiusDp));
        control.setElevation(UiKit.dp(c, OrbitGlass.RESTING_ELEVATION_DP));
    }

    public static void floatControl(View control) {
        floatControl(control, OrbitGlass.RADIUS_DP);
    }

    // ---- what text on one of these is read against -------------------------------------------------

    /**
     * The colour a label on a floating control is actually read against.
     *
     * <p>Solid is opaque, so the answer is simply its own surface; glass is translucent, so the answer
     * is the body composited over the page and {@link OrbitGlass#effectiveFill} owns that arithmetic.
     * Exposed rather than recomputed at call sites so a contrast check and the drawable can never
     * disagree about what is on screen.
     */
    public static int effectiveFill(Context c) {
        return effectiveFill(OrbitGlass.Palette.live(c));
    }

    public static int effectiveFill(OrbitGlass.Palette p) {
        if (OrbitTheme.MATERIAL_SOLID.equals(p.material())) {
            return UiKit.blend(p.surface2(), p.surface(), 0.5f);
        }
        return OrbitGlass.effectiveFill(p);
    }

    /** The ink a label on a floating control should use. One answer, for every material. */
    public static int inkOn(OrbitGlass.Palette p) {
        return OrbitContrast.inkOn(effectiveFill(p));
    }

    // ---- what each material is, in words -----------------------------------------------------------

    /** True when the selected material has glass in it, and therefore anything for Pro to tune. */
    public static boolean tunable(String material) {
        return OrbitTheme.isGlass(material);
    }

    /**
     * The one line Theme Studio shows where the glass controls would be, when there is no glass.
     *
     * <p>Stated rather than left to an empty space. Three sliders vanishing when Solid is chosen looks
     * like a bug unless Orbit says why, and the stored values behind them are not being discarded -
     * which is the other half of what this sentence is for.
     */
    public static String noGlassNote() {
        return "Solid uses no glass effects. Your glass settings are kept.";
    }

    /** A short description of what a material is, for the selector's accessibility announcement. */
    public static String materialDescription(String material) {
        String value = OrbitTheme.normalizeMaterial(material);
        if (OrbitTheme.MATERIAL_SOLID.equals(value)) return "Clean opaque controls, no glass";
        if (OrbitTheme.MATERIAL_FROSTED.equals(value)) return "Soft translucent glass";
        return "Richer glass with depth and reflection";
    }
}

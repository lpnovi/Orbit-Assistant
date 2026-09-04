package com.orbit.assistant;

import android.graphics.Color;

/**
 * The one place Orbit decides whether content is readable on a colour.
 *
 * <p>Before Theme Studio the answer was spread out: {@code onAccent} used a luminance cutoff of
 * 0.68, {@code onBubble} used 0.62, link colouring compared contrast ratios, and the inline-code
 * pill had a third rule again. That was survivable while every colour Orbit could show came from a
 * fixed catalogue somebody had already looked at. It stops being survivable the moment a user can
 * type a hex value, because then the thresholds are the only thing standing between a theme and an
 * unreadable app.
 *
 * <p>So the arithmetic lives here, once, and the two role thresholds that already shipped are
 * written down as named constants rather than repeated as bare numbers. They are deliberately
 * <em>preserved</em>, not unified: {@link #prefersDarkInk} is the better rule and is what every new
 * Theme Studio surface uses, but retro-fitting it to accents and bubbles would flip the ink on
 * Blue, Rose, Violet and Mint for users who chose those colours in an earlier release. Orbit's
 * existing look is not a bug to be corrected by a refactor.
 *
 * <p>Nothing here touches Android beyond {@link Color}'s channel accessors, so it is all directly
 * testable.
 */
public final class OrbitContrast {
    private OrbitContrast() {}

    /** Orbit's dark ink, used as content on a light ground. */
    public static final int DARK_INK = Color.rgb(18, 20, 26);
    /** Orbit's light ink, used as content on a dark ground. */
    public static final int LIGHT_INK = Color.rgb(240, 243, 250);

    /** WCAG AA for body text. Below this Orbit calls a pairing low contrast. */
    public static final double BODY_TEXT_MIN = 4.5d;
    /** WCAG AA for large text and for non-text UI such as icons, chips and controls. */
    public static final double LARGE_TEXT_MIN = 3.0d;

    /**
     * The luminance cutoff {@code UiKit.onAccent} has always used. Kept exactly, because it decides
     * the foreground on every accent Orbit has ever shipped.
     */
    static final double ON_ACCENT_CUTOFF = 0.68d;
    /** The same, for {@code UiKit.onBubble}. */
    static final double ON_BUBBLE_CUTOFF = 0.62d;

    /** WCAG relative luminance, gamma-corrected. The real one, used for contrast ratios. */
    public static double relativeLuminance(int color) {
        return 0.2126d * linear(Color.red(color))
                + 0.7152d * linear(Color.green(color))
                + 0.0722d * linear(Color.blue(color));
    }

    private static double linear(int channel) {
        double v = channel / 255d;
        return v <= 0.04045d ? v / 12.92d : Math.pow((v + 0.055d) / 1.055d, 2.4d);
    }

    /**
     * The un-gamma-corrected weighted average the shipped accent and bubble rules are calibrated
     * against. Not a substitute for {@link #relativeLuminance}; it exists so those two rules keep
     * producing the colours they produced before, and it is used nowhere else.
     */
    static double simpleLuminance(int color) {
        return (0.2126d * Color.red(color)
                + 0.7152d * Color.green(color)
                + 0.0722d * Color.blue(color)) / 255d;
    }

    /** WCAG contrast ratio between two opaque colours; 1.0 when they are identical. */
    public static double contrastRatio(int foreground, int background) {
        double a = relativeLuminance(foreground);
        double b = relativeLuminance(background);
        double lighter = Math.max(a, b);
        double darker = Math.min(a, b);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    /** Whether dark ink actually reads better than light ink on this ground. */
    public static boolean prefersDarkInk(int background) {
        return contrastRatio(DARK_INK, background) >= contrastRatio(LIGHT_INK, background);
    }

    /** Whichever of Orbit's two inks genuinely contrasts more with this ground. */
    public static int inkOn(int background) {
        return prefersDarkInk(background) ? DARK_INK : LIGHT_INK;
    }

    /**
     * One ink that has to serve two grounds at once — Orbit's background and its card surface.
     *
     * <p>{@code UiKit.TEXT} is a single process-wide colour drawn on both, so choosing it per
     * ground is not available. Picking whichever ink has the better <em>worst</em> case is what
     * stops a theme whose background and surface sit on opposite sides of the light/dark line from
     * producing text that is unreadable on one of them.
     */
    public static int primaryInk(int background, int surface) {
        double dark = Math.min(contrastRatio(DARK_INK, background), contrastRatio(DARK_INK, surface));
        double light = Math.min(contrastRatio(LIGHT_INK, background), contrastRatio(LIGHT_INK, surface));
        return dark >= light ? DARK_INK : LIGHT_INK;
    }

    /**
     * A quieter version of {@code ink} that still reads on {@code ground}.
     *
     * <p>Secondary text is dimmed by mixing it back toward what is behind it. How far it can be
     * mixed depends on the pair, so the amount is found rather than fixed: the walk stops at the
     * first blend that still clears the large-text threshold, and returns the full-strength ink if
     * none does.
     */
    public static int mutedInkOn(int ink, int ground) {
        for (float share = 0.66f; share <= 0.95f; share += 0.07f) {
            int mixed = blend(ink, ground, share);
            if (contrastRatio(mixed, ground) >= LARGE_TEXT_MIN) return mixed;
        }
        return ink;
    }

    /** Body text at this pairing is below WCAG AA. */
    public static boolean isLowContrast(int foreground, int background) {
        return contrastRatio(foreground, background) < BODY_TEXT_MIN;
    }

    /** A non-text element — an icon, a chip, a control — is below the large-element threshold. */
    public static boolean isLowContrastForUi(int foreground, int background) {
        return contrastRatio(foreground, background) < LARGE_TEXT_MIN;
    }

    /** {@code amountA} of {@code a} mixed with the remainder of {@code b}, opaque. */
    public static int blend(int a, int b, float amountA) {
        float t = Math.max(0f, Math.min(1f, amountA));
        return Color.rgb(
                Math.round(Color.red(a) * t + Color.red(b) * (1 - t)),
                Math.round(Color.green(a) * t + Color.green(b) * (1 - t)),
                Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t)));
    }

    /**
     * A surface lifted {@code share} of the way toward whatever ink reads on it.
     *
     * <p>Orbit's card ramp is three steps of separation, and on a custom surface those steps have
     * to go in the right direction: lighter on a dark theme, darker on a light one. Deriving the
     * direction from the surface itself is what makes one formula cover both.
     */
    public static int elevate(int surface, float share) {
        return blend(inkOn(surface), surface, share);
    }
}

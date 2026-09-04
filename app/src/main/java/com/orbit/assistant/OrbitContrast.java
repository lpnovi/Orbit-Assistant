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

    /**
     * A link colour derived from {@code accent} that actually reads on {@code ground}.
     *
     * <p>This is the one derivation Theme Studio adds rather than preserves, and it exists because
     * the old rule produced links that looked disconnected from the theme. It kept the accent when
     * the accent read, and otherwise mixed it toward Orbit's near-white ink, which desaturates a
     * colour and drags its hue toward grey at the same time. Nova is the case that showed it up:
     * at 2.3 to 1 on a card it always failed, and what came back was a pale mauve nobody would call
     * Nova.
     *
     * <p>So the walk happens in HSV with the hue pinned. A colour that is too dark for its ground is
     * brightened, then desaturated only if brightening alone cannot get there; one that is too
     * light is darkened and saturated, which is what keeps a pastel from turning into grey. The hue
     * never moves, so the link is recognisably the theme's colour at whatever step it stops on.
     *
     * <p>Deterministic, cheap, and dependent on nothing but the two colours, so the Theme Studio
     * preview and the real Markdown renderer reach the same answer from the same inputs.
     */
    public static int readableAccentOn(int accent, int ground) {
        return readableAccentOn(accent, ground, LARGE_TEXT_MIN);
    }

    /** As {@link #readableAccentOn(int, int)}, to an explicit contrast floor. */
    public static int readableAccentOn(int accent, int ground, double minRatio) {
        if (contrastRatio(accent, ground) >= minRatio) return accent;

        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        float hue = hsv[0];
        float saturation = hsv[1];
        float value = hsv[2];
        // Which way to move is decided by the ground, not by the accent: on a dark surface the
        // only direction with any contrast left in it is up.
        boolean lighten = !prefersDarkInk(ground);

        for (int step = 1; step <= HSV_STEPS; step++) {
            float share = step / (float) HSV_STEPS;
            float v;
            float s;
            if (lighten) {
                v = value + (1f - value) * share;
                // Saturation is given up only after value has run out, so a colour is brightened
                // before it is washed out, and never washed out further than it has to be.
                s = saturation * (1f - Math.max(0f, share - VALUE_HEADROOM) / (1f - VALUE_HEADROOM)
                        * (1f - MIN_SATURATION));
            } else {
                v = value * (1f - share * (1f - MIN_VALUE));
                s = saturation + (1f - saturation) * share * DARKEN_SATURATION_GAIN;
            }
            int candidate = Color.HSVToColor(new float[]{
                    hue, clamp(s), clamp(v)});
            if (contrastRatio(candidate, ground) >= minRatio) return candidate;
        }
        // Nothing on this hue reaches the floor, which only happens on a mid-grey ground. Orbit's
        // own readable ink is a worse link and a better sentence.
        return inkOn(ground);
    }

    /** How finely the hue-preserving walk is sampled. Enough to stop close to the first pass. */
    private static final int HSV_STEPS = 24;
    /** The share of the walk spent brightening before saturation starts being given up. */
    private static final float VALUE_HEADROOM = 0.5f;
    /** A link never desaturates past this, or it stops carrying the theme at all. */
    private static final float MIN_SATURATION = 0.35f;
    /** Nor does it darken past this, or it stops being distinguishable from body text. */
    private static final float MIN_VALUE = 0.12f;
    /** How much a darkening colour is saturated on the way, so a pastel stays its own hue. */
    private static final float DARKEN_SATURATION_GAIN = 0.9f;

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
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

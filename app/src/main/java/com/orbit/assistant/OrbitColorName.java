package com.orbit.assistant;

import android.graphics.Color;

import java.util.Locale;

/**
 * A plain-English name for a colour.
 *
 * <p>Theme Studio is a screen made almost entirely of colour, which is exactly the kind of screen
 * that stops working when colour is the only thing carrying the information. A swatch labelled
 * {@code #3A2E63} tells a sighted user very little and a screen-reader user nothing at all, so
 * every swatch Orbit draws is described as "Deep violet" first and by its hex value second.
 *
 * <p>Deterministic and offline: hue, saturation and value buckets, nothing looked up and nothing
 * learned. The names are approximate by design — this is a label, not a colour-science claim.
 */
public final class OrbitColorName {
    private OrbitColorName() {}

    /**
     * Hue boundaries, in degrees, and the name that starts at each.
     *
     * <p>Uneven on purpose. Even buckets put Orbit's own violet accent — which sits at 247° — on
     * the wrong side of a boundary and called it indigo, and green occupies far more of the circle
     * than red does. These follow how the families are actually named rather than how the numbers
     * divide.
     */
    private static final float[] HUE_STARTS = {
            10f, 40f, 52f, 66f, 90f, 150f, 175f, 195f, 215f, 240f, 260f, 285f, 320f, 345f
    };
    private static final String[] HUE_NAMES = {
            "orange", "amber", "yellow", "lime", "green", "teal", "cyan", "sky blue",
            "blue", "violet", "purple", "magenta", "pink", "red"
    };

    /** "Deep violet", "Pale sky blue", "Light grey". Always capitalised, never empty. */
    public static String of(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float hue = hsv[0];
        float sat = hsv[1];
        float val = hsv[2];

        if (sat < 0.10f) {
            if (val < 0.06f) return "Black";
            if (val < 0.28f) return "Near black";
            if (val < 0.55f) return "Dark grey";
            if (val < 0.80f) return "Grey";
            if (val < 0.96f) return "Light grey";
            return "White";
        }

        String base = hueName(hue);
        if (val < 0.22f) return capitalize("very dark " + base);
        if (val < 0.45f) return capitalize("deep " + base);
        if (sat < 0.35f && val > 0.80f) return capitalize("pale " + base);
        if (val > 0.86f && sat < 0.62f) return capitalize("light " + base);
        if (sat > 0.85f && val > 0.75f) return capitalize("vivid " + base);
        return capitalize(base);
    }

    /** What a swatch announces: the name, then the value, so both are available. */
    public static String describe(String role, int color) {
        String prefix = role == null || role.trim().isEmpty() ? "" : role.trim() + ", ";
        return prefix + of(color) + ", " + OrbitTheme.colorToken(color);
    }

    /** Red wraps the origin, so it is both the first family and the last. */
    private static String hueName(float hue) {
        float value = ((hue % 360f) + 360f) % 360f;
        for (int i = 0; i < HUE_STARTS.length; i++) {
            if (value < HUE_STARTS[i]) return i == 0 ? "red" : HUE_NAMES[i - 1];
        }
        return "red";
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
    }
}

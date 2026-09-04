package com.orbit.assistant;

import android.content.Context;

/**
 * One theme, resolved into every colour Orbit actually draws with.
 *
 * <p>This exists because of a trap the Theme Studio preview would otherwise fall into. The obvious
 * way to build a preview is to hardcode a plausible arrangement of colours next to the controls
 * that change them — and the obvious way for that preview to become a lie is for Orbit's real
 * derivation to move a month later while the preview keeps its own copy. So there is one resolver,
 * and it has two callers: {@link UiKit#syncTheme}, which assigns the result to the process-wide
 * canvas the whole app draws from, and the preview, which resolves a <em>draft</em> without
 * touching that canvas at all. Neither can drift, because neither owns the arithmetic.
 *
 * <p>The classic-surface shortcut is the one deliberate special case, and it is here rather than in
 * a formula: Orbit's card ramp and inks were tuned by eye, and a theme that does not ask for
 * anything else gets them back exactly rather than re-derived approximately.
 */
public final class OrbitThemeTokens {

    /** The theme these were resolved from. */
    public final OrbitTheme theme;

    public final int background;
    public final int surface;
    public final int surface2;
    public final int surface3;
    /** Primary ink, chosen once for the background and surface together. */
    public final int text;
    /** Secondary ink, quietened as far as it can be while still reading on the surface. */
    public final int muted;

    public final int accent;
    public final int onAccent;

    public final int userBubble;
    public final int userBubbleInk;
    public final int assistantBubble;
    public final int assistantBubbleInk;

    private OrbitThemeTokens(OrbitTheme theme, int background, int surface, int surface2,
                             int surface3, int text, int muted, int accent, int onAccent,
                             int userBubble, int userBubbleInk,
                             int assistantBubble, int assistantBubbleInk) {
        this.theme = theme;
        this.background = background;
        this.surface = surface;
        this.surface2 = surface2;
        this.surface3 = surface3;
        this.text = text;
        this.muted = muted;
        this.accent = accent;
        this.onAccent = onAccent;
        this.userBubble = userBubble;
        this.userBubbleInk = userBubbleInk;
        this.assistantBubble = assistantBubble;
        this.assistantBubbleInk = assistantBubbleInk;
    }

    /**
     * How far each step of Orbit's card ramp is lifted away from the surface beneath it, when the
     * surface is a custom colour and the shipped ramp cannot simply be reused.
     */
    private static final float ELEVATION_2 = 0.045f;
    private static final float ELEVATION_3 = 0.095f;

    public static OrbitThemeTokens resolve(Context c, OrbitTheme raw) {
        OrbitTheme theme = raw == null ? OrbitTheme.orbitDefault() : raw;

        int backgroundBase = OrbitTheme.isHexToken(theme.background)
                ? OrbitTheme.hexTokenColor(theme.background) : UiKit.classicBackground();
        // AMOLED wins over a custom background on purpose. It is a promise about the large lit area
        // of the screen; a theme that also names a background is naming what applies when AMOLED is
        // off. Surfaces are untouched by it, which is what keeps cards visible on a black page.
        int background = theme.amoled ? android.graphics.Color.BLACK : backgroundBase;

        int surface;
        int surface2;
        int surface3;
        if (OrbitTheme.isHexToken(theme.surface)) {
            surface = OrbitTheme.hexTokenColor(theme.surface);
            surface2 = OrbitContrast.elevate(surface, ELEVATION_2);
            surface3 = OrbitContrast.elevate(surface, ELEVATION_3);
        } else {
            surface = UiKit.classicSurface();
            surface2 = UiKit.classicSurface2();
            surface3 = UiKit.classicSurface3();
        }

        int text;
        int muted;
        if (theme.usesClassicSurfaces()) {
            text = UiKit.classicText();
            muted = UiKit.classicMuted();
        } else {
            boolean dark = OrbitContrast.primaryInk(background, surface) == OrbitContrast.DARK_INK;
            text = dark ? UiKit.classicDarkText() : UiKit.classicText();
            muted = OrbitContrast.mutedInkOn(text, surface);
        }

        int accent = UiKit.accentForName(c, theme.accent);
        int onAccent = UiKit.onAccent(accent);

        // The two classic fills, defined here rather than at each conversation call site so the
        // preview shows the bubbles the conversation will actually draw.
        int classicUser = OrbitContrast.blend(accent, surface2, 0.46f);
        int userBubble = resolveBubble(c, theme.userBubble, classicUser, accent);
        int assistantBubble = resolveBubble(c, theme.assistantBubble, surface, accent);

        return new OrbitThemeTokens(theme, background, surface, surface2, surface3, text, muted,
                accent, onAccent,
                userBubble, UiKit.onBubble(userBubble),
                assistantBubble, UiKit.onBubble(assistantBubble));
    }

    private static int resolveBubble(Context c, String token, int classicFill, int accent) {
        if (token == null || OrbitTheme.CLASSIC.equals(token)) return classicFill;
        if (OrbitTheme.ACCENT.equals(token)) return accent;
        if (OrbitTheme.isHexToken(token)) return OrbitTheme.hexTokenColor(token);
        return UiKit.accentForName(c, token);
    }

    /** Orbit's classic user-bubble fill for the accent in force. Used by the Classic swatch. */
    public static int classicUserBubble(int accent, int surface2) {
        return OrbitContrast.blend(accent, surface2, 0.46f);
    }

    // ---- readability ---------------------------------------------------------------------------

    /** One thing Orbit checked, and whether it reads. */
    public static final class Check {
        public final String label;
        public final double ratio;
        public final boolean passes;

        Check(String label, double ratio, boolean passes) {
            this.label = label;
            this.ratio = ratio;
            this.passes = passes;
        }
    }

    /**
     * Every readability pairing this theme is judged on.
     *
     * <p>Deliberately the pairings a person actually reads, not every combination that exists:
     * message text on both bubbles, page and card text, and the accent as a non-text element.
     * Text pairings are held to WCAG AA; the accent is held to the large-element threshold,
     * because it colours icons, chips and controls rather than body copy.
     */
    public java.util.List<Check> readability() {
        java.util.List<Check> out = new java.util.ArrayList<>();
        out.add(textCheck("Your messages", userBubbleInk, userBubble));
        out.add(textCheck("Orbit's replies", assistantBubbleInk, assistantBubble));
        out.add(textCheck("Text on the background", text, background));
        out.add(textCheck("Text on cards", text, surface));
        out.add(uiCheck("Accent on cards", accent, surface));
        return out;
    }

    private static Check textCheck(String label, int foreground, int background) {
        double ratio = OrbitContrast.contrastRatio(foreground, background);
        return new Check(label, ratio, ratio >= OrbitContrast.BODY_TEXT_MIN);
    }

    private static Check uiCheck(String label, int foreground, int background) {
        double ratio = OrbitContrast.contrastRatio(foreground, background);
        return new Check(label, ratio, ratio >= OrbitContrast.LARGE_TEXT_MIN);
    }

    /** True when at least one pairing is below its threshold. */
    public boolean hasLowContrast() {
        for (Check check : readability()) if (!check.passes) return true;
        return false;
    }

    /** The failing pairings, for the warning Theme Studio shows under the preview. */
    public java.util.List<Check> lowContrastChecks() {
        java.util.List<Check> out = new java.util.ArrayList<>();
        for (Check check : readability()) if (!check.passes) out.add(check);
        return out;
    }
}

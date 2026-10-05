package com.orbit.assistant;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * How the Side-button overlay looks. Visual facts only: every style builds the same controls with
 * the same behaviour, and {@link OrbitSession} arranges them from these numbers. Adding a style is a
 * new constant here plus its Settings label, never a second copy of the overlay.
 */
final class OverlayStyle {
    /** The overlay Orbit has always had. Its numbers are exactly the ones it shipped with. */
    static final OverlayStyle CLASSIC = new OverlayStyle(Prefs.OVERLAY_STYLE_CLASSIC, "Classic",
            78, 8, OverlayStretch.SHEET_CORNER_DP, 18, 36, 18, 40, 12, 240, 240, 22,
            false, false, 255, false);
    /**
     * Orbit's full floating card, shipped as "Float" in 0.8.4.0-beta.1: lighter dim, a slimmer
     * header, and one composer card that also carries the screen controls. Its conversation fits
     * what it holds, so an empty chat no longer reserves a tall blank middle.
     */
    static final OverlayStyle MODERN = new OverlayStyle(Prefs.OVERLAY_STYLE_MODERN, "Modern",
            44, 12, 32f, 14, 28, 15, 36, 16, 120, 260, 26,
            true, false, 255, false);
    /**
     * The compact one: a small bottom card that is little more than a header and the composer
     * until there is a conversation to show, then grows upward to a modest cap and scrolls.
     */
    static final OverlayStyle FLOAT = new OverlayStyle(Prefs.OVERLAY_STYLE_FLOAT, "Float",
            30, 10, 28f, 12, 24, 15, 36, 16, 0, 200, 24,
            true, true, 255, false);
    /**
     * Lelo mode's secret one: Float's compact bones, floated further in from the edges, rounder,
     * barely dimmed and a touch see-through, with a few hearts and sparkles drawn in the user's
     * own accent. Never offered, searchable or resolved unless {@link Prefs#leloMode} is on.
     */
    static final OverlayStyle CUTIE = new OverlayStyle(Prefs.OVERLAY_STYLE_CUTIE, "Cutie Patootie ♡",
            14, 16, 34f, 12, 24, 14.5f, 36, 18, 0, 190, 26,
            true, true, 230, true);

    final String id;
    /** What Settings calls it. */
    final String label;
    /** Alpha of the dim behind the sheet. */
    final int scrimAlpha;
    /** Gap between the sheet and the screen's sides and bottom, in dp. */
    final int marginDp;
    /** Resting corner radius of the sheet, in dp. */
    final float cornerDp;
    /** Horizontal padding inside the sheet, in dp. */
    final int sidePaddingDp;
    final int markDp;
    final float titleSp;
    /** Header icon buttons, in dp. */
    final int iconDp;
    /** Corner radius of the AI chip and screen buttons, in dp. */
    final float controlRadiusDp;
    /**
     * Resting conversation height range, in dp. Equal for a fixed-height conversation (Classic);
     * otherwise the conversation fits its content between the two, then scrolls.
     */
    final int conversationMinDp;
    final int conversationDp;
    final float composerRadiusDp;
    /** True when the screen controls live inside the composer card instead of their own bar. */
    final boolean integratedComposer;
    /**
     * Float's density choices: History and New chat share one More menu, an empty chat shows no
     * greeting bubble (the composer's hint carries it), and an empty suggestion row takes no room.
     */
    final boolean compact;
    /** Alpha of the sheet's own background. The composer card inside it always stays opaque. */
    final int sheetAlpha;
    /** Hearts and sparkles: see {@link CutieTouches}. Decoration only, never a control. */
    final boolean cute;

    private OverlayStyle(String id, String label, int scrimAlpha, int marginDp, float cornerDp,
                         int sidePaddingDp, int markDp, float titleSp, int iconDp,
                         float controlRadiusDp, int conversationMinDp, int conversationDp,
                         float composerRadiusDp, boolean integratedComposer, boolean compact,
                         int sheetAlpha, boolean cute) {
        this.id = id;
        this.label = label;
        this.scrimAlpha = scrimAlpha;
        this.marginDp = marginDp;
        this.cornerDp = cornerDp;
        this.sidePaddingDp = sidePaddingDp;
        this.markDp = markDp;
        this.titleSp = titleSp;
        this.iconDp = iconDp;
        this.controlRadiusDp = controlRadiusDp;
        this.conversationMinDp = conversationMinDp;
        this.conversationDp = conversationDp;
        this.composerRadiusDp = composerRadiusDp;
        this.integratedComposer = integratedComposer;
        this.compact = compact;
        this.sheetAlpha = sheetAlpha;
        this.cute = cute;
    }

    /** True when the conversation grows with its content instead of holding one fixed height. */
    boolean fitsContent() {
        return conversationMinDp < conversationDp;
    }

    /** The composer's resting hint. Float's doubles as the greeting it does not draw. */
    String hint() {
        if (cute) return "what's up, cutie? (˶ᵔ ᵕ ᵔ˶)";
        return compact ? "What can I help with?" : "Ask anything…";
    }

    /** Float's minimised capsule: only the compact styles can fold into one. */
    boolean canPeek() {
        return compact;
    }

    /** Gap from the window's edges while the card sits in the corner, in dp. */
    static final int CORNER_MARGIN_DP = 16;

    /**
     * The card's width for a window of this size in dp, or 0 to span the window between the
     * style's margins. A compact style spans a narrow portrait window, and on anything wider or
     * landscape it becomes a bounded card in the bottom-right corner: a little narrower on a
     * short (phone landscape) window, a little wider on a large one. Decided from the window, not
     * the device, so split screen and resizing land on the right arrangement too.
     */
    int cardWidthDp(int windowWidthDp, int windowHeightDp) {
        if (!compact || windowWidthDp <= 0) return 0;
        if (windowWidthDp < 600 && windowWidthDp <= windowHeightDp) return 0;
        int target = windowHeightDp < 480 ? 400 : windowWidthDp >= 840 ? 480 : 440;
        int available = windowWidthDp - 2 * CORNER_MARGIN_DP;
        return target >= available ? 0 : target;
    }

    /**
     * How tall the conversation may grow before it scrolls. A cornered card on a tall window gets
     * more room than a phone does; nothing else changes.
     */
    int conversationCapDp(int windowWidthDp, int windowHeightDp) {
        if (cardWidthDp(windowWidthDp, windowHeightDp) > 0 && windowHeightDp >= 720) {
            return Math.round(conversationDp * 1.6f);
        }
        return conversationDp;
    }

    static OverlayStyle of(String id) {
        if (Prefs.OVERLAY_STYLE_CLASSIC.equals(id)) return CLASSIC;
        if (Prefs.OVERLAY_STYLE_FLOAT.equals(id)) return FLOAT;
        if (Prefs.OVERLAY_STYLE_CUTIE.equals(id)) return CUTIE;
        return MODERN;
    }

    static OverlayStyle current(Context c) {
        return of(Prefs.overlayStyle(c));
    }

    /** What Settings offers, in order. The secret one exists only while Lelo mode is on. */
    static List<OverlayStyle> choices(Context c) {
        List<OverlayStyle> out = new ArrayList<>();
        out.add(MODERN);
        out.add(FLOAT);
        out.add(CLASSIC);
        if (Prefs.leloMode(c)) out.add(CUTIE);
        return out;
    }
}

package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/**
 * Orbit's page canvas: the one place the background of a screen is decided.
 *
 * <p>Before this existed there was nothing to decide. Every page painted {@code UiKit.BG} and that
 * was the whole answer, so the answer could safely live at forty call sites. Orbit Pro's advanced
 * backgrounds change that: the page is now a function of the theme's Background colour, a premium
 * effect layered over it, whether this device is entitled to draw that effect, and whether AMOLED has
 * asked for a true-black page regardless. Four inputs and three outcomes is not a thing forty
 * screens should each work out, and the failure if they did would be the quiet kind - thirty-eight
 * pages with a gradient and two without, found by a user rather than by a test.
 *
 * <p>So an Activity's entire relationship with its background is one call:
 *
 * <pre>{@code root.setBackground(OrbitBackground.pageDrawable(this));}</pre>
 *
 * <p>and it never learns what a gradient orientation is, what radius a glow has, what entitlement
 * means or what AMOLED suppresses. Theme Studio's previews call {@link #drawableFor} with a resolved
 * draft instead of the live theme, which is the same arrangement {@link OrbitThemeTokens} and
 * {@link OrbitGlass} already use and for the same reason: a preview that computed its own gradient
 * would go on looking convincing while quietly disagreeing with the real pages.
 *
 * <h2>Entitlement and AMOLED both suppress, and neither erases</h2>
 *
 * <p>{@link OrbitProStyle#resolve} is asked at render time rather than at the controls, so a Free
 * device draws Solid whatever its stored theme says - and goes on storing exactly what it stored.
 * AMOLED does the same thing for a different reason: it is a promise about the large lit area of the
 * screen, and a gradient is a large lit area. Both are suppression at the point of drawing. Turn Pro
 * Preview back on, or AMOLED back off, and the configured effect returns unchanged because nothing
 * ever went looking for it to delete.
 *
 * <h2>Static once built</h2>
 *
 * <p>Both effects are drawables that resolve their geometry from their own bounds: a
 * {@code GradientDrawable} for Linear, and one small {@link Drawable} for Glow that builds a
 * {@link RadialGradient} in {@code onBoundsChange}. Nothing is allocated per frame, nothing is
 * recomputed while scrolling, and nothing is a pre-rendered bitmap being stretched - which is what
 * makes one stored theme correct on a phone in portrait, on a tablet in landscape and across a
 * rotation, from semantic direction and position rather than from pixel coordinates.
 */
public final class OrbitBackground {

    private OrbitBackground() {}

    // ---- what a page is drawn from ----------------------------------------------------------------

    /**
     * The theme, the styling it is entitled to, and the base colour underneath both.
     *
     * <p>The same shape as {@link OrbitGlass.Palette} and for the same reason. The app passes
     * {@link #live}, the Theme Studio previews pass {@link #of} with a draft, and there is one
     * implementation of what an Orbit page looks like.
     */
    public static final class Page {
        final int base;
        final int accent;
        final boolean amoled;
        final OrbitProStyle style;

        private Page(int base, int accent, boolean amoled, OrbitProStyle style) {
            this.base = base;
            this.accent = accent;
            this.amoled = amoled;
            this.style = style == null ? OrbitProStyle.DEFAULT : style;
        }

        /** The page Orbit is currently drawing, at the styling it is currently entitled to draw. */
        public static Page live(Context c) {
            return new Page(UiKit.BG, UiKit.accent(c), Prefs.amoledMode(c), OrbitProStyle.live(c));
        }

        /**
         * The page a resolved theme would have.
         *
         * <p>{@code tokens.background} is already true black when the theme asks for AMOLED, so the
         * base colour is right either way; the flag is carried separately because suppressing the
         * effect is a second decision and one that has to be made from the theme rather than
         * inferred from a colour that happens to be black.
         */
        public static Page of(OrbitThemeTokens tokens, OrbitProStyle style) {
            if (tokens == null) return new Page(UiKit.BG, UiKit.DEFAULT_ACCENT, false, style);
            boolean amoled = tokens.theme != null && tokens.theme.amoled;
            return new Page(tokens.background, tokens.accent, amoled, style);
        }
    }

    // ---- the page ---------------------------------------------------------------------------------

    /** The background of an Orbit page, in whatever theme is live. */
    public static Drawable pageDrawable(Context c) {
        return drawableFor(c, Page.live(c));
    }

    /** The background a resolved draft would give a page. What the previews draw. */
    public static Drawable drawableFor(Context c, OrbitThemeTokens tokens, OrbitProStyle style) {
        return drawableFor(c, Page.of(tokens, style));
    }

    public static Drawable drawableFor(Context c, Page page) {
        if (!effectDraws(page)) return new ColorDrawable(page.base);
        if (page.style.backgroundMode == OrbitProStyle.BACKGROUND_LINEAR) {
            // Strength moves the far endpoint towards the base rather than fading the whole gradient,
            // so the page stays a solid coherent thing at every setting and the system bars, which are
            // painted in the base colour, go on agreeing with it. OrbitProStyle owns that arithmetic.
            return new GradientDrawable(orientation(page.style.gradientDirection),
                    new int[]{page.base, page.style.gradientEndColor(c, page.accent, page.base)});
        }
        return new GlowDrawable(page.base, page.style.backgroundEffectColor(c, page.accent),
                page.style.glowAlpha(), page.style.glowRadiusShare(), page.style.glowCenterY());
    }

    /**
     * Dresses a page canvas, and is the call every Activity should be making.
     *
     * <p>A method rather than a constant, because a page background is now a drawable with geometry
     * rather than a colour, and because a screen that wrote {@code setBackgroundColor} would be
     * correct today and silently effect-less tomorrow.
     */
    public static void applyPage(View root) {
        if (root == null) return;
        root.setBackground(pageDrawable(root.getContext()));
    }

    /**
     * Whether a premium background effect is actually being drawn right now.
     *
     * <p>Three ways for the answer to be no, and they are not the same no. The theme may not ask for
     * one; the device may not be entitled to it; or AMOLED may have asked for a true-black page,
     * which wins over decoration on the largest lit area of the screen. Only the first is the user
     * having chosen Solid, which is why Theme Studio can tell them the other two apart.
     */
    public static boolean effectDraws(Context c) {
        return effectDraws(Page.live(c));
    }

    static boolean effectDraws(Page page) {
        return !page.amoled && page.style.hasBackgroundEffect();
    }

    /**
     * The colour the status and navigation bars take.
     *
     * <p>The base background rather than a sample of the effect. A system bar cannot carry a
     * gradient without Orbit reimplementing one inside an inset it does not control, and a bar tinted
     * to whatever the effect happens to be at the top of the page would visibly disagree with it the
     * moment anything about the layout moved. The base colour is the one value that is honest at both
     * ends of the page, so the transition into the effect reads as deliberate.
     */
    public static int systemBarColor(Context c) {
        return UiKit.BG;
    }

    /** The platform orientation a stored direction means. */
    static GradientDrawable.Orientation orientation(int direction) {
        switch (direction) {
            case OrbitProStyle.DIRECTION_TR_BL: return GradientDrawable.Orientation.TR_BL;
            case OrbitProStyle.DIRECTION_RIGHT_LEFT: return GradientDrawable.Orientation.RIGHT_LEFT;
            case OrbitProStyle.DIRECTION_BR_TL: return GradientDrawable.Orientation.BR_TL;
            case OrbitProStyle.DIRECTION_BOTTOM_TOP: return GradientDrawable.Orientation.BOTTOM_TOP;
            case OrbitProStyle.DIRECTION_BL_TR: return GradientDrawable.Orientation.BL_TR;
            case OrbitProStyle.DIRECTION_LEFT_RIGHT: return GradientDrawable.Orientation.LEFT_RIGHT;
            case OrbitProStyle.DIRECTION_TL_BR: return GradientDrawable.Orientation.TL_BR;
            default: return GradientDrawable.Orientation.TOP_BOTTOM;
        }
    }

    /**
     * What the page is effectively coloured at its centre, for the readability checks.
     *
     * <p>Orbit derives page ink from the Background colour and keeps deriving it from there, because
     * flipping text between light and dark on account of one decorative glow is how a theme becomes
     * unusable in the middle of a sentence. What this is for is the opposite question: whether an
     * effect has pushed the page far enough from its base colour that Orbit should say so.
     */
    public static int effectivePageColor(Context c, Page page) {
        if (!effectDraws(page)) return page.base;
        if (page.style.backgroundMode == OrbitProStyle.BACKGROUND_LINEAR) {
            return UiKit.blend(page.base,
                    page.style.gradientEndColor(c, page.accent, page.base), 0.5f);
        }
        return UiKit.blend(page.style.backgroundEffectColor(c, page.accent), page.base,
                page.style.glowAlpha());
    }

    /**
     * Whether a background effect is configured but suppressed because AMOLED wants a black page.
     *
     * <p>Separated from {@link #effectDraws} because "no effect is drawn" and "an effect is configured
     * and hidden" are different facts and Theme Studio has to say the second one out loud. The reason a
     * gradient vanishes when AMOLED goes on is obvious once somebody tells you and completely opaque
     * until they do, which is exactly the kind of thing a person should not have to discover.
     */
    public static boolean hiddenByAmoled(Context c) {
        return hiddenByAmoled(Page.live(c));
    }

    static boolean hiddenByAmoled(Page page) {
        return page.amoled && page.style.hasBackgroundEffect();
    }

    /** The one sentence Theme Studio shows when AMOLED is hiding a configured effect. */
    public static String amoledSuppressionNote() {
        return "AMOLED uses true black, so background effects are hidden. "
                + "Turn AMOLED off to see this effect.";
    }

    /** The short label the background sample carries when it is black for that reason. */
    public static String amoledHiddenLabel() {
        return "Hidden by AMOLED";
    }

    /** What Orbit says once, as a toast, when an effect is chosen while AMOLED is already on. */
    public static String amoledToast() {
        return "AMOLED keeps the page true black.";
    }

    // ---- the glow ---------------------------------------------------------------------------------

    /**
     * A diffused radial light over a solid page.
     *
     * <p>A small custom {@link Drawable} rather than a {@code GradientDrawable} in radial mode,
     * because that class takes its radius in pixels and would therefore need to be told how large the
     * page is - which is exactly the thing a theme must not store. This asks its own bounds instead,
     * so the light is a share of the page it lands on and one stored configuration is correct in
     * portrait, in landscape, on a tablet and mid-rotation.
     *
     * <p>Two stops inside the peak rather than one, so the falloff is a curve and not a cone. A
     * linear ramp from centre to edge is exactly what makes cheap radial gradients read as a coloured
     * circle sitting on a page; the light has to lose most of its strength early and then trail off
     * for a long way to read as illumination.
     */
    static final class GlowDrawable extends Drawable {

        /** Where the light has fallen to a third of its strength, and to a tenth. */
        private static final float[] STOPS = {0f, 0.34f, 0.68f, 1f};
        private static final float[] FALLOFF = {1f, 0.42f, 0.11f, 0f};

        private final int base;
        private final int glow;
        private final float alpha;
        private final float radiusShare;
        private final float centerY;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();

        GlowDrawable(int base, int glow, float alpha, float radiusShare, float centerY) {
            this.base = base;
            this.glow = glow;
            this.alpha = alpha;
            this.radiusShare = radiusShare;
            this.centerY = centerY;
            fill.setColor(base);
        }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) {
                paint.setShader(null);
                return;
            }
            float radius = Math.max(width, height) * radiusShare;
            int peak = UiKit.withAlpha(glow, Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f));
            int[] colors = new int[FALLOFF.length];
            for (int i = 0; i < FALLOFF.length; i++) {
                colors[i] = UiKit.withAlpha(peak, Math.round(Color.alpha(peak) * FALLOFF[i]));
            }
            paint.setShader(new RadialGradient(
                    bounds.left + width / 2f,
                    bounds.top + height * centerY,
                    Math.max(1f, radius),
                    colors, STOPS, Shader.TileMode.CLAMP));
        }

        /**
         * The page, then the light on it, both inside this drawable's own bounds.
         *
         * <p>Two {@code drawRect} calls rather than a {@code drawColor} and a rect. {@code drawColor}
         * fills the whole clip rather than the bounds, which is right when this is a view's background
         * and wrong the moment it is nested inside anything - including the Theme Studio sample, which
         * is exactly where it is nested.
         */
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.drawRect(bounds, fill);
            if (paint.getShader() != null) canvas.drawRect(bounds, paint);
        }

        @Override public void setAlpha(int value) {
            paint.setAlpha(value);
            fill.setAlpha(value);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            paint.setColorFilter(filter);
            fill.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return Color.alpha(base) == 255 ? android.graphics.PixelFormat.OPAQUE
                    : android.graphics.PixelFormat.TRANSLUCENT;
        }

        /** The page colour underneath the light. Read by the tests that check suppression. */
        int baseColor() { return base; }

        /** The light's own colour, before its alpha. */
        int glowColor() { return glow; }
    }
}

package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
 * <p>So the treatment here is layered rather than divided. The controls are given glass - a translucent
 * body you can see into, a local highlight where the light catches it, accent gathering at its thick
 * edges and a hairline holding the whole thing - so they read as glass lying <em>over</em> the page
 * rather than as a panel fitted into it. Beneath them a scrim is laid over the
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
 * <p>v0.8.0.0-beta.4 made the material considerably richer without touching any of that, and
 * v0.8.0.0-beta.5 made it considerably more restrained after a Galaxy S25 Ultra showed what "richer"
 * had produced on a full-width search field. The fault was geometric rather than a matter of taste.
 * Beta 4 built the material from a stack of gradient drawables, and a gradient can only place a
 * highlight as a share of the box it lands in: the rim occupied the top quarter of the control and the
 * diagonal sweep, across a box ten times wider than it was tall, ran very nearly horizontally. The
 * result was a white band across the top of a long field, which reads as polished metal.
 *
 * <p>So the material is one {@link GlassDrawable} now, and every highlight in it is derived from the
 * control's <em>height</em>: a glint in the top-left corner, a rim bounded in dp, a thin bounce at the
 * foot, and accent pooled within a height's reach of each side. A wide bar and a small pill get the
 * same finish rather than the same proportions. Depth still comes from layering light rather than from
 * sampling what is behind the control, the shaders are built once when the bounds are set, and the
 * answer to "how does this look on an API 29 phone" is still "exactly like this".
 *
 * <p>Two materials come out of here - Frosted and Liquid - and which one a theme asks for is a free
 * choice held on {@link OrbitTheme}. {@link OrbitFloatingSurface} is what decides between them and
 * Solid, which is not glass at all; this file answers only what glass looks like.
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

    // The shares the shipped treatment is built from, before Orbit Pro scales any of them. These were
    // private constants with no reason to be anything else until Theme Studio Pro gave a person a way
    // to adjust the glass. They are now the 100% mark of those controls rather than the only numbers
    // Orbit can draw: OrbitProStyle multiplies each one, a Pro user sitting at every default lands back
    // on these exact values, and Free never leaves them.

    /**
     * How much light the lit top of the body carries, before the accent is mixed into it.
     *
     * <p>Dropped from 0.14 to 0.09 in v0.8.0.0-beta.5, together with the accent share below. Between
     * them these two were most of why the glass read as a separate design system rather than as part of
     * the theme: a body whose top was a seventh of the way towards an accent-and-white mix is a body
     * that is visibly not the surface colour under it. The surface is dominant now, and the light that
     * makes it glass comes from the specular and rim layers instead, where it can be bounded.
     */
    static final float BASE_HIGHLIGHT_SHARE = 0.09f;
    /** How much of the accent's own colour is in the lit top edge, before it meets the surface. */
    static final float BASE_FILL_ACCENT_SHARE = 0.30f;
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
    static final float BASE_SPECULAR_ALPHA = 0.085f;
    /** The lit upper rim, where the light actually meets the curve. The brightest of the four. */
    static final float BASE_RIM_LIGHT_ALPHA = 0.13f;
    /** The bounce off whatever is beneath the glass. Present at the foot, and easy to miss. */
    static final float BASE_REFLECTION_ALPHA = 0.05f;
    /** How much accent gathers at the side edges, where a pane of glass is thickest. */
    static final float BASE_REFRACTION_ALPHA = 0.16f;
    /** How far the body's interior travels between its lit top and its settled foot. */
    static final float BASE_INTERIOR_LIFT = 0.13f;

    /**
     * How thick the lit upper rim is allowed to get, whatever the control's proportions are.
     *
     * <p>This constant is the single most important number in the v0.8.0.0-beta.5 repair, so it is
     * worth being blunt about what it fixes. Beta 4 drew the rim as the first stop of a five-stop
     * vertical gradient, which means it occupied the top quarter of whatever it was drawn on. On a
     * 52dp search field that is a 13dp band of white at full rim strength, running the entire width
     * of the control - and on the Galaxy S25 Ultra the honest description of the result was a
     * polished metal tube. A rim light is the edge of a pane catching light. It is a couple of
     * pixels, it is not a quarter of the surface, and it must not grow because a control is wide.
     *
     * <p>So the rim is bounded in dp here and also capped as a share of the height below, which
     * means a small pill and a full-width bar get the same finish rather than the same proportion.
     */
    static final float RIM_MAX_DP = 2.5f;
    /** And never more than this share of a short control, so a tiny chip is not half rim. */
    static final float RIM_MAX_HEIGHT_SHARE = 0.16f;
    /** The reflection at the foot, bounded the same way and thinner still. */
    static final float REFLECTION_MAX_DP = 3.5f;
    static final float REFLECTION_MAX_HEIGHT_SHARE = 0.20f;

    /**
     * Where the specular glint sits and how far it reaches, both measured in multiples of the
     * control's <em>height</em>.
     *
     * <p>The other half of the same repair. Beta 4 used a diagonal {@code TL_BR} gradient, and a
     * diagonal across a 1000 by 160 pixel box is very nearly horizontal - so the "upper left glint"
     * became a sweep of white across the whole field. Deriving the glint's centre and radius from the
     * height instead means a wide control gets a highlight in its top-left corner and nothing
     * anywhere else, while a small square button gets a highlight that covers most of it. Same
     * renderer, same finish, correct at any aspect ratio.
     */
    private static final float SPECULAR_CENTER_X_SHARE = 0.55f;
    private static final float SPECULAR_CENTER_Y_SHARE = 0.05f;
    /** The glint's falloff. Most of the light is gone well before the edge of its radius. */
    private static final float[] SPECULAR_STOPS = {0f, 0.38f, 1f};
    private static final float[] SPECULAR_FALLOFF = {1f, 0.34f, 0f};

    /**
     * How far in from each side the accent refraction reaches, as a multiple of the height.
     *
     * <p>Also height-derived, and for the same reason: a fixed share of the width would put two large
     * violet regions on a wide search field, which is a tinted control rather than colour entering
     * glass at its thick edges.
     */
    private static final float REFRACTION_REACH = 1.15f;
    /** How much of the accent survives being mixed towards white to become refracted light. */
    private static final float REFRACTION_ACCENT_SHARE = 0.62f;

    /**
     * The body's own interior, top to foot.
     *
     * <p>Explicit positions rather than evenly spaced stops, so the tonal turn sits a little above
     * the middle where glass actually has one, and the settle towards the foot is long. The
     * v0.8.0.0-beta.5 change here is the bright central band: Beta 4's evenly spaced ramp put over
     * half the surface within a shade of its lit top, which is most of where the pearl came from.
     */
    private static final float[] INTERIOR_POSITIONS = {0f, 0.18f, 0.45f, 0.78f, 1f};
    private static final float[] INTERIOR_RAMP = {1f, 0.74f, 0.38f, 0.12f, 0f};
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
        final int surface2;
        final OrbitProStyle style;
        /**
         * Which of the three materials this surface is made of.
         *
         * <p>A free theme value, carried here alongside the premium styling rather than resolved
         * separately, so that one object answers every question a floating surface can ask: what
         * colours, what material, and how far the advanced tuning is allowed to move it.
         */
        final String material;

        private Palette(int accent, int background, int surface, int surface2,
                        OrbitProStyle style, String material) {
            this.accent = accent;
            this.background = background;
            this.surface = surface;
            this.surface2 = surface2;
            this.style = style == null ? OrbitProStyle.DEFAULT : style;
            this.material = OrbitTheme.normalizeMaterial(material);
        }

        /** The glass of the theme Orbit is drawing with, at the styling it is entitled to draw. */
        public static Palette live(Context c) {
            return new Palette(UiKit.accent(c), UiKit.BG, UiKit.SURFACE, UiKit.SURFACE_2,
                    OrbitProStyle.live(c), OrbitThemeStore.activeMaterial(c));
        }

        /** The glass a resolved theme would have. Used by the preview, which has no live canvas. */
        public static Palette of(OrbitThemeTokens tokens, OrbitProStyle style) {
            if (tokens == null) {
                return new Palette(UiKit.DEFAULT_ACCENT, UiKit.BG, UiKit.SURFACE, UiKit.SURFACE_2,
                        style, OrbitTheme.MATERIAL_DEFAULT);
            }
            return new Palette(tokens.accent, tokens.background, tokens.surface, tokens.surface2,
                    style, tokens.theme == null ? OrbitTheme.MATERIAL_DEFAULT : tokens.theme.material);
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
            Palette base = of(tokens, style);
            return new Palette(base.accent, pageBehind, base.surface, base.surface2,
                    base.style, base.material);
        }

        /** The same glass made of something else. Used by the Theme Studio material samples. */
        public Palette asMaterial(String value) {
            return new Palette(accent, background, surface, surface2, style, value);
        }

        public String material() {
            return material;
        }

        public int surface() {
            return surface;
        }

        public int surface2() {
            return surface2;
        }

        public int accent() {
            return accent;
        }

        public int background() {
            return background;
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
        return fillTop(p, 1f);
    }

    /**
     * The lit top, with the material's own share of the accent applied.
     *
     * <p>Frosted passes less than one. Its whole point is a pane that belongs to the theme rather than
     * announcing itself, and most of what made Beta 4's glass read as a separate design system was
     * how much accent-toward-white sat in the top of the body.
     */
    public static int fillTop(Palette p, float materialAccentShare) {
        int lit = UiKit.blend(p.accent, Color.WHITE,
                p.style.glassFillAccentShare() * materialAccentShare);
        return UiKit.blend(lit, p.surface,
                p.style.glassHighlightShare() * materialAccentShare);
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
        // The finish, so the answer follows the material a person actually chose rather than the
        // Liquid one. Frosted's milky lift and its quieter accent both move what a label reads against.
        Finish finish = finishFor(p);
        float share = finish.bodyAlpha / 255f;
        return UiKit.blend(finish.bodyMid(), p.background, share);
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
    /**
     * Every number one glass surface is drawn from, resolved once.
     *
     * <p>This exists so that "how strong is the highlight on Frosted" is a question with one answer in
     * one place, readable by the drawable that paints it and by the test that checks it. Beta 4 had the
     * same numbers spread across {@link OrbitProStyle} derivations and inline layer construction, which
     * was fine while there was one material and became untenable the moment there were two: a test
     * asserting "Frosted is quieter than Liquid" would otherwise have to reach into drawable internals
     * and compare gradient stop arrays.
     *
     * <p>The material multipliers are applied here and nowhere else. Everything above them - the Pro
     * opacity, tint and edge controls, and the ceilings they are bounded by - has already been resolved
     * against entitlement by the time this is built.
     */
    public static final class Finish {
        /** The material this finish belongs to, for anything that wants to say so in words. */
        public final String material;
        public final int bodyTop;
        public final int bodyFoot;
        public final int bodyAlpha;
        public final int stroke;
        public final float specularAlpha;
        /** The glint's reach, as a multiple of the control's height. Never of its width. */
        public final float specularSpread;
        public final float rimAlpha;
        public final float reflectionAlpha;
        public final float refractionAlpha;
        /**
         * The colour that gathers at the thick edges of the pane.
         *
         * <p>The theme's accent lifted towards white, not the accent itself. Refraction is coloured
         * <em>light</em> passing through a material, and a pure accent pooled at the edges reads as
         * two painted stripes rather than as anything optical.
         */
        public final int refractionColor;

        Finish(String material, int bodyTop, int bodyFoot, int bodyAlpha, int stroke,
               float specularAlpha, float specularSpread, float rimAlpha,
               float reflectionAlpha, float refractionAlpha, int refractionColor) {
            this.material = material;
            this.bodyTop = bodyTop;
            this.bodyFoot = bodyFoot;
            this.bodyAlpha = bodyAlpha;
            this.stroke = stroke;
            this.specularAlpha = specularAlpha;
            this.specularSpread = specularSpread;
            this.rimAlpha = rimAlpha;
            this.reflectionAlpha = reflectionAlpha;
            this.refractionAlpha = refractionAlpha;
            this.refractionColor = refractionColor;
        }

        /** The mid colour of the body, which is what a label on this surface reads against. */
        public int bodyMid() {
            return UiKit.blend(bodyTop, bodyFoot, 0.5f);
        }
    }

    /**
     * How much of each light layer a material keeps.
     *
     * <p>Frosted is not Liquid with the sliders down. It is a different intent: a soft translucent
     * pane you are meant to read through rather than look at, so its glint is weak and wide rather
     * than strong and local, its rim is a whisper, it has no bounce at the foot worth speaking of,
     * and its interior is lifted very slightly towards white so it reads as milky instead of merely
     * dark. Liquid keeps the dimensional treatment and is what the refined specular geometry is for.
     */
    private static final float FROSTED_SPECULAR = 0.38f;
    private static final float FROSTED_SPECULAR_SPREAD = 2.6f;
    private static final float FROSTED_RIM = 0.45f;
    private static final float FROSTED_REFLECTION = 0.35f;
    private static final float FROSTED_REFRACTION = 0.55f;
    private static final float FROSTED_ACCENT = 0.45f;
    /** The milk. A few percent of white through the whole body, which is what "frosted" means. */
    private static final float FROSTED_HAZE = 0.055f;

    private static final float LIQUID_SPECULAR_SPREAD = 1.45f;

    /**
     * The glass a palette asks for, as numbers.
     *
     * <p>Solid never reaches here: it is not glass and {@link OrbitFloatingSurface} answers for it.
     */
    public static Finish finishFor(Palette p) {
        boolean frosted = OrbitTheme.MATERIAL_FROSTED.equals(p.material);
        float accentShare = frosted ? FROSTED_ACCENT : 1f;
        int top = fillTop(p, accentShare);
        if (frosted) top = UiKit.blend(Color.WHITE, top, FROSTED_HAZE);
        // The foot settles further back towards the page the more lit the material is, which is what
        // gives the interior somewhere to travel rather than a single flat field.
        int foot = UiKit.blend(fillBottom(p), p.background, 1f - p.style.glassInteriorLift());
        if (frosted) foot = UiKit.blend(Color.WHITE, foot, FROSTED_HAZE * 0.6f);
        return new Finish(
                p.material, top, foot, p.style.glassOpacity, borderColor(p),
                p.style.glassSpecularAlpha() * (frosted ? FROSTED_SPECULAR : 1f),
                frosted ? FROSTED_SPECULAR_SPREAD : LIQUID_SPECULAR_SPREAD,
                p.style.glassRimLightAlpha() * (frosted ? FROSTED_RIM : 1f),
                p.style.glassReflectionAlpha() * (frosted ? FROSTED_REFLECTION : 1f),
                p.style.glassRefractionAlpha() * (frosted ? FROSTED_REFRACTION : 1f),
                UiKit.blend(p.accent, Color.WHITE, REFRACTION_ACCENT_SHARE));
    }

    /**
     * A glass floating surface: Frosted or Liquid, depending on the palette's material.
     *
     * <p>One {@link Drawable} rather than a stack of five, and that is the v0.8.0.0-beta.5 change that
     * makes the rest of this release possible. A {@code LayerDrawable} of gradients can only express
     * geometry as a share of the box it lands in, so every highlight in Beta 4 grew with the control -
     * which is exactly why a wide search field ended up looking like chrome. This asks its own bounds
     * instead, and derives every highlight from the <em>height</em>: a local glint in the top-left
     * corner, a rim bounded in dp, a thin bounce at the foot, and accent pooled within a height's
     * reach of each side. The same finish therefore reads correctly on a full-width bar, a compact
     * pill and a square button without any of them telling it what they are.
     *
     * <p>Still nothing captured, still no blur, still no API-level branch. Shaders are built once when
     * the bounds are set and the surface holds still for the life of the control, so a scroll costs
     * what it always did.
     */
    public static Drawable surfaceDrawable(Context c, float radiusDp) {
        return surfaceDrawable(c, Palette.live(c), radiusDp);
    }

    public static GlassDrawable surfaceDrawable(Context c, Palette p, float radiusDp) {
        return new GlassDrawable(finishFor(p), UiKit.dp(c, radiusDp),
                UiKit.dp(c, BORDER_WIDTH_DP), UiKit.dp(c, RIM_MAX_DP),
                UiKit.dp(c, REFLECTION_MAX_DP));
    }

    public static Drawable surfaceDrawable(Context c) {
        return surfaceDrawable(c, RADIUS_DP);
    }

    /**
     * Orbit's glass, drawn from its own bounds.
     *
     * <p>Six paints, five of which are shaders rebuilt whenever the control is resized and never
     * otherwise. There are no bitmaps here, nothing is captured from behind the control, and nothing
     * is recomputed per frame: {@link #draw} is a handful of {@code drawRoundRect} calls against
     * shaders that were already prepared.
     */
    public static final class GlassDrawable extends Drawable {

        private final Finish finish;
        private final float radius;
        private final float strokeWidth;
        private final float rimMaxPx;
        private final float reflectionMaxPx;

        private final RectF rect = new RectF();
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint refraction = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);

        GlassDrawable(Finish finish, float radius, float strokeWidth,
                      float rimMaxPx, float reflectionMaxPx) {
            this.finish = finish;
            this.radius = radius;
            this.strokeWidth = Math.max(1f, strokeWidth);
            this.rimMaxPx = rimMaxPx;
            this.reflectionMaxPx = reflectionMaxPx;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(this.strokeWidth);
            edge.setColor(finish.stroke);
        }

        /** The numbers this surface was built from, for the screens and tests that ask. */
        public Finish finish() {
            return finish;
        }

        /** How thick the lit rim actually came out, in pixels, at the size this was last given. */
        public float rimHeightPx() {
            return Math.min(rimMaxPx, rect.height() * RIM_MAX_HEIGHT_SHARE);
        }

        /** How far the glint reaches. Derived from the height, so a wide control does not widen it. */
        public float specularRadiusPx() {
            return rect.height() * finish.specularSpread;
        }

        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            float inset = strokeWidth / 2f;
            rect.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            float w = rect.width();
            float h = rect.height();
            if (w <= 0f || h <= 0f) {
                clearShaders();
                return;
            }

            int[] interior = new int[INTERIOR_RAMP.length];
            for (int i = 0; i < INTERIOR_RAMP.length; i++) {
                interior[i] = UiKit.withAlpha(
                        UiKit.blend(finish.bodyTop, finish.bodyFoot, INTERIOR_RAMP[i]),
                        finish.bodyAlpha);
            }
            body.setShader(new LinearGradient(0f, rect.top, 0f, rect.bottom,
                    interior, INTERIOR_POSITIONS, Shader.TileMode.CLAMP));

            // Accent pooled within one height of each side, so the middle of a wide control stays
            // the theme's own colour instead of becoming a violet field.
            float reach = Math.min(0.45f, (h * REFRACTION_REACH) / w);
            int pool = alphaOf(finish.refractionAlpha);
            refraction.setShader(pool == 0 ? null : new LinearGradient(
                    rect.left, 0f, rect.right, 0f,
                    new int[]{
                            UiKit.withAlpha(finish.refractionColor, pool),
                            UiKit.withAlpha(finish.refractionColor, Math.round(pool * 0.22f)),
                            Color.TRANSPARENT,
                            UiKit.withAlpha(finish.refractionColor, Math.round(pool * 0.22f)),
                            UiKit.withAlpha(finish.refractionColor, pool)},
                    new float[]{0f, reach * 0.55f, 0.5f, 1f - reach * 0.55f, 1f},
                    Shader.TileMode.CLAMP));

            int glint = alphaOf(finish.specularAlpha);
            specular.setShader(glint == 0 ? null : new RadialGradient(
                    rect.left + h * SPECULAR_CENTER_X_SHARE,
                    rect.top + h * SPECULAR_CENTER_Y_SHARE,
                    Math.max(1f, h * finish.specularSpread),
                    new int[]{
                            UiKit.withAlpha(Color.WHITE, glint),
                            UiKit.withAlpha(Color.WHITE, Math.round(glint * SPECULAR_FALLOFF[1])),
                            Color.TRANSPARENT},
                    SPECULAR_STOPS, Shader.TileMode.CLAMP));

            int lit = alphaOf(finish.rimAlpha);
            float rimHeight = Math.max(1f, Math.min(rimMaxPx, h * RIM_MAX_HEIGHT_SHARE));
            rim.setShader(lit == 0 ? null : new LinearGradient(
                    0f, rect.top, 0f, rect.top + rimHeight,
                    new int[]{UiKit.withAlpha(Color.WHITE, lit), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));

            int bounce = alphaOf(finish.reflectionAlpha);
            float reflectionHeight =
                    Math.max(1f, Math.min(reflectionMaxPx, h * REFLECTION_MAX_HEIGHT_SHARE));
            reflection.setShader(bounce == 0 ? null : new LinearGradient(
                    0f, rect.bottom, 0f, rect.bottom - reflectionHeight,
                    new int[]{UiKit.withAlpha(Color.WHITE, bounce), Color.TRANSPARENT},
                    null, Shader.TileMode.CLAMP));
        }

        private void clearShaders() {
            body.setShader(null);
            refraction.setShader(null);
            specular.setShader(null);
            rim.setShader(null);
            reflection.setShader(null);
        }

        private static int alphaOf(float share) {
            return Math.round(Math.max(0f, Math.min(1f, share)) * 255f);
        }

        @Override public void draw(Canvas canvas) {
            if (rect.isEmpty()) return;
            if (body.getShader() != null) canvas.drawRoundRect(rect, radius, radius, body);
            if (refraction.getShader() != null) {
                canvas.drawRoundRect(rect, radius, radius, refraction);
            }
            if (specular.getShader() != null) canvas.drawRoundRect(rect, radius, radius, specular);
            if (rim.getShader() != null) canvas.drawRoundRect(rect, radius, radius, rim);
            if (reflection.getShader() != null) {
                canvas.drawRoundRect(rect, radius, radius, reflection);
            }
            canvas.drawRoundRect(rect, radius, radius, edge);
        }

        /**
         * The rounded outline, so a floated control's elevation casts the right shadow.
         *
         * <p>A custom drawable that does not answer this gets a rectangular shadow under a rounded
         * control, which is the one visible way this refactor could have gone wrong quietly.
         */
        @Override public void getOutline(android.graphics.Outline outline) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            outline.setRoundRect(bounds, radius);
        }

        @Override public void setAlpha(int value) {
            body.setAlpha(value);
            refraction.setAlpha(value);
            specular.setAlpha(value);
            rim.setAlpha(value);
            reflection.setAlpha(value);
            edge.setAlpha(value);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            body.setColorFilter(filter);
            refraction.setColorFilter(filter);
            specular.setColorFilter(filter);
            rim.setColorFilter(filter);
            reflection.setColorFilter(filter);
            edge.setColorFilter(filter);
        }

        @Override public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    // The ripple and the float-a-control helpers used to live here and moved to
    // OrbitFloatingSurface in v0.8.0.0-beta.5, deliberately rather than for tidiness. A control is
    // dressed in whichever material the theme asks for, and leaving a glass-only version of those two
    // calls behind would have left a way to dress a control as glass on a theme that had chosen Solid -
    // which is precisely the bug the central resolver exists to make impossible. What stays here is
    // what is genuinely about glass: the colours, the finish, the drawable, and the scrim below.

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

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Liquid Orbit Glass: the material, and the three ways it could stop being one.
 *
 * <p>v0.8.0.0-beta.4 made Orbit's floating chrome considerably richer, and the honest summary of the
 * complaint it answers is that the old treatment was a slightly see-through rounded rectangle. It is
 * now five layers - a body with real interior depth, accent refraction at its sides, a directional
 * specular sweep, a lit upper rim and a faint reflection at its foot. None of that is pixel-testable
 * and this file does not try; whether it is beautiful is a question for a Galaxy S25 Ultra.
 *
 * <p>What a machine can settle is the three failures that would each be quiet and expensive.
 *
 * <p><b>A second glass.</b> Making the material richer is exactly the moment somebody writes a
 * nicer one next to the old one, leaves Chats and the Vault on the plain path, and points the Theme
 * Studio preview at the new one - so the preview would be showing a material the app does not draw.
 * {@code OrbitGlass} being the only implementation is asserted structurally.
 *
 * <p><b>Controls that do not control anything.</b> Three sliders that all look like they work is
 * worse than one that does. Each of the three is asserted to produce a genuinely different rendered
 * configuration at its minimum, default and maximum, read off the actual drawable rather than off
 * the arithmetic that produced it.
 *
 * <p><b>Paying for it.</b> The cheap way to get convincing depth is to capture what is behind the
 * control and blur it every frame. Orbit's floor is API 29, that would be the only route, and the
 * absence of it is asserted directly rather than trusted to a comment.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class LiquidGlassTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiKit.syncTheme(context);
    }

    private OrbitGlass.Palette palette(OrbitTheme theme, OrbitProStyle style) {
        return OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, theme), style);
    }

    private LayerDrawable glass(OrbitProStyle style) {
        return OrbitGlass.surfaceDrawable(context,
                palette(OrbitTheme.orbitDefault(), style), OrbitGlass.RADIUS_DP);
    }

    /** Every colour in one layer of the material, so a change to it can be seen rather than argued. */
    private static int[] layerColors(LayerDrawable glass, int layer) {
        GradientDrawable gradient = (GradientDrawable) glass.getDrawable(layer);
        int[] colors = gradient.getColors();
        assertNotNull("layer " + layer + " must be a gradient, not a flat fill", colors);
        return colors;
    }

    /** The strongest alpha anywhere in one layer. What "how lit is this" actually reduces to. */
    private static int peakAlpha(LayerDrawable glass, int layer) {
        int peak = 0;
        for (int color : layerColors(glass, layer)) peak = Math.max(peak, Color.alpha(color));
        return peak;
    }

    private static final int BODY = 0;
    private static final int REFRACTION = 1;
    private static final int SPECULAR = 2;
    private static final int RIM = 3;
    private static final int REFLECTION = 4;

    // ---- 1, 2, 3 and 15. one implementation, and everything points at it ---------------------------

    /**
     * 1 and 15. {@code OrbitGlass} is still the only thing that knows what Orbit's glass looks like.
     *
     * <p>Written against the shapes a second implementation would actually take: a class named for
     * premium or liquid glass, or a method that builds a translucent layered surface somewhere else.
     * The layer names themselves are the giveaway, because a file that assembles a specular sweep is
     * a file that has its own opinion about the material.
     */
    @Test public void orbitGlassRemainsTheOnlyLiquidGlassImplementation() {
        List<String> offenders = new ArrayList<>();
        for (Path source : ThemeStudioProTest.mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitGlass.java")) continue;
            String body = ThemeStudioProTest.readSourceFile(name);
            if (body.contains("class OrbitGlass2") || body.contains("class LiquidGlass")
                    || body.contains("class OrbitProGlass") || body.contains("class GlassSurface")
                    || body.contains("premiumGlass(") || body.contains("liquidGlass(")
                    || body.contains("specularSweep(") || body.contains("rimLight(")) {
                offenders.add(name);
            }
        }
        assertTrue("there must be exactly one glass: " + offenders, offenders.isEmpty());
    }

    /** 2 and 3. The real surfaces and the Theme Studio sample all get their glass from one place. */
    @Test public void everyGlassConsumerStillReachesOrbitGlass() {
        for (String file : new String[]{"MainActivity.java", "OrbitVaultActivity.java",
                "GlassStylePreview.java"}) {
            assertTrue(file + " must get its glass from OrbitGlass",
                    ThemeStudioProTest.readSourceFile(file).contains("OrbitGlass."));
        }
        assertTrue("the Theme Studio sample must be drawn by the shared builder",
                ThemeStudioProTest.readSourceFile("GlassStylePreview.java")
                        .contains("OrbitGlass.surfaceDrawable("));
        assertFalse("and must not hand-roll a translucent rectangle",
                ThemeStudioProTest.readSourceFile("GlassStylePreview.java")
                        .contains("GradientDrawable"));
    }

    /** The material is the same five layers whichever supported Android version draws it. */
    @Test public void theMaterialIsFiveGradientLayers() {
        LayerDrawable glass = glass(OrbitProStyle.DEFAULT);
        assertEquals(5, glass.getNumberOfLayers());
        for (int i = 0; i < glass.getNumberOfLayers(); i++) {
            assertTrue("layer " + i + " must be a gradient",
                    glass.getDrawable(i) instanceof GradientDrawable);
        }
        assertTrue("the body must have real interior depth rather than two stops",
                layerColors(glass, BODY).length >= 4);
    }

    // ---- 4. a Beta 3 install comes back intact -----------------------------------------------------

    /**
     * 4. Every glass value a Beta 3 device could have stored still means what it meant.
     *
     * <p>The opacity floor dropped from 170 to 130 in this release, which is the whole of the
     * migration: the range grew downwards, so every value that was storable before is storable now
     * and is returned unchanged. Asserted across the old range rather than at its endpoints, because
     * a clamp that had moved the wrong way would still pass at 214.
     */
    @Test public void everyBetaThreeGlassSettingMigratesUnchanged() {
        for (int opacity = 170; opacity <= 245; opacity++) {
            OrbitProStyle stored = OrbitProStyle.DEFAULT.withGlassOpacity(opacity);
            assertEquals("a stored Beta 3 opacity must survive verbatim",
                    opacity, stored.glassOpacity);
        }
        for (int strength = 0; strength <= 200; strength += 5) {
            assertEquals(strength, OrbitProStyle.DEFAULT.withGlassTint(strength).glassTint);
            assertEquals(strength, OrbitProStyle.DEFAULT.withGlassEdge(strength).glassEdge);
        }
        // And the values a Beta 3 device would have written are read back as themselves.
        Prefs.get(context).edit()
                .putInt(Prefs.THEME_PRO_GLASS_OPACITY, 188)
                .putInt(Prefs.THEME_PRO_GLASS_TINT, 155)
                .putInt(Prefs.THEME_PRO_GLASS_EDGE, 160)
                .commit();
        OrbitProStyle stored = OrbitThemeStore.activeProStyle(context);
        assertEquals(188, stored.glassOpacity);
        assertEquals(155, stored.glassTint);
        assertEquals(160, stored.glassEdge);
        assertEquals("and a Beta 3 install has no background effect to restore",
                OrbitProStyle.BACKGROUND_SOLID, stored.backgroundMode);
    }

    // ---- 5, 6 and 7. each control actually moves the material --------------------------------------

    /**
     * 5. Opacity reads as three genuinely different materials.
     *
     * <p>Asserted on the body's own alpha and on what a label ends up being read against, because
     * those are the two things opacity is for. The light layers are asserted <em>not</em> to move,
     * which is the reason thin glass still looks like glass rather than fading towards nothing.
     */
    @Test public void glassOpacityProducesThreeDifferentMaterials() {
        LayerDrawable thin = glass(
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN));
        LayerDrawable middle = glass(OrbitProStyle.DEFAULT);
        LayerDrawable solid = glass(
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MAX));

        assertTrue("the body must get steadily more substantial",
                peakAlpha(thin, BODY) < peakAlpha(middle, BODY));
        assertTrue(peakAlpha(middle, BODY) < peakAlpha(solid, BODY));
        assertTrue("and the low end must be genuinely translucent",
                peakAlpha(thin, BODY) < 160);
        assertTrue("while the high end never becomes an opaque card",
                peakAlpha(solid, BODY) < 255);

        assertEquals("light on the surface does not fade because the material got thinner",
                peakAlpha(middle, SPECULAR), peakAlpha(thin, SPECULAR));
        assertEquals(peakAlpha(middle, RIM), peakAlpha(thin, RIM));

        int thinFill = OrbitGlass.effectiveFill(palette(OrbitTheme.orbitDefault(),
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN)));
        int solidFill = OrbitGlass.effectiveFill(palette(OrbitTheme.orbitDefault(),
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MAX)));
        assertTrue("and what a label is read against must actually change", thinFill != solidFill);
    }

    /**
     * 6. Tint is the accent character of the whole material, not only the hairline.
     *
     * <p>At its minimum the glass is neutral, which means the refraction layer carries no colour at
     * all - the surface still lifts off the page and still catches light, it simply has nothing of
     * the theme in it. At its maximum the accent is visible at the edges, in the hairline and in the
     * body, and still bounded well short of a block of accent.
     */
    @Test public void glassTintProducesThreeDifferentAccentCharacters() {
        OrbitProStyle neutral = OrbitProStyle.DEFAULT.withGlassTint(OrbitProStyle.GLASS_TINT_MIN);
        OrbitProStyle standard = OrbitProStyle.DEFAULT;
        OrbitProStyle infused = OrbitProStyle.DEFAULT.withGlassTint(OrbitProStyle.GLASS_TINT_MAX);

        assertEquals("neutral glass carries no accent at its edges at all",
                0, peakAlpha(glass(neutral), REFRACTION));
        assertTrue("the default carries some",
                peakAlpha(glass(standard), REFRACTION) > 0);
        assertTrue("and the maximum carries more",
                peakAlpha(glass(infused), REFRACTION) > peakAlpha(glass(standard), REFRACTION));
        assertTrue("bounded, so it stays refraction rather than a block of accent",
                infused.glassRefractionAlpha() <= OrbitProStyle.MAX_REFRACTION_ALPHA);

        OrbitGlass.Palette neutralPalette = palette(OrbitTheme.orbitDefault(), neutral);
        OrbitGlass.Palette infusedPalette = palette(OrbitTheme.orbitDefault(), infused);
        assertTrue("the hairline must move too",
                OrbitGlass.borderColor(neutralPalette) != OrbitGlass.borderColor(infusedPalette));
        assertTrue("and so must the body's lit top",
                OrbitGlass.fillTop(neutralPalette) != OrbitGlass.fillTop(infusedPalette));
        assertTrue("and the scrim's haze under the chrome",
                OrbitGlass.hazeColor(neutralPalette) != OrbitGlass.hazeColor(infusedPalette));
    }

    /**
     * 7. Edge drives every light layer, which is what finally makes it worth having a control for.
     *
     * <p>In Beta 3 this moved a hairline's alpha and a little of the top highlight, which is not
     * enough difference to justify asking somebody to choose. It now drives the specular sweep, the
     * lit rim, the reflection and the hairline together, and its minimum is subdued rather than
     * absent - a surface with no light on it stops being glass, and no Orbit setting should turn a
     * material into a flat fill.
     */
    @Test public void glassEdgeProducesThreeDifferentSpecularCharacters() {
        OrbitProStyle flat = OrbitProStyle.DEFAULT.withGlassEdge(OrbitProStyle.GLASS_EDGE_MIN);
        OrbitProStyle polished = OrbitProStyle.DEFAULT;
        OrbitProStyle lit = OrbitProStyle.DEFAULT.withGlassEdge(OrbitProStyle.GLASS_EDGE_MAX);

        for (int layer : new int[]{SPECULAR, RIM}) {
            assertTrue("layer " + layer + " must strengthen from subdued to polished",
                    peakAlpha(glass(flat), layer) < peakAlpha(glass(polished), layer));
            assertTrue("layer " + layer + " must strengthen from polished to pronounced",
                    peakAlpha(glass(polished), layer) < peakAlpha(glass(lit), layer));
            assertTrue("and must never disappear entirely",
                    peakAlpha(glass(flat), layer) > 0);
        }
        assertTrue("the hairline must strengthen with it",
                lit.glassBorderAlpha() > flat.glassBorderAlpha());

        // Bounded on every layer, so the maximum is reflective material rather than a neon border.
        assertTrue(lit.glassSpecularAlpha() <= OrbitProStyle.MAX_SPECULAR_ALPHA);
        assertTrue(lit.glassRimLightAlpha() <= OrbitProStyle.MAX_RIM_LIGHT_ALPHA);
        assertTrue(lit.glassReflectionAlpha() <= OrbitProStyle.MAX_REFLECTION_ALPHA);
        assertTrue(lit.glassBorderAlpha() <= OrbitProStyle.MAX_BORDER_ALPHA);
        assertTrue("the rim is a highlight, never an opaque outline",
                peakAlpha(glass(lit), RIM) < 128);
        assertTrue("and the reflection stays understated",
                peakAlpha(glass(lit), REFLECTION) < peakAlpha(glass(lit), RIM));
    }

    // ---- 8 to 11. it stays readable over everything Orbit can draw behind it -----------------------

    /**
     * 8 and 9. Legible on an ordinary dark page and on true black, at every allowed setting.
     *
     * <p>Every combination of the three controls at its extremes, against every preset Orbit ships,
     * which includes the true-black ones. Read against {@code effectiveFill}, which is the body
     * composited over the page and deliberately excludes the light on top of it, so this is the
     * dimmest part of the control rather than the brightest.
     */
    @Test public void liquidGlassStaysReadableOnEveryShippedPage() {
        for (int opacity : new int[]{OrbitProStyle.GLASS_OPACITY_MIN,
                OrbitProStyle.GLASS_OPACITY_DEFAULT, OrbitProStyle.GLASS_OPACITY_MAX}) {
            for (int tint : new int[]{OrbitProStyle.GLASS_TINT_MIN,
                    OrbitProStyle.GLASS_TINT_DEFAULT, OrbitProStyle.GLASS_TINT_MAX}) {
                for (OrbitTheme preset : OrbitTheme.builtIns()) {
                    OrbitProStyle style = preset.pro
                            .withGlassOpacity(opacity).withGlassTint(tint);
                    int fill = OrbitGlass.effectiveFill(palette(preset, style));
                    assertReadable(preset.name + " at opacity " + opacity + ", tint " + tint, fill);
                }
            }
        }
    }

    /**
     * 10 and 11. Legible over a premium background too, which is a different page from the base one.
     *
     * <p>This is the pairing advanced backgrounds could have broken without anybody noticing: the
     * glass is translucent, so the thing behind it is no longer the theme's Background colour once a
     * gradient or a glow is drawn over it. The centre of the brightest glow Orbit allows is the worst
     * case, and it is checked at the most see-through setting the glass offers.
     */
    @Test public void liquidGlassStaysReadableOverAdvancedBackgrounds() {
        OrbitProStyle linear = OrbitProStyle.DEFAULT
                .withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN)
                .withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR)
                .withBackgroundEffectColor(OrbitTheme.ACCENT);
        OrbitProStyle glow = OrbitProStyle.DEFAULT
                .withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN)
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW)
                .withBackgroundEffectColor(OrbitTheme.ACCENT)
                .withGlowStrength(OrbitProStyle.GLOW_STRENGTH_MAX)
                .withGlowSize(OrbitProStyle.GLOW_SIZE_MAX);

        for (OrbitProStyle style : new OrbitProStyle[]{linear, glow}) {
            for (OrbitTheme preset : OrbitTheme.builtIns()) {
                OrbitTheme themed = preset.withPro(style);
                OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, themed);
                int behind = OrbitBackground.effectivePageColor(context,
                        OrbitBackground.Page.of(tokens, style));
                int fill = OrbitGlass.effectiveFill(
                        OrbitGlass.Palette.over(tokens, style, behind));
                assertReadable(preset.name + " glass over "
                        + style.backgroundModeLabel(), fill);
            }
        }
    }

    private static void assertReadable(String what, int fill) {
        int ink = OrbitContrast.inkOn(fill);
        assertTrue(what + " must stay readable, got "
                        + OrbitContrast.contrastRatio(ink, fill),
                OrbitContrast.contrastRatio(ink, fill) >= OrbitContrast.BODY_TEXT_MIN);
    }

    // ---- 12, 13 and 14. it did not become expensive ------------------------------------------------

    /**
     * 12 and 13. No backdrop was ever captured, and nothing processes one continuously.
     *
     * <p>The cheap way to convincing glass on API 29 is to screenshot what is behind the control and
     * blur the bitmap, which means allocating and blurring on every frame of every scroll. That is
     * the thing this release deliberately did not do, and the assertion is on the calls it would
     * have had to make rather than on the word "blur", because {@code OrbitGlass} explains at length
     * why it avoids both platform blur APIs and a test that banned the name would force Orbit to
     * delete the explanation to keep the guard.
     */
    @Test public void noBackdropIsCapturedOrBlurred() {
        for (String file : new String[]{"OrbitGlass.java", "GlassStylePreview.java",
                "OrbitBackground.java", "BackgroundStylePreview.java"}) {
            String body = ThemeStudioProTest.readSourceFile(file);
            for (String forbidden : new String[]{".setRenderEffect(", ".setBackgroundBlurRadius(",
                    "Bitmap.createBitmap(", "PixelCopy", "getDrawingCache", "setDrawingCacheEnabled",
                    "RenderScript", "ScriptIntrinsicBlur", "new Canvas("}) {
                assertFalse(file + " must not " + forbidden, body.contains(forbidden));
            }
        }
        assertTrue("and the reason must stay written down",
                ThemeStudioProTest.readSourceFile("OrbitGlass.java").contains("There is no blur"));
    }

    /** 14. Still no blur library anywhere in the build, and still no API-level branch. */
    @Test public void noBlurDependencyAndNoVersionBranch() {
        String gradle = OrbitGlassChromeTest.readRepositoryFile("app/build.gradle");
        for (String library : new String[]{"renderscript", "blurry", "blurkit", "dimezis",
                "haze", "RenderScript"}) {
            assertFalse("Orbit must not take a dependency for this: " + library,
                    gradle.contains(library));
        }
        String glass = ThemeStudioProTest.readSourceFile("OrbitGlass.java");
        assertFalse("no API-level branch to keep working", glass.contains("SDK_INT"));
        assertFalse(glass.contains("VERSION_CODES"));
    }

    /** The material is built once and holds still, so a scroll costs nothing it did not before. */
    @Test public void theMaterialIsStaticOnceBuilt() {
        String glass = ThemeStudioProTest.readSourceFile("OrbitGlass.java");
        assertFalse("no glass may be rebuilt from a scroll callback",
                glass.contains("surfaceDrawable(") && glass.contains("onScrollChanged"));
        assertFalse("and nothing here draws per frame",
                glass.contains("invalidate()") || glass.contains("postOnAnimation"));
    }
}

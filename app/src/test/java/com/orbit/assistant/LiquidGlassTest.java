package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;

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
 * <p>v0.8.0.0-beta.4 made Orbit's floating chrome considerably richer, and v0.8.0.0-beta.5 made it
 * considerably more restrained after a Galaxy S25 Ultra showed what "richer" had actually produced on a
 * full-width search field: a broad white band across the top and a diagonal sweep across the whole
 * control, which together read as polished metal rather than glass. The material is now a body with
 * real interior depth, accent pooled at its sides, a <em>local</em> glint, an edge-bounded rim and a
 * faint bounce at the foot - and every one of those is sized from the control's height rather than its
 * shape. None of it is pixel-testable and this file does not try; whether it is beautiful is a question
 * for the device.
 *
 * <p>What a machine can settle is the four failures that would each be quiet and expensive.
 *
 * <p><b>A highlight that grows with the control.</b> The Beta 4 fault, and the one this release exists
 * for. Asserted as geometry: a control ten times wider than another of the same height must get an
 * identical highlight, and the rim must stay bounded in absolute terms rather than as a share.
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

    /** The Liquid material on Orbit's own theme, which is what these tests are mostly about. */
    private OrbitGlass.Palette palette(OrbitTheme theme, OrbitProStyle style) {
        return OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, theme), style)
                .asMaterial(OrbitTheme.MATERIAL_LIQUID);
    }

    private OrbitGlass.Finish finish(OrbitProStyle style) {
        return OrbitGlass.finishFor(palette(OrbitTheme.orbitDefault(), style));
    }

    private OrbitGlass.Finish finish(String material, OrbitProStyle style) {
        return OrbitGlass.finishFor(
                OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()),
                        style).asMaterial(material));
    }

    /**
     * A glass surface laid out at a real control size, so its bounds-derived geometry is resolved.
     *
     * <p>Every highlight in the v0.8.0.0-beta.5 material is a function of the height it is drawn at,
     * so a drawable that was never given bounds has no geometry to assert about.
     */
    private OrbitGlass.GlassDrawable laidOut(String material, OrbitProStyle style,
                                             int width, int height) {
        OrbitGlass.GlassDrawable glass = OrbitGlass.surfaceDrawable(context,
                OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()),
                        style).asMaterial(material), OrbitGlass.RADIUS_DP);
        glass.setBounds(0, 0, width, height);
        return glass;
    }

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

    /** 2 and 3. The real surfaces and the Theme Studio sample all get their surface from one place. */
    @Test public void everyGlassConsumerStillReachesTheSharedResolver() {
        for (String file : new String[]{"MainActivity.java", "OrbitVaultActivity.java",
                "GlassStylePreview.java"}) {
            assertTrue(file + " must get its floating surface from OrbitFloatingSurface",
                    ThemeStudioProTest.readSourceFile(file).contains("OrbitFloatingSurface."));
        }
        assertTrue("the Theme Studio sample must be drawn by the shared builder",
                ThemeStudioProTest.readSourceFile("GlassStylePreview.java")
                        .contains("OrbitFloatingSurface.surfaceDrawable("));
        assertFalse("and must not hand-roll a translucent rectangle",
                ThemeStudioProTest.readSourceFile("GlassStylePreview.java")
                        .contains("GradientDrawable"));
        assertTrue("and OrbitGlass must still be the thing that renders the glass itself",
                ThemeStudioProTest.readSourceFile("OrbitFloatingSurface.java")
                        .contains("OrbitGlass.surfaceDrawable("));
    }

    /** The material is one bounds-aware drawable whose body has real interior depth. */
    @Test public void theMaterialIsOneBoundsAwareDrawable() {
        OrbitGlass.GlassDrawable glass =
                laidOut(OrbitTheme.MATERIAL_LIQUID, OrbitProStyle.DEFAULT, 900, 150);
        OrbitGlass.Finish finish = glass.finish();
        assertEquals(OrbitTheme.MATERIAL_LIQUID, finish.material);
        assertTrue("the body must be translucent", finish.bodyAlpha < 255);
        assertTrue("and must travel between two different tones",
                finish.bodyTop != finish.bodyFoot);
        assertTrue("the glint must have somewhere to fall", glass.specularRadiusPx() > 0f);
        assertTrue("and the rim must be a real thickness", glass.rimHeightPx() > 0f);
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
        OrbitGlass.Finish thin =
                finish(OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN));
        OrbitGlass.Finish middle = finish(OrbitProStyle.DEFAULT);
        OrbitGlass.Finish dense =
                finish(OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MAX));

        assertTrue("the body must get steadily more substantial", thin.bodyAlpha < middle.bodyAlpha);
        assertTrue(middle.bodyAlpha < dense.bodyAlpha);
        assertTrue("and the low end must be genuinely translucent", thin.bodyAlpha < 160);
        assertTrue("while the high end never becomes an opaque card", dense.bodyAlpha < 255);

        assertEquals("light on the surface does not fade because the material got thinner",
                middle.specularAlpha, thin.specularAlpha, 0.0001f);
        assertEquals(middle.rimAlpha, thin.rimAlpha, 0.0001f);

        int thinFill = OrbitGlass.effectiveFill(palette(OrbitTheme.orbitDefault(),
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MIN)));
        int denseFill = OrbitGlass.effectiveFill(palette(OrbitTheme.orbitDefault(),
                OrbitProStyle.DEFAULT.withGlassOpacity(OrbitProStyle.GLASS_OPACITY_MAX)));
        assertTrue("and what a label is read against must actually change", thinFill != denseFill);
    }

    /**
     * 22 and 23. The highlight does not grow because a control is wide.
     *
     * <p>This is the whole of the Beta 5 repair, asserted as geometry rather than as taste. The Chats
     * search field on a Galaxy S25 Ultra is roughly a thousand pixels wide and a hundred and fifty
     * tall, and in Beta 4 both the rim and the specular sweep were shares of that box: a white band
     * across the top quarter, and a diagonal that on a box of that aspect ratio is very nearly a
     * horizontal sweep. The honest description of the result was a polished metal tube.
     *
     * <p>So the rim is bounded in dp and the glint's radius is a multiple of the height. A control ten
     * times wider than another of the same height must get an identical highlight, and both must stay
     * small relative to the width.
     */
    @Test public void aWideControlDoesNotGetAWiderHighlight() {
        int height = 150;
        OrbitGlass.GlassDrawable narrow =
                laidOut(OrbitTheme.MATERIAL_LIQUID, OrbitProStyle.DEFAULT, 220, height);
        OrbitGlass.GlassDrawable wide =
                laidOut(OrbitTheme.MATERIAL_LIQUID, OrbitProStyle.DEFAULT, 2200, height);

        assertEquals("the rim must be the same thickness whatever the width",
                narrow.rimHeightPx(), wide.rimHeightPx(), 0.01f);
        assertEquals("and the glint must reach exactly as far",
                narrow.specularRadiusPx(), wide.specularRadiusPx(), 0.01f);

        // Bounded twice: by an absolute thickness, and by a share of the height. Whichever binds, the
        // rim is a lit edge rather than the band across the top quarter that Beta 4 drew.
        float cap = UiKit.dp(context, OrbitGlass.RIM_MAX_DP);
        assertTrue("the rim must stay a lit edge rather than a band", wide.rimHeightPx() <= cap);
        assertTrue("and must never be a quarter of the surface, which is what Beta 4 drew",
                wide.rimHeightPx() <= height * OrbitGlass.RIM_MAX_HEIGHT_SHARE);
        assertTrue("the glint must cover a fraction of a wide control rather than sweeping it",
                wide.specularRadiusPx() < 2200 * 0.25f);

        // A short control is held to the same share, so a small chip is not half edge light.
        OrbitGlass.GlassDrawable chip =
                laidOut(OrbitTheme.MATERIAL_LIQUID, OrbitProStyle.DEFAULT, 120, 40);
        assertTrue("a short control's rim is bounded by its own height",
                chip.rimHeightPx() <= Math.max(1f, 40 * OrbitGlass.RIM_MAX_HEIGHT_SHARE));
        assertTrue("and its glint shrinks with it", chip.specularRadiusPx()
                < wide.specularRadiusPx());

        // And the peak white a Liquid surface may carry is well under half, at the strongest edge.
        OrbitGlass.Finish lit =
                finish(OrbitProStyle.DEFAULT.withGlassEdge(OrbitProStyle.GLASS_EDGE_MAX));
        assertTrue("even the strongest specular stays a highlight", lit.specularAlpha < 0.25f);
        assertTrue("and the strongest rim is not a white line", lit.rimAlpha < 0.35f);
    }

    /**
     * 22 again, against the release it repairs. Beta 4's own numbers must no longer be reachable.
     *
     * <p>Written against the constants rather than against literals, so it keeps meaning something if
     * they are retuned again, and asserting the direction of the change: the shipped highlight is
     * quieter than it was, and the ceiling it is bounded by is lower than the old base.
     */
    @Test public void theShippedHighlightIsQuieterThanBetaFour() {
        // The Beta 4 base values, recorded here because they are the thing being moved away from.
        float betaFourSpecular = 0.15f;
        float betaFourRim = 0.24f;
        assertTrue("the specular base must have come down",
                OrbitGlass.BASE_SPECULAR_ALPHA < betaFourSpecular);
        assertTrue("and the rim base with it", OrbitGlass.BASE_RIM_LIGHT_ALPHA < betaFourRim);
        assertTrue("the body must keep more of the theme's own surface than it did",
                OrbitGlass.BASE_FILL_ACCENT_SHARE < 0.42f);
        assertTrue(OrbitGlass.BASE_HIGHLIGHT_SHARE < 0.14f);
    }

    /**
     * 20, 21, 24 and 25. The three materials are three different things.
     *
     * <p>Frosted is not Liquid with the sliders down, and Solid is not glass at all. Asserted on the
     * resolved configuration, so "quieter" is a fact about what gets drawn rather than an intention
     * recorded in a comment.
     */
    @Test public void theThreeMaterialsAreGenuinelyDifferent() {
        OrbitGlass.Finish frosted = finish(OrbitTheme.MATERIAL_FROSTED, OrbitProStyle.DEFAULT);
        OrbitGlass.Finish liquid = finish(OrbitTheme.MATERIAL_LIQUID, OrbitProStyle.DEFAULT);

        assertTrue("Frosted must be less reflective than Liquid",
                frosted.specularAlpha < liquid.specularAlpha);
        assertTrue("with a softer rim", frosted.rimAlpha < liquid.rimAlpha);
        assertTrue("a fainter bounce", frosted.reflectionAlpha < liquid.reflectionAlpha);
        assertTrue("and less accent in it", frosted.refractionAlpha < liquid.refractionAlpha);
        assertTrue("its glint must be wider and softer rather than local and bright",
                frosted.specularSpread > liquid.specularSpread);

        // Milky rather than merely dark: the body is lifted off the plain surface colour by a few
        // percent of white, which is what "frosted" has to mean on a dark theme.
        int surface = OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()).surface;
        assertTrue("Frosted's body must be lifted off the plain surface colour",
                OrbitContrast.relativeLuminance(frosted.bodyTop)
                        > OrbitContrast.relativeLuminance(surface));

        // And it carries less of the theme's accent, measured by how much changing the accent moves it.
        // Comparing the two body colours directly cannot settle this, because Frosted's milk moves it
        // away from the surface too; how strongly each material responds to the accent is the question
        // that actually distinguishes them.
        double frostedSwing = accentSwing(OrbitTheme.MATERIAL_FROSTED);
        double liquidSwing = accentSwing(OrbitTheme.MATERIAL_LIQUID);
        assertTrue("Frosted must follow the accent less closely than Liquid, got "
                        + frostedSwing + " against " + liquidSwing,
                frostedSwing < liquidSwing);
    }

    /** How far a material's body moves when the theme's accent changes hue completely. */
    private double accentSwing(String material) {
        OrbitTheme cool = OrbitTheme.orbitDefault().withAccent("#7C5BFF").withMaterial(material);
        OrbitTheme warm = OrbitTheme.orbitDefault().withAccent("#FF8A3D").withMaterial(material);
        int coolTop = OrbitGlass.finishFor(
                OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, cool),
                        OrbitProStyle.DEFAULT)).bodyTop;
        int warmTop = OrbitGlass.finishFor(
                OrbitGlass.Palette.of(OrbitThemeTokens.resolve(context, warm),
                        OrbitProStyle.DEFAULT)).bodyTop;
        return distanceFrom(coolTop, warmTop);
    }

    /** How far one colour is from another, for the claims that are about colour rather than light. */
    private static double distanceFrom(int a, int b) {
        double dr = Color.red(a) - Color.red(b);
        double dg = Color.green(a) - Color.green(b);
        double db = Color.blue(a) - Color.blue(b);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** Solid is answered outside the glass renderer entirely. */
    @Test public void solidIsNotGlass() {
        // Solid is answered by OrbitFloatingSurface and never reaches a finish at all.
        OrbitGlass.Palette solid = OrbitGlass.Palette.of(
                OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()), OrbitProStyle.DEFAULT)
                .asMaterial(OrbitTheme.MATERIAL_SOLID);
        assertTrue("Solid must be an opaque gradient, not glass",
                OrbitFloatingSurface.surfaceDrawable(context, solid, OrbitGlass.RADIUS_DP)
                        instanceof GradientDrawable);
        assertFalse(OrbitFloatingSurface.surfaceDrawable(context, solid, OrbitGlass.RADIUS_DP)
                instanceof OrbitGlass.GlassDrawable);
        assertFalse("and must not be described as tunable", OrbitFloatingSurface.tunable(
                OrbitTheme.MATERIAL_SOLID));
        assertTrue(OrbitFloatingSurface.tunable(OrbitTheme.MATERIAL_FROSTED));
        assertTrue(OrbitFloatingSurface.tunable(OrbitTheme.MATERIAL_LIQUID));
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
                0f, finish(neutral).refractionAlpha, 0.0001f);
        assertTrue("the default carries some", finish(standard).refractionAlpha > 0f);
        assertTrue("and the maximum carries more",
                finish(infused).refractionAlpha > finish(standard).refractionAlpha);
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

        assertTrue("the specular must strengthen from subdued to polished",
                finish(flat).specularAlpha < finish(polished).specularAlpha);
        assertTrue("and from polished to pronounced",
                finish(polished).specularAlpha < finish(lit).specularAlpha);
        assertTrue("while never disappearing entirely", finish(flat).specularAlpha > 0f);
        assertTrue("the rim must do the same", finish(flat).rimAlpha < finish(polished).rimAlpha);
        assertTrue(finish(polished).rimAlpha < finish(lit).rimAlpha);
        assertTrue(finish(flat).rimAlpha > 0f);
        assertTrue("the hairline must strengthen with it",
                lit.glassBorderAlpha() > flat.glassBorderAlpha());

        // Bounded on every layer, so the maximum is reflective material rather than a neon border.
        assertTrue(lit.glassSpecularAlpha() <= OrbitProStyle.MAX_SPECULAR_ALPHA);
        assertTrue(lit.glassRimLightAlpha() <= OrbitProStyle.MAX_RIM_LIGHT_ALPHA);
        assertTrue(lit.glassReflectionAlpha() <= OrbitProStyle.MAX_REFLECTION_ALPHA);
        assertTrue(lit.glassBorderAlpha() <= OrbitProStyle.MAX_BORDER_ALPHA);
        assertTrue("the rim is a highlight, never an opaque outline",
                finish(lit).rimAlpha < 0.5f);
        assertTrue("and the reflection stays understated",
                finish(lit).reflectionAlpha < finish(lit).rimAlpha);
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
    @Test public void everyMaterialStaysReadableOnEveryShippedPage() {
        for (String material : OrbitTheme.materials()) {
            for (int opacity : new int[]{OrbitProStyle.GLASS_OPACITY_MIN,
                    OrbitProStyle.GLASS_OPACITY_DEFAULT, OrbitProStyle.GLASS_OPACITY_MAX}) {
                for (int tint : new int[]{OrbitProStyle.GLASS_TINT_MIN,
                        OrbitProStyle.GLASS_TINT_DEFAULT, OrbitProStyle.GLASS_TINT_MAX}) {
                    for (OrbitTheme preset : OrbitTheme.builtIns()) {
                        OrbitProStyle style = preset.pro
                                .withGlassOpacity(opacity).withGlassTint(tint);
                        OrbitGlass.Palette p = OrbitGlass.Palette
                                .of(OrbitThemeTokens.resolve(context, preset), style)
                                .asMaterial(material);
                        assertReadable(preset.name + " " + material + " at opacity " + opacity
                                + ", tint " + tint, OrbitFloatingSurface.effectiveFill(p));
                    }
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

        for (String material : OrbitTheme.materials()) {
            for (OrbitProStyle style : new OrbitProStyle[]{linear, glow}) {
                for (OrbitTheme preset : OrbitTheme.builtIns()) {
                    OrbitTheme themed = preset.withPro(style).withMaterial(material);
                    OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, themed);
                    int behind = OrbitBackground.effectivePageColor(context,
                            OrbitBackground.Page.of(tokens, style));
                    int fill = OrbitFloatingSurface.effectiveFill(
                            OrbitGlass.Palette.over(tokens, style, behind));
                    assertReadable(preset.name + " " + material + " over "
                            + style.backgroundModeLabel(), fill);
                }
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

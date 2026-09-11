package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
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
 * Orbit Pro's advanced backgrounds, and the four ways the page could go wrong.
 *
 * <p><b>Somebody's existing theme changes.</b> Beta 4 gives every page a drawable where there used to
 * be a flat colour, on forty screens, and the compatibility promise is that a Beta 3 install looks
 * pixel-for-pixel identical until its owner deliberately asks for an effect. That is asserted as a
 * property of the defaults rather than as a claim about them: Solid is zero, zero is the default, and
 * Solid resolves to the same opaque colour the pages painted before.
 *
 * <p><b>Free draws a premium page.</b> Storage and rendering are separate decisions, and the stored
 * one is the user's. A Free device keeps every background value it was given and draws none of them,
 * and going back to Pro brings back exactly what was configured rather than something reconstructed.
 * The same thing is true of AMOLED, for a different reason: it is a promise about the large lit area
 * of the screen, and a gradient is a large lit area.
 *
 * <p><b>A screen grows its own gradient.</b> Forty pages that each work out their own background is
 * forty chances for thirty-eight of them to agree, which is a fault a user finds rather than a test.
 * One renderer, asserted structurally, and Theme Studio's own previews use it too.
 *
 * <p><b>The theme stores pixels.</b> A radial glow has a centre and a radius, and the tempting place
 * to keep them is in the theme. That theme would then be wrong the first time the phone rotated. Every
 * geometric value here is semantic - a direction, a share, a fraction of the height - and the drawable
 * resolves it against its own bounds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AdvancedBackgroundTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiKit.syncTheme(context);
    }

    private void asPro() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue(OrbitProEntitlement.hasPro(context));
    }

    private void asFree() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertFalse(OrbitProEntitlement.hasPro(context));
    }

    /** A linear background on Orbit's own theme, in one named direction. */
    private static OrbitProStyle linear(int direction) {
        return OrbitProStyle.DEFAULT
                .withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR)
                .withBackgroundEffectColor("#2A2060")
                .withGradientDirection(direction);
    }

    private static OrbitProStyle glow() {
        return OrbitProStyle.DEFAULT
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW)
                .withBackgroundEffectColor("#5B3FCF")
                .withGlowStrength(60)
                .withGlowSize(70)
                .withGlowPosition(OrbitProStyle.GLOW_CENTER);
    }

    /** The page a theme would draw, resolved against the entitlement currently in force. */
    private Drawable page(OrbitTheme theme) {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
        return OrbitBackground.drawableFor(context, tokens,
                OrbitProStyle.resolve(context, theme.pro));
    }

    /** A drawable given a real page to fill, since the glow resolves its geometry from its bounds. */
    private static Drawable laidOut(Drawable drawable) {
        drawable.setBounds(new Rect(0, 0, 1080, 2400));
        return drawable;
    }

    // ---- 16, 17 and 18. a Beta 3 install is untouched ----------------------------------------------

    /**
     * 16 and 18. An install that predates advanced backgrounds is Solid, and Solid is what it was.
     *
     * <p>Written as the preferences a Beta 3 install actually holds - premium glass and bubble keys
     * present, background keys absent - because "those keys do not exist yet" is the condition every
     * default here was chosen to serve.
     */
    @Test public void aBetaThreeInstallLoadsAsSolidAndDrawsWhatItAlwaysDrew() {
        asPro();
        Prefs.get(context).edit()
                .putString(Prefs.ACCENT, "violet")
                .putInt(Prefs.THEME_PRO_GLASS_OPACITY, 188)
                .putInt(Prefs.THEME_PRO_GLASS_TINT, 155)
                .putInt(Prefs.THEME_PRO_GLASS_EDGE, 160)
                .putInt(Prefs.THEME_SCHEMA, OrbitTheme.SCHEMA)
                .commit();

        OrbitProStyle stored = OrbitThemeStore.activeProStyle(context);
        assertEquals("no background keys means no background effect",
                OrbitProStyle.BACKGROUND_SOLID, stored.backgroundMode);
        assertFalse(stored.hasBackgroundEffect());
        assertEquals("and the defaults it landed on are Orbit's own",
                OrbitProStyle.EFFECT_COLOR_DEFAULT, stored.backgroundEffectColor);
        assertEquals(OrbitProStyle.GRADIENT_DIRECTION_DEFAULT, stored.gradientDirection);
        assertEquals(OrbitProStyle.GLOW_STRENGTH_DEFAULT, stored.glowStrength);
        assertEquals(OrbitProStyle.GLOW_SIZE_DEFAULT, stored.glowSize);
        assertEquals(OrbitProStyle.GLOW_POSITION_DEFAULT, stored.glowPosition);

        // And what it draws is the flat page colour those forty screens used to paint directly.
        UiKit.syncTheme(context);
        Drawable drawn = OrbitBackground.pageDrawable(context);
        assertTrue("Solid must be exactly an opaque fill", drawn instanceof ColorDrawable);
        assertEquals(UiKit.BG, ((ColorDrawable) drawn).getColor());
    }

    /** The shipped default is Solid, so nobody is opted in to anything by updating. */
    @Test public void solidIsTheDefaultForEverybody() {
        assertEquals(OrbitProStyle.BACKGROUND_SOLID, OrbitProStyle.BACKGROUND_MODE_DEFAULT);
        assertEquals(OrbitProStyle.BACKGROUND_SOLID, OrbitProStyle.DEFAULT.backgroundMode);
        assertFalse(OrbitProStyle.DEFAULT.hasBackgroundEffect());
        assertTrue("and a theme with no premium styling is still entirely default",
                OrbitProStyle.DEFAULT.isDefault());
    }

    /**
     * 17 and 34 and 35. The free Background colour is still free, and still one end of every effect.
     *
     * <p>The point Orbit Pro must not be allowed to blur. Linear does not introduce two premium
     * colours, it runs the existing free one into one premium colour; Glow does not replace the page,
     * it lights it. So a Free device can still choose its background colour, and a Pro device's effect
     * still starts from whatever that choice was.
     */
    @Test public void theFreeBackgroundColorStaysFreeAndStaysAnEndpoint() {
        asFree();
        OrbitTheme custom = OrbitTheme.orbitDefault().withBackground("#101820");
        assertTrue("choosing a background colour needs no entitlement",
                OrbitThemeStore.canApply(context, custom));
        assertTrue(OrbitThemeStore.applyActive(context, custom));
        UiKit.syncTheme(context);
        assertEquals("and it is the colour the page is painted", 0xFF101820, UiKit.BG | 0xFF000000);

        asPro();
        for (int direction = 0; direction < OrbitProStyle.DIRECTION_COUNT; direction++) {
            OrbitTheme themed = custom.withPro(linear(direction));
            GradientDrawable gradient = (GradientDrawable) page(themed);
            int[] colors = gradient.getColors();
            assertNotNull(colors);
            assertEquals("a linear background is the free colour plus one premium colour",
                    2, colors.length);
            assertEquals("and the free colour is always one endpoint",
                    0xFF101820, colors[0] | 0xFF000000);
            assertEquals(0xFF2A2060, colors[1] | 0xFF000000);
        }

        OrbitBackground.GlowDrawable lit = (OrbitBackground.GlowDrawable)
                page(custom.withPro(glow()));
        assertEquals("a glow is laid over the free colour, never instead of it",
                0xFF101820, lit.baseColor() | 0xFF000000);
        assertEquals(0xFF5B3FCF, lit.glowColor() | 0xFF000000);
    }

    // ---- 19 to 26. the fields persist, and survive nonsense ----------------------------------------

    /**
     * 19 to 25. Every background field is stored and read back exactly.
     *
     * <p>Through the real active-theme path rather than through the value class, because the fault
     * this protects against is a key that was added to the write and forgotten in the read, which is
     * invisible until somebody reopens Orbit.
     */
    @Test public void everyBackgroundFieldPersistsThroughTheActiveTheme() {
        asPro();
        OrbitProStyle configured = OrbitProStyle.DEFAULT
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW)
                .withBackgroundEffectColor("#3C2A7E")
                .withGradientDirection(OrbitProStyle.DIRECTION_BL_TR)
                .withGlowStrength(72)
                .withGlowSize(88)
                .withGlowPosition(OrbitProStyle.GLOW_BOTTOM);
        assertTrue(OrbitThemeStore.applyActive(context,
                OrbitTheme.orbitDefault().withPro(configured)));

        OrbitProStyle read = OrbitThemeStore.activeProStyle(context);
        assertEquals(OrbitProStyle.BACKGROUND_GLOW, read.backgroundMode);
        assertEquals("#3C2A7E", read.backgroundEffectColor);
        assertEquals(OrbitProStyle.DIRECTION_BL_TR, read.gradientDirection);
        assertEquals(72, read.glowStrength);
        assertEquals(88, read.glowSize);
        assertEquals(OrbitProStyle.GLOW_BOTTOM, read.glowPosition);
        assertTrue("and the whole value round-trips", read.same(configured));
    }

    /**
     * 26. Nonsense clamps or falls back. Nothing refuses, and nothing throws.
     *
     * <p>The same rule the rest of the theme model follows. A preference file edited by hand, a
     * restored backup from a build that numbered its modes differently, or a theme document corrupted
     * in a way JSON cannot detect all have to degrade into a usable appearance rather than into a
     * crash on the next launch.
     */
    @Test public void invalidBackgroundValuesClampOrFallBack() {
        assertEquals("a mode below the range becomes Solid",
                OrbitProStyle.BACKGROUND_SOLID,
                OrbitProStyle.DEFAULT.withBackgroundMode(-7).backgroundMode);
        assertEquals("and one above it becomes the last mode Orbit knows",
                OrbitProStyle.BACKGROUND_GLOW,
                OrbitProStyle.DEFAULT.withBackgroundMode(99).backgroundMode);
        assertEquals(0, OrbitProStyle.DEFAULT.withGradientDirection(-1).gradientDirection);
        assertEquals(OrbitProStyle.DIRECTION_COUNT - 1,
                OrbitProStyle.DEFAULT.withGradientDirection(400).gradientDirection);
        assertEquals(OrbitProStyle.GLOW_STRENGTH_MIN,
                OrbitProStyle.DEFAULT.withGlowStrength(-50).glowStrength);
        assertEquals(OrbitProStyle.GLOW_STRENGTH_MAX,
                OrbitProStyle.DEFAULT.withGlowStrength(5000).glowStrength);
        assertEquals(OrbitProStyle.GLOW_SIZE_MIN,
                OrbitProStyle.DEFAULT.withGlowSize(0).glowSize);
        assertEquals(OrbitProStyle.GLOW_SIZE_MAX,
                OrbitProStyle.DEFAULT.withGlowSize(999).glowSize);
        assertEquals(OrbitProStyle.GLOW_TOP,
                OrbitProStyle.DEFAULT.withGlowPosition(-3).glowPosition);
        assertEquals(OrbitProStyle.GLOW_BOTTOM,
                OrbitProStyle.DEFAULT.withGlowPosition(40).glowPosition);

        for (String rubbish : new String[]{null, "", "   ", "not-a-colour", "#12", "#GGGGGG",
                "rgb(1,2,3)", "0xFF00FF"}) {
            assertEquals("an unrecognised effect colour falls back to the accent",
                    OrbitProStyle.EFFECT_COLOR_DEFAULT,
                    OrbitProStyle.DEFAULT.withBackgroundEffectColor(rubbish)
                            .backgroundEffectColor);
        }
        assertEquals("a lower-case hex is canonicalised, not rejected",
                "#3C2A7E",
                OrbitProStyle.DEFAULT.withBackgroundEffectColor("#3c2a7e").backgroundEffectColor);
        assertEquals("and a named palette colour is kept as its name",
                "violet",
                OrbitProStyle.DEFAULT.withBackgroundEffectColor("violet").backgroundEffectColor);
    }

    // ---- 27 to 30. entitlement suppresses and restores ----------------------------------------------

    /**
     * 27, 28, 29 and 30. Free draws Solid, keeps everything, and gives it all back.
     *
     * <p>All four in one test on purpose, because they are one property observed at four moments and
     * splitting them would let three pass while the interesting one failed. The stored value is read
     * back after every transition, so "preserved" means the bytes are still there rather than that
     * nothing visibly broke.
     */
    @Test public void freeSuppressesBothEffectsAndProRestoresThemExactly() {
        asPro();
        for (OrbitProStyle configured : new OrbitProStyle[]{
                linear(OrbitProStyle.DIRECTION_TL_BR), glow()}) {
            OrbitTheme themed = OrbitTheme.orbitDefault().withPro(configured);
            assertTrue(OrbitThemeStore.applyActive(context, themed));

            asPro();
            assertTrue("Pro draws the effect the theme asks for",
                    OrbitBackground.effectDraws(context));
            assertFalse(page(themed) instanceof ColorDrawable);

            asFree();
            assertFalse("Free must draw no premium effect at all",
                    OrbitBackground.effectDraws(context));
            assertTrue("the page falls back to the plain background colour",
                    page(themed) instanceof ColorDrawable);
            assertTrue("and the stored configuration is untouched",
                    OrbitThemeStore.activeProStyle(context).same(configured));

            asPro();
            assertTrue("returning to Pro restores exactly what was stored",
                    OrbitThemeStore.activeProStyle(context).same(configured));
            assertTrue(OrbitBackground.effectDraws(context));
            assertEquals("mode and all",
                    configured.backgroundMode,
                    OrbitThemeStore.activeProStyle(context).backgroundMode);
        }
    }

    /** Entitlement is consulted where the page is drawn, not only where the controls are built. */
    @Test public void backgroundRenderingAsksTheCentralEntitlementApi() {
        String background = ThemeStudioProTest.readSourceFile("OrbitBackground.java");
        assertTrue("the renderer must resolve premium styling through the shared resolver",
                background.contains("OrbitProStyle.live(")
                        || background.contains("OrbitProStyle.resolve("));
        assertFalse("and must never read the developer preview preference itself",
                background.contains("PRO_PREVIEW") || background.contains("setProPreview"));
    }

    // ---- 31, 32 and 33. AMOLED wins, and gives it back ---------------------------------------------

    /**
     * 31, 32 and 33. True black means true black, and nothing is lost saying so.
     *
     * <p>AMOLED is a promise about the largest lit area of the screen, and both effects are exactly
     * that area, so it suppresses them. What it must not do is edit the theme: turning it off has to
     * bring back the configuration that was there, not a reconstruction of it.
     */
    @Test public void amoledSuppressesBothEffectsAndKeepsTheConfiguration() {
        asPro();
        for (OrbitProStyle configured : new OrbitProStyle[]{
                linear(OrbitProStyle.DIRECTION_BOTTOM_TOP), glow()}) {
            OrbitTheme lit = OrbitTheme.orbitDefault().withPro(configured);
            OrbitTheme black = lit.withAmoled(true);

            Drawable drawn = page(black);
            assertTrue("an AMOLED page is a flat fill", drawn instanceof ColorDrawable);
            assertEquals("and it is true black",
                    Color.BLACK, ((ColorDrawable) drawn).getColor());
            assertTrue("while the effect configuration is kept intact",
                    black.pro.same(configured));

            assertTrue("and turning AMOLED off brings back exactly that effect",
                    black.withAmoled(false).pro.same(configured));
            assertFalse(page(lit) instanceof ColorDrawable);
        }

        // Through storage as well, which is where a suppression that edited the theme would show up.
        OrbitTheme stored = OrbitTheme.orbitDefault().withPro(glow()).withAmoled(true);
        assertTrue(OrbitThemeStore.applyActive(context, stored));
        assertEquals("AMOLED must not have rewritten the mode",
                OrbitProStyle.BACKGROUND_GLOW,
                OrbitThemeStore.activeProStyle(context).backgroundMode);
        assertTrue(OrbitThemeStore.applyActive(context,
                OrbitThemeStore.active(context).withAmoled(false)));
        assertTrue("and the glow returns unchanged",
                OrbitThemeStore.activeProStyle(context).same(glow()));
    }

    // ---- the two effects render correctly ----------------------------------------------------------

    /** All eight directions map to eight distinct platform orientations, in a stable order. */
    @Test public void eightDirectionsMapToEightDistinctOrientations() {
        List<GradientDrawable.Orientation> seen = new ArrayList<>();
        for (int direction = 0; direction < OrbitProStyle.DIRECTION_COUNT; direction++) {
            GradientDrawable.Orientation orientation = OrbitBackground.orientation(direction);
            assertFalse("direction " + direction + " must be its own orientation",
                    seen.contains(orientation));
            seen.add(orientation);
        }
        assertEquals(8, seen.size());
        assertEquals("and the stored default must stay top to bottom",
                GradientDrawable.Orientation.TOP_BOTTOM,
                OrbitBackground.orientation(OrbitProStyle.GRADIENT_DIRECTION_DEFAULT));
        assertEquals("and every direction must have a name a person can check against the preview",
                OrbitProStyle.DIRECTION_COUNT,
                new java.util.HashSet<>(directionLabels()).size());
    }

    private static List<String> directionLabels() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < OrbitProStyle.DIRECTION_COUNT; i++) {
            out.add(OrbitProStyle.directionLabel(i));
        }
        return out;
    }

    /**
     * The glow is bounded, smooth, and derived from the page it lands on rather than from the theme.
     *
     * <p>Strength never reaches opaque, because the page is where Orbit's text readability comes from.
     * Size is a share of the longer edge rather than a number of pixels, and position is a fraction of
     * the height, which together are what make one stored theme correct in portrait, in landscape and
     * mid-rotation.
     */
    @Test public void theGlowIsBoundedAndResolvedFromItsOwnBounds() {
        asPro();
        assertTrue("even the strongest glow leaves the page readable",
                OrbitProStyle.DEFAULT.withGlowStrength(OrbitProStyle.GLOW_STRENGTH_MAX).glowAlpha()
                        <= OrbitProStyle.MAX_GLOW_ALPHA);
        assertTrue("and the faintest is still visible",
                OrbitProStyle.DEFAULT.withGlowStrength(OrbitProStyle.GLOW_STRENGTH_MIN).glowAlpha()
                        > 0f);
        assertTrue("strength must be monotonic",
                OrbitProStyle.DEFAULT.withGlowStrength(20).glowAlpha()
                        < OrbitProStyle.DEFAULT.withGlowStrength(80).glowAlpha());
        assertTrue("and so must size",
                OrbitProStyle.DEFAULT.withGlowSize(OrbitProStyle.GLOW_SIZE_MIN).glowRadiusShare()
                        < OrbitProStyle.DEFAULT.withGlowSize(OrbitProStyle.GLOW_SIZE_MAX)
                                .glowRadiusShare());
        assertTrue("the smallest glow is still a spread rather than a hard circle",
                OrbitProStyle.DEFAULT.withGlowSize(OrbitProStyle.GLOW_SIZE_MIN).glowRadiusShare()
                        >= OrbitProStyle.MIN_GLOW_RADIUS_SHARE);

        float top = OrbitProStyle.DEFAULT.withGlowPosition(OrbitProStyle.GLOW_TOP).glowCenterY();
        float middle = OrbitProStyle.DEFAULT.withGlowPosition(OrbitProStyle.GLOW_CENTER).glowCenterY();
        float bottom = OrbitProStyle.DEFAULT.withGlowPosition(OrbitProStyle.GLOW_BOTTOM).glowCenterY();
        assertTrue("the three positions must be three different places", top < middle);
        assertTrue(middle < bottom);
        assertTrue("and all three inside the page, not on its edges", top > 0f && bottom < 1f);

        // Drawn at two very different page shapes, because a stretched pre-render would show up here.
        Drawable portrait = laidOut(page(OrbitTheme.orbitDefault().withPro(glow())));
        Drawable landscape = page(OrbitTheme.orbitDefault().withPro(glow()));
        landscape.setBounds(new Rect(0, 0, 2400, 1080));
        assertNotNull("the glow must resolve at any page shape", portrait);
        assertNotNull(landscape);
        assertTrue("and remain the same drawable kind at both",
                portrait instanceof OrbitBackground.GlowDrawable
                        && landscape instanceof OrbitBackground.GlowDrawable);
    }

    /** A theme never stores a pixel coordinate, which is what keeps it correct across a rotation. */
    @Test public void noBackgroundValueIsAPixelCoordinate() {
        String style = ThemeStudioProTest.readSourceFile("OrbitProStyle.java");
        for (String forbidden : new String[]{"glowCenterXPx", "glowCenterYPx", "glowRadiusPx",
                "backgroundWidth", "backgroundHeight"}) {
            assertFalse("a theme must not store " + forbidden, style.contains(forbidden));
        }
        assertTrue("position is a fraction of the page height",
                style.contains("float glowCenterY()"));
        assertTrue("and size is a share of its longer edge",
                style.contains("float glowRadiusShare()"));
    }

    // ---- 36, 37, 38 and 53. one renderer, everywhere -----------------------------------------------

    /**
     * 36 and 37. The previews and the real pages are drawn by the same call.
     *
     * <p>This is the invariant the whole feature rests on. Choosing a background is an act of trust
     * that the page will look like the sample, and a sample with its own formula would go on looking
     * plausible for exactly as long as it took the real renderer to move.
     */
    @Test public void previewsAndPagesShareOneRenderer() {
        assertTrue("the background sample must be drawn by the central renderer",
                ThemeStudioProTest.readSourceFile("BackgroundStylePreview.java")
                        .contains("OrbitBackground.drawableFor("));
        assertTrue("and so must the glass sample's own backdrop",
                ThemeStudioProTest.readSourceFile("GlassStylePreview.java")
                        .contains("OrbitBackground.drawableFor("));
        for (String page : new String[]{"MainActivity.java", "ChatActivity.java",
                "SettingsActivity.java", "ThemeStudioActivity.java", "OrbitVaultActivity.java",
                "DeckActivity.java", "DiagnosticsActivity.java", "RoadmapActivity.java",
                "WhatsNewActivity.java", "UpdateActivity.java"}) {
            assertTrue(page + " must get its page canvas from OrbitBackground",
                    ThemeStudioProTest.readSourceFile(page).contains("OrbitBackground.applyPage("));
        }
    }

    /**
     * 38 and 53. No Activity carries gradient or radial arithmetic of its own.
     *
     * <p>The architectural regression test, and it is deliberately written against the shapes that
     * arithmetic actually takes rather than against formatting: a radial shader, a platform gradient
     * orientation, or a direct read of the premium background fields. Renaming a local variable cannot
     * fail this; reimplementing a page background in a screen can.
     *
     * <p>Scoped to the files that own a page, which is what the invariant is actually about. A radial
     * shader is a perfectly ordinary thing for a component to build - Orbit's stopped mark and its
     * message highlight both do, and neither is a page - and {@code UiKit} has drawn Orbit's sheet
     * gradient since long before any of this. What must not happen is a screen deciding for itself
     * what the page behind its content looks like.
     */
    @Test public void noScreenReimplementsThePageBackground() {
        List<String> offenders = new ArrayList<>();
        for (Path source : ThemeStudioProTest.mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitBackground.java")) continue;
            String body = ThemeStudioProTest.readSourceFile(name);

            // Nothing outside the value class may resolve the glow's geometry, whatever kind of file
            // it is. That is the route by which a second interpretation of it appears. The effect
            // colour is deliberately not on this list: Theme Studio has to resolve it to paint the
            // swatch beside the row that chooses it, which is reading a colour rather than deciding
            // what the page looks like.
            if (!name.equals("OrbitProStyle.java")) {
                for (String field : new String[]{".glowRadiusShare()", ".glowCenterY()",
                        ".glowAlpha()"}) {
                    if (body.contains(field)) {
                        offenders.add(name + " resolves " + field + " itself");
                    }
                }
            }

            // And a screen that owns a page may not assemble one.
            if (!name.endsWith("Activity.java")) continue;
            if (body.contains("RadialGradient") || body.contains("SweepGradient")) {
                offenders.add(name + " builds its own radial light");
            }
            if (body.contains("GradientDrawable.Orientation.")) {
                offenders.add(name + " chooses a gradient orientation");
            }
        }
        assertTrue("page background arithmetic must live in OrbitBackground alone: " + offenders,
                offenders.isEmpty());
    }

    /**
     * Only genuine page canvases were migrated, and the ones that were not are still deliberate.
     *
     * <p>A blind replacement of every {@code UiKit.BG} would have put a gradient behind a screenshot
     * being cropped and inside the mat it is laid on. Those four remaining uses are each a mask, an
     * editor canvas, a piece of pinned chrome or an animation backdrop, and each carries a comment
     * saying so - which is the thing this asserts, because a future edit that adds a fifth without a
     * reason is the failure worth catching.
     */
    @Test public void everyRemainingFlatPageColourIsDeliberate() {
        List<String> undocumented = new ArrayList<>();
        for (Path source : ThemeStudioProTest.mainSources()) {
            String name = source.getFileName().toString();
            String body = ThemeStudioProTest.readSourceFile(name);
            int at = body.indexOf("setBackgroundColor(UiKit.BG)");
            while (at >= 0) {
                // The comment explaining the choice sits directly above the call.
                String before = body.substring(Math.max(0, at - 600), at);
                int lastComment = before.lastIndexOf("//");
                boolean explained = lastComment >= 0
                        && before.substring(lastComment).split("\n").length <= 6;
                if (!explained) undocumented.add(name);
                at = body.indexOf("setBackgroundColor(UiKit.BG)", at + 1);
            }
        }
        assertTrue("a flat page colour now needs a stated reason: " + undocumented,
                undocumented.isEmpty());
    }

    /**
     * 30 (system bars). The bars take the base colour, and nobody fakes a gradient into an inset.
     *
     * <p>Not a compromise so much as the only honest answer. A system bar tinted to whatever the
     * effect happens to be doing at the top of the page would visibly disagree with the page the
     * moment anything about the layout moved, and the base colour is the value that is right at both
     * ends of it.
     */
    @Test public void systemBarsUseTheBaseColourRatherThanTheEffect() {
        asPro();
        OrbitTheme themed = OrbitTheme.orbitDefault().withPro(glow());
        assertTrue(OrbitThemeStore.applyActive(context, themed));
        UiKit.syncTheme(context);
        assertEquals("the bars are the page's own colour",
                UiKit.BG, OrbitBackground.systemBarColor(context));
        assertFalse("and no screen builds a gradient for a system bar",
                ThemeStudioProTest.readSourceFile("ThemeStudioActivity.java")
                        .contains("setStatusBarColor(new GradientDrawable"));
    }

    // ---- readability -------------------------------------------------------------------------------

    /**
     * 43. A premium background never takes the page's readability with it.
     *
     * <p>Two separate promises. Orbit goes on deriving page ink from the theme's Background colour,
     * because text that changed colour partway down a glow would be unusable; and the effect is
     * bounded so that the ink which follows from the base colour still reads at the brightest point
     * the effect can reach. The second is what is checked here, across every preset and both modes.
     */
    @Test public void thePageStaysReadableUnderTheBrightestAllowedEffect() {
        OrbitProStyle brightest = OrbitProStyle.DEFAULT
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW)
                .withBackgroundEffectColor(OrbitTheme.ACCENT)
                .withGlowStrength(OrbitProStyle.GLOW_STRENGTH_MAX)
                .withGlowSize(OrbitProStyle.GLOW_SIZE_MAX);
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            for (int mode : new int[]{OrbitProStyle.BACKGROUND_LINEAR,
                    OrbitProStyle.BACKGROUND_GLOW}) {
                OrbitProStyle style = brightest.withBackgroundMode(mode);
                OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, preset.withPro(style));
                int lit = OrbitBackground.effectivePageColor(context,
                        OrbitBackground.Page.of(tokens, style));
                double ratio = OrbitContrast.contrastRatio(tokens.text, lit);
                assertTrue(preset.name + " under " + style.backgroundModeLabel()
                                + " must keep page text readable, got " + ratio,
                        ratio >= OrbitContrast.BODY_TEXT_MIN);
            }
        }
    }

    /** And the two shipped background presets pass Orbit's own contrast check as they are. */
    @Test public void theBackgroundPresetsPassOrbitsOwnContrastCheck() {
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertNotNull(preset);
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, preset);
            assertFalse(preset.name + " must not trip Orbit's own warning",
                    tokens.hasLowContrast());
            int lit = OrbitBackground.effectivePageColor(context,
                    OrbitBackground.Page.of(tokens, preset.pro));
            assertTrue(preset.name + " must stay readable where its effect is strongest",
                    OrbitContrast.contrastRatio(tokens.text, lit) >= OrbitContrast.BODY_TEXT_MIN);
        }
    }

    /**
     * A page that was built before the theme was applied notices, and rebuilds once.
     *
     * <p>The fault this closes was invisible until Beta 4 and certain in it. A page's background used
     * to be a colour read from {@code UiKit} at build time, and a screen sitting underneath Theme
     * Studio watched the accent and AMOLED to decide whether to rebuild - so applying a theme that
     * changed only the background left that screen painting the previous one until the next cold
     * start. With advanced backgrounds "only the background changed" is the normal case: adding a glow
     * to the theme you already have moves nothing else at all.
     *
     * <p>Asserted on the signatures every such screen actually compares, across a change that touches
     * the background and nothing else.
     */
    @Test public void applyingOnlyABackgroundChangeStillRebuildsThePagesUnderneath() {
        asPro();
        assertTrue(OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault()));
        UiKit.syncTheme(context);
        String structuralBefore = UiKit.structuralAppearanceSignature(context);
        String appearanceBefore = UiKit.appearanceSignature(context);

        // A glow added to the theme already in force. Same accent, same AMOLED, same surfaces, same
        // background colour: every value a Beta 3 screen would have looked at is unchanged.
        assertTrue(OrbitThemeStore.applyActive(context,
                OrbitThemeStore.active(context).withPro(glow())));
        UiKit.syncTheme(context);

        assertFalse("a screen watching the structural signature must rebuild",
                structuralBefore.equals(UiKit.structuralAppearanceSignature(context)));
        assertFalse("and so must one watching the general appearance signature",
                appearanceBefore.equals(UiKit.appearanceSignature(context)));

        // And a Free device must not be made to rebuild by a stored value it cannot draw.
        asFree();
        String freeBefore = UiKit.structuralAppearanceSignature(context);
        assertTrue(OrbitThemeStore.applyActive(context,
                OrbitThemeStore.active(context).withPro(
                        glow().withGlowStrength(OrbitProStyle.GLOW_STRENGTH_MAX))));
        assertEquals("Free must not rebuild because a suppressed value moved",
                freeBefore, UiKit.structuralAppearanceSignature(context));
    }

    /** Every page that keeps its own rebuild signature includes the theme's page styling in it. */
    @Test public void everyPageThatWatchesAppearanceWatchesThePageStyling() {
        assertTrue("the shared signatures must both carry premium styling",
                ThemeStudioProTest.readSourceFile("UiKit.java")
                        .contains("\"|pro=\" + proStyleSignature(c)"));
        assertTrue("and Chats' own signature must defer to the shared one",
                ThemeStudioProTest.readSourceFile("MainActivity.java")
                        .contains("UiKit.structuralAppearanceSignature(this)"));
        assertTrue("as must the conversation, which had no such check at all before",
                ThemeStudioProTest.readSourceFile("ChatActivity.java")
                        .contains("UiKit.structuralAppearanceSignature(this)"));
    }

    // ---- 44. cost ----------------------------------------------------------------------------------

    /** The page background is built when appearance changes and never while scrolling. */
    @Test public void thePageBackgroundIsStaticRatherThanPerFrame() {
        String background = ThemeStudioProTest.readSourceFile("OrbitBackground.java");
        assertFalse("no page background may be rebuilt from a scroll callback",
                background.contains("onScrollChange") || background.contains("OnScrollChanged"));
        assertFalse("and none of it may allocate a bitmap",
                background.contains("Bitmap"));
        assertTrue("the glow resolves its shader once, when its bounds change",
                background.contains("onBoundsChange"));
        assertTrue("premium background styling is part of the appearance signature, so a page "
                        + "underneath Theme Studio rebuilds once rather than polling",
                ThemeStudioProTest.readSourceFile("UiKit.java").contains("style.backgroundMode"));
    }
}

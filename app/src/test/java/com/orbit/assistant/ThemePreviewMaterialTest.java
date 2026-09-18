package com.orbit.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The Theme Studio preview's Deck tile is made of the selected material.
 *
 * <p>Until v0.8.0.0-beta.8 it was a flat outlined rectangle. Real Deck tiles were drawn by
 * {@link OrbitFloatingSurface} in Solid, Frosted or Liquid, and the miniature beside the colour
 * controls stayed the same through all three, so the one place a person goes to judge a theme was
 * the one place the material did not show. On a Free device, where the dedicated glass sample is not
 * built, the preview said nothing about the material at all.
 *
 * <p>These tests hold the miniature to the real tile: the same resolver, the same finish, the same
 * entitlement rules, updated in place when the selector moves.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ThemePreviewMaterialTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiPresence.clearForTests();
        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
        UiKit.syncTheme(context);
    }

    // ---- 1 to 3. each material draws its own surface -----------------------------------------------

    @Test public void solidPreviewTileIsTheRealSolidSurface() {
        OrbitTheme theme = OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_SOLID);
        Drawable tile = renderTile(theme);
        assertFalse("Solid must not be glass", tile instanceof OrbitGlass.GlassDrawable);
        assertTrue(tile instanceof GradientDrawable);
        assertArrayEquals("and must be exactly Orbit's Solid surface for this theme",
                expectedSolid(theme).getColors(), ((GradientDrawable) tile).getColors());
    }

    @Test public void frostedPreviewTileIsTheRealFrostedSurface() {
        assertGlassMatches(OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_FROSTED));
    }

    @Test public void liquidPreviewTileIsTheRealLiquidSurface() {
        assertGlassMatches(OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_LIQUID));
    }

    /** The three are visibly different, which was the whole complaint. */
    @Test public void theThreeMaterialsAreDistinguishableInThePreview() {
        OrbitTheme base = OrbitTheme.orbitDefault();
        Drawable solid = renderTile(base.withMaterial(OrbitTheme.MATERIAL_SOLID));
        OrbitGlass.Finish frosted = finishOf(renderTile(base.withMaterial(OrbitTheme.MATERIAL_FROSTED)));
        OrbitGlass.Finish liquid = finishOf(renderTile(base.withMaterial(OrbitTheme.MATERIAL_LIQUID)));
        assertFalse(solid instanceof OrbitGlass.GlassDrawable);
        assertEquals(OrbitTheme.MATERIAL_FROSTED, frosted.material);
        assertEquals(OrbitTheme.MATERIAL_LIQUID, liquid.material);
        assertTrue("Frosted and Liquid must not draw the same glass",
                frosted.bodyTop != liquid.bodyTop || frosted.specularAlpha != liquid.specularAlpha
                        || frosted.reflectionAlpha != liquid.reflectionAlpha);
    }

    // ---- 4 and 5. the live preview follows the selector, in place ----------------------------------

    @Test public void theLivePreviewFollowsTheSelectorOnFree() {
        followsSelector(false);
    }

    @Test public void theLivePreviewFollowsTheSelectorOnPro() {
        followsSelector(true);
    }

    private void followsSelector(boolean pro) {
        Prefs.setProPreview(context, pro ? Prefs.PRO_PREVIEW_PRO : Prefs.PRO_PREVIEW_FREE);
        assertEquals(pro, OrbitProEntitlement.hasPro(context));
        ActivityController<ThemeStudioActivity> controller =
                Robolectric.buildActivity(ThemeStudioActivity.class).setup();
        ThemeStudioActivity activity = controller.get();
        ThemePreviewView preview = (ThemePreviewView) field(activity, "preview");
        OrbitSegmented selector = (OrbitSegmented) field(activity, "materialSegment");
        List<View> before = descendants(preview);
        View tile = tileView(preview);

        // Liquid last, because Orbit Default starts on Liquid and a click on the selected pill is
        // deliberately a no-op.
        for (String material : new String[]{OrbitTheme.MATERIAL_SOLID,
                OrbitTheme.MATERIAL_FROSTED, OrbitTheme.MATERIAL_LIQUID}) {
            selector.segmentAt(OrbitTheme.materialIndex(material)).performClick();
            OrbitTheme draft = (OrbitTheme) field(activity, "draft");
            assertEquals(material, draft.material);
            assertSame("the preview must be showing this draft", draft, preview.tokens().theme);
            Drawable background = tile.getBackground();
            if (OrbitTheme.MATERIAL_SOLID.equals(material)) {
                assertFalse(material, background instanceof OrbitGlass.GlassDrawable);
            } else {
                assertEquals(material, finishOf(background).material);
            }
            assertTrue("and say so aloud", String.valueOf(tile.getContentDescription())
                    .contains(OrbitTheme.materialLabel(material)));
        }

        List<View> after = descendants(preview);
        assertEquals("the preview hierarchy must not be rebuilt", before.size(), after.size());
        for (int i = 0; i < before.size(); i++) assertSame(before.get(i), after.get(i));
        assertEquals("and nothing is applied until Apply", OrbitTheme.MATERIAL_LIQUID,
                OrbitThemeStore.activeMaterial(context));
        controller.pause().stop().destroy();
    }

    // ---- 6. custom colours --------------------------------------------------------------------------

    @Test public void customColoursReachEveryMaterial() {
        OrbitTheme custom = OrbitTheme.orbitDefault().withAccent("#FF7A00")
                .withSurface("#20304A").withBackground("#0B1220");
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, custom);

        GradientDrawable solid = (GradientDrawable) renderTile(
                custom.withMaterial(OrbitTheme.MATERIAL_SOLID));
        assertEquals("Solid's lit end is the theme's own raised card",
                tokens.surface2, solid.getColors()[0]);
        assertGlassMatches(custom.withMaterial(OrbitTheme.MATERIAL_FROSTED));
        assertGlassMatches(custom.withMaterial(OrbitTheme.MATERIAL_LIQUID));

        // And the default theme's glass is not what a custom theme draws.
        assertNotEquals(finishOf(renderTile(OrbitTheme.orbitDefault())).stroke,
                finishOf(renderTile(custom)).stroke);
    }

    // ---- 7. AMOLED ----------------------------------------------------------------------------------

    @Test public void amoledStaysTrueBlackInEveryMaterial() {
        OrbitTheme amoled = OrbitTheme.builtIn(OrbitTheme.ID_AMOLED);
        assertNotNull(amoled);
        for (String material : OrbitTheme.materials()) {
            OrbitTheme theme = amoled.withMaterial(material);
            assertTrue("changing the material must not turn AMOLED off", theme.amoled);
            ThemePreviewView preview = new ThemePreviewView(context);
            preview.render(OrbitThemeTokens.resolve(context, theme));
            assertEquals(Color.BLACK, preview.tokens().background);
            GradientDrawable page = (GradientDrawable) preview.getBackground();
            assertEquals("the preview page stays true black", Color.BLACK,
                    page.getColor().getDefaultColor());
            if (OrbitTheme.isGlass(material)) assertGlassMatches(theme);
        }
    }

    // ---- 8. Pro glass tuning ------------------------------------------------------------------------

    @Test public void proGlassTuningMovesThePreviewTileOnlyWithPro() {
        OrbitProStyle tuned = OrbitProStyle.DEFAULT.withGlassOpacity(168)
                .withGlassTint(170).withGlassEdge(150);
        OrbitTheme theme = OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_LIQUID)
                .withPro(tuned);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
        OrbitGlass.Finish untuned = OrbitGlass.surfaceDrawable(context,
                OrbitGlass.Palette.of(tokens, OrbitProStyle.DEFAULT), UiKit.RADIUS_CARD).finish();

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        OrbitGlass.Finish withPro = finishOf(renderTile(theme));
        assertNotEquals("Pro tuning must reach the preview tile", untuned.bodyAlpha, withPro.bodyAlpha);
        assertGlassMatches(theme);

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertSameFinish(untuned, finishOf(renderTile(theme)));
        assertEquals("and the stored tuning is kept for when Pro returns",
                tuned.glassOpacity, theme.pro.glassOpacity);

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertSameFinish(withPro, finishOf(renderTile(theme)));
        assertFalse("Solid ignores glass tuning even with Pro",
                renderTile(theme.withMaterial(OrbitTheme.MATERIAL_SOLID))
                        instanceof OrbitGlass.GlassDrawable);
    }

    // ---- 9 and 10. persistence and theme files ------------------------------------------------------

    @Test public void theAppliedAndImportedMaterialIsWhatThePreviewDraws() throws Exception {
        OrbitTheme frosted = OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_FROSTED);
        assertTrue(OrbitThemeStore.applyActive(context, frosted));
        assertEquals(OrbitTheme.MATERIAL_FROSTED, OrbitThemeStore.activeMaterial(context));

        OrbitTheme solid = OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_SOLID);
        OrbitTheme restored = OrbitTheme.fromJson(solid.toJson());
        assertEquals(OrbitTheme.MATERIAL_SOLID, restored.material);
        assertFalse(renderTile(restored) instanceof OrbitGlass.GlassDrawable);
    }

    // ---- 11. real Deck tiles, and the preview agreeing with them ------------------------------------

    @Test public void thePreviewTileMatchesARealDeckTile() {
        for (String material : OrbitTheme.materials()) {
            OrbitTheme theme = OrbitTheme.orbitDefault().withMaterial(material);
            assertTrue(OrbitThemeStore.applyActive(context, theme));
            UiKit.syncTheme(context);

            DeckTile tile = DeckLayoutStore.layout(context).get(0);
            DeckTileView real = new DeckTileView(context, tile,
                    DeckTileResolver.resolve(context, tile), null);
            assertTrue("a real tile is still Orbit's interactive surface",
                    real.getBackground() instanceof RippleDrawable);
            Drawable realSurface = ((RippleDrawable) real.getBackground()).getDrawable(0);
            Drawable previewSurface = renderTile(theme);

            if (OrbitTheme.isGlass(material)) {
                assertEquals("a real " + material + " tile", material, finishOf(realSurface).material);
                assertSameFinish(finishOf(realSurface), finishOf(previewSurface));
            } else {
                assertFalse(realSurface instanceof OrbitGlass.GlassDrawable);
                assertArrayEquals(((GradientDrawable) realSurface).getColors(),
                        ((GradientDrawable) previewSurface).getColors());
            }
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private Drawable renderTile(OrbitTheme theme) {
        ThemePreviewView preview = new ThemePreviewView(context);
        preview.render(OrbitThemeTokens.resolve(context, theme));
        return tileView(preview).getBackground();
    }

    private void assertGlassMatches(OrbitTheme theme) {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
        OrbitGlass.Finish expected = OrbitGlass.surfaceDrawable(context,
                OrbitGlass.Palette.of(tokens, OrbitProStyle.resolve(context, theme.pro)),
                UiKit.RADIUS_CARD).finish();
        assertEquals(theme.material, expected.material);
        assertSameFinish(expected, finishOf(renderTile(theme)));
    }

    private GradientDrawable expectedSolid(OrbitTheme theme) {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
        return OrbitFloatingSurface.solidDrawable(context,
                OrbitGlass.Palette.of(tokens, OrbitProStyle.resolve(context, theme.pro)),
                UiKit.RADIUS_CARD);
    }

    private static OrbitGlass.Finish finishOf(Drawable drawable) {
        assertTrue("expected glass, got " + drawable, drawable instanceof OrbitGlass.GlassDrawable);
        return ((OrbitGlass.GlassDrawable) drawable).finish();
    }

    private static void assertSameFinish(OrbitGlass.Finish expected, OrbitGlass.Finish actual) {
        assertEquals(expected.material, actual.material);
        assertEquals(expected.bodyTop, actual.bodyTop);
        assertEquals(expected.bodyFoot, actual.bodyFoot);
        assertEquals(expected.bodyAlpha, actual.bodyAlpha);
        assertEquals(expected.stroke, actual.stroke);
        assertEquals(expected.specularAlpha, actual.specularAlpha, 0f);
        assertEquals(expected.specularSpread, actual.specularSpread, 0f);
        assertEquals(expected.rimAlpha, actual.rimAlpha, 0f);
        assertEquals(expected.reflectionAlpha, actual.reflectionAlpha, 0f);
        assertEquals(expected.refractionAlpha, actual.refractionAlpha, 0f);
        assertEquals(expected.refractionColor, actual.refractionColor);
    }

    private static View tileView(ThemePreviewView preview) {
        try {
            java.lang.reflect.Field field = ThemePreviewView.class.getDeclaredField("tile");
            field.setAccessible(true);
            return (View) field.get(preview);
        } catch (Exception e) {
            throw new AssertionError("could not read the preview tile", e);
        }
    }

    private static Object field(Activity activity, String name) {
        try {
            java.lang.reflect.Field field = ThemeStudioActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity);
        } catch (Exception e) {
            throw new AssertionError("could not read " + name, e);
        }
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View view, List<View> into) {
        if (view == null) return;
        into.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), into);
        }
    }
}

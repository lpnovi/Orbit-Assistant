package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONObject;

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
 * The floating-surface material: a free choice, with premium tuning on top of it.
 *
 * <p>v0.8.0.0-beta.5 added the first Orbit setting that sits next to a premium one and is deliberately
 * not premium itself, which makes the boundary between them the thing most worth protecting. Orbit had
 * floating glass before Orbit Pro existed. Putting the glass behind an entitlement would take away
 * something that already shipped free, and this project has promised never to do that - so choosing
 * between Solid, Frosted and Liquid is free for everyone, and it is only the advanced opacity, tint and
 * edge controls that need Pro.
 *
 * <p>That split has two failure modes and they point in opposite directions. The material could drift
 * behind {@code hasPro} and quietly become a paid feature; or the premium tuning could leak out and
 * become free. Both are asserted here, from both entitlement states.
 *
 * <p>The third thing this file protects is the architecture. Three materials is three chances for a
 * screen to grow its own {@code if (material == ...)} and start drawing something slightly different
 * from everywhere else, which is a fault that only has to happen on one screen to be permanent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class SurfaceMaterialTest {

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

    /** Something premium in every glass field, so a value surviving by accident cannot pass. */
    private static OrbitProStyle tuned() {
        return OrbitProStyle.DEFAULT.withGlassOpacity(168).withGlassTint(170).withGlassEdge(150);
    }

    private Drawable surface(OrbitTheme theme) {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, theme);
        return OrbitFloatingSurface.surfaceDrawable(context,
                OrbitGlass.Palette.of(tokens, OrbitProStyle.resolve(context, theme.pro)),
                OrbitGlass.RADIUS_DP);
    }

    // ---- 1. a Beta 4 theme is Liquid ---------------------------------------------------------------

    /**
     * 1. Everything that predates this choice reads back as Liquid.
     *
     * <p>Liquid because that is what v0.8.0.0-beta.4 drew. A material choice must not be able to change
     * how an existing theme looks, and the only value that guarantees it is the one the previous release
     * was already rendering.
     */
    @Test public void anythingWithoutAMaterialIsLiquid() {
        assertEquals(OrbitTheme.MATERIAL_LIQUID, OrbitTheme.MATERIAL_DEFAULT);
        assertEquals("a theme built without one", OrbitTheme.MATERIAL_LIQUID,
                OrbitTheme.custom("Mine", "violet", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false).material);
        assertEquals("an install with no such preference", OrbitTheme.MATERIAL_LIQUID,
                OrbitThemeStore.activeMaterial(context));

        // And a theme document written by Beta 4, which has every other field and not this one.
        OrbitTheme imported = OrbitTheme.fromJson(betaFourDocument());
        assertNotNull(imported);
        assertEquals(OrbitTheme.MATERIAL_LIQUID, imported.material);
    }

    private static JSONObject betaFourDocument() {
        try {
            return new JSONObject()
                    .put("format", OrbitTheme.FORMAT)
                    .put("schema", 2)
                    .put("id", "t_betafour")
                    .put("name", "From Beta 4")
                    .put("accent", "violet")
                    .put("userBubble", OrbitTheme.CLASSIC)
                    .put("assistantBubble", OrbitTheme.CLASSIC)
                    .put("surface", OrbitTheme.CLASSIC)
                    .put("background", "#0A0714")
                    .put("amoled", false)
                    .put("pro", new JSONObject()
                            .put("glassOpacity", 188)
                            .put("glassTint", 155)
                            .put("glassEdge", 160));
        } catch (Exception e) {
            throw new AssertionError("could not build a Beta 4 document", e);
        }
    }

    /**
     * An install sitting on a shipped preset adopts that preset's material rather than Liquid.
     *
     * <p>The one deliberate exception, and it exists to stop Theme Studio re-labelling a theme nobody
     * edited. Signal Violet and Aurora chose Frosted in this release; without this rule every install
     * named after one of them would stop matching it and be renamed "Your theme" on update. A preset is
     * the definition of an appearance rather than a snapshot, so adopting its material is the faithful
     * answer - and a theme of the user's own still gets Liquid, which is what they were looking at.
     */
    @Test public void anInstallOnAPresetAdoptsThatPresetsMaterial() {
        Prefs.get(context).edit().putString(Prefs.THEME_ID, OrbitTheme.ID_AURORA).commit();
        assertEquals(OrbitTheme.builtIn(OrbitTheme.ID_AURORA).material,
                OrbitThemeStore.activeMaterial(context));

        Prefs.get(context).edit().putString(Prefs.THEME_ID, Prefs.THEME_ID_CUSTOM).commit();
        assertEquals("but a theme of the user's own keeps what Beta 4 drew",
                OrbitTheme.MATERIAL_LIQUID, OrbitThemeStore.activeMaterial(context));

        // An explicitly stored material always wins over both.
        Prefs.get(context).edit()
                .putString(Prefs.THEME_ID, OrbitTheme.ID_AURORA)
                .putString(Prefs.THEME_MATERIAL, OrbitTheme.MATERIAL_SOLID)
                .commit();
        assertEquals(OrbitTheme.MATERIAL_SOLID, OrbitThemeStore.activeMaterial(context));
    }

    /** And an install on a preset is still labelled with that preset after the update. */
    @Test public void updatingDoesNotRelabelAThemeNobodyEdited() {
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_SIGNAL_VIOLET,
                OrbitTheme.ID_NOVA_ULTRA, OrbitTheme.ID_DEFAULT, OrbitTheme.ID_NEBULA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertNotNull(preset);
            asPro();
            // Storage as it would be on a device that applied this preset before materials existed.
            assertTrue(OrbitThemeStore.applyActive(context, preset));
            Prefs.get(context).edit().remove(Prefs.THEME_MATERIAL).commit();

            OrbitTheme active = OrbitThemeStore.active(context);
            assertTrue(preset.name + " must still match the preset it is named after",
                    active.sameColours(preset));
            assertEquals(preset.name + " must still be labelled with it", preset.id,
                    OrbitThemeStore.canonicalIdentity(context, active).id);
        }
    }

    // ---- 2, 3 and 4. it persists everywhere a theme goes -------------------------------------------

    /** 2. Through the active theme. */
    @Test public void materialPersistsThroughTheActiveTheme() {
        for (String material : OrbitTheme.materials()) {
            OrbitTheme themed = OrbitTheme.orbitDefault().withMaterial(material);
            assertTrue(OrbitThemeStore.applyActive(context, themed));
            assertEquals(material, OrbitThemeStore.activeMaterial(context));
            assertEquals(material, OrbitThemeStore.active(context).material);
        }
    }

    /** 3. Through the saved library, a duplicate and a rename. */
    @Test public void materialPersistsThroughTheSavedLibrary() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "violet", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false, OrbitProStyle.DEFAULT,
                        OrbitTheme.MATERIAL_FROSTED));
        assertNotNull(saved);
        assertEquals(OrbitTheme.MATERIAL_FROSTED, saved.material);
        assertEquals(OrbitTheme.MATERIAL_FROSTED,
                OrbitThemeStore.preset(context, saved.id).material);

        OrbitTheme copy = OrbitThemeStore.duplicatePreset(context, saved.id);
        assertNotNull(copy);
        assertEquals("a duplicate keeps it", OrbitTheme.MATERIAL_FROSTED, copy.material);

        assertTrue(OrbitThemeStore.renamePreset(context, saved.id, "Renamed"));
        assertEquals("and so does a rename", OrbitTheme.MATERIAL_FROSTED,
                OrbitThemeStore.preset(context, saved.id).material);

        assertEquals("as does asking for it as a new custom theme",
                OrbitTheme.MATERIAL_FROSTED, saved.asCustomNamed("Another").material);
    }

    /** 4. And through a real export and import. */
    @Test public void materialRoundTripsThroughAThemeFile() throws Exception {
        for (String material : OrbitTheme.materials()) {
            OrbitTheme original = OrbitTheme.custom("Round trip", "violet", OrbitTheme.CLASSIC,
                    OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, "#0A0714", false, tuned(), material);
            String file = OrbitThemeFileCodec.encode(original);
            assertEquals("the material must be written as a plain token",
                    material, new JSONObject(file).getString("material"));

            OrbitTheme back = OrbitThemeFileCodec.decode(file);
            assertNotNull(back);
            assertEquals(material, back.material);
            assertTrue("along with everything else about the appearance",
                    back.sameColours(original));
        }
    }

    /** An unrecognised material degrades rather than throwing, like every other token. */
    @Test public void anUnknownMaterialFallsBackRatherThanFailing() {
        for (String rubbish : new String[]{null, "", "   ", "marble", "LIQUID GLASS", "3"}) {
            assertEquals(OrbitTheme.MATERIAL_LIQUID, OrbitTheme.normalizeMaterial(rubbish));
        }
        assertEquals("and casing is forgiven rather than rejected",
                OrbitTheme.MATERIAL_FROSTED, OrbitTheme.normalizeMaterial("  Frosted "));
        assertEquals(OrbitTheme.MATERIAL_SOLID,
                OrbitTheme.orbitDefault().withMaterial("SOLID").material);
    }

    // ---- 5 to 13. free chooses the material, Pro tunes it ------------------------------------------

    /**
     * 5, 6, 7 and 8. All three materials work without Pro, and none of them asks for it.
     *
     * <p>Asserted as behaviour rather than as an absence of a check: a Free device applies each material
     * in turn, and each one is both storable and actually drawn.
     */
    @Test public void everyMaterialIsAvailableWithoutPro() {
        asFree();
        for (String material : OrbitTheme.materials()) {
            OrbitTheme themed = OrbitTheme.orbitDefault().withMaterial(material);
            assertTrue(material + " must need no entitlement",
                    OrbitThemeStore.canApply(context, themed));
            assertTrue(OrbitThemeStore.applyActive(context, themed));
            UiKit.syncTheme(context);
            assertEquals(material, OrbitThemeStore.activeMaterial(context));
            assertEquals("and the live palette must be made of it",
                    material, OrbitGlass.Palette.live(context).material());
            assertNotNull("and it must actually draw", surface(themed));
        }
    }

    /**
     * 8 again, structurally. Nothing in the material path consults entitlement.
     *
     * <p>The specific regression this prevents is somebody later deciding the material "belongs with"
     * the premium styling and moving it into {@link OrbitProStyle}, which would gate it by construction
     * because {@code OrbitProStyle.resolve} returns defaults on a Free device.
     */
    @Test public void theMaterialIsNeverGatedByEntitlement() {
        String surface = ThemeStudioProTest.readSourceFile("OrbitFloatingSurface.java");
        assertFalse("the material resolver must not consult entitlement",
                surface.contains("hasPro(") || surface.contains("OrbitProEntitlement"));
        assertFalse("and must not read the developer preview preference",
                surface.contains("PRO_PREVIEW"));

        // The material is a field of the free theme, not of the premium styling block. Asserted on the
        // declarations rather than on the word, because OrbitProStyle legitimately explains in prose
        // which material its glass controls tune.
        assertTrue(ThemeStudioProTest.readSourceFile("OrbitTheme.java")
                .contains("public final String material;"));
        String premium = ThemeStudioProTest.readSourceFile("OrbitProStyle.java");
        assertFalse("it must not become a premium field",
                premium.contains("final String material")
                        || premium.contains("MATERIAL_")
                        || premium.contains("withMaterial("));
    }

    /**
     * 9, 10, 11, 12 and 13. The tuning is premium, falls back safely, and is never destroyed.
     *
     * <p>The pair of promises this release rests on. A Free device gets each material at Orbit's own
     * settings, which is a complete feature; a Pro device gets the stored tuning; and flipping between
     * them does not lose anything, because the stored values are read back after every transition.
     */
    @Test public void glassTuningIsPremiumAndNeverDestroyed() {
        for (String material : new String[]{OrbitTheme.MATERIAL_FROSTED,
                OrbitTheme.MATERIAL_LIQUID}) {
            asPro();
            OrbitTheme themed = OrbitTheme.orbitDefault().withMaterial(material).withPro(tuned());
            assertTrue(OrbitThemeStore.applyActive(context, themed));
            UiKit.syncTheme(context);

            OrbitGlass.Finish onPro = OrbitGlass.finishFor(OrbitGlass.Palette.live(context));
            assertEquals("Pro draws the stored tuning", tuned().glassOpacity, onPro.bodyAlpha);

            asFree();
            UiKit.syncTheme(context);
            OrbitGlass.Finish onFree = OrbitGlass.finishFor(OrbitGlass.Palette.live(context));
            assertEquals("Free draws the material at Orbit's own settings",
                    OrbitProStyle.GLASS_OPACITY_DEFAULT, onFree.bodyAlpha);
            assertEquals("but the material itself is still the one chosen",
                    material, OrbitGlass.Palette.live(context).material());
            assertTrue("and the tuning is still stored",
                    OrbitThemeStore.activeProStyle(context).same(tuned()));

            asPro();
            UiKit.syncTheme(context);
            assertEquals("returning to Pro brings the tuning back",
                    tuned().glassOpacity,
                    OrbitGlass.finishFor(OrbitGlass.Palette.live(context)).bodyAlpha);
        }
    }

    // ---- 14 to 17. Solid has no glass, and does not throw the glass away --------------------------

    /**
     * 14, 15, 16 and 17. Solid suppresses glass, keeps the settings, and gives them back.
     *
     * <p>Exactly the shape of the entitlement promise, applied to a free choice: what is stored and what
     * is drawn are separate questions. Choosing Solid is a statement about this theme's chrome, not an
     * instruction to discard three values the person spent time on.
     */
    @Test public void solidSuppressesGlassWithoutDiscardingIt() {
        asPro();
        OrbitTheme glassy = OrbitTheme.orbitDefault()
                .withMaterial(OrbitTheme.MATERIAL_LIQUID).withPro(tuned());
        assertTrue(OrbitThemeStore.applyActive(context, glassy));

        OrbitTheme solid = glassy.withMaterial(OrbitTheme.MATERIAL_SOLID);
        assertTrue(OrbitThemeStore.applyActive(context, solid));
        UiKit.syncTheme(context);

        Drawable drawn = surface(solid);
        assertTrue("Solid must be an opaque surface rather than glass",
                drawn instanceof GradientDrawable);
        assertFalse(drawn instanceof OrbitGlass.GlassDrawable);
        assertFalse("and Theme Studio must know there is nothing to tune",
                OrbitFloatingSurface.tunable(OrbitTheme.MATERIAL_SOLID));
        assertTrue("while every glass value stays exactly where it was",
                OrbitThemeStore.activeProStyle(context).same(tuned()));

        for (String back : new String[]{OrbitTheme.MATERIAL_FROSTED, OrbitTheme.MATERIAL_LIQUID}) {
            assertTrue(OrbitThemeStore.applyActive(context, solid.withMaterial(back)));
            UiKit.syncTheme(context);
            assertEquals("and returning to " + back + " restores the tuning",
                    tuned().glassOpacity,
                    OrbitGlass.finishFor(OrbitGlass.Palette.live(context)).bodyAlpha);
            assertTrue(OrbitThemeStore.activeProStyle(context).same(tuned()));
        }
    }

    /** Solid is a real Orbit surface rather than a flat rectangle. */
    @Test public void solidIsAnIntentionalSurfaceRatherThanAFlatFill() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault());
        OrbitGlass.Palette p = OrbitGlass.Palette.of(tokens, OrbitProStyle.DEFAULT)
                .asMaterial(OrbitTheme.MATERIAL_SOLID);
        GradientDrawable solid = OrbitFloatingSurface.solidDrawable(context, p, OrbitGlass.RADIUS_DP);
        int[] colors = solid.getColors();
        assertNotNull("it must have tonal depth, not one flat colour", colors);
        assertEquals(2, colors.length);
        assertTrue("whose two ends actually differ", colors[0] != colors[1]);
        for (int color : colors) {
            assertEquals("and none of it may be translucent, which is the point of Solid",
                    255, android.graphics.Color.alpha(color));
        }
        assertTrue("it must still be read against something legible",
                OrbitContrast.contrastRatio(OrbitFloatingSurface.inkOn(p),
                        OrbitFloatingSurface.effectiveFill(p)) >= OrbitContrast.BODY_TEXT_MIN);
    }

    // ---- 18 and 19. one resolver ------------------------------------------------------------------

    /**
     * 18. Every real consumer goes through the shared resolver.
     *
     * <p>Named files rather than a pattern, because the list is short and the point is that it is
     * complete: these are the screens that float a control, and a new one that forgot would be invisible
     * until somebody noticed one screen looking different from the others.
     */
    @Test public void everyFloatingSurfaceComesFromTheResolver() {
        for (String file : new String[]{"MainActivity.java", "OrbitVaultActivity.java",
                "GlassStylePreview.java"}) {
            String body = ThemeStudioProTest.readSourceFile(file);
            assertTrue(file + " must dress its floating controls through OrbitFloatingSurface",
                    body.contains("OrbitFloatingSurface.floatControl(")
                            || body.contains("OrbitFloatingSurface.interactive(")
                            || body.contains("OrbitFloatingSurface.surfaceDrawable("));
            assertFalse(file + " must not build glass directly any more",
                    body.contains("OrbitGlass.surfaceDrawable(")
                            || body.contains("OrbitGlass.floatControl(")
                            || body.contains("OrbitGlass.interactive("));
        }
    }

    /**
     * 19. No screen branches on the material.
     *
     * <p>Written against the material constants rather than against the word, so a screen that mentions
     * materials in a comment is fine and one that compares against them is not. {@code OrbitTheme} owns
     * the vocabulary, {@code OrbitFloatingSurface} owns the switch, {@code OrbitGlass} distinguishes
     * Frosted from Liquid inside the glass, and Theme Studio legitimately shows the selector.
     */
    @Test public void noScreenBranchesOnTheMaterial() {
        List<String> allowed = List.of("OrbitTheme.java", "OrbitFloatingSurface.java",
                "OrbitGlass.java", "ThemeStudioActivity.java", "GlassStylePreview.java",
                "OrbitThemeStore.java");
        List<String> offenders = new ArrayList<>();
        for (Path source : ThemeStudioProTest.mainSources()) {
            String name = source.getFileName().toString();
            if (allowed.contains(name)) continue;
            String body = ThemeStudioProTest.readSourceFile(name);
            for (String constant : new String[]{"MATERIAL_SOLID", "MATERIAL_FROSTED",
                    "MATERIAL_LIQUID"}) {
                if (body.contains(constant)) offenders.add(name + " decides on " + constant);
            }
        }
        assertTrue("the material switch must live in one place: " + offenders, offenders.isEmpty());
    }

    /** The three materials are a closed, ordered, stably identified set. */
    @Test public void theMaterialsAreAStableOrderedSet() {
        String[] materials = OrbitTheme.materials();
        assertEquals(3, materials.length);
        assertEquals("plainest first, so the selector reads as increasing richness",
                OrbitTheme.MATERIAL_SOLID, materials[0]);
        assertEquals(OrbitTheme.MATERIAL_FROSTED, materials[1]);
        assertEquals(OrbitTheme.MATERIAL_LIQUID, materials[2]);
        for (int i = 0; i < materials.length; i++) {
            assertEquals(i, OrbitTheme.materialIndex(materials[i]));
            assertFalse("a stored id must never be a display string",
                    materials[i].equals(OrbitTheme.materialLabel(materials[i])));
            assertNotNull(OrbitFloatingSurface.materialDescription(materials[i]));
        }
        assertEquals("Solid", OrbitTheme.materialLabel(OrbitTheme.MATERIAL_SOLID));
        assertEquals("Frosted", OrbitTheme.materialLabel(OrbitTheme.MATERIAL_FROSTED));
        assertEquals("Liquid", OrbitTheme.materialLabel(OrbitTheme.MATERIAL_LIQUID));
    }

    /** A material change is an appearance change, or Theme Studio could not tell a draft moved. */
    @Test public void aMaterialChangeIsAnAppearanceChange() {
        OrbitTheme liquid = OrbitTheme.orbitDefault().withMaterial(OrbitTheme.MATERIAL_LIQUID);
        assertFalse(liquid.sameColours(liquid.withMaterial(OrbitTheme.MATERIAL_FROSTED)));
        assertFalse(liquid.sameColours(liquid.withMaterial(OrbitTheme.MATERIAL_SOLID)));
        assertTrue(liquid.sameColours(liquid.withMaterial(OrbitTheme.MATERIAL_LIQUID)));

        // And screens rebuild for it, including on a Free device where the premium signature cannot move.
        asFree();
        assertTrue(OrbitThemeStore.applyActive(context, liquid));
        UiKit.syncTheme(context);
        String before = UiKit.structuralAppearanceSignature(context);
        String appearanceBefore = UiKit.appearanceSignature(context);
        assertTrue(OrbitThemeStore.applyActive(context,
                liquid.withMaterial(OrbitTheme.MATERIAL_SOLID)));
        UiKit.syncTheme(context);
        assertFalse("a free material change must still rebuild the pages",
                before.equals(UiKit.structuralAppearanceSignature(context)));
        assertFalse(appearanceBefore.equals(UiKit.appearanceSignature(context)));
    }

    // ---- the shipped presets ----------------------------------------------------------------------

    /**
     * The presets demonstrate more than one material, without any of them changing on update.
     *
     * <p>Orbit Default, Orbit AMOLED and Nova AMOLED stay Liquid deliberately: they are the appearances
     * most installs are actually sitting on, and the compatibility promise is that an update changes
     * nothing nobody asked for. The presets that do choose Frosted are the ones where it is a design
     * statement, and a person is one tap away from Frosted on any theme regardless.
     */
    @Test public void theShippedPresetsDemonstrateMoreThanOneMaterial() {
        assertEquals("the appearance most installs have must not change",
                OrbitTheme.MATERIAL_LIQUID, OrbitTheme.builtIn(OrbitTheme.ID_DEFAULT).material);
        assertEquals(OrbitTheme.MATERIAL_LIQUID, OrbitTheme.builtIn(OrbitTheme.ID_AMOLED).material);
        assertEquals(OrbitTheme.MATERIAL_LIQUID,
                OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED).material);

        assertEquals("Aurora shows Frosted, so the gradient behind it is what you look at",
                OrbitTheme.MATERIAL_FROSTED, OrbitTheme.builtIn(OrbitTheme.ID_AURORA).material);
        assertEquals("Signal Violet shows Frosted, which is what its restraint looks like",
                OrbitTheme.MATERIAL_FROSTED,
                OrbitTheme.builtIn(OrbitTheme.ID_SIGNAL_VIOLET).material);
        assertEquals("Nebula Glass stays the glass one",
                OrbitTheme.MATERIAL_LIQUID, OrbitTheme.builtIn(OrbitTheme.ID_NEBULA_GLASS).material);
        assertEquals("and Nova Ultra keeps Liquid, which is what a glow is for",
                OrbitTheme.MATERIAL_LIQUID, OrbitTheme.builtIn(OrbitTheme.ID_NOVA_ULTRA).material);

        // More than one material across the shipped set, which is the point of assigning them at all.
        List<String> used = new ArrayList<>();
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            if (!used.contains(preset.material)) used.add(preset.material);
        }
        assertTrue("the shipped presets must show off more than one material", used.size() >= 2);
    }

    /** Every preset stays readable in the material it ships with. */
    @Test public void everyPresetIsReadableInItsOwnMaterial() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            OrbitGlass.Palette p = OrbitGlass.Palette.of(
                    OrbitThemeTokens.resolve(context, preset), preset.pro);
            assertEquals("the palette must carry the preset's own material",
                    preset.material, p.material());
            int fill = OrbitFloatingSurface.effectiveFill(p);
            assertTrue(preset.name + " must keep its floating controls readable",
                    OrbitContrast.contrastRatio(OrbitFloatingSurface.inkOn(p), fill)
                            >= OrbitContrast.BODY_TEXT_MIN);
        }
    }
}

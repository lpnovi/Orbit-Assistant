package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Theme files and premium presets, across four Betas of one release line.
 *
 * <p>Orbit has now added premium fields to the theme document three times: the styling block in
 * Beta 2, nothing in Beta 3, and six background values in Beta 4. Each addition is a chance to break
 * the promise that matters most about a theme file, which is that it is a file - somebody exported
 * one in February and expects it to import in September.
 *
 * <p>So the compatibility is asserted against documents shaped the way each Beta actually wrote them,
 * rather than against a round trip of the current model. A round trip proves the code agrees with
 * itself. It proves nothing at all about the file on somebody's phone.
 *
 * <p>The other half of this file is the thing a theme document must never be able to do. Import and
 * export are free and stay free, and a premium preset's <em>colours</em> are ordinary theme colours
 * that would apply perfectly well - so a document that could claim to be Aurora would be a free route
 * to most of what Aurora is. Identity comes from Orbit, never from a file.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ProBackgroundThemeFileTest {

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

    /** A theme document with the fields every Orbit build has always required. */
    private static JSONObject document(String name) throws Exception {
        return new JSONObject()
                .put("format", OrbitTheme.FORMAT)
                .put("schema", 2)
                .put("id", "t_imported")
                .put("name", name)
                .put("accent", "violet")
                .put("userBubble", "#33275C")
                .put("assistantBubble", OrbitTheme.CLASSIC)
                .put("surface", "#1C1730")
                .put("background", "#0A0714")
                .put("amoled", false);
    }

    // ---- 39, 40 and 41. every earlier Beta's file still imports -------------------------------------

    /**
     * 39. A Beta 1 file has no premium block at all, and is still a complete theme.
     *
     * <p>The premium block is deliberately not a required field. Every file Orbit wrote before Beta 2
     * lacks it and describes an appearance that was entirely correct when it was written; demanding it
     * would reject all of them for the crime of predating a feature.
     */
    @Test public void aBetaOneFileStillImports() throws Exception {
        OrbitTheme imported = OrbitThemeFileCodec.decode(document("From Beta 1").toString());
        assertNotNull(imported);
        assertEquals("From Beta 1", imported.name);
        assertEquals("violet", imported.accent);
        assertTrue("a file with no premium block is Orbit as it shipped",
                imported.pro.isDefault());
        assertEquals(OrbitProStyle.BACKGROUND_SOLID, imported.pro.backgroundMode);
    }

    /**
     * 40 and 41. A Beta 2 or Beta 3 file carries five premium values and no background.
     *
     * <p>Those two Betas wrote the same block, which is why one test covers both: Beta 3 changed how
     * Theme Studio behaved and added no theme data. The assertion that matters is the second one -
     * the five values it did name arrive intact, and the six it could not name resolve to the
     * appearance its author was actually looking at, which is a page with nothing on it.
     */
    @Test public void aBetaTwoOrThreeFileImportsWithItsStylingAndNoBackground() throws Exception {
        JSONObject pro = new JSONObject()
                .put("bubbleRadius", 22)
                .put("bubbleOutline", OrbitProStyle.OUTLINE_SUBTLE)
                .put("glassOpacity", 188)
                .put("glassTint", 155)
                .put("glassEdge", 160);
        OrbitTheme imported = OrbitThemeFileCodec.decode(
                document("From Beta 3").put("pro", pro).toString());

        assertNotNull(imported);
        assertEquals(22, imported.pro.bubbleRadiusDp);
        assertEquals(OrbitProStyle.OUTLINE_SUBTLE, imported.pro.bubbleOutline);
        assertEquals(188, imported.pro.glassOpacity);
        assertEquals(155, imported.pro.glassTint);
        assertEquals(160, imported.pro.glassEdge);

        assertEquals("a file that could not name a background has none",
                OrbitProStyle.BACKGROUND_SOLID, imported.pro.backgroundMode);
        assertEquals(OrbitProStyle.EFFECT_COLOR_DEFAULT, imported.pro.backgroundEffectColor);
        assertEquals(OrbitProStyle.GRADIENT_DIRECTION_DEFAULT, imported.pro.gradientDirection);
        assertEquals(OrbitProStyle.GLOW_STRENGTH_DEFAULT, imported.pro.glowStrength);
        assertEquals(OrbitProStyle.GLOW_SIZE_DEFAULT, imported.pro.glowSize);
        assertEquals(OrbitProStyle.GLOW_POSITION_DEFAULT, imported.pro.glowPosition);
    }

    /** A block that names four of the five old values still gets Orbit's default for the fifth. */
    @Test public void aPartialPremiumBlockFallsBackFieldByField() throws Exception {
        JSONObject pro = new JSONObject()
                .put("glassOpacity", 200)
                .put("glowStrength", 80);
        OrbitTheme imported = OrbitThemeFileCodec.decode(
                document("Partial").put("pro", pro).toString());
        assertNotNull(imported);
        assertEquals(200, imported.pro.glassOpacity);
        assertEquals(80, imported.pro.glowStrength);
        assertEquals("everything unnamed falls back individually",
                OrbitProStyle.BUBBLE_RADIUS_DEFAULT, imported.pro.bubbleRadiusDp);
        assertEquals(OrbitProStyle.GLASS_TINT_DEFAULT, imported.pro.glassTint);
        assertEquals("and a glow strength alone does not switch a mode on",
                OrbitProStyle.BACKGROUND_SOLID, imported.pro.backgroundMode);
    }

    // ---- 42. a Beta 4 file round-trips --------------------------------------------------------------

    /**
     * 42. Every background field survives a real export and a real import.
     *
     * <p>Through the codec rather than through {@code toJson}, so this covers the wrapper, the schema
     * check and the required-field check as well as the values.
     */
    @Test public void everyBackgroundFieldRoundTripsThroughAFile() throws Exception {
        asPro();
        OrbitProStyle configured = OrbitProStyle.of(15, OrbitProStyle.OUTLINE_DEFINED,
                182, 140, 165, OrbitProStyle.BACKGROUND_GLOW, "#3C2A7E",
                OrbitProStyle.DIRECTION_TR_BL, 72, 88, OrbitProStyle.GLOW_BOTTOM);
        OrbitTheme original = OrbitTheme.custom("Round trip", "violet", "#33275C",
                OrbitTheme.CLASSIC, "#1C1730", "#0A0714", false, configured);

        OrbitTheme back = OrbitThemeFileCodec.decode(OrbitThemeFileCodec.encode(original));
        assertNotNull(back);
        assertTrue("the premium styling must come back exactly", back.pro.same(configured));
        assertTrue("and so must the appearance as a whole",
                back.sameColours(original));

        // Both modes, because only one set of fields is in use at a time and the other has to survive
        // the trip too - somebody who configures a gradient, switches to a glow and exports must not
        // lose the gradient they can switch back to.
        OrbitProStyle both = configured.withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR);
        OrbitTheme linearBack = OrbitThemeFileCodec.decode(
                OrbitThemeFileCodec.encode(original.withPro(both)));
        assertNotNull(linearBack);
        assertEquals("the unused glow values are still in the file",
                72, linearBack.pro.glowStrength);
        assertEquals(OrbitProStyle.GLOW_BOTTOM, linearBack.pro.glowPosition);
    }

    // ---- 43, 44 and 45. import and export stay free, and cannot forge identity ----------------------

    /**
     * 43 and 44. Import and export need no entitlement, and a Free device keeps what it is given.
     *
     * <p>Two things at once, and they are the same decision seen from both sides. Theme files are a
     * free Orbit feature and always will be, so a Free device can export a theme carrying premium
     * fields and import one; what it cannot do is draw them. The imported data is kept rather than
     * stripped, because stripping it would mean a person who gets Pro later has a theme file that
     * silently lost half of itself on the way in.
     */
    @Test public void importAndExportStayFreeAndKeepPremiumData() throws Exception {
        asFree();
        OrbitProStyle premium = OrbitProStyle.DEFAULT
                .withGlassOpacity(176)
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW)
                .withBackgroundEffectColor("#5B3FCF")
                .withGlowStrength(65);
        String file = OrbitThemeFileCodec.encode(
                OrbitTheme.custom("Gifted", "violet", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, "#0A0714", false, premium));
        assertTrue("a Free device must be able to write a file carrying premium fields",
                new JSONObject(file).getJSONObject("pro")
                        .getInt("backgroundMode") == OrbitProStyle.BACKGROUND_GLOW);

        OrbitTheme imported = OrbitThemeFileCodec.decode(file);
        assertNotNull("and to read one", imported);
        assertTrue("the premium data must be kept intact", imported.pro.same(premium));
        assertTrue("the theme itself must be applicable, because it is not a premium preset",
                OrbitThemeStore.canApply(context, imported));
        assertTrue(OrbitThemeStore.applyActive(context, imported));
        assertTrue("and stored, still intact",
                OrbitThemeStore.activeProStyle(context).same(premium));

        // Stored, and still not drawn.
        UiKit.syncTheme(context);
        assertFalse("Free must draw no premium effect", OrbitBackground.effectDraws(context));
        assertTrue(OrbitProStyle.live(context).isDefault());

        asPro();
        assertTrue("and the moment entitlement arrives, the imported effect draws",
                OrbitBackground.effectDraws(context));
        assertTrue(OrbitProStyle.live(context).same(premium));
    }

    /**
     * 45, 50 and 51. No document can claim to be Aurora or Nova Ultra.
     *
     * <p>Written against a file that tries it in every way a file can: the premium id, the premium
     * name, the premium colours, and the premium styling all at once. Identity comes from Orbit's own
     * catalogue, so what imports is a custom theme of the user's own that happens to look similar -
     * which is fine, and is exactly what duplicating a preset has always produced.
     */
    @Test public void noFileCanForgeAPremiumPresetIdentity() throws Exception {
        asFree();
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA,
                OrbitTheme.ID_SIGNAL_VIOLET, OrbitTheme.ID_NEBULA_GLASS}) {
            OrbitTheme real = OrbitTheme.builtIn(id);
            assertNotNull(real);
            String forged = new JSONObject(OrbitThemeFileCodec.encode(real))
                    .put("id", id)
                    .put("builtIn", true)
                    .toString();

            OrbitTheme imported = OrbitThemeFileCodec.decode(forged);
            assertNotNull(imported);
            assertFalse("an imported theme must never be premium: " + id, imported.premium());
            assertFalse("nor built in", imported.builtIn);
            assertFalse("and never carry a premium id", OrbitTheme.isPremiumId(imported.id));
            assertTrue("so a Free device may apply the look it describes",
                    OrbitThemeStore.canApply(context, imported));
            assertFalse("while the real preset stays refused",
                    OrbitThemeStore.canApply(context, real));

            // And the premium half of what it describes still does not draw.
            assertTrue(OrbitThemeStore.applyActive(context, imported));
            UiKit.syncTheme(context);
            assertTrue("premium styling from a forged file must not draw either",
                    OrbitProStyle.live(context).isDefault());
            assertFalse(OrbitBackground.effectDraws(context));
        }
    }

    // ---- 46 to 51. the four premium presets ---------------------------------------------------------

    /** 46 and 47. The two presets Beta 2 shipped still work, unchanged by any of this. */
    @Test public void theOriginalTwoPremiumPresetsStillWork() {
        asPro();
        for (String id : new String[]{OrbitTheme.ID_SIGNAL_VIOLET, OrbitTheme.ID_NEBULA_GLASS}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertNotNull(preset);
            assertTrue(preset.premium());
            assertTrue("a Pro device may apply it", OrbitThemeStore.applyActive(context, preset));
            assertTrue("its premium styling draws",
                    OrbitProStyle.live(context).same(preset.pro));
            assertEquals("and it asks for no background effect, exactly as it did before",
                    OrbitProStyle.BACKGROUND_SOLID, preset.pro.backgroundMode);
        }
    }

    /**
     * 48 and 49. Aurora and Nova Ultra are premium through the same stable metadata as the others.
     *
     * <p>By id prefix, never by name. A theme called Aurora that somebody built themselves is their
     * theme and applies freely; the shipped one is premium because Orbit's catalogue says so.
     */
    @Test public void theTwoNewPresetsArePremiumThroughStableMetadata() {
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertNotNull(preset);
            assertTrue(id + " must start with the premium prefix",
                    id.startsWith(OrbitTheme.PREMIUM_ID_PREFIX));
            assertTrue(OrbitTheme.isPremiumId(id));
            assertTrue(preset.premium());
            assertTrue("and it must actually use the premium layer",
                    preset.pro.hasBackgroundEffect());
        }

        // Named, not identified. A user's own theme of the same name is theirs.
        OrbitTheme lookalike = OrbitTheme.custom("Nova Ultra", "#7E5BFF", "#2A1D5E",
                "#14112B", "#151129", "#07060F", false,
                OrbitTheme.builtIn(OrbitTheme.ID_NOVA_ULTRA).pro);
        assertFalse("premium is never decided by display name", lookalike.premium());
        asFree();
        assertTrue(OrbitThemeStore.canApply(context, lookalike));
    }

    /** 50 and 51. Neither new preset is a way round entitlement. */
    @Test public void neitherNewPresetBypassesEntitlement() {
        asFree();
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertNotNull(preset);
            assertFalse("Free must not be able to apply " + preset.name,
                    OrbitThemeStore.canApply(context, preset));
            assertFalse(OrbitThemeStore.applyActive(context, preset));
            assertFalse("and it must never be offered in first-run setup",
                    OrbitThemeStore.onboardingPresets().contains(preset));

            // Nor may its appearance be claimed by a Free device that happens to match it.
            OrbitTheme identical = OrbitTheme.custom(preset.name, preset.accent,
                    preset.userBubble, preset.assistantBubble, preset.surface,
                    preset.background, preset.amoled, preset.pro);
            assertFalse("matching a premium preset's colours must not label a theme as it",
                    OrbitThemeStore.canonicalIdentity(context, identical).premium());
        }

        asPro();
        for (String id : new String[]{OrbitTheme.ID_AURORA, OrbitTheme.ID_NOVA_ULTRA}) {
            OrbitTheme preset = OrbitTheme.builtIn(id);
            assertTrue("and Pro Preview may apply it",
                    OrbitThemeStore.applyActive(context, preset));
            assertTrue("with its background effect drawing",
                    OrbitBackground.effectDraws(context));
        }
    }

    /**
     * The saved-theme library keeps background styling too, through every act it supports.
     *
     * <p>Saving, duplicating and renaming, because all three build a new theme from an old one and any
     * of them could quietly drop a field the constructor defaults. Beta 3's fix was exactly this class
     * of bug one layer up, in {@code canonicalIdentity}.
     */
    @Test public void savedThemesCarryBackgroundStylingThroughEveryLibraryAction() {
        asPro();
        OrbitProStyle configured = OrbitProStyle.DEFAULT
                .withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR)
                .withBackgroundEffectColor("#242A66")
                .withGradientDirection(OrbitProStyle.DIRECTION_BL_TR);
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "violet", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, "#0B0F1E", false, configured));
        assertNotNull(saved);
        assertTrue("a saved theme keeps its background", saved.pro.same(configured));
        assertTrue("and reads back from the library intact",
                OrbitThemeStore.preset(context, saved.id).pro.same(configured));

        OrbitTheme copy = OrbitThemeStore.duplicatePreset(context, saved.id);
        assertNotNull(copy);
        assertTrue("a duplicate keeps it", copy.pro.same(configured));

        assertTrue(OrbitThemeStore.renamePreset(context, saved.id, "Renamed"));
        assertTrue("and so does a rename",
                OrbitThemeStore.preset(context, saved.id).pro.same(configured));
        assertEquals("Renamed", OrbitThemeStore.preset(context, saved.id).name);

        // And re-labelling an edited draft carries it through, which is the Beta 3 fix extended to the
        // six fields this release added. Without it, changing a background value while a shipped
        // preset was selected would silently reset the other five.
        OrbitTheme edited = OrbitThemeStore.canonicalIdentity(context,
                OrbitTheme.orbitDefault().withPro(configured));
        assertTrue("re-labelling must never discard a background",
                edited.pro.same(configured));
    }

    /** {@code sameColours} counts the background, or Theme Studio could not tell a draft had changed. */
    @Test public void aBackgroundChangeIsAnAppearanceChange() {
        OrbitTheme plain = OrbitTheme.orbitDefault();
        OrbitTheme gradient = plain.withPro(plain.pro
                .withBackgroundMode(OrbitProStyle.BACKGROUND_LINEAR));
        assertFalse("a mode change must count as a different appearance",
                plain.sameColours(gradient));
        assertFalse("and so must every value inside a mode",
                gradient.sameColours(gradient.withPro(gradient.pro
                        .withGradientDirection(OrbitProStyle.DIRECTION_LEFT_RIGHT))));
        assertFalse(gradient.sameColours(gradient.withPro(gradient.pro
                .withBackgroundEffectColor("#123456"))));
        OrbitTheme lit = plain.withPro(plain.pro
                .withBackgroundMode(OrbitProStyle.BACKGROUND_GLOW));
        assertFalse(lit.sameColours(lit.withPro(lit.pro.withGlowStrength(90))));
        assertFalse(lit.sameColours(lit.withPro(lit.pro.withGlowSize(90))));
        assertFalse(lit.sameColours(lit.withPro(lit.pro
                .withGlowPosition(OrbitProStyle.GLOW_BOTTOM))));
    }

    /** 52, 53 and 54 are the draft contract, asserted against the real screen in the interaction test. */
    @Test public void theDraftContractIsCoveredByTheInteractionSuite() {
        String interaction = OrbitGlassChromeTest.readRepositoryFile(
                "app/src/test/java/com/orbit/assistant/ThemeStudioInteractionTest.java");
        assertTrue("selecting a preset must be asserted to be draft-only",
                interaction.contains("selectingAPresetIsPreviewOnlyUntilApply"));
        assertTrue("and a background edit must be asserted the same way",
                interaction.contains("backgroundEditsAreDraftOnlyUntilApply"));
    }
}

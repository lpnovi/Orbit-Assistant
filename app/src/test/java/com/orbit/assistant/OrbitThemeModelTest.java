package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The theme model, its presets, and the library the user saves into.
 *
 * <p>The invariant worth stating up front: a theme is always valid. Anything that arrives from
 * storage passes through normalisation, so no screen has to defend against a truncated hex value or
 * a name from a file somebody edited by hand. Most of this file is that claim, tested from the
 * outside.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitThemeModelTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
    }

    // ---- default and built-ins --------------------------------------------------------------------

    @Test public void theDefaultThemeIsOrbitsOwnAppearance() {
        OrbitTheme theme = OrbitTheme.orbitDefault();
        assertEquals(OrbitTheme.ID_DEFAULT, theme.id);
        assertEquals("Orbit Default", theme.name);
        assertTrue(theme.builtIn);
        assertEquals(OrbitTheme.DYNAMIC, theme.accent);
        assertEquals(OrbitTheme.CLASSIC, theme.userBubble);
        assertEquals(OrbitTheme.CLASSIC, theme.assistantBubble);
        assertEquals(OrbitTheme.CLASSIC, theme.surface);
        assertEquals(OrbitTheme.CLASSIC, theme.background);
        assertFalse(theme.amoled);
        assertTrue(theme.usesClassicSurfaces());
    }

    /**
     * A fresh install must render exactly as it did before Theme Studio existed. Not approximately:
     * the classic path returns Orbit's hand-tuned constants rather than a formula's version of them.
     */
    @Test public void theDefaultThemeReproducesOrbitsShippedCanvasExactly() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault());
        assertEquals(Color.rgb(10, 12, 17), tokens.background);
        assertEquals(Color.rgb(22, 25, 33), tokens.surface);
        assertEquals(Color.rgb(30, 34, 44), tokens.surface2);
        assertEquals(Color.rgb(37, 41, 53), tokens.surface3);
        assertEquals(Color.rgb(244, 244, 248), tokens.text);
        assertEquals(Color.rgb(166, 171, 185), tokens.muted);
    }

    @Test public void amoledBlacksThePageAndLeavesCardsAlone() {
        OrbitTheme amoled = OrbitTheme.builtIn(OrbitTheme.ID_AMOLED);
        assertNotNull(amoled);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, amoled);
        assertEquals("the page must be true black", Color.BLACK, tokens.background);
        assertEquals("cards keep their own colour so they stay visible",
                Color.rgb(22, 25, 33), tokens.surface);
        assertEquals(Color.rgb(30, 34, 44), tokens.surface2);
        assertNotEquals(tokens.background, tokens.surface);
        assertTrue("cards must be distinguishable from the page",
                OrbitContrast.contrastRatio(tokens.surface, tokens.background) > 1.05d);
    }

    /** AMOLED is a promise about the page, so it wins over a custom background colour. */
    @Test public void amoledOverridesACustomBackground() {
        OrbitTheme theme = OrbitTheme.custom("night", OrbitTheme.DYNAMIC, OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, "#1A1626", "#0B0714", true);
        assertEquals(Color.BLACK, OrbitThemeTokens.resolve(context, theme).background);
        assertEquals("and the colour returns when AMOLED is off",
                Color.rgb(0x0B, 0x07, 0x14),
                OrbitThemeTokens.resolve(context, theme.withAmoled(false)).background);
    }

    @Test public void everyBuiltInPresetIsWellFormed() {
        List<OrbitTheme> presets = OrbitTheme.builtIns();
        assertTrue("Orbit ships a curated set, not a novelty catalogue",
                presets.size() >= 5 && presets.size() <= 8);
        for (OrbitTheme preset : presets) {
            assertTrue(preset.name + " must be immutable", preset.builtIn);
            assertFalse(preset.id.trim().isEmpty());
            assertFalse(preset.name.trim().isEmpty());
            assertEquals(OrbitTheme.SCHEMA, preset.schema);
            assertSame(preset, OrbitTheme.builtIn(preset.id));
            assertTrue(OrbitTheme.isBuiltInId(preset.id));
        }
    }

    private static void assertSame(OrbitTheme expected, OrbitTheme actual) {
        assertNotNull(actual);
        assertEquals(expected.id, actual.id);
    }

    /** No preset Orbit ships may produce text a person cannot read. */
    @Test public void everyBuiltInPresetIsReadable() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, preset);
            for (OrbitThemeTokens.Check check : tokens.readability()) {
                assertTrue(preset.name + " fails " + check.label + " at "
                        + String.format(java.util.Locale.US, "%.2f", check.ratio),
                        check.passes);
            }
            assertFalse(preset.name + " must not warn about itself", tokens.hasLowContrast());
        }
    }

    /** Two presets that look the same are one preset with two names. */
    @Test public void everyBuiltInPresetIsVisuallyDistinct() {
        List<OrbitTheme> presets = OrbitTheme.builtIns();
        for (int i = 0; i < presets.size(); i++) {
            for (int j = i + 1; j < presets.size(); j++) {
                assertFalse(presets.get(i).name + " and " + presets.get(j).name + " are identical",
                        presets.get(i).sameColours(presets.get(j)));
            }
        }
    }

    @Test public void aBuiltInPresetIsNeverDeletable() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            assertFalse("deleting " + preset.name + " must be refused",
                    OrbitThemeStore.deletePreset(context, preset.id));
            assertFalse("renaming " + preset.name + " must be refused",
                    OrbitThemeStore.renamePreset(context, preset.id, "Mine"));
            assertNotNull("and it must still be there",
                    OrbitThemeStore.preset(context, preset.id));
        }
    }

    // ---- tokens ------------------------------------------------------------------------------------

    @Test public void hexTokensRoundTrip() {
        assertEquals("#8B7CFF", OrbitTheme.parseHexToken("#8b7cff"));
        assertEquals("#8B7CFF", OrbitTheme.parseHexToken("  #8B7CFF  "));
        assertEquals(Color.rgb(0x8B, 0x7C, 0xFF), OrbitTheme.hexTokenColor("#8B7CFF"));
        assertEquals("#8B7CFF", OrbitTheme.colorToken(Color.rgb(0x8B, 0x7C, 0xFF)));
        assertTrue(OrbitTheme.isHexToken("#000000"));
    }

    @Test public void malformedHexIsNotAToken() {
        for (String bad : new String[]{"#8B7CF", "#8B7CFFF", "8B7CFF", "#GGGGGG", "", "#",
                "rgb(1,2,3)", null}) {
            assertNull(String.valueOf(bad) + " must not parse", OrbitTheme.parseHexToken(bad));
            assertFalse(OrbitTheme.isHexToken(bad));
        }
    }

    /** Alpha is dropped rather than stored: a translucent card is a bug, not a theme. */
    @Test public void colourTokensAreAlwaysOpaque() {
        assertEquals("#112233", OrbitTheme.colorToken(Color.argb(7, 0x11, 0x22, 0x33)));
        OrbitTheme theme = OrbitTheme.custom("t", "#112233", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false);
        assertEquals(255, Color.alpha(OrbitThemeTokens.resolve(context, theme).accent));
    }

    @Test public void unknownTokensFallBackRatherThanCrash() {
        OrbitTheme theme = new OrbitTheme("id", "n", false, "not-a-colour", "also-not",
                "nope", "neither", "nor", false);
        assertEquals(OrbitTheme.DYNAMIC, theme.accent);
        assertEquals(OrbitTheme.CLASSIC, theme.userBubble);
        assertEquals(OrbitTheme.CLASSIC, theme.assistantBubble);
        assertEquals(OrbitTheme.CLASSIC, theme.surface);
        assertEquals(OrbitTheme.CLASSIC, theme.background);
    }

    /** Existing catalogue keys are still first-class, which is what makes migration a no-op. */
    @Test public void theLegacyCatalogueKeysRemainValidTokens() {
        for (String key : UiKit.accentKeys()) {
            assertEquals(key, OrbitTheme.orbitDefault().withAccent(key).accent);
        }
        for (String key : UiKit.bubbleColorKeys()) {
            assertEquals(key, OrbitTheme.orbitDefault().withUserBubble(key).userBubble);
        }
    }

    // ---- names ---------------------------------------------------------------------------------------

    @Test public void blankNamesBecomeSomethingShowable() {
        assertEquals("Custom theme", OrbitTheme.normalizeName(""));
        assertEquals("Custom theme", OrbitTheme.normalizeName("   "));
        assertEquals("Custom theme", OrbitTheme.normalizeName(null));
        assertEquals("Custom theme", OrbitTheme.normalizeName("\n\t "));
    }

    @Test public void veryLongNamesAreTrimmedToSomethingThatFits() {
        StringBuilder long_ = new StringBuilder();
        for (int i = 0; i < 500; i++) long_.append("x");
        String name = OrbitTheme.normalizeName(long_.toString());
        assertEquals(OrbitTheme.MAX_NAME_LENGTH, name.length());
    }

    @Test public void whitespaceInNamesIsCollapsed() {
        assertEquals("My night theme", OrbitTheme.normalizeName("  My   night\n theme  "));
    }

    /** Two themes may share a name. Identity has never come from it. */
    @Test public void duplicateNamesAreAllowedAndStillDistinct() {
        OrbitTheme first = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Night", "#8B7CFF", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        OrbitTheme second = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Night", "#5097FF", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first.id, second.id);
        assertEquals(2, OrbitThemeStore.customPresetCount(context));
    }

    @Test public void identityIsStableAndNotDerivedFromTheName() {
        OrbitTheme theme = OrbitTheme.custom("First", "#8B7CFF", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false);
        assertEquals("renaming keeps identity", theme.id, theme.withName("Second").id);
        assertNotEquals("duplicating does not", theme.id, theme.asCustomNamed("Copy").id);
        assertFalse(theme.id.contains("First"));
        assertNotEquals(OrbitTheme.newId(), OrbitTheme.newId());
    }

    // ---- serialisation ------------------------------------------------------------------------------

    @Test public void themesRoundTripThroughJson() throws Exception {
        OrbitTheme original = OrbitTheme.custom("Round trip", "#8B7CFF", "#3A2E63", "#1F1930",
                "#1A1626", "#0B0714", true);
        OrbitTheme restored = OrbitTheme.fromJson(original.toJson());
        assertNotNull(restored);
        assertEquals(original.id, restored.id);
        assertEquals(original.name, restored.name);
        assertTrue(original.sameColours(restored));
        assertEquals(OrbitTheme.SCHEMA, restored.schema);
    }

    /** Written now so a later Beta's import parser can reject somebody else's JSON. */
    @Test public void serialisedThemesCarryAFormatAndSchema() throws Exception {
        JSONObject json = OrbitTheme.orbitDefault().toJson();
        assertEquals("orbit.theme", json.getString("format"));
        assertEquals(OrbitTheme.SCHEMA, json.getInt("schema"));
        assertTrue(json.has("name"));
        assertTrue(json.has("accent"));
        assertFalse("no Android or preference internals may be serialised", json.has("prefKey"));
        assertFalse(json.has("viewId"));
    }

    @Test public void aThemeFromANewerSchemaIsRefusedRatherThanGuessedAt() throws Exception {
        JSONObject json = OrbitTheme.orbitDefault().toJson();
        json.put("schema", OrbitTheme.SCHEMA + 1);
        assertNull(OrbitTheme.fromJson(json));
        json.put("schema", 0);
        assertNull(OrbitTheme.fromJson(json));
        assertNull(OrbitTheme.fromJson(null));
    }

    @Test public void aCorruptStoredThemeDegradesToSomethingUsable() throws Exception {
        JSONObject json = new JSONObject();
        json.put("schema", OrbitTheme.SCHEMA);
        json.put("id", "t_broken");
        json.put("accent", "#12");
        json.put("surface", "not a colour");
        OrbitTheme theme = OrbitTheme.fromJson(json);
        assertNotNull(theme);
        assertEquals(OrbitTheme.DYNAMIC, theme.accent);
        assertEquals(OrbitTheme.CLASSIC, theme.surface);
        assertEquals("Custom theme", theme.name);
    }

    // ---- the saved library --------------------------------------------------------------------------

    @Test public void savingRenamingAndDeletingCustomPresets() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "#8B7CFF", "#3A2E63", OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        assertNotNull(saved);
        assertFalse(saved.builtIn);
        assertEquals(1, OrbitThemeStore.customPresetCount(context));

        assertTrue(OrbitThemeStore.renamePreset(context, saved.id, "Renamed"));
        assertEquals("Renamed", OrbitThemeStore.preset(context, saved.id).name);
        assertTrue("renaming must not change the colours",
                OrbitThemeStore.preset(context, saved.id).sameColours(saved));

        assertTrue(OrbitThemeStore.deletePreset(context, saved.id));
        assertNull(OrbitThemeStore.preset(context, saved.id));
        assertEquals(0, OrbitThemeStore.customPresetCount(context));
    }

    @Test public void savingTheSameIdTwiceUpdatesRatherThanDuplicates() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "#4A4A4A", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        OrbitThemeStore.savePreset(context, saved.withAccent("#5A5A5A"));
        assertEquals(1, OrbitThemeStore.customPresetCount(context));
        assertEquals("#5A5A5A", OrbitThemeStore.preset(context, saved.id).accent);
    }

    /**
     * A hex value that is one of Orbit's named colours is stored under its name.
     *
     * <p>Not cosmetic. {@link OrbitTheme#sameColours} compares tokens, so before this a theme built
     * by typing #8B7CFF and the Nebula preset that uses the identical colour compared as different
     * themes, the gallery showed nothing selected, and the editor described Orbit's own Violet as a
     * custom colour.
     */
    @Test public void aNamedColourIsStoredUnderItsName() {
        OrbitTheme theme = OrbitTheme.custom("Mine", "#8B7CFF", "#5097FF", "#45CCA6",
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false);
        assertEquals("violet", theme.accent);
        assertEquals("blue", theme.userBubble);
        assertEquals("mint", theme.assistantBubble);
        assertEquals("and it resolves to the colour that was asked for",
                Color.rgb(0x8B, 0x7C, 0xFF), OrbitThemeTokens.resolve(context, theme).accent);
    }

    /** A colour one channel away from a named one is a colour somebody chose. */
    @Test public void aNearMissKeepsItsHexValue() {
        assertEquals("#8B7CFE", OrbitTheme.custom("Mine", "#8B7CFE", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false).accent);
    }

    /** Surfaces have no palette names, so a card colour is always stored as it was given. */
    @Test public void surfacesAreNeverRenamedIntoPaletteKeys() {
        OrbitTheme theme = OrbitTheme.custom("Mine", OrbitTheme.DYNAMIC, OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, "#8B7CFF", "#5097FF", false);
        assertEquals("#8B7CFF", theme.surface);
        assertEquals("#5097FF", theme.background);
    }

    @Test public void duplicatingCopiesTheColoursAndLeavesTheOriginal() {
        OrbitTheme original = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Mine", "#8B7CFF", "#3A2E63", OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        OrbitTheme copy = OrbitThemeStore.duplicatePreset(context, original.id);
        assertNotNull(copy);
        assertNotEquals(original.id, copy.id);
        assertTrue(copy.sameColours(original));
        assertNotEquals("a copy needs its own name", original.name, copy.name);
        assertNotNull("the original must survive",
                OrbitThemeStore.preset(context, original.id));
        assertEquals(2, OrbitThemeStore.customPresetCount(context));

        assertTrue(OrbitThemeStore.deletePreset(context, copy.id));
        assertNotNull("deleting the copy must leave the original",
                OrbitThemeStore.preset(context, original.id));
    }

    /** Duplicating a shipped preset yields a theme the user owns, not a second immutable one. */
    @Test public void duplicatingABuiltInProducesAnEditableCopy() {
        OrbitTheme copy = OrbitThemeStore.duplicatePreset(context, OrbitTheme.ID_NEBULA);
        assertNotNull(copy);
        assertFalse(copy.builtIn);
        assertFalse(OrbitTheme.isBuiltInId(copy.id));
        assertTrue(copy.sameColours(OrbitTheme.builtIn(OrbitTheme.ID_NEBULA)));
        assertTrue(OrbitThemeStore.deletePreset(context, copy.id));
    }

    /** Saving from a built-in must never write a theme that claims a shipped preset's identity. */
    @Test public void savingABuiltInStoresAnOwnedCopyInstead() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context, OrbitTheme.orbitDefault());
        assertNotNull(saved);
        assertFalse(saved.builtIn);
        assertNotEquals(OrbitTheme.ID_DEFAULT, saved.id);
        for (OrbitTheme preset : OrbitThemeStore.customPresets(context)) {
            assertFalse(OrbitTheme.isBuiltInId(preset.id));
        }
    }

    @Test public void savedPresetsSurviveBeingReadBackFromDisk() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Persisted", "#45CCA6", "#1E4038", "#182722",
                        "#16211D", "#08110E", true));
        assertNotNull(saved);
        // Nothing is cached: every read goes back to the file, which is what a process restart is.
        OrbitTheme reread = OrbitThemeStore.preset(context, saved.id);
        assertNotNull(reread);
        assertEquals("Persisted", reread.name);
        assertTrue(reread.sameColours(saved));
        assertTrue(reread.amoled);
    }

    @Test public void theGalleryListsOrbitsPresetsFirst() {
        OrbitThemeStore.savePreset(context, OrbitTheme.custom("Mine", "#8B7CFF",
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, false));
        List<OrbitTheme> all = OrbitThemeStore.allPresets(context);
        assertEquals(OrbitTheme.builtIns().size() + 1, all.size());
        for (int i = 0; i < OrbitTheme.builtIns().size(); i++) {
            assertTrue(all.get(i).builtIn);
        }
        assertFalse(all.get(all.size() - 1).builtIn);
    }

    @Test public void deletingSomethingThatIsNotThereIsRefusedNotCrashed() {
        assertFalse(OrbitThemeStore.deletePreset(context, "t_nothing"));
        assertFalse(OrbitThemeStore.renamePreset(context, "t_nothing", "x"));
        assertNull(OrbitThemeStore.duplicatePreset(context, "t_nothing"));
        assertNull(OrbitThemeStore.preset(context, null));
    }
}

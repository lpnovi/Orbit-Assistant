package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.Set;

/**
 * Orbit has one palette.
 *
 * <p>The failure this file exists to prevent is not a crash. It is Orbit quietly acquiring a second
 * set of colours: Theme Studio shipped presets whose accents were raw hex values that happened to
 * equal Violet, Blue and Mint exactly, and because nothing knew they were the same, the editor
 * called Orbit's own colour "custom #8B7CFF" and the preset using it never showed as selected. The
 * values were identical the whole time. Only the names had drifted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitPaletteTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- one definition -----------------------------------------------------------------------

    @Test public void everyNamedColourHasAKeyALabelAndAValue() {
        Set<String> keys = new HashSet<>();
        Set<String> labels = new HashSet<>();
        Set<Integer> colours = new HashSet<>();
        for (OrbitPalette.Entry entry : OrbitPalette.entries()) {
            assertFalse(entry.key.trim().isEmpty());
            assertFalse(entry.label.trim().isEmpty());
            assertTrue("keys are unique", keys.add(entry.key));
            assertTrue("labels are unique", labels.add(entry.label));
            assertTrue(entry.label + " duplicates another colour's value",
                    colours.add(entry.color & 0x00FFFFFF));
            assertEquals(255, Color.alpha(entry.color));
        }
        assertTrue("Orbit ships a palette, not a colour chart",
                OrbitPalette.entries().size() >= 6 && OrbitPalette.entries().size() <= 12);
    }

    /**
     * The values Orbit has always shipped.
     *
     * <p>Written out rather than read from the palette, so this fails if one is ever moved by
     * accident. Every install that chose one of these keeps the colour it chose.
     */
    @Test public void theShippedValuesAreUnchanged() {
        assertEquals(Color.rgb(88, 101, 242), OrbitPalette.colorFor("blurple", 0));
        assertEquals(Color.rgb(139, 124, 255), OrbitPalette.colorFor("violet", 0));
        assertEquals(Color.rgb(80, 151, 255), OrbitPalette.colorFor("blue", 0));
        assertEquals(Color.rgb(69, 204, 166), OrbitPalette.colorFor("mint", 0));
        assertEquals(Color.rgb(244, 110, 150), OrbitPalette.colorFor("rose", 0));
        assertEquals(Color.rgb(76, 0, 255), OrbitPalette.colorFor("nova", 0));
        assertEquals(Color.rgb(255, 209, 220), OrbitPalette.colorFor("pastel_pink", 0));
        assertEquals(Color.rgb(203, 229, 242), OrbitPalette.colorFor("pastel_blue", 0));
    }

    /** Nova is one colour with one value, wherever it is asked for. */
    @Test public void novaResolvesIdenticallyEverywhere() {
        int nova = Color.rgb(76, 0, 255);
        assertEquals(nova, OrbitPalette.colorFor(OrbitPalette.NOVA, 0));
        assertEquals(nova, UiKit.accentForName(context, "nova"));
        assertEquals(nova, UiKit.bubbleFill(context, "nova", Color.BLACK));
        assertEquals("nova", OrbitPalette.keyForColor(nova));
        assertEquals(nova, OrbitThemeTokens.resolve(context,
                OrbitTheme.orbitDefault().withAccent("nova")).accent);
        assertEquals("and typing its hex value reaches the same name",
                "nova", OrbitTheme.orbitDefault().withAccent("#4C00FF").accent);
    }

    // ---- the catalogues are derived, not copied -----------------------------------------------

    @Test public void theAccentCatalogueIsDynamicPlusThePalette() {
        String[] keys = UiKit.accentKeys();
        String[] labels = UiKit.accentLabels();
        assertEquals(keys.length, labels.length);
        assertEquals(OrbitPalette.DYNAMIC, keys[0]);
        assertEquals(OrbitPalette.entries().size() + 1, keys.length);
        for (int i = 0; i < OrbitPalette.entries().size(); i++) {
            assertEquals(OrbitPalette.entries().get(i).key, keys[i + 1]);
            assertEquals(OrbitPalette.entries().get(i).label, labels[i + 1]);
        }
    }

    @Test public void theBubbleCatalogueIsClassicAndAccentPlusThePalette() {
        String[] keys = UiKit.bubbleColorKeys();
        assertEquals(OrbitPalette.CLASSIC, keys[0]);
        assertEquals(OrbitPalette.ACCENT, keys[1]);
        assertEquals(OrbitPalette.entries().size() + 2, keys.length);
        for (int i = 0; i < OrbitPalette.entries().size(); i++) {
            assertEquals(OrbitPalette.entries().get(i).key, keys[i + 2]);
        }
    }

    /** Every catalogue key resolves, and no key resolves to the fallback by accident. */
    @Test public void everyCatalogueKeyResolvesToItsOwnColour() {
        for (String key : UiKit.accentKeys()) {
            if (OrbitPalette.DYNAMIC.equals(key)) continue;
            assertEquals(key, OrbitPalette.colorFor(key, 0), UiKit.accentForName(context, key));
        }
    }

    // ---- naming --------------------------------------------------------------------------------

    @Test public void aTokenIsDescribedByTheNameItActuallyHas() {
        assertEquals("Nova", OrbitPalette.labelFor("nova"));
        assertEquals("Dynamic", OrbitPalette.labelFor(OrbitPalette.DYNAMIC));
        assertEquals("Classic", OrbitPalette.labelFor(OrbitPalette.CLASSIC));
        assertEquals("Accent", OrbitPalette.labelFor(OrbitPalette.ACCENT));
        assertEquals("custom #123456", OrbitPalette.labelFor("#123456"));
        assertEquals("a named colour is never called custom",
                "Violet", OrbitPalette.labelFor("#8B7CFF"));
    }

    @Test public void aHexValueIsRecognisedOnlyWhenItIsExact() {
        assertEquals("violet", OrbitPalette.keyForHexToken("#8B7CFF"));
        assertEquals("violet", OrbitPalette.keyForHexToken("#8b7cff"));
        assertNull(OrbitPalette.keyForHexToken("#8B7CFE"));
        assertNull(OrbitPalette.keyForHexToken("not a colour"));
        assertNull(OrbitPalette.keyForHexToken(null));
        assertNull("dynamic is not a colour and can never be named from one",
                OrbitPalette.keyForColor(Color.rgb(1, 2, 3)));
    }

    @Test public void writingAColourBackPrefersItsName() {
        assertEquals("nova", OrbitPalette.tokenFor(Color.rgb(76, 0, 255)));
        assertEquals("#010203", OrbitPalette.tokenFor(Color.rgb(1, 2, 3)));
    }

    // ---- the presets use it --------------------------------------------------------------------

    /**
     * No preset Orbit ships may write a palette colour as a raw hex value.
     *
     * <p>This is the regression itself. Nebula, Tide and Moss each did, which is what made Orbit
     * look like it had two palettes.
     */
    @Test public void noBuiltInPresetHidesAPaletteColourBehindAHexValue() {
        for (OrbitTheme preset : OrbitTheme.builtIns()) {
            for (String token : new String[]{
                    preset.accent, preset.userBubble, preset.assistantBubble}) {
                assertNull(preset.name + " writes a named Orbit colour as " + token,
                        OrbitPalette.keyForHexToken(token));
            }
        }
    }

    @Test public void thePresetsThatUseOrbitColoursNameThem() {
        assertEquals("violet", OrbitTheme.builtIn(OrbitTheme.ID_NEBULA).accent);
        assertEquals("blue", OrbitTheme.builtIn(OrbitTheme.ID_TIDE).accent);
        assertEquals("mint", OrbitTheme.builtIn(OrbitTheme.ID_MOSS).accent);
        assertEquals("blurple", OrbitTheme.builtIn(OrbitTheme.ID_BLURPLE).accent);
    }

    /** Renaming the token must not have moved any preset's colour. */
    @Test public void namingAPresetsAccentDidNotChangeIt() {
        assertEquals(Color.rgb(0x8B, 0x7C, 0xFF), OrbitThemeTokens.resolve(context,
                OrbitTheme.builtIn(OrbitTheme.ID_NEBULA)).accent);
        assertEquals(Color.rgb(0x50, 0x97, 0xFF), OrbitThemeTokens.resolve(context,
                OrbitTheme.builtIn(OrbitTheme.ID_TIDE)).accent);
        assertEquals(Color.rgb(0x45, 0xCC, 0xA6), OrbitThemeTokens.resolve(context,
                OrbitTheme.builtIn(OrbitTheme.ID_MOSS)).accent);
    }

    /** A theme built by hand from a preset's colours is that preset. */
    @Test public void aHandBuiltCopyOfAPresetCompareEqualToIt() {
        OrbitTheme nebula = OrbitTheme.builtIn(OrbitTheme.ID_NEBULA);
        assertNotNull(nebula);
        OrbitTheme byHand = OrbitTheme.custom("Mine", "#8B7CFF", nebula.userBubble,
                nebula.assistantBubble, nebula.surface, nebula.background, nebula.amoled);
        assertTrue("typing Violet's hex value must land on Nebula", byHand.sameColours(nebula));
    }
}

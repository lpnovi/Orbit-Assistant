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

/**
 * Nova AMOLED, the one preset Orbit ships with a note on it.
 *
 * <p>The design constraint that shaped it is worth stating, because it looks like a compromise and
 * is not. Nova at full strength is {@code #4C00FF}, whose relative luminance is low enough that it
 * cannot reach 3 to 1 against <em>any</em> dark surface — the arithmetic simply has no solution. So
 * a preset that used Nova verbatim on a black page would trip Orbit's own low-contrast warning the
 * moment it was selected, and Orbit would be shipping a theme it disapproves of. The accent here is
 * Nova's exact hue lifted until it reads, which is why the hue assertion below matters more than
 * the value one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class NovaAmoledPresetTest {

    private Context context;
    private OrbitTheme nova;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        nova = OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED);
        assertNotNull("Orbit must ship Nova AMOLED", nova);
    }

    private static float hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[0];
    }

    // ---- identity -------------------------------------------------------------------------------

    @Test public void itIsABuiltInThatCannotBeChanged() {
        assertTrue(nova.builtIn);
        assertEquals("Nova AMOLED", nova.name);
        assertTrue(OrbitTheme.isBuiltInId(OrbitTheme.ID_NOVA_AMOLED));
        assertFalse("a built-in is never deletable",
                OrbitThemeStore.deletePreset(context, OrbitTheme.ID_NOVA_AMOLED));
        assertFalse("nor renameable",
                OrbitThemeStore.renamePreset(context, OrbitTheme.ID_NOVA_AMOLED, "Mine"));
        assertNotNull(OrbitThemeStore.preset(context, OrbitTheme.ID_NOVA_AMOLED));
    }

    /** The note is metadata about the id, never part of it and never part of the name. */
    @Test public void theCreatorsFavoriteNoteBelongsToThisPresetOnly() {
        assertEquals("Creator's favorite", nova.note());
        assertEquals("Creator's favorite", OrbitTheme.noteFor(OrbitTheme.ID_NOVA_AMOLED));
        assertFalse("the note is not in the id", OrbitTheme.ID_NOVA_AMOLED.contains("favorite"));
        assertFalse("nor in the name", nova.name.toLowerCase(java.util.Locale.US).contains("favorite"));
        for (OrbitTheme other : OrbitTheme.builtIns()) {
            if (other.id.equals(OrbitTheme.ID_NOVA_AMOLED)) continue;
            assertEquals(other.name + " must carry no note", "", other.note());
        }
    }

    /** A custom theme has no note, and a duplicate of Nova AMOLED does not inherit one. */
    @Test public void aCustomThemeNeverCarriesTheNote() {
        assertEquals("", OrbitTheme.custom("Mine", "nova", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, true).note());
        OrbitTheme copy = OrbitThemeStore.duplicatePreset(context, OrbitTheme.ID_NOVA_AMOLED);
        assertNotNull(copy);
        assertFalse(copy.builtIn);
        assertEquals("", copy.note());
        assertTrue("but it is the same appearance", copy.sameColours(nova));
    }

    /** No emoji, no ornament, and short enough to sit on one line of a preset card. */
    @Test public void theNoteIsPlainRestrainedText() {
        String note = OrbitTheme.CREATOR_FAVORITE;
        assertTrue(note.length() <= 24);
        for (int i = 0; i < note.length(); i++) {
            char ch = note.charAt(i);
            assertTrue("the note must be plain ASCII: " + note, ch >= 0x20 && ch < 0x7F);
        }
        assertFalse("no em dash", note.contains("—"));
    }

    // ---- the appearance ------------------------------------------------------------------------

    @Test public void itIsNovaOnATrueBlackPage() {
        assertTrue("AMOLED must be on", nova.amoled);
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        assertEquals("the page is true black", Color.BLACK, tokens.background);
        assertEquals("the accent keeps Nova's hue exactly",
                hueOf(OrbitPalette.colorFor(OrbitPalette.NOVA, 0)), hueOf(tokens.accent), 1.5f);
    }

    /** Cards and bubbles are lifted off the black page rather than dissolving into it. */
    @Test public void everySurfaceIsSeparatedFromTheBlackPage() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        // Orbit's shipped AMOLED preset puts its cards this far off black. Nova AMOLED matches it
        // rather than flattening everything into one black plane, which is the failure mode the
        // whole preset exists to avoid.
        double minimum = 1.10d;
        assertTrue("cards vanish into the page",
                OrbitContrast.contrastRatio(tokens.surface, Color.BLACK) >= minimum);
        assertTrue("Orbit's reply bubble vanishes into the page",
                OrbitContrast.contrastRatio(tokens.assistantBubble, Color.BLACK) >= minimum);
        assertTrue("the card ramp has to keep stepping",
                OrbitContrast.contrastRatio(tokens.surface2, tokens.surface) > 1.0d);
        assertTrue("your bubble must not be Orbit's bubble",
                OrbitContrast.contrastRatio(tokens.userBubble, tokens.assistantBubble) >= 1.3d);
    }

    /** The bubbles are both recognisably in the Nova family without being the same colour. */
    @Test public void yourBubbleIsNovaAndOrbitsComplementsIt() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        float novaHue = hueOf(OrbitPalette.colorFor(OrbitPalette.NOVA, 0));
        assertEquals("your bubble reads as Nova", novaHue, hueOf(tokens.userBubble), 12f);
        assertFalse("the two bubbles must not be the same colour",
                tokens.userBubble == tokens.assistantBubble);
    }

    /** Orbit must not ship a preset that trips its own warning. */
    @Test public void itPassesEveryReadabilityCheckOrbitApplies() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        for (OrbitThemeTokens.Check check : tokens.readability()) {
            assertTrue("Nova AMOLED fails " + check.label + " at "
                    + String.format(java.util.Locale.US, "%.2f", check.ratio), check.passes);
        }
        assertFalse(tokens.hasLowContrast());
    }

    /** A link in one of Orbit's replies reads, and is still Nova. */
    @Test public void aLinkInAReplyIsReadableAndStillNova() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        assertTrue("the link must clear the non-text threshold",
                OrbitContrast.contrastRatio(tokens.link, tokens.assistantBubble)
                        >= OrbitContrast.LARGE_TEXT_MIN);
        assertEquals("and must not have drifted off Nova's hue",
                hueOf(tokens.accent), hueOf(tokens.link), 2f);
    }

    /** Markdown chrome derived from the reply surface stays legible on this theme. */
    @Test public void inlineCodeStaysLegibleOnTheReplyBubble() {
        OrbitThemeTokens tokens = OrbitThemeTokens.resolve(context, nova);
        int tint = UiKit.inlineCodeTint(tokens.assistantBubble);
        assertTrue("an inline code pill has to be visible against the bubble",
                OrbitContrast.contrastRatio(tint, tokens.assistantBubble) > 1.0d);
        assertTrue("and its text has to be readable inside it",
                OrbitContrast.contrastRatio(UiKit.inlineCodeInk(tokens.assistantBubble), tint)
                        >= OrbitContrast.BODY_TEXT_MIN);
    }

    // ---- it is only an option -------------------------------------------------------------------

    /**
     * The creator's favorite is not the default and is never chosen for anybody.
     *
     * <p>A fresh install gets Orbit Default, and an upgrading install keeps whatever it had. Being
     * somebody's favourite is a note on a card, not a migration.
     */
    @Test public void itIsNeverAppliedToAnybodyByDefault() {
        assertFalse("a fresh install is not Nova AMOLED",
                OrbitThemeStore.active(context).id.equals(OrbitTheme.ID_NOVA_AMOLED));
        assertEquals(OrbitTheme.ID_DEFAULT, OrbitThemeStore.active(context).id);

        // The closest legacy appearance there is: Nova plus AMOLED, chosen before Theme Studio.
        Prefs.get(context).edit()
                .putString(Prefs.ACCENT, "nova")
                .putBoolean(Prefs.AMOLED_MODE, true)
                .remove(Prefs.THEME_SCHEMA).remove(Prefs.THEME_ID).remove(Prefs.THEME_NAME)
                .commit();
        OrbitTheme active = OrbitThemeStore.active(context);
        assertFalse("an upgrading Nova user must not be moved onto the preset",
                OrbitTheme.ID_NOVA_AMOLED.equals(active.id));
        assertEquals("their own accent is untouched", "nova", active.accent);
        assertTrue("and so is their AMOLED setting", active.amoled);
    }

    /** Orbit's own default has no note, so the gallery is not a list of endorsements. */
    @Test public void exactlyOnePresetCarriesANote() {
        int noted = 0;
        for (OrbitTheme preset : OrbitTheme.builtIns()) if (!preset.note().isEmpty()) noted++;
        assertEquals(1, noted);
        assertNull(OrbitTheme.builtIn("no.such.preset"));
        assertEquals("", OrbitTheme.noteFor("no.such.preset"));
    }
}

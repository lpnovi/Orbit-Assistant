package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Color;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What happens to an appearance somebody already had when they update.
 *
 * <p>This is the test that matters most in the release. Theme Studio is optional; opening Orbit
 * after an update and finding your accent gone is not something a user can opt out of. The
 * guarantee is stronger than "close enough": because a theme is stored in the same preference keys
 * the appearance always lived in, the values after migration are the identical values, and the
 * resolved colours are identical too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ThemeMigrationTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
    }

    /** Exactly what an install from before this release looks like: four keys, no schema. */
    private void installLegacyAppearance(String accent, boolean amoled,
                                         String userBubble, String assistantBubble) {
        Prefs.get(context).edit()
                .putString(Prefs.ACCENT, accent)
                .putBoolean(Prefs.AMOLED_MODE, amoled)
                .putString(Prefs.USER_BUBBLE_COLOR, userBubble)
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, assistantBubble)
                .remove(Prefs.THEME_SCHEMA)
                .remove(Prefs.THEME_ID)
                .remove(Prefs.THEME_NAME)
                .remove(Prefs.THEME_SURFACE)
                .remove(Prefs.THEME_BACKGROUND)
                .commit();
    }

    // ---- the appearance survives -------------------------------------------------------------------

    @Test public void anExistingAppearanceIsCarriedOverExactly() {
        installLegacyAppearance("mint", true, "rose", "nova");

        OrbitTheme theme = OrbitThemeStore.active(context);

        assertEquals("mint", theme.accent);
        assertTrue(theme.amoled);
        assertEquals("rose", theme.userBubble);
        assertEquals("nova", theme.assistantBubble);
        assertEquals("surface and background did not exist before, so they are Orbit's own",
                OrbitTheme.CLASSIC, theme.surface);
        assertEquals(OrbitTheme.CLASSIC, theme.background);
    }

    /**
     * Not "close": the same. The colours resolved after migration are the same ints the previous
     * release would have drawn from the same preferences.
     */
    @Test public void theResolvedColoursAreUnchangedByMigration() {
        installLegacyAppearance("blurple", false, "accent", "classic");
        int expectedAccent = UiKit.accentForName(context, "blurple");
        int expectedUserBubble = expectedAccent;
        int expectedAssistantBubble = UiKit.classicSurface();

        OrbitThemeTokens tokens =
                OrbitThemeTokens.resolve(context, OrbitThemeStore.active(context));

        assertEquals(expectedAccent, tokens.accent);
        assertEquals(expectedUserBubble, tokens.userBubble);
        assertEquals(expectedAssistantBubble, tokens.assistantBubble);
        assertEquals(UiKit.classicBackground(), tokens.background);
        assertEquals(UiKit.classicSurface(), tokens.surface);
    }

    @Test public void migrationNeverTouchesTheAppearanceValuesThemselves() {
        installLegacyAppearance("pastel_pink", true, "pastel_blue", "violet");
        SharedPreferences p = Prefs.get(context);

        OrbitThemeStore.active(context);

        assertEquals("pastel_pink", p.getString(Prefs.ACCENT, ""));
        assertTrue(p.getBoolean(Prefs.AMOLED_MODE, false));
        assertEquals("pastel_blue", p.getString(Prefs.USER_BUBBLE_COLOR, ""));
        assertEquals("violet", p.getString(Prefs.ASSISTANT_BUBBLE_COLOR, ""));
    }

    /** A fresh install has nothing to migrate and must land on Orbit's own appearance. */
    @Test public void aFreshInstallGetsOrbitDefault() {
        OrbitTheme theme = OrbitThemeStore.active(context);
        assertEquals(OrbitTheme.ID_DEFAULT, theme.id);
        assertEquals("Orbit Default", theme.name);
        assertTrue(theme.builtIn);
        assertTrue(theme.sameColours(OrbitTheme.orbitDefault()));
    }

    // ---- what it records ---------------------------------------------------------------------------

    @Test public void migrationStampsTheSchema() {
        installLegacyAppearance("mint", false, "classic", "classic");
        assertEquals(0, Prefs.get(context).getInt(Prefs.THEME_SCHEMA, 0));

        OrbitThemeStore.active(context);

        assertEquals(OrbitTheme.SCHEMA, Prefs.get(context).getInt(Prefs.THEME_SCHEMA, 0));
        assertTrue(Prefs.get(context).contains(Prefs.THEME_SURFACE));
        assertTrue(Prefs.get(context).contains(Prefs.THEME_BACKGROUND));
    }

    /**
     * An appearance that happens to be one of Orbit's presets is labelled as that preset, so the
     * gallery opens with the user's theme selected rather than appearing to have lost it.
     */
    @Test public void anAppearanceThatMatchesAPresetIsRecognisedAsOne() {
        installLegacyAppearance(OrbitTheme.DYNAMIC, true, "classic", "classic");
        OrbitTheme theme = OrbitThemeStore.active(context);
        assertEquals(OrbitTheme.ID_AMOLED, theme.id);
        assertEquals("Orbit AMOLED", theme.name);
        assertTrue(theme.builtIn);
    }

    @Test public void anAppearanceThatMatchesNoPresetIsRecordedAsTheirOwn() {
        installLegacyAppearance("mint", false, "rose", "nova");
        OrbitTheme theme = OrbitThemeStore.active(context);
        assertEquals(Prefs.THEME_ID_CUSTOM, theme.id);
        assertFalse(theme.builtIn);
        assertFalse("it still needs a showable name", theme.name.trim().isEmpty());
    }

    // ---- idempotency -------------------------------------------------------------------------------

    @Test public void migrationIsIdempotent() {
        installLegacyAppearance("rose", true, "mint", "blue");

        OrbitTheme first = OrbitThemeStore.active(context);
        for (int i = 0; i < 5; i++) {
            OrbitTheme again = OrbitThemeStore.active(context);
            assertEquals(first.id, again.id);
            assertEquals(first.name, again.name);
            assertTrue("run " + i + " changed the appearance", first.sameColours(again));
        }
    }

    /**
     * The failure this guards against: a migration that re-runs on every launch and reverts a
     * theme applied after the update. Once the schema is stamped, migration must be inert.
     */
    @Test public void migrationNeverOverwritesAThemeAppliedAfterTheUpgrade() {
        installLegacyAppearance("mint", false, "classic", "classic");
        OrbitThemeStore.active(context);

        OrbitTheme later = OrbitTheme.custom("Later", "#FF8A5B", "#4A2A1F", "#2A1D1A",
                "#241A18", "#120B0A", false);
        OrbitThemeStore.applyActive(context, later);

        for (int i = 0; i < 3; i++) {
            OrbitTheme active = OrbitThemeStore.active(context);
            assertTrue("migration reverted a later theme on read " + i,
                    active.sameColours(later));
            assertEquals("Later", active.name);
        }
    }

    /**
     * A preference backup restored from before this release brings the four legacy keys back and
     * removes the schema stamp, so migration runs again — and must respect whatever surface and
     * background are actually stored rather than assuming they are classic.
     */
    @Test public void aRestoredBackupIsMigratedWithoutFlatteningACustomSurface() {
        Prefs.get(context).edit()
                .putString(Prefs.ACCENT, "#8B7CFF")
                .putString(Prefs.USER_BUBBLE_COLOR, "#3A2E63")
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, "#1F1930")
                .putString(Prefs.THEME_SURFACE, "#1A1626")
                .putString(Prefs.THEME_BACKGROUND, "#0B0714")
                .putBoolean(Prefs.AMOLED_MODE, false)
                .remove(Prefs.THEME_SCHEMA)
                .commit();

        OrbitTheme theme = OrbitThemeStore.active(context);

        assertEquals("#1A1626", theme.surface);
        assertEquals("#0B0714", theme.background);
        assertEquals("and it is recognised as the preset it actually is",
                OrbitTheme.ID_NEBULA, theme.id);
    }

    // ---- upgrading from Beta 1 -------------------------------------------------------------------

    /** Exactly what a Theme Studio Beta 1 install looks like: the schema it stamped, and its keys. */
    private void installBetaOneAppearance(String id, String name, String accent, String userBubble,
                                          String assistantBubble, String surface,
                                          String background, boolean amoled) {
        Prefs.get(context).edit()
                .putInt(Prefs.THEME_SCHEMA, 1)
                .putString(Prefs.THEME_ID, id)
                .putString(Prefs.THEME_NAME, name)
                .putString(Prefs.ACCENT, accent)
                .putString(Prefs.USER_BUBBLE_COLOR, userBubble)
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, assistantBubble)
                .putString(Prefs.THEME_SURFACE, surface)
                .putString(Prefs.THEME_BACKGROUND, background)
                .putBoolean(Prefs.AMOLED_MODE, amoled)
                .commit();
    }

    /**
     * The failure this guards against, and the reason migration became stepwise.
     *
     * <p>The schema stamp moved from 1 to 2, so migration runs again on every Beta 1 install. The
     * step that establishes the model decides what the appearance should be <em>called</em>, and if
     * it ran a second time it would rename a theme the user created and saved back to "Your theme"
     * and unlink it from their preset. Their colours would survive and everything else about their
     * theme would not.
     */
    @Test public void upgradingFromBetaOneKeepsAThemeTheUserCreated() {
        installBetaOneAppearance("t_abc123", "Midnight", "#FF8A5B", "#4A2A1F", "#2A1D1A",
                "#241A18", "#120B0A", false);

        OrbitTheme theme = OrbitThemeStore.active(context);

        assertEquals("t_abc123", theme.id);
        assertEquals("Midnight", theme.name);
        assertFalse(theme.builtIn);
        assertEquals("#FF8A5B", theme.accent);
        assertEquals("#4A2A1F", theme.userBubble);
        assertEquals("#2A1D1A", theme.assistantBubble);
        assertEquals("#241A18", theme.surface);
        assertEquals("#120B0A", theme.background);
        assertFalse(theme.amoled);
    }

    /**
     * A Beta 1 install using a preset gets its accent's name back, and keeps the preset.
     *
     * <p>Beta 1 stored Nebula's accent as {@code #8B7CFF}, which is Violet exactly. Schema 2 writes
     * the name over the hex value. The colour does not move; what changes is that Theme Studio can
     * now say "Violet" and show Nebula as selected instead of describing Orbit's own colour as
     * custom and selecting nothing.
     */
    @Test public void upgradingFromBetaOneGivesAPaletteColourItsNameBack() {
        installBetaOneAppearance(OrbitTheme.ID_NEBULA, "Nebula", "#8B7CFF", "#3A2E63", "#1F1930",
                "#1A1626", "#0B0714", false);

        OrbitTheme theme = OrbitThemeStore.active(context);

        assertEquals("violet", theme.accent);
        assertEquals("the stored preference is rewritten, not only the read",
                "violet", Prefs.get(context).getString(Prefs.ACCENT, ""));
        assertEquals(OrbitTheme.ID_NEBULA, theme.id);
        assertTrue("and it is once again recognisably the preset it names",
                theme.sameColours(OrbitTheme.builtIn(OrbitTheme.ID_NEBULA)));
        assertEquals("the colour itself must not have moved",
                Color.rgb(0x8B, 0x7C, 0xFF), UiKit.accent(context));
    }

    /** A hand-picked colour that is not one of Orbit's stays exactly the value that was picked. */
    @Test public void upgradingFromBetaOneLeavesACustomColourAlone() {
        installBetaOneAppearance(Prefs.THEME_ID_CUSTOM, "Your theme", "#8B7CFE", "#123456",
                "#654321", "#111213", "#020304", true);

        OrbitTheme theme = OrbitThemeStore.active(context);

        assertEquals("#8B7CFE", theme.accent);
        assertEquals("#123456", theme.userBubble);
        assertEquals("#654321", theme.assistantBubble);
        assertEquals("#111213", theme.surface);
        assertEquals("#020304", theme.background);
        assertTrue(theme.amoled);
    }

    /** Dynamic is a behaviour, and no migration may turn it into a fixed colour. */
    @Test public void dynamicSurvivesEveryMigration() {
        installBetaOneAppearance(OrbitTheme.ID_DEFAULT, "Orbit Default", OrbitTheme.DYNAMIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                false);

        assertEquals(OrbitTheme.DYNAMIC, OrbitThemeStore.active(context).accent);
        assertEquals(OrbitTheme.DYNAMIC, Prefs.get(context).getString(Prefs.ACCENT, ""));
    }

    /** Running the whole thing repeatedly settles after the first pass and never moves again. */
    @Test public void theBetaOneUpgradeIsIdempotent() {
        installBetaOneAppearance(OrbitTheme.ID_TIDE, "Tide", "#5097FF", "#1E3A5C", "#16202E",
                "#141C28", "#070B12", false);

        OrbitTheme first = OrbitThemeStore.active(context);
        for (int i = 0; i < 5; i++) {
            OrbitTheme again = OrbitThemeStore.active(context);
            assertEquals("run " + i + " renamed the theme", first.name, again.name);
            assertEquals("run " + i + " relinked the theme", first.id, again.id);
            assertTrue("run " + i + " changed the appearance", first.sameColours(again));
        }
        assertEquals(OrbitTheme.SCHEMA, Prefs.get(context).getInt(Prefs.THEME_SCHEMA, 0));
    }

    /** A theme applied after the upgrade is never reverted by a later launch. */
    @Test public void aThemeAppliedAfterTheBetaOneUpgradeSurvives() {
        installBetaOneAppearance(OrbitTheme.ID_NEBULA, "Nebula", "#8B7CFF", "#3A2E63", "#1F1930",
                "#1A1626", "#0B0714", false);
        OrbitThemeStore.active(context);

        OrbitTheme chosen = OrbitTheme.builtIn(OrbitTheme.ID_NOVA_AMOLED);
        OrbitThemeStore.applyActive(context, chosen);
        for (int i = 0; i < 3; i++) {
            OrbitTheme active = OrbitThemeStore.active(context);
            assertTrue("read " + i + " reverted the applied theme", active.sameColours(chosen));
            assertEquals("Nova AMOLED", active.name);
        }
    }

    /** A Beta 1 saved preset still loads, still renames, still duplicates and still deletes. */
    @Test public void betaOneCustomPresetsSurviveTheUpgrade() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Midnight", "#FF8A5B", "#4A2A1F", "#2A1D1A",
                        "#241A18", "#120B0A", false));
        assertNotNull(saved);
        installBetaOneAppearance(saved.id, saved.name, saved.accent, saved.userBubble,
                saved.assistantBubble, saved.surface, saved.background, saved.amoled);

        OrbitThemeStore.active(context);

        OrbitTheme reloaded = OrbitThemeStore.preset(context, saved.id);
        assertNotNull("a Beta 1 saved theme must still be there", reloaded);
        assertEquals(saved.id, reloaded.id);
        assertEquals("Midnight", reloaded.name);
        assertTrue(reloaded.sameColours(saved));

        assertTrue(OrbitThemeStore.renamePreset(context, saved.id, "Midnight II"));
        assertEquals("Midnight II", OrbitThemeStore.preset(context, saved.id).name);
        assertNotNull(OrbitThemeStore.duplicatePreset(context, saved.id));
        assertEquals(2, OrbitThemeStore.customPresetCount(context));
        assertTrue(OrbitThemeStore.deletePreset(context, saved.id));
        assertEquals(1, OrbitThemeStore.customPresetCount(context));
    }

    /** A theme file written by Beta 1, at schema 1, is still read rather than discarded. */
    @Test public void aBetaOneThemeFileIsStillReadable() throws Exception {
        org.json.JSONObject theme = new org.json.JSONObject();
        theme.put("format", OrbitTheme.FORMAT);
        theme.put("schema", 1);
        theme.put("id", "t_betaone");
        theme.put("name", "From Beta 1");
        theme.put("builtIn", false);
        theme.put("accent", "#8B7CFF");
        theme.put("userBubble", "#3A2E63");
        theme.put("assistantBubble", OrbitTheme.CLASSIC);
        theme.put("surface", OrbitTheme.CLASSIC);
        theme.put("background", OrbitTheme.CLASSIC);
        theme.put("amoled", false);

        OrbitTheme parsed = OrbitTheme.fromJson(theme);
        assertNotNull("a schema 1 theme must still parse", parsed);
        assertEquals("t_betaone", parsed.id);
        assertEquals("From Beta 1", parsed.name);
        assertEquals("and its accent is recognised as Orbit's own", "violet", parsed.accent);
        assertEquals("#3A2E63", parsed.userBubble);
    }

    /** Deleting a preset is permanent: no migration puts a removed theme back. */
    @Test public void migrationNeverRecreatesADeletedPreset() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Gone", "#123456", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false));
        assertNotNull(saved);
        assertTrue(OrbitThemeStore.deletePreset(context, saved.id));

        Prefs.get(context).edit().remove(Prefs.THEME_SCHEMA).commit();
        for (int i = 0; i < 3; i++) OrbitThemeStore.active(context);

        assertEquals(0, OrbitThemeStore.customPresetCount(context));
        assertNull(OrbitThemeStore.preset(context, saved.id));
    }

    // ---- applying ------------------------------------------------------------------------------------

    @Test public void applyingAThemeWritesTheAppearanceKeysTheRestOfOrbitReads() {
        OrbitTheme theme = OrbitTheme.custom("Applied", "mint", "rose", "#1F1930",
                "#16211D", "#08110E", true);

        OrbitThemeStore.applyActive(context, theme);

        SharedPreferences p = Prefs.get(context);
        assertEquals("mint", p.getString(Prefs.ACCENT, ""));
        assertEquals("rose", p.getString(Prefs.USER_BUBBLE_COLOR, ""));
        assertEquals("#1F1930", p.getString(Prefs.ASSISTANT_BUBBLE_COLOR, ""));
        assertEquals("#16211D", p.getString(Prefs.THEME_SURFACE, ""));
        assertEquals("#08110E", p.getString(Prefs.THEME_BACKGROUND, ""));
        assertTrue(p.getBoolean(Prefs.AMOLED_MODE, false));

        assertEquals("the legacy readers must see the same accent",
                UiKit.accentForName(context, "mint"), UiKit.accent(context));
        assertEquals("rose", Prefs.userBubbleColor(context));
        assertTrue(Prefs.amoledMode(context));
    }

    /** Applying a theme must reach the canvas immediately, not on the next launch. */
    @Test public void applyingAThemeMovesTheLiveCanvas() {
        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
        int before = UiKit.SURFACE;

        OrbitThemeStore.applyActive(context,
                OrbitTheme.builtIn(OrbitTheme.ID_EMBER));

        assertNotNull(OrbitTheme.builtIn(OrbitTheme.ID_EMBER));
        assertFalse("the card colour must have moved", before == UiKit.SURFACE);
        assertEquals(OrbitTheme.hexTokenColor("#241A18"), UiKit.SURFACE);

        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
        assertEquals("and back again", before, UiKit.SURFACE);
    }

    /** Surface and background are baked into built views, so they must force a rebuild. */
    @Test public void surfaceAndBackgroundAreStructuralAppearance() {
        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
        String baseline = UiKit.structuralAppearanceSignature(context);

        OrbitThemeStore.applyActive(context,
                OrbitTheme.orbitDefault().withSurface("#1A1626"));
        String afterSurface = UiKit.structuralAppearanceSignature(context);
        assertFalse(baseline.equals(afterSurface));

        OrbitThemeStore.applyActive(context,
                OrbitTheme.orbitDefault().withSurface("#1A1626").withBackground("#0B0714"));
        assertFalse(afterSurface.equals(UiKit.structuralAppearanceSignature(context)));

        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
    }

    /** The appearance backup must carry a theme, or restoring a phone loses it. */
    @Test public void theNewThemeKeysAreIncludedInTheAppearanceBackup() throws Exception {
        OrbitThemeStore.applyActive(context,
                OrbitTheme.custom("Backed up", "#8B7CFF", "#3A2E63", "#1F1930",
                        "#1A1626", "#0B0714", false));

        org.json.JSONObject snapshot = Prefs.backupSnapshot(context);

        assertEquals("#1A1626", snapshot.optString(Prefs.THEME_SURFACE, ""));
        assertEquals("#0B0714", snapshot.optString(Prefs.THEME_BACKGROUND, ""));
        assertEquals(OrbitTheme.SCHEMA, snapshot.optInt(Prefs.THEME_SCHEMA, 0));
        assertTrue("and the snapshot must still validate",
                Prefs.validBackupSnapshot(snapshot));

        OrbitThemeStore.applyActive(context, OrbitTheme.orbitDefault());
    }
}

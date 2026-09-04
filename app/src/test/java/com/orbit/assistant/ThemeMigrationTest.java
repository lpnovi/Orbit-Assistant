package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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

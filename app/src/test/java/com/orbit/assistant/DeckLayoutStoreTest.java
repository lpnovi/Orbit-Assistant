package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The user's Deck, and the two things it must never do: rearrange itself, or lose itself.
 *
 * <p>Most of what is asserted here is survival. A layout is written by one version of Orbit and
 * read by another, so the interesting cases are the damaged and the unfamiliar ones: JSON that is
 * not JSON, a tile type this build has never heard of, a size a definition no longer offers, two
 * tiles claiming the same instance id. In every one of those the requirement is the same — do not
 * throw, and do not silently discard something the user put there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckLayoutStoreTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
    }

    private static List<String> idsOf(List<DeckTile> tiles) {
        List<String> out = new ArrayList<>();
        for (DeckTile tile : tiles) out.add(tile.instanceId);
        return out;
    }

    private static List<String> typesOf(List<DeckTile> tiles) {
        List<String> out = new ArrayList<>();
        for (DeckTile tile : tiles) out.add(tile.type);
        return out;
    }

    // ---- defaults ---------------------------------------------------------------------------------

    @Test public void aFreshDeckIsTheDefaultDeck() {
        assertFalse("nothing is stored until the user changes something",
                DeckLayoutStore.configured(context));
        assertEquals(typesOf(DeckLayoutStore.defaults()),
                typesOf(DeckLayoutStore.layout(context)));
    }

    @Test public void theDefaultDeckIsSevenUsefulTilesLedByANewChat() {
        List<DeckTile> defaults = DeckLayoutStore.defaults();
        assertEquals(7, defaults.size());
        assertEquals(DeckTileRegistry.TYPE_NEW_CHAT, defaults.get(0).type);
        assertEquals("New chat leads and is the one wide tile",
                DeckTile.Size.WIDE, defaults.get(0).size);
        for (DeckTile tile : defaults) {
            assertNotNull(tile.type + " must be a registered kind",
                    DeckTileRegistry.definition(tile.type));
        }
    }

    /** One wide plus six standard fills a two-column grid exactly, with no ragged final row. */
    @Test public void theDefaultDeckFillsATwoColumnGridExactly() {
        int columns = 0;
        for (DeckTile tile : DeckLayoutStore.defaults()) {
            columns += tile.size == DeckTile.Size.WIDE ? 2 : 1;
        }
        assertEquals(0, columns % 2);
    }

    @Test public void everyDefaultTileHasItsOwnInstanceId() {
        Set<String> ids = new HashSet<>(idsOf(DeckLayoutStore.defaults()));
        assertEquals(DeckLayoutStore.defaults().size(), ids.size());
    }

    // ---- mutations persist ------------------------------------------------------------------------

    @Test public void addingATilePersists() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Explain this:");
        assertTrue(DeckLayoutStore.add(context, tile));

        assertTrue(DeckLayoutStore.configured(context));
        List<DeckTile> after = DeckLayoutStore.layout(context);
        assertEquals(DeckLayoutStore.defaults().size() + 1, after.size());
        assertEquals("Explain this:",
                after.get(after.size() - 1).config(DeckTile.CONFIG_PROMPT));
    }

    @Test public void removingATilePersists() {
        List<DeckTile> before = DeckLayoutStore.layout(context);
        String removed = before.get(2).instanceId;

        assertTrue(DeckLayoutStore.remove(context, removed));
        assertFalse(idsOf(DeckLayoutStore.layout(context)).contains(removed));
        assertEquals(before.size() - 1, DeckLayoutStore.layout(context).size());
    }

    @Test public void reorderingPersistsAndKeepsEveryTile() {
        List<DeckTile> before = DeckLayoutStore.layout(context);
        List<String> reversed = new ArrayList<>(idsOf(before));
        java.util.Collections.reverse(reversed);

        assertTrue(DeckLayoutStore.applyOrder(context, reversed));
        assertEquals(reversed, idsOf(DeckLayoutStore.layout(context)));
    }

    /** A stale order that has lost an id must never delete the tile it forgot. */
    @Test public void reorderingFromAStaleListDropsNothing() {
        List<DeckTile> before = DeckLayoutStore.layout(context);
        List<String> partial = new ArrayList<>(idsOf(before).subList(0, 3));
        partial.add("an-id-that-does-not-exist");

        assertTrue(DeckLayoutStore.applyOrder(context, partial));
        List<DeckTile> after = DeckLayoutStore.layout(context);
        assertEquals("every tile survives", before.size(), after.size());
        assertTrue("the named ones come first",
                idsOf(after).subList(0, 3).equals(partial.subList(0, 3)));
    }

    @Test public void resizingPersists() {
        DeckTile tile = DeckLayoutStore.layout(context).get(1);
        assertEquals(DeckTile.Size.STANDARD, tile.size);

        assertTrue(DeckLayoutStore.resize(context, tile.instanceId, DeckTile.Size.WIDE));
        assertEquals(DeckTile.Size.WIDE, DeckLayoutStore.layout(context).get(1).size);
    }

    /** App tiles are standard only, so the store refuses the size rather than storing it. */
    @Test public void aSizeADefinitionDoesNotOfferIsRefused() {
        DeckTile app = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, "com.example.app");
        DeckLayoutStore.add(context, app);

        assertFalse("an app tile cannot be made wide",
                DeckLayoutStore.resize(context, app.instanceId, DeckTile.Size.WIDE));
        for (DeckTile tile : DeckLayoutStore.layout(context)) {
            if (DeckTileRegistry.TYPE_APP.equals(tile.type)) {
                assertEquals(DeckTile.Size.STANDARD, tile.size);
            }
        }
    }

    @Test public void configurationPersists() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD);
        DeckLayoutStore.add(context, tile);

        assertTrue(DeckLayoutStore.configure(context, tile.instanceId,
                DeckTile.CONFIG_PROMPT, "Plan my day"));
        assertTrue(DeckLayoutStore.configure(context, tile.instanceId,
                DeckTile.CONFIG_TITLE, "Plan"));

        DeckTile stored = find(tile.instanceId);
        assertNotNull(stored);
        assertEquals("Plan my day", stored.config(DeckTile.CONFIG_PROMPT));
        assertEquals("Plan", stored.config(DeckTile.CONFIG_TITLE));
    }

    /** Removing a tile takes its configuration with it rather than orphaning it. */
    @Test public void removingATileRemovesItsConfiguration() {
        DeckTile tile = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "a secret prompt");
        DeckLayoutStore.add(context, tile);
        DeckLayoutStore.remove(context, tile.instanceId);

        for (DeckTile stored : DeckLayoutStore.layout(context)) {
            assertNotEquals("a secret prompt", stored.config(DeckTile.CONFIG_PROMPT));
        }
    }

    @Test public void resetRestoresTheDefaults() {
        DeckLayoutStore.save(context, new ArrayList<>());
        assertTrue(DeckLayoutStore.layout(context).isEmpty());

        assertTrue(DeckLayoutStore.reset(context));
        assertEquals(typesOf(DeckLayoutStore.defaults()), typesOf(DeckLayoutStore.layout(context)));
    }

    /**
     * An emptied Deck stays empty.
     *
     * <p>The whole reason "configured" exists. Falling back to the defaults whenever there are no
     * tiles would mean the user could never actually clear their Deck.
     */
    @Test public void anIntentionallyEmptyDeckIsNotRefilled() {
        assertTrue(DeckLayoutStore.save(context, new ArrayList<>()));
        assertTrue(DeckLayoutStore.configured(context));
        assertTrue(DeckLayoutStore.layout(context).isEmpty());
        assertTrue("and still empty on the next read", DeckLayoutStore.layout(context).isEmpty());
    }

    // ---- surviving bad and unfamiliar data ---------------------------------------------------------

    @Test public void malformedJsonFallsBackToTheDefaultsWithoutThrowing() {
        for (String rubbish : new String[]{"", "   ", "not json", "{", "[]", "{\"tiles\":5}",
                "{\"version\":1}", "null"}) {
            DeckLayoutStore.writeRawForTest(context, rubbish);
            List<DeckTile> layout = DeckLayoutStore.layout(context);
            assertNotNull(rubbish + " must not throw", layout);
            assertEquals(rubbish + " must fall back to the defaults",
                    typesOf(DeckLayoutStore.defaults()), typesOf(layout));
        }
    }

    /** A future Orbit's tile type is kept, not deleted, so a downgrade cannot destroy a layout. */
    @Test public void anUnknownTileTypeSurvivesARoundTrip() throws Exception {
        JSONObject unknown = new JSONObject();
        unknown.put("id", "future-1");
        unknown.put("type", "orbit.something_new");
        unknown.put("size", "wide");
        unknown.put("config", new JSONObject().put("someFutureKey", "value"));

        JSONArray tiles = new JSONArray().put(unknown);
        DeckLayoutStore.writeRawForTest(context,
                new JSONObject().put("version", 99).put("tiles", tiles).toString());

        List<DeckTile> layout = DeckLayoutStore.layout(context);
        assertEquals(1, layout.size());
        assertEquals("orbit.something_new", layout.get(0).type);
        assertEquals("its unknown configuration is preserved",
                "value", layout.get(0).config("someFutureKey"));

        // And writing it back out again must not have dropped anything.
        DeckLayoutStore.save(context, layout);
        assertEquals("value", DeckLayoutStore.layout(context).get(0).config("someFutureKey"));
    }

    @Test public void anUnknownTileTypeResolvesAsUnavailableRatherThanCrashing() throws Exception {
        DeckLayoutStore.writeRawForTest(context, new JSONObject()
                .put("version", 1)
                .put("tiles", new JSONArray().put(new JSONObject()
                        .put("id", "x").put("type", "orbit.not_a_thing").put("size", "standard")))
                .toString());

        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, tile);
        assertEquals(DeckTile.Availability.UNRESOLVED, resolved.availability);
        assertFalse(resolved.title.isEmpty());
    }

    /** Two tiles cannot share an id, or every operation addressing one would hit both. */
    @Test public void duplicateInstanceIdsAreRepairedDeterministically() throws Exception {
        JSONArray tiles = new JSONArray();
        for (int i = 0; i < 2; i++) {
            tiles.put(new JSONObject()
                    .put("id", "same-id")
                    .put("type", DeckTileRegistry.TYPE_ROUTINES)
                    .put("size", "standard")
                    .put("config", new JSONObject()));
        }
        DeckLayoutStore.writeRawForTest(context,
                new JSONObject().put("version", 1).put("tiles", tiles).toString());

        List<DeckTile> layout = DeckLayoutStore.layout(context);
        assertEquals("neither tile is dropped", 2, layout.size());
        assertNotEquals(layout.get(0).instanceId, layout.get(1).instanceId);
        assertEquals("the first keeps the original id", "same-id", layout.get(0).instanceId);
    }

    /** A size the definition no longer supports is coerced back into range on read. */
    @Test public void anUnsupportedStoredSizeIsCoercedOnRead() throws Exception {
        DeckLayoutStore.writeRawForTest(context, new JSONObject()
                .put("version", 1)
                .put("tiles", new JSONArray().put(new JSONObject()
                        .put("id", "a")
                        .put("type", DeckTileRegistry.TYPE_APP)
                        .put("size", "wide")
                        .put("config", new JSONObject().put(DeckTile.CONFIG_PACKAGE, "com.x"))))
                .toString());

        assertEquals(DeckTile.Size.STANDARD, DeckLayoutStore.layout(context).get(0).size);
    }

    @Test public void theSchemaVersionIsWritten() throws Exception {
        DeckLayoutStore.add(context, DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD));
        String raw = context.getSharedPreferences("orbit_deck", Context.MODE_PRIVATE)
                .getString("deck_layout", "");
        assertEquals(DeckLayoutStore.SCHEMA_VERSION, new JSONObject(raw).optInt("version", -1));
    }

    @Test public void aDeckCannotGrowWithoutBound() {
        List<DeckTile> many = new ArrayList<>();
        for (int i = 0; i < DeckLayoutStore.MAX_TILES + 10; i++) {
            many.add(DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                    .withConfig(DeckTile.CONFIG_PROMPT, "p" + i));
        }
        DeckLayoutStore.save(context, many);
        assertEquals(DeckLayoutStore.MAX_TILES, DeckLayoutStore.layout(context).size());
        assertFalse("and a further add is refused rather than silently dropped",
                DeckLayoutStore.add(context, DeckTile.of(DeckTileRegistry.TYPE_SETTINGS,
                        DeckTile.Size.STANDARD)));
    }

    // ---- duplicates -------------------------------------------------------------------------------

    @Test public void aSingletonDestinationMayAppearOnlyOnce() {
        assertTrue("Routines is already in the defaults",
                DeckLayoutStore.contains(context, DeckTileRegistry.TYPE_ROUTINES));
        assertTrue(DeckLayoutStore.wouldDuplicate(context,
                DeckTile.of(DeckTileRegistry.TYPE_ROUTINES, DeckTile.Size.STANDARD)));
    }

    @Test public void configuredTilesMayRepeatWithDifferentConfiguration() {
        DeckTile first = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Explain this:");
        DeckLayoutStore.add(context, first);

        DeckTile different = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Plan my day");
        assertFalse("a second, different prompt is not a duplicate",
                DeckLayoutStore.wouldDuplicate(context, different));

        DeckTile same = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PROMPT, "Explain this:");
        assertTrue("the same prompt twice is", DeckLayoutStore.wouldDuplicate(context, same));
    }

    @Test public void theSameAppTwiceIsDetectedByPackageNotByName() {
        DeckTile first = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, "com.example.music")
                .withConfig(DeckTile.CONFIG_TITLE, "Music");
        DeckLayoutStore.add(context, first);

        DeckTile renamed = DeckTile.of(DeckTileRegistry.TYPE_APP, DeckTile.Size.STANDARD)
                .withConfig(DeckTile.CONFIG_PACKAGE, "com.example.music")
                .withConfig(DeckTile.CONFIG_TITLE, "Tunes");
        assertTrue("renaming it does not make it a different tile",
                DeckLayoutStore.wouldDuplicate(context, renamed));
    }

    // ---- sanitising -------------------------------------------------------------------------------

    @Test public void titlesAreBoundedAndFlattened() {
        assertEquals("one two", DeckTile.sanitizeTitle("  one   two  "));
        assertEquals("a b", DeckTile.sanitizeTitle("a\nb"));
        assertTrue(DeckTile.sanitizeTitle(repeat("x", 200)).length() <= DeckTile.MAX_TITLE_LENGTH);
        assertEquals("", DeckTile.sanitizeTitle(null));
    }

    @Test public void promptsAreBoundedButKeepTheirLineBreaks() {
        assertEquals("one\ntwo", DeckTile.sanitizePrompt("  one\ntwo  "));
        assertTrue(DeckTile.sanitizePrompt(repeat("y", 5000)).length()
                <= DeckTile.MAX_PROMPT_LENGTH);
    }

    private static String repeat(String value, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(value);
        return out.toString();
    }

    private DeckTile find(String instanceId) {
        for (DeckTile tile : DeckLayoutStore.layout(context)) {
            if (tile.instanceId.equals(instanceId)) return tile;
        }
        return null;
    }
}

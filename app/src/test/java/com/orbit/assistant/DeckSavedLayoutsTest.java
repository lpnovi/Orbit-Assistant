package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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

/** Schema-3 migration, isolation, entitlement fallback, and layout-management integrity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckSavedLayoutsTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
    }

    @Test public void schema2MigratesExactlyOnceWithoutChangingItsContents() throws Exception {
        JSONObject appearance = new JSONObject()
                .put("accent", "mint")
                .put("material", OrbitTheme.MATERIAL_FROSTED)
                .put("icon", DeckTileAppearance.ICON_MONOCHROME)
                .put("showLabel", false)
                .put("futureAppearance", "kept");
        JSONObject rootTile = tile("tile-root", DeckTileRegistry.TYPE_PROMPT, "large")
                .put("config", new JSONObject()
                        .put(DeckTile.CONFIG_PROMPT, "Keep this\nexactly")
                        .put("futureKey", "futureValue")
                        .put("emptyFutureKey", ""))
                .put("appearance", appearance);
        JSONObject child = tile("tile-child", "orbit.future", "wide")
                .put("config", new JSONObject().put("unknown", "yes"));
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("kind", "section")
                        .put("id", "section-id").put("title", "Work"))
                .put(new JSONObject().put("kind", "tile").put("tile", rootTile))
                .put(new JSONObject().put("kind", "folder").put("id", "folder-id")
                        .put("title", "Tools").put("tiles", new JSONArray().put(child)));
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 2)
                .put("layout", new JSONObject().put("id", "primary").put("items", items))
                .toString());

        DeckCollection migrated = DeckLayoutStore.collection(context);
        assertEquals(1, migrated.layouts.size());
        assertEquals("primary", migrated.activeLayoutId);
        assertEquals("My Deck", migrated.active().name);
        assertEquals("section-id", migrated.active().items.get(0).id);
        assertEquals("tile-root", migrated.active().items.get(1).id);
        DeckFolder folder = (DeckFolder) migrated.active().items.get(2);
        assertEquals("folder-id", folder.id);
        assertEquals(1, folder.tiles.size());
        assertEquals("tile-child", folder.tiles.get(0).instanceId);
        DeckTile migratedTile = migrated.active().allTiles().get(0);
        assertEquals(DeckTile.Size.LARGE, migratedTile.size);
        assertEquals("futureValue", migratedTile.config("futureKey"));
        assertEquals("", migratedTile.configMap().get("emptyFutureKey"));
        assertEquals("mint", migratedTile.appearance.accent);
        assertFalse(migratedTile.appearance.showLabel);

        String once = DeckLayoutStore.rawForTest(context);
        assertEquals(3, new JSONObject(once).getInt("version"));
        DeckLayoutStore.collection(context);
        assertEquals("reopening does not migrate or duplicate again",
                once, DeckLayoutStore.rawForTest(context));
        assertEquals(1, DeckLayoutStore.layoutCount(context));
    }

    @Test public void configuredEmptySchema2DeckStaysEmpty() throws Exception {
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 2)
                .put("layout", new JSONObject().put("id", "primary")
                        .put("items", new JSONArray())).toString());

        assertTrue(DeckLayoutStore.configured(context));
        assertTrue(DeckLayoutStore.deck(context).items.isEmpty());
        assertEquals(1, DeckLayoutStore.layoutCount(context));
        assertTrue(DeckLayoutStore.deck(context).items.isEmpty());
    }

    @Test public void malformedSchema2FailsSafelyWithoutTouchingUnrelatedPreferences() throws Exception {
        String malformed = new JSONObject().put("version", 2).put("notLayout", true).toString();
        Prefs.get(context).edit().putString("beta7_unrelated_marker", "kept").commit();
        DeckLayoutStore.writeRawForTest(context, malformed);

        assertEquals(DeckLayoutStore.defaults().size(), DeckLayoutStore.deck(context).allTiles().size());
        assertEquals(malformed, DeckLayoutStore.rawForTest(context));
        assertEquals("kept", Prefs.get(context).getString("beta7_unrelated_marker", ""));
    }

    @Test public void schema3RepairsInvalidAndDuplicateIdsOnceAndPersistsTheRepair() throws Exception {
        JSONArray items = new JSONArray()
                .put(new JSONObject().put("kind", "section").put("id", "same").put("title", "One"))
                .put(new JSONObject().put("kind", "folder").put("id", "same").put("title", "Two")
                        .put("tiles", new JSONArray()
                                .put(tile("", DeckTileRegistry.TYPE_FLASHLIGHT, "standard"))
                                .put(tile("repaired:tile", DeckTileRegistry.TYPE_MEDIA, "standard"))));
        JSONObject layout = new JSONObject().put("id", "").put("name", "  My   Deck  ")
                .put("items", items);
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 3)
                .put("activeLayoutId", "missing").put("layouts", new JSONArray().put(layout))
                .toString());

        DeckCollection repaired = DeckLayoutStore.collection(context);
        assertEquals("primary", repaired.activeLayoutId);
        assertEquals("My Deck", repaired.active().name);
        assertEquals(5, identityIds(repaired.active()).size());
        String once = DeckLayoutStore.rawForTest(context);
        DeckLayoutStore.collection(context);
        assertEquals(once, DeckLayoutStore.rawForTest(context));
    }

    @Test public void invalidActiveIdFallsBackToTheFirstLayoutDeterministically() throws Exception {
        JSONArray layouts = new JSONArray()
                .put(new JSONObject().put("id", "first").put("name", "First")
                        .put("items", new JSONArray()))
                .put(new JSONObject().put("id", "second").put("name", "Second")
                        .put("items", new JSONArray()));
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 3)
                .put("activeLayoutId", "missing").put("layouts", layouts).toString());

        assertEquals("first", DeckLayoutStore.activeLayoutId(context));
        assertEquals("first", new JSONObject(DeckLayoutStore.rawForTest(context))
                .getString("activeLayoutId"));
    }

    @Test public void blankLayoutIsReallyBlankAndSurvivesRestart() {
        DeckLayout blank = DeckLayoutStore.createBlankLayout(context, "  Minimal\nDeck  ");

        assertNotNull(blank);
        assertEquals("Minimal Deck", blank.name);
        assertTrue(blank.items.isEmpty());
        assertEquals(blank.id, DeckLayoutStore.activeLayoutId(context));
        assertTrue(DeckLayoutStore.deck(context).items.isEmpty());
    }

    @Test public void duplicateRemapsEveryMutableIdentityAndPreservesMeaning() {
        String sourceId = DeckLayoutStore.activeLayoutId(context);
        DeckLayoutStore.addSection(context, "Work");
        DeckLayoutStore.addFolder(context, "Tools");
        DeckTile prompt = DeckTile.of(DeckTileRegistry.TYPE_PROMPT, DeckTile.Size.LARGE)
                .withConfig(DeckTile.CONFIG_PROMPT, "Plan my day")
                .withConfig("future", "kept")
                .withAppearance(new DeckTileAppearance("mint", OrbitTheme.MATERIAL_LIQUID,
                        DeckTileAppearance.ICON_MONOCHROME, false));
        DeckLayoutStore.add(context, prompt);
        DeckFolder folder = firstFolder(DeckLayoutStore.deck(context));
        DeckLayoutStore.moveTile(context, prompt.instanceId, folder.id);
        DeckLayout source = DeckLayoutStore.deck(context);

        DeckLayout copy = DeckLayoutStore.duplicateLayout(context, sourceId, "Work copy");
        assertNotNull(copy);
        assertNotEquals(source.id, copy.id);
        assertEquals(source.items.size(), copy.items.size());
        assertEquals(source.allTiles().size(), copy.allTiles().size());
        assertNoSharedIds(source, copy);
        DeckTile copiedPrompt = findType(copy, DeckTileRegistry.TYPE_PROMPT);
        assertNotNull(copiedPrompt);
        assertEquals("Plan my day", copiedPrompt.config(DeckTile.CONFIG_PROMPT));
        assertEquals("kept", copiedPrompt.config("future"));
        assertEquals(prompt.size, copiedPrompt.size);
        assertEquals(prompt.appearance, copiedPrompt.appearance);
    }

    @Test public void editingDuplicateCannotModifySource() {
        String sourceId = DeckLayoutStore.activeLayoutId(context);
        int sourceItems = DeckLayoutStore.deck(context).items.size();
        DeckLayout copy = DeckLayoutStore.duplicateLayout(context, sourceId, "Copy");
        DeckTile copied = copy.allTiles().get(0);
        assertTrue(DeckLayoutStore.configure(context, copy.id, copied.instanceId,
                DeckTile.CONFIG_TITLE, "Changed"));
        assertTrue(DeckLayoutStore.updateAppearance(context, copy.id, copied.instanceId,
                new DeckTileAppearance("violet", OrbitTheme.MATERIAL_FROSTED,
                        DeckTileAppearance.ICON_MONOCHROME, false)));
        assertTrue(DeckLayoutStore.addSection(context, copy.id, "Only in copy"));
        assertTrue(DeckLayoutStore.addFolder(context, copy.id, "Copy folder"));

        assertEquals("Changed", DeckLayoutStore.deck(context).allTiles().get(0)
                .config(DeckTile.CONFIG_TITLE));
        assertTrue(DeckLayoutStore.switchLayout(context, sourceId));
        assertEquals("", DeckLayoutStore.deck(context).allTiles().get(0)
                .config(DeckTile.CONFIG_TITLE));
        assertEquals(DeckTileAppearance.DEFAULT,
                DeckLayoutStore.deck(context).allTiles().get(0).appearance);
        assertEquals(sourceItems, DeckLayoutStore.deck(context).items.size());
    }

    @Test public void namesAreSanitizedButDuplicateNamesRemainDistinct() {
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Second");
        assertTrue(DeckLayoutStore.renameLayout(context, second.id,
                "  My\nDeck   "));
        assertEquals("My Deck", DeckLayoutStore.deck(context).name);
        assertNotEquals(DeckLayoutStore.layouts(context).get(0).id,
                DeckLayoutStore.layouts(context).get(1).id);
        assertEquals(DeckLayoutStore.layouts(context).get(0).name,
                DeckLayoutStore.layouts(context).get(1).name);
        assertFalse(DeckLayoutStore.renameLayout(context, second.id, "   \n "));
        assertTrue(DeckLayoutStore.deck(context).name.length() <= DeckLayout.MAX_NAME_LENGTH);
    }

    @Test public void switchingPersistsAndDuplicateRulesArePerActiveLayout() {
        String first = DeckLayoutStore.activeLayoutId(context);
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Second");
        DeckTile settings = DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD);
        assertFalse(DeckLayoutStore.wouldDuplicate(context, settings));
        assertTrue(DeckLayoutStore.add(context, settings));
        assertTrue(DeckLayoutStore.wouldDuplicate(context,
                DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.STANDARD)));

        assertTrue(DeckLayoutStore.switchLayout(context, first));
        assertTrue("the default first layout already has no Settings tile",
                !DeckLayoutStore.contains(context, DeckTileRegistry.TYPE_SETTINGS));
        assertEquals(first, DeckLayoutStore.collection(context).activeLayoutId);
        assertTrue(DeckLayoutStore.switchLayout(context, second.id));
        assertEquals(second.id, DeckLayoutStore.collection(context).activeLayoutId);
    }

    @Test public void freeFallbackPreservesAllLayoutsAndKeepsTheActiveOneEditable() {
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Work");
        assertNotNull(second);
        assertTrue(DeckLayoutStore.add(context,
                DeckTile.of(DeckTileRegistry.TYPE_SETTINGS, DeckTile.Size.LARGE)));
        String active = DeckLayoutStore.activeLayoutId(context);
        String rawBefore = DeckLayoutStore.rawForTest(context);

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertFalse(DeckLayoutStore.switchLayout(context, DeckLayout.PRIMARY_ID));
        assertFalse(DeckLayoutStore.renameLayout(context, active, "Hidden rename"));
        assertNull(DeckLayoutStore.createBlankLayout(context, "Third"));
        assertNull(DeckLayoutStore.duplicateLayout(context, active, "Copy"));
        assertFalse(DeckLayoutStore.deleteLayout(context, DeckLayout.PRIMARY_ID));
        assertEquals(active, DeckLayoutStore.activeLayoutId(context));
        assertTrue("ordinary Free organization stays writable",
                DeckLayoutStore.addSection(context, active, "Free section"));
        assertTrue(DeckLayoutStore.addFolder(context, active, "Free folder"));
        DeckTile freeTile = DeckLayoutStore.deck(context).allTiles().get(0);
        assertTrue(DeckLayoutStore.resize(context, active, freeTile.instanceId,
                DeckTile.Size.STANDARD));
        DeckFolder freeFolder = firstFolder(DeckLayoutStore.deck(context));
        assertTrue(DeckLayoutStore.moveTile(context, active, freeTile.instanceId, freeFolder.id));
        assertEquals(2, DeckLayoutStore.layoutCount(context));

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertEquals("Work", DeckLayoutStore.collection(context).layout(active).name);
        assertNotNull(DeckLayoutStore.collection(context).layout(DeckLayout.PRIMARY_ID));
        assertNotEquals("the Free edit changes only the active layout, without deleting data",
                rawBefore, DeckLayoutStore.rawForTest(context));
    }

    @Test public void layoutCapNeverDeletesAnExistingLayout() {
        for (int i = 1; i < DeckLayoutStore.MAX_LAYOUTS; i++) {
            assertNotNull(DeckLayoutStore.createBlankLayout(context, "Deck " + i));
        }
        List<String> before = layoutIds(DeckLayoutStore.layouts(context));
        assertNull(DeckLayoutStore.createBlankLayout(context, "One too many"));
        assertNull(DeckLayoutStore.duplicateLayout(context,
                DeckLayoutStore.activeLayoutId(context), "One too many"));
        assertNull(DeckLayoutStore.createTemplateLayout(context,
                DeckLayoutTemplates.ESSENTIALS, "One too many"));
        assertEquals(before, layoutIds(DeckLayoutStore.layouts(context)));
    }

    @Test public void deletingInactiveAndActiveLayoutsIsNarrowAndDeterministic() {
        String first = DeckLayoutStore.activeLayoutId(context);
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Second");
        DeckLayout third = DeckLayoutStore.createBlankLayout(context, "Third");
        assertTrue(DeckLayoutStore.deleteLayout(context, second.id));
        assertNull(DeckLayoutStore.collection(context).layout(second.id));
        assertEquals(third.id, DeckLayoutStore.activeLayoutId(context));

        assertTrue(DeckLayoutStore.deleteLayout(context, third.id));
        assertEquals(first, DeckLayoutStore.activeLayoutId(context));
        assertFalse(DeckLayoutStore.deleteLayout(context, first));
    }

    @Test public void everyTemplateCreatesFreshSafeIndependentItems() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        String original = DeckLayoutStore.activeLayoutId(context);
        Set<String> allIds = new HashSet<>();
        for (DeckLayoutTemplates.Template template : DeckLayoutTemplates.all()) {
            DeckLayout created = DeckLayoutStore.createTemplateLayout(
                    context, template.id, template.name);
            assertNotNull(created);
            assertNotEquals(original, created.id);
            for (DeckTile tile : created.allTiles()) {
                assertFalse(DeckTileRegistry.TYPE_APP.equals(tile.type));
                assertFalse(DeckTileRegistry.TYPE_ROUTINE.equals(tile.type));
                assertFalse(DeckTileRegistry.TYPE_PROMPT.equals(tile.type));
                assertFalse(DeckTileRegistry.TYPE_VAULT.equals(tile.type));
                assertFalse(DeckTileRegistry.TYPE_QUICK_CAPTURE.equals(tile.type));
            }
            for (String id : identityIds(created)) {
                assertTrue("every template runtime id is fresh", allIds.add(id));
            }
        }
        assertNotNull("the previous layout is untouched",
                DeckLayoutStore.collection(context).layout(original));
        DeckLayout template = DeckLayoutStore.deck(context);
        assertTrue(DeckLayoutStore.renameLayout(context, template.id, "Renamed template"));
        DeckLayout duplicate = DeckLayoutStore.duplicateLayout(context, template.id, "Template copy");
        assertNotNull(duplicate);
        assertTrue(DeckLayoutStore.deleteLayout(context, template.id));
        assertNull(DeckLayoutStore.collection(context).layout(template.id));
    }

    @Test public void suggestionsConsultOnlyTheActiveLayout() {
        DeckTileResolver.LiveState playing = new DeckTileResolver.LiveState(null, true, "Music");
        String first = DeckLayoutStore.activeLayoutId(context);
        assertFalse(containsSuggestion(DeckSuggestionEngine.suggestions(context, playing,
                DeckLayoutStore.allTiles(context), DeckSuggestionEngine.MAX_PHONE, 1L), "media"));

        DeckLayout blank = DeckLayoutStore.createBlankLayout(context, "Blank");
        assertTrue(containsSuggestion(DeckSuggestionEngine.suggestions(context, playing,
                DeckLayoutStore.allTiles(context), DeckSuggestionEngine.MAX_PHONE, 1L), "media"));
        assertTrue(DeckLayoutStore.switchLayout(context, first));
        assertFalse(containsSuggestion(DeckSuggestionEngine.suggestions(context, playing,
                DeckLayoutStore.allTiles(context), DeckSuggestionEngine.MAX_PHONE, 1L), "media"));
        assertNotNull(DeckLayoutStore.collection(context).layout(blank.id));
    }

    @Test public void staleMutationsCannotCrossAChangedActiveLayout() {
        String first = DeckLayoutStore.activeLayoutId(context);
        DeckTile firstTile = DeckLayoutStore.deck(context).allTiles().get(0);
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Second");

        assertFalse(DeckLayoutStore.configure(context, first, firstTile.instanceId,
                DeckTile.CONFIG_TITLE, "Stale"));
        assertFalse(DeckLayoutStore.applyItemOrder(context, first, new ArrayList<>()));
        assertFalse(DeckLayoutStore.remove(context, first, firstTile.instanceId));
        assertTrue(DeckLayoutStore.deck(context).items.isEmpty());
        assertEquals(second.id, DeckLayoutStore.activeLayoutId(context));
    }

    @Test public void resetChangesOnlyTheActiveLayout() {
        String first = DeckLayoutStore.activeLayoutId(context);
        DeckTile removed = DeckLayoutStore.deck(context).allTiles().get(0);
        assertTrue(DeckLayoutStore.remove(context, removed.instanceId));
        DeckLayout second = DeckLayoutStore.createBlankLayout(context, "Second");
        assertTrue(DeckLayoutStore.reset(context, second.id));
        assertEquals(DeckLayoutStore.defaults().size(), DeckLayoutStore.deck(context).allTiles().size());
        assertTrue(DeckLayoutStore.switchLayout(context, first));
        assertEquals(DeckLayoutStore.defaults().size() - 1,
                DeckLayoutStore.deck(context).allTiles().size());
    }

    private static JSONObject tile(String id, String type, String size) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("size", size)
                .put("config", new JSONObject());
    }

    private static boolean containsSuggestion(
            List<DeckSuggestionEngine.Suggestion> suggestions, String id) {
        for (DeckSuggestionEngine.Suggestion suggestion : suggestions) {
            if (id.equals(suggestion.id)) return true;
        }
        return false;
    }

    private static DeckFolder firstFolder(DeckLayout layout) {
        for (DeckItem item : layout.items) if (item instanceof DeckFolder) return (DeckFolder) item;
        return null;
    }

    private static DeckTile findType(DeckLayout layout, String type) {
        for (DeckTile tile : layout.allTiles()) if (type.equals(tile.type)) return tile;
        return null;
    }

    private static void assertNoSharedIds(DeckLayout source, DeckLayout copy) {
        Set<String> sourceIds = new HashSet<>();
        sourceIds.add(source.id);
        for (DeckItem item : source.items) sourceIds.add(item.id);
        for (DeckTile tile : source.allTiles()) sourceIds.add(tile.instanceId);
        assertFalse(sourceIds.contains(copy.id));
        for (DeckItem item : copy.items) assertFalse(sourceIds.contains(item.id));
        for (DeckTile tile : copy.allTiles()) assertFalse(sourceIds.contains(tile.instanceId));
    }

    private static List<String> layoutIds(List<DeckLayout> layouts) {
        List<String> ids = new ArrayList<>();
        for (DeckLayout layout : layouts) ids.add(layout.id);
        return ids;
    }

    private static Set<String> identityIds(DeckLayout layout) {
        Set<String> ids = new HashSet<>();
        ids.add(layout.id);
        for (DeckItem item : layout.items) {
            ids.add(item.id);
            if (item instanceof DeckFolder) {
                for (DeckTile tile : ((DeckFolder) item).tiles) ids.add(tile.instanceId);
            }
        }
        return ids;
    }
}

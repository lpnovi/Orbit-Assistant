package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Behavioral coverage for Beta 6 Deck structure, migration, geometry, and Pro appearance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DeckBeta6Test {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DeckLayoutStore.clearForTest(context);
        UiKit.syncTheme(context);
    }

    @Test public void schemaOneMigratesInOrderWithoutChangingTileData() throws Exception {
        JSONObject first = tile("one", DeckTileRegistry.TYPE_PROMPT, "standard")
                .put("config", new JSONObject().put(DeckTile.CONFIG_PROMPT, "hello")
                        .put("futureKey", "futureValue"));
        JSONObject second = tile("two", DeckTileRegistry.TYPE_MEDIA, "wide");
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 1)
                .put("tiles", new JSONArray().put(first).put(second)).toString());

        DeckLayout migrated = DeckLayoutStore.deck(context);
        assertEquals(Arrays.asList("one", "two"), ids(migrated.allTiles()));
        assertEquals(DeckTile.Size.STANDARD, migrated.allTiles().get(0).size);
        assertEquals(DeckTile.Size.WIDE, migrated.allTiles().get(1).size);
        assertEquals("hello", migrated.allTiles().get(0).config(DeckTile.CONFIG_PROMPT));
        assertEquals("futureValue", migrated.allTiles().get(0).config("futureKey"));
        assertEquals(DeckLayoutStore.SCHEMA_VERSION,
                new JSONObject(DeckLayoutStore.rawForTest(context)).getInt("version"));

        String once = DeckLayoutStore.rawForTest(context);
        assertEquals("reopening is idempotent", ids(migrated.allTiles()),
                ids(DeckLayoutStore.deck(context).allTiles()));
        assertEquals("reopening does not rewrite or duplicate", once, DeckLayoutStore.rawForTest(context));
    }

    @Test public void configuredEmptySchemaOneStaysEmptyWhileUnconfiguredGetsDefaults() throws Exception {
        DeckLayoutStore.writeRawForTest(context, new JSONObject().put("version", 1)
                .put("tiles", new JSONArray()).toString());
        assertTrue(DeckLayoutStore.deck(context).items.isEmpty());
        DeckLayoutStore.clearForTest(context);
        assertEquals(DeckLayoutStore.defaults(), DeckLayoutStore.layout(context));
    }

    @Test public void malformedStorageFallsBackWithoutExploding() {
        DeckLayoutStore.writeRawForTest(context, "{not-json");
        assertEquals(DeckLayoutStore.defaults(), DeckLayoutStore.layout(context));
    }

    @Test public void sectionsAreFreeStableMarkersAndRemovalKeepsTiles() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        int before = DeckLayoutStore.allTiles(context).size();
        assertTrue(DeckLayoutStore.addSection(context, "Daily"));
        DeckSection section = null;
        for (DeckItem item : DeckLayoutStore.deck(context).items) if (item instanceof DeckSection) section = (DeckSection) item;
        assertNotNull(section);
        String id = section.id;
        assertTrue(DeckLayoutStore.renameItem(context, id, "Work"));
        assertEquals(id, findSection().id);
        assertEquals("Work", findSection().title);
        assertTrue(DeckLayoutStore.removeSection(context, id));
        assertEquals(before, DeckLayoutStore.allTiles(context).size());
    }

    @Test public void folderMovesPreserveIdentityAndHierarchyWideDuplicates() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertTrue(DeckLayoutStore.addFolder(context, "Work"));
        assertTrue(DeckLayoutStore.addFolder(context, "Media"));
        List<DeckFolder> folders = folders();
        DeckTile settings = null;
        for (DeckTile tile : DeckLayoutStore.layout(context)) {
            if (DeckTileRegistry.TYPE_ROUTINES.equals(tile.type)) settings = tile;
        }
        assertNotNull(settings);
        String tileId = settings.instanceId;
        assertTrue(DeckLayoutStore.moveTile(context, tileId, folders.get(0).id));
        assertEquals(tileId, DeckLayoutStore.deck(context).folder(folders.get(0).id).tiles.get(0).instanceId);
        assertTrue(DeckLayoutStore.contains(context, DeckTileRegistry.TYPE_ROUTINES));
        assertTrue(DeckLayoutStore.wouldDuplicate(context,
                DeckTile.of(DeckTileRegistry.TYPE_ROUTINES, DeckTile.Size.STANDARD)));
        assertTrue(DeckLayoutStore.moveTile(context, tileId, folders.get(1).id));
        assertEquals(tileId, DeckLayoutStore.deck(context).folder(folders.get(1).id).tiles.get(0).instanceId);
        assertTrue(DeckLayoutStore.moveTile(context, tileId, null));
        assertTrue(ids(DeckLayoutStore.layout(context)).contains(tileId));
    }

    @Test public void removingFolderMovesChildrenBackAndNeverDeletesThem() {
        DeckLayoutStore.addFolder(context, "Work");
        DeckFolder folder = folders().get(0);
        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        DeckLayoutStore.moveTile(context, tile.instanceId, folder.id);
        assertTrue(DeckLayoutStore.removeFolderMovingChildren(context, folder.id));
        assertTrue(ids(DeckLayoutStore.layout(context)).contains(tile.instanceId));
        assertEquals(0, DeckLayoutStore.folderCount(context));
    }

    @Test public void suggestionsSeeTilesInsideFolders() {
        DeckLayoutStore.addFolder(context, "Media");
        DeckFolder folder = folders().get(0);
        DeckTile media = null;
        for (DeckTile tile : DeckLayoutStore.layout(context)) if (DeckTileRegistry.TYPE_MEDIA.equals(tile.type)) media = tile;
        assertNotNull(media);
        DeckLayoutStore.moveTile(context, media.instanceId, folder.id);
        List<DeckSuggestionEngine.Suggestion> suggestions = DeckSuggestionEngine.suggestions(context,
                new DeckTileResolver.LiveState(null, true, "Music"), DeckLayoutStore.allTiles(context),
                DeckSuggestionEngine.MAX_PHONE, System.currentTimeMillis());
        for (DeckSuggestionEngine.Suggestion suggestion : suggestions) assertFalse("media".equals(suggestion.id));
    }

    @Test public void largeIsRealTwoByTwoGeometryWithoutOverlap() {
        DeckGridLayout grid = new DeckGridLayout(context);
        grid.setColumns(2);
        grid.setSpacing(UiKit.dp(context, 12));
        grid.setMinRowHeight(UiKit.dp(context, 100));
        View large = new View(context);
        View standard = new View(context);
        grid.addView(large, new DeckGridLayout.LayoutParams(2, 2));
        grid.addView(standard, new DeckGridLayout.LayoutParams(1, 1));
        layout(grid);
        assertTrue(large.getHeight() >= UiKit.dp(context, 212));
        assertTrue(standard.getTop() >= large.getBottom());
        assertFalse(Rect.intersects(bounds(large), bounds(standard)));
        assertTrue(grid.occupancyIsValidForTest());
    }

    @Test public void draggingMixedSizesKeepsAValidTwoDimensionalPacking() {
        DeckGridLayout grid = new DeckGridLayout(context);
        grid.setColumns(3);
        grid.setSpacing(UiKit.dp(context, 12));
        grid.setMinRowHeight(UiKit.dp(context, 100));
        View standardA = new View(context);
        View large = new View(context);
        View wide = new View(context);
        View standardB = new View(context);
        grid.addView(standardA, new DeckGridLayout.LayoutParams(1, 1));
        grid.addView(large, new DeckGridLayout.LayoutParams(2, 2));
        grid.addView(wide, new DeckGridLayout.LayoutParams(2, 1));
        grid.addView(standardB, new DeckGridLayout.LayoutParams(1, 1));
        layout(grid);
        grid.beginDrag(large);
        float[] target = grid.slotCenterForTest(wide);
        float[] origin = grid.slotCenterForTest(large);
        grid.updateDrag(target[0] - origin[0], target[1] - origin[1]);
        layout(grid);
        assertTrue(grid.occupancyIsValidForTest());
        assertTrue(grid.endDrag() || grid.occupancyIsValidForTest());
        layout(grid);
        assertTrue(grid.occupancyIsValidForTest());
    }

    @Test public void sectionRowIsNaturalHeightAndFullWidth() {
        DeckGridLayout grid = new DeckGridLayout(context);
        grid.setColumns(3);
        grid.setMinRowHeight(UiKit.dp(context, 100));
        View tile = new View(context);
        View section = new View(context);
        section.setMinimumHeight(UiKit.dp(context, 36));
        grid.addView(tile, new DeckGridLayout.LayoutParams(1, 1));
        grid.addView(section, new DeckGridLayout.LayoutParams(3, 0));
        layout(grid);
        assertEquals(1080, section.getWidth());
        assertTrue(section.getHeight() < tile.getHeight());
        assertTrue(section.getTop() >= tile.getBottom());
    }

    @Test public void registryControlsLargeAndAppStaysStandardOnly() {
        assertTrue(DeckTileRegistry.definition(DeckTileRegistry.TYPE_PROMPT).supports(DeckTile.Size.LARGE));
        DeckTileRegistry.Definition app = DeckTileRegistry.definition(DeckTileRegistry.TYPE_APP);
        assertTrue(app.supports(DeckTile.Size.STANDARD));
        assertFalse(app.supports(DeckTile.Size.WIDE));
        assertFalse(app.supports(DeckTile.Size.LARGE));
    }

    @Test public void resizeAndOrganizationRemainFree() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        assertTrue(DeckLayoutStore.resize(context, tile.instanceId, DeckTile.Size.LARGE));
        assertEquals(DeckTile.Size.LARGE, DeckLayoutStore.layout(context).get(0).size);
        assertTrue(DeckLayoutStore.addSection(context, "AI"));
        assertTrue(DeckLayoutStore.addFolder(context, "Work"));
    }

    @Test public void appearanceIsStoredWhileFreeIgnoredAndRestoredWithPro() {
        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        DeckTileAppearance custom = new DeckTileAppearance("#123456", OrbitTheme.MATERIAL_FROSTED,
                DeckTileAppearance.ICON_MONOCHROME, false);
        assertTrue(DeckLayoutStore.updateAppearance(context, tile.instanceId, custom));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        DeckTile storedFree = DeckLayoutStore.layout(context).get(0);
        assertEquals(custom, storedFree.appearance);
        assertEquals(DeckTileAppearance.DEFAULT, storedFree.appearance.effective(context));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertEquals(custom, DeckLayoutStore.layout(context).get(0).appearance.effective(context));
    }

    @Test public void hiddenVisualLabelStillHasAccessibleTitle() {
        DeckTile base = DeckLayoutStore.layout(context).get(0);
        DeckTile hidden = base.withAppearance(base.appearance.withShowLabel(false));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        DeckTileResolver.Resolved resolved = DeckTileResolver.resolve(context, hidden);
        DeckTileView view = new DeckTileView(context, hidden, resolved, null);
        assertFalse(view.labelVisible());
        assertTrue(String.valueOf(view.getContentDescription()).contains(resolved.title));
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        DeckTileView free = new DeckTileView(context, hidden, resolved, null);
        assertTrue(free.labelVisible());
    }

    @Test public void appearanceRoundTripCoversLargeAndAllMaterials() {
        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        for (String material : new String[]{OrbitTheme.MATERIAL_SOLID,
                OrbitTheme.MATERIAL_FROSTED, OrbitTheme.MATERIAL_LIQUID}) {
            DeckTileAppearance appearance = new DeckTileAppearance("violet", material,
                    DeckTileAppearance.ICON_ACCENT, true);
            assertTrue(DeckLayoutStore.resize(context, tile.instanceId, DeckTile.Size.LARGE));
            assertTrue(DeckLayoutStore.updateAppearance(context, tile.instanceId, appearance));
            DeckTile stored = DeckLayoutStore.layout(context).get(0);
            assertEquals(DeckTile.Size.LARGE, stored.size);
            assertEquals(material, stored.appearance.material);
        }
    }

    @Test public void activityRendersStructuralItemsAndLargeFootprint() {
        DeckTile first = DeckLayoutStore.layout(context).get(0);
        DeckLayoutStore.resize(context, first.instanceId, DeckTile.Size.LARGE);
        DeckLayoutStore.addSection(context, "Daily");
        DeckLayoutStore.addFolder(context, "Work");
        DeckActivity activity = Robolectric.buildActivity(DeckActivity.class).setup().get();
        DeckGridLayout grid = activity.gridForTest();
        assertEquals(DeckLayoutStore.deck(context).items.size(), grid.orderedChildren().size());
        boolean heading = false;
        boolean large = false;
        boolean folder = false;
        for (View child : grid.orderedChildren()) {
            DeckGridLayout.LayoutParams lp = (DeckGridLayout.LayoutParams) child.getLayoutParams();
            if (child.isAccessibilityHeading()) heading = true;
            if (lp.rowSpan == 2 && lp.span == 2) large = true;
            if (String.valueOf(child.getContentDescription()).contains("folder")) folder = true;
        }
        assertTrue(heading);
        assertTrue(large);
        assertTrue(folder);
    }

    @Test public void folderSurfaceUsesRealTileViewsAndStoredInstances() {
        DeckLayoutStore.addFolder(context, "Work");
        DeckFolder folder = folders().get(0);
        DeckTile tile = DeckLayoutStore.layout(context).get(0);
        DeckLayoutStore.moveTile(context, tile.instanceId, folder.id);
        DeckActivity activity = Robolectric.buildActivity(DeckActivity.class).setup().get();
        activity.openFolderForTest(folder.id);
        assertTrue(activity.sheetOpenForTest());
        assertNotNull(activity.folderGridForTest());
        View child = activity.folderGridForTest().orderedChildren().get(0);
        assertTrue(child instanceof DeckTileView);
        assertEquals(tile.instanceId, ((DeckTileView) child).tile().instanceId);
        assertEquals(DeckTileResolver.resolve(context, tile).title,
                ((DeckTileView) child).titleText().toString());
    }

    private JSONObject tile(String id, String type, String size) throws Exception {
        return new JSONObject().put("id", id).put("type", type).put("size", size)
                .put("config", new JSONObject());
    }

    private List<String> ids(List<DeckTile> tiles) {
        List<String> out = new ArrayList<>();
        for (DeckTile tile : tiles) out.add(tile.instanceId);
        return out;
    }

    private DeckSection findSection() {
        for (DeckItem item : DeckLayoutStore.deck(context).items) if (item instanceof DeckSection) return (DeckSection) item;
        return null;
    }

    private List<DeckFolder> folders() {
        List<DeckFolder> out = new ArrayList<>();
        for (DeckItem item : DeckLayoutStore.deck(context).items) if (item instanceof DeckFolder) out.add((DeckFolder) item);
        return out;
    }

    private void layout(DeckGridLayout grid) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        grid.layout(0, 0, 1080, grid.getMeasuredHeight());
    }

    private Rect bounds(View view) { return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom()); }
}

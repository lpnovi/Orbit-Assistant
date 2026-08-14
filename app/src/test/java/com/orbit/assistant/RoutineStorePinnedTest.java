package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Arrays;
import java.util.List;

/** Pinned routine persistence, ordering, cleanup, and compatibility with pre-pinning data. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineStorePinnedTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static List<AssistantReply.Action> flashlightAction() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        JSONObject params = new JSONObject();
        try {
            params.put("on", true);
        } catch (Exception ignored) {}
        actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT, params, false));
        return actions;
    }

    private RoutineStore.Routine save(String name) {
        RoutineStore.Routine routine = RoutineStore.create(name, flashlightAction());
        assertTrue("could not save " + name, RoutineStore.upsert(context, routine));
        return routine;
    }

    private static List<String> names(List<RoutineStore.Routine> routines) {
        List<String> out = new ArrayList<>();
        for (RoutineStore.Routine routine : routines) out.add(routine.name);
        return out;
    }

    private String storedJson() {
        return context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");
    }

    @Test public void newRoutinesStartUnpinned() {
        save("Bedtime");
        RoutineStore.Routine stored = RoutineStore.findById(context, RoutineStore.list(context).get(0).id);
        assertNotNull(stored);
        assertFalse(stored.pinned);
    }

    @Test public void pinnedStateIsPersistedAndReReadFromStorage() {
        RoutineStore.Routine routine = save("Driving");
        assertTrue(RoutineStore.setPinned(context, routine.id, true));

        // list() re-reads SharedPreferences, so this is the same path a relaunch takes.
        RoutineStore.Routine reloaded = RoutineStore.findById(context, routine.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.pinned);
        assertTrue(storedJson().contains("\"pinned\""));

        assertTrue(RoutineStore.setPinned(context, routine.id, false));
        RoutineStore.Routine unpinned = RoutineStore.findById(context, routine.id);
        assertNotNull(unpinned);
        assertFalse(unpinned.pinned);
    }

    @Test public void pinnedRoutinesListFirstAndBothGroupsKeepTheirOrder() {
        save("Alpha");
        RoutineStore.Routine bravo = save("Bravo");
        save("Charlie");
        RoutineStore.Routine delta = save("Delta");
        assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie", "Delta"), names(RoutineStore.list(context)));

        assertTrue(RoutineStore.setPinned(context, delta.id, true));
        assertTrue(RoutineStore.setPinned(context, bravo.id, true));

        // Bravo before Delta because the pinned group keeps the original relative order,
        // not the order in which the two were pinned.
        assertEquals(Arrays.asList("Bravo", "Delta", "Alpha", "Charlie"), names(RoutineStore.list(context)));

        assertTrue(RoutineStore.setPinned(context, bravo.id, false));
        assertEquals(Arrays.asList("Delta", "Alpha", "Bravo", "Charlie"), names(RoutineStore.list(context)));
    }

    @Test public void deletingAPinnedRoutineRemovesItAndItsPinnedMetadata() {
        RoutineStore.Routine keep = save("Keep");
        RoutineStore.Routine remove = save("Remove");
        assertTrue(RoutineStore.setPinned(context, remove.id, true));
        assertTrue(RoutineStore.setPinned(context, keep.id, true));
        assertTrue(storedJson().contains("\"pinned\""));

        assertTrue(RoutineStore.delete(context, remove.id));
        assertNull(RoutineStore.findById(context, remove.id));
        assertFalse(storedJson().contains(remove.id));
        assertEquals(Arrays.asList("Keep"), names(RoutineStore.list(context)));

        // The surviving pinned routine is untouched by the deletion.
        RoutineStore.Routine survivor = RoutineStore.findById(context, keep.id);
        assertNotNull(survivor);
        assertTrue(survivor.pinned);

        // Once the last pinned routine goes, no pinned metadata is left behind.
        assertTrue(RoutineStore.setPinned(context, keep.id, false));
        assertFalse(storedJson().contains("\"pinned\""));
    }

    @Test public void routineDataSavedBeforePinningLoadsAsUnpinned() throws Exception {
        // Exactly the shape Orbit wrote before pinned routines existed.
        JSONObject action = new JSONObject()
                .put("type", RoutineActionCatalog.FLASHLIGHT)
                .put("params", new JSONObject().put("on", true))
                .put("requiresConfirmation", false);
        JSONObject legacy = new JSONObject()
                .put("id", "legacy-routine-id")
                .put("name", "Legacy")
                .put("createdAt", 1000L)
                .put("updatedAt", 2000L)
                .put("lastRunAt", 3000L)
                .put("actions", new JSONArray().put(action));
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .edit().putString("routines_v1", new JSONArray().put(legacy).toString()).commit();

        List<RoutineStore.Routine> routines = RoutineStore.list(context);
        assertEquals(1, routines.size());
        RoutineStore.Routine loaded = routines.get(0);
        assertEquals("Legacy", loaded.name);
        assertFalse(loaded.pinned);
        assertEquals(1, loaded.actions.size());
        assertEquals(3000L, loaded.lastRunAt);

        // It can still be pinned afterwards without any migration step.
        assertTrue(RoutineStore.setPinned(context, "legacy-routine-id", true));
        RoutineStore.Routine pinned = RoutineStore.findById(context, "legacy-routine-id");
        assertNotNull(pinned);
        assertTrue(pinned.pinned);
        assertEquals(1, pinned.actions.size());
    }

    @Test public void backupAndRestoreRoundTripPreservesPinnedState() {
        save("Plain");
        RoutineStore.Routine pinned = save("Favorite");
        assertTrue(RoutineStore.setPinned(context, pinned.id, true));

        String backup = RoutineStore.backupJson(context);
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(RoutineStore.list(context).isEmpty());

        assertTrue(RoutineStore.restoreBackupJson(context, backup));
        assertEquals(Arrays.asList("Favorite", "Plain"), names(RoutineStore.list(context)));
        RoutineStore.Routine restored = RoutineStore.findById(context, pinned.id);
        assertNotNull(restored);
        assertTrue(restored.pinned);
    }

    @Test public void editingAPinnedRoutineKeepsItPinned() {
        RoutineStore.Routine routine = save("Gaming");
        assertTrue(RoutineStore.setPinned(context, routine.id, true));

        RoutineStore.Routine edited = RoutineStore.findById(context, routine.id)
                .withNameAndActions("Gaming Night", flashlightAction());
        assertTrue(edited.pinned);
        assertTrue(RoutineStore.upsert(context, edited));

        RoutineStore.Routine reloaded = RoutineStore.findById(context, routine.id);
        assertNotNull(reloaded);
        assertEquals("Gaming Night", reloaded.name);
        assertTrue(reloaded.pinned);
    }

    @Test public void runningAPinnedRoutineKeepsItPinned() {
        RoutineStore.Routine routine = save("Morning");
        assertTrue(RoutineStore.setPinned(context, routine.id, true));

        RoutineStore.markRun(context, routine.id);

        RoutineStore.Routine reloaded = RoutineStore.findById(context, routine.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.pinned);
        assertTrue(reloaded.lastRunAt > 0L);
    }

    @Test public void duplicatingDoesNotCopyPinnedState() {
        RoutineStore.Routine routine = save("Source");
        assertTrue(RoutineStore.setPinned(context, routine.id, true));

        RoutineStore.Routine copy = RoutineStore.create("Source Copy", routine.actions);
        assertTrue(RoutineStore.upsert(context, copy));

        RoutineStore.Routine storedCopy = RoutineStore.findById(context, copy.id);
        assertNotNull(storedCopy);
        assertFalse(storedCopy.pinned);
        assertEquals(Arrays.asList("Source", "Source Copy"), names(RoutineStore.list(context)));
    }

    @Test public void pinningAnUnknownRoutineFails() {
        save("Only");
        assertFalse(RoutineStore.setPinned(context, "missing-id", true));
        assertFalse(RoutineStore.setPinned(context, "", true));
        assertFalse(RoutineStore.setPinned(context, null, true));
    }
}

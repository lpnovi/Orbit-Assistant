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

/** Manual routine ordering: persistence, group behavior, and safety against the other features. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineStoreOrderTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("orbit_routine_history", Context.MODE_PRIVATE).edit().clear().commit();
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

    private List<String> displayedNames() {
        return names(RoutineStore.list(context));
    }

    private List<String> idsOf(RoutineStore.Routine... routines) {
        List<String> out = new ArrayList<>();
        for (RoutineStore.Routine routine : routines) out.add(routine.id);
        return out;
    }

    @Test public void manualOrderIsPersistedAndReReadFromStorage() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie"), displayedNames());

        assertTrue(RoutineStore.applyOrder(context, idsOf(c, a, b)));

        // list() re-reads SharedPreferences, the same path a relaunch takes.
        assertEquals(Arrays.asList("Charlie", "Alpha", "Bravo"), displayedNames());
    }

    @Test public void manualOrderWorksInsideThePinnedGroup() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        RoutineStore.Routine d = save("Delta");
        assertTrue(RoutineStore.setPinned(context, a.id, true));
        assertTrue(RoutineStore.setPinned(context, c.id, true));
        assertEquals(Arrays.asList("Alpha", "Charlie", "Bravo", "Delta"), displayedNames());

        // Reorder within the pinned group only; the unpinned tail is passed through unchanged.
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, a, b, d)));
        assertEquals(Arrays.asList("Charlie", "Alpha", "Bravo", "Delta"), displayedNames());
        assertTrue(RoutineStore.findById(context, c.id).pinned);
        assertTrue(RoutineStore.findById(context, a.id).pinned);
        assertFalse(RoutineStore.findById(context, b.id).pinned);
    }

    @Test public void manualOrderWorksInsideTheUnpinnedGroup() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        RoutineStore.Routine d = save("Delta");
        assertTrue(RoutineStore.setPinned(context, a.id, true));

        assertTrue(RoutineStore.applyOrder(context, idsOf(a, d, c, b)));
        assertEquals(Arrays.asList("Alpha", "Delta", "Charlie", "Bravo"), displayedNames());
        assertFalse(RoutineStore.findById(context, d.id).pinned);
    }

    @Test public void reorderingNeverChangesPinnedState() {
        RoutineStore.Routine pinned = save("Pinned");
        RoutineStore.Routine plain = save("Plain");
        assertTrue(RoutineStore.setPinned(context, pinned.id, true));

        // Even an order that puts the unpinned routine first cannot flip pinned state, and the
        // pinned-first view still wins on read.
        assertTrue(RoutineStore.applyOrder(context, idsOf(plain, pinned)));

        assertTrue(RoutineStore.findById(context, pinned.id).pinned);
        assertFalse(RoutineStore.findById(context, plain.id).pinned);
        assertEquals(Arrays.asList("Pinned", "Plain"), displayedNames());
    }

    @Test public void editingARoutineKeepsItsManualPosition() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, b, a)));

        RoutineStore.Routine edited = RoutineStore.findById(context, b.id)
                .withNameAndActions("Bravo Two", flashlightAction());
        assertTrue(RoutineStore.upsert(context, edited));

        assertEquals(Arrays.asList("Charlie", "Bravo Two", "Alpha"), displayedNames());
    }

    @Test public void runningARoutineKeepsItsManualPosition() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, b, a)));

        RoutineStore.markRun(context, a.id);
        RoutineStore.markRun(context, c.id);

        assertEquals(Arrays.asList("Charlie", "Bravo", "Alpha"), displayedNames());
        assertTrue(RoutineStore.findById(context, a.id).lastRunAt > 0L);
    }

    @Test public void recordingRunHistoryKeepsTheManualOrder() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        assertTrue(RoutineStore.applyOrder(context, idsOf(b, a)));
        String before = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");

        RoutineRunHistoryStore.record(context, a.id, a.name,
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");

        String after = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");
        assertEquals(before, after);
        assertEquals(Arrays.asList("Bravo", "Alpha"), displayedNames());
    }

    @Test public void deletingARoutineLeavesTheRemainingOrderIntact() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, a, b)));

        assertTrue(RoutineStore.delete(context, a.id));

        assertNull(RoutineStore.findById(context, a.id));
        assertEquals(Arrays.asList("Charlie", "Bravo"), displayedNames());
    }

    @Test public void newRoutinesStillAppendToTheEnd() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        assertTrue(RoutineStore.applyOrder(context, idsOf(b, a)));

        save("Charlie");

        assertEquals(Arrays.asList("Bravo", "Alpha", "Charlie"), displayedNames());
    }

    @Test public void applyOrderIgnoresUnknownIdsAndKeepsOmittedRoutines() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        save("Charlie");

        // A stale list that names a deleted routine and forgets Charlie must not drop anything.
        assertTrue(RoutineStore.applyOrder(context,
                Arrays.asList(b.id, "gone-routine-id", a.id)));

        assertEquals(Arrays.asList("Bravo", "Alpha", "Charlie"), displayedNames());
    }

    @Test public void applyOrderIsSafeAgainstDuplicateIdsAndEmptyInput() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");

        assertTrue(RoutineStore.applyOrder(context, Arrays.asList(b.id, b.id, a.id)));
        assertEquals(Arrays.asList("Bravo", "Alpha"), displayedNames());

        assertFalse(RoutineStore.applyOrder(context, null));
        assertEquals(Arrays.asList("Bravo", "Alpha"), displayedNames());
    }

    @Test public void reorderingPreservesEveryStoredRoutineField() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        assertTrue(RoutineStore.setPinned(context, b.id, true));
        RoutineStore.markRun(context, b.id);
        RoutineStore.Routine beforeMove = RoutineStore.findById(context, b.id);

        assertTrue(RoutineStore.applyOrder(context, idsOf(b, a)));

        RoutineStore.Routine afterMove = RoutineStore.findById(context, b.id);
        assertNotNull(afterMove);
        assertEquals(beforeMove.name, afterMove.name);
        assertEquals(beforeMove.createdAt, afterMove.createdAt);
        assertEquals(beforeMove.updatedAt, afterMove.updatedAt);
        assertEquals(beforeMove.lastRunAt, afterMove.lastRunAt);
        assertEquals(beforeMove.actions.size(), afterMove.actions.size());
        assertTrue(afterMove.pinned);
    }

    @Test public void manualOrderTravelsThroughBackupAndRestore() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertTrue(RoutineStore.setPinned(context, c.id, true));
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, b, a)));
        assertEquals(Arrays.asList("Charlie", "Bravo", "Alpha"), displayedNames());

        String backup = RoutineStore.backupJson(context);
        context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE).edit().clear().commit();
        assertTrue(RoutineStore.list(context).isEmpty());

        assertTrue(RoutineStore.restoreBackupJson(context, backup));
        assertEquals(Arrays.asList("Charlie", "Bravo", "Alpha"), displayedNames());
        assertTrue(RoutineStore.findById(context, c.id).pinned);
    }

    @Test public void routineDataFromBeforeManualOrderingRestoresInItsStoredOrder() throws Exception {
        // An older backup carries no ordering information of its own; the array order is the order.
        JSONArray legacy = new JSONArray();
        String[] storedNames = {"Older One", "Older Two", "Older Three"};
        for (int i = 0; i < storedNames.length; i++) {
            JSONObject action = new JSONObject()
                    .put("type", RoutineActionCatalog.FLASHLIGHT)
                    .put("params", new JSONObject().put("on", true))
                    .put("requiresConfirmation", false);
            legacy.put(new JSONObject()
                    .put("id", "legacy-" + i)
                    .put("name", storedNames[i])
                    .put("createdAt", 1000L + i)
                    .put("updatedAt", 2000L + i)
                    .put("lastRunAt", 0L)
                    .put("actions", new JSONArray().put(action)));
        }
        assertTrue(RoutineStore.restoreBackupJson(context, legacy.toString()));

        assertEquals(Arrays.asList("Older One", "Older Two", "Older Three"), displayedNames());
        for (RoutineStore.Routine routine : RoutineStore.list(context)) assertFalse(routine.pinned);

        // And it can be reordered afterwards with no migration step.
        assertTrue(RoutineStore.applyOrder(context,
                Arrays.asList("legacy-2", "legacy-0", "legacy-1")));
        assertEquals(Arrays.asList("Older Three", "Older One", "Older Two"), displayedNames());
    }

    @Test public void pinningKeepsManualOrderInsideBothGroups() {
        RoutineStore.Routine a = save("Alpha");
        RoutineStore.Routine b = save("Bravo");
        RoutineStore.Routine c = save("Charlie");
        assertTrue(RoutineStore.applyOrder(context, idsOf(c, b, a)));

        // Pinning Bravo lifts only Bravo; Charlie and Alpha keep their relative order below it.
        assertTrue(RoutineStore.setPinned(context, b.id, true));
        assertEquals(Arrays.asList("Bravo", "Charlie", "Alpha"), displayedNames());

        // Unpinning returns it to its manual position rather than to the end of the list.
        assertTrue(RoutineStore.setPinned(context, b.id, false));
        assertEquals(Arrays.asList("Charlie", "Bravo", "Alpha"), displayedNames());
    }
}

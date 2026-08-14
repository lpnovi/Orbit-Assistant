package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** Routine run history recording, persistence, bounds, clearing, and routine-data safety. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutineRunHistoryStoreTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("orbit_routine_history", Context.MODE_PRIVATE).edit().clear().commit();
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

    private static AssistantReply.Action extensionAction() throws Exception {
        JSONObject params = new JSONObject()
                .put("extensionId", "orbit.discord.webhook")
                .put("actionId", "send")
                .put("extensionName", "Discord Webhook")
                .put("actionName", "Discord Webhook")
                .put("actionParameters", new JSONObject().put("message", "deploy finished"));
        return new AssistantReply.Action(RoutineActionCatalog.EXTENSION_ACTION, params, false);
    }

    private String storedJson() {
        return context.getSharedPreferences("orbit_routine_history", Context.MODE_PRIVATE)
                .getString("runs_v1", "[]");
    }

    @Test public void recordsASuccessfulRun() {
        RoutineRunHistoryStore.record(context, "r1", "Bedtime",
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 3, 3, -1, null, "");

        List<RoutineRunHistoryStore.Entry> entries = RoutineRunHistoryStore.list(context);
        assertEquals(1, entries.size());
        RoutineRunHistoryStore.Entry entry = entries.get(0);
        assertEquals("Bedtime", entry.routineName);
        assertTrue(entry.success);
        assertEquals(3, entry.completedSteps);
        assertEquals(3, entry.totalSteps);
        assertEquals(0, entry.failedStep);
        assertEquals("Completed 3 of 3 steps", entry.headline());
        assertEquals("Run manually", entry.sourceLabel());
        assertTrue(entry.runAt > 0L);
    }

    @Test public void recordsAFailedRunWithTheActionAndReason() throws Exception {
        RoutineRunHistoryStore.record(context, "r2", "Deploy alert",
                RoutineRunHistoryStore.SOURCE_TRIGGER, false, 1, 3, 1, extensionAction(),
                "Extension request could not be completed.");

        RoutineRunHistoryStore.Entry entry = RoutineRunHistoryStore.list(context).get(0);
        assertFalse(entry.success);
        assertEquals(2, entry.failedStep);
        assertEquals("Discord Webhook", entry.failedAction);
        assertEquals("Failed at action 2: Discord Webhook", entry.headline());
        assertEquals("Extension request could not be completed.", entry.reason);
        assertEquals("Automatic trigger", entry.sourceLabel());
    }

    @Test public void doesNotStoreExtensionParametersOrConfiguration() throws Exception {
        RoutineRunHistoryStore.record(context, "r3", "Deploy alert",
                RoutineRunHistoryStore.SOURCE_WIDGET, false, 0, 1, 0, extensionAction(),
                "Extension request could not be completed.");

        String raw = storedJson();
        assertTrue(raw.contains("Discord Webhook"));
        // Nothing from the action's configured parameters may reach stored history.
        assertFalse(raw.contains("deploy finished"));
        assertFalse(raw.contains("actionParameters"));
        assertFalse(raw.contains("orbit.discord.webhook"));
    }

    @Test public void historyPersistsAndIsNewestFirst() {
        RoutineRunHistoryStore.record(context, "r1", "First",
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");
        RoutineRunHistoryStore.record(context, "r2", "Second",
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");
        RoutineRunHistoryStore.record(context, "r3", "Third",
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");

        // list() re-reads SharedPreferences, the same path a relaunch takes.
        List<RoutineRunHistoryStore.Entry> entries = RoutineRunHistoryStore.list(context);
        assertEquals(3, entries.size());
        assertEquals("Third", entries.get(0).routineName);
        assertEquals("Second", entries.get(1).routineName);
        assertEquals("First", entries.get(2).routineName);
    }

    @Test public void historyIsBoundedToTheRetentionLimit() {
        int overflow = RoutineRunHistoryStore.MAX_ENTRIES + 15;
        for (int i = 0; i < overflow; i++) {
            RoutineRunHistoryStore.record(context, "r" + i, "Run " + i,
                    RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");
        }
        List<RoutineRunHistoryStore.Entry> entries = RoutineRunHistoryStore.list(context);
        assertEquals(RoutineRunHistoryStore.MAX_ENTRIES, entries.size());
        // The newest survives and the oldest is dropped.
        assertEquals("Run " + (overflow - 1), entries.get(0).routineName);
        assertEquals("Run " + (overflow - RoutineRunHistoryStore.MAX_ENTRIES),
                entries.get(entries.size() - 1).routineName);
    }

    @Test public void clearingHistoryLeavesRoutinesPinsAndTriggersAlone() {
        RoutineStore.Routine routine = RoutineStore.create("Gaming", flashlightAction());
        assertTrue(RoutineStore.upsert(context, routine));
        assertTrue(RoutineStore.setPinned(context, routine.id, true));
        RoutineRunHistoryStore.record(context, routine.id, routine.name,
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");
        assertFalse(RoutineRunHistoryStore.isEmpty(context));

        assertTrue(RoutineRunHistoryStore.clear(context));

        assertTrue(RoutineRunHistoryStore.isEmpty(context));
        assertTrue(RoutineRunHistoryStore.list(context).isEmpty());
        RoutineStore.Routine survivor = RoutineStore.findById(context, routine.id);
        assertNotNull(survivor);
        assertEquals("Gaming", survivor.name);
        assertTrue(survivor.pinned);
        assertEquals(1, survivor.actions.size());
    }

    @Test public void recordingARunDoesNotChangeRoutineDataOrPinnedState() {
        RoutineStore.Routine pinned = RoutineStore.create("Pinned", flashlightAction());
        assertTrue(RoutineStore.upsert(context, pinned));
        RoutineStore.Routine plain = RoutineStore.create("Plain", flashlightAction());
        assertTrue(RoutineStore.upsert(context, plain));
        assertTrue(RoutineStore.setPinned(context, pinned.id, true));
        String routinesBefore = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");

        RoutineRunHistoryStore.record(context, pinned.id, pinned.name,
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");

        String routinesAfter = context.getSharedPreferences("orbit_routines", Context.MODE_PRIVATE)
                .getString("routines_v1", "[]");
        assertEquals(routinesBefore, routinesAfter);

        List<RoutineStore.Routine> routines = RoutineStore.list(context);
        assertEquals(2, routines.size());
        assertEquals("Pinned", routines.get(0).name);
        assertTrue(routines.get(0).pinned);
        assertEquals("Plain", routines.get(1).name);
        assertFalse(routines.get(1).pinned);
    }

    @Test public void deletingARoutineKeepsItsRecordedRunsReadable() {
        RoutineStore.Routine routine = RoutineStore.create("Temporary", flashlightAction());
        assertTrue(RoutineStore.upsert(context, routine));
        RoutineRunHistoryStore.record(context, routine.id, routine.name,
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");

        assertTrue(RoutineStore.delete(context, routine.id));

        // The snapshot name keeps history meaningful after the routine is gone.
        List<RoutineRunHistoryStore.Entry> entries = RoutineRunHistoryStore.list(context);
        assertEquals(1, entries.size());
        assertEquals("Temporary", entries.get(0).routineName);
    }

    @Test public void damagedHistoryDataIsIgnoredRatherThanCrashing() {
        context.getSharedPreferences("orbit_routine_history", Context.MODE_PRIVATE)
                .edit().putString("runs_v1", "{not valid json").commit();
        assertTrue(RoutineRunHistoryStore.list(context).isEmpty());

        RoutineRunHistoryStore.record(context, "r1", "Recovered",
                RoutineRunHistoryStore.SOURCE_MANUAL, true, 1, 1, -1, null, "");
        assertEquals(1, RoutineRunHistoryStore.list(context).size());
    }

    @Test public void longFailureReasonsAreTrimmedForTheUi() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 60; i++) huge.append("failure detail ");
        RoutineRunHistoryStore.record(context, "r1", "Noisy",
                RoutineRunHistoryStore.SOURCE_MANUAL, false, 0, 2, 0, null, huge.toString());

        RoutineRunHistoryStore.Entry entry = RoutineRunHistoryStore.list(context).get(0);
        assertTrue(entry.reason.length() <= 161);
        assertTrue(entry.reason.endsWith("…"));
    }
}

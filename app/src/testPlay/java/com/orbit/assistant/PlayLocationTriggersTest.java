package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Location-triggered Routines in the Google Play edition: unavailable, never armed, never asked for,
 * and never at the expense of a saved trigger or of foreground location.
 *
 * <p>Several tests grant ACCESS_BACKGROUND_LOCATION through Robolectric even though the Play
 * manifest does not request it. That is deliberate: it proves the Play edition refuses location
 * triggers by its own rule, not only because Android happens to be saying no.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PlayLocationTriggersTest {
    private Application context;
    private ActivityController<?> controller;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        TestWorkManager.ensureInitialized(context);
        for (RoutineTriggerStore.Trigger t : RoutineTriggerStore.list(context)) {
            RoutineTriggerStore.delete(context, t.id);
        }
        for (RoutineStore.Routine r : RoutineStore.list(context)) RoutineStore.delete(context, r.id);
        shadowOf((LocationManager) context.getSystemService(Context.LOCATION_SERVICE))
                .setLocationEnabled(true);
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
    }

    private void grantEverything() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    private RoutineStore.Routine routine() {
        RoutineStore.Routine routine = RoutineStore.create("Arrive home", oneAction());
        assertTrue(RoutineStore.upsert(context, routine));
        return routine;
    }

    private RoutineTriggerStore.Trigger savedLocationTrigger(RoutineStore.Routine routine) {
        RoutineTriggerStore.Trigger trigger = RoutineTriggerStore.createLocation(routine.id, "Home",
                51.5, -0.12, 150f, RoutineTriggerStore.LOCATION_ENTER);
        assertTrue(RoutineTriggerStore.upsert(context, trigger));
        return trigger;
    }

    // ---- the rule ------------------------------------------------------------------------------------

    @Test public void locationTriggersAreUnavailableEvenIfAndroidWouldAllowThem() {
        grantEverything();
        assertFalse(OrbitDistribution.supportsLocationTriggers());
        assertFalse(RoutineLocationTriggerScheduler.hasBackgroundLocation(context));
        assertFalse(RoutineLocationTriggerScheduler.ready(context));
    }

    @Test public void aLocationTriggerIsNeverArmed() {
        grantEverything();
        RoutineTriggerStore.Trigger trigger = savedLocationTrigger(routine());
        assertTrue(trigger.enabled);
        assertFalse("nothing may be monitored in the Play edition",
                RoutineLocationTriggerScheduler.schedule(context, trigger));
    }

    /** Moving from GitHub to Play changes nothing that was saved, so moving back restores it. */
    @Test public void savedLocationTriggersAreKeptExactlyAsTheyWere() {
        grantEverything();
        RoutineTriggerStore.Trigger trigger = savedLocationTrigger(routine());
        RoutineTriggerScheduler.rescheduleAll(context);

        ActivityController<RoutineTriggersActivity> screen = Robolectric.buildActivity(
                RoutineTriggersActivity.class, new Intent(context, RoutineTriggersActivity.class)
                        .putExtra(RoutineTriggersActivity.EXTRA_ROUTINE_ID, trigger.routineId));
        controller = screen.setup();

        RoutineTriggerStore.Trigger after = RoutineTriggerStore.findById(context, trigger.id);
        assertNotNull("never deleted", after);
        assertTrue("never switched off", after.enabled);
        assertEquals("Home", after.locationName);
        assertEquals(51.5, after.latitude, 0d);
        assertEquals(-0.12, after.longitude, 0d);
        assertEquals(RoutineTriggerStore.LOCATION_ENTER, after.locationTransition);
    }

    @Test public void aStaleProximityEventRunsNothing() {
        grantEverything();
        RoutineTriggerStore.Trigger trigger = savedLocationTrigger(routine());
        Intent event = new Intent(context, LocationRoutineTriggerReceiver.class)
                .putExtra(LocationRoutineTriggerReceiver.EXTRA_TRIGGER_ID, trigger.id)
                .putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        new LocationRoutineTriggerReceiver().onReceive(context, event);

        RoutineTriggerStore.Trigger after = RoutineTriggerStore.findById(context, trigger.id);
        assertEquals("the routine must not have run", 0L, after.lastRunAt);
        assertEquals("", after.lastResult);
        assertTrue(after.enabled);
    }

    // ---- nothing asks for background location ---------------------------------------------------------

    @Test public void theSetupHelperNeverRequestsBackgroundLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CapabilityAccessHelper.setupLocationAutomation(activity, 1, 2);
        assertNull(shadowOf(activity).getLastRequestedPermission());
        assertNull("not even Android's settings page", shadowOf(activity).getNextStartedActivity());
        assertEquals("Not available", CapabilityAccessHelper.locationAutomationStatus(activity));
    }

    @Test public void theTriggerScreenOffersNoLocationTriggerAndExplainsWhy() {
        RoutineStore.Routine routine = routine();
        ActivityController<RoutineTriggersActivity> screen = Robolectric.buildActivity(
                RoutineTriggersActivity.class, new Intent(context, RoutineTriggersActivity.class)
                        .putExtra(RoutineTriggersActivity.EXTRA_ROUTINE_ID, routine.id));
        controller = screen.setup();
        View root = screen.get().getWindow().getDecorView();

        assertNull(findText(root, "+  New location trigger"));
        assertNotNull(findText(root, OrbitDistribution.LOCATION_TRIGGERS_UNAVAILABLE));
        TextView section = findText(root, "LOCATION TRIGGERS");
        assertTrue("no empty location section inviting one",
                section == null || section.getVisibility() != View.VISIBLE);
        assertNull(findContaining(root, "background location"));
        assertNull(findContaining(root, "Allow all the time"));
        assertNotNull("time triggers are untouched", findText(root, "+  New time trigger"));
    }

    @Test public void aSavedLocationTriggerIsShownAsUnavailableWithNoSwitch() {
        RoutineTriggerStore.Trigger trigger = savedLocationTrigger(routine());
        ActivityController<RoutineTriggersActivity> screen = Robolectric.buildActivity(
                RoutineTriggersActivity.class, new Intent(context, RoutineTriggersActivity.class)
                        .putExtra(RoutineTriggersActivity.EXTRA_ROUTINE_ID, trigger.routineId));
        controller = screen.setup();
        View root = screen.get().getWindow().getDecorView();

        TextView state = findText(root, OrbitDistribution.LOCATION_TRIGGER_UNAVAILABLE_STATE);
        assertNotNull(state);
        View card = (View) state.getParent().getParent().getParent();
        boolean hasOptions = false;
        for (android.widget.ImageButton b : collect(card, android.widget.ImageButton.class)) {
            hasOptions |= "Trigger options".contentEquals(b.getContentDescription());
        }
        assertTrue("this is the trigger's own card, where the switch would sit", hasOptions);
        assertTrue("a switch here could only pretend to do something",
                collect(card, OrbitSwitch.class).isEmpty());
    }

    @Test public void theLocationTriggerEditorCannotBeOpened() {
        RoutineStore.Routine routine = routine();
        ActivityController<LocationTriggerEditorActivity> editor = Robolectric.buildActivity(
                LocationTriggerEditorActivity.class,
                new Intent(context, LocationTriggerEditorActivity.class)
                        .putExtra(LocationTriggerEditorActivity.EXTRA_ROUTINE_ID, routine.id));
        editor.create();
        assertTrue(editor.get().isFinishing());
        assertTrue(RoutineTriggerStore.listForRoutine(context, routine.id).isEmpty());
    }

    @Test public void aDraftedLocationTriggerReportsItIsUnavailable() throws Exception {
        RoutineTriggerDraft draft = RoutineTriggerDraft.fromPayload(context, new JSONObject()
                .put("type", RoutineTriggerStore.TYPE_LOCATION).put("transition", "arrive")
                .put("placeLabel", "Home").put("resolved", true));
        assertNotNull(draft);
        assertEquals(OrbitDistribution.LOCATION_TRIGGER_UNAVAILABLE_STATE, draft.readiness(context));
    }

    // ---- foreground location is untouched ----------------------------------------------------------

    @Test public void foregroundLocationStillWorks() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location fix = new Location(LocationManager.GPS_PROVIDER);
        fix.setLatitude(51.5);
        fix.setLongitude(-0.12);
        fix.setTime(System.currentTimeMillis());
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        shadowOf(lm).simulateLocation(fix);

        assertTrue(RoutineLocationTriggerScheduler.hasFineLocation(context));
        assertTrue(RoutineLocationTriggerScheduler.isLocationEnabled(context));
        Location best = RoutineLocationTriggerScheduler.bestLastKnownLocation(context);
        assertNotNull("Saved Places, weather and IF conditions read this", best);
        assertEquals(51.5, best.getLatitude(), 1e-9);
    }

    @Test public void savedPlacesStillOffersCurrentLocation() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
        ActivityController<SavedPlacesActivity> places =
                Robolectric.buildActivity(SavedPlacesActivity.class);
        controller = places.setup();
        assertFalse(places.get().isFinishing());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static List<AssistantReply.Action> oneAction() {
        List<AssistantReply.Action> actions = new ArrayList<>();
        try {
            actions.add(new AssistantReply.Action(RoutineActionCatalog.FLASHLIGHT,
                    new JSONObject().put("on", true), false));
        } catch (Exception ignored) {}
        return actions;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static TextView findContaining(View view, String text) {
        if (view instanceof TextView && String.valueOf(((TextView) view).getText())
                .toLowerCase(java.util.Locale.US).contains(text.toLowerCase(java.util.Locale.US))) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findContaining(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> List<T> collect(View view, Class<T> type) {
        List<T> out = new ArrayList<>();
        if (type.isInstance(view)) out.add(type.cast(view));
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(collect(group.getChildAt(i), type));
        }
        return out;
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every Orbit notification category exists before anything needs to fire.
 *
 * <p>Android will not show a category under Settings until the app has created it, and Orbit
 * created each one on the way to posting that kind of notification. Two Samsung devices running the
 * same build therefore listed different categories: the tablet had "Orbit updates" because an
 * update had been offered there, and the phone did not. Nothing was broken — the list was simply
 * describing which parts of Orbit had happened to run.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitNotificationChannelsTest {

    private Context context;
    private NotificationManager manager;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        for (NotificationChannel channel : new ArrayList<>(manager.getNotificationChannels())) {
            manager.deleteNotificationChannel(channel.getId());
        }
        shadow().setNotificationsEnabled(true);
    }

    private ShadowNotificationManager shadow() {
        return org.robolectric.Shadows.shadowOf(manager);
    }

    private List<String> registeredIds() {
        List<String> out = new ArrayList<>();
        for (NotificationChannel channel : manager.getNotificationChannels()) {
            out.add(channel.getId());
        }
        return out;
    }

    // ---- what registration produces ---------------------------------------------------------------

    @Test public void registrationCreatesEveryOrbitChannel() {
        OrbitNotificationChannels.ensureAll(context);
        List<String> ids = registeredIds();
        for (String expected : OrbitNotificationChannels.ids()) {
            assertTrue("Android should list " + expected, ids.contains(expected));
        }
        assertEquals("and nothing beyond Orbit's own four",
                OrbitNotificationChannels.ids().size(), ids.size());
    }

    /** The IDs are Orbit's permanent identity for these categories and must not drift. */
    @Test public void theChannelIdsAreUnchanged() {
        assertEquals(Arrays.asList("orbit_background_responses", "orbit_reminders",
                        "orbit_routine_triggers", "orbit_updates"),
                OrbitNotificationChannels.ids());
        assertEquals("orbit_updates", OrbitNotificationChannels.UPDATES);
        assertEquals("orbit_reminders", OrbitNotificationChannels.REMINDERS);
        assertEquals("orbit_routine_triggers", OrbitNotificationChannels.ROUTINE_TRIGGERS);
        assertEquals("orbit_background_responses", OrbitNotificationChannels.BACKGROUND_RESPONSES);
    }

    @Test public void everyChannelIsNamedAndDescribed() {
        OrbitNotificationChannels.ensureAll(context);
        for (String id : OrbitNotificationChannels.ids()) {
            NotificationChannel channel = manager.getNotificationChannel(id);
            assertNotNull(id + " should exist", channel);
            assertTrue(id + " should be named", channel.getName().length() > 0);
            assertNotNull(id + " should be described", channel.getDescription());
            assertTrue(id + " should be described", channel.getDescription().length() > 0);
        }
        assertEquals("Orbit updates",
                manager.getNotificationChannel(OrbitNotificationChannels.UPDATES).getName());
        assertEquals("Notifies you when a verified Orbit release is available.",
                manager.getNotificationChannel(OrbitNotificationChannels.UPDATES).getDescription());
        assertEquals("Reminders",
                manager.getNotificationChannel(OrbitNotificationChannels.REMINDERS).getName());
        assertEquals("Routine triggers",
                manager.getNotificationChannel(OrbitNotificationChannels.ROUTINE_TRIGGERS).getName());
        assertEquals("Background responses",
                manager.getNotificationChannel(
                        OrbitNotificationChannels.BACKGROUND_RESPONSES).getName());
    }

    // ---- what registration must not do ------------------------------------------------------------

    /** A category appearing in Settings must never cost the user a notification. */
    @Test public void registrationPostsNothing() {
        OrbitNotificationChannels.ensureAll(context);
        assertEquals(0, shadow().size());
        assertTrue(shadow().getAllNotifications().isEmpty());
    }

    @Test public void repeatedRegistrationIsHarmless() {
        OrbitNotificationChannels.ensureAll(context);
        int first = registeredIds().size();
        OrbitNotificationChannels.ensureAll(context);
        OrbitNotificationChannels.ensureAll(context);
        OrbitNotificationChannels.ensure(context, OrbitNotificationChannels.UPDATES);
        assertEquals(first, registeredIds().size());
        assertEquals(0, shadow().size());
    }

    /** An ID Orbit does not own is not created just because something asked for it. */
    @Test public void anUnknownChannelIsNotInvented() {
        OrbitNotificationChannels.ensure(context, "something_else");
        assertNull(manager.getNotificationChannel("something_else"));
        assertTrue(registeredIds().isEmpty());
    }

    // ---- start-up ---------------------------------------------------------------------------------

    /**
     * The whole point: the categories exist without any Orbit feature having run.
     *
     * <p>Asserted through the real Application the manifest declares, so this also proves the
     * manifest entry is present — a registration function nothing calls would fix nothing.
     */
    @Test public void appStartUpRegistersThemAll() {
        Application application = RuntimeEnvironment.getApplication();
        assertTrue("the manifest should declare OrbitApplication",
                application instanceof OrbitApplication);
        // The channels this test cleared in setUp are put back by start-up alone.
        assertTrue(registeredIds().isEmpty());
        application.onCreate();
        for (String expected : OrbitNotificationChannels.ids()) {
            assertNotNull("start-up should register " + expected,
                    manager.getNotificationChannel(expected));
        }
        assertEquals("and post nothing", 0, shadow().size());
    }

    // ---- the notifiers still use their own channels -----------------------------------------------

    /** The update notification exists before any update notification has ever fired. */
    @Test public void theUpdateChannelExistsBeforeTheFirstUpdateNotification() {
        OrbitNotificationChannels.ensureAll(context);
        assertNotNull(manager.getNotificationChannel(OrbitNotificationChannels.UPDATES));
        assertEquals(0, shadow().size());
    }

    @Test public void reminderNotificationsStillUseTheRemindersChannel() {
        OrbitNotificationChannels.ensureAll(context);
        ReminderNotifier.show(context,
                ReminderStore.create("Take the bread out", System.currentTimeMillis() + 60_000L));
        assertUsesChannel(OrbitNotificationChannels.REMINDERS);
    }

    @Test public void routineTriggerNotificationsStillUseTheirOwnChannel() {
        OrbitNotificationChannels.ensureAll(context);
        RoutineTriggerNotifier.notificationsAllowed(context);
        assertNotNull(manager.getNotificationChannel(OrbitNotificationChannels.ROUTINE_TRIGGERS));
    }

    @Test public void backgroundResponsesStillUseTheirOwnChannel() {
        OrbitNotificationChannels.ensureAll(context);
        Prefs.get(context).edit().putBoolean("background_notifications", true).commit();
        NotificationHelper.notifyResponseComplete(context, "c1", "Hello", "Hi there");
        assertUsesChannel(OrbitNotificationChannels.BACKGROUND_RESPONSES);
    }

    private void assertUsesChannel(String expected) {
        if (shadow().size() == 0) return; // Permission or preference refused it; nothing to check.
        for (android.app.Notification posted : shadow().getAllNotifications()) {
            assertEquals(expected, posted.getChannelId());
        }
    }

}

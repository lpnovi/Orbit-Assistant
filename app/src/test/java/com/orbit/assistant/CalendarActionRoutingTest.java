package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.CalendarContract;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Where {@code ADD_CALENDAR_EVENTS} has to be known about, and where it must not be.
 *
 * <p>Adding an action type is easy to do incompletely: a provider can be told it may return an
 * action its schema never described, or a schema can describe one Orbit cannot execute. This walks
 * every place that enumerates Orbit's action vocabulary and checks the new action is either present
 * or deliberately absent, with the deliberate absences named.
 *
 * <p>It also pins the distinction the whole release rests on: {@code CREATE_EVENT} still opens
 * Android's composer, and did not quietly become the direct writer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class CalendarActionRoutingTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        drainStartedActivities();
    }

    private static Path repositoryRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path path = start; path != null; path = path.getParent()) {
            if (Files.isRegularFile(path.resolve("settings.gradle"))) return path;
        }
        throw new AssertionError("repository root was not found above " + start);
    }

    private static String read(String relativePath) {
        Path file = repositoryRoot().resolve(relativePath);
        if (!Files.isRegularFile(file)) fail("expected file is missing: " + relativePath);
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }

    private ShadowApplication shadowApp() {
        return Shadows.shadowOf((Application) RuntimeEnvironment.getApplication());
    }

    private Intent drainStartedActivities() {
        Intent last = null;
        Intent started;
        while ((started = shadowApp().getNextStartedActivity()) != null) last = started;
        return last;
    }

    // ---- CREATE_EVENT is untouched --------------------------------------------------------------

    /** The useful behaviour Orbit already had: open Android's composer and let the user save. */
    @Test public void createEventStillOpensTheAndroidComposer() throws Exception {
        ComponentName calendarApp = new ComponentName("com.orbit.test.calendar",
                "com.orbit.test.calendar.Insert");
        ShadowPackageManager packages = Shadows.shadowOf(context.getPackageManager());
        packages.addActivityIfNotPresent(calendarApp);
        IntentFilter filter = new IntentFilter(Intent.ACTION_INSERT);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.addDataScheme(CalendarContract.Events.CONTENT_URI.getScheme());
        filter.addDataAuthority(CalendarContract.AUTHORITY, null);
        packages.addIntentFilterForActivity(calendarApp, filter);

        JSONObject params = new JSONObject().put("title", "Dentist")
                .put("beginMillis", 1_800_000_000_000L).put("endMillis", 1_800_003_600_000L);
        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context,
                new AssistantReply.Action("CREATE_EVENT", params, false));

        assertTrue(result.message, result.success);
        assertEquals("Calendar event composer opened", result.message);

        Intent started = drainStartedActivities();
        assertNotNull("CREATE_EVENT must still start Android's composer", started);
        assertEquals(Intent.ACTION_INSERT, started.getAction());
        assertEquals(CalendarContract.Events.CONTENT_URI, started.getData());
        assertEquals("Dentist", started.getStringExtra(CalendarContract.Events.TITLE));
    }

    /** And the direct writer never opens a composer, whatever else it does. */
    @Test public void theDirectWriterNeverOpensAComposer() throws Exception {
        FakeCalendarProvider provider = new FakeCalendarProvider();
        provider.calendars.add(new FakeCalendarProvider.Calendar(1L, "Personal",
                "me@example.com", CalendarContract.Calendars.CAL_ACCESS_OWNER, true));
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, provider);
        shadowApp().grantPermissions(Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR);
        OrbitCalendarStore.forgetTarget(context);

        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context,
                new AssistantReply.Action(CalendarActionExecutor.ACTION_TYPE,
                        twelveGames(), true));

        assertTrue(result.message, result.success);
        assertEquals(12, provider.eventsIn(1L).size());
        assertEquals("twelve composer windows is exactly what this replaces",
                null, drainStartedActivities());
    }

    private static JSONObject twelveGames() throws Exception {
        JSONArray events = new JSONArray();
        for (int i = 0; i < 12; i++) {
            events.put(new JSONObject()
                    .put("title", "Game " + (i + 1))
                    .put("date", String.format("2026-09-%02d", i + 1))
                    .put("hour", 12).put("minute", 0)
                    .put("timezone", "America/Detroit"));
        }
        return new JSONObject().put("events", events);
    }

    // ---- routing ---------------------------------------------------------------------------------

    @Test public void theExecutorRoutesTheNewActionAndStillRecognisesIt() {
        assertTrue(CalendarActionExecutor.isCalendarWrite(
                new AssistantReply.Action("ADD_CALENDAR_EVENTS", null, true)));
        assertTrue("action types are matched case-insensitively everywhere else too",
                CalendarActionExecutor.isCalendarWrite(
                        new AssistantReply.Action("add_calendar_events", null, true)));
        assertFalse(CalendarActionExecutor.isCalendarWrite(
                new AssistantReply.Action("CREATE_EVENT", null, true)));
        assertFalse(CalendarActionExecutor.isCalendarWrite(null));

        String executor = read("app/src/main/java/com/orbit/assistant/DeviceActionExecutor.java");
        assertTrue("DeviceActionExecutor must route the action",
                executor.contains("case \"ADD_CALENDAR_EVENTS\""));
        assertTrue("and stay a routing layer rather than owning the implementation",
                executor.contains("CalendarActionExecutor.execute(c, p)"));
        assertFalse("calendar provider work does not belong in the switch",
                executor.contains("CalendarContract.Events.CALENDAR_ID"));
    }

    /** An unknown action must still be refused, so the vocabulary stays closed. */
    @Test public void anInventedCalendarActionIsStillUnsupported() {
        DeviceActionExecutor.Result result = DeviceActionExecutor.executeDetailed(context,
                new AssistantReply.Action("DELETE_CALENDAR_EVENTS", new JSONObject(), true));
        assertEquals(DeviceActionExecutor.STATUS_UNAVAILABLE, result.status);
    }

    // ---- every schema that enumerates actions ------------------------------------------------------

    @Test public void everyProviderSchemaKnowsTheAction() {
        String chatGpt = read("app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue("the ChatGPT allowlist must accept it",
                chatGpt.contains("\"ADD_CALENDAR_EVENTS\""));
        assertTrue("and the prompt must describe its parameters",
                chatGpt.contains("ADD_CALENDAR_EVENTS {events:"));

        String relay = read("app/src/main/java/com/orbit/assistant/RelayProvider.java");
        assertTrue("the relay must announce it as a capability",
                relay.contains("\"ADD_CALENDAR_EVENTS\""));

        String server = read("server/app.py");
        assertTrue("the relay server prompt must describe it",
                server.contains("ADD_CALENDAR_EVENTS"));
        assertTrue("and the server still filters to announced capabilities only",
                server.contains("a.type in announced"));
    }

    @Test public void theManifestDeclaresBothCalendarPermissions() {
        String manifest = read("app/src/main/AndroidManifest.xml");
        assertTrue(manifest.contains(
                "<uses-permission android:name=\"android.permission.READ_CALENDAR\" />"));
        assertTrue(manifest.contains(
                "<uses-permission android:name=\"android.permission.WRITE_CALENDAR\" />"));
        assertTrue("the permission bridge must be declared",
                manifest.contains("android:name=\".CalendarPermissionActivity\""));
    }

    // ---- deliberate absences ------------------------------------------------------------------------

    /**
     * Orbit Local cannot produce device actions, and this release does not change that. Declaring
     * otherwise would make it claim a calendar write it has no way to request.
     */
    @Test public void orbitLocalStillDeclaresNoDeviceActions() {
        AiProvider local = AiProviders.byId(Prefs.PROVIDER_LOCAL);
        assertFalse("Orbit Local must stay truthful about device actions",
                local.capabilities().deviceActions);
        String source = read("app/src/main/java/com/orbit/assistant/OrbitLocalProvider.java");
        assertFalse("and its prompt must not describe calendar actions",
                source.contains("ADD_CALENDAR_EVENTS"));
    }

    /**
     * Routines replay saved actions later, without the user present. A calendar write is a
     * persistent outbound action, so it stays outside the routine catalog for the same reason
     * calls, messages, and CREATE_EVENT already do.
     */
    @Test public void calendarWritesAreNotReplayableRoutineSteps() {
        assertFalse(RoutineActionCatalog.isSupported(CalendarActionExecutor.ACTION_TYPE));
        assertFalse("CREATE_EVENT was never a routine step either",
                RoutineActionCatalog.isSupported("CREATE_EVENT"));
    }

    // ---- truthfulness in the prompts ------------------------------------------------------------------

    /**
     * The failure this release exists for: confident text saying the games were being added while
     * nothing was written. The prompts now state that the model does not perform the write and
     * cannot report its outcome.
     */
    @Test public void thePromptsForbidClaimingACalendarWriteSucceeded() {
        String chatGpt = read("app/src/main/java/com/orbit/assistant/ChatGptClient.java");
        assertTrue(chatGpt.contains("You do not perform the calendar write"));
        assertTrue(chatGpt.contains("Never state that events were added"));
        assertTrue("the pre-execution wording is shown as an example",
                chatGpt.contains("can add them to your calendar"));
        assertTrue("hosted research must not be confused with the device action",
                chatGpt.contains("Web research is not the calendar action"));
        assertTrue("and the mandatory source line survives",
                chatGpt.contains("keep the mandatory Source line"));
        assertTrue("one action per batch, never one per event",
                chatGpt.contains("never one action per event"));

        String server = read("server/app.py");
        assertTrue(server.contains("Never claim events were added or saved"));
    }

    /** Success wording exists in exactly one place, and that place has counted rows. */
    @Test public void onlyNativeExecutionCanProduceSuccessWording() throws IOException {
        Path sources = repositoryRoot().resolve("app/src/main/java");
        try (java.util.stream.Stream<Path> files = Files.walk(sources)) {
            java.util.List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                                    .contains("\"Added \"");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toList());
            assertEquals("only the executor may say events were added",
                    java.util.Collections.singletonList("CalendarActionExecutor.java"), offenders);
        }
    }
}

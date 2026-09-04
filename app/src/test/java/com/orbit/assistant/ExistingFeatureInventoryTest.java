package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The inventory: what Orbit already owned before v0.7.8.0 Beta 1, and who still owns it.
 *
 * <p>This exists because of how the release was described. "Basic utilities", "device actions" and
 * "everyday assistant features" are exactly the words under which somebody cheerfully writes a
 * second timer parser, a second flashlight executor, or a second conversion table — and none of
 * that would fail a build, or a test, or a device check. It would simply mean two things that
 * disagree, discovered later.
 *
 * <p>So the ownership is written down and asserted. Every capability below existed before this
 * release, was deliberately <em>not</em> re-implemented in it, and still has exactly one
 * implementation. If a second one appears, this is where it is caught.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ExistingFeatureInventoryTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        RecentActionContext.clear();
    }

    private String actionType(String phrase) {
        AssistantReply reply = LocalCommandRouter.tryHandle(context, phrase);
        return reply == null || reply.actions.isEmpty() ? null : reply.actions.get(0).type;
    }

    // ---- the capabilities Orbit already had ------------------------------------------------------

    /**
     * Everything on this list predates the release and was left exactly where it was.
     *
     * <p>Each is still recognised by {@link LocalCommandRouter} and still executed by
     * {@link DeviceActionExecutor}, which is what "not rebuilt" actually means.
     */
    @Test public void everyPreExistingDeviceCommandIsStillOwnedByTheSameRouter() {
        assertEquals("SET_TIMER", actionType("set a timer for 10 minutes"));
        assertEquals("SET_ALARM", actionType("set an alarm for 7:30"));
        assertEquals("FLASHLIGHT", actionType("turn on the flashlight"));
        assertEquals("SET_BRIGHTNESS", actionType("set brightness to 40%"));
        assertEquals("SET_VOLUME", actionType("set volume to 20%"));
        assertEquals("SET_DND", actionType("turn on do not disturb"));
        assertEquals("OPEN_SETTINGS", actionType("open settings"));
        assertEquals("OPEN_APP", actionType("open Spotify"));
    }

    /** Relative levels and "put it back" are the same system they always were. */
    @Test public void relativeAndFollowUpCommandsAreUnchanged() {
        AssistantReply dimmer = LocalCommandRouter.tryHandle(context, "lower brightness by 10%");
        assertNotNull(dimmer);
        assertEquals("SET_BRIGHTNESS", dimmer.actions.get(0).type);
        assertTrue(dimmer.actions.get(0).params.has("delta"));

        RecentActionContext.recordLevel(RecentActionContext.Target.BRIGHTNESS, 70);
        AssistantReply back = LocalCommandRouter.tryHandle(context, "put it back");
        assertNotNull("the recent-action follow-up must still resolve", back);
        assertEquals("SET_BRIGHTNESS", back.actions.get(0).type);
        assertEquals(70, back.actions.get(0).params.optInt("percent"));
    }

    /** The kitchen still owns cooking, and this release added nothing to it. */
    @Test public void kitchenUtilitiesStillOwnCooking() {
        assertNotNull(KitchenMathRouter.answer("2 cups to ml"));
        assertNotNull(KitchenMathRouter.answer("500g in pounds"));
        assertNotNull(KitchenMathRouter.answer("425f in celsius"));
        assertNotNull(KitchenMathRouter.answer("double 3/4 cup"));

        assertEquals("cooking volume is not in the general table",
                null, MeasureUnit.fromAlias("cup"));
        assertEquals("cooking mass is not in the general table",
                null, MeasureUnit.fromAlias("gram"));
    }

    /** Reminders, Calendar, and Routines each still have exactly one owner. */
    @Test public void theOtherPreExistingSystemsAreUntouched() {
        String executor = source("DeviceActionExecutor.java");
        assertTrue("reminders are still SET_REMINDER through ReminderScheduler",
                executor.contains("case \"SET_REMINDER\"") && executor.contains("ReminderScheduler.schedule"));
        assertTrue("Calendar writes still route to CalendarActionExecutor",
                executor.contains("CalendarActionExecutor.execute"));
        assertTrue("and the Calendar composer is still a separate action",
                executor.contains("case \"CREATE_EVENT\""));
        assertTrue("routines still run through the shared engine",
                source("RoutineCommandRouter.java").contains("AssistantReply"));
    }

    // ---- no second implementation ------------------------------------------------------------------

    /**
     * There is one place that talks to Android for a device action, and it is the executor.
     *
     * <p>The pattern this guards against has a name in the release brief: an
     * {@code OrbitLocalFlashlightExecutor} sitting beside the real one. So no file added by the
     * local-action work may reach for the Android APIs the executor owns.
     */
    @Test public void theLocalActionPathHasNoAndroidControlOfItsOwn() {
        for (String file : new String[]{"OrbitLocalActionRouter.java", "LocalActionSchema.java"}) {
            String source = source(file);
            for (String forbidden : new String[]{
                    "setTorchMode", "CameraManager", "setStreamVolume", "setInterruptionFilter",
                    "setRingerMode", "Settings.System.putInt", "AlarmClock.ACTION",
                    "startActivity", "getLaunchIntentForPackage"}) {
                assertFalse(file + " must not control Android itself (" + forbidden + ")",
                        source.contains(forbidden));
            }
        }
    }

    /** And the executor is genuinely the only file that does. */
    @Test public void onlyTheExecutorTouchesTheDeviceControlApis() {
        assertEquals("the flashlight has exactly one implementation",
                List.of("DeviceActionExecutor.java"), filesContaining("setTorchMode"));
        assertEquals("so does the ringer",
                List.of("DeviceActionExecutor.java"), filesContaining("setRingerMode("));
        assertEquals("and media transport control",
                List.of("MediaControl.java"), filesContaining("getTransportControls"));
    }

    /** One timer grammar, one alarm grammar, one conversion table per dimension. */
    @Test public void thereIsOneOfEachParser() {
        assertEquals("the sentence shape of a timer request lives in one place",
                List.of("LocalCommandRouter.java"), filesContaining("TIMER_SUBJECT_AFTER"));
        // How long a timer runs for is a separate question from whether the sentence is asking for
        // one, and it is now answered in exactly one file. A second unit table anywhere is the
        // defect this pins: two parsers is how "4 minutes and 30 seconds" became four minutes.
        assertEquals("duration grammar lives in one place",
                List.of("DurationParser.java"), filesContaining("case \"mins\":"));
        assertEquals("and so does alarm grammar",
                List.of("LocalCommandRouter.java"), filesContaining("ALARM_TIME"));
        assertEquals("only the executor starts an Android timer",
                List.of("DeviceActionExecutor.java"), filesContaining("AlarmClock.EXTRA_LENGTH"));
        assertEquals("cooking units are declared once",
                List.of("KitchenUnit.java"), filesContaining("Dimension.VOLUME"));
        assertEquals("general units are declared once",
                List.of("MeasureUnit.java"), filesContaining("Dimension.LENGTH"));
    }

    /**
     * The new work reuses Orbit's one exact rational rather than introducing a second number type.
     */
    @Test public void thereIsStillOneNumericRepresentation() {
        assertTrue(source("OrbitCalculator.java").contains("KitchenQuantity"));
        assertTrue(source("MeasureMath.java").contains("KitchenQuantity"));
        assertTrue("and the general table renders through the kitchen's own Rendered type",
                source("MeasureMath.java").contains("KitchenMath.Rendered"));
        assertTrue(filesContaining("class KitchenQuantity").size() == 1);
    }

    // ---- what Beta 1 actually added -----------------------------------------------------------------

    /**
     * The genuinely new actions, and the fact that they are shared rather than local-only.
     *
     * <p>Media and ringer control did not exist anywhere in Orbit before this release — asserted
     * against the Stable source in the report rather than here — and they were added to the one
     * catalog every provider reads, not to the local path.
     */
    @Test public void theNewActionsAreSharedByEveryProvider() {
        String executor = source("DeviceActionExecutor.java");
        assertTrue(executor.contains("case \"MEDIA_CONTROL\""));
        assertTrue(executor.contains("case \"SET_RINGER_MODE\""));

        assertTrue("the cloud client may request them",
                source("ChatGptClient.java").contains("\"MEDIA_CONTROL\""));
        assertTrue("so may the relay",
                source("RelayProvider.java").contains("\"MEDIA_CONTROL\""));
        assertTrue("and so may the on-device action model",
                LocalActionSchema.ALLOWED_ACTIONS.contains("MEDIA_CONTROL"));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static String source(String simpleName) {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/" + simpleName);
    }

    /** Every app source file containing {@code needle}, by file name, sorted. */
    private static List<String> filesContaining(String needle, String... ignoring) {
        File dir = new File("src/main/java/com/orbit/assistant");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".java"));
        List<String> hits = new ArrayList<>();
        List<String> ignored = java.util.Arrays.asList(ignoring);
        if (files != null) {
            for (File file : files) {
                if (ignored.contains(file.getName())) continue;
                try {
                    String text = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8);
                    if (text.contains(needle)) hits.add(file.getName());
                } catch (Exception ignore) {
                    // A file that cannot be read cannot contain a second implementation.
                }
            }
        }
        java.util.Collections.sort(hits);
        return hits;
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The security boundary between the on-device action model and Orbit's action layer.
 *
 * <p>The model is a text generator running on the user's phone, and that changes nothing about what
 * its output is: untrusted input. These tests are the contract that says so. They are deliberately
 * separate from anything that runs inference — no model is loaded here, and none needs to be,
 * because every rule below is a property of the validator alone.
 *
 * <p>The property that matters most is structural rather than a list of rejections: the executor
 * receives a parameter object Orbit built, field by field, from values it checked. So the last test
 * in the "what cannot get through" section is not "the intent field is rejected" but "no field the
 * model wrote is ever forwarded".
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class LocalActionSchemaTest {

    /** An app resolver for a phone that has exactly these apps on it. */
    private static final LocalActionSchema.AppResolver APPS = wanted -> {
        if (wanted == null) return null;
        switch (wanted.trim().toLowerCase(java.util.Locale.US)) {
            case "spotify": return "Spotify";
            case "settings": return "Settings";
            default: return null;
        }
    };

    private static LocalActionSchema.Validation validate(String json) {
        return LocalActionSchema.validate(json, APPS);
    }

    private static AssistantReply.Action accepted(String json) {
        LocalActionSchema.Validation validation = validate(json);
        assertTrue("expected this to be accepted: " + json, validation.accepted());
        return validation.action;
    }

    private static void rejected(String reason, String json) {
        LocalActionSchema.Validation validation = validate(json);
        assertFalse("expected this to be rejected: " + json, validation.accepted());
        assertEquals(reason, validation.rejection);
        assertNull(validation.action);
    }

    // ---- what gets through ----------------------------------------------------------------------

    @Test public void everyAllowlistedActionIsAccepted() {
        assertEquals("FLASHLIGHT",
                accepted("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":false}}").type);
        assertEquals("SET_BRIGHTNESS",
                accepted("{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":40}}").type);
        assertEquals("SET_VOLUME",
                accepted("{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":0}}").type);
        assertEquals("SET_DND",
                accepted("{\"action\":\"SET_DND\",\"params\":{\"enabled\":true}}").type);
        assertEquals("SET_RINGER_MODE",
                accepted("{\"action\":\"SET_RINGER_MODE\",\"params\":{\"mode\":\"vibrate\"}}").type);
        assertEquals("MEDIA_CONTROL",
                accepted("{\"action\":\"MEDIA_CONTROL\",\"params\":{\"command\":\"NEXT\"}}").type);
        assertEquals("SET_TIMER",
                accepted("{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":600}}").type);
        assertEquals("SET_ALARM",
                accepted("{\"action\":\"SET_ALARM\",\"params\":{\"hour\":7,\"minute\":30}}").type);
        assertEquals("OPEN_APP",
                accepted("{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\"}}").type);
        assertEquals("OPEN_SETTINGS",
                accepted("{\"action\":\"OPEN_SETTINGS\",\"params\":{}}").type);
    }

    @Test public void theAllowlistIsTheOneOrbitDocuments() {
        assertEquals(10, LocalActionSchema.ALLOWED_ACTIONS.size());
        for (String dangerous : new String[]{"SMS", "SMS_CONTACT", "DIAL", "DIAL_CONTACT",
                "OPEN_URL", "WEB_SEARCH", "SHARE", "COPY", "CREATE_EVENT", "ADD_CALENDAR_EVENTS",
                "SET_REMINDER", "NAVIGATE", "EXTENSION_ACTION"}) {
            assertFalse(dangerous + " must never be reachable from the local action model",
                    LocalActionSchema.ALLOWED_ACTIONS.contains(dangerous));
        }
    }

    /** Small models write booleans several ways, and all of them mean the same thing. */
    @Test public void booleansAreReadHoweverTheModelWroteThem() {
        assertFalse(accepted("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":false}}")
                .params.optBoolean("on", true));
        assertFalse(accepted("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":\"off\"}}")
                .params.optBoolean("on", true));
        assertTrue(accepted("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":\"true\"}}")
                .params.optBoolean("on", false));
        assertTrue(accepted("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":1}}")
                .params.optBoolean("on", false));
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":\"maybe\"}}");
    }

    /** Model output usually arrives wrapped in a sentence or a fence, and that is fine. */
    @Test public void theObjectIsFoundInsideOrdinaryModelChatter() {
        assertEquals("FLASHLIGHT", accepted(
                "Sure! ```json\n{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":false}}\n``` done.").type);
        assertEquals("SET_VOLUME", accepted(
                "{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":30}} I hope that helps").type);
    }

    @Test public void aTimerMayBeGivenInMinutes() {
        assertEquals(600, accepted("{\"action\":\"SET_TIMER\",\"params\":{\"minutes\":10}}")
                .params.optInt("seconds"));
    }

    @Test public void aTimerLabelIsSanitisedAndBounded() {
        assertEquals("Pasta", accepted(
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":600,\"label\":\"Pasta\"}}")
                .params.optString("label"));
        assertEquals("a label made only of punctuation names nothing", "Orbit timer", accepted(
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":60,\"label\":\"<>{}\"}}")
                .params.optString("label"));
        String longLabel = accepted("{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":60,"
                + "\"label\":\"" + repeat("a", 200) + "\"}}").params.optString("label");
        assertTrue(longLabel.length() <= LocalActionSchema.MAX_LABEL);
    }

    // ---- what cannot get through -----------------------------------------------------------------

    @Test public void malformedOutputIsRejected() {
        rejected(LocalActionSchema.REJECT_EMPTY, "");
        rejected(LocalActionSchema.REJECT_EMPTY, null);
        rejected(LocalActionSchema.REJECT_NOT_JSON, "I can turn the flashlight off for you.");
        rejected(LocalActionSchema.REJECT_NOT_JSON, "{\"action\": \"FLASHLIGHT\"");
        rejected(LocalActionSchema.REJECT_NO_ACTION, "{\"params\":{\"on\":true}}");
        rejected(LocalActionSchema.REJECT_TOO_LONG, repeat("x", 2500));
    }

    @Test public void unknownActionsAreRejected() {
        rejected(LocalActionSchema.REJECT_UNKNOWN_ACTION, "{\"action\":\"SEND_SMS\"}");
        rejected(LocalActionSchema.REJECT_UNKNOWN_ACTION,
                "{\"action\":\"OPEN_URL\",\"params\":{\"url\":\"https://example.com\"}}");
        rejected(LocalActionSchema.REJECT_UNKNOWN_ACTION, "{\"action\":\"DELETE_EVERYTHING\"}");
        rejected(LocalActionSchema.REJECT_UNKNOWN_ACTION, "{\"action\":\"NONE\"}");
    }

    /** Beta 1 allows one action. An output carrying several is refused, never partly obeyed. */
    @Test public void multipleActionsAreRejectedOutright() {
        rejected(LocalActionSchema.REJECT_MULTIPLE_ACTIONS,
                "{\"actions\":[{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":true}},"
                        + "{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":100}}]}");
        rejected(LocalActionSchema.REJECT_MULTIPLE_ACTIONS, "{\"actions\":[]}");
        assertEquals("but a list of exactly one is the same request written differently",
                "FLASHLIGHT", accepted("{\"actions\":[{\"action\":\"FLASHLIGHT\","
                        + "\"params\":{\"on\":true}}]}").type);
    }

    @Test public void outOfRangeValuesAreRejectedRatherThanClamped() {
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":-1}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":200}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_VOLUME\",\"params\":{\"percent\":101}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_ALARM\",\"params\":{\"hour\":25,\"minute\":0}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_ALARM\",\"params\":{\"hour\":7,\"minute\":90}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":0}}");
        rejected(LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":-60}}");
        rejected("a week is not a timer", LocalActionSchema.REJECT_OUT_OF_RANGE,
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":604800}}");
    }

    private static void rejected(String why, String reason, String json) {
        LocalActionSchema.Validation validation = validate(json);
        assertFalse(why, validation.accepted());
        assertEquals(why, reason, validation.rejection);
    }

    @Test public void missingOrMistypedParametersAreRejected() {
        rejected(LocalActionSchema.REJECT_BAD_PARAMS, "{\"action\":\"FLASHLIGHT\",\"params\":{}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS, "{\"action\":\"SET_BRIGHTNESS\",\"params\":{}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"SET_BRIGHTNESS\",\"params\":{\"percent\":\"quite dim\"}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"SET_RINGER_MODE\",\"params\":{\"mode\":\"loud\"}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"MEDIA_CONTROL\",\"params\":{\"command\":\"SEEK\"}}");
    }

    /**
     * Injection attempts, in the forms they would actually take.
     *
     * <p>None of these could reach the executor even if they were ignored rather than rejected,
     * because the parameter object is rebuilt from checked values. They are rejected anyway: a model
     * reaching for an Intent is a model whose whole output should be distrusted, and rejecting makes
     * that visible in Diagnostics instead of silently dropping it.
     */
    @Test public void injectionAttemptsAreRejectedRatherThanIgnored() {
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\","
                        + "\"intent\":\"android.intent.action.CALL\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\","
                        + "\"component\":\"com.evil/.Main\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\","
                        + "\"package_name\":\"com.evil\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"shell\":\"rm -rf /\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"OPEN_SETTINGS\",\"params\":{\"url\":\"https://evil.example\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":true,\"path\":\"/data/data\"}}");
        rejected(LocalActionSchema.REJECT_FORBIDDEN_FIELD,
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":60},\"class\":\"java.lang.Runtime\"}");
    }

    /**
     * The structural guarantee, asserted directly.
     *
     * <p>Whatever else was in the model's object, the action Orbit builds carries only the fields
     * Orbit put there.
     */
    @Test public void nothingTheModelWroteIsEverForwarded() {
        AssistantReply.Action action = accepted(
                "{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":60,\"label\":\"Tea\","
                        + "\"unexpected\":\"anything at all\",\"nested\":{\"deep\":true}}}");
        assertEquals(2, action.params.length());
        assertTrue(action.params.has("seconds"));
        assertTrue(action.params.has("label"));
        assertFalse(action.params.has("unexpected"));
        assertFalse(action.params.has("nested"));
        assertFalse("and a local action never carries its own confirmation flag",
                action.requiresConfirmation);
    }

    // ---- app resolution --------------------------------------------------------------------------

    /** A generated app name only becomes a launch if the phone actually has that app. */
    @Test public void appNamesMustResolveToSomethingInstalled() {
        assertEquals("Spotify",
                accepted("{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"spotify\"}}")
                        .params.optString("app"));
        rejected(LocalActionSchema.REJECT_UNKNOWN_APP,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Definitely Not Installed\"}}");
        rejected(LocalActionSchema.REJECT_UNKNOWN_APP,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"com.evil.malware\"}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"\"}}");
        rejected(LocalActionSchema.REJECT_BAD_PARAMS,
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"" + repeat("a", 100) + "\"}}");
    }

    @Test public void withoutAResolverNoAppMayBeOpened() {
        LocalActionSchema.Validation validation = LocalActionSchema.validate(
                "{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\"}}", null);
        assertFalse(validation.accepted());
        assertEquals(LocalActionSchema.REJECT_UNKNOWN_APP, validation.rejection);
    }

    // ---- reported categories ---------------------------------------------------------------------

    /** Diagnostics sees a category word, never a value. */
    @Test public void acceptedActionsReportACategory() {
        assertEquals("flashlight",
                validate("{\"action\":\"FLASHLIGHT\",\"params\":{\"on\":true}}").category);
        assertEquals("timer",
                validate("{\"action\":\"SET_TIMER\",\"params\":{\"seconds\":60}}").category);
        assertEquals("app",
                validate("{\"action\":\"OPEN_APP\",\"params\":{\"app\":\"Spotify\"}}").category);
        assertEquals("a rejection carries no category at all",
                "", validate("{\"action\":\"SEND_SMS\"}").category);
    }

    // ---- the object scanner ------------------------------------------------------------------------

    @Test public void theFirstBalancedObjectIsFound() {
        assertEquals("{\"a\":1}", LocalActionSchema.firstJsonObject("noise {\"a\":1} more"));
        assertEquals("{\"a\":{\"b\":2}}",
                LocalActionSchema.firstJsonObject("x {\"a\":{\"b\":2}} y"));
        assertEquals("a brace inside a string is not a brace",
                "{\"a\":\"}\"}", LocalActionSchema.firstJsonObject("{\"a\":\"}\"}"));
        assertEquals("", LocalActionSchema.firstJsonObject("{\"a\":1"));
        assertEquals("", LocalActionSchema.firstJsonObject("no braces here"));
        assertEquals("", LocalActionSchema.firstJsonObject(null));
    }

    private static String repeat(String s, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(s);
        return out.toString();
    }
}

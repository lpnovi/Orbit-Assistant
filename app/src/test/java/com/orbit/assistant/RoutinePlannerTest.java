package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What the planner is told, and what it is not. The capability list is generated from the live
 * catalog, and nothing sensitive is described to it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class RoutinePlannerTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void theCapabilityListCoversTheSupportedActions() {
        String capabilities = RoutinePlanner.capabilities(context);
        for (String type : new String[]{
                RoutineActionCatalog.SET_DND, RoutineActionCatalog.SET_BRIGHTNESS,
                RoutineActionCatalog.SET_VOLUME, RoutineActionCatalog.FLASHLIGHT,
                RoutineActionCatalog.SET_TIMER, RoutineActionCatalog.SET_ALARM,
                RoutineActionCatalog.OPEN_APP}) {
            assertTrue("the planner must know about " + type, capabilities.contains(type));
        }
    }

    @Test public void everyDescribedTypeIsOneOrbitActuallySupports() {
        String capabilities = RoutinePlanner.capabilities(context);
        for (String line : capabilities.split("\n")) {
            if (!line.startsWith("- ")) continue;
            String type = line.substring(2, line.indexOf(' ', 2)).trim();
            assertTrue("described a type the catalog does not support: " + type,
                    RoutineActionCatalog.isSupported(type));
        }
    }

    @Test public void noExtensionIsOfferedWhenNoneIsEnabled() {
        assertFalse(RoutinePlanner.capabilities(context).contains("EXTENSION_ACTION"));
    }

    @Test public void thePromptCarriesTheDescriptionAndTheRules() {
        String prompt = RoutinePlanner.prompt(context, "Turn on Do Not Disturb at bedtime");
        assertTrue(prompt.contains("Turn on Do Not Disturb at bedtime"));
        assertTrue(prompt.contains("SET_DND"));
        assertTrue("the step limit must be stated",
                prompt.contains(String.valueOf(RoutineActionCatalog.MAX_STEPS)));
        assertTrue("the name limit must be stated",
                prompt.contains(String.valueOf(RoutineStore.MAX_NAME_LENGTH)));
        assertTrue("unsupported requests must be reported, not approximated",
                prompt.contains("unsupported"));
    }

    @Test public void thePromptIsOnlyTheInstructionsCapabilitiesAndDescription() {
        // Nothing else may be attached: no conversation, screen text, notifications, or memories.
        String description = "Focus routine with Do Not Disturb";
        String prompt = RoutinePlanner.prompt(context, description);

        String withoutParts = prompt
                .replace(RoutinePlanner.capabilities(context), "")
                .replace(description, "");
        for (String marker : new String[]{"Recent messages", "Screen", "Notification",
                "Remembered", "Memory", "conversation"}) {
            assertFalse("the planning prompt must not carry " + marker,
                    withoutParts.contains(marker));
        }
    }

    @Test public void extensionSecretsAreNeverDescribedToThePlanner() {
        // Only identifiers and display names are ever emitted for an extension action.
        String capabilities = RoutinePlanner.capabilities(context);
        for (String secretish : new String[]{"http://", "https://", "Authorization",
                "token", "secret", "apiKey", "webhook"}) {
            assertFalse("capability metadata leaked " + secretish,
                    capabilities.toLowerCase(java.util.Locale.US)
                            .contains(secretish.toLowerCase(java.util.Locale.US)));
        }
    }

    @Test public void schedulingLanguageIsRecognised() {
        assertTrue(RoutinePlanner.mentionsAutomation("every weekday at 8 turn on DND"));
        assertTrue(RoutinePlanner.mentionsAutomation("when I arrive home lower brightness"));
        assertTrue(RoutinePlanner.mentionsAutomation("when I leave work set volume to 0"));
        assertTrue(RoutinePlanner.mentionsAutomation("only when I am at home"));
        assertTrue(RoutinePlanner.mentionsAutomation("schedule this for the morning"));
    }

    @Test public void ordinaryDescriptionsAreNotTreatedAsScheduling() {
        assertFalse(RoutinePlanner.mentionsAutomation(
                "Turn on Do Not Disturb and set brightness to 20%"));
        assertFalse(RoutinePlanner.mentionsAutomation("Gaming mode with full volume"));
        assertFalse(RoutinePlanner.mentionsAutomation(null));
    }

    @Test public void schedulingIsReportedRatherThanBuilt() {
        String notice = RoutinePlanner.automationNotice();
        assertTrue(notice.toLowerCase(java.util.Locale.US).contains("automatic triggers"));
        assertTrue("the user must be told the steps were still created",
                notice.toLowerCase(java.util.Locale.US).contains("steps were created"));
    }

    @Test public void thePlannerMayScheduleButMayNotGuess() {
        String prompt = RoutinePlanner.prompt(context, "every morning at 7 set brightness to 60%");
        // Triggers are drafted from v0.7.3.1, but only from wording that is actually concrete.
        assertTrue(prompt.contains("\"trigger\""));
        assertTrue(prompt.contains("Never guess a clock time"));
        assertTrue(prompt.contains("Never guess a place"));
        assertTrue("an else branch must still be reported, not invented",
                prompt.contains("Do not invent an \"otherwise\""));
    }

    @Test public void anEmptyDescriptionIsRejectedBeforeAnyRequest() {
        final String[] error = {null};
        RoutinePlanner.build(context, "   ", new RoutinePlanner.Callback() {
            @Override public void onDraft(RoutineDraft draft, String notice) {
                throw new AssertionError("an empty description must not be planned");
            }
            @Override public void onError(String message) { error[0] = message; }
        });
        assertTrue(error[0] != null && !error[0].trim().isEmpty());
    }
}

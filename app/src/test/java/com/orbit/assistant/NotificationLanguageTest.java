package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Notification Intelligence language routing.
 *
 * <p>The reported failure: "What notifications have I missed?" reached Orbit's own notification
 * history, but "What notifs have I missed?" fell through to the hosted model, which then
 * correctly reported that it cannot see notifications. Only the spelling differed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class NotificationLanguageTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static void recognized(String prompt) {
        assertTrue("Orbit should read its own notification history for: " + prompt,
                NotificationQueryHelper.looksLikeNotificationQuery(prompt));
    }

    private static void notRecognized(String prompt) {
        assertFalse("this belongs to the model, not the notification log: " + prompt,
                NotificationQueryHelper.looksLikeNotificationQuery(prompt));
    }

    // ---- the reported regression ----

    @Test public void theFullWordingStillWorks() {
        recognized("What notifications have I missed?");
    }

    @Test public void theShorthandNowWorks() {
        recognized("What notifs have i missed?");
        recognized("What notif did I miss?");
        recognized("What notifs did I miss?");
    }

    @Test public void shorthandExpandsToTheSameCanonicalText() {
        assertEquals("what notifications have i missed",
                LanguageNormalizer.canonical("What notifs have I missed?"));
        assertEquals("what notification did i miss",
                LanguageNormalizer.canonical("What notif did I miss?"));
    }

    // ---- natural catch-up requests ----

    @Test public void everydayCatchUpRequestsAreUnderstood() {
        String[] prompts = {
                "What notifications came in?", "What notifs came in?",
                "Any new notifications?", "Any new notifs?",
                "Any important notifications?", "Any important notifs?",
                "Any important notifs today?",
                "Catch me up on my notifications.", "Catch me up on my notifs.",
                "Summarize my notifications.", "Summarize my notifs.",
                "Show my recent notifications.", "Show my recent notifs.",
                "What did I miss in my notifications?", "What did I miss in my notifs?"
        };
        for (String prompt : prompts) recognized(prompt);
    }

    @Test public void messageHistoryQuestionsWithoutTheWordNotification() {
        String[] prompts = {
                "Who messaged me?", "Did anyone message me?", "Did anyone text me?",
                "Did I get any messages?", "Any messages while I was gone?",
                "Anything come in since I last checked?"
        };
        for (String prompt : prompts) recognized(prompt);
    }

    @Test public void punctuationAndSpacingDoNotMatter() {
        recognized("what notifs have i missed");
        recognized("What   NOTIFS   have I missed !!");
        recognized("What notifs have I missed");
    }

    // ---- people and apps ----

    @Test public void specificPeopleAndAppsStillRoute() {
        recognized("Did Niki message me?");
        recognized("Any messages from Niki?");
        recognized("Did Discord send me anything?");
        recognized("What did I miss from Messages?");
        recognized("Any important Discord notifs?");
    }

    // ---- the boundary that matters ----

    @Test public void conceptualNotificationQuestionsGoToTheModel() {
        String[] prompts = {
                "What is an Android notification channel?",
                "How do notifications work?",
                "Why do apps send notifications?",
                "How do I disable notifications on Android?",
                "Why are my notifications delayed?",
                "What does notification importance mean?"
        };
        for (String prompt : prompts) notRecognized(prompt);
    }

    @Test public void ordinaryConversationIsNotHijacked() {
        notRecognized("What is the weather today?");
        notRecognized("Tell me a joke");
        notRecognized("How do I get to the station?");
    }

    // ---- feature state ----

    @Test public void withoutNotificationAccessOrbitSaysSoRatherThanFallingThrough() {
        NotificationQueryHelper.Prepared prepared =
                NotificationQueryHelper.prepare(context, "What notifs have I missed?");
        assertTrue("a recognized request must stay with Orbit", prepared.recognized);
        assertNotNull(prepared.localReply);
        assertTrue(prepared.localReply.text.toLowerCase().contains("notification access"));
    }

    @Test public void anUnrecognizedPromptIsLeftAlone() {
        NotificationQueryHelper.Prepared prepared =
                NotificationQueryHelper.prepare(context, "How do notifications work?");
        assertFalse(prepared.recognized);
        assertNull(prepared.localReply);
        assertEquals("", prepared.context);
    }

    @Test public void everyFeatureStateAnswersLocallyRatherThanFallingThrough() {
        // Whichever gate is closed, Orbit says which one. What must never happen is the request
        // reaching the hosted model, which would report that it has no notification access at all.
        for (boolean intelligenceOn : new boolean[]{true, false}) {
            Prefs.get(context).edit()
                    .putBoolean(Prefs.NOTIFICATION_AI_ENABLED, intelligenceOn).commit();
            NotificationQueryHelper.Prepared prepared =
                    NotificationQueryHelper.prepare(context, "Summarize my notifs");
            assertTrue("intelligenceOn=" + intelligenceOn, prepared.recognized);
            assertNotNull("intelligenceOn=" + intelligenceOn, prepared.localReply);
            assertEquals("no history may be sent to the model in this state",
                    "", prepared.context);
        }
    }

    // ---- time windows ----

    @Test public void timeWindowsSurviveTheShorthand() {
        recognized("What notifs did I get in the last 2 hours?");
        recognized("Catch me up on notifications from the last hour.");
        recognized("What notifications did I get yesterday?");
        recognized("Show my notifs since 1 PM.");
        recognized("Any important notifs today?");
    }

    @Test public void writtenTimeWindowsAreReadAsCounts() {
        assertEquals(1, LanguageNormalizer.wordNumber("an"));
        assertEquals(2, LanguageNormalizer.wordNumber("couple"));
        assertEquals(30, LanguageNormalizer.wordNumber("thirty"));
        assertEquals(-1, LanguageNormalizer.wordNumber("umpteen"));
    }

    @Test public void whileIWasGoneStillRoutes() {
        recognized("What did I miss while I was gone?");
        recognized("Any messages while I was gone?");
    }
}

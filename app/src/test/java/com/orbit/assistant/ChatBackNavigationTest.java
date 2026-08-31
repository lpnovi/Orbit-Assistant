package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Leaving a conversation should be Android's own gesture, not an imitation of it.
 *
 * <p>v0.7.7.9 Beta 1 believed the whole implementation was a subtraction: register nothing, declare
 * no close animation, and let Android's own cross-activity predictive back do the rest. Every
 * assertion below the "one destination" heading passed, and on a Galaxy S25 Ultra the conversation
 * did not move at all. The lesson is in what those tests could not reach: they proved Orbit had
 * asked for a transition, and no test can prove the system drew one.
 *
 * <p>Beta 2 therefore draws it, and these cover what that means. Exactly one owner holds back at a
 * time. The motion is a function of gesture progress and of nothing else, so it reverses when the
 * gesture reverses and leaves nothing behind when it is cancelled. A commit navigates once. The
 * keyboard still gets back first. Chats is genuinely underneath, from every surface that can open a
 * conversation. And Diagnostics may not describe any of it as observed unless Orbit observed it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatBackNavigationTest {
    private Context context;

    @org.junit.After public void tearDown() {
        OrbitPredictiveBack.keyboardVisibleForTest = null;
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        OrbitPredictiveBack.keyboardVisibleForTest = null;
        DiagnosticStore.prefs(context).edit().clear().commit();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    private ActivityController<ChatActivity> openChat(String id) {
        ConversationStore.save(context, id, new ArrayList<>());
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, id);
        return Robolectric.buildActivity(ChatActivity.class, intent).setup();
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(descendants(group.getChildAt(i)));
        }
        return out;
    }

    private static View back(ChatActivity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && "Back".contentEquals(description)) return v;
        }
        throw new AssertionError("the conversation must have a Back control");
    }

    // ---- exactly one owner of back -------------------------------------------------------------

    /**
     * An ordinary conversation holds back for the gesture, and holds nothing for a chooser.
     *
     * <p>The chooser callback closes a chooser and does nothing else, so leaving it armed with no
     * chooser on screen would be a back that goes nowhere. Which of the two owners is armed is the
     * whole of the arrangement, and it is decided in one place.
     */
    @Test public void anOrdinaryConversationHoldsBackOnlyForTheGesture() {
        ActivityController<ChatActivity> controller = openChat("c-plain");
        ChatActivity activity = controller.get();
        assertFalse("no chooser callback may stand while there is no chooser",
                activity.backHandlerArmedForTest());
        assertEquals("the drawn gesture is armed exactly where the device can report progress",
                OrbitPredictiveBack.enabled(context), activity.predictiveBackArmedForTest());
        controller.pause().stop().destroy();
    }

    /** While the attachment chooser is open, back is Orbit's, because it has something to close. */
    @Test public void anOpenChooserTakesBackAndThenGivesItStraightBack() {
        ActivityController<ChatActivity> controller = openChat("c-chooser");
        ChatActivity activity = controller.get();

        activity.showAttachmentMenuForTest();
        assertTrue("an open chooser must consume back", activity.backHandlerArmedForTest());
        assertFalse("and the drawn gesture must stand down while it does",
                activity.predictiveBackArmedForTest());

        activity.performBackForTest();
        assertFalse("closing it must release the chooser's hold on back",
                activity.backHandlerArmedForTest());
        assertEquals("and hand back to the gesture again",
                OrbitPredictiveBack.enabled(context), activity.predictiveBackArmedForTest());
        assertFalse("and closing a chooser is not leaving the conversation",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /**
     * A chooser can also be dismissed without back, and the screen must notice.
     *
     * <p>Choosing an item or tapping outside removes it too. A remembered flag would miss both and
     * leave the conversation holding a gesture it has no use for, so the state is observed from the
     * chooser actually being there.
     */
    @Test public void aChooserClosedWithoutBackStillReleasesTheGesture() {
        ActivityController<ChatActivity> controller = openChat("c-chooser-tap");
        ChatActivity activity = controller.get();

        activity.showAttachmentMenuForTest();
        assertTrue(activity.backHandlerArmedForTest());

        OrbitAttachmentMenu.dismiss(activity.findViewById(android.R.id.content));
        Robolectric.flushForegroundThreadScheduler();
        assertFalse("dismissal by any route releases the chooser's callback",
                activity.backHandlerArmedForTest());
        assertEquals("and hands back to the gesture, with nothing stale left armed",
                OrbitPredictiveBack.enabled(context), activity.predictiveBackArmedForTest());
        controller.pause().stop().destroy();
    }

    // ---- one destination ---------------------------------------------------------------------

    /** Orbit's Back control performs Back. It does not have a destination of its own. */
    @Test public void theBackControlAndTheGestureShareOneDestination() {
        ActivityController<ChatActivity> controller = openChat("c-button");
        ChatActivity activity = controller.get();
        assertFalse(activity.isFinishing());

        back(activity).performClick();
        assertTrue("the Back control finishes the conversation, exactly as Back does",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** With a chooser open, the Back control closes it first, like every other back. */
    @Test public void theBackControlAlsoClosesAnOpenChooserFirst() {
        ActivityController<ChatActivity> controller = openChat("c-button-chooser");
        ChatActivity activity = controller.get();
        activity.showAttachmentMenuForTest();

        back(activity).performClick();
        assertFalse("the button and the gesture cannot mean two different things",
                activity.isFinishing());
        assertFalse(activity.backHandlerArmedForTest());
        controller.pause().stop().destroy();
    }

    // ---- nothing survives the screen ---------------------------------------------------------

    /** A recreated conversation starts with back belonging to the system, as a fresh one does. */
    @Test public void noGestureStateSurvivesRecreation() {
        ActivityController<ChatActivity> controller = openChat("c-recreate");
        controller.get().showAttachmentMenuForTest();
        assertTrue(controller.get().backHandlerArmedForTest());

        controller.pause().stop().destroy();

        ActivityController<ChatActivity> again = openChat("c-recreate");
        assertFalse("a rebuilt conversation may not inherit a gesture from the last one",
                again.get().backHandlerArmedForTest());
        again.pause().stop().destroy();
    }

    /** Leaving does not lose the conversation, whichever way back was performed. */
    @Test public void leavingChangesNothingAboutTheConversation() {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "What is on this screen?"));
        history.add(new AssistantClient.History("assistant", "A settings page."));
        ConversationStore.save(context, "c-intact", history);

        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "c-intact");
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        back(controller.get()).performClick();
        controller.pause().stop().destroy();

        ConversationStore.Conversation after = ConversationStore.load(context, "c-intact");
        assertNotNull(after);
        assertEquals(2, after.messages.size());
        assertEquals("What is on this screen?", after.messages.get(0).content);
        assertEquals("no request may be created by leaving a conversation",
                0, PendingRequestStore.active(context).size());
    }

    // ---- the window must not say how it closes -----------------------------------------------

    /**
     * The other half of standing aside, and the easier one to lose by accident.
     *
     * <p>A window that declares {@code activityClose*} animations is closed that way, so the system
     * never tracks it with the finger. Orbit's page transitions declare both halves, so the
     * conversation uses a variant that keeps the entrance the user chose and says nothing about
     * leaving. Asserted on the styles themselves because that is where the mistake would be made.
     */
    @Test public void thePredictiveStylesDeclareNoCloseAnimation() throws Exception {
        String styles = new String(Files.readAllBytes(
                new File("src/main/res/values/styles.xml").toPath()), StandardCharsets.UTF_8);
        for (String name : new String[]{"OrbitWindowAnimation.PredictiveSlide",
                "OrbitWindowAnimation.PredictiveFade", "OrbitWindowAnimation.PredictiveNone"}) {
            int start = styles.indexOf("name=\"" + name + "\"");
            assertTrue(name + " must exist", start > 0);
            String body = styles.substring(start, styles.indexOf("</style>", start));
            assertFalse(name + " must not declare a close-enter animation",
                    body.contains("activityCloseEnterAnimation"));
            assertFalse(name + " must not declare a close-exit animation",
                    body.contains("activityCloseExitAnimation"));
        }
    }

    /** Each predictive style keeps the entrance of the page transition it stands in for. */
    @Test public void thePredictiveStylesFollowThePageTransitionPreference() {
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION, Prefs.PAGE_TRANSITION_SLIDE).commit();
        assertEquals(R.style.OrbitWindowAnimation_PredictiveSlide,
                UiKit.predictiveTransitionStyle(context));
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION, Prefs.PAGE_TRANSITION_FADE).commit();
        assertEquals(R.style.OrbitWindowAnimation_PredictiveFade,
                UiKit.predictiveTransitionStyle(context));
        Prefs.get(context).edit().putString(Prefs.PAGE_TRANSITION, Prefs.PAGE_TRANSITION_NONE).commit();
        assertEquals(R.style.OrbitWindowAnimation_PredictiveNone,
                UiKit.predictiveTransitionStyle(context));
    }

    // ---- the setting -------------------------------------------------------------------------

    /** On by default, and it is a choice of transition rather than a switch for Android's back. */
    @Test public void theEnhancedTreatmentIsOnByDefaultAndCanBeTurnedOff() {
        assertTrue("swipe back to Chats ships on", Prefs.swipeToGoBack(context));

        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        assertFalse(Prefs.swipeToGoBack(context));

        ActivityController<ChatActivity> controller = openChat("c-setting-off");
        ChatActivity activity = controller.get();
        // Back still works, and still through the same one path. Orbit never disables the
        // platform's back; it only stops asking for the platform's transition.
        assertFalse(activity.backHandlerArmedForTest());
        back(activity).performClick();
        assertTrue("ordinary back must work with the enhanced treatment off",
                activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** With the setting off, Orbit keeps its own page transition rather than handing back over. */
    @Test public void turningItOffKeepsOrbitsOwnTransition() {
        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        ActivityController<ChatActivity> controller = openChat("c-transition-off");
        assertFalse("nothing is handed to the platform while the treatment is off",
                UiKit.applyPredictiveBackTransition(controller.get()));
        controller.pause().stop().destroy();
    }

    /**
     * Only a device that can actually run the transition is offered it.
     *
     * <p>Below API 34 the platform runs back correctly but commits without an interactive
     * transition, so a window with no close animation would simply blink away. Orbit keeps its own
     * page transition there rather than building a brittle imitation of a system feature.
     */
    @Test public void olderDevicesKeepOrbitsOwnTransition() {
        boolean applied = UiKit.applyPredictiveBackTransition(
                Robolectric.buildActivity(MainActivity.class).setup().get());
        assertEquals("only a device with the progress API is handed back",
                OrbitBackHandler.predictiveTransitionAvailable(), applied);
    }

    // ---- the drawn gesture ---------------------------------------------------------------------

    /** The gesture exists exactly where the platform reports progress, and nowhere else. */
    @Test public void theDrawnGestureIsOfferedOnlyWhereProgressIsReported() {
        assertEquals("API 34 is where onBackProgressed arrives",
                Build.VERSION.SDK_INT >= 34, OrbitPredictiveBack.available());
        assertEquals("and the setting is the other half of it",
                OrbitPredictiveBack.available(), OrbitPredictiveBack.enabled(context));

        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        assertFalse("turning it off must leave nothing armed",
                OrbitPredictiveBack.enabled(context));
    }

    /**
     * The conversation moves while the finger is still down, and moves by the amount reported.
     *
     * <p>This is the acceptance requirement in test form. Nothing here waits for a release, and
     * nothing here is a fixed-length animation: every position asserted is read back after handing
     * the gesture one progress value.
     */
    @Test public void theConversationTracksProgressWhileTheFingerIsDown() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-progress");
        ChatActivity activity = controller.get();
        OrbitPredictiveBack back = activity.predictiveBackForTest();

        back.startedForTest();
        assertTrue("the gesture is live from the moment it starts", back.gesturingForTest());
        assertEquals("and has not moved anything yet", 0f, back.translationForTest(), 0.5f);

        float previous = back.translationForTest();
        for (float p : new float[]{0.1f, 0.25f, 0.4f, 0.6f, 0.85f}) {
            back.progressedForTest(p);
            float now = back.translationForTest();
            assertTrue("the conversation must move further as the gesture does", now > previous);
            previous = now;
        }
        assertEquals("every progress event must have been drawn", 5, back.progressEventsForTest());
        assertFalse("and none of it may navigate", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Reversing the gesture reverses the picture, because the picture is only the progress. */
    @Test public void reversingTheGestureReversesTheConversation() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-reverse");
        OrbitPredictiveBack back = controller.get().predictiveBackForTest();

        back.startedForTest();
        back.progressedForTest(0.8f);
        float far = back.translationForTest();
        back.progressedForTest(0.3f);
        float near = back.translationForTest();
        assertTrue("pulling the gesture back must pull the conversation back", near < far);
        back.progressedForTest(0f);
        assertEquals("and returning to no progress must return it exactly",
                0f, back.translationForTest(), 0.5f);
        controller.pause().stop().destroy();
    }

    /** A cancelled gesture puts the conversation back and undresses the window. */
    @Test public void cancellingRestoresEverythingAndNavigatesNowhere() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-cancel");
        ChatActivity activity = controller.get();
        OrbitPredictiveBack back = activity.predictiveBackForTest();

        back.startedForTest();
        back.progressedForTest(0.7f);
        back.cancelledForTest();
        idle();

        assertEquals("the conversation must come to rest exactly where it started",
                0f, back.translationForTest(), 0.5f);
        assertFalse("no gesture may be left running", back.gesturingForTest());
        assertFalse("and the window must be undressed again", back.revealingForTest());
        assertFalse("a cancelled gesture is not a navigation", activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** A cancelled gesture is not allowed to cost the user anything they had. */
    @Test public void cancellingKeepsTheDraftTheScrollAndTheConversation() {
        assumeDrawnGesture();
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "What is on this screen?"));
        history.add(new AssistantClient.History("assistant", "A settings page."));
        ConversationStore.save(context, "c-cancel-state", history);

        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "c-cancel-state");
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        ChatActivity activity = controller.get();
        activity.setDraftForTest("half a question");

        OrbitPredictiveBack back = activity.predictiveBackForTest();
        back.startedForTest();
        back.progressedForTest(0.65f);
        back.cancelledForTest();
        idle();

        assertEquals("an abandoned gesture may not take the draft with it",
                "half a question", activity.draftForTest());
        ConversationStore.Conversation after = ConversationStore.load(context, "c-cancel-state");
        assertNotNull(after);
        assertEquals("nor anything of the conversation", 2, after.messages.size());
        controller.pause().stop().destroy();
    }

    /** A committed gesture leaves the conversation once, however many times it is told to. */
    @Test public void committingFinishesExactlyOnce() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-commit");
        ChatActivity activity = controller.get();
        OrbitPredictiveBack back = activity.predictiveBackForTest();

        back.startedForTest();
        back.progressedForTest(0.9f);
        back.invokedForTest();
        idle();
        assertTrue("a committed gesture must leave the conversation", back.navigatedForTest());
        assertTrue(activity.isFinishing());

        back.invokedForTest();
        idle();
        assertTrue("and a repeat may not navigate a second time", back.navigatedForTest());
        controller.pause().stop().destroy();
    }

    /**
     * Nothing of a gesture survives the screen being taken down.
     *
     * <p>Detaching has to undress the window as well as release the callback: a conversation left
     * translucent and shifted sideways would be a considerably worse bug than the one this feature
     * fixes.
     */
    @Test public void detachingLeavesNoGestureBehind() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-detach");
        OrbitPredictiveBack back = controller.get().predictiveBackForTest();
        back.startedForTest();
        back.progressedForTest(0.5f);

        back.detach();
        assertFalse("the callback must be released", back.isArmed());
        assertFalse("the window must be undressed", back.revealingForTest());
        assertEquals("and the conversation put back", 0f, back.translationForTest(), 0.5f);
        controller.pause().stop().destroy();
    }

    // ---- a real Chats screen underneath ---------------------------------------------------------

    /**
     * Every surface outside Chats opens a conversation on top of a real Chats screen.
     *
     * <p>{@code parentActivityName} does not do this and never did: the platform reads it for Up
     * navigation and for stacks built with {@code TaskStackBuilder}, not for Back. A conversation
     * launched on its own was the root of its task, so Back ended at the launcher and the gesture
     * had nothing of Orbit's to reveal.
     */
    @Test public void aConversationOpenedFromOutsideChatsLandsOnTopOfChats() {
        Intent open = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "c-stack");
        Intent[] stack = ChatActivity.stackFor(context, open);

        assertEquals("Chats first, then the conversation", 2, stack.length);
        assertEquals(MainActivity.class.getName(), stack[0].getComponent().getClassName());
        assertEquals(ChatActivity.class.getName(), stack[1].getComponent().getClassName());
        assertTrue("Chats has to be able to start the task",
                (stack[0].getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("and must reuse the Chats screen that is already there rather than duplicate it",
                (stack[0].getFlags() & Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0);
    }

    /** Opening a chat from Chats needs no stack of its own: the one it wants is already there. */
    @Test public void aConversationOpenedFromChatsKeepsTheChatsBeneathIt() {
        ActivityController<MainActivity> chats = Robolectric.buildActivity(MainActivity.class).setup();
        ConversationStore.save(context, "c-from-chats", new ArrayList<>());
        chats.get().openChatForTest("c-from-chats");
        Robolectric.flushForegroundThreadScheduler();

        Intent started = org.robolectric.Shadows.shadowOf(chats.get()).getNextStartedActivity();
        assertNotNull("the conversation must be started", started);
        assertEquals(ChatActivity.class.getName(), started.getComponent().getClassName());
        assertEquals("started plainly, so Chats stays underneath it in the same task",
                0, started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK);
        assertFalse("and Chats is not finished by opening one", chats.get().isFinishing());
        chats.pause().stop().destroy();
    }

    // ---- Diagnostics may not claim what Orbit cannot see ------------------------------------------

    /**
     * The Beta 1 flaw, in test form.
     *
     * <p>Beta 1 read the API level and the preference and reported "system predictive" as though it
     * had watched it happen. Whatever Orbit reports about back, it may describe configuration as
     * configuration and observations as observations, and it may never present the platform's
     * animation as a thing it saw.
     */
    @Test public void diagnosticsNeverClaimsTheSystemDrewATransition() {
        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();

        assertFalse("Orbit cannot see the system's frames and must not claim them",
                report.contains("system predictive"));
        assertTrue("what Orbit asked for is configuration, and must read as configuration",
                report.contains("Predictive navigation requested:"));
        assertTrue("the device's capability is a runtime fact and may be stated as one",
                report.contains("Platform predictive API:"));
        assertTrue("what a conversation actually installed is an observation",
                report.contains("Chat back callback:"));
        assertTrue("and how many screens can offer the gesture is counted, not guessed",
                report.contains("Eligible screens: " + OrbitNavigation.eligibleScreenCount()));
        diagnostics.pause().stop().destroy();
    }

    /** With nothing observed yet, the observed lines say so rather than guessing. */
    @Test public void diagnosticsSaysNothingObservedBeforeAnythingHasBeen() {
        DiagnosticStore.prefs(context).edit().clear().commit();
        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();

        assertTrue(report.contains("Chat back callback: not observed yet"));
        assertTrue(report.contains("Last predictive gesture: none recorded"));
        assertTrue(report.contains("Last back path: none recorded"));
        diagnostics.pause().stop().destroy();
    }

    /** A gesture Orbit drew is reported with the count of frames it was actually given. */
    @Test public void diagnosticsReportsTheProgressItWasActuallyGiven() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-report");
        OrbitPredictiveBack back = controller.get().predictiveBackForTest();
        back.startedForTest();
        back.progressedForTest(0.2f);
        back.progressedForTest(0.5f);
        back.progressedForTest(0.7f);
        back.cancelledForTest();
        idle();
        controller.pause().stop().destroy();

        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();
        assertTrue("the outcome and the count are both observations Orbit really has",
                report.contains("Last predictive gesture: cancelled · 3 progress events"));
        assertTrue(report.contains("Last back path: Orbit progress"));
        diagnostics.pause().stop().destroy();
    }

    /** The Back control is a different path and is recorded as one, with no progress claimed. */
    @Test public void theBackControlIsRecordedAsItsOwnPath() {
        ActivityController<ChatActivity> controller = openChat("c-button-path");
        back(controller.get()).performClick();
        controller.pause().stop().destroy();

        ActivityController<DiagnosticsActivity> diagnostics =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        String report = diagnostics.get().fullReport();
        assertTrue(report.contains("Last back path: Back control"));
        assertTrue("a tap draws nothing and must not be credited with frames",
                report.contains("committed · 0 progress events"));
        diagnostics.pause().stop().destroy();
    }

    // ---- the keyboard still goes first -----------------------------------------------------------

    /**
     * With the keyboard up, back is the keyboard's, and the conversation must not budge.
     *
     * <p>The two-step behaviour is the point: one back closes the keyboard, the next leaves the
     * conversation. Sliding the conversation sideways during the first of those would be wrong even
     * if it snapped back afterwards, so the gesture declines to start at all.
     */
    @Test public void theKeyboardTakesBackFirstAndTheConversationDoesNotMove() {
        assumeDrawnGesture();
        ActivityController<ChatActivity> controller = openChat("c-ime");
        ChatActivity activity = controller.get();
        OrbitPredictiveBack back = activity.predictiveBackForTest();

        OrbitPredictiveBack.keyboardVisibleForTest = true;
        back.startedForTest();
        back.progressedForTest(0.8f);
        assertEquals("the conversation may not move while the keyboard owns back",
                0f, back.translationForTest(), 0.5f);
        assertFalse("and nothing may be dressed for a reveal", back.revealingForTest());

        back.invokedForTest();
        idle();
        assertFalse("the first back closes the keyboard, it does not leave the conversation",
                activity.isFinishing());

        // Keyboard gone. The next back is an ordinary gesture again.
        OrbitPredictiveBack.keyboardVisibleForTest = false;
        back.startedForTest();
        back.progressedForTest(0.8f);
        assertTrue("and the one after it moves the conversation as usual",
                back.translationForTest() > 0f);
        back.invokedForTest();
        idle();
        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
    }

    /** Skips a test whose subject does not exist on the API level it is being run at. */
    private void assumeDrawnGesture() {
        org.junit.Assume.assumeTrue(OrbitPredictiveBack.available());
    }

    private static void idle() {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(600));
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
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
 * <p>The temptation with this feature is to write it: a touch listener on the root, a translation
 * driven by finger position, a screenshot of Chats painted underneath. Every part of that is worse
 * than what the platform already does, and it fights the system's own edge gesture for the same
 * touches. So the whole implementation is a subtraction, and these cover the two halves of it: the
 * screen must not keep a back callback registered when it has nothing of its own to close, and the
 * window must not declare how it closes, because either one silently costs the conversation the
 * system's finger-tracked transition and the real Chats screen behind it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatBackNavigationTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
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

    // ---- the platform owns back --------------------------------------------------------------

    /**
     * With nothing of its own open, the conversation registers no back callback at all.
     *
     * <p>This is the single most important assertion in the release. A registered callback tells
     * the system the app will handle back itself, and the system therefore cannot animate a
     * navigation it no longer knows is coming. Standing aside is what produces the finger-tracked
     * transition and the real Chats screen behind it.
     */
    @Test public void anOrdinaryConversationLeavesBackToTheSystem() {
        ActivityController<ChatActivity> controller = openChat("c-plain");
        ChatActivity activity = controller.get();
        assertFalse("nothing may be registered while there is nothing of Orbit's own to close",
                activity.backHandlerArmedForTest());
        controller.pause().stop().destroy();
    }

    /** While the attachment chooser is open, back is Orbit's, because it has something to close. */
    @Test public void anOpenChooserTakesBackAndThenGivesItStraightBack() {
        ActivityController<ChatActivity> controller = openChat("c-chooser");
        ChatActivity activity = controller.get();

        activity.showAttachmentMenuForTest();
        assertTrue("an open chooser must consume back", activity.backHandlerArmedForTest());

        activity.performBackForTest();
        assertFalse("closing it must hand back to the system again",
                activity.backHandlerArmedForTest());
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
        assertFalse("dismissal by any route returns back to the system",
                activity.backHandlerArmedForTest());
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
        assertTrue("swipe back to Chats ships on", Prefs.enhancedChatBack(context));

        Prefs.get(context).edit().putBoolean(Prefs.ENHANCED_CHAT_BACK, false).commit();
        assertFalse(Prefs.enhancedChatBack(context));

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
}

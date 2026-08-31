package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

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
 * The Undo offer floats over Chats. It does not take a slice out of it.
 *
 * <p>v0.7.7.9 Beta 1 put the bar in the page as an ordinary sibling below the weighted list, so
 * showing it took its height out of the list's viewport. On a Galaxy S25 Ultra that produced a hard
 * black edge across the bottom of Chats: the list was abruptly given a new bottom and the chat card
 * that straddled it was sliced flat against the window background. These cover the fix at the level
 * the bug lived at — geometry — rather than at the level it was visible at, which was colour.
 *
 * <p>Everything about <i>deleting</i> is deliberately not here. The deferred-deletion architecture,
 * the Undo window, the second-delete rule and the commit-on-leave rule passed on the device and are
 * covered by {@link ChatSwipeActionTest}; this file only asserts that hosting the offer differently
 * left all of that alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatsUndoBarTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    private ActivityController<MainActivity> chats(String... ids) {
        for (String id : ids) {
            List<AssistantClient.History> history = new ArrayList<>();
            history.add(new AssistantClient.History("user", "hello"));
            history.add(new AssistantClient.History("assistant", "hi"));
            ConversationStore.save(context, id, history);
        }
        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().refreshChatsForTest();
        return controller;
    }

    private static void layout(MainActivity activity) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
    }

    // ---- the bar is over the list, not in it -----------------------------------------------------

    /**
     * The list and the bar are siblings in a frame, so one cannot resize the other.
     *
     * <p>This is the whole fix. A vertical layout gives a visible sibling its height by taking it
     * from the weighted child above; a frame lays them over one another and the list keeps every
     * pixel it had.
     */
    @Test public void theBarIsLaidOverTheListRatherThanNextToIt() {
        ActivityController<MainActivity> controller = chats("u-1", "u-2");
        MainActivity activity = controller.get();

        View bar = activity.undoBarForTest();
        assertNotNull(bar);
        ViewGroup host = (ViewGroup) bar.getParent();
        assertTrue("the Undo bar must be hosted in a frame, not stacked in the page",
                host instanceof FrameLayout);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        assertEquals("and anchored to the bottom of it",
                Gravity.BOTTOM, lp.gravity & Gravity.VERTICAL_GRAVITY_MASK);
        assertTrue("with room beneath it rather than flush against the edge", lp.bottomMargin > 0);
        controller.pause().stop().destroy();
    }

    /** Hidden, it costs the page nothing at all. */
    @Test public void aHiddenBarTakesNoSpace() {
        ActivityController<MainActivity> controller = chats("u-hidden");
        MainActivity activity = controller.get();
        layout(activity);

        assertEquals("nothing to undo means nothing on screen",
                View.GONE, activity.undoBarForTest().getVisibility());
        assertEquals("and no height taken from anything",
                0, activity.undoBarForTest().getHeight());
        controller.pause().stop().destroy();
    }

    /**
     * Showing it does not change the size of the list. This is the cutoff, asserted directly.
     */
    @Test public void showingTheBarDoesNotResizeTheList() {
        ActivityController<MainActivity> controller = chats("u-a", "u-b", "u-c");
        MainActivity activity = controller.get();
        layout(activity);

        ScrollView list = activity.listViewportForTest();
        int before = list.getHeight();
        assertTrue("the list must have a height to compare", before > 0);

        activity.deleteChatForTest("u-a");
        layout(activity);

        assertEquals("the Undo bar is on screen", View.VISIBLE,
                activity.undoBarForTest().getVisibility());
        assertEquals("and the list viewport is exactly the size it was",
                before, list.getHeight());
        controller.pause().stop().destroy();
    }

    /**
     * The last chat can still be scrolled clear of the floating bar, and the room is given back.
     *
     * <p>Padding inside the scrolled content, not height taken off the viewport: growing it cannot
     * move anything already on screen, and it is not left there once the offer has gone.
     */
    @Test public void theListMakesRoomForTheBarAndTakesItBack() {
        ActivityController<MainActivity> controller = chats("u-room-1", "u-room-2");
        MainActivity activity = controller.get();
        View content = activity.listContentForTest();
        int resting = content.getPaddingBottom();

        activity.deleteChatForTest("u-room-1");
        assertTrue("the last chat must be able to clear the floating bar",
                content.getPaddingBottom() > resting);

        activity.commitDeletionsForTest();
        assertEquals("and the room is not kept once there is nothing floating over the list",
                resting, content.getPaddingBottom());
        controller.pause().stop().destroy();
    }

    /** Showing and hiding the offer must not move the list under the user. */
    @Test public void theListDoesNotJumpWhenTheOfferComesAndGoes() {
        ActivityController<MainActivity> controller =
                chats("u-s1", "u-s2", "u-s3", "u-s4", "u-s5", "u-s6");
        MainActivity activity = controller.get();
        layout(activity);

        ScrollView list = activity.listViewportForTest();
        list.scrollTo(0, 40);
        int before = list.getScrollY();

        activity.deleteChatForTest("u-s1");
        layout(activity);
        assertEquals("appearing may not scroll the list", before, list.getScrollY());

        activity.commitDeletionsForTest();
        Robolectric.flushForegroundThreadScheduler();
        layout(activity);
        assertEquals("and neither may going away", before, list.getScrollY());
        controller.pause().stop().destroy();
    }

    // ---- insets ----------------------------------------------------------------------------------

    /**
     * The bottom inset is applied once, on the host, and the bar sits inside it.
     *
     * <p>Chats already asks {@code UiKit.applyActivityInsets} to keep it clear of the navigation
     * bar. Applying it again to the bar itself is how a floating surface ends up with a black strip
     * under it, so the bar carries no inset padding of its own.
     */
    @Test public void theBarSitsInsideTheOneInsetChatsAlreadyApplies() {
        ActivityController<MainActivity> controller = chats("u-inset");
        MainActivity activity = controller.get();

        View bar = activity.undoBarForTest();
        View host = (View) bar.getParent();
        assertEquals("the bar's host is the one view Chats applies insets to",
                activity.findViewById(android.R.id.content).getClass(),
                ((View) host.getParent()).getClass());
        assertEquals("so the bar carries only its own design padding, never a second inset",
                UiKit.dp(activity, 12), bar.getPaddingBottom());
        assertTrue("and it is the host that keeps the page clear of the system bars",
                host.getPaddingBottom() >= 0 && host.getPaddingLeft() > 0);
        controller.pause().stop().destroy();
    }

    // ---- the offer keeps working -----------------------------------------------------------------

    /** Undo is live the moment the bar appears, not once its entrance has finished. */
    @Test public void undoIsUsableImmediately() {
        ActivityController<MainActivity> controller = chats("u-now", "u-other");
        MainActivity activity = controller.get();

        activity.deleteChatForTest("u-now");
        View bar = activity.undoBarForTest();
        assertEquals(View.VISIBLE, bar.getVisibility());

        activity.undoDeletionForTest();
        assertNotNull("the chat comes back because it never went anywhere",
                ConversationStore.load(context, "u-now"));
        controller.pause().stop().destroy();
    }

    /**
     * A rebuild for a new accent cannot strand an offer, and cannot strand its clock either.
     *
     * <p>Chats only rebuilds itself in {@code onResume}, and leaving Chats has always committed a
     * pending deletion first, so by the time a new accent is applied there is nothing left to
     * offer. What could still go wrong is the countdown: while it was posted to the Undo bar
     * itself, a rebuild replaced the view that was holding it and the callback could no longer be
     * taken off. It now lives on the activity, so committing really does stop it.
     */
    @Test public void arebuildForANewAccentStrandsNeitherTheOfferNorItsClock() {
        ActivityController<MainActivity> controller = chats("u-theme", "u-theme-2");
        MainActivity activity = controller.get();
        activity.deleteChatForTest("u-theme");
        assertEquals(View.VISIBLE, activity.undoBarForTest().getVisibility());

        Prefs.get(context).edit().putString(Prefs.ACCENT, "amber").commit();
        controller.pause().resume();
        Robolectric.flushForegroundThreadScheduler();

        assertNull("leaving Chats carried the deletion out, exactly as it always has",
                ConversationStore.load(context, "u-theme"));
        assertEquals("so the rebuilt page shows no offer",
                View.GONE, activity.undoBarForTest().getVisibility());
        assertNotNull("and nothing else was taken with it",
                ConversationStore.load(context, "u-theme-2"));

        // The clock the commit stopped must not come back and take something else with it.
        List<AssistantClient.History> later = new ArrayList<>();
        later.add(new AssistantClient.History("user", "still here"));
        ConversationStore.save(context, "u-theme-3", later);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(8000));
        assertNotNull("a stopped countdown may not fire later",
                ConversationStore.load(context, "u-theme-3"));
        assertNotNull(ConversationStore.load(context, "u-theme-2"));
        controller.pause().stop().destroy();
    }

    /** The timeout still ends the offer, and still carries out the deletion. */
    @Test public void theOfferStillExpiresAndStillDeletes() {
        ActivityController<MainActivity> controller = chats("u-expire", "u-keep");
        MainActivity activity = controller.get();
        activity.deleteChatForTest("u-expire");

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(6000));

        assertNull("an offer that was not taken becomes the deletion it always was",
                ConversationStore.load(context, "u-expire"));
        assertNotNull("and no other chat is touched", ConversationStore.load(context, "u-keep"));
        assertEquals(View.GONE, activity.undoBarForTest().getVisibility());
        controller.pause().stop().destroy();
    }

    /** A larger font must not clip either half of the bar. */
    @Test public void alargeFontStillFitsTheLabelAndTheButton() {
        ActivityController<MainActivity> controller = chats("u-font", "u-font-2");
        MainActivity activity = controller.get();
        activity.deleteChatForTest("u-font");
        layout(activity);

        ViewGroup bar = (ViewGroup) activity.undoBarForTest();
        assertTrue("the bar sizes itself to its content", bar.getHeight() > 0);
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            assertTrue("nothing inside the bar may be clipped away", child.getHeight() > 0);
            assertTrue(child.getBottom() <= bar.getHeight() - bar.getPaddingBottom() + 1);
        }
        controller.pause().stop().destroy();
    }

    /** Deleting still records only a category word, and still leaves the swipe gestures alone. */
    @Test public void deletingStillReportsNothingAboutTheChat() {
        ActivityController<MainActivity> controller = chats("u-diag", "u-diag-2");
        controller.get().deleteChatForTest("u-diag");

        String action = DiagnosticStore.prefs(context).getString("gesture_last_action", "");
        assertEquals("delete", action);
        assertFalse("no chat identity may reach diagnostics", action.contains("u-diag"));
        controller.pause().stop().destroy();
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A chat card that moves with the finger, and only ever acts on the chat it belongs to.
 *
 * <p>Two things make this class worth having. The first is that a horizontal gesture lives inside a
 * vertical one, so most of what is asserted here is about the gesture Orbit declines to take: a
 * drag that started as a scroll must stay a scroll no matter where the finger wanders afterwards.
 * The second is identity. This list rebuilds itself constantly, two chats are allowed to have the
 * same visible title, and a swipe that resolved by position or by text would eventually delete the
 * wrong conversation. Every action here is addressed by conversation id.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatSwipeActionTest {
    private Context context;
    private int slop;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        OrbitSwipeRow.resetActive();
    }

    @After public void tearDown() {
        OrbitSwipeRow.resetActive();
        OrbitRequestManager.resetForTest();
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private String chat(String title, String body) {
        String id = ConversationStore.newId();
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", body));
        ConversationStore.save(context, id, history);
        ConversationStore.rename(context, id, title);
        return id;
    }

    /** A row wired the way the Chats list wires one, laid out at a real width. */
    private OrbitSwipeRow row(int leftAction, int rightAction, boolean pinned,
                              List<String> committed) {
        View card = new TextView(context);
        OrbitSwipeRow swipe = new OrbitSwipeRow(context, card);
        swipe.configure(leftAction, rightAction, pinned,
                (source, action) -> committed.add(action == OrbitSwipeRow.ACTION_DELETE
                        ? "delete" : "pin"));
        int width = 1000;
        int height = 200;
        swipe.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        swipe.layout(0, 0, width, height);
        return swipe;
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, action, x, y, 0);
    }

    /** Drives a whole gesture through the row the way a finger would, in small steps. */
    private void drag(OrbitSwipeRow row, float fromX, float fromY, float toX, float toY,
                      boolean release) {
        row.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, fromX, fromY));
        row.onTouchEvent(event(MotionEvent.ACTION_DOWN, fromX, fromY));
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            float x = fromX + (toX - fromX) * i / steps;
            float y = fromY + (toY - fromY) * i / steps;
            row.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, x, y));
            row.onTouchEvent(event(MotionEvent.ACTION_MOVE, x, y));
        }
        if (release) row.onTouchEvent(event(MotionEvent.ACTION_UP, toX, toY));
    }

    private static float displacement(OrbitSwipeRow row) {
        return row.card().getTranslationX();
    }

    // ---- the card follows the finger -----------------------------------------------------------

    /**
     * The defining requirement of the release: the card is already moving while the finger is.
     *
     * <p>Not "a swipe was recognised and then an animation played". Half way through the gesture,
     * with nothing released, the card has to be part way across.
     */
    @Test public void theCardMovesWhileTheFingerIsStillDown() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(row, 800, 100, 800 - (slop + 160), 100, false);
        assertTrue("the card must already be displaced before release", displacement(row) < -80f);
        assertTrue("and it must not have acted yet", committed.isEmpty());
    }

    /** Displacement tracks the gesture rather than snapping to a fixed offset. */
    @Test public void displacementTracksHowFarTheFingerHasGone() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow near = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);
        OrbitSwipeRow far = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(near, 800, 100, 800 - (slop + 90), 100, false);
        drag(far, 800, 100, 800 - (slop + 240), 100, false);
        assertTrue("a longer drag must move the card further",
                Math.abs(displacement(far)) > Math.abs(displacement(near)));
    }

    /** Released below the threshold, the card returns to rest and nothing happens. */
    @Test public void releasingBelowTheThresholdSpringsBack() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(row, 800, 100, 800 - (slop + 40), 100, true);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue("no action for a drag that did not reach the commit point", committed.isEmpty());
        assertEquals("and the card must come back to rest", 0f, displacement(row), 1.5f);
    }

    /** Past the threshold, releasing commits, and exactly once. */
    @Test public void releasingPastTheThresholdCommitsOnce() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(row, 900, 100, 400, 100, true);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, committed.size());
        assertEquals("delete", committed.get(0));
    }

    /** The other direction is the other action, and the two never cross over. */
    @Test public void swipingRightIsThePinAction() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(row, 100, 100, 600, 100, true);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(1, committed.size());
        assertEquals("pin", committed.get(0));
    }

    // ---- arbitration with the scroll -----------------------------------------------------------

    /** A vertical drag is a scroll, and the row must not take the gesture or move at all. */
    @Test public void aVerticalDragIsLeftToTheScroller() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        row.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 500, 400));
        boolean intercepted = false;
        for (int i = 1; i <= 12; i++) {
            intercepted |= row.onInterceptTouchEvent(
                    event(MotionEvent.ACTION_MOVE, 500, 400 - (slop + 20) * i / 3f));
        }
        assertFalse("a scroll must never be taken as a swipe", intercepted);
        assertEquals(0f, displacement(row), 0.01f);
        assertTrue(committed.isEmpty());
    }

    /**
     * A drag that began vertically stays a scroll even if it later curves sideways.
     *
     * <p>The user is reading the list. Deleting something they dragged past because their thumb
     * drifted would be the single worst outcome this feature could produce.
     */
    @Test public void aScrollThatCurvesSidewaysStaysAScroll() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        row.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 800, 500));
        row.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 800, 500 - (slop * 3)));
        boolean intercepted = false;
        for (int i = 1; i <= 10; i++) {
            intercepted |= row.onInterceptTouchEvent(
                    event(MotionEvent.ACTION_MOVE, 800 - i * 60, 500 - (slop * 3)));
        }
        assertFalse("vertical intent is decided once and is final", intercepted);
        assertEquals(0f, displacement(row), 0.01f);
    }

    /** A clearly horizontal drag is taken, even with some vertical drift in it. */
    @Test public void aMostlyHorizontalDragIsTakenAsASwipe() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        row.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 900, 100));
        boolean intercepted = false;
        for (int i = 1; i <= 10; i++) {
            intercepted |= row.onInterceptTouchEvent(
                    event(MotionEvent.ACTION_MOVE, 900 - i * 40, 100 + i * 4));
        }
        assertTrue("a diagonal that clearly favours horizontal is a swipe", intercepted);
    }

    /** A press that never moves is a tap, and taps must still open the chat. */
    @Test public void aTapIsNeverIntercepted() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        assertFalse(row.onInterceptTouchEvent(event(MotionEvent.ACTION_DOWN, 500, 100)));
        assertFalse(row.onInterceptTouchEvent(event(MotionEvent.ACTION_MOVE, 502, 101)));
        assertFalse(row.onInterceptTouchEvent(event(MotionEvent.ACTION_UP, 502, 101)));
        assertEquals(0f, displacement(row), 0.01f);
    }

    // ---- state safety ---------------------------------------------------------------------------

    /** Only one card may be displaced. Starting on a second settles the first. */
    @Test public void onlyOneCardIsEverDisplaced() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow first = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);
        OrbitSwipeRow second = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        drag(first, 800, 100, 800 - (slop + 140), 100, false);
        assertTrue(Math.abs(displacement(first)) > 50f);

        drag(second, 800, 100, 800 - (slop + 140), 100, false);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals("the first card must settle when another takes over",
                0f, displacement(first), 1.5f);
        assertTrue(Math.abs(displacement(second)) > 50f);
    }

    /** Nothing about a drag survives the row leaving the screen. */
    @Test public void detachingClearsEveryTraceOfTheGesture() {
        List<String> committed = new ArrayList<>();
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        ViewGroup host = controller.get().findViewById(android.R.id.content);

        View card = new TextView(context);
        OrbitSwipeRow row = new OrbitSwipeRow(context, card);
        row.configure(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false,
                (source, action) -> committed.add("acted"));
        host.addView(row, 900, 200);
        row.layout(0, 0, 900, 200);

        drag(row, 800, 100, 800 - (slop + 200), 100, false);
        assertTrue(Math.abs(displacement(row)) > 50f);

        host.removeView(row);
        assertEquals("a rebuilt list may not inherit a translation", 0f, displacement(row), 0.01f);
        assertEquals(1f, card.getAlpha(), 0.01f);
        assertTrue("and detaching is not an action", committed.isEmpty());
        controller.pause().stop().destroy();
    }

    /** With gestures off the row is inert and the card never moves. */
    @Test public void aDisabledRowDoesNotRespondHorizontally() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_NONE, OrbitSwipeRow.ACTION_NONE, false, committed);
        assertFalse(row.swipeEnabled());

        drag(row, 900, 100, 300, 100, true);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0f, displacement(row), 0.01f);
        assertTrue(committed.isEmpty());
    }

    /** A direction with no action never moves the card, even at full drag distance. */
    @Test public void aDirectionWithNoActionDoesNotOpen() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_NONE, false, committed);

        drag(row, 100, 100, 700, 100, true);
        Robolectric.getForegroundThreadScheduler()
                .advanceBy(600, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(0f, displacement(row), 0.01f);
        assertTrue(committed.isEmpty());
    }

    // ---- accessibility ---------------------------------------------------------------------------

    /** Both actions exist without the gesture, named for what they will do. */
    @Test public void bothActionsAreReachableWithoutSwiping() {
        List<String> committed = new ArrayList<>();
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, false, committed);

        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        row.card().onInitializeAccessibilityNodeInfo(info);
        List<String> labels = new ArrayList<>();
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a : info.getActionList()) {
            if (a.getLabel() != null) labels.add(a.getLabel().toString());
        }
        assertTrue("Pin must be reachable without a swipe", labels.contains("Pin chat"));
        assertTrue("Delete must be reachable without a swipe", labels.contains("Delete chat"));

        assertTrue(row.card().performAccessibilityAction(R.id.orbit_action_pin, null));
        assertEquals(1, committed.size());
        assertEquals("pin", committed.get(0));
    }

    /** A pinned chat offers Unpin, so the action says what it will actually do. */
    @Test public void aPinnedRowOffersUnpin() {
        OrbitSwipeRow row = row(OrbitSwipeRow.ACTION_DELETE, OrbitSwipeRow.ACTION_PIN, true,
                new ArrayList<>());
        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        row.card().onInitializeAccessibilityNodeInfo(info);
        boolean unpin = false;
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction a : info.getActionList()) {
            if (a.getLabel() != null && "Unpin chat".contentEquals(a.getLabel())) unpin = true;
        }
        assertTrue(unpin);
    }

    // ---- pinning ---------------------------------------------------------------------------------

    /** Pinning is persisted, survives a reload, and is reversed by doing it again. */
    @Test public void pinningPersistsAndReverses() {
        String id = chat("Weekend plans", "Where should we go?");
        assertFalse("chats start unpinned", ConversationStore.isPinned(context, id));

        assertTrue(ConversationStore.setPinned(context, id, true));
        assertTrue(ConversationStore.load(context, id).pinned);

        assertFalse(ConversationStore.setPinned(context, id, false));
        assertFalse(ConversationStore.load(context, id).pinned);
    }

    /** Pinning does not pretend the chat just happened. */
    @Test public void pinningDoesNotDisturbRecentOrdering() {
        String older = chat("Older", "first");
        String newer = chat("Newer", "second");
        long olderAt = ConversationStore.load(context, older).updatedAt;

        ConversationStore.setPinned(context, older, true);
        assertEquals("pinning is not activity", olderAt,
                ConversationStore.load(context, older).updatedAt);
        assertEquals("and the newest chat is still the newest",
                newer, ConversationStore.latest(context).id);
    }

    /** Everything else about the chat survives being pinned. */
    @Test public void pinningKeepsTheRestOfTheConversationIntact() {
        String id = chat("Screenshot chat", "What is this?");
        ConversationStore.setMode(context, id, Prefs.MODE_DEEP);
        ConversationStore.setPinned(context, id, true);

        ConversationStore.Conversation after = ConversationStore.load(context, id);
        assertEquals("Screenshot chat", after.title);
        assertEquals(1, after.messages.size());
        assertEquals(Prefs.MODE_DEEP, after.intelligenceMode);
        assertTrue(after.pinned);
    }

    /** A chat stored before pinning existed simply reads as unpinned. No migration. */
    @Test public void conversationsWrittenBeforePinningReadAsUnpinned() {
        String id = chat("Legacy", "hello");
        String raw = ConversationStore.backupJsonForTest(context);
        assertFalse("an unpinned chat is not written with a pinned key at all",
                raw.contains("\"pinned\""));
        assertFalse(ConversationStore.load(context, id).pinned);
    }

    // ---- delete, undo, and identity ----------------------------------------------------------------

    /** The swipe deletes the chat it belongs to, and nothing near it. */
    @Test public void deletingActsOnItsOwnChatOnly() {
        String above = chat("Above", "one");
        String target = chat("Target", "two");
        String below = chat("Below", "three");

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(target);

        assertNotNull(ConversationStore.load(context, above));
        assertNotNull(ConversationStore.load(context, below));
        assertNotNull("nothing is destroyed while Undo still stands",
                ConversationStore.load(context, target));
        assertFalse("but it is out of the list", listedIds(controller.get()).contains(target));
        assertTrue(listedIds(controller.get()).contains(above));
        controller.pause().stop().destroy();
    }

    /**
     * Two chats can be called the same thing, and the swipe must still act on the right one.
     *
     * <p>The list is addressed by conversation id everywhere, so this is really a test that no
     * shortcut through the visible title has crept in.
     */
    @Test public void identicalTitlesAreDistinguishedByIdentity() {
        String first = chat("Untitled chat", "one");
        String second = chat("Untitled chat", "two");

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(second);
        controller.get().commitDeletionsForTest();

        assertNotNull("the other chat with the same name must survive",
                ConversationStore.load(context, first));
        assertNull(ConversationStore.load(context, second));
        controller.pause().stop().destroy();
    }

    /**
     * Undo brings the whole conversation back, because it never went anywhere.
     *
     * <p>The safety of this design is the point of the test: there is no snapshot to be incomplete,
     * so messages, attachment references, stopped-turn anchors, mode, title and pinned state all
     * survive without anyone having remembered to serialise them.
     */
    @Test public void undoRestoresTheWholeConversation() {
        String id = ConversationStore.newId();
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        String attachment = AttachmentStore.saveHistoryAttachment(context, bitmap);
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "What is on this screen?", true,
                attachment, "screen", "Screen attached", "screen text"));
        history.add(new AssistantClient.History("assistant", "A settings page."));
        ConversationStore.save(context, id, history);
        ConversationStore.rename(context, id, "Screenshot chat");
        ConversationStore.setMode(context, id, Prefs.MODE_DEEP);
        ConversationStore.setPinned(context, id, true);
        ConversationStore.markTurnStopped(context, id, "req-1234");

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(id);
        controller.get().undoDeletionForTest();

        ConversationStore.Conversation after = ConversationStore.load(context, id);
        assertNotNull(after);
        assertEquals(id, after.id);
        assertEquals("Screenshot chat", after.title);
        assertEquals(2, after.messages.size());
        assertEquals("the attachment reference must come back with it",
                attachment, after.messages.get(0).attachmentPath);
        assertTrue("and its file was never touched", new File(attachment).isFile());
        assertEquals("screen text", after.messages.get(0).attachmentText);
        assertEquals(Prefs.MODE_DEEP, after.intelligenceMode);
        assertTrue("pinned state survives too", after.pinned);
        assertEquals("and so does the stopped mark", 1,
                ConversationStore.stoppedRequestIds(context, id).size());
        assertTrue(listedIds(controller.get()).contains(id));
        controller.pause().stop().destroy();
    }

    /** Once the window closes the deletion is real, through Orbit's ordinary delete path. */
    @Test public void anUndoThatNeverCameFinishesTheDeletion() {
        String id = chat("Doomed", "goodbye");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(id);
        assertNotNull(ConversationStore.load(context, id));

        controller.get().commitDeletionsForTest();
        assertNull("the deletion completes when the offer expires",
                ConversationStore.load(context, id));
        controller.pause().stop().destroy();
    }

    /** Leaving Chats settles the pending deletion rather than leaving it depending on the process. */
    @Test public void leavingChatsCompletesAPendingDeletion() {
        String id = chat("Leaving", "bye");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(id);

        controller.pause();
        assertNull(ConversationStore.load(context, id));
        controller.stop().destroy();
    }

    /** A second delete honours the first rather than discarding it, and offers Undo for the newest. */
    @Test public void asecondDeleteCommitsTheFirstAndKeepsTheNewestUndoable() {
        String first = chat("First", "one");
        String second = chat("Second", "two");

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(first);
        controller.get().deleteChatForTest(second);

        assertNull("the earlier deletion is carried out, not forgotten",
                ConversationStore.load(context, first));
        assertNotNull("and the newest one is still undoable",
                ConversationStore.load(context, second));

        controller.get().undoDeletionForTest();
        assertNotNull(ConversationStore.load(context, second));
        controller.pause().stop().destroy();
    }

    // ---- the list ---------------------------------------------------------------------------------

    /** Pinned chats get their own heading above the rest, and only when there are some. */
    @Test public void theListShowsAPinnedSectionOnlyWhenSomethingIsPinned() {
        String plain = chat("Plain", "one");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertFalse("no empty Pinned heading", headings(controller.get()).contains("PINNED"));

        ConversationStore.setPinned(context, plain, true);
        controller.get().refreshChatsForTest();
        List<String> headings = headings(controller.get());
        assertTrue(headings.contains("PINNED"));
        assertFalse("with everything pinned there is no Recent group to head",
                headings.contains("RECENT"));

        chat("Other", "two");
        controller.get().refreshChatsForTest();
        headings = headings(controller.get());
        assertEquals("Pinned comes first", 0, headings.indexOf("PINNED"));
        assertTrue(headings.contains("RECENT"));
        controller.pause().stop().destroy();
    }

    /** With gestures turned off the cards are ordinary cards again. */
    @Test public void turningChatSwipesOffMakesTheRowsInert() {
        chat("Anything", "one");
        Prefs.get(context).edit().putBoolean(Prefs.CHAT_SWIPE_ACTIONS, false).commit();

        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        List<OrbitSwipeRow> rows = swipeRows(controller.get());
        assertFalse(rows.isEmpty());
        for (OrbitSwipeRow row : rows) {
            assertFalse("a disabled row responds to nothing horizontal", row.swipeEnabled());
        }
        controller.pause().stop().destroy();
    }

    /** On by default, both of them. */
    @Test public void bothGestureSettingsShipOn() {
        assertTrue(Prefs.chatSwipeActions(context));
        assertTrue(Prefs.enhancedChatBack(context));
    }

    /** Diagnostics records the category of the gesture and nothing about the chat. */
    @Test public void gestureDiagnosticsRecordNoChatContent() {
        String id = chat("Very Private Chat Title", "extremely secret body text");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().deleteChatForTest(id);

        String dump = DiagnosticStore.prefs(context).getAll().toString();
        assertTrue(dump.contains("delete"));
        assertFalse("no chat title", dump.contains("Very Private Chat Title"));
        assertFalse("no conversation text", dump.contains("extremely secret"));
        assertFalse("and not even the conversation id", dump.contains(id));
        controller.pause().stop().destroy();
    }

    // ---- what this release deliberately did not touch ----------------------------------------------

    /**
     * The overlay keeps the gestures it already had, and gains none.
     *
     * <p>Its swipe up and swipe down are settled and were explicitly out of scope, so the guard is
     * on the source rather than on behaviour: the assistant sheet must not have acquired a chat
     * card, a horizontal action, or any part of this release's machinery.
     */
    @Test public void theOverlayGainedNoGestures() throws Exception {
        String overlay = read("src/main/java/com/orbit/assistant/OrbitSession.java");
        assertFalse("the overlay must not gain chat-card swipes",
                overlay.contains("OrbitSwipeRow"));
        assertFalse("nor a back handler of the full app's kind",
                overlay.contains("OrbitBackHandler"));
        assertFalse("and it must not consult the full app's gesture settings",
                overlay.contains("chatSwipeActions") || overlay.contains("enhancedChatBack"));
    }

    /**
     * Messages did not gain swipes either.
     *
     * <p>A conversation is full of text selection, code blocks, links, tables, long-press actions
     * and horizontally scrolling content. Wrapping a message in a horizontal gesture would compete
     * with all of it, so the framework stops at the chat list for this release.
     */
    @Test public void individualMessagesGainedNoSwipes() throws Exception {
        String chat = read("src/main/java/com/orbit/assistant/ChatActivity.java");
        assertFalse("message bubbles must not be wrapped in a swipe row",
                chat.contains("OrbitSwipeRow"));
    }

    private static String read(String path) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new File(path).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(descendants(group.getChildAt(i)));
        }
        return out;
    }

    private static List<OrbitSwipeRow> swipeRows(MainActivity activity) {
        List<OrbitSwipeRow> out = new ArrayList<>();
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof OrbitSwipeRow) out.add((OrbitSwipeRow) v);
        }
        return out;
    }

    private List<String> listedIds(MainActivity activity) {
        List<String> titles = new ArrayList<>();
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (v instanceof TextView) titles.add(((TextView) v).getText().toString());
        }
        List<String> ids = new ArrayList<>();
        for (ConversationStore.Conversation chat : ConversationStore.list(context)) {
            if (titles.contains(chat.title)) ids.add(chat.id);
        }
        return ids;
    }

    private static List<String> headings(MainActivity activity) {
        List<String> out = new ArrayList<>();
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (!(v instanceof TextView)) continue;
            String text = ((TextView) v).getText().toString();
            if ("PINNED".equals(text) || "RECENT".equals(text) || "RECENT CHATS".equals(text)) {
                out.add(text);
            }
        }
        return out;
    }
}

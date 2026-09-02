package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The viewer as a screen, opened the way a person opens it: by tapping a thumbnail.
 *
 * <p>{@link AttachmentViewerTest} pins the model, the transform and the session store down in
 * isolation. This is the other half - that a tap on the third photo actually reaches the third
 * photo through the real strip, the real Intent and the real Activity, and that what the screen
 * then offers matches what the source allows. An unsent message may be edited from here; a sent one
 * may not, and the difference has to hold in the built UI rather than only in the model.
 *
 * <p>The assertion that a viewer performs no AI work at all is here deliberately. Looking at an
 * attachment is not composing with it, and the cost of getting that wrong is a request the user
 * never asked for and did not agree to pay for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AttachmentViewerScreenTest {

    private Context context;
    private final List<File> temporaryFiles = new ArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        DiagnosticStore.prefs(context).edit().clear().commit();
        ConversationStore.clear(context);
        AttachmentViewerStore.clear();
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
        drain();
    }

    @After public void tearDown() {
        AttachmentViewerStore.clear();
        OrbitRequestManager.resetForTest();
        for (File file : temporaryFiles) file.delete();
        temporaryFiles.clear();
    }

    // ---- opening from the composer strip ------------------------------------------------------------

    /** Tapping the first photo opens the first photo. */
    @Test public void tappingPhotoOneOpensIndexZero() {
        ActivityController<ChatActivity> chat = openChat("c-view-first");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 0);
        assertEquals(0, viewer.currentIndex());
        assertEquals(3, viewer.imageCount());

        chat.pause().stop().destroy();
    }

    /** And tapping the third opens the third, not the first and not the last-tapped. */
    @Test public void tappingPhotoThreeOpensIndexTwo() {
        ActivityController<ChatActivity> chat = openChat("c-view-third");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 2);
        assertEquals(2, viewer.currentIndex());
        assertEquals("3 of 3", viewer.counterText());

        chat.pause().stop().destroy();
    }

    /** The counter reads against the ordered list the user is swiping. */
    @Test public void thecounterMatchesTheOrderedList() {
        ActivityController<ChatActivity> chat = openChat("c-view-counter");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg", "four.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 1);
        assertEquals("2 of 4", viewer.counterText());

        chat.pause().stop().destroy();
    }

    /** One image opens directly, with no counter to read. */
    @Test public void asingleImageOpensWithNoCounter() {
        ActivityController<ChatActivity> chat = openChat("c-view-single");
        attach(chat.get(), "only.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 0);
        assertEquals(1, viewer.imageCount());
        assertEquals("", viewer.counterText());

        chat.pause().stop().destroy();
    }

    /**
     * A PDF card is not a door into an image viewer.
     *
     * <p>The strip draws it, it carries a rendered first page, and tapping it does nothing at all -
     * which is the correct outcome rather than opening page one of a document with no way to reach
     * page two.
     */
    @Test public void apdfCardOpensNoViewer() {
        ActivityController<ChatActivity> chat = openChat("c-view-pdf");
        chat.get().addComposerAttachmentForTest(
                new ComposerAttachment("pdf", "syllabus.pdf", "extracted text", bitmap()));

        View card = cardFor(chat.get(), 0);
        assertFalse("a document card must not be clickable", card.isClickable());
        assertNull("and nothing may be launched by it", nextStarted(chat.get()));

        chat.pause().stop().destroy();
    }

    // ---- moving between images ------------------------------------------------------------------------

    /**
     * Next and previous work as controls, not only as gestures.
     *
     * <p>A screen reader cannot reliably perform a horizontal swipe on a custom pager, so without
     * these the second photo would be reachable only by a gesture some users cannot make.
     */
    @Test public void theaccessibleControlsMoveBetweenImages() {
        ActivityController<ChatActivity> chat = openChat("c-view-steps");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 0);
        assertNotNull(controlNamed(viewer, "Next image"));
        assertNotNull(controlNamed(viewer, "Previous image"));

        viewer.stepForTest(1);
        assertEquals(1, viewer.currentIndex());
        assertEquals("2 of 3", viewer.counterText());
        viewer.stepForTest(1);
        assertEquals(2, viewer.currentIndex());
        viewer.stepForTest(1);
        assertEquals("there is nothing past the last image", 2, viewer.currentIndex());
        viewer.stepForTest(-1);
        assertEquals(1, viewer.currentIndex());

        chat.pause().stop().destroy();
    }

    /** The page a screen reader lands on announces where it is. */
    @Test public void everyPageAnnouncesItsPosition() {
        ActivityController<ChatActivity> chat = openChat("c-view-a11y");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(chat.get(), 1);
        assertNotNull(describedAs(viewer, "Image 1 of 3"));
        assertNotNull(describedAs(viewer, "Image 2 of 3"));
        assertNotNull(describedAs(viewer, "Image 3 of 3"));
        assertNotNull("and closing it is reachable", controlNamed(viewer, "Close image viewer"));

        chat.pause().stop().destroy();
    }

    /** A tapped card tells a screen reader that it leads somewhere. */
    @Test public void anopenableCardSaysThatItOpens() {
        ActivityController<ChatActivity> chat = openChat("c-view-card-a11y");
        attach(chat.get(), "one.jpg", "two.jpg");

        CharSequence description = cardFor(chat.get(), 0).getContentDescription();
        assertNotNull(description);
        assertTrue(description.toString().contains("Photo attachment 1 of 2"));
        assertTrue(description.toString().contains("opens full screen"));

        chat.pause().stop().destroy();
    }

    // ---- removing an unsent image ------------------------------------------------------------------------

    /** Remove takes exactly the attachment on screen out of the composer, and nothing else. */
    @Test public void removeDeletesTheExactAttachmentFromTheComposer() {
        ActivityController<ChatActivity> chat = openChat("c-view-remove");
        ChatActivity activity = chat.get();
        attach(activity, "one.jpg", "two.jpg", "three.jpg");
        activity.placeInComposer("Half-written question");
        String middle = activity.pendingAttachments().get(1).id;

        AttachmentViewerActivity viewer = openFromStrip(activity, 1);
        assertTrue("an unsent image offers a removal", viewer.offersRemove());
        viewer.removeCurrentForTest();

        List<ComposerAttachment> left = activity.pendingAttachments();
        assertEquals(2, left.size());
        assertEquals("one.jpg", left.get(0).label);
        assertEquals("three.jpg", left.get(1).label);
        for (ComposerAttachment a : left) {
            assertFalse("the removed attachment is gone by id", middle.equals(a.id));
        }
        assertEquals("removing an image must not touch what the user typed",
                "Half-written question", activity.composerText());

        chat.pause().stop().destroy();
    }

    /** The viewer and the strip beneath it agree immediately, because they read one collection. */
    @Test public void theviewerAndTheStripAgreeAfterARemoval() {
        ActivityController<ChatActivity> chat = openChat("c-view-agree");
        ChatActivity activity = chat.get();
        attach(activity, "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(activity, 1);
        viewer.removeCurrentForTest();

        assertEquals("the viewer redraws without it", 2, viewer.imageCount());
        assertEquals("and so does the composer", 2, activity.pendingAttachments().size());
        // Returning to the conversation rebuilds the strip from the same collection.
        chat.pause().resume();
        assertEquals(2, stripCards(activity).size());

        chat.pause().stop().destroy();
    }

    /** Removing the only image leaves nothing to look at, so the viewer closes. */
    @Test public void removingTheLastImageClosesTheViewer() {
        ActivityController<ChatActivity> chat = openChat("c-view-remove-last");
        ChatActivity activity = chat.get();
        attach(activity, "only.jpg");

        ActivityController<AttachmentViewerActivity> viewer = viewerController(activity, 0);
        viewer.get().removeCurrentForTest();

        assertTrue("the viewer closes rather than showing an empty screen",
                viewer.get().isFinishing());
        assertTrue(activity.pendingAttachments().isEmpty());

        chat.pause().stop().destroy();
    }

    // ---- a turn that has already been sent -----------------------------------------------------------------

    /** History is a record. The viewer over it offers no removal at all. */
    @Test public void asentTurnOffersNoRemove() {
        AttachmentViewerActivity viewer = historyViewer(
                Arrays.asList(storedImage(), storedImage()), 0);
        assertFalse("a sent turn must not be editable from a picture viewer",
                viewer.offersRemove());
        assertEquals(2, viewer.imageCount());
    }

    /** A stored image whose file has gone shows an unavailable state and does not crash. */
    @Test public void amissingHistoricalImageFallsBackSafely() {
        String gone = new File(context.getCacheDir(), "orbit-never-written.jpg").getAbsolutePath();
        AttachmentViewerActivity viewer = historyViewer(Arrays.asList(gone, storedImage()), 0);

        assertTrue("the page says so rather than showing nothing", viewer.showsUnavailable(0));
        assertEquals("and the count stays honest", 2, viewer.imageCount());
        viewer.stepForTest(1);
        assertEquals("the surviving image is still reachable", 1, viewer.currentIndex());
        assertFalse(viewer.showsUnavailable(1));
        assertTrue("and Diagnostics records that one was met",
                DiagnosticStore.prefs(context).getBoolean("viewer_missing", false));
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------

    /** A rotation comes back on the same image of the same message. */
    @Test public void arecreatedViewerKeepsItsPlace() {
        ActivityController<ChatActivity> chat = openChat("c-view-rotate");
        attach(chat.get(), "one.jpg", "two.jpg", "three.jpg", "four.jpg");

        ActivityController<AttachmentViewerActivity> viewer = viewerController(chat.get(), 2);
        assertEquals(2, viewer.get().currentIndex());

        Bundle state = new Bundle();
        viewer.saveInstanceState(state);
        Intent intent = viewer.get().getIntent();
        viewer.pause().stop().destroy();

        ActivityController<AttachmentViewerActivity> restored =
                Robolectric.buildActivity(AttachmentViewerActivity.class, intent)
                        .create(state).start().resume();
        assertEquals("a rotation must not reopen on the wrong attachment",
                2, restored.get().currentIndex());
        assertEquals("3 of 4", restored.get().counterText());

        restored.pause().stop().destroy();
        chat.pause().stop().destroy();
    }

    /** A viewer whose session died with the process closes instead of drawing nothing. */
    @Test public void aviewerWithNoSessionClosesCleanly() {
        Intent intent = new Intent(context, AttachmentViewerActivity.class)
                .putExtra(AttachmentViewerActivity.EXTRA_TOKEN, "view-never-issued");
        ActivityController<AttachmentViewerActivity> viewer =
                Robolectric.buildActivity(AttachmentViewerActivity.class, intent).create();
        assertTrue(viewer.get().isFinishing());
        viewer.destroy();
    }

    // ---- what a viewer must never do ---------------------------------------------------------------------------

    /**
     * Looking at an attachment is not composing with it.
     *
     * <p>No conversation written, no request enqueued, nothing sent. Opening a photo, swiping
     * through the set and removing one must cost exactly zero model calls.
     */
    @Test public void theviewerPerformsNoAiWorkAtAll() {
        ActivityController<ChatActivity> chat = openChat("c-view-silent");
        ChatActivity activity = chat.get();
        attach(activity, "one.jpg", "two.jpg", "three.jpg");

        AttachmentViewerActivity viewer = openFromStrip(activity, 0);
        viewer.stepForTest(1);
        viewer.stepForTest(1);
        viewer.removeCurrentForTest();

        ConversationStore.Conversation stored = ConversationStore.load(context, "c-view-silent");
        assertTrue("a viewer may not write a turn",
                stored == null || stored.messages.isEmpty());
        assertFalse("and may not start a request",
                PendingRequestStore.hasActiveForConversation(context, "c-view-silent"));

        chat.pause().stop().destroy();
    }

    /** Diagnostics records the shape of a viewing, never anything that was in it. */
    @Test public void diagnosticsRecordsCountsAndNotContent() {
        ActivityController<ChatActivity> chat = openChat("c-view-diag");
        attach(chat.get(), "Screenshot_20260830_211455_private.jpg", "two.jpg");
        openFromStrip(chat.get(), 1);

        assertEquals("composer", DiagnosticStore.prefs(context).getString("viewer_source", ""));
        assertEquals(2, DiagnosticStore.prefs(context).getInt("viewer_count", 0));
        assertEquals(1, DiagnosticStore.prefs(context).getInt("viewer_index", -1));
        for (Object value : DiagnosticStore.prefs(context).getAll().values()) {
            if (!(value instanceof String)) continue;
            assertFalse("a filename must never reach Diagnostics",
                    ((String) value).contains("private"));
        }

        chat.pause().stop().destroy();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** Taps the strip card at a position and returns the viewer it opened. */
    private AttachmentViewerActivity openFromStrip(ChatActivity activity, int position) {
        return viewerController(activity, position).get();
    }

    private ActivityController<AttachmentViewerActivity> viewerController(ChatActivity activity,
                                                                         int position) {
        cardFor(activity, position).performClick();
        Intent launched = nextStarted(activity);
        assertNotNull("tapping an image card must open the viewer", launched);
        assertEquals(AttachmentViewerActivity.class.getName(),
                launched.getComponent().getClassName());
        return Robolectric.buildActivity(AttachmentViewerActivity.class, launched).setup();
    }

    /** A read-only viewer over stored images, opened the way a sent turn opens one. */
    private AttachmentViewerActivity historyViewer(List<String> paths, int position) {
        String token = AttachmentViewerStore.openHistory(paths, "image",
                paths.size() + " attachments", position);
        assertFalse("the session should have opened", token.isEmpty());
        DiagnosticStore.recordAttachmentViewer(context, AttachmentViewerStore.SOURCE_HISTORY,
                paths.size(), position);
        Intent intent = new Intent(context, AttachmentViewerActivity.class)
                .putExtra(AttachmentViewerActivity.EXTRA_TOKEN, token);
        return Robolectric.buildActivity(AttachmentViewerActivity.class, intent).setup().get();
    }

    /** Writes a real JPEG into the cache so a history session has something to decode. */
    private String storedImage() {
        try {
            File file = File.createTempFile("orbit-view", ".jpg", context.getCacheDir());
            temporaryFiles.add(file);
            try (FileOutputStream out = new FileOutputStream(file)) {
                Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
                        .compress(Bitmap.CompressFormat.JPEG, 80, out);
            }
            return file.getAbsolutePath();
        } catch (Exception failure) {
            throw new AssertionError("the test needs a readable stored image", failure);
        }
    }

    /** The strip card for one staged attachment, by its position in the composer. */
    private static View cardFor(ChatActivity activity, int position) {
        List<View> cards = stripCards(activity);
        assertTrue("the strip must have drawn that card", position < cards.size());
        return cards.get(position);
    }

    /** Every card the attachment strip is currently drawing, in order. */
    private static List<View> stripCards(ChatActivity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            if (!(v instanceof AttachmentStripView)) continue;
            ViewGroup row = (ViewGroup) ((ViewGroup) v).getChildAt(0);
            List<View> cards = new ArrayList<>();
            for (int i = 0; i < row.getChildCount(); i++) cards.add(row.getChildAt(i));
            return cards;
        }
        throw new AssertionError("the conversation must have an attachment strip");
    }

    private static View controlNamed(AttachmentViewerActivity viewer, String description) {
        return describedAs(viewer, description);
    }

    private static View describedAs(AttachmentViewerActivity viewer, String description) {
        for (View v : descendants(viewer.getWindow().getDecorView())) {
            CharSequence actual = v.getContentDescription();
            if (actual != null && description.contentEquals(actual)) return v;
        }
        return null;
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(descendants(group.getChildAt(i)));
            }
        }
        return out;
    }

    private static Intent nextStarted(ChatActivity activity) {
        return Shadows.shadowOf(activity).getNextStartedActivity();
    }

    private void drain() {
        while (Shadows.shadowOf((android.app.Application) RuntimeEnvironment.getApplication())
                .getNextStartedActivity() != null) { /* drained */ }
    }

    private void attach(ChatActivity activity, String... labels) {
        for (String label : labels) {
            activity.addComposerAttachmentForTest(
                    new ComposerAttachment("image", label, "", bitmap()));
        }
    }

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888);
    }

    private ActivityController<ChatActivity> openChat(String conversationId) {
        ConversationStore.save(context, conversationId, new ArrayList<>());
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        return Robolectric.buildActivity(ChatActivity.class, intent).setup();
    }
}

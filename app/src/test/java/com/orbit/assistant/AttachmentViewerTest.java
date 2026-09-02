package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the attachment viewer is looking at, and who owns a gesture while it is on screen.
 *
 * <p>Two things carry the weight here, and neither of them is drawing. The first is that the list
 * the viewer shows is not the list the strip draws: a message can hold a PDF and three photos, so
 * "the second photo" and "the second card" are different items and tapping one must never open the
 * other. The second is the arbitration - a zoomed photo being panned must not turn into the next
 * photo halfway through the drag, and a pinch must never be read as a swipe.
 *
 * <p>The removal cases are the destructive ones and are asserted by id rather than by position, in
 * a set where position and id disagree, because "removed the wrong photo" is the failure that
 * costs a user something they cannot get back from a composer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AttachmentViewerTest {

    @After public void tearDown() {
        AttachmentViewerStore.clear();
    }

    // ---- which images may be opened at all -------------------------------------------------------

    /** Photos, camera shots and screen captures are images. Documents are not. */
    @Test public void onlyImageAttachmentsCanBeOpened() {
        assertTrue(AttachmentViewerModel.isViewableImage("image"));
        assertTrue(AttachmentViewerModel.isViewableImage("camera"));
        assertTrue(AttachmentViewerModel.isViewableImage("screen"));
        assertTrue(AttachmentViewerModel.isViewableImage("screen_selection"));
    }

    /**
     * A PDF carries a rendered first page, and that is exactly why it is excluded.
     *
     * <p>Dropping a fifty page document into an image viewer would offer the user page one, full
     * screen, with no way to reach page two - a worse answer than the card they already had.
     */
    @Test public void adocumentIsNeverRoutedIntoTheImageViewer() {
        assertFalse(AttachmentViewerModel.isViewableImage("pdf"));
        assertFalse(AttachmentViewerModel.isViewableImage("file_text"));
        assertFalse(AttachmentViewerModel.isViewableImage("clipboard"));
        assertFalse(AttachmentViewerModel.isViewableImage(null));
        // A PDF card carries a bitmap, so "has an image" is not the test that keeps it out.
        assertFalse(AttachmentViewerModel.isViewable(attachment("pdf", "syllabus.pdf")));
        assertFalse("a text file has no picture at all",
                AttachmentViewerModel.isViewable(attachment("file_text", "notes.txt")));
        assertTrue(AttachmentViewerModel.isViewable(attachment("image", "photo.jpg")));
    }

    // ---- opening on the image that was tapped -----------------------------------------------------

    @Test public void tappingTheFirstPhotoOpensTheFirstPhoto() {
        ComposerAttachments composer = composerOf("image", "image", "image");
        String token = AttachmentViewerStore.openComposer(composer, idAt(composer, 0));
        assertEquals(0, session(token).model.index());
    }

    @Test public void tappingTheThirdPhotoOpensTheThirdPhoto() {
        ComposerAttachments composer = composerOf("image", "image", "image");
        String token = AttachmentViewerStore.openComposer(composer, idAt(composer, 2));
        assertEquals(2, session(token).model.index());
    }

    /**
     * The real mixed case: a PDF staged first, then three photos.
     *
     * <p>The strip draws four cards and the viewer holds three images, so the second photo sits at
     * card 3 and image 1. Tapping it has to open that photo, which is only possible because the
     * opening position is resolved from the attachment's own id.
     */
    @Test public void amixedSetOpensTheTappedImageAndNotItsCardPosition() {
        ComposerAttachments composer = composerOf("pdf", "image", "image", "image");
        String secondPhoto = idAt(composer, 2);

        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, secondPhoto));
        assertEquals("three images, not four cards", 3, session.model.size());
        assertEquals("the tapped photo, by identity", 1, session.model.index());
        assertEquals(secondPhoto, session.model.current().id);
    }

    /** A composer holding nothing viewable opens nothing at all. */
    @Test public void acomposerOfDocumentsOpensNoViewer() {
        assertEquals("", AttachmentViewerStore.openComposer(
                composerOf("pdf", "file_text"), "att-anything"));
        assertEquals("", AttachmentViewerStore.openComposer(null, "att-1"));
    }

    /** Attachment order is viewer order, unchanged. */
    @Test public void theviewerPreservesAttachmentOrder() {
        ComposerAttachments composer = composerOf("image", "image", "image", "image");
        List<String> expected = new ArrayList<>();
        for (ComposerAttachment a : composer.items()) expected.add(a.id);

        List<String> actual = new ArrayList<>();
        for (AttachmentViewerModel.Item item
                : session(AttachmentViewerStore.openComposer(composer, expected.get(0))).model.items()) {
            actual.add(item.id);
        }
        assertEquals(expected, actual);
    }

    // ---- the counter and what a screen reader hears ------------------------------------------------

    @Test public void thecounterMatchesTheOrderedList() {
        AttachmentViewerModel model = modelOf(5, true);
        assertEquals("1 of 5", model.counterText());
        model.moveTo(1);
        assertEquals("2 of 5", model.counterText());
        model.moveTo(4);
        assertEquals("5 of 5", model.counterText());
    }

    /** One image needs no counter, and a counter that always said "1 of 1" would be chrome. */
    @Test public void asingleImageShowsNoCounter() {
        assertEquals("", modelOf(1, true).counterText());
    }

    /**
     * A screen reader is always told the position, including for one image.
     *
     * <p>"Image 1 of 1" is how somebody who cannot see the screen learns there is nothing to swipe
     * to, which the hidden visual counter cannot tell them.
     */
    @Test public void ascreenReaderIsAlwaysToldThePosition() {
        assertEquals("Image 1 of 1", modelOf(1, true).descriptionAt(0));
        AttachmentViewerModel model = modelOf(5, true);
        assertEquals("Image 1 of 5", model.descriptionAt(0));
        assertEquals("Image 4 of 5", model.descriptionAt(3));
    }

    /** And is never made to read a forty-character camera filename to locate itself. */
    @Test public void ascreenReaderIsNotReadAGiantCameraFilename() {
        String filename = "Screenshot_20260830_211455_Orbit Assistant.jpg";
        AttachmentViewerModel model = new AttachmentViewerModel(java.util.Collections.singletonList(
                new AttachmentViewerModel.Item("att-1", "image", filename)), 0, true);
        assertEquals("Image 1 of 1", model.descriptionAt(0));
        assertFalse(model.descriptionAt(0).contains(filename));
    }

    /** The remove control names the image it would remove. */
    @Test public void theremoveControlSaysWhichImage() {
        AttachmentViewerModel model = modelOf(4, true);
        model.moveTo(2);
        assertEquals("Remove image 3 of 4", model.removeDescription());
        assertEquals("Remove this image", modelOf(1, true).removeDescription());
    }

    // ---- moving between images ---------------------------------------------------------------------

    @Test public void nextAndPreviousWalkTheListAndStopAtItsEnds() {
        AttachmentViewerModel model = modelOf(3, false);
        assertFalse(model.hasPrevious());
        assertTrue(model.next());
        assertEquals(1, model.index());
        assertTrue(model.next());
        assertEquals(2, model.index());
        assertFalse("there is nothing past the last image", model.next());
        assertFalse(model.hasNext());
        assertTrue(model.previous());
        assertEquals(1, model.index());
    }

    @Test public void asingleImageHasNowhereToGo() {
        AttachmentViewerModel model = modelOf(1, false);
        assertFalse(model.next());
        assertFalse(model.previous());
        assertEquals(0, model.index());
    }

    /** An out-of-range position lands somewhere real rather than throwing. */
    @Test public void apositionOutsideTheListIsClamped() {
        AttachmentViewerModel model = modelOf(3, false);
        model.moveTo(99);
        assertEquals(2, model.index());
        model.moveTo(-4);
        assertEquals(0, model.index());
    }

    // ---- zoom ----------------------------------------------------------------------------------------

    /** Zoom is bounded at both ends. No negative scale, no infinite scale, no vanishing image. */
    @Test public void zoomIsBounded() {
        ZoomPanController zoom = fitted();
        zoom.zoomTo(1000f, 500f, 500f);
        assertEquals(ZoomPanController.MAX_SCALE, zoom.scale(), 0.001f);
        zoom.zoomTo(0.01f, 500f, 500f);
        assertEquals("fit-to-screen is the floor", ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
        zoom.zoomTo(-3f, 500f, 500f);
        assertEquals(ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
    }

    /** A pathological gesture cannot produce a pathological transform. */
    @Test public void amalformedGestureCannotBreakTheTransform() {
        ZoomPanController zoom = fitted();
        zoom.zoomTo(Float.NaN, 10f, 10f);
        zoom.zoomTo(Float.POSITIVE_INFINITY, 10f, 10f);
        zoom.scaleBy(0f, 10f, 10f);
        zoom.scaleBy(Float.NaN, 10f, 10f);
        zoom.panBy(Float.NaN, Float.NEGATIVE_INFINITY);
        assertTrue(zoom.scale() >= ZoomPanController.MIN_SCALE);
        assertTrue(zoom.scale() <= ZoomPanController.MAX_SCALE);
        assertFalse(Float.isNaN(zoom.translationX()));
        assertFalse(Float.isNaN(zoom.translationY()));
    }

    /** A repeated pinch accumulates into the range and stays there. */
    @Test public void arepeatedPinchSettlesInsideTheRange() {
        ZoomPanController zoom = fitted();
        for (int i = 0; i < 200; i++) zoom.scaleBy(1.1f, 500f, 500f);
        assertEquals(ZoomPanController.MAX_SCALE, zoom.scale(), 0.001f);
        for (int i = 0; i < 400; i++) zoom.scaleBy(0.9f, 500f, 500f);
        assertEquals(ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
    }

    /** Double tap zooms in about the tapped point, and a second one puts it back. */
    @Test public void doubleTapTogglesZoom() {
        ZoomPanController zoom = fitted();
        assertFalse(zoom.isZoomed());
        zoom.toggleZoom(200f, 300f);
        assertEquals(ZoomPanController.DOUBLE_TAP_SCALE, zoom.scale(), 0.001f);
        assertTrue(zoom.isZoomed());
        zoom.toggleZoom(200f, 300f);
        assertEquals(ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
        assertEquals(0f, zoom.translationX(), 0.001f);
        assertEquals(0f, zoom.translationY(), 0.001f);
    }

    // ---- pan, and who owns a sideways drag -----------------------------------------------------------

    /** A fitted image cannot be panned. There is nothing off screen to pan to. */
    @Test public void afittedImageDoesNotPan() {
        ZoomPanController zoom = fitted();
        assertFalse(zoom.panBy(-200f, -200f));
        assertEquals(0f, zoom.translationX(), 0.001f);
        assertEquals(0f, zoom.translationY(), 0.001f);
    }

    @Test public void azoomedImagePansWithinItsOwnEdges() {
        ZoomPanController zoom = fitted();
        zoom.zoomTo(3f, 500f, 500f);
        assertTrue(zoom.panBy(-100f, 0f));
        assertTrue(zoom.translationX() < 0f);
        // Far past the edge, and it stops at the edge rather than sailing off into nothing.
        zoom.panBy(-100000f, 0f);
        assertEquals(-zoom.maxTranslationX(), zoom.translationX(), 0.5f);
    }

    /**
     * The arbitration, stated directly.
     *
     * <p>A fitted image declines a horizontal drag in both directions, which is what lets the swipe
     * change the image. A zoomed one claims it until its edge arrives, which is what stops a pan
     * turning into a page change halfway through.
     */
    @Test public void ahorizontalDragBelongsToThePagerOnlyWhenTheImageCannotUseIt() {
        ZoomPanController zoom = fitted();
        assertFalse("fitted: the pager takes it", zoom.canPanHorizontally(-1));
        assertFalse(zoom.canPanHorizontally(1));

        zoom.zoomTo(3f, 500f, 500f);
        assertTrue("zoomed and centred: the image keeps it", zoom.canPanHorizontally(-1));
        assertTrue(zoom.canPanHorizontally(1));

        // Panned hard against the left edge of the image: there is nothing further that way, so
        // the drag is handed on rather than held.
        zoom.panBy(100000f, 0f);
        assertFalse(zoom.canPanHorizontally(-1));
        assertTrue("and the other direction still has content", zoom.canPanHorizontally(1));
    }

    /** Zooming back out surrenders the gesture again. */
    @Test public void returningToFitGivesTheGestureBackToThePager() {
        ZoomPanController zoom = fitted();
        zoom.zoomTo(3f, 500f, 500f);
        assertTrue(zoom.canPanHorizontally(1));
        zoom.reset();
        assertFalse(zoom.canPanHorizontally(1));
        assertFalse(zoom.canPanHorizontally(-1));
    }

    /** A transform on nothing is not a transform. Bounds come first. */
    @Test public void anImagelessControllerAnswersNothing() {
        ZoomPanController zoom = new ZoomPanController();
        assertFalse(zoom.hasContent());
        assertFalse(zoom.canPanHorizontally(1));
        assertFalse(zoom.panBy(50f, 50f));
        zoom.zoomTo(3f, 10f, 10f);
        assertEquals(ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
    }

    /** New bounds are a new image, so a transform is never inherited across one. */
    @Test public void changingTheContentResetsTheTransform() {
        ZoomPanController zoom = fitted();
        zoom.zoomTo(3f, 100f, 100f);
        zoom.panBy(-80f, -40f);
        assertTrue(zoom.isZoomed());

        zoom.setBounds(1000f, 2000f, 900f, 600f);
        assertEquals(ZoomPanController.MIN_SCALE, zoom.scale(), 0.001f);
        assertEquals(0f, zoom.translationX(), 0.001f);
        assertEquals(0f, zoom.translationY(), 0.001f);
    }

    // ---- removing an unsent image ---------------------------------------------------------------------

    /**
     * Removing from the viewer removes exactly that attachment from the one canonical collection.
     *
     * <p>By id, in a set where the id and the position disagree, because a removal resolved by
     * position is how the wrong photo disappears.
     */
    @Test public void removingAnUnsentImageRemovesExactlyThatAttachment() {
        ComposerAttachments composer = composerOf("image", "image", "image");
        String first = idAt(composer, 0);
        String middle = idAt(composer, 1);
        String last = idAt(composer, 2);

        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, middle));
        assertTrue(session.removeCurrent());

        assertEquals(2, composer.size());
        assertEquals("the remaining attachments keep their order",
                Arrays.asList(first, last), idsOf(composer));
        assertEquals("and the viewer agrees", 2, session.model.size());
    }

    /** Removing the middle image lands on the one that took its place. */
    @Test public void removingTheMiddleImageSettlesOnItsSuccessor() {
        ComposerAttachments composer = composerOf("image", "image", "image");
        String last = idAt(composer, 2);
        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, idAt(composer, 1)));

        session.removeCurrent();
        assertEquals(1, session.model.index());
        assertEquals(last, session.model.current().id);
    }

    /** Removing the final image leaves nothing, which is the viewer closing. */
    @Test public void removingTheLastImageEmptiesTheViewer() {
        ComposerAttachments composer = composerOf("image");
        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, idAt(composer, 0)));

        assertTrue(session.removeCurrent());
        assertTrue(session.model.isEmpty());
        assertTrue("and the composer is empty too", composer.isEmpty());
    }

    /** A document staged beside the photos is never touched by an image removal. */
    @Test public void removingAPhotoLeavesADocumentAlone() {
        ComposerAttachments composer = composerOf("pdf", "image", "image");
        String document = idAt(composer, 0);
        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, idAt(composer, 1)));

        session.removeCurrent();
        assertEquals(2, composer.size());
        assertEquals("the PDF is still first", document, composer.items().get(0).id);
    }

    /** A viewer over a sent turn has no removal at all, and cannot be talked into one. */
    @Test public void asentTurnIsReadOnly() {
        AttachmentViewerStore.Session session = session(AttachmentViewerStore.openHistory(
                Arrays.asList("/tmp/a.jpg", "/tmp/b.jpg"), "image", "2 attachments", 0));
        assertFalse(session.removable());
        assertFalse(session.removeCurrent());
        assertEquals("history is untouched", 2, session.model.size());
        assertFalse(session.model.isRemovable());
        assertFalse(new AttachmentViewerModel(java.util.Collections.singletonList(
                new AttachmentViewerModel.Item("/tmp/a.jpg", "image", "")), 0, false)
                .removeCurrent());
    }

    // ---- sent turns, and images that are no longer there -------------------------------------------------

    /** A stored image opens at the position that was tapped. */
    @Test public void asentTurnOpensOnTheThumbnailThatWasTapped() {
        AttachmentViewerStore.Session session = session(AttachmentViewerStore.openHistory(
                Arrays.asList("/tmp/a.jpg", "/tmp/b.jpg", "/tmp/c.jpg"), "image", "3 attachments", 2));
        assertEquals(2, session.model.index());
        assertEquals("/tmp/c.jpg", session.model.current().id);
    }

    /**
     * A recorded image whose file has gone resolves to nothing, and the rest still work.
     *
     * <p>Nothing is fabricated and nothing crashes: the page says the image is unavailable and the
     * user can still swipe to the ones that survived.
     */
    @Test public void amissingStoredImageIsSurvived() throws Exception {
        File real = File.createTempFile("orbit-viewer", ".jpg");
        Bitmap bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(real)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        }
        String gone = new File(real.getParentFile(), "orbit-viewer-never-written.jpg")
                .getAbsolutePath();

        AttachmentViewerStore.Session session = session(AttachmentViewerStore.openHistory(
                Arrays.asList(gone, real.getAbsolutePath()), "image", "2 attachments", 0));
        assertNull("a file that is not there is not an image", session.imageAt(0));
        assertNotNull("and its neighbour still loads", session.imageAt(1));
        assertEquals("both are still listed, so the count stays honest", 2, session.model.size());
        real.delete();
    }

    /** A composer image is borrowed, never re-decoded: the viewer shows the bitmap it was given. */
    @Test public void acomposerImageIsTheComposersOwnBitmap() {
        ComposerAttachments composer = composerOf("image", "image");
        AttachmentViewerStore.Session session = session(
                AttachmentViewerStore.openComposer(composer, idAt(composer, 0)));
        assertSameBitmap(composer.items().get(0).image, session.imageAt(0));
        assertSameBitmap(composer.items().get(1).image, session.imageAt(1));
    }

    // ---- the session token ------------------------------------------------------------------------------

    /**
     * A viewer token is readable more than once, unlike a share token.
     *
     * <p>Deliberately: a viewer is a screen the user stays on, and every rotation recreates it. A
     * one-shot token would leave a rotated viewer looking at nothing.
     */
    @Test public void aviewerTokenSurvivesBeingRead() {
        ComposerAttachments composer = composerOf("image");
        String token = AttachmentViewerStore.openComposer(composer, idAt(composer, 0));
        assertNotNull(AttachmentViewerStore.peek(token));
        assertNotNull("a rotation must not lose the viewer", AttachmentViewerStore.peek(token));
        AttachmentViewerStore.close(token);
        assertNull("and closing it releases what it held", AttachmentViewerStore.peek(token));
    }

    @Test public void anunknownTokenResolvesToNothing() {
        assertNull(AttachmentViewerStore.peek(null));
        assertNull(AttachmentViewerStore.peek(""));
        assertNull(AttachmentViewerStore.peek("view-never-issued"));
    }

    /** Abandoned viewer sessions do not pile up holding other people's photographs. */
    @Test public void abandonedSessionsAreBounded() {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ComposerAttachments composer = composerOf("image");
            tokens.add(AttachmentViewerStore.openComposer(composer, idAt(composer, 0)));
        }
        int alive = 0;
        for (String token : tokens) if (AttachmentViewerStore.peek(token) != null) alive++;
        assertTrue("open viewers must not accumulate without limit", alive <= 2);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static void assertSameBitmap(Bitmap expected, Bitmap actual) {
        assertNotNull(actual);
        assertTrue("the viewer must borrow the composer bitmap rather than copy it",
                expected == actual);
    }

    private static AttachmentViewerStore.Session session(String token) {
        AttachmentViewerStore.Session session = AttachmentViewerStore.peek(token);
        assertNotNull("the viewer should have opened", session);
        return session;
    }

    private static AttachmentViewerModel modelOf(int count, boolean removable) {
        List<AttachmentViewerModel.Item> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new AttachmentViewerModel.Item("att-" + i, "image", "photo" + i + ".jpg"));
        }
        return new AttachmentViewerModel(items, 0, removable);
    }

    private static ZoomPanController fitted() {
        ZoomPanController zoom = new ZoomPanController();
        // A 1000x1000 viewport over a 1000x800 image: fitted at scale 1, pannable once zoomed.
        zoom.setBounds(1000f, 1000f, 1000f, 800f);
        return zoom;
    }

    private static ComposerAttachment attachment(String kind, String label) {
        boolean picture = !"file_text".equals(kind) && !"clipboard".equals(kind);
        return new ComposerAttachment(kind, label, "",
                picture ? Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888) : null);
    }

    private static ComposerAttachments composerOf(String... kinds) {
        ComposerAttachments composer = new ComposerAttachments();
        for (int i = 0; i < kinds.length; i++) {
            composer.add(attachment(kinds[i], kinds[i] + "-" + i));
        }
        return composer;
    }

    private static String idAt(ComposerAttachments composer, int index) {
        return composer.items().get(index).id;
    }

    private static List<String> idsOf(ComposerAttachments composer) {
        List<String> ids = new ArrayList<>();
        for (ComposerAttachment a : composer.items()) ids.add(a.id);
        return ids;
    }
}

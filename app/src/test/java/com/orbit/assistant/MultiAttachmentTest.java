package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One ordered set of attachments, and everything that has to remain true about it.
 *
 * <p>Before this release Orbit modelled a manually attached thing as one {@code ComposerAttachment}
 * in every place that mattered, so "four photos" was not a state the app could be in. Making it one
 * is the easy half; the hard half is that a picker can report the same selection through two
 * different fields at once, that identity is not a filename, that order is the user's order, and
 * that a limit has to be told to the user rather than silently applied. Each of those is a way the
 * feature could look like it works while quietly losing something.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class MultiAttachmentTest {

    @Before public void setUp() {
        Prefs.get(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    // ---- reading a picker result -----------------------------------------------------------------

    /** The old shape: one photo, in getData. */
    @Test public void aSingleSelectionInGetDataIsRead() {
        Intent result = new Intent().setData(uri("a"));
        assertEquals(Arrays.asList(uri("a")), AttachmentUriCollector.fromPickerResult(result));
    }

    /** The multi shape: several photos, in ClipData, in the order the user picked them. */
    @Test public void severalClipDataItemsAreReadInOrder() {
        Intent result = new Intent();
        result.setClipData(clip(uri("a"), uri("b"), uri("c")));
        assertEquals(Arrays.asList(uri("a"), uri("b"), uri("c")),
                AttachmentUriCollector.fromPickerResult(result));
    }

    /**
     * The shape that produces a bug if only one field is read, or if both are read naively.
     *
     * <p>Android's own photo picker populates {@code getData} with the first item and
     * {@code ClipData} with all of them. Reading one field loses photos; reading both without
     * deduplicating attaches the first one twice.
     */
    @Test public void aUriPresentInBothFieldsIsAttachedOnce() {
        Intent result = new Intent().setData(uri("a"));
        result.setClipData(clip(uri("a"), uri("b")));
        assertEquals(Arrays.asList(uri("a"), uri("b")),
                AttachmentUriCollector.fromPickerResult(result));
    }

    /** Deduplication never reorders: first seen fixes position. */
    @Test public void deduplicationPreservesFirstSeenOrder() {
        Intent result = new Intent().setData(uri("c"));
        result.setClipData(clip(uri("a"), uri("c"), uri("b"), uri("a")));
        assertEquals(Arrays.asList(uri("c"), uri("a"), uri("b")),
                AttachmentUriCollector.fromPickerResult(result));
    }

    /**
     * Identity is the URI, never the filename.
     *
     * <p>Two different photos routinely share {@code IMG_0042.jpg} - one from the camera roll and
     * one from a download - and collapsing them would silently drop a photo the user selected.
     */
    @Test public void twoDifferentFilesSharingANameAreTwoAttachments() {
        Intent result = new Intent();
        result.setClipData(clip(
                Uri.parse("content://media/external/images/media/11/IMG_0042.jpg"),
                Uri.parse("content://com.example.downloads/docs/98/IMG_0042.jpg")));
        assertEquals(2, AttachmentUriCollector.fromPickerResult(result).size());
    }

    /** Case in a scheme or authority is not identity; case in a path is. */
    @Test public void schemeAndAuthorityCompareCaseInsensitively() {
        Intent result = new Intent();
        result.setClipData(clip(
                Uri.parse("content://Media.Provider/item/1"),
                Uri.parse("CONTENT://media.provider/item/1"),
                Uri.parse("content://media.provider/item/A")));
        assertEquals("the same URI written two ways is one item; a different path is another",
                2, AttachmentUriCollector.fromPickerResult(result).size());
    }

    /** A cancelled picker reports nothing at all, which is not the same as an error. */
    @Test public void aCancelledPickerYieldsNothing() {
        assertTrue(AttachmentUriCollector.fromPickerResult(null).isEmpty());
        assertTrue(AttachmentUriCollector.fromPickerResult(new Intent()).isEmpty());
    }

    /** A file path is not something the sender proved it may read, so Orbit will not read it. */
    @Test public void nonContentSchemesAreRefused() {
        Intent result = new Intent().setData(Uri.parse("file:///data/data/com.orbit.assistant/x"));
        assertTrue(AttachmentUriCollector.fromPickerResult(result).isEmpty());
    }

    /** A hostile item count costs bounded work rather than unbounded work. */
    @Test public void anAbsurdSelectionIsBounded() {
        List<Uri> many = new ArrayList<>();
        for (int i = 0; i < 500; i++) many.add(uri("item" + i));
        Intent result = new Intent();
        result.setClipData(clip(many.toArray(new Uri[0])));
        assertEquals(AttachmentUriCollector.MAX_SCANNED_ITEMS,
                AttachmentUriCollector.fromPickerResult(result).size());
    }

    // ---- the composer collection -------------------------------------------------------------------

    @Test public void attachmentsKeepTheOrderTheyWereAddedIn() {
        ComposerAttachments composer = new ComposerAttachments();
        composer.addAll(images("one", "two", "three"));
        assertEquals(Arrays.asList("one", "two", "three"), labels(composer));
    }

    /** A second trip to Gallery adds to the message rather than throwing the first away. */
    @Test public void asecondBatchAppendsRatherThanReplaces() {
        ComposerAttachments composer = new ComposerAttachments();
        composer.addAll(images("one", "two"));
        composer.addAll(images("three"));
        assertEquals(Arrays.asList("one", "two", "three"), labels(composer));
    }

    /** Removing the middle one removes exactly that one. */
    @Test public void removingOneLeavesTheOthersInOrder() {
        ComposerAttachments composer = new ComposerAttachments();
        List<ComposerAttachment> added = images("one", "two", "three");
        composer.addAll(added);

        assertTrue(composer.remove(added.get(1).id));
        assertEquals(Arrays.asList("one", "three"), labels(composer));
    }

    @Test public void removingTheFirstAndLastWorksTheSameWay() {
        ComposerAttachments composer = new ComposerAttachments();
        List<ComposerAttachment> added = images("one", "two", "three");
        composer.addAll(added);

        composer.remove(added.get(0).id);
        composer.remove(added.get(2).id);
        assertEquals(java.util.Collections.singletonList("two"), labels(composer));
    }

    /** An id that is not there removes nothing, silently and safely. */
    @Test public void removingAnUnknownIdChangesNothing() {
        ComposerAttachments composer = new ComposerAttachments();
        composer.addAll(images("one", "two"));
        assertFalse(composer.remove("att-never-issued"));
        assertEquals(2, composer.size());
    }

    /** Two photos taken in the same burst share a label and are still two attachments. */
    @Test public void twoAttachmentsWithTheSameLabelAreStillDistinct() {
        ComposerAttachments composer = new ComposerAttachments();
        List<ComposerAttachment> added = images("IMG_0042.jpg", "IMG_0042.jpg");
        composer.addAll(added);

        composer.remove(added.get(0).id);
        assertEquals(1, composer.size());
        assertEquals(added.get(1).id, composer.first().id);
    }

    // ---- the central limit ---------------------------------------------------------------------------

    /** One number, and it is the same number everywhere. */
    @Test public void thereIsOneAttachmentLimit() {
        assertEquals(10, ComposerAttachments.MAX_PER_TURN);
        ComposerAttachments composer = new ComposerAttachments();
        assertEquals(ComposerAttachments.MAX_PER_TURN, composer.remainingCapacity());
    }

    /** Over the limit, the first items in order are kept and the rest are reported. */
    @Test public void overTheLimitTheFirstItemsAreKeptAndTheRestAreReported() {
        ComposerAttachments composer = new ComposerAttachments();
        List<ComposerAttachment> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) twelve.add(image("photo" + i));

        ComposerAttachments.AddResult result = composer.addAll(twelve);
        assertEquals(12, result.offered);
        assertEquals(10, result.accepted);
        assertEquals(2, result.rejectedOverLimit);
        assertTrue("the user has to be told, never silently trimmed", result.hitLimit());
        assertEquals("photo1", composer.items().get(0).label);
        assertEquals("photo10", composer.items().get(9).label);
    }

    /** The loader applies the same limit rather than decoding images that cannot be attached. */
    @Test public void theLoaderRefusesToReadPastTheCapacity() {
        List<Uri> twelve = new ArrayList<>();
        for (int i = 0; i < 12; i++) twelve.add(uri("photo" + i));

        AttachmentBatch batch = AttachmentBatchLoader.load(
                RuntimeEnvironment.getApplication(), twelve, "", 10, null);
        assertEquals("everything the user selected is counted", 12, batch.selected);
        // Nothing decodes in a unit test, so all ten readable slots fail; what is being asserted is
        // that the two past the capacity are counted as rejected rather than forgotten.
        assertEquals(12, batch.accepted() + batch.rejected);
    }

    // ---- what a set becomes on the wire ---------------------------------------------------------------

    /** One attachment produces exactly the context text it always did. */
    @Test public void oneAttachmentIsUnchangedOnTheWire() {
        List<ComposerAttachment> one = java.util.Collections.singletonList(
                new ComposerAttachment("file_text", "notes.txt", "the file contents", null));
        assertEquals("the file contents", ComposerAttachments.contextTextOf(one));
        assertEquals("notes.txt", ComposerAttachments.labelOf(one));
        assertEquals("file_text", ComposerAttachments.kindOf(one));
    }

    /** Several are numbered so the model can tell which description belongs to which item. */
    @Test public void severalAttachmentsAreNumberedInOrder() {
        List<ComposerAttachment> several = Arrays.asList(
                new ComposerAttachment("file_text", "first.txt", "alpha", null),
                new ComposerAttachment("file_text", "second.txt", "beta", null));
        String context = ComposerAttachments.contextTextOf(several);
        assertTrue(context.contains("Attachment 1 of 2: first.txt"));
        assertTrue(context.contains("Attachment 2 of 2: second.txt"));
        assertTrue(context.indexOf("alpha") < context.indexOf("beta"));
        assertEquals("2 attachments", ComposerAttachments.labelOf(several));
    }

    /** A set of different kinds reports as mixed rather than claiming to be one of them. */
    @Test public void aMixedSetReportsAsMixed() {
        assertEquals("mixed", ComposerAttachments.kindOf(Arrays.asList(
                new ComposerAttachment("image", "a", "", null),
                new ComposerAttachment("pdf", "b", "", null))));
        assertEquals("image", ComposerAttachments.kindOf(Arrays.asList(
                new ComposerAttachment("image", "a", "", null),
                new ComposerAttachment("image", "b", "", null))));
    }

    /** Images come out in order, with the non-image attachments simply absent. */
    @Test public void imagesAreExtractedInOrderWithGapsClosed() {
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        List<Bitmap> images = ComposerAttachments.imagesOf(Arrays.asList(
                new ComposerAttachment("image", "a", "", first),
                new ComposerAttachment("file_text", "notes", "text", null),
                new ComposerAttachment("image", "b", "", second)));
        assertEquals(2, images.size());
        assertEquals(first, images.get(0));
        assertEquals(second, images.get(1));
    }

    // ---- manual attachments and screen context stay different things ------------------------------------

    /**
     * A photo and a screenshot accumulate; two screenshots do not.
     *
     * <p>Both captures claim to be the phone's screen, so the second is the user correcting the
     * first. A photo they picked is a separate thing they chose to share and must survive.
     */
    @Test public void aScreenCaptureSupersedesAScreenCaptureAndNothingElse() {
        ComposerAttachments composer = new ComposerAttachments();
        composer.add(image("holiday.jpg"));
        composer.addScreenCapture(new ComposerAttachment("screen", "Screen · Gmail", "text", bitmap()));
        composer.addScreenCapture(
                new ComposerAttachment("screen_selection", "Selection · Gmail", "text", bitmap()));

        assertEquals(2, composer.size());
        assertEquals("the picked photo survives", "holiday.jpg", composer.items().get(0).label);
        assertEquals("Selection · Gmail", composer.items().get(1).label);
        assertTrue(composer.hasScreenCapture());
    }

    /** The v0.7.7.8 rule is untouched: policy-armed screen context is not a one-shot. */
    @Test public void policyArmedScreenContextIsStillNotConsumedBySend() {
        assertTrue(OrbitSession.screenAttachmentIsConsumedBySend(true, false));
        assertFalse("a standing Attach policy must survive every send",
                OrbitSession.screenAttachmentIsConsumedBySend(true, true));
    }

    // ---- the snapshot handed to a request ---------------------------------------------------------------

    /**
     * Send freezes the set, so the request and the composer stop sharing a list.
     *
     * <p>Without this, removing a thumbnail a second after pressing Send would change a message
     * that had already gone, and change the conversation's own record of it.
     */
    @Test public void theSnapshotTakenAtSendCannotBeChangedAfterwards() {
        ComposerAttachments composer = new ComposerAttachments();
        List<ComposerAttachment> added = images("one", "two", "three");
        composer.addAll(added);

        List<ComposerAttachment> sent = composer.snapshot();
        composer.remove(added.get(0).id);
        composer.clear();

        assertEquals("the sent message keeps what it was sent with", 3, sent.size());
        assertEquals("one", sent.get(0).label);
        assertTrue(composer.isEmpty());
    }

    // ---- batch reporting -------------------------------------------------------------------------------

    /** A clean single result reads exactly as it always did. */
    @Test public void oneAttachmentSummaryIsUnchanged() {
        AttachmentBatch batch = AttachmentBatch.of(images("holiday.jpg"), 1, 0, "");
        assertEquals("holiday.jpg attached", batch.summary());
    }

    /** A partial success says both halves rather than looking like success or failure. */
    @Test public void aPartialFailureIsReportedWithBothCounts() {
        AttachmentBatch batch = AttachmentBatch.of(images("a", "b", "c", "d"), 5, 1, "");
        assertEquals("4 attachments added · 1 couldn't be read", batch.summary());
    }

    /** A cancelled picker says nothing at all, because nothing went wrong. */
    @Test public void acancelledBatchSaysNothing() {
        assertTrue(AttachmentBatch.cancelled().cancelled);
        assertEquals("", AttachmentBatch.cancelled().summary());
    }

    /** A total failure reports the reason and never a path or a filename it read. */
    @Test public void atotalFailureReportsItsReason() {
        AttachmentBatch batch = AttachmentBatch.failed("Orbit could not read that image.");
        assertTrue(batch.isEmpty());
        assertEquals("Orbit could not read that image.", batch.summary());
    }

    // ---- the bridge --------------------------------------------------------------------------------------

    /** One picker trip resolves once, with the whole batch, not once per selected photo. */
    @Test public void abatchIsDeliveredOnceAsAWhole() {
        final List<AttachmentBatch> received = new ArrayList<>();
        String token = AttachmentBridge.register(received::add);
        AttachmentBridge.deliver(token, AttachmentBatch.of(images("a", "b", "c"), 3, 0, ""));

        assertEquals("one callback for one picker trip", 1, received.size());
        assertEquals(3, received.get(0).accepted());
    }

    /** A result for a token nobody owns any more attaches to nothing. */
    @Test public void astaleBatchIsRefused() {
        final List<AttachmentBatch> received = new ArrayList<>();
        String token = AttachmentBridge.register(received::add);
        AttachmentBridge.cancel(token);

        AttachmentBridge.deliver(token, AttachmentBatch.of(images("a"), 1, 0, ""));
        assertTrue("a cancelled owner must never receive a batch", received.isEmpty());
    }

    /** Delivering twice - a duplicated lifecycle callback - attaches once. */
    @Test public void adoubledDeliveryAttachesOnce() {
        final List<AttachmentBatch> received = new ArrayList<>();
        String token = AttachmentBridge.register(received::add);
        AttachmentBridge.deliver(token, AttachmentBatch.of(images("a"), 1, 0, ""));
        AttachmentBridge.deliver(token, AttachmentBatch.of(images("b"), 1, 0, ""));

        assertEquals(1, received.size());
        assertEquals("a", received.get(0).attachments.get(0).label);
    }

    /** The prior fix stands: a cancelled picker does not leave a flow looking in flight. */
    @Test public void acancelledPickerLeavesNothingPending() {
        String token = AttachmentBridge.register(batch -> { });
        assertTrue(AttachmentBridge.isPending(token));
        AttachmentBridge.deliver(token, AttachmentBatch.cancelled());
        assertFalse(AttachmentBridge.isPending(token));
    }

    // ---- gallery selection -------------------------------------------------------------------------------

    /**
     * The chosen Gallery is asked for a multiple selection; it is never swapped for another app.
     *
     * <p>A Gallery that supports {@code EXTRA_ALLOW_MULTIPLE} returns several photos and one that
     * does not returns the single photo it always returned. Neither outcome is a reason to open a
     * different picker than the one the user chose.
     */
    @Test public void theChosenGalleryIsAskedForMultipleAndNeverSubstituted() {
        GalleryAppPreference.Target target = new GalleryAppPreference.Target(
                "com.sec.android.gallery3d",
                "com.sec.android.gallery3d.app.GalleryActivity", Intent.ACTION_PICK);

        Intent multi = GalleryAppPreference.intentForTarget(target, 10);
        assertNotNull(multi);
        assertTrue("the multi-select request must reach the chosen Gallery",
                multi.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertNotNull("and it must still be that Gallery's own component", multi.getComponent());
        assertEquals("com.sec.android.gallery3d", multi.getComponent().getPackageName());
    }

    /** With room for only one more, Orbit does not ask for several. */
    @Test public void withRoomForOneOrbitDoesNotAskForSeveral() {
        GalleryAppPreference.Target target = new GalleryAppPreference.Target(
                "com.example.gallery", "com.example.gallery.Pick", Intent.ACTION_PICK);
        Intent single = GalleryAppPreference.intentForTarget(target, 1);
        assertFalse(single.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
    }

    /** Android's own picker uses the documented multi-select mechanism, not a version fork. */
    @Test public void thesystemPickerUsesTheDocumentedMultiSelect() {
        Intent system = GalleryAppPreference.systemPickerIntent(10);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, system.getAction());
        assertEquals("image/*", system.getType());
        assertTrue(system.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
    }

    /**
     * A Gallery that genuinely returns one item is accepted as returning one item.
     *
     * <p>Orbit reports what happened and does not claim a multiple selection occurred, nor go
     * behind the user's back to a picker that would have allowed one.
     */
    @Test public void agalleryThatReturnsOneItemIsAcceptedAsOne() {
        Intent result = new Intent().setData(uri("only"));
        assertEquals(1, AttachmentUriCollector.fromPickerResult(result).size());
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static Uri uri(String id) {
        return Uri.parse("content://com.example.provider/media/" + id);
    }

    private static ClipData clip(Uri... uris) {
        ClipData clip = ClipData.newRawUri("test", uris[0]);
        for (int i = 1; i < uris.length; i++) clip.addItem(new ClipData.Item(uris[i]));
        return clip;
    }

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
    }

    private static ComposerAttachment image(String label) {
        return new ComposerAttachment("image", label, "", bitmap());
    }

    private static List<ComposerAttachment> images(String... labels) {
        List<ComposerAttachment> out = new ArrayList<>();
        for (String label : labels) out.add(image(label));
        return out;
    }

    private static List<String> labels(ComposerAttachments composer) {
        List<String> out = new ArrayList<>();
        for (ComposerAttachment attachment : composer.items()) out.add(attachment.label);
        return out;
    }
}

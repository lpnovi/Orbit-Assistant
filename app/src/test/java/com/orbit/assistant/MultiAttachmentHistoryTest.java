package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * That a turn's attachments stay that turn's attachments, however many there are.
 *
 * <p>{@code 0.7.7.8} established the rule: an attachment belongs to the message the user shared it
 * with, and a follow-up rebuilds the conversation as the user remembers having it. Generalising a
 * slot into a set is where that rule is easiest to break in two opposite directions - by carrying
 * only the first image forward, or by carrying so many that a request grows without limit. The
 * retention budget is the interesting case: "3 turns × 1 image" must not have quietly become
 * "3 turns × unlimited" just because a turn can now hold ten.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class MultiAttachmentHistoryTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
    }

    // ---- storage and reload ------------------------------------------------------------------------

    /** A turn with three images keeps all three, in order, across a save and a load. */
    @Test public void aTurnWithThreeImagesReloadsWithAllThreeInOrder() {
        List<String> paths = Arrays.asList(store("one"), store("two"), store("three"));
        ConversationStore.save(context, "c-multi", new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "Compare these screenshots", true, paths,
                        "image", "3 attachments", "context", "", "", "", ""))));

        AssistantClient.History reloaded = ConversationStore.load(context, "c-multi").messages.get(0);
        assertEquals(3, reloaded.attachmentCount());
        assertEquals(paths, reloaded.attachmentPaths);
        assertEquals("the legacy field is always the head of the list",
                paths.get(0), reloaded.attachmentPath);
    }

    /**
     * A conversation written before this release still loads, unchanged and un-migrated.
     *
     * <p>Read through the same public constructor an older Orbit used, which is the only shape a
     * stored record from that era can produce.
     */
    @Test public void anOldSingleAttachmentTurnStillLoads() {
        String path = store("legacy");
        ConversationStore.save(context, "c-legacy", new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "What is this?", true, path,
                        "image", "photo.jpg", "context"))));

        AssistantClient.History reloaded = ConversationStore.load(context, "c-legacy").messages.get(0);
        assertEquals(1, reloaded.attachmentCount());
        assertEquals(path, reloaded.attachmentPath);
        assertEquals(java.util.Collections.singletonList(path), reloaded.attachmentPaths);
    }

    /** A turn with no attachment reports an empty list rather than a list holding "". */
    @Test public void aTurnWithNoAttachmentHasNoPaths() {
        AssistantClient.History plain = new AssistantClient.History("user", "hello");
        assertEquals(0, plain.attachmentCount());
        assertTrue(plain.attachmentPaths.isEmpty());
        assertEquals("", plain.attachmentPath);
    }

    // ---- reconstruction for a follow-up ---------------------------------------------------------------

    /** A follow-up rebuilds every image of the turn it belongs to, in order. */
    @Test public void afollowUpRebuildsTheWholeAttachmentSet() {
        List<String> paths = Arrays.asList(store("a"), store("b"), store("c"));
        List<AssistantClient.History> window = new ArrayList<>(Arrays.asList(
                new AssistantClient.History("user", "Compare these", true, paths,
                        "image", "3 attachments", "context", "", "", "", ""),
                new AssistantClient.History("assistant", "They differ in the header.")));

        HistoryAttachments.Plan plan = HistoryAttachments.plan(window, true);
        HistoryAttachments.Turn turn = plan.at(0);
        assertNotNull(turn);
        assertEquals(3, turn.imageCount());
        assertEquals(paths, turn.imagePaths);
        assertEquals(3, plan.images);
    }

    /**
     * The bound did not widen when a turn learned to hold more.
     *
     * <p>Two turns of six photos each is twelve stored images; a request may still carry three.
     * That is the whole point of the budget being counted in images rather than in turns.
     */
    @Test public void theImageBudgetIsStillThreeAcrossTheWholeRequest() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        for (int i = 0; i < 6; i++) { first.add(store("first" + i)); second.add(store("second" + i)); }

        List<AssistantClient.History> window = new ArrayList<>(Arrays.asList(
                new AssistantClient.History("user", "batch one", true, first,
                        "image", "6 attachments", "", "", "", "", ""),
                new AssistantClient.History("assistant", "ok"),
                new AssistantClient.History("user", "batch two", true, second,
                        "image", "6 attachments", "", "", "", "", ""),
                new AssistantClient.History("assistant", "ok")));

        HistoryAttachments.Plan plan = HistoryAttachments.plan(window, true);
        assertEquals("a request may still carry three reconstructed images",
                HistoryAttachments.MAX_IMAGES, plan.images);
    }

    /**
     * One enormous turn cannot spend the whole budget and starve the turns after it.
     *
     * <p>The budget is three images from at most three turns, so a ten-photo turn contributes some
     * of its photos rather than all of them, and a later turn still gets one.
     */
    @Test public void oneLargeTurnDoesNotCrowdOutTheOthers() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) ten.add(store("bulk" + i));
        List<String> one = java.util.Collections.singletonList(store("later"));

        List<AssistantClient.History> window = new ArrayList<>(Arrays.asList(
                new AssistantClient.History("user", "ten photos", true, ten,
                        "image", "10 attachments", "", "", "", "", ""),
                new AssistantClient.History("assistant", "ok"),
                new AssistantClient.History("user", "one more", true, one,
                        "image", "later.jpg", "", "", "", "", "")));

        HistoryAttachments.Plan plan = HistoryAttachments.plan(window, true);
        assertEquals(HistoryAttachments.MAX_IMAGES, plan.images);
        HistoryAttachments.Turn newest = plan.at(2);
        assertNotNull(newest);
        assertEquals("the newest turn keeps its image", 1, newest.imageCount());
        HistoryAttachments.Turn bulk = plan.at(0);
        assertNotNull(bulk);
        assertTrue("the ten-photo turn contributes some, not all", bulk.imageCount() < 10);
    }

    /** A file that has since been deleted costs its image and keeps the turn's text. */
    @Test public void amissingFileDegradesSafely() {
        String present = store("present");
        String gone = new File(context.getFilesDir(), "orbit_attachments/history/gone.jpg")
                .getAbsolutePath();

        List<AssistantClient.History> window = new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "two photos", true,
                        Arrays.asList(present, gone), "image", "2 attachments",
                        "what the user attached", "", "", "", "")));

        HistoryAttachments.Plan plan = HistoryAttachments.plan(window, true);
        HistoryAttachments.Turn turn = plan.at(0);
        assertNotNull(turn);
        assertEquals("the readable one is still sent", 1, turn.imageCount());
        assertEquals(present, turn.imagePath);
        assertTrue(turn.assetMissing);
        assertEquals(1, plan.missingAssets);
        assertEquals("and nothing is invented to fill the hole",
                "what the user attached", turn.text);
    }

    /** The same stored file is never sent twice inside one request. */
    @Test public void adeduplicatedFileIsSentOnce() {
        String shared = store("shared");
        List<AssistantClient.History> window = new ArrayList<>(Arrays.asList(
                new AssistantClient.History("user", "first", true,
                        java.util.Collections.singletonList(shared),
                        "image", "a.jpg", "", "", "", "", ""),
                new AssistantClient.History("assistant", "ok"),
                new AssistantClient.History("user", "again", true,
                        java.util.Collections.singletonList(shared),
                        "image", "a.jpg", "", "", "", "", "")));

        assertEquals(1, HistoryAttachments.plan(window, true).images);
    }

    /** A stored screen capture still follows the screenshot preference; a photo does not. */
    @Test public void screenCapturesStillObeyTheScreenshotPreference() {
        List<String> screens = java.util.Collections.singletonList(store("screen"));
        List<String> photos = java.util.Collections.singletonList(store("photo"));
        List<AssistantClient.History> window = new ArrayList<>(Arrays.asList(
                new AssistantClient.History("user", "screen turn", true, screens,
                        "screen", "Screen attached", "", "", "", "", ""),
                new AssistantClient.History("assistant", "ok"),
                new AssistantClient.History("user", "photo turn", true, photos,
                        "image", "photo.jpg", "", "", "", "", "")));

        HistoryAttachments.Plan withScreens = HistoryAttachments.plan(window, true);
        assertEquals(2, withScreens.images);

        HistoryAttachments.Plan withoutScreens = HistoryAttachments.plan(window, false);
        assertEquals("only the user's own photo survives the preference being off",
                1, withoutScreens.images);
        // The screen turn carried no text either, so with its image withheld it contributes
        // nothing at all and is left out of the plan rather than added as an empty turn.
        assertNull(withoutScreens.at(0));
        assertEquals(1, withoutScreens.at(2).imageCount());
    }

    // ---- pending requests ------------------------------------------------------------------------------

    /** An in-flight request freezes every image, in order, and reads them back the same way. */
    @Test public void apendingRequestFreezesEveryImage() {
        List<String> paths = Arrays.asList(store("p1"), store("p2"), store("p3"));
        PendingRequestStore.Item created = PendingRequestStore.create(context, "c-1", "compare",
                "context", paths, false, false, Prefs.MODE_BALANCED, true, "");

        PendingRequestStore.Item reloaded = PendingRequestStore.load(context, created.id);
        assertNotNull(reloaded);
        assertEquals(paths, reloaded.screenshotPaths);
        assertEquals(paths.get(0), reloaded.screenshotPath);
    }

    /** A request written before this release reads back as the one image it recorded. */
    @Test public void alegacyPendingRequestStillReadsBack() {
        String path = store("legacy-pending");
        PendingRequestStore.Item created = PendingRequestStore.create(context, "c-1", "what is this",
                "context", path, false, false, Prefs.MODE_BALANCED, true, "");

        PendingRequestStore.Item reloaded = PendingRequestStore.load(context, created.id);
        assertEquals(java.util.Collections.singletonList(path), reloaded.screenshotPaths);
        assertEquals(path, reloaded.screenshotPath);
    }

    // ---- deletion ---------------------------------------------------------------------------------------

    /** Deleting a conversation removes the private image files only it referred to. */
    @Test public void deletingAConversationCleansTheFilesItOwned() {
        String mine = store("mine");
        String alsoMine = store("also-mine");
        ConversationStore.save(context, "c-doomed", new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "two photos", true,
                        Arrays.asList(mine, alsoMine), "image", "2 attachments", "",
                        "", "", "", ""))));

        assertTrue(new File(mine).isFile());
        ConversationStore.delete(context, "c-doomed");
        assertFalse("a deleted chat must not leave its photos behind", new File(mine).isFile());
        assertFalse(new File(alsoMine).isFile());
    }

    /** A file another conversation still refers to is never removed out from under it. */
    @Test public void afileAnotherChatStillUsesSurvives() {
        String shared = store("shared-file");
        ConversationStore.save(context, "c-a", new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "mine", true,
                        java.util.Collections.singletonList(shared),
                        "image", "a.jpg", "", "", "", "", ""))));
        ConversationStore.save(context, "c-b", new ArrayList<>(java.util.Collections.singletonList(
                new AssistantClient.History("user", "also mine", true,
                        java.util.Collections.singletonList(shared),
                        "image", "a.jpg", "", "", "", "", ""))));

        ConversationStore.delete(context, "c-a");
        assertTrue("the surviving chat keeps its image", new File(shared).isFile());
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    /** Writes a real private JPEG the way an attachment is stored, and returns its path. */
    private String store(String name) {
        Bitmap bitmap = Bitmap.createBitmap(6, 6, Bitmap.Config.ARGB_8888);
        String path = AttachmentStore.saveHistoryAttachment(context, bitmap);
        assertFalse("the test fixture must actually write a file: " + name, path.isEmpty());
        return path;
    }
}

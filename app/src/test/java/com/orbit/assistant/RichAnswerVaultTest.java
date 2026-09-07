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
import java.util.List;

/**
 * Saving a sourced picture goes through the Vault Orbit already has, and keeps what makes it useful.
 *
 * <p>Two things are easy to get wrong here and both are permanent. The first is saving a pointer
 * into the response-image cache instead of a copy: the cache is trimmed by age and size, so the
 * item would work for a week and then be a broken thumbnail forever. The second is putting the
 * source hostname into the Vault's own provenance vocabulary, which would mean the Saved-from
 * filter offering whatever domains a month of asking questions produced, as though Orbit vouched
 * for each of them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerVaultTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitVaultStore.deleteAllData(context);
    }

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888);
    }

    private static RichAnswerImage sourced() {
        return RichAnswerImage.webSource("https://cdn.example.org/robin.jpg",
                "https://www.example.org/birds/robin?ref=orbit",
                "A European robin in winter", "robin on a branch", 0);
    }

    @Test public void savingKeepsTheCaptionAndTheSourcePage() {
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        assertNotNull(saved);
        assertEquals(OrbitVaultItem.TYPE_IMAGE, saved.type);
        assertEquals("A European robin in winter", saved.title);
        assertEquals(OrbitVaultSource.RICH_ANSWER, saved.source);
        assertEquals("https://www.example.org/birds/robin?ref=orbit", saved.sourceUrl);
        assertEquals("example.org", saved.sourceHostLabel());
        assertTrue(saved.hasSourceUrl());
    }

    /**
     * A saved picture is the Vault's own file, never a path into the response cache.
     *
     * <p>Asserted through {@link OrbitVaultMedia#owns}, which canonicalises both sides, so a path
     * that merely looked like a Vault path could not pass it either.
     */
    @Test public void theSavedFileIsVaultOwnedRatherThanACachePath() {
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        assertNotNull(saved);
        assertFalse(saved.mediaPath.isEmpty());
        assertTrue("the Vault must own the file it points at",
                OrbitVaultMedia.owns(context, saved.mediaPath));
        assertTrue("and it must actually exist", new File(saved.mediaPath).isFile());
        assertFalse("it must not live in the response image cache",
                saved.mediaPath.contains("orbit_response_images"));
        assertFalse("nor anywhere under the cache directory at all",
                saved.mediaPath.startsWith(context.getCacheDir().getAbsolutePath()));
    }

    /** A picture stays an IMAGE. Where it came from is a source, not a type. */
    @Test public void aSavedRichImageIsAnOrdinaryImageItem() {
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        assertTrue(saved.isImage());
        assertEquals("Image", saved.typeLabel());
        for (String type : OrbitVaultItem.TYPES) {
            assertFalse("no rich-answer type may have been added", type.contains("rich"));
        }
        assertEquals("the type list is unchanged", 5, OrbitVaultItem.TYPES.length);
    }

    /** Rich answer joins the closed provenance vocabulary, and the domain does not. */
    @Test public void richAnswerIsACanonicalSourceAndTheDomainIsNot() {
        assertTrue(OrbitVaultSource.isCanonical(OrbitVaultSource.RICH_ANSWER));
        assertEquals(OrbitVaultSource.RICH_ANSWER,
                OrbitVaultSource.family(OrbitVaultSource.RICH_ANSWER));
        assertEquals(OrbitVaultSource.RICH_ANSWER,
                OrbitVaultSource.displayLabel(OrbitVaultSource.RICH_ANSWER));

        boolean listed = false;
        for (String source : OrbitVaultSource.FILTERABLE) {
            if (OrbitVaultSource.RICH_ANSWER.equals(source)) listed = true;
        }
        assertTrue("it must be offered in the Saved-from filter", listed);

        for (String host : new String[]{
                "example.org", "www.example.org", "Wikipedia", "https://example.org"}) {
            assertFalse(host + " is a third party's word and must never be a canonical source",
                    OrbitVaultSource.isCanonical(host));
            assertEquals("", OrbitVaultSource.family(host));
        }
    }

    /** And the Saved-from filter genuinely finds a saved rich image by that source. */
    @Test public void savedFromFilteringFindsRichAnswerImages() {
        OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        OrbitVaultStore.saveText(context, "A note", "Just some text",
                OrbitVaultSource.QUICK_CAPTURE);

        List<String> present = OrbitVaultStore.sourcesPresent(context);
        assertTrue(present.contains(OrbitVaultSource.RICH_ANSWER));

        OrbitVaultFilter filter = OrbitVaultFilter.NONE.withSource(OrbitVaultSource.RICH_ANSWER);
        List<OrbitVaultItem> found = OrbitVaultStore.browse(context, filter,
                OrbitVaultStore.Sort.NEWEST);
        assertEquals(1, found.size());
        assertEquals(OrbitVaultSource.RICH_ANSWER, found.get(0).source);
    }

    /** The user's own note still works on a saved picture, exactly as on anything else. */
    @Test public void theUsersOwnNoteStillWorks() {
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        assertTrue(OrbitVaultStore.updateNote(context, saved.id, "For the bird feeder post"));
        OrbitVaultItem reread = OrbitVaultStore.get(context, saved.id);
        assertEquals("For the bird feeder post", reread.note);
        assertTrue(reread.hasNote());
        assertEquals("and the source page is untouched by an edit",
                saved.sourceUrl, reread.sourceUrl);
    }

    /** Renaming, pinning and editing all carry the source page across. */
    @Test public void editsCarryTheSourcePageAcross() {
        OrbitVaultItem saved = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        OrbitVaultStore.updateTitle(context, saved.id, "Robin");
        OrbitVaultStore.setPinned(context, saved.id, true);
        OrbitVaultItem reread = OrbitVaultStore.get(context, saved.id);
        assertEquals("Robin", reread.title);
        assertTrue(reread.isPinned());
        assertEquals(saved.sourceUrl, reread.sourceUrl);
    }

    /** Deleting the item removes the file it owned, and nothing else. */
    @Test public void deletingRemovesOnlyTheFileThisItemOwned() {
        OrbitVaultItem first = OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced());
        OrbitVaultItem second = OrbitVaultStore.saveRichAnswerImage(context, bitmap(),
                RichAnswerImage.webSource("https://cdn.example.org/wren.jpg",
                        "https://example.org/birds/wren", "A wren", "", 0));
        assertTrue(OrbitVaultStore.delete(context, first.id));
        assertFalse("its own picture is gone", new File(first.mediaPath).exists());
        assertTrue("the other picture is untouched", new File(second.mediaPath).isFile());
        assertNotNull(OrbitVaultStore.get(context, second.id));
        assertNull(OrbitVaultStore.get(context, first.id));
    }

    /** A source page survives a store round trip, and a hostile one never gets stored at all. */
    @Test public void onlyOrdinaryWebAddressesAreKeptAsASourcePage() {
        for (String hostile : new String[]{
                "javascript:alert(1)", "file:///etc/hosts", "content://x/y",
                "intent://x#Intent;end", "  ", ""}) {
            OrbitVaultItem item = new OrbitVaultItem("id", OrbitVaultItem.TYPE_IMAGE, "t", "",
                    OrbitVaultSource.RICH_ANSWER, "", "/path/x.jpg", "", 0, 0, false, hostile,
                    1L, 1L);
            assertEquals(hostile + " must never be kept as a source page", "", item.sourceUrl);
            assertFalse(item.hasSourceUrl());
        }
        OrbitVaultItem ok = new OrbitVaultItem("id", OrbitVaultItem.TYPE_IMAGE, "t", "",
                OrbitVaultSource.RICH_ANSWER, "", "/path/x.jpg", "", 0, 0, false,
                "http://example.org/page", 1L, 1L);
        assertEquals("http://example.org/page", ok.sourceUrl);
    }

    /** Every item written before this release simply has no source page, and that is fine. */
    @Test public void olderItemsWithoutASourcePageAreUnaffected() {
        OrbitVaultItem saved = OrbitVaultStore.saveText(context, "Note", "Some text",
                OrbitVaultSource.QUICK_CAPTURE);
        assertNotNull(saved);
        assertFalse(saved.hasSourceUrl());
        assertEquals("", saved.sourceUrl);
        assertEquals("", saved.sourceHostLabel());
    }

    /** Saving is refused when the Vault is off, whatever route asks. */
    @Test public void nothingIsSavedWhileTheVaultIsOff() {
        Prefs.get(context).edit().putBoolean(Prefs.VAULT_ENABLED, false).commit();
        assertFalse(OrbitVaultStore.enabled(context));
        assertNull(OrbitVaultStore.saveRichAnswerImage(context, bitmap(), sourced()));
    }

    /** A save with nothing to save writes nothing. */
    @Test public void anEmptySaveWritesNothing() {
        assertNull(OrbitVaultStore.saveRichAnswerImage(context, null, sourced()));
        assertNull(OrbitVaultStore.saveRichAnswerImage(context, bitmap(), null));
        assertEquals(0, OrbitVaultStore.count(context));
    }

    /** The viewer offers Save to Vault through the Vault's own path, not a private one. */
    @Test public void theViewerSavesThroughTheVaultsOwnPath() {
        String viewer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/AttachmentViewerActivity.java");
        assertTrue(viewer.contains("OrbitVaultStore.saveRichAnswerImage"));
        assertFalse("no second media store may appear beside the Vault's",
                viewer.contains("new File(") && viewer.contains("FileOutputStream"));
        assertTrue("and Open source opens the page rather than the raw image",
                viewer.contains("image.sourceUrl"));
        assertTrue("revalidated immediately before the launch",
                viewer.contains("RichAnswerUrlPolicy.isOpenableWebUrl(image.sourceUrl)"));
    }

    /** There is one image viewer, and Rich Answers uses it. */
    @Test public void thereIsNoSecondImageViewer() {
        assertFalse("a rich-answer viewer Activity must not exist",
                new File("app/src/main/java/com/orbit/assistant/"
                        + "RichAnswerImageViewerActivity.java").exists());
        String manifest = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/AndroidManifest.xml");
        assertFalse(manifest.contains("RichAnswerImageViewerActivity"));
        String card = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCardView.java");
        assertTrue("tapping a rich image opens the canonical viewer",
                card.contains("AttachmentViewerActivity.openRichAnswer"));
    }
}

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

import java.util.Collections;
import java.util.List;

/**
 * What "Ask Orbit about this page" actually attaches.
 *
 * <p>Device testing showed the semantics were already right — opening page 5 and asking "what's
 * this?" got an answer about page 5 — while the composer showed a narrow text label that did not
 * communicate any of it. So these pin the structure: a document to name, a place inside it, a
 * picture of that place, and a way back to it. Not one display string that happens to contain a
 * page number.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DocumentPageContextTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        SharedContentStore.clear();
    }

    private static Bitmap page() {
        return Bitmap.createBitmap(40, 56, Bitmap.Config.ARGB_8888);
    }

    private static DocumentReference reference() {
        return new DocumentReference("/data/orbit/health.pdf", "Health Behavior Theory", 388);
    }

    // ---- the structured page ----------------------------------------------------------------------

    @Test public void aPageIsATitleAPlaceAndAPicture() {
        DocumentPageContext page = new DocumentPageContext("Health Behavior Theory", 4, 388,
                "Chapter four begins here.", reference(), page());
        assertEquals("Health Behavior Theory", page.displayLabel());
        assertEquals("Page 5 of 388", page.pageLabel());
        assertTrue(page.hasText());
        assertTrue(page.hasImage());
        assertTrue(page.isUsable());
        assertNotNull("the page can reopen the document it came from", page.document);
        assertEquals(4, page.document.page);
        assertEquals(4, page.document.openAt());
        assertTrue(page.document.namesPage());
        assertEquals("Page 5 of 388", page.document.pageLabel());
    }

    @Test public void thePageNumberIsClampedToTheDocument() {
        DocumentPageContext beyond = new DocumentPageContext("Doc", 900, 10, "text");
        assertEquals(9, beyond.page);
        assertEquals("Page 10 of 10", beyond.pageLabel());
        DocumentPageContext negative = new DocumentPageContext("Doc", -4, 10, "text");
        assertEquals(0, negative.page);
    }

    /** A whole-document reference is not a page reference, and must not pretend to be one. */
    @Test public void aWholeDocumentReferenceNamesNoPage() {
        DocumentReference whole = reference();
        assertFalse(whole.namesPage());
        assertEquals(0, whole.openAt());
        assertEquals("388 pages", whole.pageLabel());
        assertEquals(DocumentReference.WHOLE_DOCUMENT, whole.page);
    }

    // ---- the composer card ------------------------------------------------------------------------

    @Test public void theComposerCardIsTwoLinesAndAThumbnail() {
        ComposerAttachment card = ComposerAttachment.documentPage(
                new DocumentPageContext("Health Behavior Theory", 4, 388,
                        "Chapter four begins here.", reference(), page()));
        assertNotNull(card);
        assertEquals("pdf_page", card.kind);
        assertEquals("Health Behavior Theory", card.label);
        assertEquals("Page 5 of 388", card.detail);
        assertTrue(card.hasDetail());
        assertTrue("the exact page is the card's thumbnail", card.isVisual());
        assertTrue("and tapping it has somewhere to go", card.isDocument());
        assertTrue(card.isDocumentPage());
        assertEquals(4, card.document.openAt());
    }

    /** One attachment, never two. The user staged one page and must see one card. */
    @Test public void thePageIsOneAttachmentCarryingBothItsHalves() {
        ComposerAttachment card = ComposerAttachment.documentPage(
                new DocumentPageContext("Health Behavior Theory", 4, 388,
                        "Chapter four begins here.", reference(), page()));
        ComposerAttachments composer = new ComposerAttachments();
        composer.add(card);
        assertEquals("one card, not a page card plus a mystery image", 1, composer.size());
        List<ComposerAttachment> items = composer.items();
        assertEquals(1, ComposerAttachments.imagesOf(items).size());
        assertEquals(1, ComposerAttachments.documentsOf(items).size());
        assertTrue(ComposerAttachments.contextTextOf(items).contains("only that page"));
        assertTrue("and it can be removed like any other", composer.remove(card.id));
    }

    @Test public void theCardIsDescribedInFullToAScreenReader() {
        ComposerAttachment card = ComposerAttachment.documentPage(
                new DocumentPageContext("Health Behavior Theory", 4, 388,
                        "Chapter four.", reference(), page()));
        String description = AttachmentLabels.cardDescription(card, 1, 1);
        assertTrue(description, description.contains("Health Behavior Theory"));
        assertTrue("the second line is spoken, since it is not read separately",
                description.contains("Page 5 of 388"));
        String remove = AttachmentLabels.removeDescription(card, 1, 1);
        assertTrue(remove, remove.startsWith("Remove"));
        assertTrue(remove, remove.contains("Page 5 of 388"));
    }

    // ---- the model's view of the page -------------------------------------------------------------

    @Test public void theRequestContextNamesTheExactPageAndNothingElse() {
        String context = new DocumentPageContext("Health Behavior Theory", 4, 388,
                "Chapter four begins here.", reference(), page()).requestContext();
        assertTrue(context.contains("page 5 of 388"));
        assertTrue(context.contains("Health Behavior Theory"));
        assertTrue(context.contains("only that page"));
        assertTrue("page contents are data, not instructions",
                context.contains("untrusted data"));
        assertTrue(context.contains("Chapter four begins here."));
    }

    /**
     * A page Orbit could render but not read says so.
     *
     * <p>The alternative is worse in both directions: a model with vision is left unsure whether
     * it was given text it cannot see, and a model without vision is handed a page reference and
     * nothing to go on.
     */
    @Test public void aScannedPageIsAttachedAndDescribedHonestly() {
        DocumentPageContext scanned = new DocumentPageContext("Scanned notes", 2, 9, "  ",
                new DocumentReference("/data/orbit/scan.pdf", "Scanned notes", 9), page());
        assertFalse(scanned.hasText());
        assertTrue("a scanned page is still worth attaching", scanned.isUsable());
        String context = scanned.requestContext();
        assertTrue(context, context.contains("little or no extractable text"));
        assertTrue(context, context.contains("do not claim to have read text"));
        assertFalse("it must not claim an image the provider may never receive",
                context.toLowerCase(java.util.Locale.ROOT).contains("rendering is attached"));

        ComposerAttachment card = ComposerAttachment.documentPage(scanned);
        assertNotNull("Ask Orbit still works on a scanned page", card);
        assertEquals(ComposerAttachment.CONTENT_VISUAL_ONLY, card.contentState);
    }

    /** A page with neither text nor a rendering is nothing to attach. */
    @Test public void anUnreadableUnrenderablePageIsRefused() {
        DocumentPageContext nothing = new DocumentPageContext("Broken", 0, 4, "", null, null);
        assertFalse(nothing.isUsable());
        assertNull(ComposerAttachment.documentPage(nothing));
        assertEquals("", SharedContentStore.stageDocumentPage(nothing));
    }

    // ---- staging ----------------------------------------------------------------------------------

    @Test public void stagingCarriesEveryPartOfThePageAndIsOneShot() {
        DocumentPageContext page = new DocumentPageContext("Health Behavior Theory", 4, 388,
                "Chapter four begins here.", reference(), page());
        String token = SharedContentStore.stageDocumentPage(page);
        assertFalse(token.isEmpty());
        SharedContentStore.Staged staged = SharedContentStore.consume(token);
        assertNotNull(staged);
        assertEquals("nothing is pasted into the composer", "", staged.text);
        assertNotNull(staged.documentPage);
        assertTrue(staged.documentPage.hasImage());
        assertNotNull(staged.documentPage.document);
        assertEquals(4, staged.documentPage.document.page);
        assertNull("a handoff token is used exactly once", SharedContentStore.consume(token));
    }

    // ---- history ----------------------------------------------------------------------------------

    /** A sent turn keeps the page it was about, and can reopen there. */
    @Test public void aSentTurnRemembersWhichPageItWasAbout() {
        DocumentReference page = new DocumentReference("/data/orbit/health.pdf",
                "Health Behavior Theory", 388, 4);
        AssistantClient.History turn = new AssistantClient.History("user", "What's this?", true,
                Collections.emptyList(), "pdf_page", "Health Behavior Theory", "context",
                "", "", "", "", Collections.singletonList(page));
        String conversationId = ConversationStore.newId();
        ConversationStore.save(context, conversationId, Collections.singletonList(turn));

        List<AssistantClient.History> reloaded =
                ConversationStore.load(context, conversationId).messages;
        assertEquals(1, reloaded.size());
        assertEquals(1, reloaded.get(0).documents.size());
        DocumentReference restored = reloaded.get(0).documents.get(0);
        assertTrue("the page reference survives being written and read back",
                restored.namesPage());
        assertEquals(4, restored.page);
        assertEquals(4, restored.openAt());
        assertEquals("Page 5 of 388", restored.pageLabel());
        assertEquals("Health Behavior Theory", restored.label);
    }

    /** An older conversation recorded no page, and must load as exactly what it was. */
    @Test public void anOlderWholeDocumentTurnLoadsUnchanged() {
        DocumentReference whole = new DocumentReference("/data/orbit/health.pdf",
                "Health Behavior Theory", 388);
        AssistantClient.History turn = new AssistantClient.History("user", "Summarize this", true,
                Collections.emptyList(), "pdf", "Health Behavior Theory", "context",
                "", "", "", "", Collections.singletonList(whole));
        String conversationId = ConversationStore.newId();
        ConversationStore.save(context, conversationId, Collections.singletonList(turn));

        DocumentReference restored =
                ConversationStore.load(context, conversationId).messages.get(0).documents.get(0);
        assertFalse("no page was recorded, so none is invented", restored.namesPage());
        assertEquals(0, restored.openAt());
    }
}

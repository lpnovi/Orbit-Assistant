package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** The document feature's pure state, local search, page context and durable metadata. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DocumentViewerModelTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        SharedContentStore.clear();
        ConversationStore.clear(context);
        DiagnosticStore.prefs(context).edit().clear().commit();
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        SharedContentStore.clear();
        DocumentViewerStore.clear();
        ConversationStore.clear(context);
    }

    @Test public void pageNavigationClampsAndRecreatesAtTheSamePage() {
        DocumentViewerModel model = new DocumentViewerModel(18, 0);
        assertEquals("Page 1 of 18", model.counterText());
        assertFalse(model.hasPrevious());
        assertTrue(model.next());
        assertEquals(1, model.page());
        model.moveTo(99);
        assertEquals(17, model.page());
        assertFalse(model.hasNext());
        model.moveTo(-4);
        assertEquals(0, model.page());

        DocumentViewerModel recreated = new DocumentViewerModel(18, 12);
        assertEquals(12, recreated.page());
        assertEquals("Page 13 of 18", recreated.counterText());
    }

    @Test public void renderWindowNeverExceedsPreviousCurrentNext() {
        assertEquals(Arrays.asList(0, 1), DocumentRenderWindow.around(0, 20));
        assertEquals(Arrays.asList(8, 9, 10), DocumentRenderWindow.around(9, 20));
        assertEquals(Arrays.asList(18, 19), DocumentRenderWindow.around(19, 20));
        assertTrue(DocumentRenderWindow.around(0, 0).isEmpty());
    }

    @Test public void aStaleRenderGenerationCannotReplaceTheCurrentPage() {
        DocumentRenderWindow guard = new DocumentRenderWindow();
        long pageSeven = guard.nextGeneration();
        long pageEight = guard.nextGeneration();
        assertFalse(guard.accepts(pageSeven));
        assertTrue(guard.accepts(pageEight));
    }

    @Test public void searchIsCaseInsensitivePageAwareAndCountsEveryMatch() {
        DocumentTextIndex index = new DocumentTextIndex(Arrays.asList(
                "Orbit begins here. orbit again.",
                "Nothing on this page.",
                "The final ORBIT mention."));
        List<DocumentTextIndex.Match> matches = index.search("oRbIt");
        assertEquals(3, matches.size());
        assertEquals(0, matches.get(0).page);
        assertEquals(0, matches.get(1).page);
        assertEquals(2, matches.get(2).page);
        assertTrue(index.search("missing phrase").isEmpty());
    }

    @Test public void imageOnlyPagesProduceACleanNoTextState() {
        DocumentTextIndex index = new DocumentTextIndex(Arrays.asList("", "   ", null));
        assertFalse(index.hasSearchableText());
        assertTrue(index.search("anything").isEmpty());
    }

    @Test public void pathologicalSearchResultVolumeIsBounded() {
        char[] repeated = new char[DocumentTextIndex.MAX_MATCHES + 500];
        java.util.Arrays.fill(repeated, 'a');
        DocumentTextIndex index = new DocumentTextIndex(
                java.util.Collections.singletonList(new String(repeated)));
        assertEquals(DocumentTextIndex.MAX_MATCHES, index.search("a").size());
    }

    @Test public void askStagesExactPageMetadataWithoutPastingOrSending() {
        int chatsBefore = ConversationStore.list(context).size();
        DocumentPageContext page = new DocumentPageContext("syllabus.pdf", 3, 18,
                "Confidential page sentence.");
        String token = SharedContentStore.stageDocumentPage(page);
        SharedContentStore.Staged staged = SharedContentStore.consume(token);

        assertNotNull(staged);
        assertEquals(SharedContentStore.SOURCE_DOCUMENT_PAGE, staged.source);
        assertEquals("the page is not pasted into the composer text", "", staged.text);
        // The document's name and the place inside it are separate facts, so the card can put
        // them on separate lines instead of ellipsizing away whichever one the reader needed.
        assertEquals("syllabus.pdf", staged.documentPage.displayLabel());
        assertEquals("Page 4 of 18", staged.documentPage.pageLabel());
        assertTrue(staged.documentPage.requestContext().contains("only that page"));
        assertTrue(staged.documentPage.requestContext().contains("Confidential page sentence."));
        assertEquals("staging alone creates no conversation and therefore no request",
                chatsBefore, ConversationStore.list(context).size());
        assertNull("the handoff token is one-shot", SharedContentStore.consume(token));
        assertFalse("diagnostics contain no extracted page text",
                DiagnosticStore.prefs(context).getAll().toString().contains("Confidential"));
    }

    @Test public void pageContextArrivesAsARemovableComposerChipAndNeverAutoSends() {
        DocumentPageContext page = new DocumentPageContext("syllabus.pdf", 14, 20,
                "The text on page fifteen.");
        String token = SharedContentStore.stageDocumentPage(page);
        String conversationId = ConversationStore.newId();
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ChatActivity.EXTRA_SHARE_TOKEN, token);
        org.robolectric.android.controller.ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        ChatActivity chat = controller.get();
        try {
            assertEquals("page text is not pasted", "", chat.composerText());
            assertEquals(1, chat.pendingAttachments().size());
            ComposerAttachment attached = chat.pendingAttachments().get(0);
            assertEquals("pdf_page", attached.kind);
            assertEquals("syllabus.pdf", attached.label);
            assertEquals("Page 15 of 20", attached.detail);
            assertTrue("the card knows it is one page, not a whole document",
                    attached.isDocumentPage());
            assertTrue(PendingRequestStore.activeForConversation(context, conversationId).isEmpty());
            ComposerAttachments removable = new ComposerAttachments();
            removable.add(attached);
            assertTrue("the ordinary attachment collection can remove page context",
                    removable.remove(attached.id));
        } finally {
            controller.pause().stop().destroy();
        }
    }

    @Test public void documentReferenceSurvivesConversationRecreationAndDeletesWithChat()
            throws Exception {
        File pdf = new File(context.getFilesDir(), "document-history-test.pdf");
        assertTrue(pdf.createNewFile() || pdf.exists());
        DocumentReference reference = new DocumentReference(pdf.getAbsolutePath(),
                "syllabus.pdf", 18);
        AssistantClient.History user = new AssistantClient.History("user", "Explain this", true,
                java.util.Collections.emptyList(), "pdf", "syllabus.pdf", "safe context",
                "", "", "", "", java.util.Collections.singletonList(reference));
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, java.util.Collections.singletonList(user));

        ConversationStore.Conversation restored = ConversationStore.load(context, id);
        assertNotNull(restored);
        assertEquals(1, restored.messages.get(0).documents.size());
        assertEquals("syllabus.pdf", restored.messages.get(0).documents.get(0).label);
        assertEquals(18, restored.messages.get(0).documents.get(0).pageCount);

        ConversationStore.delete(context, id);
        assertFalse("the private PDF is released when its last chat is deleted", pdf.exists());
    }

    @Test public void clearingAllHistoryReleasesRetainedDocuments() throws Exception {
        File pdf = new File(context.getFilesDir(), "document-clear-test.pdf");
        assertTrue(pdf.createNewFile() || pdf.exists());
        DocumentReference reference = new DocumentReference(pdf.getAbsolutePath(),
                "notes.pdf", 2);
        AssistantClient.History user = new AssistantClient.History("user", "Summarize", true,
                java.util.Collections.emptyList(), "pdf", "notes.pdf", "safe context",
                "", "", "", "", java.util.Collections.singletonList(reference));
        ConversationStore.save(context, ConversationStore.newId(),
                java.util.Collections.singletonList(user));

        ConversationStore.clear(context);

        assertFalse("clear history also clears its private PDF", pdf.exists());
    }

    @Test public void missingViewerCapabilityFinishesWithoutCrashing() {
        Intent intent = new Intent(context, DocumentViewerActivity.class)
                .putExtra(DocumentViewerActivity.EXTRA_TOKEN, "missing-token");
        org.robolectric.android.controller.ActivityController<DocumentViewerActivity> controller =
                Robolectric.buildActivity(DocumentViewerActivity.class, intent).create();
        try {
            assertTrue(controller.get().isFinishing());
        } finally {
            controller.destroy();
        }
    }
}

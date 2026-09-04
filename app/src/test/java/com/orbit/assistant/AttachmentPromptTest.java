package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

/**
 * What Orbit asks on the user's behalf, and whether it is true.
 *
 * <p>Both surfaces used to say "Analyze this PDF preview and tell me the important points" for
 * every PDF, including the ordinary documents whose entire text Orbit had just extracted page by
 * page. "Preview" describes the fallback for a scan, so the sentence quietly told the model to
 * treat a fully-read document as a couple of pictures of its opening pages. Nothing failed; the
 * answers were simply hedged about pages the model could actually read.
 */
public final class AttachmentPromptTest {

    private static ComposerAttachment pdf(String contentState) {
        return new ComposerAttachment("pdf", "syllabus.pdf · 12 pages · text loaded",
                "context", null, new DocumentReference("/data/orbit/x.pdf", "syllabus.pdf", 12),
                "", contentState);
    }

    // ---- the wording has to match what was loaded -------------------------------------------------

    @Test public void aFullyReadPdfIsNotCalledAPreview() {
        String prompt = AttachmentPrompts.defaultPrompt(
                pdf(ComposerAttachment.CONTENT_FULL_TEXT));
        assertEquals("Analyze this PDF and tell me the important points.", prompt);
        assertFalse(prompt, prompt.toLowerCase(Locale.ROOT).contains("preview"));
    }

    @Test public void aPartiallyReadPdfSaysSo() {
        String prompt = AttachmentPrompts.defaultPrompt(
                pdf(ComposerAttachment.CONTENT_PARTIAL_TEXT));
        assertEquals("Analyze the available text from this PDF and tell me the important points.",
                prompt);
        assertFalse("it must not imply the whole document either",
                prompt.toLowerCase(Locale.ROOT).contains("preview"));
    }

    /** The one case "preview" describes: a document Orbit could render but not read. */
    @Test public void aScannedPdfIsTheOnlyPreview() {
        assertEquals("Analyze this PDF preview and tell me the important points.",
                AttachmentPrompts.defaultPrompt(pdf(ComposerAttachment.CONTENT_VISUAL_ONLY)));
    }

    /** An attachment recorded before content state existed is not accused of being a preview. */
    @Test public void anUnlabelledPdfDefaultsToTheHonestWording() {
        assertEquals("Analyze this PDF and tell me the important points.",
                AttachmentPrompts.defaultPrompt(pdf(ComposerAttachment.CONTENT_UNKNOWN)));
    }

    // ---- the other kinds are unchanged ------------------------------------------------------------

    @Test public void everyOtherAttachmentKeepsItsOwnQuestion() {
        assertEquals("Summarize this file and tell me what matters.",
                AttachmentPrompts.defaultPrompt(
                        new ComposerAttachment("file_text", "notes.txt", "c", null)));
        assertEquals("Help me with this clipboard content.",
                AttachmentPrompts.defaultPrompt(
                        new ComposerAttachment("clipboard", "Clipboard text", "c", null)));
        assertEquals("What should I know about this selection?",
                AttachmentPrompts.defaultPrompt(
                        new ComposerAttachment("screen_selection", "Selection", "c", null)));
        assertEquals("What can you tell me about this screen?",
                AttachmentPrompts.defaultPrompt(
                        new ComposerAttachment("screen", "Screen", "c", null)));
        assertEquals("What can you tell me about this image?",
                AttachmentPrompts.defaultPrompt(
                        new ComposerAttachment("image", "Photo", "", null)));
    }

    @Test public void severalAttachmentsAreAskedAboutTogether() {
        assertEquals("What can you tell me about these 3 attachments?",
                AttachmentPrompts.defaultPrompt(Arrays.asList(
                        new ComposerAttachment("image", "a", "", null),
                        new ComposerAttachment("image", "b", "", null),
                        new ComposerAttachment("image", "c", "", null))));
    }

    @Test public void nothingAttachedAsksTheGeneralQuestion() {
        assertEquals("What can you help me with?",
                AttachmentPrompts.defaultPrompt(Collections.emptyList()));
        assertEquals("What can you help me with?",
                AttachmentPrompts.defaultPrompt((java.util.List<ComposerAttachment>) null));
        assertEquals("What can you help me with?",
                AttachmentPrompts.defaultPrompt((ComposerAttachment) null));
    }

    /** A staged page is asked about as a page, not as a whole document. */
    @Test public void aPageAttachmentAsksAboutThePage() {
        ComposerAttachment page = ComposerAttachment.documentPage(
                new DocumentPageContext("Health Behavior Theory", 4, 388, "Chapter four.",
                        new DocumentReference("/data/orbit/h.pdf", "Health Behavior Theory", 388),
                        null));
        String prompt = AttachmentPrompts.defaultPrompt(page);
        assertTrue(prompt, prompt.toLowerCase(Locale.ROOT).contains("page"));
        assertFalse("a page is not a whole PDF",
                prompt.toLowerCase(Locale.ROOT).contains("this pdf"));
    }

    // ---- the loader records the state the wording depends on --------------------------------------

    /** The card's caption and the prompt are two readings of the same recorded fact. */
    @Test public void theCardCaptionAndThePromptAgree() {
        ComposerAttachment full = pdf(ComposerAttachment.CONTENT_FULL_TEXT);
        assertTrue(full.label.contains("text loaded"));
        assertFalse(AttachmentPrompts.defaultPrompt(full).contains("preview"));

        ComposerAttachment visual = new ComposerAttachment("pdf",
                "scan.pdf · 4 pages · visual preview", "context", null,
                new DocumentReference("/data/orbit/s.pdf", "scan.pdf", 4), "",
                ComposerAttachment.CONTENT_VISUAL_ONLY);
        assertTrue(visual.label.contains("visual preview"));
        assertTrue(AttachmentPrompts.defaultPrompt(visual).contains("preview"));
    }
}

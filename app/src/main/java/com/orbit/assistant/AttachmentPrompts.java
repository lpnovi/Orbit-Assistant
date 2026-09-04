package com.orbit.assistant;

import java.util.List;

/**
 * What Orbit asks on the user's behalf when they attach something and type nothing.
 *
 * <p>One implementation for full chat and the Side-button overlay, because the two had identical
 * copies of it and the copies were identically wrong about PDFs. Both said "Analyze this PDF
 * preview and tell me the important points" for every PDF, including the ordinary text documents
 * whose entire text Orbit had just extracted page by page. "Preview" describes the fallback for a
 * scan, so the sentence quietly told the model to treat a fully-read document as a couple of
 * pictures of its first pages.
 *
 * <p>The rule is that the prompt has to agree with the context beside it. Orbit already records how
 * much it managed to load, so the prompt is chosen from that rather than from the file's type:
 * everything loaded, some of it loaded, or only a rendering. Understating what Orbit has is not a
 * safe direction to be wrong in — it invites the model to hedge about pages it can actually read —
 * and overstating it is worse.
 */
public final class AttachmentPrompts {

    private AttachmentPrompts() {}

    /** The question Orbit asks for a turn the user left empty. */
    public static String defaultPrompt(List<ComposerAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return "What can you help me with?";
        if (attachments.size() > 1) {
            return "What can you tell me about these " + attachments.size() + " attachments?";
        }
        return defaultPrompt(attachments.get(0));
    }

    /** The same question for one attachment. */
    public static String defaultPrompt(ComposerAttachment attachment) {
        if (attachment == null) return "What can you help me with?";
        if (attachment.isDocumentPage()) {
            return attachment.hasDetail()
                    ? "What's on this page?" : "What can you tell me about this page?";
        }
        if ("file_text".equals(attachment.kind)) {
            return "Summarize this file and tell me what matters.";
        }
        if ("pdf".equals(attachment.kind)) return pdfPrompt(attachment.contentState);
        if ("clipboard".equals(attachment.kind)) return "Help me with this clipboard content.";
        if ("screen_selection".equals(attachment.kind)) {
            return "What should I know about this selection?";
        }
        if ("screen".equals(attachment.kind)) return "What can you tell me about this screen?";
        return "What can you tell me about this image?";
    }

    /**
     * The PDF wording, chosen by what was actually loaded.
     *
     * <p>"Preview" is reserved for the one case it describes: a document Orbit could not read, only
     * render. A partially loaded document says so rather than implying either extreme — the context
     * beside it already names exactly which pages are missing, and the prompt must not contradict
     * that by asking for an analysis of the whole thing.
     */
    static String pdfPrompt(String contentState) {
        if (ComposerAttachment.CONTENT_VISUAL_ONLY.equals(contentState)) {
            return "Analyze this PDF preview and tell me the important points.";
        }
        if (ComposerAttachment.CONTENT_PARTIAL_TEXT.equals(contentState)) {
            return "Analyze the available text from this PDF and tell me the important points.";
        }
        // Full text, and the pre-Beta-3 default for anything that did not record its state: a PDF
        // Orbit could not read at all never reaches here without being marked visual-only.
        return "Analyze this PDF and tell me the important points.";
    }
}

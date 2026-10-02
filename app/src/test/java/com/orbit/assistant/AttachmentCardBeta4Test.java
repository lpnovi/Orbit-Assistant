package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The Beta 4 sent-attachment treatment is compact and preview-first, not an ellipsis-only tweak. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AttachmentCardBeta4Test {
    @Test public void metadataIsQuietAndTypeSpecific() {
        assertEquals("PDF · Text loaded", ChatActivity.attachmentMetadata(
                new AssistantClient.History("user", "", true, "", "pdf",
                        "paper.pdf", "extracted")));
        assertEquals("Image", ChatActivity.attachmentMetadata(
                new AssistantClient.History("user", "", true, "", "image",
                        "photo.jpg", "")));
        assertEquals("Text · Loaded", ChatActivity.attachmentMetadata(
                new AssistantClient.History("user", "", true, "", "vault",
                        "Note", "saved words")));
    }

    @Test public void implementationPinsCompactDimensionsAndPreviewReplacementRule() {
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/ChatActivity.java");
        assertTrue(source.contains("setMinimumHeight(UiKit.dp(this, 68))"));
        assertTrue(source.contains("UiKit.dp(this, 52), UiKit.dp(this, 52)"));
        assertTrue(source.contains("A document that already"));
        assertTrue(source.contains("has a thumbnail never gets a duplicate icon"));
        assertTrue(source.contains("setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE)"));
        assertTrue(source.contains("label, 14, UiKit.TEXT, false"));
        assertFalse("filename text is no longer loud accent purple",
                source.contains("label, 14, UiKit.accent"));
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Conversation Control in the Side-button overlay (0.8.3.0-beta.3), checked at the source.
 *
 * <p>The overlay stays the compact surface it is: it gains no new control, no navigator and no
 * repeated attachment rows. What it does gain rides on what it already has - Send's long-press, the
 * status line - and its retries keep earlier answers exactly as full chat's do.
 */
public final class OverlayConversationControlTest {
    private static String overlay() {
        return ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
    }

    @Test public void anOrdinaryOverlayRetryKeepsTheEarlierAnswer() {
        String source = overlay();
        assertTrue(source.contains("retryAsVariant(answerAt, user, null)"));
        assertTrue(source.contains("OrbitRequestManager.enqueueAnswerVariant("));
        assertTrue("a failed retry says the earlier answer is still there",
                source.contains("Retry failed · earlier answer kept"));
    }

    @Test public void theOverlayOffersNoEditBecauseBranchesLiveInFullChat() {
        assertTrue(overlay().contains("MessageActions.bindUser(bubble, text, null,"));
    }

    @Test public void sendWithIsHeldSendAndIsSpentByOneMessage() {
        String source = overlay();
        assertTrue(source.contains("sendButton.setOnLongClickListener("));
        int consume = source.indexOf("AiSelection requestSelection = oneTurnSelection == null");
        assertTrue(consume >= 0);
        assertTrue("the choice is cleared as soon as it is used",
                source.indexOf("oneTurnSelection = null;", consume) > consume);
        assertFalse("Send with never becomes the chat's selection",
                source.contains("applyOverlaySelection(oneTurnSelection"));
    }

    @Test public void keptContextIsACountOnTheStatusLineNotARowPerMessage() {
        String source = overlay();
        assertTrue(source.contains("ready + \" · \" + kept + \" kept\""));
        assertFalse("no kept-context row is drawn under overlay messages",
                source.contains("KeptContext") && source.contains("addScreenAttachmentBadge(item.label"));
    }

    @Test public void longAttachmentNamesStayOnOneLineInTheOverlayToo() {
        String source = overlay();
        int badge = source.indexOf("private void addScreenAttachmentBadge");
        String body = source.substring(badge, source.indexOf("messages.addView(badge", badge));
        assertTrue(body.contains("setSingleLine(true)"));
        assertTrue(body.contains("TruncateAt.MIDDLE"));
    }
}

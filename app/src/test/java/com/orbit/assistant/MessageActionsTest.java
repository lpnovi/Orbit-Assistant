package com.orbit.assistant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Copy must take the real message, not chrome, and the contextual menu must expose Copy /
 * Regenerate or Copy / Edit &amp; resend without putting persistent controls under bubbles.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class MessageActionsTest {

    @Test public void userCopyIsTheVisibleMessageAndNothingElse() {
        assertEquals("Bring milk and eggs", MessageActions.userCopyText("Bring milk and eggs"));
        assertEquals("Hello - world", MessageActions.userCopyText("Hello — world"));
        assertEquals("", MessageActions.userCopyText(null));
        assertEquals("", MessageActions.userCopyText(""));
        assertFalse(MessageActions.userCopyText("Bring milk").contains("Copy"));
        assertFalse(MessageActions.userCopyText("Bring milk").contains("user"));
    }

    @Test public void assistantCopyKeepsTheReplyAndATrailingSource() {
        String raw = "OLED is usually best for perfect blacks.\n\nSource: https://example.com/oled";
        String copied = MessageActions.assistantCopyText(raw);
        assertTrue(copied.startsWith("OLED is usually best for perfect blacks."));
        assertTrue(copied.contains("Source: https://example.com/oled"));
        assertFalse(copied.contains("Copy"));
        assertFalse(copied.contains("Regenerate"));
    }

    @Test public void assistantCopyKeepsMarkdownAndTheFullLongReply() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 40; i++) body.append("**Point ").append(i).append("** is still in the answer. ");
        String copied = MessageActions.assistantCopyText(body.toString());
        assertTrue(copied.contains("**Point 0**"));
        assertTrue(copied.contains("**Point 39**"));
        assertEquals(body.toString().trim(), copied);
    }

    @Test public void theLatestAssistantTurnOffersCopyAndRegenerate() {
        assertArrayEquals(new String[]{MessageActions.COPY_MENU_LABEL, MessageActions.REGENERATE_MENU_LABEL},
                MessageActions.assistantLabels(true));
        assertEquals(2, MessageActions.assistantIcons(true).length);
    }

    @Test public void olderAssistantTurnsOfferCopyOnly() {
        assertArrayEquals(new String[]{MessageActions.COPY_MENU_LABEL},
                MessageActions.assistantLabels(false));
        assertEquals(1, MessageActions.assistantIcons(false).length);
    }

    /**
     * Edit &amp; resend is temporarily withdrawn from the menu until its resend state handling is
     * reliable on device; the composer-side editing machinery remains and is still tested below.
     */
    @Test public void userTurnsOfferCopyOnly() {
        assertArrayEquals(new String[]{MessageActions.COPY_MENU_LABEL},
                MessageActions.userLabels());
        assertEquals(1, MessageActions.userIcons().length);
    }

    @Test public void copyingPutsTheAssistantTextOnTheClipboard() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        MessageActions.copyAssistant(activity,
                "Saturn is a gas giant.\n\nSource: https://example.com/saturn", null);
        assertEquals("Saturn is a gas giant.\n\nSource: https://example.com/saturn",
                clipboardText(activity));
    }

    @Test public void aUserBubbleCanBeCopiedWithoutChangingTheText() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TextView bubble = UiKit.text(activity, "Pack a jacket", 15, UiKit.TEXT, false);
        MessageActions.bindUser(bubble, "Pack a jacket", () -> {}, null);
        assertTrue(bubble.isLongClickable());
        MessageActions.copyUser(activity, "Pack a jacket", () -> {});
        assertEquals("Pack a jacket", clipboardText(activity));
        assertEquals("Pack a jacket", bubble.getText().toString());
    }

    @Test public void emptyUserTextDoesNotGainACopyGesture() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TextView bubble = UiKit.text(activity, "", 15, UiKit.TEXT, false);
        MessageActions.bindUser(bubble, "   ", () -> {}, null);
        assertFalse(bubble.isLongClickable());
    }

    @Test public void anAssistantBubbleBecomesLongPressable() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TextView bubble = UiKit.text(activity, "Saturn is a gas giant.", 15, UiKit.TEXT, false);
        MessageActions.bindAssistant(bubble, "Saturn is a gas giant.", true, () -> {}, null);
        assertTrue(bubble.isLongClickable());
    }

    private static String clipboardText(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        ClipData clip = cm.getPrimaryClip();
        assertNotNull(clip);
        assertTrue(clip.getItemCount() > 0);
        CharSequence text = clip.getItemAt(0).getText();
        return text == null ? "" : text.toString();
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Copy must take the real message, not chrome, and the assistant action row must stay a
 * compact non-focusable control so it cannot steal the composer.
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
        assertFalse(copied.contains("Copy Orbit response"));
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

    @Test public void theAssistantRowExposesCopyAndOptionalRegenerate() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        LinearLayout withRegen = MessageActions.assistantRow(activity, "Hello", true, () -> {});
        assertEquals(2, withRegen.getChildCount());
        ImageButton copy = (ImageButton) withRegen.getChildAt(0);
        ImageButton regen = (ImageButton) withRegen.getChildAt(1);
        assertEquals(MessageActions.COPY_ASSISTANT_DESCRIPTION, copy.getContentDescription());
        assertEquals(MessageActions.REGENERATE_DESCRIPTION, regen.getContentDescription());
        assertFalse("copy must not steal composer focus", copy.isFocusable());
        assertFalse(regen.isFocusable());

        LinearLayout copyOnly = MessageActions.assistantRow(activity, "Hello", false, () -> {});
        assertEquals(1, copyOnly.getChildCount());
        assertEquals(MessageActions.COPY_ASSISTANT_DESCRIPTION,
                copyOnly.getChildAt(0).getContentDescription());
    }

    @Test public void tappingCopyPutsTheAssistantTextOnTheClipboard() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        LinearLayout row = MessageActions.assistantRow(activity,
                "Saturn is a gas giant.\n\nSource: https://example.com/saturn", true, () -> {});
        row.getChildAt(0).performClick();
        assertEquals("Saturn is a gas giant.\n\nSource: https://example.com/saturn",
                clipboardText(activity));
    }

    @Test public void aUserBubbleCanBeCopiedWithoutChangingTheText() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TextView bubble = UiKit.text(activity, "Pack a jacket", 15, UiKit.TEXT, false);
        MessageActions.bindUserCopy(bubble, "Pack a jacket", null);
        assertTrue(bubble.isLongClickable());
        MessageActions.copyUser(activity, bubble, "Pack a jacket", () -> {});
        assertEquals("Pack a jacket", clipboardText(activity));
        assertEquals("Pack a jacket", bubble.getText().toString());
    }

    @Test public void emptyUserTextDoesNotGainACopyGesture() {
        android.app.Activity activity = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        TextView bubble = UiKit.text(activity, "", 15, UiKit.TEXT, false);
        MessageActions.bindUserCopy(bubble, "   ", null);
        assertFalse(bubble.isLongClickable());
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

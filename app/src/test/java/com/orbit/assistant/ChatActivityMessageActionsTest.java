package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Full chat keeps the conversation clean: no persistent Copy/Regenerate chrome under replies.
 * Long-press is how message actions appear; Edit &amp; resend only fills the composer.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatActivityMessageActionsTest {
    private static final String CHAT_ID = "message-actions-test";
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        MessageActions.dismiss();
    }

    @Test public void aFreshChatHasNoPersistentMessageActions() {
        ChatActivity activity = openChat();
        assertEquals(0, countCopyButtons(activity.getWindow().getDecorView()));
        assertEquals(0, countRegenButtons(activity.getWindow().getDecorView()));
    }

    @Test public void assistantRepliesHaveNoPersistentCopyOrRegenerateChrome() {
        seedConversation(
                user("What is Saturn?"),
                assistant("Saturn is a gas giant."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        assertEquals("ordinary replies must not keep Copy under the bubble",
                0, countCopyButtons(root));
        assertEquals("ordinary replies must not keep Regenerate under the bubble",
                0, countRegenButtons(root));
        assertTrue("the assistant reply must be long-pressable",
                hasLongClickableText(root, "Saturn is a gas giant."));
    }

    @Test public void userMessagesAreLongPressableWithoutAVisibleControl() {
        seedConversation(
                user("Bring milk and eggs"),
                assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        TextView userBubble = findText(root, "Bring milk and eggs");
        assertNotNull("the user's message must still be a bubble", userBubble);
        assertTrue(userBubble.isLongClickable());
        assertEquals(0, countCopyButtons(root));
    }

    @Test public void copyingAnAssistantReplyPutsTheRealTextOnTheClipboard() {
        seedConversation(
                user("What is Saturn?"),
                assistant("Saturn is a gas giant.\n\nSource: https://example.com/saturn"));
        ChatActivity activity = openChat();
        EditText composer = findComposer(activity.getWindow().getDecorView());
        assertNotNull(composer);
        composer.requestFocus();
        assertTrue(composer.hasFocus());

        MessageActions.copyAssistant(activity,
                "Saturn is a gas giant.\n\nSource: https://example.com/saturn", null);

        assertEquals("Saturn is a gas giant.\n\nSource: https://example.com/saturn",
                clipboardText(activity));
        assertTrue("copy must leave the composer focused", composer.hasFocus());
        assertEquals(2, ConversationStore.load(context, CHAT_ID).messages.size());
    }

    @Test public void copyingAUserMessageDoesNotChangeTheBubble() {
        seedConversation(
                user("Bring milk and eggs"),
                assistant("Noted."));
        ChatActivity activity = openChat();
        TextView userBubble = findText(activity.getWindow().getDecorView(), "Bring milk and eggs");
        assertNotNull(userBubble);
        MessageActions.copyUser(activity, "Bring milk and eggs", null);
        assertEquals("Bring milk and eggs", clipboardText(activity));
        assertEquals("Bring milk and eggs", userBubble.getText().toString());
        assertEquals(2, ConversationStore.load(context, CHAT_ID).messages.size());
    }

    @Test public void editAndResendPutsTheOriginalTextInTheComposer() {
        seedConversation(
                user("Bring milk and eggs"),
                assistant("Noted."));
        ChatActivity activity = openChat();
        EditText composer = findComposer(activity.getWindow().getDecorView());
        assertNotNull(composer);
        activity.placeInComposer("Bring milk and eggs");
        assertEquals("Bring milk and eggs", composer.getText().toString());
        assertEquals("the original turn must still be in history",
                "Bring milk and eggs",
                ConversationStore.load(context, CHAT_ID).messages.get(0).content);
        assertEquals(2, ConversationStore.load(context, CHAT_ID).messages.size());
    }

    @Test public void editAndResendLeavesTheComposerEditable() {
        seedConversation(user("Original"), assistant("Answer"));
        ChatActivity activity = openChat();
        EditText composer = findComposer(activity.getWindow().getDecorView());
        activity.placeInComposer("Original");
        composer.getText().append(" plus more");
        assertEquals("Original plus more", composer.getText().toString());
        assertEquals("Original", ConversationStore.load(context, CHAT_ID).messages.get(0).content);
    }

    @Test public void markdownRepliesCopyTheMessageNotRenderedLabels() {
        seedConversation(
                user("Summarise OLED"),
                assistant("## OLED vs Mini LED\n\n**Quick takeaway:** OLED is usually best."));
        ChatActivity activity = openChat();
        assertEquals(0, countCopyButtons(activity.getWindow().getDecorView()));
        MessageActions.copyAssistant(activity,
                "## OLED vs Mini LED\n\n**Quick takeaway:** OLED is usually best.", null);
        String copied = clipboardText(activity);
        assertTrue(copied.contains("OLED vs Mini LED"));
        assertTrue(copied.contains("OLED is usually best."));
        assertFalse(copied.contains("Copy code block"));
        assertFalse(copied.contains("Copy Orbit response"));
    }

    @Test public void jumpToLatestIsStillHostedInTheConversationPane() {
        ChatActivity activity = openChat();
        ImageButton jump = (ImageButton) findByDescription(activity.getWindow().getDecorView(),
                "Jump to latest");
        assertNotNull(jump);
        assertEquals(View.GONE, jump.getVisibility());
    }

    private ChatActivity openChat() {
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, CHAT_ID);
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        return controller.get();
    }

    private void seedConversation(AssistantClient.History... turns) {
        List<AssistantClient.History> history = new ArrayList<>();
        for (AssistantClient.History turn : turns) history.add(turn);
        ConversationStore.save(context, CHAT_ID, history);
    }

    private static AssistantClient.History user(String text) {
        return new AssistantClient.History("user", text);
    }

    private static AssistantClient.History assistant(String text) {
        return new AssistantClient.History("assistant", text);
    }

    private static String clipboardText(Context context) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(cm);
        ClipData clip = cm.getPrimaryClip();
        assertNotNull(clip);
        CharSequence text = clip.getItemAt(0).getText();
        return text == null ? "" : text.toString();
    }

    private static int countCopyButtons(View view) {
        return countByDescription(view, "Copy Orbit response");
    }

    private static int countRegenButtons(View view) {
        return countByDescription(view, "Regenerate response");
    }

    private static int countByDescription(View view, String description) {
        int count = 0;
        CharSequence label = view.getContentDescription();
        if (description.equals(label == null ? null : label.toString())) count++;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                count += countByDescription(group.getChildAt(i), description);
            }
        }
        return count;
    }

    private static View findByDescription(View view, String description) {
        CharSequence label = view.getContentDescription();
        if (description.equals(label == null ? null : label.toString())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean hasLongClickableText(View view, String value) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (value.equals(text == null ? null : text.toString()) && view.isLongClickable()) {
                return true;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasLongClickableText(group.getChildAt(i), value)) return true;
            }
        }
        return false;
    }

    private static TextView findText(View view, String value) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (value.equals(text == null ? null : text.toString())) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText findComposer(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findComposer(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}

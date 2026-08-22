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
 * Full chat hosts Copy and Regenerate on assistant replies and a long-press Copy path on
 * the user's own messages, without putting extra chrome on every bubble.
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
    }

    @Test public void aFreshChatHasNoMessageActions() {
        ChatActivity activity = openChat();
        assertEquals(0, countByDescription(activity.getWindow().getDecorView(),
                MessageActions.COPY_ASSISTANT_DESCRIPTION));
        assertEquals(0, countByDescription(activity.getWindow().getDecorView(),
                MessageActions.REGENERATE_DESCRIPTION));
    }

    @Test public void theLatestAssistantReplyHasCopyAndRegenerate() {
        seedConversation(
                user("What is Saturn?"),
                assistant("Saturn is a gas giant."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        assertEquals(1, countByDescription(root, MessageActions.COPY_ASSISTANT_DESCRIPTION));
        assertEquals(1, countByDescription(root, MessageActions.REGENERATE_DESCRIPTION));

        ImageButton copy = (ImageButton) findByDescription(root, MessageActions.COPY_ASSISTANT_DESCRIPTION);
        ImageButton regen = (ImageButton) findByDescription(root, MessageActions.REGENERATE_DESCRIPTION);
        assertNotNull(copy);
        assertNotNull(regen);
        assertFalse("copy must not steal composer focus", copy.isFocusable());
        assertFalse(regen.isFocusable());
    }

    @Test public void olderAssistantRepliesKeepCopyWithoutRegenerate() {
        seedConversation(
                user("First"),
                assistant("First answer"),
                user("Second"),
                assistant("Second answer"));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        assertEquals(2, countByDescription(root, MessageActions.COPY_ASSISTANT_DESCRIPTION));
        assertEquals(1, countByDescription(root, MessageActions.REGENERATE_DESCRIPTION));
    }

    @Test public void tappingCopyPutsTheAssistantReplyOnTheClipboard() {
        seedConversation(
                user("What is Saturn?"),
                assistant("Saturn is a gas giant.\n\nSource: https://example.com/saturn"));
        ChatActivity activity = openChat();
        EditText composer = findComposer(activity.getWindow().getDecorView());
        assertNotNull(composer);
        composer.requestFocus();
        assertTrue(composer.hasFocus());

        ImageButton copy = (ImageButton) findByDescription(activity.getWindow().getDecorView(),
                MessageActions.COPY_ASSISTANT_DESCRIPTION);
        assertNotNull(copy);
        copy.performClick();

        assertEquals("Saturn is a gas giant.\n\nSource: https://example.com/saturn",
                clipboardText(activity));
        assertTrue("copy must leave the composer focused", composer.hasFocus());
        assertEquals(2, ConversationStore.load(context, CHAT_ID).messages.size());
    }

    @Test public void userMessagesCanBeCopiedThroughLongPressWithoutAVisibleControl() {
        seedConversation(
                user("Bring milk and eggs"),
                assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        TextView userBubble = findText(root, "Bring milk and eggs");
        assertNotNull("the user's message must still be a bubble", userBubble);
        assertTrue(userBubble.isLongClickable());
        assertEquals("no extra copy control should sit under the user bubble",
                1, countByDescription(root, MessageActions.COPY_ASSISTANT_DESCRIPTION));

        MessageActions.copyUser(activity, userBubble, "Bring milk and eggs", () -> {});
        assertEquals("Bring milk and eggs", clipboardText(activity));
        assertEquals("Bring milk and eggs", userBubble.getText().toString());
    }

    @Test public void markdownRepliesCopyTheMessageNotTheRenderedLabels() {
        seedConversation(
                user("Summarise OLED"),
                assistant("## OLED vs Mini LED\n\n**Quick takeaway:** OLED is usually best."));
        ChatActivity activity = openChat();
        ImageButton copy = (ImageButton) findByDescription(activity.getWindow().getDecorView(),
                MessageActions.COPY_ASSISTANT_DESCRIPTION);
        assertNotNull(copy);
        copy.performClick();
        String copied = clipboardText(activity);
        assertTrue(copied.contains("OLED vs Mini LED"));
        assertTrue(copied.contains("OLED is usually best."));
        assertFalse(copied.contains("Copy code block"));
        assertFalse(copied.contains("Copy Orbit response"));
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

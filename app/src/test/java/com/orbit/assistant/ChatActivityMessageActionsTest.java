package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.work.Configuration;
import androidx.work.ListenableWorker;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Full chat keeps the conversation clean: no persistent Copy/Regenerate chrome under replies.
 * Long-press is how message actions appear, it selects the held message with Orbit's ripple rather
 * than resizing it, and Edit &amp; resend puts the message back in the composer without sending.
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
        initWorkManager();
    }

    /**
     * Lets the ordinary Send path run end to end under Robolectric. Only the background execution
     * is stubbed: the request is still enqueued through {@code OrbitRequestManager}, so what these
     * tests assert about history and the composer is the real send behaviour.
     */
    private void initWorkManager() {
        try {
            WorkManager.getInstance(context);
            return;
        } catch (IllegalStateException notInitialized) {
            // Falls through to initialize it for this test application.
        }
        // Off the main thread: WorkManager keeps its queue in a database, and Room refuses main
        // thread access. The send path under test never waits on it.
        ExecutorService background = Executors.newSingleThreadExecutor();
        Configuration configuration = new Configuration.Builder()
                .setExecutor(background)
                .setTaskExecutor(background)
                .setWorkerFactory(new WorkerFactory() {
                    @Override public ListenableWorker createWorker(
                            Context appContext, String workerClassName, WorkerParameters params) {
                        return new Worker(appContext, params) {
                            @Override public Result doWork() {
                                return Result.success();
                            }
                        };
                    }
                })
                .build();
        WorkManager.initialize(context, configuration);
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

    // ---- Long-press selection -----------------------------------------------------------------

    @Test public void longPressingAMessageSelectsItWithoutResizingTheBubble() {
        seedConversation(user("Bring milk and eggs"), assistant("Noted."));
        ChatActivity activity = openChat();
        TextView bubble = findText(activity.getWindow().getDecorView(), "Bring milk and eggs");
        assertNotNull(bubble);

        assertTrue(bubble.performLongClick());

        Drawable held = bubble.getForeground();
        assertTrue("the held message must carry Orbit's ripple selection",
                held instanceof OrbitMessageHighlight);
        assertEquals("the selection must use the live accent",
                UiKit.accent(activity), ((OrbitMessageHighlight) held).accentColor());
        assertEquals("the bubble must not grow", 1f, bubble.getScaleX(), 0.0001f);
        assertEquals("the bubble must not grow", 1f, bubble.getScaleY(), 0.0001f);
        assertEquals("selection must not move the message",
                0f, bubble.getTranslationY(), 0.0001f);
    }

    @Test public void dismissingTheMenuCannotLeaveAMessageSelected() {
        seedConversation(user("Bring milk and eggs"), assistant("Noted."));
        ChatActivity activity = openChat();
        TextView bubble = findText(activity.getWindow().getDecorView(), "Bring milk and eggs");
        bubble.performLongClick();
        assertNotNull(bubble.getForeground());

        MessageActions.dismiss();
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);

        assertNull("a dismissed menu must leave no selected message", bubble.getForeground());
    }

    @Test public void holdingASecondMessageReleasesTheFirst() {
        seedConversation(user("First message"), assistant("Answer"), user("Second message"));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        TextView first = findText(root, "First message");
        TextView second = findText(root, "Second message");
        assertNotNull(first);
        assertNotNull(second);

        first.performLongClick();
        second.performLongClick();
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);

        assertNull("rapid repeated long presses must not stack selections", first.getForeground());
        assertTrue("the message actually held must be the selected one",
                second.getForeground() instanceof OrbitMessageHighlight);
    }

    @Test public void aRichMarkdownReplyIsSelectableAsOneMessage() {
        seedConversation(
                user("Summarise OLED"),
                assistant("## OLED vs Mini LED\n\nOLED gives perfect blacks.\n\n```\ncode\n```"));
        ChatActivity activity = openChat();
        View bubble = findLongClickableAncestorOfText(
                activity.getWindow().getDecorView(), "OLED gives perfect blacks.");
        assertNotNull("a Markdown reply must still be long-pressable", bubble);
        assertTrue(bubble.performLongClick());
        assertTrue(bubble.getForeground() instanceof OrbitMessageHighlight);
        MessageActions.dismiss();
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        assertNull(bubble.getForeground());
    }

    // ---- Edit & resend ------------------------------------------------------------------------

    @Test public void editAndResendExplainsItselfWithoutSending() {
        seedConversation(user("Bring milk"), assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        EditText composer = findComposer(root);
        assertNotNull(composer);

        activity.beginEditResend("Bring milk");

        assertTrue(activity.isEditingPreviousMessage());
        assertEquals("Bring milk", composer.getText().toString());
        assertEquals("the caret belongs at the end of the recalled text",
                composer.length(), composer.getSelectionStart());
        assertEquals("the composer must be ready to type in", composer.length(),
                composer.getSelectionEnd());
        assertTrue("Edit & resend must keep composer focus", composer.hasFocus());
        assertEquals(View.VISIBLE, editingPill(root).getVisibility());
        assertEquals("nothing may be sent by choosing Edit & resend",
                2, ConversationStore.load(context, CHAT_ID).messages.size());
    }

    @Test public void editAndResendHoldsAnUnsentDraftAndPutsItBackOnCancel() {
        seedConversation(user("Bring milk"), assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        EditText composer = findComposer(root);
        composer.setText("half-written thought");

        activity.beginEditResend("Bring milk");
        assertEquals("half-written thought", activity.heldDraft());
        assertEquals("Bring milk", composer.getText().toString());

        findByDescription(root, "Stop editing previous message").performClick();

        assertFalse(activity.isEditingPreviousMessage());
        assertNull(activity.heldDraft());
        assertEquals("an unsent draft must survive Edit & resend",
                "half-written thought", composer.getText().toString());
        assertEquals(View.GONE, editingPill(root).getVisibility());
    }

    @Test public void aSecondEditAndResendKeepsTheOriginalHeldDraft() {
        seedConversation(user("Bring milk"), assistant("Noted."), user("And bread"));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        EditText composer = findComposer(root);
        composer.setText("half-written thought");

        activity.beginEditResend("Bring milk");
        activity.beginEditResend("And bread");

        assertEquals("the recalled message must never become the held draft",
                "half-written thought", activity.heldDraft());
        assertEquals("And bread", composer.getText().toString());
    }

    @Test public void cancellingWithNoEarlierDraftClearsTheRecalledText() {
        seedConversation(user("Bring milk"), assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        EditText composer = findComposer(root);

        activity.beginEditResend("Bring milk");
        findByDescription(root, "Stop editing previous message").performClick();

        assertFalse(activity.isEditingPreviousMessage());
        assertEquals("", composer.getText().toString());
        assertEquals(View.GONE, editingPill(root).getVisibility());
    }

    @Test public void sendingTheRevisedMessageLeavesEditModeAndKeepsTheOriginal() {
        seedConversation(user("Bring milk"), assistant("Noted."));
        ChatActivity activity = openChat();
        View root = activity.getWindow().getDecorView();
        EditText composer = findComposer(root);

        activity.beginEditResend("Bring milk");
        composer.getText().append(" and eggs");
        View send = findByDescription(root, "Send message");
        assertNotNull("Send must be available for the revised message", send);
        send.performClick();

        List<AssistantClient.History> saved = ConversationStore.load(context, CHAT_ID).messages;
        assertEquals(3, saved.size());
        assertEquals("the original turn must be left exactly as it was",
                "Bring milk", saved.get(0).content);
        assertEquals("Bring milk and eggs", saved.get(2).content);
        assertFalse(activity.isEditingPreviousMessage());
        assertNull(activity.heldDraft());
        assertEquals("", composer.getText().toString());
        assertEquals(View.GONE, editingPill(root).getVisibility());
    }

    @Test public void anEmptyMessageCannotEnterEditAndResend() {
        seedConversation(user("Bring milk"), assistant("Noted."));
        ChatActivity activity = openChat();
        activity.beginEditResend("   ");
        assertFalse(activity.isEditingPreviousMessage());
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

    /** The compact Edit &amp; resend pill, located through the one control that leaves the mode. */
    private static View editingPill(View root) {
        View stop = findByDescription(root, "Stop editing previous message");
        assertNotNull("Edit & resend must have a visible way out", stop);
        return (View) stop.getParent();
    }

    /**
     * The message a rich Markdown reply's inner text belongs to: the outermost ancestor carrying
     * the long-press, which is the bubble the selection is drawn on.
     */
    private static View findLongClickableAncestorOfText(View root, String value) {
        TextView text = findTextContaining(root, value);
        assertNotNull("the reply text must be rendered", text);
        View bubble = null;
        View node = text;
        while (node != null && node != root) {
            if (node.isLongClickable()) bubble = node;
            node = node.getParent() instanceof View ? (View) node.getParent() : null;
        }
        return bubble;
    }

    /** Rendered Markdown may add its own leading or trailing marks around the text it shows. */
    private static TextView findTextContaining(View view, String value) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.toString().contains(value)) return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findTextContaining(group.getChildAt(i), value);
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

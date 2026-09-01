package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;

import org.junit.After;
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
 * The composer with several attachments on it, driven the way a person drives it.
 *
 * <p>{@link MultiAttachmentTest} pins the model down in isolation; this checks the thing the model
 * exists for. Three photos and a sentence have to leave as <em>one</em> user turn carrying all
 * three, in order — not three turns, not one turn with the first photo — and the composer has to
 * come back empty afterwards without having touched anything the user did not send.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class MultiAttachmentComposerTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});
        TestWorkManager.ensureInitialized(context);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    /**
     * The headline case: "Compare these screenshots" plus three photos is one message.
     *
     * <p>One user turn, all three images on it, in the order they were attached.
     */
    @Test public void threeAttachmentsAndSomeTextLeaveAsOneTurn() {
        ActivityController<ChatActivity> controller = openChat("c-multi-send");
        ChatActivity activity = controller.get();

        attach(activity, "first.jpg", "second.jpg", "third.jpg");
        activity.placeInComposer("Compare these screenshots");
        send(activity);

        List<AssistantClient.History> stored =
                ConversationStore.load(context, "c-multi-send").messages;
        assertEquals("one message, never one per photo", 1, stored.size());
        AssistantClient.History turn = stored.get(0);
        assertEquals("Compare these screenshots", turn.content);
        assertTrue(turn.screenAttached);
        assertEquals("all three images stay on the turn that carried them",
                3, turn.attachmentCount());
        assertEquals("3 attachments", turn.attachmentLabel);
        assertEquals("image", turn.attachmentKind);

        controller.pause().stop().destroy();
    }

    /** The whole set is described to the model, numbered, in order. */
    @Test public void everyAttachmentIsDescribedInOrder() {
        ActivityController<ChatActivity> controller = openChat("c-multi-context");
        ChatActivity activity = controller.get();

        activity.setPendingAttachment(new ComposerAttachment("file_text", "alpha.txt",
                "the alpha contents", null));
        addFile(activity, "beta.txt", "the beta contents");
        activity.placeInComposer("Which is longer?");
        send(activity);

        String context = ConversationStore.load(this.context, "c-multi-context")
                .messages.get(0).attachmentText;
        assertTrue(context.contains("Attachment 1 of 2: alpha.txt"));
        assertTrue(context.contains("Attachment 2 of 2: beta.txt"));
        assertTrue(context.indexOf("the alpha contents") < context.indexOf("the beta contents"));

        controller.pause().stop().destroy();
    }

    /** Sending clears the strip, and a follow-up carries nothing. */
    @Test public void theComposerIsEmptyAfterTheMessageCarryingItHasGone() {
        ActivityController<ChatActivity> controller = openChat("c-multi-clear");
        ChatActivity activity = controller.get();

        attach(activity, "one.jpg", "two.jpg");
        assertEquals(2, activity.pendingAttachments().size());
        activity.placeInComposer("What are these?");
        send(activity);

        assertTrue("the strip empties with the message", activity.pendingAttachments().isEmpty());
        assertNull(activity.pendingAttachment());

        activity.placeInComposer("And the background?");
        assertTrue("nothing re-arms itself between messages",
                activity.pendingAttachments().isEmpty());

        controller.pause().stop().destroy();
    }

    /** Removing one attachment leaves the others, their order, and the typed text alone. */
    @Test public void removingOneLeavesTheTextAndTheOthersUntouched() {
        ActivityController<ChatActivity> controller = openChat("c-multi-remove");
        ChatActivity activity = controller.get();

        attach(activity, "one.jpg", "two.jpg", "three.jpg");
        activity.placeInComposer("Half-written question");

        String middleId = activity.pendingAttachments().get(1).id;
        removeControlFor(activity, middleId).performClick();

        List<ComposerAttachment> left = activity.pendingAttachments();
        assertEquals(2, left.size());
        assertEquals("one.jpg", left.get(0).label);
        assertEquals("three.jpg", left.get(1).label);
        assertEquals("removing an attachment must not touch what the user typed",
                "Half-written question", activity.composerText());

        controller.pause().stop().destroy();
    }

    /** A second batch appends; it does not replace the first. */
    @Test public void asecondBatchAppendsInTheComposer() {
        ActivityController<ChatActivity> controller = openChat("c-multi-append");
        ChatActivity activity = controller.get();

        attach(activity, "one.jpg", "two.jpg");
        addFile(activity, "notes.txt", "some notes");

        List<ComposerAttachment> staged = activity.pendingAttachments();
        assertEquals(3, staged.size());
        assertEquals("one.jpg", staged.get(0).label);
        assertEquals("notes.txt", staged.get(2).label);

        controller.pause().stop().destroy();
    }

    /** A single attachment still behaves exactly as it did before any of this. */
    @Test public void oneAttachmentIsUnchangedEndToEnd() {
        ActivityController<ChatActivity> controller = openChat("c-single");
        ChatActivity activity = controller.get();

        activity.setPendingAttachment(new ComposerAttachment("image", "A photo",
                "attachment context", bitmap()));
        assertNotNull(activity.pendingAttachment());
        activity.placeInComposer("What is in this photo?");
        send(activity);

        AssistantClient.History turn =
                ConversationStore.load(context, "c-single").messages.get(0);
        assertEquals(1, turn.attachmentCount());
        assertEquals("A photo", turn.attachmentLabel);
        assertEquals("attachment context", turn.attachmentText);
        assertNull(activity.pendingAttachment());

        controller.pause().stop().destroy();
    }

    /** With nothing typed, Orbit asks a question that describes what is actually attached. */
    @Test public void anUntypedSendGetsAPromptThatMatchesTheSet() {
        ActivityController<ChatActivity> controller = openChat("c-multi-default");
        ChatActivity activity = controller.get();

        attach(activity, "one.jpg", "two.jpg", "three.jpg");
        send(activity);

        String prompt = ConversationStore.load(context, "c-multi-default").messages.get(0).content;
        assertTrue("the default prompt must describe three things, not one",
                prompt.contains("3"));

        controller.pause().stop().destroy();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Bitmap bitmap() {
        return Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
    }

    private void attach(ChatActivity activity, String... labels) {
        for (String label : labels) {
            activity.addComposerAttachmentForTest(
                    new ComposerAttachment("image", label, "", bitmap()));
        }
    }

    private void addFile(ChatActivity activity, String label, String contents) {
        activity.addComposerAttachmentForTest(
                new ComposerAttachment("file_text", label, contents, null));
    }

    /** The remove control belonging to exactly one attachment, found by its description. */
    private static View removeControlFor(ChatActivity activity, String attachmentId) {
        List<ComposerAttachment> staged = activity.pendingAttachments();
        int position = -1;
        for (int i = 0; i < staged.size(); i++) {
            if (staged.get(i).id.equals(attachmentId)) { position = i; break; }
        }
        if (position < 0) throw new AssertionError("no such attachment is staged");
        // Asked of the same helper the strip labels its controls with, so this finds the control
        // rather than restating its wording - which is what this test is actually about.
        String wanted = AttachmentLabels.removeDescription(
                staged.get(position), position + 1, staged.size());
        for (View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null && wanted.contentEquals(description)) return v;
        }
        throw new AssertionError("every attachment must have its own remove control");
    }

    private static void send(ChatActivity activity) {
        for (View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null
                    && ComposerActionState.SEND_DESCRIPTION.contentEquals(description)) {
                v.performClick();
                return;
            }
        }
        throw new AssertionError("the composer must have a Send control");
    }

    private static List<View> descendants(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) out.addAll(descendants(group.getChildAt(i)));
        }
        return out;
    }

    private ActivityController<ChatActivity> openChat(String conversationId) {
        ConversationStore.save(context, conversationId, new ArrayList<>());
        Intent intent = new Intent(context, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        return Robolectric.buildActivity(ChatActivity.class, intent).setup();
    }
}

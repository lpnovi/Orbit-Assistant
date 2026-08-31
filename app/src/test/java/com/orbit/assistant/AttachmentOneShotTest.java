package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;

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
 * Attaching something is a thing you do to one message, not a mode you switch on.
 *
 * <p>Sending a photo in any other chat app works this way: the photo stays on the message that
 * carried it, and the attach button is not still loaded for whatever you type next. Beta 3's
 * overlay left the screen armed after a send, so a follow-up question picked the screen up again
 * and the conversation grew a second attachment the user never made.
 *
 * <p>The one thing that must <em>not</em> change with it is automatic screen context. Attach screen
 * by default, and an app profile set to Attach, are standing instructions to keep supplying the
 * live screen. Consuming one of those would quietly turn a continuous feature into a one-shot.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class AttachmentOneShotTest {
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

    // ---- the rule -------------------------------------------------------------------------------

    /** A screen the user attached by hand is used up by the message they attached it to. */
    @Test public void aManuallyAttachedScreenIsConsumedByTheMessageItWasSentWith() {
        assertTrue("a hand-attached screen does not survive its own send",
                OrbitSession.screenAttachmentIsConsumedBySend(true, false));
    }

    /** A screen armed by policy is a standing instruction and survives every send. */
    @Test public void anAutomaticallyArmedScreenSurvivesTheSend() {
        assertFalse("automatic screen context must not become one-shot",
                OrbitSession.screenAttachmentIsConsumedBySend(true, true));
    }

    /** A message sent with no screen at all has nothing to consume. */
    @Test public void aMessageWithNoScreenConsumesNothing() {
        assertFalse(OrbitSession.screenAttachmentIsConsumedBySend(false, false));
        assertFalse(OrbitSession.screenAttachmentIsConsumedBySend(false, true));
    }

    /**
     * What arms the screen automatically is unchanged, which is the other half of not breaking it.
     *
     * <p>Read straight from the policy the overlay uses at invocation. If this ever stopped
     * returning true for an Attach profile, automatic screen context would be gone regardless of
     * what the consume rule above says.
     */
    @Test public void theAutomaticArmingPolicyIsUnchanged() {
        assertFalse("off by default, as before",
                AppProfileStore.shouldAttachByDefault(context, "com.example.app", false));
        assertTrue("the global Attach screen by default preference still arms it",
                AppProfileStore.shouldAttachByDefault(context, "com.example.app", true));

        saveScreenPolicy("com.example.attach", AppProfileStore.SCREEN_ATTACH);
        assertTrue("an app profile set to Attach still arms it on its own",
                AppProfileStore.shouldAttachByDefault(context, "com.example.attach", false));

        saveScreenPolicy("com.example.never", AppProfileStore.SCREEN_NEVER);
        assertFalse("and a blocked app is still never armed",
                AppProfileStore.shouldAttachByDefault(context, "com.example.never", true));
    }

    // ---- full chat, end to end -------------------------------------------------------------------

    /**
     * The same rule in the surface where it can actually be driven.
     *
     * <p>Full chat has always consumed its attachment on send; this pins that behaviour down so the
     * two surfaces cannot drift apart again. The attachment ends up on the sent turn, the composer
     * ends up empty, and the follow-up carries nothing.
     */
    @Test public void fullChatConsumesItsAttachmentAndTheFollowUpCarriesNone() {
        ActivityController<ChatActivity> controller = openChat("c-oneshot");
        ChatActivity activity = controller.get();

        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        activity.setPendingAttachment(new ComposerAttachment(
                "image", "A photo", "attachment context", bitmap));
        assertNotNull("the composer is armed before the send", activity.pendingAttachment());

        activity.placeInComposer("What is in this photo?");
        sendButton(activity).performClick();

        assertNull("the composer is unarmed once the message carrying it has gone",
                activity.pendingAttachment());

        List<AssistantClient.History> stored =
                ConversationStore.load(context, "c-oneshot").messages;
        assertEquals(1, stored.size());
        assertTrue("the attachment stayed on the message that carried it",
                stored.get(0).screenAttached);
        assertEquals("image", stored.get(0).attachmentKind);

        // A follow-up typed straight afterwards must not pick the photo back up.
        activity.placeInComposer("And what about the background?");
        assertNull("nothing re-arms itself between messages", activity.pendingAttachment());

        controller.pause().stop().destroy();
    }

    /** Attaching again after a send is a real second share and must still work. */
    @Test public void attachingAgainAfterASendStillArmsTheComposer() {
        ActivityController<ChatActivity> controller = openChat("c-reattach-ui");
        ChatActivity activity = controller.get();

        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        activity.setPendingAttachment(new ComposerAttachment("image", "First", "first", bitmap));
        activity.placeInComposer("What is this?");
        sendButton(activity).performClick();
        assertNull(activity.pendingAttachment());

        activity.setPendingAttachment(new ComposerAttachment("image", "Second", "second", bitmap));
        assertNotNull("deliberately attaching again really does attach again",
                activity.pendingAttachment());
        assertEquals("Second", activity.pendingAttachment().label);

        controller.pause().stop().destroy();
    }

    private void saveScreenPolicy(String pkg, String policy) {
        AppProfileStore.save(context, new AppProfileStore.Profile(pkg, pkg,
                AppProfileStore.CATEGORY_AUTO, AppProfileStore.PRIVACY_AUTO, policy,
                AppProfileStore.SCREENSHOT_GLOBAL, AppProfileStore.MODE_GLOBAL,
                AppProfileStore.ACTION_AUTO, AppProfileStore.ACTION_AUTO,
                AppProfileStore.ACTION_AUTO, System.currentTimeMillis()));
    }

    /** The composer's one Send/Stop control, found the way a person finds it: by its label. */
    private static android.view.View sendButton(ChatActivity activity) {
        for (android.view.View v : descendants(activity.getWindow().getDecorView())) {
            CharSequence description = v.getContentDescription();
            if (description != null
                    && ComposerActionState.SEND_DESCRIPTION.contentEquals(description)) {
                return v;
            }
        }
        throw new AssertionError("the composer must have a Send control");
    }

    private static List<android.view.View> descendants(android.view.View root) {
        List<android.view.View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
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

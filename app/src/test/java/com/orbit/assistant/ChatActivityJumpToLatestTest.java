package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Full chat hosts Jump to latest in the conversation pane, never in the composer, and keeps it
 * hidden while the newest messages are still on screen.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class ChatActivityJumpToLatestTest {

    @Test public void theControlIsPresentButHiddenOnAFreshChat() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);
        assertNotNull("full chat must host Jump to latest", jump);
        assertEquals("Jump to latest", jump.getContentDescription());
        assertEquals(View.GONE, jump.getVisibility());
    }

    @Test public void theControlSitsInTheConversationPaneNotTheComposer() {
        ChatActivity activity = openChat();
        ImageButton jump = findJump(activity);
        assertTrue("the control must float over the conversation, not live in the composer",
                jump.getParent() instanceof FrameLayout);
        FrameLayout pane = (FrameLayout) jump.getParent();
        boolean hasScroll = false;
        for (int i = 0; i < pane.getChildCount(); i++) {
            if (pane.getChildAt(i) instanceof ScrollView) hasScroll = true;
        }
        assertTrue("the conversation pane must still be the scroller", hasScroll);

        EditText composer = findComposer(activity.getWindow().getDecorView());
        assertNotNull(composer);
        assertFalse("the jump control must not be inside the text field's parent row",
                isDescendant(composer.getParent(), jump));
    }

    private ChatActivity openChat() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, "jump-latest-test");
        ActivityController<ChatActivity> controller =
                Robolectric.buildActivity(ChatActivity.class, intent).setup();
        return controller.get();
    }

    private static ImageButton findJump(ChatActivity activity) {
        return (ImageButton) findByDescription(activity.getWindow().getDecorView(), "Jump to latest");
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

    private static boolean isDescendant(android.view.ViewParent parent, View child) {
        android.view.ViewParent walk = child.getParent();
        while (walk != null) {
            if (walk == parent) return true;
            walk = walk.getParent();
        }
        return false;
    }
}

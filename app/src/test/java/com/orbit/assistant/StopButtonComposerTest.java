package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The composer's single control while Orbit is replying.
 *
 * <p>Both surfaces build their composer differently but share one rule and one way of applying it,
 * {@link ComposerActionState}, which is what these drive. The button here is built the same way
 * the real composers build theirs — a 44dp {@code ImageButton} in a horizontal composer row next to
 * the editor — so the footprint and focus assertions mean what they say.
 *
 * <p>The typing assertions are the v0.7.3.9 invariants applied to this feature: tapping Stop is an
 * ordinary button press and must leave the editor's focus, its input connection, and the keyboard
 * exactly as the user had them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class StopButtonComposerTest {
    private Activity activity;
    private Context context;
    private TracingEditText input;
    private ImageButton control;
    private LinearLayout composer;

    private static final String OVERLAY_CHAT = "overlay-conversation";
    private static final String FULL_CHAT = "full-chat-conversation";

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(name -> {});

        LinearLayout root = new LinearLayout(activity);
        composer = new LinearLayout(activity);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        input = new TracingEditText(activity);
        input.setFocusable(true);
        input.setFocusableInTouchMode(true);
        composer.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        control = new ImageButton(activity);
        control.setImageResource(R.drawable.ic_send);
        control.setContentDescription(ComposerActionState.SEND_DESCRIPTION);
        composer.addView(control, new LinearLayout.LayoutParams(UiKit.dp(activity, 44), UiKit.dp(activity, 44)));
        root.addView(composer);
        activity.setContentView(root);
    }

    @After public void tearDown() {
        OrbitRequestManager.resetForTest();
    }

    private void seedUserMessage(String conversationId) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "Tell me about Saturn"));
        ConversationStore.save(context, conversationId, history);
    }

    private PendingRequestStore.Item startRequest(String conversationId) {
        seedUserMessage(conversationId);
        return PendingRequestStore.create(context, conversationId, "Tell me about Saturn", "", "",
                false, false, Prefs.MODE_BALANCED, false, "");
    }

    /** Full chat reads the durable record alone; the overlay also knows its own in-flight turn. */
    private boolean stopForFullChat(String conversationId) {
        return ComposerActionState.shouldShowStop(context, conversationId, false);
    }

    private boolean stopForOverlay(String conversationId, boolean busy) {
        return ComposerActionState.shouldShowStop(context, conversationId, busy);
    }

    // ---- 14. The setting on -------------------------------------------------------------------

    @Test public void settingOnShowsStopWhileARequestIsActive() {
        assertTrue("Stop is on for everyone by default", Prefs.showStopButton(context));
        PendingRequestStore.Item request = startRequest(FULL_CHAT);

        assertTrue(stopForFullChat(FULL_CHAT));
        ComposerActionState.apply(control, true);
        assertEquals(ComposerActionState.STOP_DESCRIPTION, control.getContentDescription());
        assertEquals("Stop response", control.getContentDescription());

        PendingRequestStore.markRunning(context, request.id);
        assertTrue(stopForFullChat(FULL_CHAT));
    }

    @Test public void theOverlayShowsStopFromTheMomentItStartsWaiting() {
        // The overlay flips to waiting before the request has been written down, and must not show
        // Send for that instant.
        assertTrue(stopForOverlay(OVERLAY_CHAT, true));
        assertFalse(stopForOverlay(OVERLAY_CHAT, false));
    }

    // ---- 15. The setting off ------------------------------------------------------------------

    @Test public void settingOffPreservesSendExactly() {
        Prefs.get(context).edit().putBoolean(Prefs.SHOW_STOP_BUTTON, false).commit();
        startRequest(FULL_CHAT);

        assertFalse(stopForFullChat(FULL_CHAT));
        assertFalse(stopForOverlay(OVERLAY_CHAT, true));

        ComposerActionState.apply(control, stopForFullChat(FULL_CHAT));
        assertEquals(ComposerActionState.SEND_DESCRIPTION, control.getContentDescription());
    }

    // ---- 16-17. Getting back to Send -----------------------------------------------------------

    @Test public void normalCompletionRestoresSend() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        assertTrue(stopForFullChat(FULL_CHAT));

        PendingRequestStore.markDone(context, request.id);

        assertFalse(stopForFullChat(FULL_CHAT));
        ComposerActionState.apply(control, stopForFullChat(FULL_CHAT));
        assertEquals(ComposerActionState.SEND_DESCRIPTION, control.getContentDescription());
    }

    @Test public void aFailureAlsoRestoresSend() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);

        PendingRequestStore.markFailed(context, request.id, "Orbit could not finish this response.");

        assertFalse(stopForFullChat(FULL_CHAT));
    }

    @Test public void stopRestoresSendImmediately() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        ComposerActionState.apply(control, true);

        OrbitRequestManager.cancelActiveForConversation(context, FULL_CHAT);

        // No waiting on the worker, no waiting on a callback from the network: the durable record
        // is already terminal the instant cancel returns.
        assertFalse(stopForFullChat(FULL_CHAT));
        ComposerActionState.apply(control, stopForFullChat(FULL_CHAT));
        assertEquals(ComposerActionState.SEND_DESCRIPTION, control.getContentDescription());
    }

    // ---- One control, one footprint -------------------------------------------------------------

    @Test public void stopTakesOverTheSendButtonWithoutMovingTheComposer() {
        int childrenBefore = composer.getChildCount();
        ViewGroup.LayoutParams paramsBefore = control.getLayoutParams();
        int widthBefore = paramsBefore.width;
        int heightBefore = paramsBefore.height;
        int indexBefore = composer.indexOfChild(control);

        ComposerActionState.apply(control, true);

        assertEquals("no extra composer button appears", childrenBefore, composer.getChildCount());
        assertSame("the same control is reused", paramsBefore, control.getLayoutParams());
        assertEquals(widthBefore, control.getLayoutParams().width);
        assertEquals(heightBefore, control.getLayoutParams().height);
        assertEquals(indexBefore, composer.indexOfChild(control));
        assertSame(composer, control.getParent());
    }

    @Test public void sendAndStopUseDifferentIconsAndDescriptions() {
        assertEquals(R.drawable.ic_send, ComposerActionState.iconFor(false));
        assertEquals(R.drawable.ic_stop, ComposerActionState.iconFor(true));
        assertEquals("Send message", ComposerActionState.descriptionFor(false));
        assertEquals("Stop response", ComposerActionState.descriptionFor(true));
    }

    // ---- 18-20. Typing is not disturbed ----------------------------------------------------------

    @Test public void stopDoesNotMoveComposerFocus() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        input.requestFocus();
        input.getText().append("my next message");
        assertTrue(input.hasFocus());
        int connectionsBefore = input.connectionsCreated();

        ComposerActionState.apply(control, true);
        control.performClick();
        OrbitRequestManager.cancelActiveForConversation(context, FULL_CHAT);
        ComposerActionState.apply(control, stopForFullChat(FULL_CHAT));

        assertTrue("the editor keeps focus", input.hasFocus());
        assertSame(input, activity.getWindow().getDecorView().findFocus());
        assertEquals("nothing restarts the input connection",
                connectionsBefore, input.connectionsCreated());
        assertEquals("the draft is untouched", "my next message", input.getText().toString());
    }

    @Test public void theControlItselfNeverTakesFocusFromTheEditor() {
        input.requestFocus();

        // Composer buttons are not focusable in touch mode, in either surface, which is why a tap
        // on Stop cannot pull the typing session out of the editor.
        assertFalse(control.isFocusableInTouchMode());
        control.performClick();

        assertTrue(input.hasFocus());
    }

    @Test public void theNextTypedMessageWorksImmediatelyInFullChat() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        input.requestFocus();

        OrbitRequestManager.cancelActiveForConversation(context, FULL_CHAT);

        // Full chat gates nothing on a request being in flight, and the record is already terminal.
        assertFalse(PendingRequestStore.hasActiveForConversation(context, FULL_CHAT));
        assertFalse(stopForFullChat(FULL_CHAT));
        input.getText().clear();
        input.getText().append("and now the next one");
        assertEquals("and now the next one", input.getText().toString());
        assertTrue(input.hasFocus());
        assertTrue(input.isEnabled());
        assertTrue(input.isFocusableInTouchMode());
        assertTrue(input.getShowSoftInputOnFocus());
    }

    @Test public void theNextTypedMessageWorksImmediatelyInTheSideButtonOverlay() {
        PendingRequestStore.Item request = startRequest(OVERLAY_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        input.requestFocus();
        assertTrue(stopForOverlay(OVERLAY_CHAT, true));

        OrbitRequestManager.cancelActiveForConversation(context, OVERLAY_CHAT);

        // The overlay refuses a new message while it believes it is waiting, so what matters is
        // that stopping ends that state rather than leaving it stuck.
        assertFalse(PendingRequestStore.hasActiveForConversation(context, OVERLAY_CHAT));
        assertFalse("the overlay is no longer waiting", stopForOverlay(OVERLAY_CHAT, false));
        input.getText().append("straight into the next question");
        assertEquals("straight into the next question", input.getText().toString());
        assertTrue(input.hasFocus());
    }

    // ---- One light tick, never two ---------------------------------------------------------------

    /** An ImageButton that counts the haptic requests made of it, without needing real hardware. */
    private static final class CountingButton extends ImageButton {
        int ticks;

        CountingButton(Context context) { super(context); }

        @Override public boolean performHapticFeedback(int feedbackConstant) {
            ticks++;
            return true;
        }
    }

    @Test public void stopGivesExactlyOneLightTickWhenHapticsAreOn() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, true).commit();
        CountingButton button = pressableComposerButton();

        press(button);

        // The tick comes from the shared composer press behaviour, which is why stopping adds none
        // of its own and one tap can only ever produce one.
        assertEquals(1, button.ticks);
    }

    @Test public void stopIsSilentWhenHapticsAreOff() {
        Prefs.get(activity).edit().putBoolean(Prefs.HAPTICS, false).commit();
        CountingButton button = pressableComposerButton();

        press(button);

        assertEquals(0, button.ticks);
    }

    private CountingButton pressableComposerButton() {
        CountingButton button = new CountingButton(activity);
        UiKit.pressScale(button);
        composer.addView(button, new LinearLayout.LayoutParams(UiKit.dp(activity, 44), UiKit.dp(activity, 44)));
        ComposerActionState.apply(button, true);
        return button;
    }

    private static void press(View button) {
        long now = android.os.SystemClock.uptimeMillis();
        button.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, 1f, 1f, 0));
        button.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now + 20, android.view.MotionEvent.ACTION_UP, 1f, 1f, 0));
    }

    @Test public void stoppingDoesNotHideOrReopenTheKeyboard() {
        PendingRequestStore.Item request = startRequest(FULL_CHAT);
        PendingRequestStore.markRunning(context, request.id);
        input.requestFocus();
        View decor = activity.getWindow().getDecorView();
        int visibilityBefore = decor.getVisibility();

        control.performClick();
        OrbitRequestManager.cancelActiveForConversation(context, FULL_CHAT);

        // Nothing on the stop path asks the input method for anything at all.
        assertEquals(visibilityBefore, decor.getVisibility());
        assertTrue(input.getShowSoftInputOnFocus());
        assertTrue(input.hasFocus());
    }
}

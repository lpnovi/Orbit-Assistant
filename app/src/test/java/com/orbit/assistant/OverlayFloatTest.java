package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.service.voice.VoiceInteractionSession;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;


/** Float adapts to its window, and folds into Peek without letting go of anything it holds. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OverlayFloatTest {
    private Context context;
    private OrbitSession session;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    // ---- Responsive placement ----

    @Test public void portraitPhoneKeepsTheBottomFloatAcrossTheWidth() {
        assertEquals(0, OverlayStyle.FLOAT.cardWidthDp(412, 915));
        assertEquals(0, OverlayStyle.FLOAT.cardWidthDp(360, 780));
        // Split screen on a big display is a narrow portrait window too.
        assertEquals(0, OverlayStyle.FLOAT.cardWidthDp(400, 800));
        assertEquals(OverlayStyle.FLOAT.conversationDp, OverlayStyle.FLOAT.conversationCapDp(412, 915));
    }

    @Test public void landscapePhoneBecomesABoundedCornerCard() {
        int width = OverlayStyle.FLOAT.cardWidthDp(915, 412);
        assertTrue("landscape constrains " + width, width > 0 && width <= 420);
        assertTrue(width < 915 / 2);
    }

    @Test public void largeWindowsGetACornerCardNeverTheFullWidth() {
        for (int[] window : new int[][]{{800, 1280}, {1280, 800}, {673, 841}, {1920, 1080}}) {
            int width = OverlayStyle.FLOAT.cardWidthDp(window[0], window[1]);
            assertTrue(window[0] + "x" + window[1] + " -> " + width, width >= 420 && width <= 480);
            assertTrue(width < window[0] - 2 * OverlayStyle.CORNER_MARGIN_DP);
        }
        // A tall corner card gets more conversation before it scrolls, a phone does not.
        assertTrue(OverlayStyle.FLOAT.conversationCapDp(800, 1280) > OverlayStyle.FLOAT.conversationDp);
        assertEquals(OverlayStyle.FLOAT.conversationDp, OverlayStyle.FLOAT.conversationCapDp(915, 412));
    }

    @Test public void onlyTheCompactStylesMoveIntoTheCorner() {
        for (OverlayStyle s : new OverlayStyle[]{OverlayStyle.MODERN, OverlayStyle.CLASSIC}) {
            assertEquals(0, s.cardWidthDp(915, 412));
            assertEquals(0, s.cardWidthDp(1280, 800));
            assertFalse(s.canPeek());
        }
        assertTrue(OverlayStyle.CUTIE.cardWidthDp(1280, 800) > 0);
    }

    @Config(sdk = 35)
    @Test public void landscapeFloatSitsBottomRightAndTheKeyboardOnlyLiftsIt() {
        FrameLayout root = floatOverlay(915, 412);
        int nav = UiKit.dp(context, 48);
        apply(root, new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, nav, 0))
                .build());
        FrameLayout.LayoutParams lp = sheetParams(root);
        int width = UiKit.dp(context, OverlayStyle.FLOAT.cardWidthDp(915, 412));
        assertEquals(width, lp.width);
        assertEquals(Gravity.BOTTOM | Gravity.RIGHT, lp.gravity);
        int margin = UiKit.dp(context, OverlayStyle.CORNER_MARGIN_DP);
        assertEquals("clear of a side navigation bar", margin + nav, lp.rightMargin);
        assertEquals(margin, lp.bottomMargin);

        int ime = UiKit.dp(context, 200);
        apply(root, new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, ime))
                .setVisible(WindowInsets.Type.ime(), true)
                .build());
        lp = sheetParams(root);
        assertEquals("the keyboard never stretches it", width, lp.width);
        assertEquals("nor moves it to another corner", Gravity.BOTTOM | Gravity.RIGHT, lp.gravity);
        assertEquals(ime + margin, lp.bottomMargin);
    }

    @Config(sdk = 35)
    @Test public void portraitFloatStillSpansWithItsOwnMargins() {
        FrameLayout root = floatOverlay(412, 915);
        apply(root, new WindowInsets.Builder().build());
        FrameLayout.LayoutParams lp = sheetParams(root);
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, lp.width);
        assertEquals(Gravity.BOTTOM, lp.gravity);
        int margin = UiKit.dp(context, OverlayStyle.FLOAT.marginDp);
        assertEquals(margin, lp.leftMargin);
        assertEquals(margin, lp.rightMargin);
        assertEquals(margin, lp.bottomMargin);
    }

    // ---- The handle's gestures ----

    @Test public void theHandleReleaseKeepsDownForCloseAndUpForTheChat() {
        int threshold = 150;
        long press = 500;
        assertSame(OverlayStretch.Release.DISMISS,
                OverlayStretch.release(200f, true, 120, threshold, press, true, false));
        assertSame(OverlayStretch.Release.OPEN_CHAT,
                OverlayStretch.release(-200f, true, 120, threshold, press, true, false));
        assertSame("a tap folds a style that can peek", OverlayStretch.Release.PEEK,
                OverlayStretch.release(0f, false, 90, threshold, press, true, false));
        assertSame("and does nothing to one that cannot", OverlayStretch.Release.SETTLE,
                OverlayStretch.release(0f, false, 90, threshold, press, false, false));
        assertSame("a sloppy tap that moved is neither", OverlayStretch.Release.SETTLE,
                OverlayStretch.release(40f, true, 90, threshold, press, true, false));
        assertSame(OverlayStretch.Release.SETTLE,
                OverlayStretch.release(-40f, true, 90, threshold, press, true, false));
        assertSame("a long press is not a tap", OverlayStretch.Release.SETTLE,
                OverlayStretch.release(0f, false, 700, threshold, press, true, false));
        assertSame("a cancelled touch never acts", OverlayStretch.Release.SETTLE,
                OverlayStretch.release(300f, true, 90, threshold, press, true, true));
    }

    @Test public void tappingTheFloatHandleFoldsIntoPeek() {
        FrameLayout root = floatOverlay(412, 915);
        View handle = handle(root);
        touch(handle, 0f);
        assertTrue(session.isPeekingForTest());
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNotNull(capsule(root));
        assertEquals(View.VISIBLE, capsule(root).getVisibility());
        assertTrue("the sheet clips to the capsule", sheet(root).getClipToOutline());
    }

    @Test public void draggingTheHandleDownDoesNotFold() {
        FrameLayout root = floatOverlay(412, 915);
        // Short of the close and open thresholds, so the sheet just settles; past them the
        // classifier above sends it to Close or the chat, never to Peek.
        touch(handle(root), UiKit.dp(context, 40));
        assertFalse(session.isPeekingForTest());
        touch(handle(root), -UiKit.dp(context, 40));
        assertFalse(session.isPeekingForTest());
    }

    @Test public void peekKeepsTheSameSessionAndGivesItBack() {
        FrameLayout root = floatOverlay(412, 915);
        EditText input = input(root);
        input.setText("half a thought");
        View reply = new View(context);
        reply.setMinimumHeight(UiKit.dp(context, 60));
        LinearLayout messages = (LinearLayout) conversation(root).getChildAt(0);
        messages.addView(reply);
        int count = messages.getChildCount();
        LinearLayout sheet = sheet(root);

        handle(root).performClick();
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertTrue(session.isPeekingForTest());
        assertSame("never rebuilt", sheet, sheet(root));
        assertEquals("half a thought", input.getText().toString());
        assertEquals(count, messages.getChildCount());

        capsule(root).performClick();
        assertFalse(session.isPeekingForTest());
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertSame(sheet, sheet(root));
        assertSame(input, input(root));
        assertEquals("half a thought", input.getText().toString());
        assertEquals(count, messages.getChildCount());
        assertFalse("back to an ordinary sheet", sheet.getClipToOutline());
        assertEquals(View.GONE, capsule(root).getVisibility());
    }

    @Test public void whileFoldedOnlyTheCapsuleTakesTouches() {
        FrameLayout root = floatOverlay(412, 915);
        VoiceInteractionSession.Insets open = new VoiceInteractionSession.Insets();
        session.onComputeInsets(open);
        assertEquals(VoiceInteractionSession.Insets.TOUCHABLE_INSETS_FRAME, open.touchableInsets);

        handle(root).performClick();
        ShadowLooper.idleMainLooper(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        layout(root, 412, 915);
        VoiceInteractionSession.Insets folded = new VoiceInteractionSession.Insets();
        session.onComputeInsets(folded);
        assertEquals(VoiceInteractionSession.Insets.TOUCHABLE_INSETS_REGION, folded.touchableInsets);
    }

    @Test public void peekNeverStopsWhatIsRunning() {
        // Folding is purely visual: it must not stop a request, the microphone or speech, save
        // and hide, or rebuild the sheet. Scoped to the Peek methods themselves.
        String source = OrbitGlassChromeTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitSession.java");
        int from = source.indexOf("private void requestPeek()");
        int to = source.indexOf("private void cancelPeekMorph()");
        assertTrue(from > 0 && to > from);
        String peek = source.substring(from, to);
        for (String forbidden : new String[]{"stopListening(", "stopSpeaking(", "stopGenerating(",
                "cancelActiveForConversation(", "dismissAnimated(", "hide()", "buildSheet(",
                "renderConversation(", "OrbitRequestManager", "submit("}) {
            assertFalse(forbidden, peek.contains(forbidden));
        }
    }

    @Test public void modernAndClassicNeverFold() {
        for (String style : new String[]{Prefs.OVERLAY_STYLE_MODERN, Prefs.OVERLAY_STYLE_CLASSIC}) {
            FrameLayout root = overlay(style, 412, 915);
            View handle = handle(root);
            assertFalse(style, handle.hasOnClickListeners());
            assertFalse(style, handle.getContentDescription().toString().contains("minimize"));
            touch(handle, 0f);
            assertFalse(style, session.isPeekingForTest());
            assertNull(style, capsule(root));
        }
    }

    @Test public void cutieFoldsLikeFloat() {
        Prefs.get(context).edit().putBoolean(Prefs.LELO_MODE, true).commit();
        FrameLayout root = overlay(Prefs.OVERLAY_STYLE_CUTIE, 412, 915);
        handle(root).performClick();
        assertTrue(session.isPeekingForTest());
    }

    // ---- Helpers ----

    private FrameLayout floatOverlay(int widthDp, int heightDp) {
        return overlay(Prefs.OVERLAY_STYLE_FLOAT, widthDp, heightDp);
    }

    private FrameLayout overlay(String style, int widthDp, int heightDp) {
        Prefs.setOverlayStyle(context, style);
        OrbitSessionService service = Robolectric.buildService(OrbitSessionService.class).create().get();
        session = (OrbitSession) service.onNewSession(null);
        FrameLayout root = (FrameLayout) session.onCreateContentView();
        layout(root, widthDp, heightDp);
        return root;
    }

    private void layout(View root, int widthDp, int heightDp) {
        root.measure(View.MeasureSpec.makeMeasureSpec(UiKit.dp(context, widthDp), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(UiKit.dp(context, heightDp), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private void apply(FrameLayout root, WindowInsets insets) {
        root.dispatchApplyWindowInsets(insets);
    }

    private void touch(View handle, float dy) {
        long t = android.os.SystemClock.uptimeMillis();
        float y = 500f;
        handle.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 10f, y, 0));
        if (dy != 0f) {
            handle.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_MOVE, 10f, y + dy / 2, 0));
            handle.dispatchTouchEvent(MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_MOVE, 10f, y + dy, 0));
        }
        handle.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, 10f, y + dy, 0));
    }

    private static LinearLayout sheet(FrameLayout root) {
        return (LinearLayout) ((View) conversation(root).getParent());
    }

    private static FrameLayout.LayoutParams sheetParams(FrameLayout root) {
        return (FrameLayout.LayoutParams) sheet(root).getLayoutParams();
    }

    private static View handle(View root) {
        return findStarting(root, "Tap to minimize Orbit", "Swipe up to open this chat");
    }

    private static View capsule(View root) {
        View found = findEnding(root, "Tap to expand.");
        return found;
    }

    private static View findStarting(View view, String... prefixes) {
        CharSequence d = view.getContentDescription();
        if (d != null) for (String p : prefixes) if (d.toString().startsWith(p)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findStarting(g.getChildAt(i), prefixes);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findEnding(View view, String suffix) {
        CharSequence d = view.getContentDescription();
        if (d != null && d.toString().endsWith(suffix)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findEnding(g.getChildAt(i), suffix);
                if (found != null) return found;
            }
        }
        return null;
    }

    static ScrollView conversation(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                ScrollView found = conversation(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static EditText input(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                EditText found = input(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}

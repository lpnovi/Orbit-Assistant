package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.service.voice.VoiceInteractionSession;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowValueAnimator;

import java.util.concurrent.TimeUnit;

/** Orbit Dock: an opt-in arrangement of the same overlay, plus its once-only completion pulse. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OrbitDockTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @After public void restoreAnimations() {
        setDurationScale(1f);
    }

    // ---- Choosing it ----

    @Test public void dockIsOptInAndStaysChosen() throws Exception {
        assertSame("never the default", OverlayStyle.MODERN, OverlayStyle.current(context));

        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_DOCK);
        assertSame(OverlayStyle.DOCK, OverlayStyle.current(context));
        assertTrue(OverlayStyle.choices(context).contains(OverlayStyle.DOCK));

        JSONObject backup = Prefs.backupSnapshot(context);
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, backup));
        assertSame(OverlayStyle.DOCK, OverlayStyle.current(context));
    }

    @Test public void dockNeverDimsAndIsOnlyAStyle() {
        OverlayStyle s = OverlayStyle.DOCK;
        assertTrue(s.dock());
        assertEquals("no dim behind it", 0, s.scrimAlpha);
        assertTrue("subtly see-through, still readable", s.sheetAlpha < 255 && s.sheetAlpha >= 230);
        assertFalse("not a Peek style", s.canPeek());
        for (OverlayStyle other : new OverlayStyle[]{OverlayStyle.MODERN, OverlayStyle.FLOAT,
                OverlayStyle.CLASSIC, OverlayStyle.CUTIE}) {
            assertFalse(other.label, other.dock());
        }
    }

    @Test public void thePulseCanBeTurnedOffAndTravelsWithABackup() throws Exception {
        assertTrue("on unless turned off", Prefs.dockCompletionPulse(context));
        Prefs.get(context).edit().putBoolean(Prefs.DOCK_COMPLETION_PULSE, false).commit();
        JSONObject backup = Prefs.backupSnapshot(context);
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, backup));
        assertFalse(Prefs.dockCompletionPulse(context));
    }

    @Test public void searchFindsDockAndItsPulse() {
        assertEquals("Overlay style", SettingsSearchIndex.search("dock").get(0).title);
        assertEquals("Completion pulse", SettingsSearchIndex.search("completion pulse").get(0).title);
    }

    // ---- The overlay ----

    @Test public void itOpensAsTheBarAloneWithNothingBehindIt() {
        OrbitSession session = dock();
        ViewGroup root = (ViewGroup) session.onCreateContentView();
        assertFalse("collapsed on open", session.isDockExpandedForTest());
        View scrim = root.getChildAt(0);
        assertFalse("tapping beside Dock never closes it", scrim.isClickable());
        assertNotNull(find(root, "Show conversation. Swipe down to close Orbit."));
        // The same controls as every other style, all still there.
        for (String control : new String[]{"Close Orbit", "Attach to message", "Voice input",
                "Send message", "Attach current screen", "Recent chats", "New chat"}) {
            assertNotNull(control, find(root, control));
        }
        assertTrue(editor(root) != null);
    }

    @Test public void onlyDocksOwnPiecesTakeTouches() {
        OrbitSession session = dock();
        ViewGroup root = (ViewGroup) session.onCreateContentView();
        attach(root);
        java.util.List<android.graphics.Rect> pieces = session.dockTouchRects();
        assertFalse(pieces.isEmpty());
        View send = find(root, "Send message");
        int[] at = new int[2];
        send.getLocationInWindow(at);
        int x = at[0] + send.getWidth() / 2, y = at[1] + send.getHeight() / 2;
        boolean sendTouchable = false;
        for (android.graphics.Rect piece : pieces) {
            assertFalse("the app above Dock keeps its touches", piece.contains(root.getWidth() / 2, 4));
            sendTouchable |= piece.contains(x, y);
        }
        assertTrue("Send is touchable", sendTouchable);
    }

    @Test public void otherStylesStillTakeTheWholeWindow() {
        for (String style : new String[]{Prefs.OVERLAY_STYLE_MODERN, Prefs.OVERLAY_STYLE_FLOAT,
                Prefs.OVERLAY_STYLE_CLASSIC}) {
            Prefs.setOverlayStyle(context, style);
            OrbitSession session = newSession();
            ViewGroup root = (ViewGroup) session.onCreateContentView();
            attach(root);
            assertTrue(style, session.dockTouchRects().isEmpty());
            VoiceInteractionSession.Insets insets = new VoiceInteractionSession.Insets();
            session.onComputeInsets(insets);
            assertFalse(style, insets.touchableInsets
                    == VoiceInteractionSession.Insets.TOUCHABLE_INSETS_REGION);
            assertTrue(style, root.getChildAt(0).isClickable());
        }
    }

    @Test public void theHandleOpensThePanelAndBackFoldsItBeforeClosing() {
        OrbitSession session = dock();
        ViewGroup root = (ViewGroup) session.onCreateContentView();
        find(root, "Show conversation. Swipe down to close Orbit.").performClick();
        assertTrue(session.isDockExpandedForTest());
        assertNotNull(find(root, "Hide conversation. Drag up to make it taller."));

        session.onBackPressed();
        assertFalse("Back folds the conversation first", session.isDockExpandedForTest());
    }

    // ---- The pulse ----

    @Test public void thePulsePlaysOncePerAnswerAndOnlyWhenWanted() {
        assertTrue(CompletionPulse.shouldPlay(true, "r1", ""));
        assertFalse("never twice for one answer", CompletionPulse.shouldPlay(true, "r1", "r1"));
        assertTrue(CompletionPulse.shouldPlay(true, "r2", "r1"));
        assertFalse("switched off", CompletionPulse.shouldPlay(false, "r1", ""));
        assertFalse(CompletionPulse.shouldPlay(true, null, ""));
        assertFalse(CompletionPulse.shouldPlay(true, "", ""));
    }

    @Test public void thePulseIsTwoSoftBeatsThatEndAtRest() {
        assertTrue(CompletionPulse.DURATION_MS >= 700 && CompletionPulse.DURATION_MS <= 1200);
        assertTrue(CompletionPulse.SWELL >= 0.03f && CompletionPulse.SWELL <= 0.05f);
        assertEquals(0f, CompletionPulse.envelope(0f), 0f);
        assertEquals(0f, CompletionPulse.envelope(1f), 0f);
        assertEquals(0f, CompletionPulse.envelope(0.55f), 1e-4f);
        float first = 0f, second = 0f;
        for (int i = 0; i <= 1000; i++) {
            float t = i / 1000f;
            float e = CompletionPulse.envelope(t);
            assertTrue(e >= 0f && e <= 1f);
            if (t < 0.55f) first = Math.max(first, e); else second = Math.max(second, e);
        }
        assertEquals(1f, first, 0.01f);
        assertTrue("the second beat is softer", second < first * 0.5f && second > 0f);
    }

    @Test public void thePulseLeavesItsViewExactlyAsItFoundIt() {
        View bubble = new View(context);
        bubble.setElevation(4f);
        CompletionPulse.play(bubble, 0xFF7C4DFF, 20f, CompletionPulse.SWELL);
        assertNotNull("glowing", bubble.getForeground());
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
        assertNull(bubble.getForeground());
        assertEquals(1f, bubble.getScaleX(), 0f);
        assertEquals(1f, bubble.getScaleY(), 0f);
        assertEquals(4f, bubble.getElevation(), 0f);
    }

    @Test public void thePulseStaysStillWhenAnimationsAreOff() {
        setDurationScale(0f);
        View bubble = new View(context);
        CompletionPulse.play(bubble, 0xFF7C4DFF, 20f, CompletionPulse.SWELL);
        assertNull(bubble.getForeground());
        assertEquals(1f, bubble.getScaleX(), 0f);
    }

    // ---- The preview's words ----

    @Test public void thePreviewShowsPlainWordsAndFollowsALongAnswer() {
        assertEquals("Hello there and welcome", DockText.opening("## Hello **there**\n\n- and welcome"));
        StringBuilder longAnswer = new StringBuilder();
        for (int i = 0; i < 200; i++) longAnswer.append("word").append(i).append(' ');
        String tail = DockText.tail(longAnswer.toString());
        assertTrue(tail.startsWith(DockText.ELLIPSIS));
        assertTrue(tail.length() <= DockText.TAIL_CHARS + 1);
        assertTrue("the newest words", tail.endsWith("word199"));
        assertEquals("Short", DockText.tail("Short"));
    }

    // ---- Helpers ----

    private static void setDurationScale(float scale) {
        try {
            java.lang.reflect.Method set =
                    ShadowValueAnimator.class.getDeclaredMethod("setDurationScale", float.class);
            set.setAccessible(true);
            set.invoke(null, scale);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private OrbitSession dock() {
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_DOCK);
        return newSession();
    }

    private OrbitSession newSession() {
        OrbitSessionService service = Robolectric.buildService(OrbitSessionService.class).create().get();
        return (OrbitSession) service.onNewSession(null);
    }

    /** Puts the overlay in a real window, so locations are the ones Android would report. */
    private static void attach(View root) {
        android.app.Activity host = Robolectric.buildActivity(android.app.Activity.class).setup().get();
        host.setContentView(root);
        ShadowLooper.idleMainLooper();
    }

    private static EditText editor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = editor(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View find(View view, String description) {
        if (view.getContentDescription() != null
                && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}

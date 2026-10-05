package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/** Overlay styles change how the Side-button sheet looks, never which controls it has. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OverlayStyleTest {
    private static final String[] STYLES = {
            Prefs.OVERLAY_STYLE_MODERN, Prefs.OVERLAY_STYLE_FLOAT, Prefs.OVERLAY_STYLE_CLASSIC};
    /** Present as their own buttons in every style. */
    private static final String[] EVERY_CONTROL = {
            "Close Orbit", "Attach to message", "Voice input", "Send message",
            "Attach current screen", "Select or mark part of the current screen"};

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void modernIsTheDefaultAndUnknownValuesFallBackToIt() {
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));

        Prefs.setOverlayStyle(context, "gemini");
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));
        Prefs.setOverlayStyle(context, "");
        assertSame(OverlayStyle.MODERN, OverlayStyle.current(context));

        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_FLOAT);
        assertSame(OverlayStyle.FLOAT, OverlayStyle.current(context));
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_CLASSIC);
        assertSame(OverlayStyle.CLASSIC, OverlayStyle.current(context));
    }

    @Test public void beta1FloatBecomesModernExactlyOnce() {
        // What 0.8.4.0-beta.1 stored for its Float, which is the design now called Modern.
        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, "float").commit();
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));
        assertEquals(Prefs.OVERLAY_STYLE_MODERN,
                Prefs.get(context).getString(Prefs.OVERLAY_STYLE, null));

        // Choosing the new Float afterwards sticks, read after read.
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_FLOAT);
        assertEquals(Prefs.OVERLAY_STYLE_FLOAT, Prefs.overlayStyle(context));
        assertEquals(Prefs.OVERLAY_STYLE_FLOAT, Prefs.overlayStyle(context));
        assertSame(OverlayStyle.FLOAT, OverlayStyle.current(context));
    }

    @Test public void beta1ClassicAndUnsetStayWhatTheyMeant() {
        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, "classic").commit();
        assertEquals(Prefs.OVERLAY_STYLE_CLASSIC, Prefs.overlayStyle(context));

        Prefs.get(context).edit().clear().commit();
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));
        // Resolving the default stores nothing, so the default can still change later.
        assertFalse(Prefs.get(context).contains(Prefs.OVERLAY_STYLE));
    }

    @Test public void backupsKeepTheMeaningTheyWereMadeWith() throws Exception {
        // A beta.1 backup knows nothing of the migration, so its "float" is Modern.
        JSONObject beta1 = new JSONObject().put(Prefs.OVERLAY_STYLE, "float");
        assertTrue(Prefs.restoreBackupSnapshot(context, beta1));
        assertEquals(Prefs.OVERLAY_STYLE_MODERN, Prefs.overlayStyle(context));

        // A backup made after choosing the new Float restores the new Float.
        Prefs.setOverlayStyle(context, Prefs.OVERLAY_STYLE_FLOAT);
        JSONObject beta2 = Prefs.backupSnapshot(context);
        Prefs.get(context).edit().clear().commit();
        assertTrue(Prefs.restoreBackupSnapshot(context, beta2));
        assertEquals(Prefs.OVERLAY_STYLE_FLOAT, Prefs.overlayStyle(context));
    }

    @Test public void classicKeepsTheGeometryItShippedWith() {
        OverlayStyle s = OverlayStyle.CLASSIC;
        assertEquals(OverlayStretch.SHEET_CORNER_DP, s.cornerDp, 0f);
        assertEquals(78, s.scrimAlpha);
        assertEquals(8, s.marginDp);
        assertEquals(18, s.sidePaddingDp);
        assertEquals(40, s.iconDp);
        assertEquals(240, s.conversationDp);
        assertFalse(s.fitsContent());
        assertFalse(s.integratedComposer);
        assertFalse(s.compact);
        assertEquals(OverlayStretch.cornerRadiusDp(0.5f),
                OverlayStretch.cornerRadiusDp(0.5f, s.cornerDp), 0f);
    }

    @Test public void modernIsBeta1sFloatDesign() {
        OverlayStyle s = OverlayStyle.MODERN;
        assertEquals(44, s.scrimAlpha);
        assertEquals(12, s.marginDp);
        assertEquals(32f, s.cornerDp, 0f);
        assertEquals(14, s.sidePaddingDp);
        assertEquals(28, s.markDp);
        assertEquals(15f, s.titleSp, 0f);
        assertEquals(36, s.iconDp);
        assertEquals(16f, s.controlRadiusDp, 0f);
        assertEquals(26f, s.composerRadiusDp, 0f);
        assertTrue(s.integratedComposer);
        assertFalse(s.compact);
        assertTrue(s.fitsContent());
    }

    @Test public void everyStyleBuildsEveryControl() {
        for (String style : STYLES) {
            View root = overlay(style);
            for (String control : EVERY_CONTROL) {
                assertNotNull(style + " lost " + control, find(root, control));
            }
            assertNotNull(style + " lost the AI selector", modeChip(root));
            if (Prefs.OVERLAY_STYLE_FLOAT.equals(style)) continue;
            assertNotNull(style + " lost Recent chats", find(root, "Recent chats"));
            assertNotNull(style + " lost New chat", find(root, "New chat"));
            assertNull(style + " grew a More menu", find(root, "More options"));
        }
    }

    @Test public void floatReachesHistoryAndNewChatThroughItsMoreMenu() {
        View root = overlay(Prefs.OVERLAY_STYLE_FLOAT);
        assertNull(find(root, "Recent chats"));
        assertNull(find(root, "New chat"));
        View more = find(root, "More options");
        assertNotNull(more);
        assertTrue("More is a full touch target", more.getLayoutParams().height >= UiKit.dp(context, 36));

        more.performClick();
        View menu = latestPopup();
        assertNotNull(textView(menu, "New chat"));
        ((View) textView(menu, "Recent chats").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertNotNull("Recent chats opened the history list", textView(root, "No saved chats yet."));

        more.performClick();
        ((View) textView(latestPopup(), "New chat").getParent()).performClick();
        ShadowLooper.idleMainLooper();
        assertNull("New chat left history", textView(root, "No saved chats yet."));
    }

    @Test public void modernAndFloatMoveTheScreenControlsIntoTheComposer() {
        View classic = overlay(Prefs.OVERLAY_STYLE_CLASSIC);
        assertNotSame(composerOf(classic, "Voice input"), composerOf(classic, "Attach current screen"));

        for (String style : new String[]{Prefs.OVERLAY_STYLE_MODERN, Prefs.OVERLAY_STYLE_FLOAT}) {
            View root = overlay(style);
            assertSame(style, composerOf(root, "Voice input"), composerOf(root, "Attach current screen"));
            assertSame(style, composerOf(root, "Voice input"), composerOf(root, "Select or mark part of the current screen"));
        }
    }

    @Test public void classicKeepsItsFixedConversationAndReservedRows() {
        View root = overlay(Prefs.OVERLAY_STYLE_CLASSIC);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) conversation(root).getLayoutParams();
        assertEquals(UiKit.dp(context, 240), lp.height);
        assertEquals(0f, lp.weight, 0f);
        measure(root);
        assertEquals(UiKit.dp(context, 240), conversation(root).getMeasuredHeight());
        assertNotNull(textView(root, "What can I help with?"));
        assertTrue(suggestions(root).getVisibility() != View.GONE);
    }

    @Test public void emptyConversationsShrinkByStyle() {
        int classic = emptyConversationHeight(Prefs.OVERLAY_STYLE_CLASSIC);
        int modern = emptyConversationHeight(Prefs.OVERLAY_STYLE_MODERN);
        int floating = emptyConversationHeight(Prefs.OVERLAY_STYLE_FLOAT);

        // Modern no longer reserves beta.1's 220dp blank middle, but keeps a fuller feel.
        assertTrue("modern " + modern, modern < UiKit.dp(context, 220));
        assertTrue("modern " + modern, modern >= UiKit.dp(context, 120));
        // Float's empty conversation is a sliver, a fraction of Modern's.
        assertTrue("float " + floating + " modern " + modern, floating * 3 <= modern);
        assertTrue(modern < classic);

        int modernSheet = sheetHeight(Prefs.OVERLAY_STYLE_MODERN);
        int floatSheet = sheetHeight(Prefs.OVERLAY_STYLE_FLOAT);
        assertTrue("float sheet " + floatSheet + " modern " + modernSheet,
                modernSheet - floatSheet >= UiKit.dp(context, 120));
    }

    @Test public void floatHidesWhatAnEmptyChatDoesNotNeed() {
        View root = overlay(Prefs.OVERLAY_STYLE_FLOAT);
        assertNull("no greeting bubble", textView(root, "What can I help with?"));
        assertEquals("What can I help with?", composerInput(root).getHint().toString());
        assertEquals(View.GONE, suggestions(root).getVisibility());

        View modern = overlay(Prefs.OVERLAY_STYLE_MODERN);
        assertNotNull(textView(modern, "What can I help with?"));
    }

    @Test public void floatGrowsWithItsConversationUpToACap() {
        View root = overlay(Prefs.OVERLAY_STYLE_FLOAT);
        int empty = measuredConversation(root);

        View reply = new View(context);
        reply.setMinimumHeight(UiKit.dp(context, 90));
        messages(root).addView(reply);
        int oneReply = measuredConversation(root);
        assertTrue("grew " + empty + " -> " + oneReply, oneReply >= empty + UiKit.dp(context, 90));

        View longReply = new View(context);
        longReply.setMinimumHeight(UiKit.dp(context, 900));
        messages(root).addView(longReply);
        assertEquals("long answers scroll inside the cap",
                UiKit.dp(context, OverlayStyle.FLOAT.conversationDp), measuredConversation(root));
        assertTrue(OverlayStyle.FLOAT.conversationDp < OverlayStyle.MODERN.conversationDp);
    }

    @Test public void stretchStillTracksTheFingerInEveryStyle() {
        for (String style : STYLES) {
            OverlayStyle s = OverlayStyle.of(style);
            int base = emptyConversationHeight(style);
            assertTrue(style + " has a base to stretch from", base > 0);
            int max = OverlayStretch.maxConversationHeight(UiKit.dp(context, 900),
                    UiKit.dp(context, 300), 0, base);
            assertEquals(style, base + 100, OverlayStretch.stretchedHeight(base, max, -100f));
            assertEquals(style, base, OverlayStretch.stretchedHeight(base, max, 40f));
            assertEquals(s.cornerDp, OverlayStretch.cornerRadiusDp(0f, s.cornerDp), 0f);
            assertTrue(OverlayStretch.cornerRadiusDp(1f, s.cornerDp) < s.cornerDp);
        }
    }

    private View overlay(String style) {
        Prefs.setOverlayStyle(context, style);
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        OrbitSession session = (OrbitSession) service.onNewSession(null);
        return session.onCreateContentView();
    }

    private int emptyConversationHeight(String style) {
        return measuredConversation(overlay(style));
    }

    private int sheetHeight(String style) {
        View root = overlay(style);
        measure(root);
        return ((View) conversation(root).getParent()).getMeasuredHeight();
    }

    private int measuredConversation(View root) {
        measure(root);
        return conversation(root).getMeasuredHeight();
    }

    private void measure(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(UiKit.dp(context, 412), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(UiKit.dp(context, 900), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
    }

    private static PopupWindow popup() {
        return shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow();
    }

    private static View latestPopup() {
        PopupWindow popup = popup();
        assertNotNull("menu opened", popup);
        return popup.getContentView();
    }

    private static ScrollView conversation(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ScrollView found = conversation(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static LinearLayout messages(View root) {
        return (LinearLayout) conversation(root).getChildAt(0);
    }

    private static HorizontalScrollView suggestions(View view) {
        if (view instanceof HorizontalScrollView) return (HorizontalScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                HorizontalScrollView found = suggestions(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static android.widget.EditText composerInput(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = composerInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** The header's AI chip: the one clickable TextView beside the title. */
    private static View modeChip(View root) {
        View voice = find(root, "Close Orbit");
        ViewGroup header = (ViewGroup) voice.getParent();
        for (int i = 0; i < header.getChildCount(); i++) {
            View child = header.getChildAt(i);
            if (child instanceof TextView && child.hasOnClickListeners()) return child;
        }
        return null;
    }

    /** The outlined card a control sits in: the nearest ancestor that is a direct child of the sheet. */
    private static View composerOf(View root, String description) {
        View view = find(root, description);
        while (view.getParent() instanceof View
                && ((View) view.getParent()).getParent() != null
                && !(((View) view.getParent()).getParent() instanceof android.widget.FrameLayout)) {
            view = (View) view.getParent();
        }
        return view;
    }

    private static TextView textView(View view, String text) {
        if (view instanceof TextView && !(view instanceof android.widget.EditText)
                && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textView(group.getChildAt(i), text);
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

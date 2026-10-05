package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Overlay styles change how the Side-button sheet looks, never which controls it has. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class OverlayStyleTest {
    private static final String[] EVERY_CONTROL = {
            "Recent chats", "New chat", "Close Orbit", "Attach to message", "Voice input",
            "Send message", "Attach current screen",
            "Select or mark part of the current screen"};

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    @Test public void classicIsTheDefaultAndUnknownValuesFallBackToIt() {
        assertEquals(Prefs.OVERLAY_STYLE_CLASSIC, Prefs.overlayStyle(context));
        assertSame(OverlayStyle.CLASSIC, OverlayStyle.current(context));

        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, "gemini").commit();
        assertSame(OverlayStyle.CLASSIC, OverlayStyle.current(context));

        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, Prefs.OVERLAY_STYLE_FLOAT).commit();
        assertSame(OverlayStyle.FLOAT, OverlayStyle.current(context));
    }

    @Test public void classicKeepsTheGeometryItShippedWith() {
        assertEquals(OverlayStretch.SHEET_CORNER_DP, OverlayStyle.CLASSIC.cornerDp, 0f);
        assertEquals(8, OverlayStyle.CLASSIC.marginDp);
        assertEquals(240, OverlayStyle.CLASSIC.conversationDp);
        assertEquals(OverlayStretch.cornerRadiusDp(0.5f),
                OverlayStretch.cornerRadiusDp(0.5f, OverlayStyle.CLASSIC.cornerDp), 0f);
    }

    @Test public void bothStylesBuildEveryControl() {
        for (String style : new String[]{Prefs.OVERLAY_STYLE_CLASSIC, Prefs.OVERLAY_STYLE_FLOAT}) {
            View root = overlay(style);
            for (String control : EVERY_CONTROL) {
                assertNotNull(style + " lost " + control, find(root, control));
            }
        }
    }

    @Test public void floatMovesTheScreenControlsIntoTheComposer() {
        View classic = overlay(Prefs.OVERLAY_STYLE_CLASSIC);
        assertNotSame(composerOf(classic, "Voice input"), composerOf(classic, "Attach current screen"));

        View floating = overlay(Prefs.OVERLAY_STYLE_FLOAT);
        assertSame(composerOf(floating, "Voice input"), composerOf(floating, "Attach current screen"));
    }

    private View overlay(String style) {
        Prefs.get(context).edit().putString(Prefs.OVERLAY_STYLE, style).commit();
        OrbitSessionService service =
                Robolectric.buildService(OrbitSessionService.class).create().get();
        OrbitSession session = (OrbitSession) service.onNewSession(null);
        return session.onCreateContentView();
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

package com.orbit.assistant;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class DiagnosticsTypographyTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        TestWorkManager.ensureInitialized(context);
    }

    @Test public void defaultHeadingsStayIdentical() { check("orbit_default", 1f); }
    @Test public void timesNewRomanHeadingsStayIdentical() { check("times_new_roman", 1f); }
    @Test public void lightHeadingsStayIdentical() { check("light", 1f); }
    @Test public void condensedHeadingsStayIdentical() { check("condensed", 1f); }
    @Test public void casualHeadingsStayIdentical() { check("casual", 1f); }
    @Test public void monospaceHeadingsStayIdentical() { check("monospace", 1f); }
    @Test public void largeTextHeadingsStayIdentical() { check("light", 1.6f); }

    private void check(String font, float scale) {
        Prefs.get(context).edit().putString(Prefs.APP_FONT, font).commit();
        RuntimeEnvironment.setFontScale(scale);
        ActivityController<DiagnosticsActivity> controller =
                Robolectric.buildActivity(DiagnosticsActivity.class).setup();
        try {
            View root = controller.get().getWindow().getDecorView();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            Map<String, State> before = headings(root);
            assertEquals(13, before.size());
            TextView preview = UiKit.text(context, "Theme Studio font sample", 15, UiKit.TEXT, false);
            UiKit.applyFontPreview(preview, "times_new_roman", Typeface.NORMAL);
            State previewBefore = new State(preview);
            for (String title : new String[]{"Rich Answers", "Rich Answers", "Memory"}) {
                ((View) find(root, title).getParent()).performClick();
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                assertHeadings(before, root);
            }
            find(root, "Collapse all").performClick();
            assertHeadings(before, root);
            find(root, "Expand all").performClick();
            assertHeadings(before, root);
            UiKit.applyTypography(preview);
            previewBefore.assertUnchanged(preview);
        } finally {
            controller.pause().stop().destroy();
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private static void assertHeadings(Map<String, State> before, View root) {
        for (Map.Entry<String, State> entry : before.entrySet()) {
            entry.getValue().assertUnchanged(find(root, entry.getKey()));
        }
    }

    private static Map<String, State> headings(View root) {
        Map<String, State> out = new LinkedHashMap<>();
        for (String title : new String[]{"Request flow", "Thinking updates", "Auto routing",
                "Screen & app context", "Memory", "Calendar", "Rich Answers", "Orbit Local",
                "Actions & utilities", "Routines", "Orbit Deck", "Gestures", "Advanced"}) {
            out.put(title, new State(find(root, title)));
        }
        return out;
    }

    private static TextView find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = find(group.getChildAt(i), text);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static class State {
        final TextView view;
        final Typeface face;
        final int style, weight, color;
        final float size, spacing, scale;
        final boolean fakeBold;
        State(TextView text) {
            assertNotNull(text);
            view = text; face = text.getTypeface(); style = face.getStyle(); weight = face.getWeight();
            color = text.getCurrentTextColor(); size = text.getTextSize();
            spacing = text.getLetterSpacing(); scale = text.getTextScaleX();
            fakeBold = text.getPaint().isFakeBoldText();
        }
        void assertUnchanged(TextView text) {
            assertNotNull(text);
            assertSame("disclosure must retain the first-render heading", view, text);
            assertEquals(view.getText().toString(), face, text.getTypeface());
            assertEquals(style, text.getTypeface().getStyle());
            assertEquals(weight, text.getTypeface().getWeight());
            assertEquals(color, text.getCurrentTextColor());
            assertEquals(size, text.getTextSize(), 0f);
            assertEquals(spacing, text.getLetterSpacing(), 0f);
            assertEquals(scale, text.getTextScaleX(), 0f);
            assertEquals(fakeBold, text.getPaint().isFakeBoldText());
        }
    }
}

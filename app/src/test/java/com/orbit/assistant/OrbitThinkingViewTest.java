package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Lifecycle behavior of the Orbit thinking indicator. These cover the states that would otherwise
 * leave an animation running behind a finished response, not the appearance of the animation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class OrbitThinkingViewTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
    }

    private static void draw(View view, int size) {
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size),
                Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        bitmap.recycle();
    }

    @Test public void startsAndStopsOnDemand() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        assertFalse(view.isRunning());

        view.start();
        assertTrue(view.isRunning());

        view.stop();
        assertFalse(view.isRunning());
    }

    @Test public void startingTwiceKeepsASingleRunningState() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.start();
        view.start();
        assertTrue(view.isRunning());

        view.stop();
        assertFalse(view.isRunning());
    }

    @Test public void stoppingWithoutStartingIsSafe() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.stop();
        view.settle();
        assertFalse(view.isRunning());
    }

    @Test public void settlingResolvesToStoppedAndDrawsNothingAfterwards() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.start();
        view.settle();
        assertTrue("settle must not stop the view instantly while animating", view.isRunning());

        // The collapse is time-based; once it has elapsed the next frame retires the view so no
        // indicator can keep drawing behind a completed answer.
        Robolectric.getForegroundThreadScheduler().advanceBy(400, java.util.concurrent.TimeUnit.MILLISECONDS);
        draw(view, 60);
        assertFalse(view.isRunning());
    }

    @Test public void detachingFromTheWindowStopsIt() {
        ActivityController<android.app.Activity> controller =
                Robolectric.buildActivity(android.app.Activity.class).setup();
        android.app.Activity activity = controller.get();
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);

        OrbitThinkingView view = new OrbitThinkingView(activity);
        host.addView(view, 60, 60);
        view.start();
        assertTrue(view.isRunning());

        // Whatever removes the indicator - a rebuild, a dismissed overlay, a destroyed screen -
        // ends its frames.
        host.removeView(view);
        assertFalse(view.isRunning());

        controller.pause().stop().destroy();
    }

    @Test public void drawingWhileStoppedIsANoOp() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        draw(view, 60);
        assertFalse(view.isRunning());
    }

    @Test public void drawsWithoutCrashingAtSmallAndLargeSizes() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.start();
        draw(view, 1);
        draw(view, 26);
        draw(view, 30);
        draw(view, 240);
        assertTrue(view.isRunning());
        view.stop();
    }

    @Test public void measuresToACompactDefaultWhenUnconstrained() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        assertEquals(UiKit.dp(context, 30), view.getMeasuredWidth());
        assertEquals(UiKit.dp(context, 30), view.getMeasuredHeight());
    }

    @Test public void followsTheCurrentAccentRatherThanAFixedColour() {
        Prefs.get(context).edit().putString(Prefs.ACCENT, "mint").commit();
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.start();
        draw(view, 60);

        // Changing accent and re-reading must track the appearance system, not a baked-in colour.
        Prefs.get(context).edit().putString(Prefs.ACCENT, "rose").commit();
        view.applyAccent();
        draw(view, 60);

        assertEquals(UiKit.accentForName(context, "rose"), UiKit.accent(context));
        assertTrue(view.isRunning());
        view.stop();
    }

    @Test public void settleStopsImmediatelyWhenAnimationsAreDisabled() {
        OrbitThinkingView view = new OrbitThinkingView(context);
        view.start();
        boolean animationsOn = UiKit.animationsEnabled();
        view.settle();
        if (animationsOn) {
            assertTrue(view.isRunning());
        } else {
            // With system animations off the state resolves at once rather than relying on frames.
            assertFalse(view.isRunning());
        }
        view.stop();
        assertFalse(view.isRunning());
    }
}

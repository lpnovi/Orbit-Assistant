package com.orbit.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * The hidden Orbit Launch Sequence.
 *
 * <p>Reached by holding the Orbit mark on Chats for a moment, and by nothing else. It is
 * deliberately not a Settings switch: an easter egg with a control panel is a feature.
 *
 * <h2>What it is not allowed to do</h2>
 *
 * <p>No provider, no network, no conversation, no Diagnostics, no persistence, and no effect on any
 * Orbit behaviour whatsoever. It draws a scene and it closes. The one thing it stores is a counter
 * in memory that dies with the Activity.
 *
 * <p>Its Back is deliberately {@link OrbitNavigation.Policy#LOCAL}: this is a full-screen scene laid
 * over Chats rather than a page in Orbit's hierarchy, so Back closes it immediately instead of being
 * animated as a page transition. That also means it can never contend with the app-wide predictive
 * gesture, which is not installed here at all.
 *
 * <h2>Screen readers</h2>
 *
 * <p>The scene is one decorative view, and a decorative view must not become a trap. There is an
 * always-present, focusable Close control with a real label, the scene itself announces what it is
 * and how to leave, and Back works normally — so TalkBack has at least three ways out and needs
 * none of them to be the drawing.
 */
public final class OrbitLaunchSequenceActivity extends Activity {

    private OrbitLaunchSequenceView scene;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        getWindow().setStatusBarColor(UiKit.BG);
        getWindow().setNavigationBarColor(UiKit.BG);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(UiKit.BG);

        scene = new OrbitLaunchSequenceView(this);
        root.addView(scene, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A real, focusable way out that does not depend on the gesture, the drawing, or on being
        // able to see anything at all.
        TextView close = UiKit.text(this, "Close", 14, UiKit.MUTED, true);
        close.setContentDescription("Close the Orbit launch sequence");
        close.setFocusable(true);
        close.setClickable(true);
        close.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 10),
                UiKit.dp(this, 18), UiKit.dp(this, 10));
        close.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.accent(this), 99, this));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.TOP | Gravity.END;
        closeLp.setMargins(0, UiKit.dp(this, 14), UiKit.dp(this, 14), 0);
        root.addView(close, closeLp);

        setContentView(root);
        UiKit.applyActivityInsets(this, root, false);
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
        // Accent and the platform's motion setting can both have changed while this was away.
        scene.refreshTheme();
        scene.start();
    }

    @Override protected void onPause() {
        // The frame loop ends with the screen the user can see. Nothing keeps running behind it.
        scene.stop();
        UiPresence.leave(this);
        super.onPause();
    }

    @Override protected void onDestroy() {
        scene.stop();
        super.onDestroy();
    }

    /** The scene, for tests. */
    OrbitLaunchSequenceView sceneForTest() { return scene; }
}

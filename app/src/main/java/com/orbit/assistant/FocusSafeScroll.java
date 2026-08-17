package com.orbit.assistant;

import android.widget.ScrollView;
import android.view.View;

/**
 * Automatic message scrolling that moves pixels and nothing else.
 *
 * <p>Both surfaces previously auto-scrolled with {@code fullScroll(View.FOCUS_DOWN)}. Despite the
 * name, that is a focus-navigation call: {@link ScrollView#fullScroll} looks for a focusable view
 * in the direction it scrolls and gives it focus. Orbit appends focusable controls — Copy,
 * Regenerate, source links, memory controls, action cards — immediately before scrolling to the
 * newest message, so the scroll handed focus to one of them and took it away from the composer.
 *
 * <p>A physical trace on a Samsung device showed exactly that: the editor holding focus at
 * {@code response.rendered}, then losing it 39 ms later, matching the 40 ms delayed scroll, and
 * repeating 41-42 ms after every inset-triggered scroll that followed a re-tap. The keyboard
 * stayed on screen talking to an editor that no longer had focus.
 *
 * <p>So this class exists to make one rule impossible to get wrong on either surface: scrolling
 * changes scroll position. Focus changes only because the user, or an explicit input transition,
 * changed it. There is deliberately no IME, focus, or window logic here.
 */
public final class FocusSafeScroll {
    private FocusSafeScroll() {}

    /** Scrolls to the bottom of the content without participating in focus search. */
    public static void toBottom(ScrollView scrollView, boolean smooth) {
        int target = bottomOffset(scrollView);
        if (target < 0) return;
        if (smooth) scrollView.smoothScrollTo(0, target);
        else scrollView.scrollTo(0, target);
    }

    /** Scrolls to the top of the content without participating in focus search. */
    public static void toTop(ScrollView scrollView, boolean smooth) {
        if (scrollView == null) return;
        if (smooth) scrollView.smoothScrollTo(0, 0);
        else scrollView.scrollTo(0, 0);
    }

    /**
     * The scroll offset that puts the end of the content at the bottom of the viewport, or -1
     * when there is nothing to scroll.
     */
    public static int bottomOffset(ScrollView scrollView) {
        if (scrollView == null || scrollView.getChildCount() == 0) return -1;
        View content = scrollView.getChildAt(0);
        if (content == null) return -1;
        int viewport = scrollView.getHeight() - scrollView.getPaddingTop() - scrollView.getPaddingBottom();
        return Math.max(0, content.getHeight() - viewport);
    }
}

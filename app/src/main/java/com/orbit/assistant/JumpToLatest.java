package com.orbit.assistant;

import android.content.Context;

/**
 * Visibility for the full-chat Jump to latest control.
 *
 * <p>The control appears only once the newest messages have left the viewport, using the same
 * near-bottom slop full chat already uses to decide whether to follow new content. Automatic
 * follow and the jump control therefore cannot disagree about whether the latest messages are
 * still on screen.
 */
final class JumpToLatest {
    private JumpToLatest() {}

    /** Matches the existing full-chat follow slop. */
    static int slopPx(Context c) {
        return c == null ? 0 : UiKit.dp(c, 96);
    }

    static boolean nearBottom(int contentHeight, int viewportHeight, int scrollY, int slopPx) {
        if (contentHeight <= viewportHeight) return true;
        return contentHeight - (scrollY + viewportHeight) <= Math.max(0, slopPx);
    }

    static boolean shouldShow(int contentHeight, int viewportHeight, int scrollY, int slopPx) {
        return !nearBottom(contentHeight, viewportHeight, scrollY, slopPx);
    }
}

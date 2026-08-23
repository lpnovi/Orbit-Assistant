package com.orbit.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupWindow;

/**
 * Contextual message actions for full chat and the Side-button overlay.
 *
 * <p>The conversation stays visually quiet. Long-pressing a message gives one haptic
 * acknowledgement, sends {@link OrbitMessageHighlight}'s accent ripple through the bubble, and
 * opens one Orbit menu: Copy and Regenerate on assistant replies (Regenerate only on the latest
 * turn), Copy and Edit &amp; resend on the user's own messages. Edit &amp; resend only returns the
 * text to the composer; sending still goes through the ordinary Send path and does not rewrite
 * history.
 */
final class MessageActions {
    static final String COPY_MENU_LABEL = "Copy";
    static final String REGENERATE_MENU_LABEL = "Regenerate";
    static final String EDIT_MENU_LABEL = "Edit & resend";

    /** Both surfaces draw message bubbles at this radius, so the selection matches their shape. */
    private static final float BUBBLE_RADIUS_DP = 18f;
    /** The released selection's fade, plus enough slack for its last frame to have landed. */
    private static final long RELEASE_CLEAR_MS = OrbitMessageHighlight.releaseDurationMs() + 80L;

    interface AfterCopy {
        void onCopied();
    }

    private static PopupWindow openMenu;
    private static View highlighted;
    private static OrbitMessageHighlight selection;
    private static float pressRawX;
    private static float pressRawY;
    private static boolean pressKnown;

    /**
     * Records where a press landed so the ripple can start there rather than at the middle of the
     * bubble. One shared listener for every message: it stores two floats and never consumes the
     * event, so ordinary tapping, link handling, scrolling, and Android's own long-press timing
     * are all left exactly as they were.
     */
    private static final View.OnTouchListener PRESS_POINT = (view, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            pressRawX = event.getRawX();
            pressRawY = event.getRawY();
            pressKnown = true;
        }
        return false;
    };

    private MessageActions() {}

    /** Visible assistant-reply text plus a trailing hosted-search source URL, if any. */
    static String assistantCopyText(String raw) {
        return SourceLinkUtil.copyText(raw);
    }

    /** The same text the user bubble shows, without role labels or other chrome. */
    static String userCopyText(String raw) {
        if (raw == null) return "";
        return raw.replace("—", "-");
    }

    static String[] assistantLabels(boolean canRegenerate) {
        return canRegenerate
                ? new String[]{COPY_MENU_LABEL, REGENERATE_MENU_LABEL}
                : new String[]{COPY_MENU_LABEL};
    }

    static int[] assistantIcons(boolean canRegenerate) {
        return canRegenerate
                ? new int[]{R.drawable.ic_copy, R.drawable.ic_regenerate}
                : new int[]{R.drawable.ic_copy};
    }

    /**
     * Edit &amp; resend is deliberately absent: its state handling proved unreliable on device, and
     * a visibly broken action is worse than none. The composer-side machinery stays in place so
     * the action can return once resending is dependable; the roadmap records that intent.
     */
    static String[] userLabels() {
        return new String[]{COPY_MENU_LABEL};
    }

    static int[] userIcons() {
        return new int[]{R.drawable.ic_copy};
    }

    static void bindAssistant(View bubble, String rawText, boolean canRegenerate,
                              Runnable regenerate, AfterCopy afterCopy) {
        if (bubble == null) return;
        String copyText = assistantCopyText(rawText);
        if (copyText.trim().isEmpty()) return;
        bindTree(bubble, v -> {
            showAssistantMenu(bubble, copyText, canRegenerate, regenerate, afterCopy);
            return true;
        });
    }

    static void bindUser(View bubble, String rawText, Runnable editResend, AfterCopy afterCopy) {
        if (bubble == null) return;
        String text = userCopyText(rawText);
        if (text.trim().isEmpty()) return;
        bindTree(bubble, v -> {
            showUserMenu(bubble, text, editResend, afterCopy);
            return true;
        });
    }

    static void copyAssistant(Context c, String rawText, AfterCopy afterCopy) {
        copy(c, "Orbit response", assistantCopyText(rawText), afterCopy);
    }

    static void copyUser(Context c, String rawText, AfterCopy afterCopy) {
        copy(c, "Orbit message", userCopyText(rawText), afterCopy);
    }

    static void copy(Context c, String clipLabel, String text, AfterCopy afterCopy) {
        if (c == null) return;
        String value = text == null ? "" : text;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(clipLabel, value));
        if (afterCopy != null) afterCopy.onCopied();
    }

    static void dismiss() {
        PopupWindow popup = openMenu;
        openMenu = null;
        if (popup != null && popup.isShowing()) {
            try { popup.dismiss(); } catch (Exception ignored) {}
        }
        clearHighlight();
    }

    private static void showAssistantMenu(View bubble, String copyText, boolean canRegenerate,
                                          Runnable regenerate, AfterCopy afterCopy) {
        showMenu(bubble, assistantLabels(canRegenerate), assistantIcons(canRegenerate),
                (index, label) -> {
                    if (COPY_MENU_LABEL.equals(label)) {
                        copy(bubble.getContext(), "Orbit response", copyText, afterCopy);
                    } else if (REGENERATE_MENU_LABEL.equals(label) && regenerate != null) {
                        regenerate.run();
                    }
                });
    }

    private static void showUserMenu(View bubble, String text, Runnable editResend,
                                     AfterCopy afterCopy) {
        showMenu(bubble, userLabels(), userIcons(), (index, label) -> {
            if (COPY_MENU_LABEL.equals(label)) {
                copy(bubble.getContext(), "Orbit message", text, afterCopy);
            } else if (EDIT_MENU_LABEL.equals(label) && editResend != null) {
                editResend.run();
            }
        });
    }

    private static void showMenu(View bubble, String[] labels, int[] icons,
                                 UiKit.OrbitMenuChoice choice) {
        if (bubble == null || labels == null || labels.length == 0) return;
        dismiss();
        UiKit.haptic(bubble, HapticFeedbackConstants.LONG_PRESS);
        highlight(bubble);
        PopupWindow popup = UiKit.showOrbitActionMenu(bubble.getContext(), bubble, labels, icons,
                (index, label) -> {
                    if (choice != null) choice.onChoice(index, label);
                });
        if (popup == null) {
            clearHighlight();
            return;
        }
        openMenu = popup;
        popup.setOnDismissListener(() -> {
            if (openMenu == popup) openMenu = null;
            clearHighlight();
        });
    }

    /**
     * Marks the held message with Orbit's accent ripple. The effect lives entirely in the
     * bubble's foreground, so the message keeps its own size and position and the conversation
     * around it does not move or reflow.
     */
    private static void highlight(View bubble) {
        highlighted = bubble;
        OrbitMessageHighlight held = new OrbitMessageHighlight(bubble.getContext(), BUBBLE_RADIUS_DP);
        if (pressKnown) {
            int[] onScreen = new int[2];
            bubble.getLocationOnScreen(onScreen);
            held.setPressPoint(pressRawX - onScreen[0], pressRawY - onScreen[1]);
        }
        // Consumed, so a long-press that arrived without a touch — an accessibility action, say —
        // ripples from the middle of its own message instead of an earlier finger position.
        pressKnown = false;
        selection = held;
        bubble.setForeground(held);
    }

    /**
     * Fades the selection out and drops it. The fade is a fixed length, so the foreground is
     * dropped exactly once from a posted runnable rather than from the drawable's own last frame,
     * and the drop is identity-checked: a message long-pressed again mid-fade keeps its new
     * selection, and the released one cannot clear it.
     */
    private static void clearHighlight() {
        View bubble = highlighted;
        OrbitMessageHighlight held = selection;
        highlighted = null;
        selection = null;
        if (bubble == null) return;
        if (held == null) {
            drop(bubble, null);
            return;
        }
        held.release();
        if (!bubble.isAttachedToWindow() || !UiKit.animationsEnabled()) {
            drop(bubble, held);
            return;
        }
        bubble.postDelayed(() -> drop(bubble, held), RELEASE_CLEAR_MS);
    }

    private static void drop(View bubble, OrbitMessageHighlight held) {
        if (bubble.getForeground() == held) bubble.setForeground(null);
    }

    /**
     * Long-press is attached to the message and its non-interactive children so a rich
     * Markdown reply still opens the menu. Code-block Copy buttons keep their own tap.
     *
     * <p>Nothing here fights Android's own gesture detection: a press that turns into a scroll is
     * cancelled by the conversation's scroll container before the long-press timer fires, which is
     * what keeps the menu out of ordinary scrolling.
     */
    private static void bindTree(View view, View.OnLongClickListener listener) {
        if (view == null || listener == null) return;
        if (isReservedControl(view)) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(listener);
        view.setOnTouchListener(PRESS_POINT);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                bindTree(group.getChildAt(i), listener);
            }
        }
    }

    private static boolean isReservedControl(View view) {
        return view instanceof Button || view instanceof ImageButton;
    }
}

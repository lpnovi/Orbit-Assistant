package com.orbit.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupWindow;

/**
 * Contextual message actions for full chat and the Side-button overlay.
 *
 * <p>The conversation stays visually quiet. Long-pressing a message opens one Orbit menu:
 * Copy and Regenerate on assistant replies (Regenerate only on the latest turn), Copy and
 * Edit &amp; resend on the user's own messages. Edit &amp; resend only returns the text to
 * the composer; sending still goes through the ordinary Send path and does not rewrite history.
 */
final class MessageActions {
    static final String COPY_MENU_LABEL = "Copy";
    static final String REGENERATE_MENU_LABEL = "Regenerate";
    static final String EDIT_MENU_LABEL = "Edit & resend";

    interface AfterCopy {
        void onCopied();
    }

    private static PopupWindow openMenu;
    private static View highlighted;

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

    static String[] userLabels() {
        return new String[]{COPY_MENU_LABEL, EDIT_MENU_LABEL};
    }

    static int[] userIcons() {
        return new int[]{R.drawable.ic_copy, R.drawable.ic_edit};
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

    private static void highlight(View bubble) {
        highlighted = bubble;
        Context c = bubble.getContext();
        bubble.setForeground(UiKit.outlined(Color.TRANSPARENT,
                UiKit.withAlpha(UiKit.accent(c), 110), 18, c));
        if (!UiKit.animationsEnabled()) return;
        bubble.animate().cancel();
        bubble.animate().scaleX(1.012f).scaleY(1.012f)
                .setDuration(UiKit.MOTION_FAST)
                .setInterpolator(UiKit.motionEasing())
                .start();
    }

    private static void clearHighlight() {
        View bubble = highlighted;
        highlighted = null;
        if (bubble == null) return;
        bubble.setForeground(null);
        bubble.animate().cancel();
        if (UiKit.animationsEnabled() && bubble.isAttachedToWindow()) {
            bubble.animate().scaleX(1f).scaleY(1f)
                    .setDuration(UiKit.MOTION_FAST)
                    .start();
        } else {
            bubble.setScaleX(1f);
            bubble.setScaleY(1f);
        }
    }

    /**
     * Long-press is attached to the message and its non-interactive children so a rich
     * Markdown reply still opens the menu. Code-block Copy buttons keep their own tap.
     */
    private static void bindTree(View view, View.OnLongClickListener listener) {
        if (view == null || listener == null) return;
        if (isReservedControl(view)) return;
        view.setLongClickable(true);
        view.setOnLongClickListener(listener);
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

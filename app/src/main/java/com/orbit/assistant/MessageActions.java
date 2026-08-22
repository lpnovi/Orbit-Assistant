package com.orbit.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * Shared Copy / Regenerate language for full chat and the Side-button overlay.
 *
 * <p>Assistant replies keep a compact icon row so Copy and Regenerate stay one tap away.
 * User messages stay visually quiet and reveal Copy through a long-press Orbit menu, so a
 * long conversation is not lined with extra chrome.
 */
final class MessageActions {
    static final String COPY_ASSISTANT_DESCRIPTION = "Copy Orbit response";
    static final String REGENERATE_DESCRIPTION = "Regenerate response";
    static final String COPY_MENU_LABEL = "Copy";
    static final int ACTION_SIZE_DP = 38;
    private static final int FLASH_MS = 900;

    interface AfterCopy {
        void onCopied();
    }

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

    static ImageButton iconButton(Context c, int drawable, String description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(drawable);
        b.setImageTintList(ColorStateList.valueOf(UiKit.MUTED));
        b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 8), UiKit.dp(c, 8), UiKit.dp(c, 8));
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);
        b.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(c), 16, c));
        UiKit.pressScale(b);
        return b;
    }

    /**
     * Compact Copy / Regenerate row that sits under an assistant reply. Regenerating stays
     * on the latest turn only; older replies keep Copy.
     */
    static LinearLayout assistantRow(Context c, String rawText, boolean canRegenerate,
                                     Runnable regenerate) {
        return assistantRow(c, rawText, canRegenerate, regenerate, null);
    }

    static LinearLayout assistantRow(Context c, String rawText, boolean canRegenerate,
                                     Runnable regenerate, AfterCopy afterCopy) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setFocusable(false);
        row.setPadding(UiKit.dp(c, 2), UiKit.dp(c, 2), UiKit.dp(c, 2), UiKit.dp(c, 2));

        ImageButton copy = iconButton(c, R.drawable.ic_copy, COPY_ASSISTANT_DESCRIPTION);
        copy.setOnClickListener(v -> copyAssistant(c, copy, rawText, afterCopy));
        row.addView(copy, actionLp(c, 0));

        if (canRegenerate && regenerate != null) {
            ImageButton regen = iconButton(c, R.drawable.ic_regenerate, REGENERATE_DESCRIPTION);
            regen.setOnClickListener(v -> regenerate.run());
            row.addView(regen, actionLp(c, 6));
        }
        return row;
    }

    static LinearLayout.LayoutParams rowLayoutParams(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(c, ACTION_SIZE_DP + 4));
        lp.gravity = Gravity.START;
        lp.setMargins(UiKit.dp(c, 5), -UiKit.dp(c, 2), 0, UiKit.dp(c, 4));
        return lp;
    }

    static void bindUserCopy(View bubble, String rawText, AfterCopy afterCopy) {
        if (bubble == null) return;
        String text = userCopyText(rawText);
        if (text.trim().isEmpty()) return;
        bubble.setLongClickable(true);
        bubble.setOnLongClickListener(v -> {
            UiKit.haptic(v, HapticFeedbackConstants.LONG_PRESS);
            showCopyMenu(v.getContext(), v, text, afterCopy);
            return true;
        });
    }

    static void copyAssistant(Context c, View feedback, String rawText, AfterCopy afterCopy) {
        copy(c, feedback, "Orbit response", assistantCopyText(rawText), afterCopy);
    }

    static void copyUser(Context c, View feedback, String rawText, AfterCopy afterCopy) {
        copy(c, feedback, "Orbit message", userCopyText(rawText), afterCopy);
    }

    static void copy(Context c, View feedback, String clipLabel, String text, AfterCopy afterCopy) {
        if (c == null) return;
        String value = text == null ? "" : text;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText(clipLabel, value));
        flashCopied(feedback);
        if (afterCopy != null) afterCopy.onCopied();
        else Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
    }

    static void flashCopied(View feedback) {
        if (!(feedback instanceof ImageButton)) return;
        ImageButton button = (ImageButton) feedback;
        Context c = button.getContext();
        button.setImageTintList(ColorStateList.valueOf(UiKit.accent(c)));
        button.postDelayed(() -> {
            if (button.isAttachedToWindow()) {
                button.setImageTintList(ColorStateList.valueOf(UiKit.MUTED));
            }
        }, FLASH_MS);
    }

    static void showCopyMenu(Context c, View anchor, String text, AfterCopy afterCopy) {
        if (c == null || anchor == null) return;
        String value = text == null ? "" : text;
        UiKit.showOrbitActionMenu(c, anchor,
                new String[]{COPY_MENU_LABEL},
                new int[]{R.drawable.ic_copy},
                (index, label) -> copy(c, null, "Orbit message", value, afterCopy));
    }

    private static LinearLayout.LayoutParams actionLp(Context c, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiKit.dp(c, ACTION_SIZE_DP), UiKit.dp(c, ACTION_SIZE_DP));
        if (leftMarginDp > 0) lp.setMargins(UiKit.dp(c, leftMarginDp), 0, 0, 0);
        return lp;
    }
}

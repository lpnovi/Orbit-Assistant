package com.orbit.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Contextual message actions for full chat and the Side-button overlay.
 *
 * <p>The conversation stays visually quiet. Long-pressing a message gives one haptic
 * acknowledgement, sends {@link OrbitMessageHighlight}'s accent ripple through the bubble, and
 * opens one Orbit menu: Copy, Save to Vault and Retry on assistant replies (Retry only on the latest
 * turn), plus Retry with, Reply to this and Response details where the surface supports them; Copy
 * and Reply to this on the user's own messages. Finished replies in full chat also carry a small
 * strip of the same actions ({@link #actionStrip}). Save to Vault writes one local item and does
 * not leave the conversation.
 */
final class MessageActions {
    static final String COPY_MENU_LABEL = "Copy";
    static final String SAVE_TO_VAULT_MENU_LABEL = "Save to Vault";
    static final String RETRY_MENU_LABEL = "Retry";
    static final String RETRY_WITH_MENU_LABEL = "Retry with…";
    static final String REPLY_MENU_LABEL = "Reply to this";
    static final String DETAILS_MENU_LABEL = "Response details";
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

    /**
    /**
     * What a held or stripped assistant reply can do, beyond Copy and Save to Vault.
     *
     * <p>Each action is offered only when its runnable is set, so a surface states what it supports
     * by what it passes rather than by flags that can disagree with the code behind them.
     */
    static final class AssistantActions {
        /** Asks the same question again with this chat's current AI. Latest reply only. */
        Runnable retry;
        /** Opens Retry with. Latest reply only. */
        Runnable retryWith;
        /** Quotes this reply into the composer. */
        Runnable reply;
        /** Shows what produced this reply. Null when Orbit does not know. */
        Runnable details;
        /** Web pages this reply cited, kept as provenance when it is saved. */
        List<String> sourceUrls = new ArrayList<>();
        AfterCopy afterCopy;
    }

    /**
     * The actions offered on a held assistant reply.
     *
     * <p>Save to Vault joins Copy on every reply. The conversation stays visually quiet, and saving
     * is reached by the same gesture and in the same menu language as copying already was.
     */
    static String[] assistantLabels(boolean canRetry) {
        return assistantLabels(canRetry, true);
    }

    /**
     * The same menu with the Vault switched off: Copy, and Retry where it applies.
     *
     * <p>Removed rather than greyed out. A disabled entry is a question the user has to answer
     * every time they long-press a reply, and they already answered it in Settings.
     */
    static String[] assistantLabels(boolean canRetry, boolean vault) {
        AssistantActions a = new AssistantActions();
        if (canRetry) a.retry = () -> {};
        return labelsOf(assistantMenu(a, vault));
    }

    static int[] assistantIcons(boolean canRetry) {
        return assistantIcons(canRetry, true);
    }

    static int[] assistantIcons(boolean canRetry, boolean vault) {
        AssistantActions a = new AssistantActions();
        if (canRetry) a.retry = () -> {};
        return iconsOf(assistantMenu(a, vault));
    }

    /** One menu entry: label, icon, and what it does. */
    static final class Entry {
        final String label;
        final int icon;
        final Runnable run;
        Entry(String label, int icon, Runnable run) {
            this.label = label;
            this.icon = icon;
            this.run = run;
        }
    }

    /** The full long-press menu for an assistant reply, in order. */
    static List<Entry> assistantMenu(AssistantActions a, boolean vault) {
        List<Entry> out = new ArrayList<>();
        out.add(new Entry(COPY_MENU_LABEL, R.drawable.ic_copy, null));
        if (vault) out.add(new Entry(SAVE_TO_VAULT_MENU_LABEL, R.drawable.ic_vault, null));
        if (a.retry != null) out.add(new Entry(RETRY_MENU_LABEL, R.drawable.ic_regenerate, a.retry));
        out.addAll(moreMenu(a));
        return out;
    }

    /**
     * What the strip's More holds: the less common actions. Copy, Retry and Save to Vault are
     * already on the strip itself, so they are not repeated here.
     */
    static List<Entry> moreMenu(AssistantActions a) {
        List<Entry> out = new ArrayList<>();
        if (a.retryWith != null) out.add(new Entry(RETRY_WITH_MENU_LABEL, R.drawable.ic_tune, a.retryWith));
        if (a.reply != null) out.add(new Entry(REPLY_MENU_LABEL, R.drawable.ic_edit, a.reply));
        if (a.details != null) out.add(new Entry(DETAILS_MENU_LABEL, R.drawable.ic_settings, a.details));
        return out;
    }

    static String[] labelsOf(List<Entry> entries) {
        String[] out = new String[entries.size()];
        for (int i = 0; i < out.length; i++) out[i] = entries.get(i).label;
        return out;
    }

    private static int[] iconsOf(List<Entry> entries) {
        int[] out = new int[entries.size()];
        for (int i = 0; i < out.length; i++) out[i] = entries.get(i).icon;
        return out;
    }

    /** The last reply saved, so an accidental second tap says so instead of saving it twice. */
    private static String lastSavedText = "";
    private static long lastSavedAt = 0L;
    /** A repeat of the same save within this window is treated as one tap, not a new save. */
    static final long REPEAT_SAVE_WINDOW_MS = 10_000L;

    /**
     * Saves exactly the reply the user was looking at, and says so where they are.
     *
     * <p>What travels is {@link #assistantCopyText}: the same visible words Copy would put on the
     * clipboard, plus the first web page the answer cited, kept as the item's source page. No
     * hidden prompt, no screen context, no reasoning, no provider or request identity, no
     * attachment, and nothing from the rest of the conversation. This is local storage work: it
     * opens no screen, sends no request, and leaves the conversation exactly where it was.
     *
     * <p>A second tap on the same reply within a few seconds is an accident, not a decision, so it
     * says "Already saved" rather than writing a duplicate; a later deliberate save still saves.
     */
    static void saveToVault(Context c, String rawText) {
        saveToVault(c, rawText, null);
    }

    static void saveToVault(Context c, String rawText, List<String> sourceUrls) {
        if (c == null) return;
        String visible = assistantCopyText(rawText);
        if (visible.trim().isEmpty()) return;
        long now = System.currentTimeMillis();
        if (visible.equals(lastSavedText) && now - lastSavedAt < REPEAT_SAVE_WINDOW_MS) {
            Toast.makeText(c, "Already saved to your Vault", Toast.LENGTH_SHORT).show();
            return;
        }
        String source = sourceUrls == null || sourceUrls.isEmpty() ? "" : sourceUrls.get(0);
        boolean saved = OrbitVaultStore.saveOrbitReply(c, visible, source) != null;
        if (saved) {
            lastSavedText = visible;
            lastSavedAt = now;
        }
        Toast.makeText(c, saved ? OrbitVaultStore.savedMessage(c) : OrbitVaultStore.saveFailureMessage(c, "Orbit could not save that reply"),
                Toast.LENGTH_SHORT).show();
    }

    /** Forgets the repeat guard. For tests. */
    static void resetSaveGuardForTest() {
        lastSavedText = "";
        lastSavedAt = 0L;
    }

    /**
     * Copy, and Reply to this when the surface supports quoting.
     *
     * <p>Edit &amp; resend is deliberately absent: its state handling proved unreliable on device,
     * and a visibly broken action is worse than none. The composer-side machinery stays in place so
     * the action can return once resending is dependable; the roadmap records that intent.
     */
    static String[] userLabels() {
        return new String[]{COPY_MENU_LABEL};
    }

    static String[] userLabels(boolean canReply) {
        return canReply ? new String[]{COPY_MENU_LABEL, REPLY_MENU_LABEL} : userLabels();
    }

    static int[] userIcons() {
        return new int[]{R.drawable.ic_copy};
    }

    static int[] userIcons(boolean canReply) {
        return canReply ? new int[]{R.drawable.ic_copy, R.drawable.ic_edit} : userIcons();
    }

    static void bindAssistant(View bubble, String rawText, boolean canRegenerate,
                              Runnable regenerate, AfterCopy afterCopy) {
        AssistantActions a = new AssistantActions();
        if (canRegenerate) a.retry = regenerate;
        a.afterCopy = afterCopy;
        bindAssistant(bubble, rawText, a);
    }

    static void bindAssistant(View bubble, String rawText, AssistantActions actions) {
        if (bubble == null) return;
        String copyText = assistantCopyText(rawText);
        if (copyText.trim().isEmpty()) return;
        AssistantActions a = actions == null ? new AssistantActions() : actions;
        bindTree(bubble, v -> {
            showAssistantMenu(bubble, rawText, copyText, a);
            return true;
        });
    }

    static void bindUser(View bubble, String rawText, Runnable editResend, AfterCopy afterCopy) {
        bindUser(bubble, rawText, editResend, null, afterCopy);
    }

    static void bindUser(View bubble, String rawText, Runnable editResend, Runnable reply,
                         AfterCopy afterCopy) {
        if (bubble == null) return;
        String text = userCopyText(rawText);
        if (text.trim().isEmpty()) return;
        bindTree(bubble, v -> {
            showUserMenu(bubble, text, editResend, reply, afterCopy);
            return true;
        });
    }

    static final String STRIP_TAG = "orbit-response-actions";

    /**
     * The quiet row under a finished reply: Copy, Retry (latest reply only), Save to Vault (when
     * the Vault is on) and More. Icons only, muted, each with a 44dp target and a spoken label, so
     * the answer stays the most prominent thing on screen.
     */
    static LinearLayout actionStrip(Context c, String rawText, AssistantActions actions) {
        AssistantActions a = actions == null ? new AssistantActions() : actions;
        LinearLayout strip = new LinearLayout(c);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setTag(STRIP_TAG);
        String copyText = assistantCopyText(rawText);
        AfterCopy copied = a.afterCopy != null ? a.afterCopy
                : () -> Toast.makeText(c, "Copied", Toast.LENGTH_SHORT).show();
        strip.addView(stripButton(c, R.drawable.ic_copy, "Copy response",
                v -> copy(c, "Orbit response", copyText, copied)));
        if (a.retry != null) {
            strip.addView(stripButton(c, R.drawable.ic_regenerate, "Retry response",
                    v -> a.retry.run()));
        }
        if (Prefs.vaultEnabled(c)) {
            strip.addView(stripButton(c, R.drawable.ic_vault, "Save response to Vault",
                    v -> saveToVault(c, rawText, a.sourceUrls)));
        }
        List<Entry> more = moreMenu(a);
        if (!more.isEmpty()) {
            ImageButton moreButton = stripButton(c, R.drawable.ic_more, "More response actions", null);
            moreButton.setOnClickListener(v -> {
                dismiss();
                UiKit.showOrbitActionMenu(c, moreButton, labelsOf(more), iconsOf(more),
                        (index, label) -> more.get(index).run.run());
            });
            strip.addView(moreButton);
        }
        return strip;
    }

    private static ImageButton stripButton(Context c, int icon, String description,
                                           View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(UiKit.MUTED));
        b.setBackground(UiKit.ripple(android.graphics.Color.TRANSPARENT, UiKit.accent(c), 14, c));
        b.setContentDescription(description);
        int pad = UiKit.dp(c, 12);
        b.setPadding(pad, pad, pad, pad);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(c, 44), UiKit.dp(c, 44)));
        if (click != null) b.setOnClickListener(click);
        UiKit.pressScale(b);
        return b;
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

    private static void showAssistantMenu(View bubble, String rawText, String copyText,
                                          AssistantActions a) {
        boolean vault = Prefs.vaultEnabled(bubble.getContext());
        List<Entry> entries = assistantMenu(a, vault);
        showMenu(bubble, labelsOf(entries), iconsOf(entries), (index, label) -> {
            if (COPY_MENU_LABEL.equals(label)) {
                copy(bubble.getContext(), "Orbit response", copyText, a.afterCopy);
            } else if (SAVE_TO_VAULT_MENU_LABEL.equals(label)) {
                saveToVault(bubble.getContext(), rawText, a.sourceUrls);
            } else {
                Runnable run = entries.get(index).run;
                if (run != null) run.run();
            }
        });
    }

    private static void showUserMenu(View bubble, String text, Runnable editResend,
                                     Runnable reply, AfterCopy afterCopy) {
        boolean canReply = reply != null;
        showMenu(bubble, userLabels(canReply), userIcons(canReply), (index, label) -> {
            if (COPY_MENU_LABEL.equals(label)) {
                copy(bubble.getContext(), "Orbit message", text, afterCopy);
            } else if (REPLY_MENU_LABEL.equals(label) && reply != null) {
                reply.run();
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

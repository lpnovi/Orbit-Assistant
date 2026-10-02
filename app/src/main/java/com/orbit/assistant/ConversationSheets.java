package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

/**
 * The temporary Orbit sheets behind Conversation Control's quiet indicators (0.8.3.0-beta.3+):
 * the context-window details, what is kept in the chat, and the Continue in new chat confirmation.
 *
 * <p>Each opens only on request and shows amounts and names, never Orbit's instructions, a hidden
 * prompt, or anything else a user did not put into the chat themselves.
 */
final class ConversationSheets {
    private ConversationSheets() {}

    // ---- context window --------------------------------------------------------------------------

    /**
     * The context details. {@code onContinue} is offered when the chat has something to continue
     * from; null leaves the action out.
     */
    /** The Auto denominator: what the limit is instead of a number Orbit does not have yet. */
    static final String AUTO_CONTEXT_LINE = "Auto chooses per request";

    static AlertDialog showContext(Activity a, ContextEstimate estimate, Runnable onContinue) {
        LinearLayout body = column(a);
        if (estimate == null) {
            body.addView(note(a, "Orbit is still measuring this chat."));
        } else {
            TextView headline = UiKit.text(a, estimate.knowsLimit()
                    ? ContextEstimate.approx(estimate.tokens) + " of "
                            + ContextEstimate.exact(estimate.limit) + " tokens"
                    : ContextEstimate.approx(estimate.tokens) + " tokens", 18, UiKit.TEXT, true);
            body.addView(headline);
            String sub = estimate.auto ? AUTO_CONTEXT_LINE
                    : estimate.knowsLimit()
                    ? estimate.percent() + "% used" + (estimate.modelName.isEmpty() ? "" : " · " + estimate.modelName)
                    : (estimate.modelName.isEmpty() ? "Window size unknown" : estimate.modelName + " · window size unknown");
            TextView subline = UiKit.text(a, sub, 13, UiKit.MUTED, false);
            subline.setPadding(0, UiKit.dp(a, 2), 0, UiKit.dp(a, 10));
            body.addView(subline);
            if (estimate.knowsLimit()) {
                ProgressBar bar = UiKit.horizontalProgress(a);
                bar.setMax(1000);
                bar.setProgress(Math.max(estimate.tokens > 0 ? 6 : 0,
                        Math.round(estimate.fraction() * 1000)));
                bar.setProgressTintList(ColorStateList.valueOf(
                        ContextMeterView.colorFor(a, estimate.level() == ContextEstimate.Level.NORMAL
                                ? ContextEstimate.Level.FILLING : estimate.level())));
                body.addView(bar, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(a, 6)));
            }
            Map<ContextLedger.Category, Integer> rows = estimate.breakdown;
            if (!rows.isEmpty()) {
                View gap = new View(a);
                body.addView(gap, new LinearLayout.LayoutParams(1, UiKit.dp(a, 10)));
                for (Map.Entry<ContextLedger.Category, Integer> row : rows.entrySet()) {
                    body.addView(row(a, row.getKey().label, ContextEstimate.approx(row.getValue())));
                }
            }
            StringBuilder notes = new StringBuilder("Estimated. Orbit counts about four characters "
                    + "of text per token and a fixed amount per image; the AI's own count can differ.");
            if (estimate.olderMessagesNotSent > 0) {
                notes.append(" Only your most recent messages are sent; the ")
                        .append(estimate.olderMessagesNotSent)
                        .append(estimate.olderMessagesNotSent == 1 ? " oldest is" : " oldest are")
                        .append(" not resent.");
            }
            if (estimate.auto) {
                notes.append(" Auto picks the model when you send, so no limit is shown here. "
                        + "Each request only goes to a model whose context window fits it.");
            } else if (estimate.fittedByProvider) {
                notes.append(" Orbit Local fits each request into its own small on-device window, "
                        + "keeping your newest messages and the most relevant parts of attachments.");
            } else if (!estimate.knowsLimit()) {
                notes.append(" Orbit does not know this model's context window, so no percentage "
                        + "is shown.");
            }
            TextView footnote = note(a, notes.toString());
            footnote.setPadding(0, UiKit.dp(a, 12), 0, 0);
            body.addView(footnote);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(a)
                .setTitle("Context window")
                .setView(scroll(a, body))
                .setPositiveButton("Done", null);
        if (onContinue != null) builder.setNeutralButton("Continue in new chat", (d, w) -> onContinue.run());
        AlertDialog dialog = builder.create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
        return dialog;
    }

    // ---- kept in this chat -----------------------------------------------------------------------

    interface KeptActions {
        void remove(KeptContext item);
    }

    /** What this chat keeps, each item inspectable and removable. Rebuilt in place on removal. */
    static AlertDialog showKept(Activity a, List<KeptContext> items, KeptActions actions) {
        LinearLayout body = column(a);
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Kept in this chat")
                .setView(scroll(a, body))
                .setPositiveButton("Done", null)
                .create();
        fillKept(a, body, new java.util.ArrayList<>(items), actions, dialog);
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
        return dialog;
    }

    private static void fillKept(Activity a, LinearLayout body, List<KeptContext> items,
                                 KeptActions actions, AlertDialog dialog) {
        body.removeAllViews();
        if (items.isEmpty()) {
            body.addView(note(a, "Nothing is kept in this chat. Hold an attached document, text "
                    + "file or Vault item to keep it for every message."));
            return;
        }
        body.addView(note(a, "Orbit sends these with every new message in this chat until you "
                + "remove them. They are shown once, where you added them."));
        for (KeptContext item : items) {
            LinearLayout line = new LinearLayout(a);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(0, UiKit.dp(a, 8), 0, UiKit.dp(a, 2));
            LinearLayout text = new LinearLayout(a);
            text.setOrientation(LinearLayout.VERTICAL);
            TextView name = UiKit.text(a, item.label, 14, UiKit.TEXT, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            text.addView(name);
            text.addView(UiKit.text(a, item.typeLabel() + " · " + ContextEstimate.approx(
                    ContextLedger.tokensFor(item.text)) + " tokens", 12, UiKit.MUTED, false));
            text.setContentDescription(item.label + ", " + item.typeLabel() + ". Opens a preview.");
            text.setOnClickListener(v -> inspect(a, item));
            text.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(a), 10, a));
            line.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageButton remove = new ImageButton(a);
            remove.setImageResource(R.drawable.ic_close);
            remove.setColorFilter(UiKit.MUTED);
            remove.setBackground(UiKit.ripple(Color.TRANSPARENT, UiKit.accent(a), 14, a));
            int pad = UiKit.dp(a, 13);
            remove.setPadding(pad, pad, pad, pad);
            remove.setContentDescription("Stop keeping " + item.label);
            remove.setOnClickListener(v -> {
                actions.remove(item);
                items.remove(item);
                if (items.isEmpty()) dialog.dismiss();
                else fillKept(a, body, items, actions, dialog);
            });
            line.addView(remove, new LinearLayout.LayoutParams(UiKit.dp(a, 44), UiKit.dp(a, 44)));
            body.addView(line);
        }
    }

    /** A read-only look at what an item will send. */
    private static void inspect(Activity a, KeptContext item) {
        String text = item.text.trim();
        String preview = text.length() > 1600 ? text.substring(0, 1600).trim() + "…" : text;
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(item.label)
                .setMessage(item.typeLabel() + "\n\n" + preview)
                .setPositiveButton("Done", null)
                .create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
    }

    // ---- continue in new chat --------------------------------------------------------------------

    /** Says plainly what Continue in new chat does before it does it. */
    static void confirmContinue(Activity a, Runnable onConfirm) {
        AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("Continue in a new chat?")
                .setMessage("Orbit will write a short summary of this chat and start a new chat "
                        + "with it, along with anything you kept. This chat stays exactly as it is.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue", (d, w) -> onConfirm.run())
                .create();
        UiKit.styleOrbitDialog(dialog, a, false);
        dialog.show();
    }

    // ---- pieces ----------------------------------------------------------------------------------

    private static LinearLayout column(Activity a) {
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(a, 22), UiKit.dp(a, 6), UiKit.dp(a, 22), UiKit.dp(a, 4));
        return body;
    }

    private static ScrollView scroll(Activity a, View content) {
        ScrollView scroll = new ScrollView(a);
        scroll.addView(content);
        return scroll;
    }

    private static LinearLayout row(Activity a, String label, String value) {
        LinearLayout line = new LinearLayout(a);
        line.setPadding(0, UiKit.dp(a, 4), 0, UiKit.dp(a, 4));
        line.addView(UiKit.text(a, label, 14, UiKit.MUTED, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(UiKit.text(a, value, 14, UiKit.TEXT, false));
        return line;
    }

    private static TextView note(Activity a, String text) {
        TextView note = UiKit.text(a, text, 12, UiKit.MUTED, false);
        note.setLineSpacing(0, 1.12f);
        return note;
    }
}

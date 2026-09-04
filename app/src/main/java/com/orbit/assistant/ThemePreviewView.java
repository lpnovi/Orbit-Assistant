package com.orbit.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A small piece of Orbit, drawn in a theme that has not been applied yet.
 *
 * <p>The point of Theme Studio is that you do not have to save a colour, leave Settings, open a
 * conversation, decide it was wrong and go back. That only works if what is shown here is
 * trustworthy, which means it has to be built from the same resolved tokens the conversation is
 * built from — hence {@link OrbitThemeTokens}, and hence the deliberate absence of a single
 * hardcoded colour in this file.
 *
 * <p>It is a representative fragment rather than a fake screenshot of the app: a header strip, one
 * exchange of messages with real Markdown in the reply, a card, an accent chip and a Deck tile.
 * Enough to judge a theme; little enough to sit above the controls on a phone without pushing them
 * off the screen.
 */
public final class ThemePreviewView extends LinearLayout {

    private OrbitThemeTokens tokens;

    public ThemePreviewView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClipToOutline(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Rebuilds the fragment for {@code tokens}. Cheap: a handful of views and no measurement. */
    public void render(OrbitThemeTokens tokens) {
        this.tokens = tokens;
        removeAllViews();
        if (tokens == null) return;
        Context c = getContext();

        setBackground(UiKit.outlined(tokens.background,
                UiKit.withAlpha(tokens.text, 34), 20, c));
        setPadding(UiKit.dp(c, 14), UiKit.dp(c, 12), UiKit.dp(c, 14), UiKit.dp(c, 14));
        setContentDescription(describe());

        addView(header(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        addView(userBubble(c), lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, Gravity.END));
        addView(assistantBubble(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        addView(bottomRow(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));
    }

    private String describe() {
        if (tokens == null) return "Theme preview";
        return "Theme preview. Background " + OrbitColorName.of(tokens.background)
                + ", cards " + OrbitColorName.of(tokens.surface)
                + ", accent " + OrbitColorName.of(tokens.accent)
                + ", your messages " + OrbitColorName.of(tokens.userBubble)
                + ", Orbit's replies " + OrbitColorName.of(tokens.assistantBubble) + ".";
    }

    // ---- pieces --------------------------------------------------------------------------------

    private View header(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View mark = new View(c);
        mark.setBackground(UiKit.rounded(tokens.accent, 99, c));
        LinearLayout.LayoutParams markLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, 16), UiKit.dp(c, 16));
        markLp.rightMargin = UiKit.dp(c, 9);
        row.addView(mark, markLp);

        TextView title = UiKit.text(c, UiKit.appTitle(c), 15, tokens.text, true);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = UiKit.text(c, "Balanced", 11, tokens.onAccent, false);
        chip.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 4), UiKit.dp(c, 10), UiKit.dp(c, 4));
        chip.setBackground(UiKit.rounded(tokens.accent, 99, c));
        row.addView(chip);
        return row;
    }

    private View userBubble(Context c) {
        TextView bubble = UiKit.text(c, "How does this look?", 13, tokens.userBubbleInk, false);
        bubble.setPadding(UiKit.dp(c, 13), UiKit.dp(c, 9), UiKit.dp(c, 13), UiKit.dp(c, 9));
        bubble.setBackground(UiKit.rounded(tokens.userBubble, UiKit.RADIUS_BUBBLE, c));
        bubble.setContentDescription("Your message bubble, "
                + OrbitColorName.of(tokens.userBubble));
        return bubble;
    }

    private View assistantBubble(Context c) {
        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(UiKit.dp(c, 13), UiKit.dp(c, 10), UiKit.dp(c, 13), UiKit.dp(c, 11));
        bubble.setBackground(UiKit.rounded(tokens.assistantBubble, UiKit.RADIUS_BUBBLE, c));
        bubble.setContentDescription("Orbit's reply bubble, "
                + OrbitColorName.of(tokens.assistantBubble));

        TextView heading = UiKit.text(c, "Looking good", 13, tokens.assistantBubbleInk, true);
        bubble.addView(heading);

        TextView body = UiKit.text(c, "Headings, links and code all follow the theme.",
                12.5f, tokens.assistantBubbleInk, false);
        body.setPadding(0, UiKit.dp(c, 3), 0, 0);
        bubble.addView(body);

        LinearLayout inline = new LinearLayout(c);
        inline.setOrientation(HORIZONTAL);
        inline.setGravity(Gravity.CENTER_VERTICAL);
        inline.setPadding(0, UiKit.dp(c, 7), 0, 0);

        // The same two derivations the renderer uses for an inline code pill and a link, so a
        // theme that would make either unreadable shows it here rather than in a real answer.
        int codeTint = UiKit.inlineCodeTint(tokens.assistantBubble);
        TextView code = UiKit.text(c, "setTimer()", 11.5f,
                UiKit.inlineCodeInk(tokens.assistantBubble), false);
        UiKit.applyCodeTypeface(code);
        code.setPadding(UiKit.dp(c, 7), UiKit.dp(c, 2), UiKit.dp(c, 7), UiKit.dp(c, 3));
        code.setBackground(UiKit.rounded(codeTint, 7, c));
        inline.addView(code);

        TextView link = UiKit.text(c, "a link", 12,
                UiKit.linkColorOn(c, tokens.assistantBubble), false);
        link.setPaintFlags(link.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.leftMargin = UiKit.dp(c, 10);
        inline.addView(link, linkLp);

        bubble.addView(inline);
        return bubble;
    }

    /** A card and a Deck tile side by side, which is where the surface token shows itself. */
    private View bottomRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setPadding(UiKit.dp(c, 11), UiKit.dp(c, 9), UiKit.dp(c, 11), UiKit.dp(c, 10));
        card.setBackground(UiKit.outlined(tokens.surface,
                UiKit.withAlpha(tokens.accent, 44), UiKit.RADIUS_CARD, c));
        card.setContentDescription("Card, " + OrbitColorName.of(tokens.surface));
        card.addView(UiKit.text(c, "Reminders", 12.5f, tokens.text, true));
        card.addView(UiKit.text(c, "Two today", 11, tokens.muted, false));
        row.addView(card, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.35f));

        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 9), UiKit.dp(c, 8), UiKit.dp(c, 9));
        tile.setBackground(UiKit.outlined(tokens.surface2,
                UiKit.withAlpha(tokens.accent, 52), UiKit.RADIUS_CARD, c));
        tile.setContentDescription("Deck tile, " + OrbitColorName.of(tokens.surface2));

        View dot = new View(c);
        dot.setBackground(UiKit.rounded(tokens.accent, 99, c));
        LinearLayout.LayoutParams dotLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, 15), UiKit.dp(c, 15));
        dotLp.bottomMargin = UiKit.dp(c, 5);
        tile.addView(dot, dotLp);
        tile.addView(UiKit.text(c, "Deck", 10.5f, tokens.text, false));

        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tileLp.leftMargin = UiKit.dp(c, 9);
        row.addView(tile, tileLp);
        return row;
    }

    private LinearLayout.LayoutParams lp(int width, int height, int topDp) {
        return lp(width, height, topDp, Gravity.NO_GRAVITY);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int topDp, int gravity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.topMargin = UiKit.dp(getContext(), topDp);
        if (gravity != Gravity.NO_GRAVITY) params.gravity = gravity;
        return params;
    }
}

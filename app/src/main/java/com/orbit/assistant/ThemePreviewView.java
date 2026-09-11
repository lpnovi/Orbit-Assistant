package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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

    /**
     * How large the Orbit mark is drawn in the preview header.
     *
     * <p>Two points wider than the dot it replaced, which is the smallest size at which the mark's
     * innermost ring and its satellite are still separable. The row's height comes from the chip
     * beside it, so nothing moves.
     */
    private static final float MARK_SIZE_DP = 18f;

    private OrbitThemeTokens tokens;
    /**
     * The premium styling this preview is allowed to draw, resolved exactly as the app resolves it.
     *
     * <p>Held rather than recomputed per piece, and never read from the live app. A preview that
     * resolved entitlement differently from the conversation would be the one failure worth caring
     * about here: somebody dragging a slider on a Free device and seeing it move while their chats
     * did not.
     */
    private OrbitProStyle style = OrbitProStyle.DEFAULT;

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
        // The theme's own premium styling, put through the same entitlement gate the app draws
        // with rather than trusted because it is stored.
        style = OrbitProStyle.resolve(c, tokens.theme == null ? null : tokens.theme.pro);

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
        addView(glassRow(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
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

        // Orbit's own brand mark, wearing the draft's accent. It was a plain accent circle, which
        // is not what sits beside the title anywhere else in Orbit and told nobody how their accent
        // would actually look on the app's own mark. Drawn by UiKit from ic_orbit.xml's geometry,
        // the same call the Chats header and the overlay make, so the miniature cannot drift from
        // the real one. The accent is passed in rather than resolved at draw time, because this is
        // a preview of a theme that has not been applied.
        View mark = UiKit.orbitMark(c, MARK_SIZE_DP, tokens.accent);
        LinearLayout.LayoutParams markLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, MARK_SIZE_DP), UiKit.dp(c, MARK_SIZE_DP));
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
        // The same call the conversation makes, given the draft's accent and styling instead of
        // the applied theme's. Roundness and outline therefore cannot be shown here as anything
        // other than what a real message would get.
        bubble.setBackground(UiKit.bubbleSurface(c, tokens.userBubble, tokens.accent, style));
        bubble.setContentDescription("Your message bubble, "
                + OrbitColorName.of(tokens.userBubble) + shapeDescription());
        return bubble;
    }

    /** How the premium message shape reads aloud, or nothing when it is Orbit's own. */
    private String shapeDescription() {
        if (style.isDefault()) return "";
        String outline = style.bubbleOutline == OrbitProStyle.OUTLINE_OFF
                ? "no outline" : style.bubbleOutlineLabel().toLowerCase(java.util.Locale.US)
                + " outline";
        return ", " + style.bubbleRadiusLabel().toLowerCase(java.util.Locale.US)
                + " corners, " + outline;
    }

    private View assistantBubble(Context c) {
        LinearLayout bubble = new LinearLayout(c);
        bubble.setOrientation(VERTICAL);
        bubble.setPadding(UiKit.dp(c, 13), UiKit.dp(c, 10), UiKit.dp(c, 13), UiKit.dp(c, 11));
        bubble.setBackground(UiKit.bubbleSurface(c, tokens.assistantBubble, tokens.accent, style));
        bubble.setContentDescription("Orbit's reply bubble, "
                + OrbitColorName.of(tokens.assistantBubble) + shapeDescription());

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

        // The theme's own link token, not the accent Orbit happens to be using right now. The
        // preview shows a draft, and reading the live accent here is what left this sample sitting
        // still while every other colour on the screen moved.
        TextView link = UiKit.text(c, "a link", 12, tokens.link, false);
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

        // Orbit's own Deck mark, tinted by the draft's accent. It was a plain accent circle until
        // Beta 3, which said nothing: a coloured dot above the word "Deck" is not a Deck tile, and
        // the one thing this sample is here to show is how an icon carries the accent on a card.
        // The resource is the same grid MainActivity opens Deck with, so the miniature and the real
        // thing cannot end up drawing different marks.
        ImageView mark = new ImageView(c);
        mark.setImageResource(R.drawable.ic_deck);
        mark.setImageTintList(ColorStateList.valueOf(tokens.accent));
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, 16), UiKit.dp(c, 16));
        markLp.bottomMargin = UiKit.dp(c, 5);
        tile.addView(mark, markLp);
        tile.addView(UiKit.text(c, "Deck", 10.5f, tokens.text, false));

        LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        tileLp.leftMargin = UiKit.dp(c, 9);
        row.addView(tile, tileLp);
        return row;
    }

    /**
     * Orbit's floating glass, over the page the theme actually has.
     *
     * <p>The glass is the one part of Orbit's appearance a person cannot judge from colours alone,
     * because it is translucent: what it looks like depends on what is behind it. So this is a
     * floating control drawn over the preview's own background, from {@link OrbitGlass} rather than
     * from an impression of it - the same drawable Chats and the Vault put under their search box.
     * Adjusting opacity, tint or edge moves this and the real screens together or neither.
     */
    private View glassRow(Context c) {
        OrbitGlass.Palette palette = OrbitGlass.Palette.of(tokens, style);

        LinearLayout control = new LinearLayout(c);
        control.setOrientation(HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 9), UiKit.dp(c, 12), UiKit.dp(c, 9));
        control.setBackground(OrbitGlass.surfaceDrawable(c, palette, 14f));

        // Read against the fill composited over the page, which is what OrbitGlass.effectiveFill
        // exists to answer, so the label stays legible at every opacity the control allows.
        int ink = OrbitContrast.inkOn(OrbitGlass.effectiveFill(palette));
        control.addView(UiKit.text(c, "Search", 11.5f, ink, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        control.addView(UiKit.text(c, "Chats", 10.5f, UiKit.withAlpha(ink, 170), false));
        control.setContentDescription("Floating glass control, "
                + (style.isDefault() ? "Orbit default" : style.glassOpacityLabel().toLowerCase(
                        java.util.Locale.US) + ", tint " + style.glassTintLabel().toLowerCase(
                        java.util.Locale.US) + ", edge " + style.glassEdgeLabel().toLowerCase(
                        java.util.Locale.US)) + ".");
        return control;
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

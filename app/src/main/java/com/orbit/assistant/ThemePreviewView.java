package com.orbit.assistant;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
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
 *
 * <h2>Built once, then updated</h2>
 *
 * <p>It used to rebuild: {@code render} called {@code removeAllViews} and constructed every label,
 * bubble and tile again. That was fine when a render followed a colour being chosen from a menu,
 * and became the wrong shape entirely in v0.8.0.0-beta.2, when premium sliders started calling it
 * continuously as a finger moved. Sixty hierarchy rebuilds a second is wasteful on its own, and it
 * also fed the typography watcher a fresh set of labels on every frame, which is where the visible
 * weight flicker on "Looking good" came from.
 *
 * <p>So the hierarchy is constructed once, in {@link #buildOnce}, and {@link #render} now only
 * assigns colours, drawables and text to views that already exist. The preview is the same picture
 * either way; what changed is that dragging a slider no longer destroys and recreates it.
 *
 * <p>There is deliberately no glass sample here any more. Beta 2 added one, and once Theme Studio
 * grew a dedicated glass preview sitting directly above the glass controls, keeping a second one
 * up here made the overview longer without making it more useful. This view is the overall look;
 * the detailed tuning samples belong beside the controls that tune them.
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

    // ---- the pieces, held so they can be updated rather than replaced --------------------------

    private LinearLayout markHost;
    /**
     * The accent the brand mark is currently drawn in.
     *
     * <p>{@link UiKit#orbitMark(Context, float, int)} captures its accent when it is constructed,
     * because a preview mark has to hold still at a draft's accent rather than follow the applied
     * one. That leaves exactly one child here that cannot be recoloured in place, so it is swapped
     * instead - and only when the accent genuinely moves. A premium slider never changes the
     * accent, so the hierarchy really is stable for the whole of a drag, which is the property
     * this file was refactored to get.
     */
    private int markAccent;
    private boolean markDrawn;
    private TextView title;
    private TextView modeChip;
    private TextView userBubble;
    private LinearLayout assistantBubble;
    private TextView assistantHeading;
    private TextView assistantBody;
    private TextView inlineCode;
    private TextView inlineLink;
    private LinearLayout card;
    private TextView cardTitle;
    private TextView cardSubtitle;
    private LinearLayout tile;
    private ImageView tileMark;
    private TextView tileLabel;

    public ThemePreviewView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClipToOutline(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        buildOnce(c);
    }

    /**
     * Updates the fragment to {@code tokens}.
     *
     * <p>No view is created or removed here. Every call assigns to the same objects, which is what
     * makes this cheap enough to run on every reported value of a drag.
     */
    public void render(OrbitThemeTokens tokens) {
        this.tokens = tokens;
        if (tokens == null) return;
        Context c = getContext();
        // The theme's own premium styling, put through the same entitlement gate the app draws
        // with rather than trusted because it is stored.
        style = OrbitProStyle.resolve(c, tokens.theme == null ? null : tokens.theme.pro);

        setBackground(UiKit.outlined(tokens.background, UiKit.withAlpha(tokens.text, 34), 20, c));
        setContentDescription(describe());

        if (!markDrawn || markAccent != tokens.accent) {
            markAccent = tokens.accent;
            markDrawn = true;
            markHost.removeAllViews();
            markHost.addView(UiKit.orbitMark(c, MARK_SIZE_DP, tokens.accent),
                    new LinearLayout.LayoutParams(UiKit.dp(c, MARK_SIZE_DP),
                            UiKit.dp(c, MARK_SIZE_DP)));
        }
        title.setTextColor(tokens.text);
        modeChip.setTextColor(tokens.onAccent);
        modeChip.setBackground(UiKit.rounded(tokens.accent, 99, c));

        // The same call the conversation makes, given the draft's accent and styling instead of
        // the applied theme's. Roundness and outline therefore cannot be shown here as anything
        // other than what a real message would get.
        userBubble.setTextColor(tokens.userBubbleInk);
        userBubble.setBackground(UiKit.bubbleSurface(c, tokens.userBubble, tokens.accent, style));
        userBubble.setContentDescription("Your message bubble, "
                + OrbitColorName.of(tokens.userBubble) + shapeDescription());

        assistantBubble.setBackground(
                UiKit.bubbleSurface(c, tokens.assistantBubble, tokens.accent, style));
        assistantBubble.setContentDescription("Orbit's reply bubble, "
                + OrbitColorName.of(tokens.assistantBubble) + shapeDescription());
        assistantHeading.setTextColor(tokens.assistantBubbleInk);
        assistantBody.setTextColor(tokens.assistantBubbleInk);

        // The same two derivations the renderer uses for an inline code pill and a link, so a
        // theme that would make either unreadable shows it here rather than in a real answer.
        inlineCode.setTextColor(UiKit.inlineCodeInk(tokens.assistantBubble));
        inlineCode.setBackground(
                UiKit.rounded(UiKit.inlineCodeTint(tokens.assistantBubble), 7, c));
        // The theme's own link token, not the accent Orbit happens to be using right now. The
        // preview shows a draft, and reading the live accent here is what left this sample sitting
        // still while every other colour on the screen moved.
        inlineLink.setTextColor(tokens.link);

        card.setBackground(UiKit.outlined(tokens.surface,
                UiKit.withAlpha(tokens.accent, 44), UiKit.RADIUS_CARD, c));
        card.setContentDescription("Card, " + OrbitColorName.of(tokens.surface));
        cardTitle.setTextColor(tokens.text);
        cardSubtitle.setTextColor(tokens.muted);

        tile.setBackground(UiKit.outlined(tokens.surface2,
                UiKit.withAlpha(tokens.accent, 52), UiKit.RADIUS_CARD, c));
        tile.setContentDescription("Deck tile, " + OrbitColorName.of(tokens.surface2));
        tileMark.setImageTintList(ColorStateList.valueOf(tokens.accent));
        tileLabel.setTextColor(tokens.text);
    }

    /** The tokens this preview is currently showing, for the screens and tests that ask. */
    public OrbitThemeTokens tokens() {
        return tokens;
    }

    private String describe() {
        if (tokens == null) return "Theme preview";
        return "Theme preview. Background " + OrbitColorName.of(tokens.background)
                + ", cards " + OrbitColorName.of(tokens.surface)
                + ", accent " + OrbitColorName.of(tokens.accent)
                + ", your messages " + OrbitColorName.of(tokens.userBubble)
                + ", Orbit's replies " + OrbitColorName.of(tokens.assistantBubble) + ".";
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

    // ---- construction ---------------------------------------------------------------------------

    /**
     * Builds the hierarchy, with no colour in it.
     *
     * <p>Everything here is structure, size and text. Colour arrives in {@link #render}, which is
     * the whole point of the split: the expensive part happens once and the part that changes as a
     * slider moves is a handful of assignments.
     */
    private void buildOnce(Context c) {
        setPadding(UiKit.dp(c, 14), UiKit.dp(c, 12), UiKit.dp(c, 14), UiKit.dp(c, 14));

        addView(header(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0));
        addView(buildUserBubble(c), lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, Gravity.END));
        addView(buildAssistantBubble(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8));
        addView(bottomRow(c), lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10));
    }

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
        markHost = new LinearLayout(c);
        markHost.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams markLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, MARK_SIZE_DP), UiKit.dp(c, MARK_SIZE_DP));
        markLp.rightMargin = UiKit.dp(c, 9);
        row.addView(markHost, markLp);

        title = UiKit.text(c, UiKit.appTitle(c), 15, UiKit.TEXT, true);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modeChip = UiKit.text(c, "Balanced", 11, UiKit.TEXT, false);
        modeChip.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 4), UiKit.dp(c, 10), UiKit.dp(c, 4));
        row.addView(modeChip);
        return row;
    }

    private View buildUserBubble(Context c) {
        userBubble = UiKit.text(c, "How does this look?", 13, UiKit.TEXT, false);
        userBubble.setPadding(UiKit.dp(c, 13), UiKit.dp(c, 9), UiKit.dp(c, 13), UiKit.dp(c, 9));
        return userBubble;
    }

    private View buildAssistantBubble(Context c) {
        assistantBubble = new LinearLayout(c);
        assistantBubble.setOrientation(VERTICAL);
        assistantBubble.setPadding(UiKit.dp(c, 13), UiKit.dp(c, 10),
                UiKit.dp(c, 13), UiKit.dp(c, 11));

        assistantHeading = UiKit.text(c, "Looking good", 13, UiKit.TEXT, true);
        assistantBubble.addView(assistantHeading);

        assistantBody = UiKit.text(c, "Headings, links and code all follow the theme.",
                12.5f, UiKit.TEXT, false);
        assistantBody.setPadding(0, UiKit.dp(c, 3), 0, 0);
        assistantBubble.addView(assistantBody);

        LinearLayout inline = new LinearLayout(c);
        inline.setOrientation(HORIZONTAL);
        inline.setGravity(Gravity.CENTER_VERTICAL);
        inline.setPadding(0, UiKit.dp(c, 7), 0, 0);

        inlineCode = UiKit.text(c, "setTimer()", 11.5f, UiKit.TEXT, false);
        UiKit.applyCodeTypeface(inlineCode);
        inlineCode.setPadding(UiKit.dp(c, 7), UiKit.dp(c, 2), UiKit.dp(c, 7), UiKit.dp(c, 3));
        inline.addView(inlineCode);

        inlineLink = UiKit.text(c, "a link", 12, UiKit.TEXT, false);
        inlineLink.setPaintFlags(inlineLink.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linkLp.leftMargin = UiKit.dp(c, 10);
        inline.addView(inlineLink, linkLp);

        assistantBubble.addView(inline);
        return assistantBubble;
    }

    /** A card and a Deck tile side by side, which is where the surface token shows itself. */
    private View bottomRow(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(HORIZONTAL);

        card = new LinearLayout(c);
        card.setOrientation(VERTICAL);
        card.setPadding(UiKit.dp(c, 11), UiKit.dp(c, 9), UiKit.dp(c, 11), UiKit.dp(c, 10));
        cardTitle = UiKit.text(c, "Reminders", 12.5f, UiKit.TEXT, true);
        card.addView(cardTitle);
        cardSubtitle = UiKit.text(c, "Two today", 11, UiKit.MUTED, false);
        card.addView(cardSubtitle);
        row.addView(card, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.35f));

        tile = new LinearLayout(c);
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(UiKit.dp(c, 8), UiKit.dp(c, 9), UiKit.dp(c, 8), UiKit.dp(c, 9));

        // Orbit's own Deck mark, tinted by the draft's accent. It was a plain accent circle until
        // Beta 3, which said nothing: a coloured dot above the word "Deck" is not a Deck tile, and
        // the one thing this sample is here to show is how an icon carries the accent on a card.
        // The resource is the same grid MainActivity opens Deck with, so the miniature and the real
        // thing cannot end up drawing different marks.
        tileMark = new ImageView(c);
        tileMark.setImageResource(R.drawable.ic_deck);
        tileMark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tileMark.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams tileMarkLp =
                new LinearLayout.LayoutParams(UiKit.dp(c, 16), UiKit.dp(c, 16));
        tileMarkLp.bottomMargin = UiKit.dp(c, 5);
        tile.addView(tileMark, tileMarkLp);
        tileLabel = UiKit.text(c, "Deck", 10.5f, UiKit.TEXT, false);
        tile.addView(tileLabel);

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

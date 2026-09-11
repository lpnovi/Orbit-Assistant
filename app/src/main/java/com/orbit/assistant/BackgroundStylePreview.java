package com.orbit.assistant;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A page of Orbit, at whatever the draft's background says, sitting above the background controls.
 *
 * <p>A background is the one part of a theme that cannot be judged from a swatch and cannot be judged
 * from the overview preview either. A gradient needs height to run down and a glow needs width to
 * spread across, so a fragment the size of a colour chip shows neither, and the main Theme Preview is
 * a composite of the whole app rather than a plain page - the very thing an effect is drawn on. So
 * this is a small page: the effect, a card on top of it, and a line of ordinary text on the page
 * itself, which between them answer the two questions somebody tuning a background actually has. Does
 * it look good, and can I still read it.
 *
 * <p>Every pixel of the effect comes from {@link OrbitBackground}. There is no gradient arithmetic in
 * this file and no radial falloff either, which matters more here than anywhere else in Theme Studio:
 * a preview with its own formula would go on looking plausible while the real pages drifted away from
 * it, and the whole point of choosing a background is trusting that the page will look like this.
 *
 * <p>Built once, updated in place.
 */
public final class BackgroundStylePreview extends FrameLayout {

    /**
     * How tall the sample page is.
     *
     * <p>Tall enough that a top-to-bottom gradient has somewhere to travel and a glow positioned at
     * the top is visibly not at the bottom, short enough that the controls it belongs to are still on
     * screen beneath it on a phone. Below about this the three glow positions start to look alike,
     * which would make the control appear broken.
     */
    private static final int HEIGHT_DP = 132;

    private final LinearLayout content;
    private final TextView pageText;
    private final LinearLayout card;
    private final TextView cardTitle;
    private final TextView cardBody;
    private final TextView note;

    /** The corner the sample page is clipped to, matching the other two Theme Studio samples. */
    private static final float RADIUS_DP = 14f;

    public BackgroundStylePreview(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        content = new LinearLayout(c);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(c, 14), UiKit.dp(c, 12), UiKit.dp(c, 14), UiKit.dp(c, 12));
        // The effect is a drawable with its own geometry rather than a rounded fill, so the corner
        // has to be a clip. Without it a gradient would run to a square edge inside a page of
        // rounded samples, which reads as an unfinished panel rather than as a sample of a page.
        content.setClipToOutline(true);
        content.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline out) {
                out.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        UiKit.dp(view.getContext(), RADIUS_DP));
            }
        });
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, HEIGHT_DP)));

        // Text directly on the page, which is the pairing a background effect could damage and the
        // reason Orbit keeps deriving page ink from the theme's Background colour rather than from
        // whatever the effect happens to be doing at that point on the screen.
        pageText = UiKit.text(c, "Text on the page", 12.5f, UiKit.TEXT, false);
        content.addView(pageText);

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(c, 11), UiKit.dp(c, 9), UiKit.dp(c, 11), UiKit.dp(c, 10));
        cardTitle = UiKit.text(c, "A card", 13, UiKit.TEXT, true);
        card.addView(cardTitle);
        cardBody = UiKit.text(c, "Cards keep their own color, so the effect stays behind them.",
                11.5f, UiKit.MUTED, false);
        cardBody.setLineSpacing(0, 1.1f);
        card.addView(cardBody);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = UiKit.dp(c, 10);
        content.addView(card, cardLp);

        // The AMOLED explanation, and the only thing in this view that ever changes visibility. It
        // has to be a held child rather than something added and removed, or turning AMOLED on would
        // change the height of the sample and move the controls under the user's finger.
        note = UiKit.text(c, "", 11, UiKit.MUTED, false);
        note.setGravity(Gravity.CENTER);
        note.setPadding(UiKit.dp(c, 10), UiKit.dp(c, 4), UiKit.dp(c, 10), UiKit.dp(c, 6));
        FrameLayout.LayoutParams noteLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        addView(note, noteLp);
    }

    /** Redraws the sample for a draft, with the styling already resolved against entitlement. */
    public void render(OrbitThemeTokens tokens, OrbitProStyle style) {
        if (tokens == null) return;
        Context c = getContext();
        OrbitProStyle resolved = style == null ? OrbitProStyle.DEFAULT : style;
        OrbitBackground.Page page = OrbitBackground.Page.of(tokens, resolved);

        content.setBackground(OrbitBackground.drawableFor(c, page));
        pageText.setTextColor(tokens.text);
        card.setBackground(UiKit.outlined(tokens.surface,
                UiKit.withAlpha(tokens.accent, 44), UiKit.RADIUS_CARD, c));
        cardTitle.setTextColor(tokens.text);
        cardBody.setTextColor(tokens.muted);

        boolean hidden = tokens.theme != null && tokens.theme.amoled
                && resolved.hasBackgroundEffect();
        note.setText(hidden ? "Background effects are hidden while AMOLED is on." : "");
        note.setTextColor(tokens.muted);
        note.setVisibility(hidden ? VISIBLE : GONE);

        setContentDescription(describe(resolved, hidden));
    }

    private String describe(OrbitProStyle style, boolean hidden) {
        if (hidden) {
            return "Background sample. " + style.backgroundModeLabel()
                    + " is configured but hidden while AMOLED is on.";
        }
        if (style.backgroundMode == OrbitProStyle.BACKGROUND_LINEAR) {
            return "Background sample. Linear gradient, from the "
                    + style.directionLabel().toLowerCase(java.util.Locale.US) + ".";
        }
        if (style.backgroundMode == OrbitProStyle.BACKGROUND_GLOW) {
            return "Background sample. Radial glow, "
                    + style.glowStrengthLabel().toLowerCase(java.util.Locale.US) + ", "
                    + style.glowSizeLabel().toLowerCase(java.util.Locale.US) + ", at the "
                    + style.glowPositionLabel().toLowerCase(java.util.Locale.US) + ".";
        }
        return "Background sample. Solid, the theme's own background color.";
    }
}

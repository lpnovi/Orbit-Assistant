package com.orbit.assistant;

import android.view.Gravity;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A floating Orbit control, over something, sitting above the glass sliders.
 *
 * <p>Glass is the one part of Orbit's appearance that cannot be judged from a swatch, because it is
 * translucent: what it looks like is a function of what is behind it. A sample of it therefore has
 * to have a behind. So this draws a short band of the theme's page and cards, and floats a control
 * over them exactly as Chats and the Vault do - which is the only arrangement in which opacity,
 * tint and edge strength mean anything at all.
 *
 * <p>Everything visible here comes from {@link OrbitGlass}. The surface is its own drawable, the
 * text colour comes from its own {@code effectiveFill}, and there is no rounded rectangle in this
 * file pretending to be glass. That is not tidiness: a hand-drawn imitation would go on looking
 * convincing while quietly disagreeing with the real floating chrome, and somebody would tune their
 * glass against a sample that was lying to them.
 *
 * <p>Built once, updated in place.
 */
public final class GlassStylePreview extends FrameLayout {

    private final LinearLayout backdrop;
    private final LinearLayout cardBand;
    private final LinearLayout control;
    private final TextView label;
    private final TextView hint;

    public GlassStylePreview(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        // The page and a card edge beneath the glass. Without something textured back here the
        // control would be floating over a flat field and every opacity would look the same.
        backdrop = new LinearLayout(c);
        backdrop.setOrientation(LinearLayout.VERTICAL);
        backdrop.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12));
        addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cardBand = new LinearLayout(c);
        cardBand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 30));
        bandLp.topMargin = UiKit.dp(c, 26);
        backdrop.addView(cardBand, bandLp);

        control = new LinearLayout(c);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 9), UiKit.dp(c, 12), UiKit.dp(c, 9));
        control.setElevation(UiKit.dp(c, OrbitGlass.RESTING_ELEVATION_DP));

        label = UiKit.text(c, "Search", 12, UiKit.TEXT, false);
        control.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hint = UiKit.text(c, "Chats", 11, UiKit.MUTED, false);
        control.addView(hint);

        FrameLayout.LayoutParams controlLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlLp.setMargins(UiKit.dp(c, 22), UiKit.dp(c, 20), UiKit.dp(c, 22), UiKit.dp(c, 14));
        addView(control, controlLp);
    }

    /** Redraws the sample for a draft, with the styling already resolved against entitlement. */
    public void render(OrbitThemeTokens tokens, OrbitProStyle style) {
        if (tokens == null) return;
        Context c = getContext();
        OrbitProStyle resolved = style == null ? OrbitProStyle.DEFAULT : style;
        OrbitGlass.Palette palette = OrbitGlass.Palette.of(tokens, resolved);

        backdrop.setBackground(UiKit.rounded(tokens.background, 14, c));
        cardBand.setBackground(UiKit.rounded(tokens.surface, UiKit.RADIUS_CARD, c));

        control.setBackground(OrbitGlass.surfaceDrawable(c, palette, 14f));
        // Read against the fill composited over the page, which is the question effectiveFill
        // exists to answer, so the label stays legible at every opacity the control allows.
        int ink = OrbitContrast.inkOn(OrbitGlass.effectiveFill(palette));
        label.setTextColor(ink);
        hint.setTextColor(UiKit.withAlpha(ink, 170));

        setContentDescription("Floating glass sample. "
                + resolved.glassOpacityLabel() + ", tint "
                + resolved.glassTintLabel().toLowerCase(java.util.Locale.US) + ", edge "
                + resolved.glassEdgeLabel().toLowerCase(java.util.Locale.US) + ".");
    }
}

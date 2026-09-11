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

    /** The corner the sample page is clipped to, so the effect behind the glass ends cleanly. */
    private static final float BACKDROP_RADIUS_DP = 14f;

    private final LinearLayout backdrop;
    private final LinearLayout cardBand;
    private final LinearLayout accentBand;
    private final LinearLayout control;
    private final TextView label;
    private final TextView hint;

    public GlassStylePreview(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        // What is behind the glass, and it is behind it on purpose. Glass is translucent, so a sample
        // floating over a flat field would look the same at every opacity, would show no refraction,
        // and would say nothing at all about how the material sits over a premium background. So the
        // backdrop is a real Orbit page - drawn by OrbitBackground, gradient or glow included - with
        // two bands of colour passing underneath the control.
        backdrop = new LinearLayout(c);
        backdrop.setOrientation(LinearLayout.VERTICAL);
        backdrop.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12), UiKit.dp(c, 12));
        backdrop.setClipToOutline(true);
        backdrop.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline out) {
                out.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        UiKit.dp(view.getContext(), BACKDROP_RADIUS_DP));
            }
        });
        addView(backdrop, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        cardBand = new LinearLayout(c);
        cardBand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bandLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 28));
        bandLp.topMargin = UiKit.dp(c, 26);
        backdrop.addView(cardBand, bandLp);

        // A second, accent-tinted band. Two different colours under one translucent surface is what
        // makes the difference between low and high opacity impossible to miss, and it is where the
        // refraction at the sides of the glass becomes legible rather than theoretical.
        accentBand = new LinearLayout(c);
        accentBand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams accentLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(c, 16));
        accentLp.topMargin = UiKit.dp(c, 6);
        backdrop.addView(accentBand, accentLp);

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

        // The same call every real Orbit page makes, so a person tuning their glass against a
        // gradient or a glow is looking at the combination they will actually get.
        backdrop.setBackground(OrbitBackground.drawableFor(c, tokens, resolved));
        cardBand.setBackground(UiKit.rounded(tokens.surface, UiKit.RADIUS_CARD, c));
        accentBand.setBackground(UiKit.rounded(
                UiKit.blend(tokens.accent, tokens.surface2, 0.34f), UiKit.RADIUS_CARD, c));

        control.setBackground(OrbitGlass.surfaceDrawable(c, palette, 14f));
        // Read against the fill composited over the page, which is the question effectiveFill
        // exists to answer, so the label stays legible at every opacity the control allows.
        int ink = OrbitContrast.inkOn(OrbitGlass.effectiveFill(palette));
        label.setTextColor(ink);
        hint.setTextColor(UiKit.withAlpha(ink, 170));

        setContentDescription("Liquid glass sample. "
                + resolved.glassOpacityLabel() + ", tint "
                + resolved.glassTintLabel().toLowerCase(java.util.Locale.US) + ", edge "
                + resolved.glassEdgeLabel().toLowerCase(java.util.Locale.US) + ".");
    }
}

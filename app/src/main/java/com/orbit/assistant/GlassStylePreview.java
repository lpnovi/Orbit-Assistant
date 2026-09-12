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
 * <p>Everything visible here comes from {@link OrbitFloatingSurface}. Both samples are its drawables,
 * the text colour comes from its own {@code effectiveFill}, and there is no rounded rectangle in this
 * file pretending to be glass. That is not tidiness: a hand-drawn imitation would go on looking
 * convincing while quietly disagreeing with the real floating chrome, and somebody would tune their
 * glass against a sample that was lying to them. It follows the selected material too, so choosing
 * Solid in the Colors card shows a Solid sample here rather than glass nobody asked for.
 *
 * <p>Two samples rather than one, and that is a lesson rather than a flourish. In v0.8.0.0-beta.4 this
 * preview showed a single control about a third of the screen wide, the material looked considered in
 * it, and the same material read as a polished metal tube on the real Chats search field. A finish
 * whose highlights are derived from the shape they land on has to be judged on more than one shape.
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
    private final LinearLayout compact;
    private final TextView compactLabel;

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

        // The cluster the two samples sit in, so a wide bar and a compact control are laid out in one
        // column rather than positioned independently in the frame.
        LinearLayout cluster = new LinearLayout(c);
        cluster.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams clusterLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clusterLp.setMargins(UiKit.dp(c, 20), UiKit.dp(c, 18), UiKit.dp(c, 20), UiKit.dp(c, 14));
        addView(cluster, clusterLp);

        // A full-width bar, at the proportions of the real Chats search field. This is the sample that
        // exists because of a specific failure: in v0.8.0.0-beta.4 the material was judged in a small
        // preview, looked considered there, and read as a chrome tube on the actual search field. A
        // highlight whose geometry depends on the shape of what it lands on has to be shown on the
        // widest shape Orbit floats.
        control = new LinearLayout(c);
        control.setOrientation(LinearLayout.HORIZONTAL);
        control.setGravity(Gravity.CENTER_VERTICAL);
        control.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 11), UiKit.dp(c, 12), UiKit.dp(c, 11));
        control.setElevation(UiKit.dp(c, OrbitGlass.RESTING_ELEVATION_DP));

        label = UiKit.text(c, "Search", 12, UiKit.TEXT, false);
        control.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hint = UiKit.text(c, "Chats", 11, UiKit.MUTED, false);
        control.addView(hint);
        cluster.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // And a compact one beside it, at the proportions of a Vault selector. The same finish has to
        // look right on both, and two samples is the cheapest way to notice when it does not.
        LinearLayout compactRow = new LinearLayout(c);
        compactRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams compactRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        compactRowLp.topMargin = UiKit.dp(c, 8);
        cluster.addView(compactRow, compactRowLp);

        compact = new LinearLayout(c);
        compact.setOrientation(LinearLayout.HORIZONTAL);
        compact.setGravity(Gravity.CENTER);
        compact.setPadding(UiKit.dp(c, 12), UiKit.dp(c, 8), UiKit.dp(c, 12), UiKit.dp(c, 9));
        compact.setElevation(UiKit.dp(c, OrbitGlass.RESTING_ELEVATION_DP));
        compactLabel = UiKit.text(c, "All", 11.5f, UiKit.TEXT, false);
        compact.addView(compactLabel);
        compactRow.addView(compact, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

        // Both samples get the same call, at the two corner radii the real controls use, so the only
        // difference between them is the shape the material had to fit.
        control.setBackground(OrbitFloatingSurface.surfaceDrawable(c, palette, 14f));
        compact.setBackground(OrbitFloatingSurface.surfaceDrawable(c, palette, 12f));
        // Read against the fill composited over the page, which is the question effectiveFill
        // exists to answer, so the label stays legible at every opacity the control allows.
        int ink = OrbitFloatingSurface.inkOn(palette);
        label.setTextColor(ink);
        hint.setTextColor(UiKit.withAlpha(ink, 170));
        compactLabel.setTextColor(ink);

        setContentDescription(describe(tokens, resolved));
    }

    private String describe(OrbitThemeTokens tokens, OrbitProStyle resolved) {
        String material = tokens.theme == null
                ? OrbitTheme.MATERIAL_DEFAULT : tokens.theme.material;
        String what = OrbitTheme.materialLabel(material) + " floating controls, a wide bar and a "
                + "compact control. ";
        if (!OrbitTheme.isGlass(material)) return what + OrbitFloatingSurface.noGlassNote();
        return what + resolved.glassOpacityLabel() + ", tint "
                + resolved.glassTintLabel().toLowerCase(java.util.Locale.US) + ", edge "
                + resolved.glassEdgeLabel().toLowerCase(java.util.Locale.US) + ".";
    }
}

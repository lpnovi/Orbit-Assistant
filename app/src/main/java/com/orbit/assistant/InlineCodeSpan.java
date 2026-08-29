package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * How Orbit draws an inline `code` term inside a reply.
 *
 * <p>The previous treatment was a flat {@code BackgroundColorSpan} in one fixed slate grey. Two
 * things were wrong with it. A background colour span paints the full line box, from the font's
 * ascent to its descent, so a four-letter token such as {@code READY} came out as a tall square
 * block with square corners — visibly taller than the word inside it. And the grey was a constant,
 * so it took no notice of the bubble it was sitting on: the same rectangle appeared on a dark
 * classic bubble, on an accent-coloured one, and on a pastel one, where it read as tape stuck over
 * the sentence rather than as part of it.
 *
 * <p>This draws a small rounded pill instead, sized to the text rather than to the line, with its
 * fill and its ink both derived from the surface behind it. See {@link UiKit#inlineCodeTint(int)}.
 *
 * <p>It is a {@link ReplacementSpan}, which measures and draws the run itself and therefore cannot
 * be broken across two lines. That is the right trade for what inline code actually is — a short
 * identifier or literal, treated by the line breaker like any other unbreakable word — but it is
 * wrong for a long run, which must stay wrappable. {@link #fits} draws that line, and
 * {@code OrbitMarkdown} keeps the flat treatment for anything longer.
 */
public final class InlineCodeSpan extends ReplacementSpan {

    /**
     * The longest run drawn as a pill.
     *
     * <p>A replacement span is unbreakable, so an over-long one would be pushed onto its own line
     * and could still overflow a bubble. Real inline code — {@code READY}, {@code requestId} — is
     * far below this; prose-length runs inside backticks fall back to the wrappable treatment.
     */
    public static final int MAX_PILL_CHARS = 28;

    /** Inline code is set slightly smaller so a monospace face matches the prose around it. */
    private static final float CODE_SIZE_RATIO = 0.94f;

    private final int fill;
    private final int ink;
    private final float horizontalPadding;
    private final float verticalPadding;
    private final float radius;

    private InlineCodeSpan(int fill, int ink, float horizontalPadding, float verticalPadding,
                           float radius) {
        this.fill = fill;
        this.ink = ink;
        this.horizontalPadding = horizontalPadding;
        this.verticalPadding = verticalPadding;
        this.radius = radius;
    }

    /** A pill tinted for the bubble or card colour it will be drawn on. */
    public static InlineCodeSpan on(Context c, int surface) {
        return new InlineCodeSpan(UiKit.inlineCodeTint(surface), UiKit.inlineCodeInk(surface),
                UiKit.dp(c, 4.5f), UiKit.dp(c, 1.5f), UiKit.dp(c, 5f));
    }

    /** Whether a run is short enough to be drawn as an unbreakable pill. */
    public static boolean fits(CharSequence text) {
        return text != null && text.length() > 0 && text.length() <= MAX_PILL_CHARS
                && text.toString().indexOf('\n') < 0;
    }

    /**
     * The width of the pill, and — deliberately — no change to the line's height.
     *
     * <p>Leaving {@code fm} untouched is what keeps a paragraph containing inline code on exactly
     * the same line rhythm as one without it. The pill is inset within the line box instead of
     * pushing it open.
     */
    @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                                Paint.FontMetricsInt fm) {
        return Math.round(codePaint(paint).measureText(text, start, end) + horizontalPadding * 2);
    }

    @Override public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                               float x, int top, int y, int bottom, @NonNull Paint paint) {
        Paint code = codePaint(paint);
        float width = code.measureText(text, start, end) + horizontalPadding * 2;
        Paint.FontMetrics metrics = code.getFontMetrics();

        // Snug to the glyphs rather than to the line box, then clamped so the pill can never
        // bleed into the line above or below however tall the selected Orbit font runs.
        float pillTop = Math.max(top, y + metrics.ascent - verticalPadding);
        float pillBottom = Math.min(bottom, y + metrics.descent + verticalPadding);

        int previousColor = code.getColor();
        Paint.Style previousStyle = code.getStyle();
        code.setColor(fill);
        code.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(x, pillTop, x + width, pillBottom), radius, radius, code);

        code.setStyle(previousStyle);
        code.setColor(ink);
        canvas.drawText(text, start, end, x + horizontalPadding, y, code);
        code.setColor(previousColor);
    }

    /**
     * A copy of the paragraph's paint wearing a monospace face.
     *
     * <p>Copied rather than mutated: the same paint instance draws the rest of the line, and every
     * other span on it, immediately afterwards.
     */
    private Paint codePaint(Paint source) {
        Paint code = new Paint(source);
        code.setTypeface(Typeface.MONOSPACE);
        code.setTextSize(source.getTextSize() * CODE_SIZE_RATIO);
        // Orbit's Monospace app font carries deliberate letter spacing and the Condensed font a
        // horizontal scale. Neither belongs inside a code pill, whose whole job is to render an
        // identifier exactly as it was written.
        code.setLetterSpacing(0f);
        code.setTextScaleX(1f);
        code.setUnderlineText(false);
        return code;
    }
}

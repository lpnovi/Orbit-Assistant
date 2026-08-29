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
 *
 * <h2>Punctuation</h2>
 *
 * <p>A replacement span's padding is part of its measured width, so whatever follows the run
 * begins after it. With one padding value on both sides, a sentence ending {@code `requestId`.}
 * put the pill's whole trailing padding between the last letter and the full stop, and the eye
 * reads a gap that size as a word space. On the device that came out as "requestId ." — and the
 * same for "READY :" and "SUCCEEDED ,". The punctuation belongs to the sentence, not to the code,
 * so it has to sit against the term the way it would after any other word.
 *
 * <p>The answer is asymmetry rather than the removal of padding. The pill keeps its full breathing
 * room where it meets prose, a space, or the end of a line, and tightens only on the side that
 * actually touches sentence punctuation or a bracket. The tightened value is still non-zero, so
 * the fill never crowds the glyphs, and a monospace advance already carries its own side bearing
 * on top of it.
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

    /** Breathing room where the pill meets prose, a space, or the start or end of a line. */
    private static final float PAD_DP = 4.5f;

    /**
     * Breathing room where the pill meets punctuation that belongs to the sentence.
     *
     * <p>Small, and deliberately not zero. It keeps the fill clear of the glyphs while leaving the
     * visible gap in the range of ordinary letter spacing rather than of a word space.
     */
    private static final float TIGHT_PAD_DP = 1.5f;

    /** No neighbouring character: the run starts or ends the line it sits on. */
    static final char NOTHING = '\0';

    private final int fill;
    private final int ink;
    private final float leadPadding;
    private final float trailPadding;
    private final float verticalPadding;
    private final float radius;

    private InlineCodeSpan(int fill, int ink, float leadPadding, float trailPadding,
                           float verticalPadding, float radius) {
        this.fill = fill;
        this.ink = ink;
        this.leadPadding = leadPadding;
        this.trailPadding = trailPadding;
        this.verticalPadding = verticalPadding;
        this.radius = radius;
    }

    /** A pill tinted for the bubble or card colour it will be drawn on. */
    public static InlineCodeSpan on(Context c, int surface) {
        return on(c, surface, NOTHING, NOTHING);
    }

    /**
     * As above, told which characters sit either side of the run in the line being rendered.
     *
     * <p>Only their category is used, and only to choose between two padding values. Nothing about
     * the surrounding sentence is stored, drawn, or recorded.
     */
    public static InlineCodeSpan on(Context c, int surface, char before, char after) {
        return new InlineCodeSpan(UiKit.inlineCodeTint(surface), UiKit.inlineCodeInk(surface),
                UiKit.dp(c, opensAgainstCode(before) ? TIGHT_PAD_DP : PAD_DP),
                UiKit.dp(c, closesAgainstCode(after) ? TIGHT_PAD_DP : PAD_DP),
                UiKit.dp(c, 1.5f), UiKit.dp(c, 5f));
    }

    /**
     * Punctuation that reads as attached to the word before it.
     *
     * <p>Sentence punctuation, closing brackets and quotes, and the joiners that bind two terms
     * into one. A letter, a digit, a space, or nothing at all is not in this set, so the pill keeps
     * its full padding everywhere in ordinary prose.
     */
    static boolean closesAgainstCode(char next) {
        switch (next) {
            case '.': case ',': case ':': case ';': case '!': case '?':
            case ')': case ']': case '}':
            case '"': case '\'': case '’': case '”': case '»':
            case '…': case '%': case '/': case '-': case '–': case '—':
                return true;
            default:
                return false;
        }
    }

    /** The mirror of {@link #closesAgainstCode}: what reads as attached to the word after it. */
    static boolean opensAgainstCode(char previous) {
        switch (previous) {
            case '(': case '[': case '{':
            case '"': case '\'': case '‘': case '“': case '«':
            case '/': case '-': case '–': case '—':
                return true;
            default:
                return false;
        }
    }

    /** Whether a run is short enough to be drawn as an unbreakable pill. */
    public static boolean fits(CharSequence text) {
        return text != null && text.length() > 0 && text.length() <= MAX_PILL_CHARS
                && text.toString().indexOf('\n') < 0;
    }

    /** Room kept between the pill's leading edge and the first glyph. */
    float leadPadding() { return leadPadding; }

    /** Room kept between the last glyph and the pill's trailing edge. */
    float trailPadding() { return trailPadding; }

    /**
     * The width of the pill, and — deliberately — no change to the line's height.
     *
     * <p>Leaving {@code fm} untouched is what keeps a paragraph containing inline code on exactly
     * the same line rhythm as one without it. The pill is inset within the line box instead of
     * pushing it open.
     */
    @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                                Paint.FontMetricsInt fm) {
        return width(codePaint(paint), text, start, end);
    }

    @Override public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                               float x, int top, int y, int bottom, @NonNull Paint paint) {
        Paint code = codePaint(paint);
        // The same rounded width the line was measured with, so the pill's trailing edge lands
        // exactly where the next character's origin does. Measuring one way and drawing another
        // leaves a sub-pixel step, and it shows up precisely where it is least wanted: against the
        // full stop that follows the term.
        int width = width(code, text, start, end);
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
        // Drawn on the paragraph's own baseline, so a code term sits on the same line as the words
        // either side of it whichever Orbit font is selected.
        canvas.drawText(text, start, end, x + leadPadding, y, code);
        code.setColor(previousColor);
    }

    /** One definition of the pill's width, shared by measurement and drawing. */
    private int width(Paint code, CharSequence text, int start, int end) {
        return Math.round(code.measureText(text, start, end) + leadPadding + trailPadding);
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

package com.orbit.assistant;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;

/**
 * How Orbit draws the checkbox of a Markdown task item.
 *
 * <p>Before this, {@code - [x] Done} reached the screen with its syntax intact: the list parser
 * removed the bullet and printed "[x] Done", so an answer that had gone to the trouble of marking
 * work finished looked unfinished itself.
 *
 * <p>Two decisions shape it.
 *
 * <p><b>It is a span, not a view.</b> A task item is therefore exactly the same object an ordinary
 * bullet is — one {@code TextView} holding one line of rich text — so it inherits the list's
 * indentation, its wrapping, its text scaling and its inline Markdown for free, and the box cannot
 * drift away from the words it belongs to. Being drawn on the first line's baseline is what aligns
 * it with the first line of a task whose text runs to three.
 *
 * <p><b>It is not a control.</b> These boxes present what the assistant wrote; they are not a todo
 * list the user owns. A stock {@code CheckBox} would arrive clickable and focusable and would
 * promise a toggle that must not exist, because toggling it would silently edit a stored reply. A
 * span has nothing to press. State is announced instead through the item's content description, so
 * a screen reader hears "Checked, Headings rendered" rather than either the bracket characters or
 * a control that cannot be operated.
 */
public final class TaskBoxSpan extends ReplacementSpan {

    /**
     * The character the box is drawn over.
     *
     * <p>A replacement span measures and draws its own run, so what the run contains is never
     * shown. A single space is used so that selecting or copying a task item yields readable text
     * rather than a stray glyph.
     */
    public static final String PLACEHOLDER = " ";

    /** Box side, as a share of the line's text size, so it tracks the chat text-size preference. */
    private static final float SIZE_RATIO = 0.82f;

    /** Room kept between the box and the words after it. */
    private static final float TRAIL_PAD_DP = 7f;

    /** Room kept before the box, matching the optical inset an ordinary bullet has. */
    private static final float LEAD_PAD_DP = 1.5f;

    private final boolean checked;
    private final int mark;
    private final int outline;
    private final int tick;
    private final float leadPadding;
    private final float trailPadding;
    private final float stroke;
    private final float radius;

    private TaskBoxSpan(boolean checked, int mark, int outline, int tick,
                        float leadPadding, float trailPadding, float stroke, float radius) {
        this.checked = checked;
        this.mark = mark;
        this.outline = outline;
        this.tick = tick;
        this.leadPadding = leadPadding;
        this.trailPadding = trailPadding;
        this.stroke = stroke;
        this.radius = radius;
    }

    /**
     * A box tinted for the bubble colour it will be drawn on.
     *
     * <p>Coloured from {@link UiKit#linkColorOn}, the same source the quote bar uses, for the same
     * reason: the assistant bubble can be set to the accent itself, and a checked box painted in
     * the raw accent would be invisible on exactly that bubble.
     */
    public static TaskBoxSpan on(Context c, boolean checked, int surface) {
        int mark = UiKit.linkColorOn(c, surface);
        return new TaskBoxSpan(checked, mark,
                UiKit.withAlpha(mark, 170), UiKit.onAccent(mark),
                UiKit.dp(c, LEAD_PAD_DP), UiKit.dp(c, TRAIL_PAD_DP),
                Math.max(1f, UiKit.dp(c, 1.4f)), UiKit.dp(c, 3.5f));
    }

    /** True when this box is ticked. Read by tests and by the accessibility description. */
    public boolean isChecked() { return checked; }

    /**
     * The width of the box and its padding, and no change to the line's height.
     *
     * <p>Leaving {@code fm} alone keeps a task list on the same line rhythm as the bullet list
     * above it; the box is inset within the line box rather than pushing it open.
     */
    @Override public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                                Paint.FontMetricsInt fm) {
        return Math.round(side(paint) + leadPadding + trailPadding);
    }

    @Override public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                               float x, int top, int y, int bottom, @NonNull Paint paint) {
        float side = side(paint);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        // Centred on the run of text rather than on the line box, so the box sits with the
        // letters beside it however tall the selected Orbit font runs.
        float middle = y + (metrics.ascent + metrics.descent) / 2f;
        float boxTop = Math.max(top, middle - side / 2f);
        float boxLeft = x + leadPadding;
        RectF box = new RectF(boxLeft, boxTop, boxLeft + side, boxTop + side);

        int previousColor = paint.getColor();
        Paint.Style previousStyle = paint.getStyle();
        float previousStroke = paint.getStrokeWidth();
        Paint.Cap previousCap = paint.getStrokeCap();
        Paint.Join previousJoin = paint.getStrokeJoin();
        boolean previousAntiAlias = paint.isAntiAlias();
        paint.setAntiAlias(true);

        if (checked) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(mark);
            canvas.drawRoundRect(box, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(tick);
            paint.setStrokeWidth(Math.max(1f, side * 0.14f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(check(box), paint);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(outline);
            paint.setStrokeWidth(stroke);
            // Inset by half the stroke so the outline lands inside the measured square rather
            // than straddling its edge, which is what makes a 1dp border look like 2dp.
            RectF edge = new RectF(box.left + stroke / 2f, box.top + stroke / 2f,
                    box.right - stroke / 2f, box.bottom - stroke / 2f);
            canvas.drawRoundRect(edge, radius, radius, paint);
        }

        paint.setAntiAlias(previousAntiAlias);
        paint.setStrokeJoin(previousJoin);
        paint.setStrokeCap(previousCap);
        paint.setStrokeWidth(previousStroke);
        paint.setStyle(previousStyle);
        paint.setColor(previousColor);
    }

    /** The tick, proportioned to the box so it scales with the chat text size. */
    private static Path check(RectF box) {
        Path path = new Path();
        path.moveTo(box.left + box.width() * 0.26f, box.top + box.height() * 0.52f);
        path.lineTo(box.left + box.width() * 0.44f, box.top + box.height() * 0.70f);
        path.lineTo(box.left + box.width() * 0.76f, box.top + box.height() * 0.31f);
        return path;
    }

    private static float side(Paint paint) {
        return Math.round(paint.getTextSize() * SIZE_RATIO);
    }
}

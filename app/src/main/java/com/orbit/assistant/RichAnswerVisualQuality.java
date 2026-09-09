package com.orbit.assistant;

import android.graphics.Bitmap;

/**
 * Whether a picture Orbit downloaded is a photograph of something or a piece of graphic design.
 *
 * <p><b>The failure this closes.</b> Beta 7 taught Rich Answers to ask "is this the same
 * photograph as the one I already have", and it answers that well. Beta 8 sent Orbit to the web and
 * it came back with real Mallard photographs. What the device then produced was the question
 * neither of those asks: "show me pics of a mallard duck" returned one genuine Mallard and, beside
 * it, a large near-empty card carrying a small blue cube. That cube was a different image from the
 * duck, it was large enough, and it decoded cleanly. It was also completely useless.
 * <b>Different is not useful</b>, and a second picture that is not a photograph is worse than no
 * second picture at all.
 *
 * <p><b>What this is not.</b> It does not know what a duck is and it never will. There is no model
 * here, no dependency, no network and nothing learned - subject recognition would mean another
 * request per candidate to decide something that is not actually the question. The question is far
 * cruder and entirely local: does this bitmap have the structure a photograph has, or is it a small
 * shape sitting on a flat field.
 *
 * <p><b>Three numbers, read off one small grid.</b> The decoded picture is reduced to a
 * {@link #GRID}x{@link #GRID} sample of luma and coarse colour, the same way
 * {@link RichAnswerVisualIdentity} reduces one, and three things are measured:
 *
 * <ol>
 *   <li><b>Content coverage.</b> The most common brightness in the grid is taken as the background,
 *       and the cells that are not it are the content. A photograph fills its frame; an icon
 *       occupies a corner of one.</li>
 *   <li><b>Texture coverage.</b> How many neighbouring cells differ appreciably. A photograph varies
 *       almost everywhere, even across its sky; a flat graphic varies only along the border of the
 *       shape drawn on it.</li>
 *   <li><b>Colour spread.</b> How many coarse colours the picture uses at all. A rendered interface
 *       asset uses two or three.</li>
 * </ol>
 *
 * <p><b>Deliberately hard to fail.</b> Refusing a real photograph costs the user the picture they
 * asked for, so a picture is only called a graphic when it is flat <em>and</em> untextured, or
 * essentially blank. A white studio background, a shallow depth of field, a close-up, a dark
 * exposure and a simple subject all pass, because all of them still carry texture across most of
 * the frame. This is here to catch the blue cube, not to become a photography critic.
 *
 * <p>Pure arithmetic on a bitmap that has already been fetched and decoded. Nothing is kept, and
 * what leaves this class is three numbers and a yes or no.
 */
public final class RichAnswerVisualQuality {

    /** The side of the grid every picture is reduced to before anything is measured. */
    static final int GRID = 24;

    /** How many source pixels are read along each axis, at most. A bound, not a quality setting. */
    static final int MAX_SAMPLES_PER_AXIS = 192;

    /** How wide a brightness bucket is, out of 255. Eight levels; finer than that is noise. */
    static final int LUMA_BUCKET = 8;

    /** How far from the modal bucket a cell may sit and still count as background. */
    static final int BACKGROUND_SPREAD = 1;

    /** How much two neighbouring cells must differ before that counts as texture. */
    static final int TEXTURE_DELTA = 8;

    /** Levels per colour channel in the coarse colour histogram. Six is 216 buckets. */
    static final int COLOUR_LEVELS = 6;

    /**
     * The brightness range a picture must span before it is a picture of anything.
     *
     * <p>Under this it is a solid fill, a blank placeholder, or a decode that produced nothing.
     */
    static final int MIN_SPAN = 10;

    /**
     * The most of the frame a graphic's content occupies.
     *
     * <p>Twenty-two percent. A photograph does not have one flat tone covering four fifths of it; a
     * badge, an icon or a loading card does, and that is the whole shape of the blue cube sitting
     * on its empty background.
     */
    static final double MAX_GRAPHIC_CONTENT = 0.22;

    /**
     * The most texture a graphic may carry.
     *
     * <p>Twelve percent of neighbouring pairs. A flat field with one shape on it varies only along
     * that shape's border. A photograph of a duck on still water is well past this before the duck
     * is reached, which is why this is an <em>and</em> with the coverage rule rather than an or.
     */
    static final double MAX_GRAPHIC_TEXTURE = 0.12;

    /** The fewest coarse colours a picture may use before it was drawn rather than taken. */
    static final int MIN_GRAPHIC_COLOURS = 3;

    private RichAnswerVisualQuality() {}

    /** What one decoded picture measures, kept together so a test can read the numbers. */
    public static final class Measurement {

        /** What an undecodable or missing picture produces. Nothing is refused on this alone. */
        static final Measurement NONE = new Measurement(255, 1.0, 1.0, COLOUR_LEVELS);

        /** How far the brightest and darkest cells are apart, out of 255. */
        public final int span;
        /** The share of the frame that is not the single most common tone, 0 to 1. */
        public final double contentRatio;
        /** The share of neighbouring cell pairs that differ appreciably, 0 to 1. */
        public final double textureRatio;
        /** How many coarse colours appear at all. */
        public final int colours;

        Measurement(int span, double contentRatio, double textureRatio, int colours) {
            this.span = span;
            this.contentRatio = contentRatio;
            this.textureRatio = textureRatio;
            this.colours = colours;
        }

        /** Whether this reads as a graphic rather than as a photograph. */
        public boolean isGraphic() {
            if (span < MIN_SPAN) return true;
            if (contentRatio <= MAX_GRAPHIC_CONTENT && textureRatio <= MAX_GRAPHIC_TEXTURE) {
                return true;
            }
            return colours <= MIN_GRAPHIC_COLOURS && textureRatio <= MAX_GRAPHIC_TEXTURE;
        }
    }

    /**
     * Why this picture is not a photograph, or {@link RichAnswerTrace.Reason#NONE}.
     *
     * <p>Never throws. A picture that cannot be measured is not refused: an unreadable bitmap is
     * treated as acceptable, because the cost of being wrong in that direction is one mediocre
     * picture and the cost of the other is a correct answer nobody ever sees.
     */
    public static RichAnswerTrace.Reason judge(Bitmap bitmap) {
        return measure(bitmap).isGraphic()
                ? RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER
                : RichAnswerTrace.Reason.NONE;
    }

    /** Whether this picture carries the structure a photograph carries. */
    public static boolean isPhotographLike(Bitmap bitmap) {
        return judge(bitmap) == RichAnswerTrace.Reason.NONE;
    }

    /**
     * The three numbers, read off one decoded picture.
     *
     * <p>Exposed because a threshold argued over in the abstract is a threshold nobody can check. A
     * test that says "this fixture measures four percent content and one percent texture" is a
     * statement about the fixture; a test that only says "rejected" is a statement about nothing.
     */
    public static Measurement measure(Bitmap bitmap) {
        try {
            if (bitmap == null || bitmap.isRecycled()) return Measurement.NONE;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width < 8 || height < 8) return Measurement.NONE;

            int cells = GRID * GRID;
            double[] luma = new double[cells];
            int[] counts = new int[cells];
            double[] red = new double[cells];
            double[] green = new double[cells];
            double[] blue = new double[cells];
            int stepX = Math.max(1, width / MAX_SAMPLES_PER_AXIS);
            int stepY = Math.max(1, height / MAX_SAMPLES_PER_AXIS);
            int[] row = new int[width];

            for (int y = 0; y < height; y += stepY) {
                bitmap.getPixels(row, 0, width, 0, y, width, 1);
                int cellY = (int) ((long) y * GRID / height);
                if (cellY >= GRID) cellY = GRID - 1;
                int base = cellY * GRID;
                for (int x = 0; x < width; x += stepX) {
                    int pixel = row[x];
                    int alpha = (pixel >>> 24) & 0xFF;
                    int r = (pixel >>> 16) & 0xFF;
                    int g = (pixel >>> 8) & 0xFF;
                    int b = pixel & 0xFF;
                    // Composited onto white, exactly as the fingerprint does it, so a transparent
                    // PNG is measured as what a reader would see rather than as a black field.
                    if (alpha < 255) {
                        double a = alpha / 255.0;
                        r = (int) Math.round(r * a + 255 * (1 - a));
                        g = (int) Math.round(g * a + 255 * (1 - a));
                        b = (int) Math.round(b * a + 255 * (1 - a));
                    }
                    int cellX = (int) ((long) x * GRID / width);
                    if (cellX >= GRID) cellX = GRID - 1;
                    int cell = base + cellX;
                    luma[cell] += 0.299 * r + 0.587 * g + 0.114 * b;
                    red[cell] += r;
                    green[cell] += g;
                    blue[cell] += b;
                    counts[cell]++;
                }
            }

            for (int i = 0; i < cells; i++) {
                if (counts[i] > 0) {
                    luma[i] /= counts[i];
                    red[i] /= counts[i];
                    green[i] /= counts[i];
                    blue[i] /= counts[i];
                } else if (i > 0) {
                    // A cell no sample landed in borrows its neighbour, which only happens on a
                    // picture narrower than the grid, and keeps the row continuous.
                    luma[i] = luma[i - 1];
                    red[i] = red[i - 1];
                    green[i] = green[i - 1];
                    blue[i] = blue[i - 1];
                }
            }
            return summarize(luma, red, green, blue);
        } catch (Throwable ignored) {
            return Measurement.NONE;
        }
    }

    private static Measurement summarize(double[] luma, double[] red, double[] green, double[] blue) {
        int cells = luma.length;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int buckets = 256 / LUMA_BUCKET + 1;
        int[] histogram = new int[buckets];
        for (double cell : luma) {
            if (cell < min) min = cell;
            if (cell > max) max = cell;
            int bucket = (int) (cell / LUMA_BUCKET);
            histogram[Math.max(0, Math.min(buckets - 1, bucket))]++;
        }

        int modal = 0;
        for (int i = 1; i < buckets; i++) if (histogram[i] > histogram[modal]) modal = i;
        int background = 0;
        for (int i = Math.max(0, modal - BACKGROUND_SPREAD);
             i <= Math.min(buckets - 1, modal + BACKGROUND_SPREAD); i++) {
            background += histogram[i];
        }

        int textured = 0;
        int pairs = 0;
        for (int y = 0; y < GRID; y++) {
            for (int x = 0; x < GRID; x++) {
                int at = y * GRID + x;
                if (x + 1 < GRID) {
                    pairs++;
                    if (Math.abs(luma[at] - luma[at + 1]) >= TEXTURE_DELTA) textured++;
                }
                if (y + 1 < GRID) {
                    pairs++;
                    if (Math.abs(luma[at] - luma[at + GRID]) >= TEXTURE_DELTA) textured++;
                }
            }
        }

        boolean[] seen = new boolean[COLOUR_LEVELS * COLOUR_LEVELS * COLOUR_LEVELS];
        int colours = 0;
        for (int i = 0; i < cells; i++) {
            int bucket = quantize(red[i]) * COLOUR_LEVELS * COLOUR_LEVELS
                    + quantize(green[i]) * COLOUR_LEVELS
                    + quantize(blue[i]);
            if (!seen[bucket]) {
                seen[bucket] = true;
                colours++;
            }
        }

        int span = (int) Math.round(max - min);
        double contentRatio = 1.0 - (background / (double) cells);
        double textureRatio = pairs == 0 ? 0 : textured / (double) pairs;
        return new Measurement(span, contentRatio, textureRatio, colours);
    }

    private static int quantize(double channel) {
        int level = (int) (channel * COLOUR_LEVELS / 256.0);
        return Math.max(0, Math.min(COLOUR_LEVELS - 1, level));
    }
}

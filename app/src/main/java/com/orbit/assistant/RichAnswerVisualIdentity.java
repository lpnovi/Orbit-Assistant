package com.orbit.assistant;

import android.graphics.Bitmap;

import java.util.Locale;

/**
 * Whether two pictures Orbit has actually downloaded are the same photograph.
 *
 * <p><b>The bug this exists to close.</b> Beta 6 could already tell that
 * {@code .../1920px-Mallard-Duck.jpg} and {@code .../960px-Mallard-Duck.jpg} were one duck, because
 * the two addresses differ only in a rendering instruction and {@link RichAnswerAssetIdentity} can
 * read that out of the text. On a real device the failure that survived was the one text cannot
 * reach: a CDN serving the same photograph under a completely unrelated address, and a plural
 * request answering with the same Mallard twice. Two addresses that share nothing still name one
 * picture, and the only thing that can prove it is the picture.
 *
 * <p><b>What this is not.</b> It is image deduplication, not subject recognition. Two genuinely
 * different photographs of a mallard are two photographs, and nothing here may merge them because
 * both contain a duck. The comparison is over a normalised gradient of the decoded picture, which
 * is a statement about <em>this image</em> and not about what is in it.
 *
 * <p><b>Why a perceptual hash and not a checksum.</b> A SHA-256 of the encoded bytes answers a
 * different and useless question. The same photograph re-encoded by a CDN, saved at another
 * quality, stripped of its metadata or served at a second width produces completely different
 * bytes, so a byte digest calls one duck two ducks every single time. The picture has to be decoded
 * and compared as a picture.
 *
 * <p><b>The algorithm.</b> A difference hash, twice.
 *
 * <ol>
 *   <li>The decoded bitmap is reduced to a {@link #GRID}x{@link #GRID} grid of luma, box-averaged
 *       so that a 1920-wide and a 960-wide copy of one photograph land on nearly the same numbers.
 *       Colour, exact size and compression noise are all gone by this point.</li>
 *   <li>That grid is resampled to 9x8 and to 8x9, and each is turned into 64 bits by asking whether
 *       each cell is brighter than its neighbour - to the right for the first, below for the
 *       second. Comparing neighbours rather than an average is what makes it survive a brightness
 *       or contrast shift.</li>
 *   <li>Two fingerprints are the same photograph when at most {@link #MAX_DISTANCE} of those 128
 *       bits disagree.</li>
 * </ol>
 *
 * <p><b>A featureless picture is never a duplicate of anything.</b> A flat or near-flat image has
 * no gradient to hash, so every one of them would collide with every other one - and merging on no
 * evidence is exactly how a genuinely different photograph gets thrown away. Below
 * {@link #MIN_CONTRAST} the fingerprint is marked weak and {@link #sameImage} refuses to answer
 * yes, whatever it is compared with.
 *
 * <p>Pure arithmetic. No network, no {@code Context}, no dependency, no model, nothing persisted
 * and no pixel of the picture kept: what leaves this class is 128 bits and a flag.
 */
public final class RichAnswerVisualIdentity {

    /**
     * The side of the normalised grid every picture is reduced to before anything is compared.
     *
     * <p>Thirty-two. Large enough that the 9x8 and 8x9 hashes are resampled from real structure
     * rather than from eight numbers, and small enough that the whole normalisation is a kilobyte
     * of doubles however big the picture was.
     */
    static final int GRID = 32;

    /**
     * How many source pixels are read along each axis, at most.
     *
     * <p>A bound rather than a quality setting. {@link RemoteImageLoader} already decodes to at
     * most 1800 on the long edge, and averaging 256 samples into each of 32 rows is far more
     * agreement than the comparison needs, so reading every pixel of a large photograph would be
     * work spent for no change in the answer.
     */
    static final int MAX_SAMPLES_PER_AXIS = 256;

    /** Bits in one fingerprint: 64 horizontal, 64 vertical. */
    public static final int HASH_BITS = 128;

    /**
     * How many of those 128 bits may disagree and still be one photograph.
     *
     * <p>Ten, and deliberately nearer the strict end. This has to survive a resize, a re-encode and
     * a CDN rendition, which move a handful of bits where two neighbouring cells were already close
     * to equal; it must not stretch as far as two different photographs of the same subject, which
     * disagree on tens of bits. Missing a duplicate costs one repeated picture in an answer that
     * then shows one. Inventing one costs a genuinely different photograph the user never sees, and
     * that is the more expensive mistake.
     */
    public static final int MAX_DISTANCE = 10;

    /**
     * The brightness range a normalised grid must span before it is worth hashing, out of 255.
     *
     * <p>Twelve. Under it there is no gradient - a solid colour, a blank placeholder, a decode that
     * produced nothing - and the bits below would be noise rather than identity.
     */
    static final int MIN_CONTRAST = 12;

    private RichAnswerVisualIdentity() {}

    /**
     * The visual identity of one decoded picture.
     *
     * <p>Immutable, tiny and safe to keep: two longs and a flag. It cannot be turned back into the
     * picture, and it is never written to storage.
     */
    public static final class Fingerprint {

        /** What an undecodable, missing or featureless picture produces. Matches nothing. */
        public static final Fingerprint NONE = new Fingerprint(0L, 0L, false);

        final long horizontal;
        final long vertical;
        private final boolean strong;

        Fingerprint(long horizontal, long vertical, boolean strong) {
            this.horizontal = horizontal;
            this.vertical = vertical;
            this.strong = strong;
        }

        /** Whether this fingerprint carries enough structure to be compared at all. */
        public boolean isStrong() { return strong; }

        /**
         * A short, non-reversible label for diagnostics, or empty when there is nothing to name.
         *
         * <p>Four hex characters of the horizontal hash. Enough to see at a glance that two rows of
         * a report are talking about the same picture, and far too little to reconstruct anything.
         * It is a reading aid: the decision itself is {@link #sameImage}, never this string.
         */
        public String token() {
            if (!strong) return "";
            return String.format(Locale.US, "%04X", (horizontal >>> 48) & 0xFFFFL) + "…";
        }
    }

    /**
     * The fingerprint of a decoded picture, or {@link Fingerprint#NONE}.
     *
     * <p>Never throws. A recycled bitmap, an unreadable one, or one too small to have a gradient
     * comes back as a fingerprint that matches nothing, which is the safe direction: an unknown
     * picture is treated as a new picture rather than as a copy of one already accepted.
     */
    public static Fingerprint fingerprint(Bitmap bitmap) {
        try {
            if (bitmap == null || bitmap.isRecycled()) return Fingerprint.NONE;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width < 8 || height < 8) return Fingerprint.NONE;

            double[] grid = normalize(bitmap, width, height);
            if (grid == null) return Fingerprint.NONE;

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (double cell : grid) {
                if (cell < min) min = cell;
                if (cell > max) max = cell;
            }
            if (max - min < MIN_CONTRAST) return Fingerprint.NONE;

            long horizontal = horizontalHash(resample(grid, GRID, GRID, 9, 8), 9, 8);
            long vertical = verticalHash(resample(grid, GRID, GRID, 8, 9), 8, 9);
            return new Fingerprint(horizontal, vertical, true);
        } catch (Throwable ignored) {
            // A fingerprint that cannot be taken is not a failed answer, and it is certainly not a
            // duplicate. The picture goes on to be judged on everything else.
            return Fingerprint.NONE;
        }
    }

    /**
     * Whether two fingerprints are the same photograph.
     *
     * <p>Both have to be strong. A weak fingerprint is the absence of evidence, and the absence of
     * evidence never merges two pictures.
     */
    public static boolean sameImage(Fingerprint first, Fingerprint second) {
        if (first == null || second == null) return false;
        if (!first.isStrong() || !second.isStrong()) return false;
        return distance(first, second) <= MAX_DISTANCE;
    }

    /**
     * How many of the 128 bits disagree, or {@link #HASH_BITS} when either side has none.
     *
     * <p>Exposed because a number is far easier to reason about in a test than a boolean, and
     * because "how close was it" is the question anybody weighing {@link #MAX_DISTANCE} asks first.
     */
    public static int distance(Fingerprint first, Fingerprint second) {
        if (first == null || second == null) return HASH_BITS;
        if (!first.isStrong() || !second.isStrong()) return HASH_BITS;
        return Long.bitCount(first.horizontal ^ second.horizontal)
                + Long.bitCount(first.vertical ^ second.vertical);
    }

    // ---- normalisation ---------------------------------------------------------------------------

    /**
     * One picture as a {@link #GRID}x{@link #GRID} grid of luma, 0-255.
     *
     * <p>Box-averaged rather than sampled, which is the whole reason a resize survives: every
     * source pixel that falls inside a cell contributes to it, so halving the width of a photograph
     * changes how many pixels each cell averages and not what the average is. Rows are read one at
     * a time, so a large picture never costs more than one row of ints.
     */
    private static double[] normalize(Bitmap bitmap, int width, int height) {
        double[] sums = new double[GRID * GRID];
        int[] counts = new int[GRID * GRID];
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
                // Transparency is composited onto white rather than ignored, so a PNG with a clear
                // background is compared as what it looks like rather than as a black square.
                if (alpha < 255) {
                    double a = alpha / 255.0;
                    r = (int) Math.round(r * a + 255 * (1 - a));
                    g = (int) Math.round(g * a + 255 * (1 - a));
                    b = (int) Math.round(b * a + 255 * (1 - a));
                }
                int cellX = (int) ((long) x * GRID / width);
                if (cellX >= GRID) cellX = GRID - 1;
                int cell = base + cellX;
                sums[cell] += 0.299 * r + 0.587 * g + 0.114 * b;
                counts[cell]++;
            }
        }

        double[] grid = new double[GRID * GRID];
        for (int i = 0; i < grid.length; i++) {
            // A cell no sample landed in borrows its left neighbour, which only happens on a
            // picture narrower than the grid and keeps the row continuous rather than punching a
            // black hole into it.
            grid[i] = counts[i] > 0 ? sums[i] / counts[i] : (i > 0 ? grid[i - 1] : 0);
        }
        return grid;
    }

    /**
     * A grid resampled to another size by area-weighted averaging.
     *
     * <p>Used because 32 divides neither 9 nor 8 evenly and a nearest-neighbour reduction would
     * throw away a third of the rows, which is exactly the kind of arbitrary choice that makes one
     * rendition of a photograph hash differently from another.
     */
    static double[] resample(double[] grid, int gridWidth, int gridHeight, int width, int height) {
        double[] out = new double[width * height];
        for (int y = 0; y < height; y++) {
            double top = (double) y * gridHeight / height;
            double bottom = (double) (y + 1) * gridHeight / height;
            for (int x = 0; x < width; x++) {
                double left = (double) x * gridWidth / width;
                double right = (double) (x + 1) * gridWidth / width;
                double total = 0;
                double weight = 0;
                for (int sy = (int) Math.floor(top); sy < Math.ceil(bottom) && sy < gridHeight; sy++) {
                    double spanY = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    if (spanY <= 0) continue;
                    for (int sx = (int) Math.floor(left); sx < Math.ceil(right) && sx < gridWidth; sx++) {
                        double spanX = Math.min(right, sx + 1) - Math.max(left, sx);
                        if (spanX <= 0) continue;
                        double area = spanX * spanY;
                        total += grid[sy * gridWidth + sx] * area;
                        weight += area;
                    }
                }
                out[y * width + x] = weight > 0 ? total / weight : 0;
            }
        }
        return out;
    }

    /** Sixty-four bits: whether each cell is brighter than the one to its right. */
    static long horizontalHash(double[] grid, int width, int height) {
        long bits = 0L;
        int bit = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x + 1 < width; x++) {
                if (grid[y * width + x] > grid[y * width + x + 1]) bits |= 1L << bit;
                bit++;
            }
        }
        return bits;
    }

    /** Sixty-four bits: whether each cell is brighter than the one below it. */
    static long verticalHash(double[] grid, int width, int height) {
        long bits = 0L;
        int bit = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y + 1 < height; y++) {
                if (grid[y * width + x] > grid[(y + 1) * width + x]) bits |= 1L << bit;
                bit++;
            }
        }
        return bits;
    }
}

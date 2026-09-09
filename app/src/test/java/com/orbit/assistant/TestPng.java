package com.orbit.assistant;

import java.io.ByteArrayOutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * A genuinely valid PNG of any size, built by hand for tests.
 *
 * <p>Needed because the two obvious ways to get one are both unavailable. {@code java.awt} and
 * {@code javax.imageio} are hidden by the Android compile classpath, and {@code Bitmap.compress}
 * under Robolectric produces no real encoded bytes at all - so a test that wants to prove "this
 * picture loads" has nothing to hand the decoder. Robolectric decodes through ImageIO at runtime,
 * which means real PNG bytes work and invented ones correctly do not.
 *
 * <p>Writing the format is a couple of dozen lines: signature, {@code IHDR}, one deflated
 * {@code IDAT}, {@code IEND}. Being able to choose the dimensions matters, because several rules
 * under test - the sampling bound, and whether a picture is large enough to be worth drawing at
 * all - are answers about size.
 */
final class TestPng {

    private static final byte[] SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private TestPng() {}

    /** A solid mid-grey RGB PNG of exactly these dimensions. */
    static byte[] rgb(int width, int height) {
        return encode(width, height, flat(width, height));
    }

    /**
     * A picture with actual structure in it: the same "photograph" at any size.
     *
     * <p>Needed by everything that tests {@link RichAnswerVisualIdentity}, because
     * {@link #rgb} is a solid colour and a solid colour has no gradient to fingerprint. The pattern
     * is defined in normalised coordinates and is deliberately low-frequency, so one seed rendered
     * at 1920 wide and at 640 wide really is one photograph at two sizes rather than two pictures
     * that happen to share a formula - which is exactly the thing under test.
     *
     * <p>Different seeds are different photographs. They are built to look alike in the ways that
     * must not fool a fingerprint - the same palette, the same brightness range, the same smooth
     * composition - and to differ in structure, which is the only thing the hash reads.
     */
    static byte[] photo(int width, int height, int seed) {
        return encode(width, height, pattern(width, height, seed, 0));
    }

    /**
     * The same photograph, degraded the way a re-encode degrades one.
     *
     * <p>A genuine JPEG round trip is not available here: {@code Bitmap.compress} produces nothing
     * real under Robolectric, and {@code javax.imageio} is off the Android compile classpath. What
     * a re-encode does to a picture perceptually is nudge every pixel by a few levels, so that is
     * what this does, deterministically and by a bounded amount.
     */
    static byte[] recompressed(int width, int height, int seed, int amplitude) {
        return encode(width, height, pattern(width, height, seed, amplitude));
    }

    /**
     * The blue cube: a small solid shape centred on a large flat field.
     *
     * <p>The fixture for Beta 9. What the device produced beside a real Mallard photograph was
     * exactly this shape - a large, valid, technically-large-enough raster that is a piece of
     * interface graphic rather than a picture of anything. It has to be genuinely distinct from the
     * photograph it sits beside, or the perceptual duplicate check would refuse it for the wrong
     * reason and prove nothing.
     *
     * @param coverage how much of each axis the shape occupies, 0 to 1. A cube is about an eighth.
     */
    static byte[] graphic(int width, int height, double coverage) {
        return encode(width, height, shapeOnFlatField(width, height, coverage));
    }

    private static byte[] shapeOnFlatField(int width, int height, double coverage) {
        byte[] raw = new byte[height * (1 + width * 3)];
        double half = Math.max(0.01, Math.min(0.9, coverage)) / 2.0;
        int left = (int) ((0.5 - half) * width);
        int right = (int) ((0.5 + half) * width);
        int top = (int) ((0.5 - half) * height);
        int bottom = (int) ((0.5 + half) * height);
        for (int y = 0; y < height; y++) {
            int row = y * (1 + width * 3);
            raw[row] = 0;
            boolean insideY = y >= top && y < bottom;
            for (int x = 0; x < width; x++) {
                boolean shape = insideY && x >= left && x < right;
                // A near-black card carrying one saturated blue block, which is what the phone drew.
                raw[row + 1 + x * 3] = (byte) (shape ? 40 : 22);
                raw[row + 1 + x * 3 + 1] = (byte) (shape ? 96 : 22);
                raw[row + 1 + x * 3 + 2] = (byte) (shape ? 220 : 28);
            }
        }
        return raw;
    }

    private static byte[] flat(int width, int height) {
        byte[] raw = new byte[height * (1 + width * 3)];
        for (int y = 0; y < height; y++) {
            int row = y * (1 + width * 3);
            raw[row] = 0;   // filter type: none
            for (int x = 0; x < width * 3; x++) raw[row + 1 + x] = (byte) 0x80;
        }
        return raw;
    }

    private static byte[] pattern(int width, int height, int seed, int amplitude) {
        byte[] raw = new byte[height * (1 + width * 3)];
        double fx = 1 + Math.abs(seed) % 3;
        double fy = 1 + (Math.abs(seed) / 3) % 3;
        double phaseX = (Math.abs(seed) % 7) / 7.0;
        double phaseY = (Math.abs(seed) % 5) / 5.0;
        for (int y = 0; y < height; y++) {
            int row = y * (1 + width * 3);
            raw[row] = 0;
            double v = (y + 0.5) / height;
            for (int x = 0; x < width; x++) {
                double u = (x + 0.5) / width;
                double a = Math.sin(2 * Math.PI * (fx * u + phaseX))
                        * Math.cos(2 * Math.PI * (fy * v + phaseY));
                double b = Math.sin(2 * Math.PI * ((fx + fy) * (u * 0.5 + v * 0.5) + phaseX * phaseY));
                double value = 128 + 70 * a + 40 * b;
                if (amplitude > 0) value += ((x * 7 + y * 13) % (2 * amplitude + 1)) - amplitude;
                int level = (int) Math.round(Math.max(0, Math.min(255, value)));
                raw[row + 1 + x * 3] = (byte) level;
                raw[row + 1 + x * 3 + 1] = (byte) level;
                raw[row + 1 + x * 3 + 2] = (byte) level;
            }
        }
        return raw;
    }

    private static byte[] encode(int width, int height, byte[] raw) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad size");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(SIGNATURE);

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            writeInt(header, width);
            writeInt(header, height);
            header.write(8);    // bit depth
            header.write(2);    // colour type: truecolour RGB
            header.write(0);    // compression: deflate
            header.write(0);    // filter: adaptive
            header.write(0);    // interlace: none
            writeChunk(out, "IHDR", header.toByteArray());

            writeChunk(out, "IDAT", deflate(raw));
            writeChunk(out, "IEND", new byte[0]);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("could not build a test PNG", e);
        }
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data)
            throws Exception {
        writeInt(out, data.length);
        byte[] name = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }
}

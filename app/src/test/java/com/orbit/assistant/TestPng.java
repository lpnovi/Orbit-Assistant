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

            // One filter byte per scanline, then three bytes per pixel.
            byte[] raw = new byte[height * (1 + width * 3)];
            for (int y = 0; y < height; y++) {
                int row = y * (1 + width * 3);
                raw[row] = 0;   // filter type: none
                for (int x = 0; x < width * 3; x++) raw[row + 1 + x] = (byte) 0x80;
            }
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

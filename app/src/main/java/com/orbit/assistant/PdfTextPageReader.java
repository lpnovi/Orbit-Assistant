package com.orbit.assistant;

import android.graphics.RectF;

import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

/**
 * Reads one PDF page as text, and optionally as text plus the position of every character.
 *
 * <p>One reader for both jobs on purpose. Orbit's search index and its highlight geometry have to
 * agree on the page's text down to the character, because a match is an offset into that string; if
 * two extractions disagree by so much as a stray space, every highlight after that point lands on
 * the wrong word. Sharing the extraction makes them identical by construction rather than by
 * coincidence, and geometry capture is a flag rather than a second code path.
 *
 * <h2>How characters are matched to positions</h2>
 *
 * <p>The stripper does not write its output in one piece: it interleaves runs of glyphs with word,
 * line and paragraph separators of its own. Guessing where those separators land would be fragile,
 * so instead the text is collected through this class's own {@link Writer} and the output length is
 * read immediately before and after each run of glyphs. Whatever the stripper appended in between
 * is exactly that run, wherever it chose to put it, and everything else is left without geometry —
 * which is correct, because a separator is not on the page.
 *
 * <p>Within a run, characters normally correspond one-to-one with text positions. When they do not
 * — a ligature expanding to two characters, a diacritic being decomposed — the run is divided
 * proportionally instead. That keeps a highlight on the right line and word rather than abandoning
 * the page's geometry over a typographic detail.
 */
final class PdfTextPageReader extends PDFTextStripper {

    /** Nothing is written for a character whose position is unknown. */
    private static final float UNKNOWN = Float.NaN;

    private final boolean captureGeometry;
    private final StringBuilder sink = new StringBuilder();

    private float[] boxes = new float[0];
    private float pageWidth;
    private float pageHeight;

    PdfTextPageReader(boolean captureGeometry) throws IOException {
        super();
        this.captureGeometry = captureGeometry;
        // Reading order, not drawing order. Without this a two-column page is extracted as
        // interleaved fragments, which makes both the search text and the highlights nonsense.
        setSortByPosition(true);
    }

    /** Reads one page, one-based, exactly as {@link PdfPageTextExtractor} does. */
    String readPage(com.tom_roush.pdfbox.pdmodel.PDDocument document, int oneBasedPage)
            throws IOException {
        sink.setLength(0);
        boxes = new float[0];
        setStartPage(oneBasedPage);
        setEndPage(oneBasedPage);
        writeText(document, new Writer() {
            @Override public void write(char[] buffer, int offset, int count) {
                sink.append(buffer, offset, count);
            }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return sink.toString();
    }

    /**
     * The geometry captured for the page just read, aligned to the string {@code readPage}
     * returned, then trimmed and truncated the same way the search index trims and truncates it.
     */
    DocumentTextGeometry geometryFor(int zeroBasedPage) {
        String raw = sink.toString();
        if (!captureGeometry) {
            return new DocumentTextGeometry(zeroBasedPage, boundedText(raw), null);
        }
        int lead = leadingWhitespace(raw);
        String bounded = boundedText(raw);
        float[] aligned = new float[bounded.length() * 4];
        Arrays.fill(aligned, UNKNOWN);
        for (int i = 0; i < bounded.length(); i++) {
            int source = (lead + i) * 4;
            if (source + 3 >= boxes.length) break;
            System.arraycopy(boxes, source, aligned, i * 4, 4);
        }
        return new DocumentTextGeometry(zeroBasedPage, bounded, aligned);
    }

    /**
     * The bounding of a page's text, in one place.
     *
     * <p>{@link PdfPageTextExtractor} applies exactly this, and geometry offsets are shifted by the
     * same amount, so the two strings are the same string.
     */
    static String boundedText(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= PdfPageTextExtractor.MAX_PAGE_CHARS
                ? value : value.substring(0, PdfPageTextExtractor.MAX_PAGE_CHARS);
    }

    private static int leadingWhitespace(String raw) {
        if (raw == null) return 0;
        int i = 0;
        while (i < raw.length() && Character.isWhitespace(raw.charAt(i))) i++;
        // A page of nothing but whitespace trims to empty; the offset is then meaningless anyway.
        return i >= raw.length() ? 0 : i;
    }

    // ---- capture ----------------------------------------------------------------------------------

    @Override protected void startPage(PDPage page) throws IOException {
        super.startPage(page);
        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        float width = box == null ? 0f : box.getWidth();
        float height = box == null ? 0f : box.getHeight();
        int rotation = page.getRotation();
        // PDFBox reports text positions in the page's display orientation, so the page size it is
        // measured against has to be the rotated one too.
        pageWidth = DocumentPageMapping.displayWidth(width, height, rotation);
        pageHeight = DocumentPageMapping.displayHeight(width, height, rotation);
    }

    @Override protected void writeString(String text, List<TextPosition> positions)
            throws IOException {
        int start = sink.length();
        super.writeString(text, positions);
        if (!captureGeometry) return;
        record(start, sink.length(), positions);
    }

    /**
     * Geometry is captured only this far into a page.
     *
     * <p>Twice the per-page text ceiling, which leaves room for the leading whitespace a trim
     * removes while keeping the worst case a couple of megabytes rather than unbounded.
     */
    private static final int GEOMETRY_CEILING = DocumentTextGeometry.MAX_CHARS * 2;

    private void record(int start, int end, List<TextPosition> positions) {
        int count = end - start;
        if (count <= 0 || positions == null || positions.isEmpty()) return;
        if (pageWidth <= 0f || pageHeight <= 0f) return;
        if (start >= GEOMETRY_CEILING) return;
        int limit = Math.min(count, GEOMETRY_CEILING - start);
        ensureCapacity(start + limit);

        int spelled = 0;
        for (TextPosition position : positions) spelled += lengthOf(position);

        if (spelled == count) {
            // The ordinary case: each position spelled the characters it contributed, in order.
            int charIndex = 0;
            for (TextPosition position : positions) {
                for (int k = 0; k < lengthOf(position) && charIndex < limit; k++, charIndex++) {
                    writeBox(start + charIndex, position);
                }
            }
            return;
        }
        // A ligature expanded, or a diacritic was decomposed. The run is divided proportionally,
        // which keeps the highlight on the right word even though it is no longer per-character.
        for (int i = 0; i < limit; i++) {
            writeBox(start + i, positions.get((int) Math.min(positions.size() - 1L,
                    (long) i * positions.size() / count)));
        }
    }

    private static int lengthOf(TextPosition position) {
        if (position == null) return 0;
        String unicode = position.getUnicode();
        return unicode == null ? 0 : unicode.length();
    }

    private void writeBox(int charIndex, TextPosition position) {
        if (position == null) return;
        int base = charIndex * 4;
        if (base + 3 >= boxes.length) return;
        RectF box = DocumentPageMapping.normalize(
                position.getXDirAdj(), position.getYDirAdj(),
                position.getWidthDirAdj(), position.getHeightDir(),
                pageWidth, pageHeight);
        if (box == null) return;
        boxes[base] = box.left;
        boxes[base + 1] = box.top;
        boxes[base + 2] = box.right;
        boxes[base + 3] = box.bottom;
    }

    private void ensureCapacity(int characters) {
        int needed = characters * 4;
        if (boxes.length >= needed) return;
        int grown = Math.max(needed, Math.max(1024, boxes.length * 2));
        float[] next = new float[grown];
        Arrays.fill(next, UNKNOWN);
        System.arraycopy(boxes, 0, next, 0, boxes.length);
        boxes = next;
    }
}

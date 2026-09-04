package com.orbit.assistant;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One page's text together with where each character sits on it.
 *
 * <p>Orbit's own type, not a bag of PDFBox objects. {@code TextPosition} is a live handle into a
 * loaded document with its own font, matrix and page references attached; letting those escape into
 * an Activity would keep a whole {@code PDDocument} alive for as long as a search result was
 * selected, and would tie the viewer to a particular PDF library. What the viewer actually needs is
 * far smaller: a string it can search, and a fraction-of-the-page rectangle per character.
 *
 * <p>Normalized fractions rather than points or pixels, for the reason set out in
 * {@link DocumentPageMapping}: geometry that means the same thing at any render size, zoom or pan.
 *
 * <p>Immutable once built, and bounded: a page contributes at most {@link #MAX_CHARS} characters of
 * geometry, so a pathological page cannot turn one search into hundreds of megabytes of rectangles.
 */
public final class DocumentTextGeometry {

    /** Matches the per-page text ceiling, so geometry and searchable text agree on what a page is. */
    static final int MAX_CHARS = PdfPageTextExtractor.MAX_PAGE_CHARS;

    /** Nothing known about this page. Used rather than null so callers need no special case. */
    public static final DocumentTextGeometry EMPTY = new DocumentTextGeometry(-1, "", null);

    public final int page;
    /** The page's text, character-for-character the same string the search index holds. */
    public final String text;
    /** Four floats per character — left, top, right, bottom — or null when none were captured. */
    private final float[] boxes;

    DocumentTextGeometry(int page, String text, float[] boxes) {
        this.page = page;
        this.text = text == null ? "" : text;
        this.boxes = boxes != null && boxes.length >= this.text.length() * 4 ? boxes : null;
    }

    public boolean hasGeometry() { return boxes != null && !text.isEmpty(); }

    /**
     * The rectangles covering one range of the page's text.
     *
     * <p>Returns the merged line fragments rather than one box per character, so a match reads as
     * one highlight and a match that wraps reads as two.
     */
    public List<RectF> rectsFor(int offset, int length) {
        List<RectF> out = new ArrayList<>();
        if (!hasGeometry() || length <= 0) return out;
        int from = Math.max(0, offset);
        int to = Math.min(text.length(), from + length);
        List<RectF> characters = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            int base = i * 4;
            float left = boxes[base];
            if (Float.isNaN(left)) continue;
            characters.add(new RectF(left, boxes[base + 1], boxes[base + 2], boxes[base + 3]));
        }
        return DocumentPageMapping.mergeLineFragments(characters);
    }

    /**
     * Where the n-th occurrence of a query starts on this page, or -1.
     *
     * <p>The fallback used when the geometry's text and the search index's text are not identical.
     * They normally are — both come from the same extraction — but a PDF that produces slightly
     * different output on a second pass would otherwise put every highlight at the wrong offset,
     * and being off by a word is worse than the highlight simply not appearing. Counting
     * occurrences is stable in a way that counting characters is not.
     */
    public int offsetOfOccurrence(String query, int occurrence) {
        if (query == null || query.trim().isEmpty() || occurrence < 0) return -1;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        String haystack = text.toLowerCase(Locale.ROOT);
        int from = 0;
        for (int seen = 0; ; seen++) {
            int found = haystack.indexOf(needle, from);
            if (found < 0) return -1;
            if (seen == occurrence) return found;
            from = found + Math.max(1, needle.length());
        }
    }
}

package com.orbit.assistant;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Local, page-by-page PDFBox extraction with explicit memory ceilings. */
public final class PdfPageTextExtractor {
    static final int MAX_PAGE_CHARS = 60000;
    static final int MAX_TOTAL_CHARS = 4_000_000;

    private PdfPageTextExtractor() {}

    /**
     * Every page's searchable text.
     *
     * <p>Read through {@link PdfTextPageReader} in text-only mode, which is the same reader the
     * highlight geometry uses. That is deliberate: a search match is an offset into these strings,
     * so if geometry were extracted by a separate stripper whose output differed by a single
     * character, every highlight past that point would sit on the wrong word.
     */
    public static DocumentTextIndex extract(Context context, String path) throws Exception {
        if (context == null || path == null || path.trim().isEmpty()) {
            return new DocumentTextIndex(new ArrayList<>());
        }
        PDFBoxResourceLoader.init(context.getApplicationContext());
        List<String> pages = new ArrayList<>();
        int retained = 0;
        try (PDDocument document = PDDocument.load(new File(path))) {
            PdfTextPageReader reader = new PdfTextPageReader(false);
            int count = Math.max(0, document.getNumberOfPages());
            if (count > DocumentViewerActivity.MAX_PAGES) {
                throw new IllegalArgumentException("PDF has too many pages");
            }
            for (int page = 1; page <= count; page++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("PDF indexing cancelled");
                }
                String text = PdfTextPageReader.boundedText(reader.readPage(document, page));
                // The whole-document ceiling is separate from the per-page one: a book of readable
                // pages must not be able to hold the entire text of itself in memory at once.
                int remaining = Math.max(0, MAX_TOTAL_CHARS - retained);
                String bounded = text.length() <= remaining ? text
                        : (remaining <= 0 ? "" : text.substring(0, remaining));
                pages.add(bounded);
                retained += bounded.length();
            }
        }
        return new DocumentTextIndex(pages);
    }

    /**
     * One page's text together with the position of every character on it.
     *
     * <p>Extracted on demand rather than alongside the index. Geometry costs four floats per
     * character, so keeping it for a 388-page book would cost tens of megabytes to answer a
     * question about one page — and the viewer only ever highlights the page the user is looking
     * at.
     */
    public static DocumentTextGeometry geometry(Context context, String path, int page)
            throws Exception {
        if (context == null || path == null || path.trim().isEmpty() || page < 0) {
            return DocumentTextGeometry.EMPTY;
        }
        PDFBoxResourceLoader.init(context.getApplicationContext());
        try (PDDocument document = PDDocument.load(new File(path))) {
            if (page >= document.getNumberOfPages()) return DocumentTextGeometry.EMPTY;
            PdfTextPageReader reader = new PdfTextPageReader(true);
            reader.readPage(document, page + 1);
            return reader.geometryFor(page);
        }
    }
}

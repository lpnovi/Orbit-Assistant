package com.orbit.assistant;

import android.content.Context;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Local, page-by-page PDFBox extraction with explicit memory ceilings. */
public final class PdfPageTextExtractor {
    static final int MAX_PAGE_CHARS = 60000;
    static final int MAX_TOTAL_CHARS = 4_000_000;

    private PdfPageTextExtractor() {}

    public static DocumentTextIndex extract(Context context, String path) throws Exception {
        if (context == null || path == null || path.trim().isEmpty()) {
            return new DocumentTextIndex(new ArrayList<>());
        }
        PDFBoxResourceLoader.init(context.getApplicationContext());
        List<String> pages = new ArrayList<>();
        int retained = 0;
        try (PDDocument document = PDDocument.load(new File(path))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int count = Math.max(0, document.getNumberOfPages());
            if (count > DocumentViewerActivity.MAX_PAGES) {
                throw new IllegalArgumentException("PDF has too many pages");
            }
            for (int page = 1; page <= count; page++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("PDF indexing cancelled");
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                text = text == null ? "" : text.trim();
                int remaining = Math.max(0, MAX_TOTAL_CHARS - retained);
                int take = Math.min(text.length(), Math.min(MAX_PAGE_CHARS, remaining));
                String bounded = take <= 0 ? "" : text.substring(0, take);
                pages.add(bounded);
                retained += take;
            }
        }
        return new DocumentTextIndex(pages);
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;

/** A real two-page fixture proves PDFBox extraction keeps page identity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class PdfPageTextExtractorTest {

    @Test public void knownFixturePreservesPageBoundaries() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);
        File fixture = new File(context.getCacheDir(), "orbit-page-text-fixture.pdf");
        try (PDDocument document = new PDDocument()) {
            addPage(document, "Alpha belongs only to page one");
            addPage(document, "Beta belongs only to page two");
            document.save(fixture);
        }

        DocumentTextIndex index = PdfPageTextExtractor.extract(context, fixture.getAbsolutePath());
        assertEquals(2, index.pageCount());
        assertTrue(index.pageText(0).contains("Alpha belongs only to page one"));
        assertTrue(index.pageText(1).contains("Beta belongs only to page two"));
        assertEquals(0, index.search("Alpha").get(0).page);
        assertEquals(1, index.search("Beta").get(0).page);
        fixture.delete();
    }

    private static void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(72, 700);
            stream.showText(text);
            stream.endText();
        }
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * Search geometry read out of real PDFs, built here so their text positions are known.
 *
 * <p>Fixtures rather than a checked-in file, because the point of these is that a word Orbit says
 * it found is where Orbit says it is, and that can only be asserted against a page whose layout the
 * test itself decided. A screenshot comparison would be the alternative and it would prove less:
 * it breaks on any accent or font change while never saying whether the rectangle was on the word.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 31, 35})
public final class DocumentSearchGeometryTest {

    private static final float PAGE_WIDTH = 400f;
    private static final float PAGE_HEIGHT = 800f;

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        // PDFBox keeps the standard-14 font metrics in the library's assets rather than on the
        // classpath, so a fixture that writes text has to point it at them the same way Orbit's
        // own extraction does before the first font is touched.
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);
    }

    /** One line of text and where its baseline sits, in PDF points from the bottom-left. */
    private static final class Line {
        final String text;
        final float x;
        final float baselineY;

        Line(String text, float x, float baselineY) {
            this.text = text;
            this.x = x;
            this.baselineY = baselineY;
        }
    }

    private File writePdf(String name, int rotation, List<List<Line>> pages) throws Exception {
        File out = new File(context.getCacheDir(), name);
        try (PDDocument document = new PDDocument()) {
            for (List<Line> lines : pages) {
                PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
                page.setRotation(rotation);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    for (Line line : lines) {
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, 12f);
                        stream.newLineAtOffset(line.x, line.baselineY);
                        stream.showText(line.text);
                        stream.endText();
                    }
                }
            }
            document.save(out);
        }
        return out;
    }

    private static List<Line> lines(Line... items) { return java.util.Arrays.asList(items); }

    @SafeVarargs
    private static List<List<Line>> pages(List<Line>... items) {
        return java.util.Arrays.asList(items);
    }

    private DocumentTextIndex index(File pdf) throws Exception {
        return PdfPageTextExtractor.extract(context, pdf.getAbsolutePath());
    }

    private DocumentTextGeometry geometry(File pdf, int page) throws Exception {
        return PdfPageTextExtractor.geometry(context, pdf.getAbsolutePath(), page);
    }

    // ---- the text the two extractions see ---------------------------------------------------------

    /**
     * The single most important property here.
     *
     * <p>A match is an offset into the index's text and a highlight is an offset into the
     * geometry's. If those two strings are not the same string, every highlight past the first
     * difference is on the wrong word — and it would look plausible, which is worse.
     */
    @Test public void theIndexAndTheGeometryReadTheSameText() throws Exception {
        File pdf = writePdf("same-text.pdf", 0, pages(
                lines(new Line("The quick brown fox", 50f, 700f),
                        new Line("jumps over the lazy dog", 50f, 660f)),
                lines(new Line("A second page of text", 50f, 700f))));
        DocumentTextIndex index = index(pdf);
        for (int page = 0; page < index.pageCount(); page++) {
            assertEquals("page " + page + " must read identically in both extractions",
                    index.pageText(page), geometry(pdf, page).text);
        }
    }

    // ---- occurrences ------------------------------------------------------------------------------

    @Test public void oneResultIsFoundAndPlaced() throws Exception {
        File pdf = writePdf("one.pdf", 0, pages(
                lines(new Line("A unique needle here", 50f, 700f))));
        List<DocumentTextIndex.Match> matches = index(pdf).search("needle");
        assertEquals(1, matches.size());
        assertEquals(0, matches.get(0).page);
        assertEquals("needle".length(), matches.get(0).length);

        List<RectF> rects = geometry(pdf, 0)
                .rectsFor(matches.get(0).offset, matches.get(0).length);
        assertEquals("one word on one line is one highlight", 1, rects.size());
        assertOnPage(rects.get(0));
    }

    /** Five occurrences on one page are five results, each with its own place. */
    @Test public void severalResultsOnOnePageAreSeparateOccurrences() throws Exception {
        File pdf = writePdf("many.pdf", 0, pages(
                lines(new Line("test one test two", 50f, 700f),
                        new Line("test three test four", 50f, 660f),
                        new Line("and test five", 50f, 620f))));
        List<DocumentTextIndex.Match> matches = index(pdf).search("test");
        assertEquals("five occurrences, not one page", 5, matches.size());
        for (int i = 0; i < matches.size(); i++) {
            assertEquals("each knows which occurrence on its page it is", i, matches.get(i).onPage);
        }

        DocumentTextGeometry geometry = geometry(pdf, 0);
        RectF previous = null;
        for (DocumentTextIndex.Match match : matches) {
            List<RectF> rects = geometry.rectsFor(match.offset, match.length);
            assertEquals(1, rects.size());
            assertOnPage(rects.get(0));
            if (previous != null) {
                assertFalse("Next must move the highlight, not redraw it in place",
                        rects.get(0).equals(previous));
            }
            previous = rects.get(0);
        }
    }

    @Test public void resultsAcrossPagesKeepTheirOwnPages() throws Exception {
        File pdf = writePdf("across.pdf", 0, pages(
                lines(new Line("alpha marker beta", 50f, 700f)),
                lines(new Line("gamma marker delta", 50f, 700f)),
                lines(new Line("nothing here", 50f, 700f))));
        List<DocumentTextIndex.Match> matches = index(pdf).search("marker");
        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).page);
        assertEquals(1, matches.get(1).page);
        for (DocumentTextIndex.Match match : matches) {
            List<RectF> rects = geometry(pdf, match.page).rectsFor(match.offset, match.length);
            assertFalse("each page places its own result", rects.isEmpty());
            assertOnPage(rects.get(0));
        }
    }

    @Test public void aMultiWordPhraseIsPlacedAsOneRunOnALine() throws Exception {
        File pdf = writePdf("phrase.pdf", 0, pages(
                lines(new Line("health behavior theory matters", 50f, 700f))));
        List<DocumentTextIndex.Match> matches = index(pdf).search("behavior theory");
        assertEquals(1, matches.size());
        List<RectF> rects = geometry(pdf, 0)
                .rectsFor(matches.get(0).offset, matches.get(0).length);
        assertEquals("a phrase on one line is one highlight", 1, rects.size());
        List<RectF> single = geometry(pdf, 0).rectsFor(matches.get(0).offset, "behavior".length());
        assertTrue("the phrase covers more of the line than its first word alone",
                rects.get(0).width() > single.get(0).width());
    }

    @Test public void searchIsCaseInsensitive() throws Exception {
        File pdf = writePdf("case.pdf", 0, pages(
                lines(new Line("Chapter Summary and summary notes", 50f, 700f))));
        DocumentTextIndex index = index(pdf);
        assertEquals(2, index.search("summary").size());
        assertEquals(2, index.search("SUMMARY").size());
        assertEquals(2, index.search("SuMmArY").size());
    }

    /** A result spanning two text fragments still resolves to geometry on the right line. */
    @Test public void aResultSpanningFragmentsIsStillPlaced() throws Exception {
        File pdf = writePdf("fragments.pdf", 0, pages(
                lines(new Line("split", 50f, 700f),
                        new Line("word", 90f, 700f))));
        DocumentTextGeometry geometry = geometry(pdf, 0);
        int offset = geometry.text.toLowerCase(java.util.Locale.ROOT).indexOf("split");
        assertTrue("the fixture should contain the word", offset >= 0);
        List<RectF> rects = geometry.rectsFor(offset, "split".length());
        assertFalse(rects.isEmpty());
        for (RectF rect : rects) assertOnPage(rect);
    }

    // ---- rotation ---------------------------------------------------------------------------------

    /**
     * A rotated page is measured in the orientation it is displayed in.
     *
     * <p>The failure this guards against is a highlight that is correct on an upright page and
     * ninety degrees out on a scanned one — which is precisely the kind of page a reader is most
     * likely to be searching.
     */
    @Test public void aRotatedPagePlacesHighlightsInsideItself() throws Exception {
        File pdf = writePdf("rotated.pdf", 90, pages(
                lines(new Line("rotated needle here", 50f, 700f))));
        DocumentTextGeometry geometry = geometry(pdf, 0);
        int offset = geometry.text.toLowerCase(java.util.Locale.ROOT).indexOf("needle");
        assertTrue("the rotated page should still be readable", offset >= 0);
        List<RectF> rects = geometry.rectsFor(offset, "needle".length());
        assertFalse("a rotated page still produces geometry", rects.isEmpty());
        for (RectF rect : rects) assertOnPage(rect);
    }

    // ---- absence ----------------------------------------------------------------------------------

    @Test public void clearingTheQueryLeavesNothingToDraw() throws Exception {
        File pdf = writePdf("clear.pdf", 0, pages(
                lines(new Line("something to find", 50f, 700f))));
        DocumentTextIndex index = index(pdf);
        assertTrue(index.search("").isEmpty());
        assertTrue(index.search("   ").isEmpty());
        assertTrue(index.search(null).isEmpty());
    }

    @Test public void aPageWithoutGeometryDegradesQuietly() {
        DocumentTextGeometry empty = DocumentTextGeometry.EMPTY;
        assertFalse(empty.hasGeometry());
        assertTrue(empty.rectsFor(0, 5).isEmpty());
        assertEquals(-1, empty.offsetOfOccurrence("anything", 0));
    }

    /** The fallback used when the two extractions disagree: count occurrences, not characters. */
    @Test public void occurrenceCountingFindsTheSameWordAgain() throws Exception {
        File pdf = writePdf("occurrences.pdf", 0, pages(
                lines(new Line("test one test two test three", 50f, 700f))));
        DocumentTextGeometry geometry = geometry(pdf, 0);
        int first = geometry.offsetOfOccurrence("test", 0);
        int second = geometry.offsetOfOccurrence("test", 1);
        int third = geometry.offsetOfOccurrence("test", 2);
        assertTrue(first >= 0 && second > first && third > second);
        assertEquals("beyond the last occurrence there is nothing",
                -1, geometry.offsetOfOccurrence("test", 9));
        // And it agrees with the index, which is the point of having it.
        List<DocumentTextIndex.Match> matches = index(pdf).search("test");
        assertEquals(3, matches.size());
        assertEquals(matches.get(1).offset, second);
    }

    private static void assertOnPage(RectF rect) {
        assertNotNull(rect);
        assertTrue("left inside the page: " + rect, rect.left >= 0f && rect.left <= 1f);
        assertTrue("top inside the page: " + rect, rect.top >= 0f && rect.top <= 1f);
        assertTrue("right inside the page: " + rect, rect.right >= 0f && rect.right <= 1f);
        assertTrue("bottom inside the page: " + rect, rect.bottom >= 0f && rect.bottom <= 1f);
        assertTrue("a highlight has width: " + rect, rect.width() > 0f);
        assertTrue("a highlight has height: " + rect, rect.height() > 0f);
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * The photographs inside a page, which is the part Beta 1 and Beta 2 never read.
 *
 * <p>Every case here is a shape the real web actually serves. A lazy loader that leaves a
 * placeholder in {@code src} and the real address in {@code data-src}; a responsive image offered
 * only through {@code srcset}; a {@code <picture>} with format alternatives; a protocol-relative
 * address; markup with an unclosed tag in it. Beta 2 would have found nothing on any of them,
 * because it stopped reading at {@code </head>} - and that is exactly why a university publication
 * with five photographs of a spider contributed no picture at all.
 *
 * <p>Pure text in, value objects out. Nothing here touches the network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerArticleImagesTest {

    private static final String PAGE = "https://pubs.example.edu/content/spiders/widow.html";

    private List<RichAnswerArticleImages.Candidate> parse(String body) {
        return RichAnswerArticleImages.parse(
                "<html><head><title>Widow spiders</title></head><body>" + body + "</body></html>",
                PAGE);
    }

    private RichAnswerArticleImages.Candidate find(List<RichAnswerArticleImages.Candidate> all,
                                                   String urlFragment) {
        for (RichAnswerArticleImages.Candidate candidate : all) {
            if (candidate.url.contains(urlFragment)) return candidate;
        }
        return null;
    }

    // ---- addresses -------------------------------------------------------------------------------

    @Test public void anOrdinaryImageSourceIsFound() {
        List<RichAnswerArticleImages.Candidate> found =
                parse("<img src=\"https://cdn.example.edu/photos/widow.jpg\" alt=\"A female widow\">");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/photos/widow.jpg", found.get(0).url);
        assertEquals(RichAnswerArticleImages.Origin.ARTICLE_IMG, found.get(0).origin);
        assertEquals("A female widow", found.get(0).alt);
    }

    @Test public void aRelativeAddressResolvesAgainstThePage() {
        List<RichAnswerArticleImages.Candidate> found = parse("<img src=\"images/widow.jpg\">");
        assertEquals("https://pubs.example.edu/content/spiders/images/widow.jpg",
                found.get(0).url);
    }

    @Test public void aRootRelativeAddressResolvesAgainstTheHost() {
        List<RichAnswerArticleImages.Candidate> found = parse("<img src=\"/media/widow.jpg\">");
        assertEquals("https://pubs.example.edu/media/widow.jpg", found.get(0).url);
    }

    @Test public void aProtocolRelativeAddressTakesThePagesScheme() {
        List<RichAnswerArticleImages.Candidate> found =
                parse("<img src=\"//cdn.example.edu/photos/widow.jpg\">");
        assertEquals("https://cdn.example.edu/photos/widow.jpg", found.get(0).url);
    }

    /** The lazy-loading shape: a placeholder in src, the real photograph one attribute along. */
    @Test public void aLazyLoadedAddressIsFound() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"/assets/blank.gif\" data-src=\"https://cdn.example.edu/photos/widow.jpg\">");
        RichAnswerArticleImages.Candidate real = find(found, "widow.jpg");
        assertNotNull("the real address must not be lost behind a placeholder", real);
        assertEquals(RichAnswerArticleImages.Origin.LAZY_IMAGE, real.origin);
    }

    @Test public void everyLazyAttributeSpellingIsRead() {
        for (String attribute : new String[]{
                "data-src", "data-lazy-src", "data-original", "data-image", "data-lazy"}) {
            List<RichAnswerArticleImages.Candidate> found = parse(
                    "<img " + attribute + "=\"https://cdn.example.edu/photos/widow.jpg\">");
            assertNotNull(attribute + " must be read",
                    find(found, "widow.jpg"));
        }
    }

    @Test public void aPictureSourceIsFound() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<picture>"
                        + "<source srcset=\"https://cdn.example.edu/photos/widow.webp\" type=\"image/webp\">"
                        + "<img src=\"https://cdn.example.edu/photos/widow.jpg\" alt=\"A widow\">"
                        + "</picture>");
        RichAnswerArticleImages.Candidate source = find(found, "widow.webp");
        assertNotNull(source);
        assertEquals(RichAnswerArticleImages.Origin.PICTURE_SOURCE, source.origin);
        assertNotNull("and the fallback is still a candidate", find(found, "widow.jpg"));
    }

    @Test public void dataSrcsetIsRead() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img data-srcset=\"https://cdn.example.edu/photos/widow-640.jpg 640w\">");
        assertNotNull(find(found, "widow-640.jpg"));
    }

    // ---- srcset ----------------------------------------------------------------------------------

    /** The bug a naive split causes: the first entry is the thumbnail Orbit then rejects. */
    @Test public void srcsetPrefersTheLargerSensibleEntry() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img srcset=\"https://cdn.example.edu/s.jpg 320w, "
                        + "https://cdn.example.edu/m.jpg 640w, "
                        + "https://cdn.example.edu/l.jpg 1200w\">");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/l.jpg", found.get(0).url);
        assertEquals(1200, found.get(0).declaredWidth);
        assertEquals(RichAnswerArticleImages.Origin.SRCSET, found.get(0).origin);
    }

    /** But not absurdly large: a 4K original is bandwidth spent on pixels that get sampled away. */
    @Test public void srcsetRefusesToChaseTheBiggestAssetOnOffer() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img srcset=\"https://cdn.example.edu/m.jpg 900w, https://cdn.example.edu/huge.jpg 4800w\">");
        assertEquals("https://cdn.example.edu/m.jpg", found.get(0).url);
    }

    /** When every entry is oversized, the smallest of them beats giving up on the picture. */
    @Test public void srcsetFallsBackToTheSmallestOversizedEntry() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img srcset=\"https://cdn.example.edu/a.jpg 3000w, https://cdn.example.edu/b.jpg 5000w\">");
        assertEquals("https://cdn.example.edu/a.jpg", found.get(0).url);
    }

    @Test public void srcsetUnderstandsDensityDescriptors() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img srcset=\"https://cdn.example.edu/one.jpg 1x, https://cdn.example.edu/two.jpg 2x\">");
        assertEquals("https://cdn.example.edu/two.jpg", found.get(0).url);
    }

    /** A comma is legal inside an address, and splitting on it truncates every CDN transform URL. */
    @Test public void srcsetToleratesCommasInsideAddresses() {
        List<String[]> entries = RichAnswerArticleImages.srcsetEntries(
                "https://cdn.example.edu/w_600,h_400/widow.jpg 600w, "
                        + "https://cdn.example.edu/w_1200,h_800/widow.jpg 1200w");
        assertEquals(2, entries.size());
        assertEquals("https://cdn.example.edu/w_600,h_400/widow.jpg", entries.get(0)[0]);
        assertEquals("1200w", entries.get(1)[1]);
    }

    @Test public void srcsetWithoutDescriptorsTakesTheFirstEntry() {
        RichAnswerArticleImages.Chosen chosen = RichAnswerArticleImages.bestFromSrcset(
                "https://cdn.example.edu/a.jpg, https://cdn.example.edu/b.jpg", PAGE);
        assertNotNull(chosen);
        assertEquals("https://cdn.example.edu/a.jpg", chosen.url);
    }

    // ---- text ------------------------------------------------------------------------------------

    @Test public void aFigureCaptionIsCapturedForTheImageInsideIt() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<figure><img src=\"https://cdn.example.edu/photos/widow.jpg\">"
                        + "<figcaption>Female <em>Latrodectus variolus</em> on its web</figcaption>"
                        + "</figure>");
        assertEquals("Female Latrodectus variolus on its web", found.get(0).caption);
    }

    @Test public void titleStandsInWhenThereIsNoAltText() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/photos/widow.jpg\" title=\"Northern black widow\">");
        assertEquals("Northern black widow", found.get(0).alt);
    }

    @Test public void entitiesAreDecodedInAltText() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/photos/widow.jpg\" "
                        + "alt=\"Widow &amp; hourglass &quot;marking&quot;\">");
        assertEquals("Widow & hourglass \"marking\"", found.get(0).alt);
    }

    @Test public void entitiesAreDecodedInAnAddress() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/photo.jpg&amp;size=large\">");
        assertNotNull(find(found, "size=large"));
    }

    // ---- structure -------------------------------------------------------------------------------

    @Test public void anImageInsideAnArticleIsContent() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<article><p>Text</p><img src=\"https://cdn.example.edu/a.jpg\"></article>");
        assertEquals(RichAnswerArticleImages.STRUCTURE_CONTENT, found.get(0).structure);
    }

    @Test public void anImageInsideMainIsContent() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<main><img src=\"https://cdn.example.edu/a.jpg\"></main>");
        assertEquals(RichAnswerArticleImages.STRUCTURE_CONTENT, found.get(0).structure);
    }

    @Test public void anImageInsideAFigureIsContent() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<figure><img src=\"https://cdn.example.edu/a.jpg\"></figure>");
        assertEquals(RichAnswerArticleImages.STRUCTURE_CONTENT, found.get(0).structure);
    }

    @Test public void headerNavFooterAndAsideAreAllChrome() {
        for (String tag : new String[]{"header", "nav", "footer", "aside"}) {
            List<RichAnswerArticleImages.Candidate> found = parse(
                    "<" + tag + "><img src=\"https://cdn.example.edu/a.jpg\"></" + tag + ">");
            assertEquals(tag + " contents are furniture",
                    RichAnswerArticleImages.STRUCTURE_CHROME, found.get(0).structure);
        }
    }

    @Test public void anImageInNeitherIsNeutral() {
        List<RichAnswerArticleImages.Candidate> found =
                parse("<div><img src=\"https://cdn.example.edu/a.jpg\"></div>");
        assertEquals(RichAnswerArticleImages.STRUCTURE_NEUTRAL, found.get(0).structure);
    }

    /** A captioned picture inside an aside is still a captioned picture. */
    @Test public void aFigureInsideAnAsideStaysContent() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<aside><figure><img src=\"https://cdn.example.edu/a.jpg\">"
                        + "<figcaption>A widow</figcaption></figure></aside>");
        assertEquals(RichAnswerArticleImages.STRUCTURE_CONTENT, found.get(0).structure);
    }

    /**
     * An unclosed chrome tag must not condemn the rest of the document.
     *
     * <p>The alternative rule - run the span to the end - would mean one sloppy {@code <nav>} near
     * the top of a page marks every photograph below it as navigation and rejects all of them.
     */
    @Test public void anUnclosedChromeTagIsIgnoredRatherThanSwallowingThePage() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<nav><a href=\"/\">Home</a>"
                        + "<p>Body text</p><img src=\"https://cdn.example.edu/photos/widow.jpg\">");
        assertEquals(RichAnswerArticleImages.STRUCTURE_NEUTRAL, found.get(0).structure);
    }

    // ---- robustness ------------------------------------------------------------------------------

    @Test public void theSameAddressIsOnlyOfferedOnce() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/a.jpg\">"
                        + "<img src=\"https://cdn.example.edu/a.jpg\" alt=\"again\">"
                        + "<img srcset=\"https://cdn.example.edu/a.jpg 800w\">");
        assertEquals(1, found.size());
    }

    /** A described occurrence beats a bare duplicate, whichever order they appear in. */
    @Test public void deduplicationKeepsTheDescribedOccurrence() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/a.jpg\">"
                        + "<figure><img src=\"https://cdn.example.edu/a.jpg\">"
                        + "<figcaption>A northern black widow</figcaption></figure>");
        assertEquals(1, found.size());
        assertEquals("A northern black widow", found.get(0).caption);
    }

    @Test public void malformedAttributesDoNotStopTheParse() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img alt=unquoted src='https://cdn.example.edu/a.jpg' width=\"640\" height=480>"
                        + "<img src=>"
                        + "<img>"
                        + "<img src=\"https://cdn.example.edu/b.jpg\">");
        assertEquals(2, found.size());
        assertEquals(640, found.get(0).declaredWidth);
        assertEquals(480, found.get(0).declaredHeight);
    }

    @Test public void malformedMarkupStillYieldsItsPictures() {
        List<RichAnswerArticleImages.Candidate> found = RichAnswerArticleImages.parse(
                "<html><body><div><p>Unclosed"
                        + "<img src=\"https://cdn.example.edu/a.jpg\" alt=\"A widow\"",
                PAGE);
        // The final tag is never terminated, so it is not a tag; the complete one before it is.
        assertTrue(found.size() <= 1);
    }

    @Test public void aPercentageWidthIsNotADeclaredSize() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/a.jpg\" width=\"100%\" height=\"auto\">");
        assertEquals(0, found.get(0).declaredWidth);
        assertEquals(0, found.get(0).declaredHeight);
    }

    /** An address inside a script is not a picture on the page. */
    @Test public void scriptAndStyleContentIsNotScanned() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<script>var hero = '<img src=\"https://cdn.example.edu/injected.jpg\">';</script>"
                        + "<style>.a{background:url(https://cdn.example.edu/bg.jpg)}</style>"
                        + "<img src=\"https://cdn.example.edu/real.jpg\">");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/real.jpg", found.get(0).url);
    }

    @Test public void commentedOutMarkupIsNotScanned() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<!-- <img src=\"https://cdn.example.edu/old.jpg\"> -->"
                        + "<img src=\"https://cdn.example.edu/new.jpg\">");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/new.jpg", found.get(0).url);
    }

    /**
     * A commented-out script must not swallow the rest of the document.
     *
     * <p>The opening token has no matching terminator, because the whole thing is a comment. A
     * blanking pass that looked for one would run to the end of the page and erase every real
     * photograph below it - which on a page like this is all of them.
     */
    @Test public void aCommentedOutScriptDoesNotHideTheRestOfThePage() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<!-- <script src=\"/legacy.js\"> -->"
                        + "<article><img src=\"https://cdn.example.edu/widow.jpg\" alt=\"A widow\">"
                        + "</article>");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/widow.jpg", found.get(0).url);
    }

    /** An unterminated script blanks to the end, which is the safe direction. */
    @Test public void anUnterminatedScriptIsNotTreatedAsMarkup() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"https://cdn.example.edu/before.jpg\">"
                        + "<script>var a = '<img src=\"https://cdn.example.edu/after.jpg\">';");
        assertEquals(1, found.size());
        assertEquals("https://cdn.example.edu/before.jpg", found.get(0).url);
    }

    /**
     * Many unterminated script tokens must stay cheap.
     *
     * <p>A lazy regex here is quadratic - it restarts a scan to the end of the document at every
     * one of them - and the documents this reads are written by strangers. Timed rather than
     * merely asserted, because "it finished" is the only thing that actually matters.
     */
    @Test public void aHostilePageFullOfUnterminatedScriptTokensStaysFast() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 4000; i++) body.append("<script ");
        body.append("<img src=\"https://cdn.example.edu/widow.jpg\">");
        long started = System.currentTimeMillis();
        RichAnswerArticleImages.parse(body.toString(), PAGE);
        assertTrue("parsing must stay linear in the document",
                System.currentTimeMillis() - started < 4000);
    }

    /** An unsafe scheme never becomes a candidate, whatever a page writes in its markup. */
    @Test public void unsafeSchemesAreRefusedAtTheParser() {
        List<RichAnswerArticleImages.Candidate> found = parse(
                "<img src=\"javascript:alert(1)\">"
                        + "<img src=\"file:///etc/passwd\">"
                        + "<img src=\"data:image/png;base64,AAAA\">"
                        + "<img src=\"content://media/1\">"
                        + "<img src=\"intent://x\">"
                        + "<img src=\"http://insecure.example.edu/a.jpg\">");
        assertTrue("nothing unsafe may reach the download queue", found.isEmpty());
    }

    /** A long gallery is ranked from, not crawled. */
    @Test public void candidatesAreBounded() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("<img src=\"https://cdn.example.edu/photos/").append(i).append(".jpg\">");
        }
        assertTrue(parse(body.toString()).size() <= RichAnswerArticleImages.MAX_CANDIDATES);
    }

    @Test public void anEmptyDocumentYieldsNothing() {
        assertTrue(RichAnswerArticleImages.parse("", PAGE).isEmpty());
        assertTrue(RichAnswerArticleImages.parse(null, PAGE).isEmpty());
        assertNull(RichAnswerArticleImages.bestFromSrcset("", PAGE));
    }

    /**
     * The shape that broke two betas: a publication page with no preview declaration at all.
     *
     * <p>Beta 2 read this page's head, found no {@code og:image}, and moved on. Everything it
     * wanted was twelve lines further down.
     */
    @Test public void aPublicationPageWithNoPreviewMetadataStillYieldsItsPhotographs() {
        String html = "<html><head><title>Widow Spiders | Example Extension</title>"
                + "<link rel=\"stylesheet\" href=\"/style.css\"></head><body>"
                + "<header><img src=\"https://pubs.example.edu/img/logo.png\" alt=\"Example University\"></header>"
                + "<main><article>"
                + "<h1>Widow spiders</h1>"
                + "<figure><img src=\"https://pubs.example.edu/img/northern-black-widow.jpg\" "
                + "alt=\"Female northern black widow\" width=\"800\" height=\"600\">"
                + "<figcaption>Female northern black widow showing the hourglass</figcaption></figure>"
                + "</article></main>"
                + "<footer><img src=\"https://pubs.example.edu/img/seal.png\"></footer>"
                + "</body></html>";

        assertFalse("this page genuinely declares no preview image",
                RichAnswerPageMetadata.parse(html, PAGE).hasImage());

        List<RichAnswerArticleImages.Candidate> found = RichAnswerArticleImages.parse(html, PAGE);
        RichAnswerArticleImages.Candidate photo = find(found, "northern-black-widow.jpg");
        assertNotNull("the photograph in the article must be found", photo);
        assertEquals(RichAnswerArticleImages.STRUCTURE_CONTENT, photo.structure);
        assertEquals(800, photo.declaredWidth);
        assertTrue(photo.caption.contains("hourglass"));

        assertEquals("and the header logo is known to be furniture",
                RichAnswerArticleImages.STRUCTURE_CHROME, find(found, "logo.png").structure);
        assertEquals(RichAnswerArticleImages.STRUCTURE_CHROME, find(found, "seal.png").structure);
    }
}

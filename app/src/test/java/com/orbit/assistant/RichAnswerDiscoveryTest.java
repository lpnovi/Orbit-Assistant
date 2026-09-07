package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * How Orbit decides there is a picture worth showing, and which one.
 *
 * <p>Two halves, and they fail in opposite directions. Metadata parsing fails by finding nothing
 * when a page did declare something; relevance fails by finding something when the answer would be
 * better without it. The second is the expensive one - an image on every web answer teaches people
 * to stop looking at them - so most of what is asserted here is what Orbit refuses.
 *
 * <p>All of it is pure text work with no network, which is exactly why the fetch and the decision
 * live in different classes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerDiscoveryTest {

    // ---- page metadata ---------------------------------------------------------------------------

    @Test public void openGraphImageIsFound() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<html><head><title>European robin</title>"
                        + "<meta property=\"og:image\" content=\"https://cdn.example.org/robin.jpg\">"
                        + "</head><body><img src=\"https://cdn.example.org/logo.png\"></body></html>",
                "https://example.org/birds/robin");
        assertTrue(preview.hasImage());
        assertEquals("https://cdn.example.org/robin.jpg", preview.bestImage());
        assertEquals("European robin", preview.title);
    }

    @Test public void twitterImageIsFoundWhenOpenGraphIsAbsent() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<head><meta name=\"twitter:image\" content=\"https://cdn.example.org/card.jpg\">"
                        + "<meta name=\"twitter:image:alt\" content=\"A robin in snow\"></head>",
                "https://example.org/p");
        assertEquals("https://cdn.example.org/card.jpg", preview.bestImage());
        assertEquals("A robin in snow", preview.description);
    }

    /** og:image wins over twitter:image, whatever order the page wrote them in. */
    @Test public void openGraphIsPreferredOverTheTwitterCard() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<head><meta name=\"twitter:image\" content=\"https://cdn.example.org/card.jpg\">"
                        + "<meta property=\"og:image\" content=\"https://cdn.example.org/hero.jpg\">"
                        + "</head>", "https://example.org/p");
        assertEquals("https://cdn.example.org/hero.jpg", preview.bestImage());
        assertTrue("and the other stays as a fallback candidate",
                preview.imageUrls.contains("https://cdn.example.org/card.jpg"));
    }

    /** A relative or protocol-relative declaration is resolved against the page it came from. */
    @Test public void relativeImageAddressesAreResolvedAgainstThePage() {
        assertEquals("https://example.org/img/hero.jpg", RichAnswerPageMetadata.parse(
                "<head><meta property=\"og:image\" content=\"/img/hero.jpg\"></head>",
                "https://example.org/birds/robin").bestImage());
        assertEquals("https://example.org/birds/hero.jpg", RichAnswerPageMetadata.parse(
                "<head><meta property=\"og:image\" content=\"hero.jpg\"></head>",
                "https://example.org/birds/robin").bestImage());
        assertEquals("https://cdn.example.net/hero.jpg", RichAnswerPageMetadata.parse(
                "<head><meta property=\"og:image\" content=\"//cdn.example.net/hero.jpg\"></head>",
                "https://example.org/p").bestImage());
    }

    /** A candidate is put through the URL policy before it can reach a fetch queue. */
    @Test public void unsafeDeclaredImagesAreDroppedAtParseTime() {
        for (String declared : new String[]{
                "javascript:alert(1)", "file:///etc/passwd", "content://x/y",
                "intent://x#Intent;end", "http://127.0.0.1/x.jpg", "https://localhost/x.jpg",
                "https://192.168.0.4/x.jpg", "https://user:pw@example.org/x.jpg"}) {
            RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                    "<head><meta property=\"og:image\" content=\"" + declared + "\"></head>",
                    "https://example.org/p");
            assertFalse(declared + " must never become a candidate", preview.hasImage());
        }
    }

    /** The same image declared several times is one candidate. */
    @Test public void duplicateCandidatesAreRemoved() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<head><meta property=\"og:image\" content=\"https://cdn.example.org/a.jpg\">"
                        + "<meta property=\"og:image:url\" content=\"https://cdn.example.org/a.jpg\">"
                        + "<meta name=\"twitter:image\" content=\"https://cdn.example.org/a.jpg\">"
                        + "</head>", "https://example.org/p");
        assertEquals(1, preview.imageUrls.size());
    }

    /** Malformed and empty markup produce a clean empty preview rather than an exception. */
    @Test public void malformedMarkupDegradesToNothing() {
        for (String html : new String[]{
                "", "   ", "not html at all", "<html", "<head><meta property=og:image content=",
                "<head><meta property=\"og:image\"></head>",
                "<head><meta content=\"https://cdn.example.org/a.jpg\"></head>"}) {
            assertFalse("[" + html + "] must not produce a candidate",
                    RichAnswerPageMetadata.parse(html, "https://example.org/p").hasImage());
        }
        assertFalse(RichAnswerPageMetadata.parse(null, "https://example.org/p").hasImage());
        assertFalse(RichAnswerPageMetadata.empty().hasImage());
    }

    /**
     * Only the head is read, so a body full of images contributes nothing.
     *
     * <p>The point of a declared preview image is that its author said it represents the page.
     * Anything in the body is navigation chrome, advertising, or an avatar, and Orbit has no way to
     * tell which.
     */
    @Test public void nothingIsTakenFromThePageBody() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<head><title>Page</title></head><body>"
                        + "<meta property=\"og:image\" content=\"https://cdn.example.org/body.jpg\">"
                        + "<img src=\"https://cdn.example.org/photo.jpg\"></body>",
                "https://example.org/p");
        assertFalse(preview.hasImage());
    }

    /** A truncated fetch still yields what did arrive, because the head arrives first. */
    @Test public void aTruncatedDocumentStillYieldsItsDeclarations() {
        RichAnswerPageMetadata.Preview preview = RichAnswerPageMetadata.parse(
                "<html><head><meta property=\"og:image\" content=\"https://cdn.example.org/a.jpg\">"
                        + "<meta name=\"description\" content=\"cut off here",
                "https://example.org/p");
        assertEquals("https://cdn.example.org/a.jpg", preview.bestImage());
    }

    /** Entities in a caption are decoded, because a caption is read rather than parsed. */
    @Test public void captionEntitiesAreDecoded() {
        assertEquals("Fish &amp; chips", "Fish &amp; chips");
        assertEquals("Fish & chips", RichAnswerPageMetadata.decode("Fish &amp; chips"));
        assertEquals("\"quoted\"", RichAnswerPageMetadata.decode("&quot;quoted&quot;"));
        assertEquals("a b", RichAnswerPageMetadata.decode("a&nbsp;b"));
    }

    /** Only a bounded number of candidates ever leaves one page. */
    @Test public void candidatesAreBounded() {
        StringBuilder head = new StringBuilder("<head>");
        for (int i = 0; i < 20; i++) {
            head.append("<meta property=\"og:image\" content=\"https://cdn.example.org/")
                .append(i).append(".jpg\">");
        }
        head.append("</head>");
        assertTrue(RichAnswerPageMetadata.parse(head.toString(), "https://example.org/p")
                .imageUrls.size() <= RichAnswerPageMetadata.MAX_CANDIDATES);
    }

    // ---- fetch bounds ------------------------------------------------------------------------------

    /** A metadata read is deliberately tiny, and only ever a GET for markup. */
    @Test public void theMetadataFetchIsBoundedAndPassive() {
        assertTrue("a head is kilobytes, not megabytes",
                RichAnswerPageFetcher.MAX_BYTES <= 128 * 1024);
        assertTrue(RichAnswerPageFetcher.CONNECT_TIMEOUT_MS > 0
                && RichAnswerPageFetcher.CONNECT_TIMEOUT_MS <= 10000);
        assertTrue(RichAnswerPageFetcher.READ_TIMEOUT_MS > 0
                && RichAnswerPageFetcher.READ_TIMEOUT_MS <= 15000);

        assertTrue(RichAnswerPageFetcher.looksLikeHtml("text/html; charset=utf-8"));
        assertTrue(RichAnswerPageFetcher.looksLikeHtml("application/xhtml+xml"));
        for (String type : new String[]{
                "application/pdf", "image/jpeg", "application/json", "text/plain",
                "application/octet-stream", null}) {
            assertFalse(type + " is not a page Orbit reads declarations out of",
                    RichAnswerPageFetcher.looksLikeHtml(type));
        }

        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerPageFetcher.java");
        assertTrue("no cookies may travel with it", source.contains("\"Cookie\", \"\""));
        assertFalse("and it must never POST", source.contains("setRequestMethod(\"POST\")"));
        assertTrue("output is explicitly off", source.contains("setDoOutput(false)"));
        assertFalse("nothing here follows a redirect without revalidating it",
                source.contains("setInstanceFollowRedirects(true)"));
    }

    /** An image fetch is bounded too, and refuses anything that is not an image. */
    @Test public void theImageFetchIsBoundedAndTypeChecked() {
        String source = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RemoteImageLoader.java");
        assertTrue("the content type must be an image",
                source.contains("startsWith(\"image/\")"));
        assertTrue("an oversized response is refused", source.contains("Image too large"));
        assertTrue("decoding is bounded so a huge picture cannot exhaust memory",
                source.contains("inSampleSize"));
        assertTrue("timeouts are set on every connection",
                source.contains("setConnectTimeout") && source.contains("setReadTimeout"));
        assertTrue("the disk cache has a ceiling", source.contains("MAX_DISK_BYTES"));
        assertTrue("and is trimmed deterministically", source.contains("trimDiskCache"));
        assertTrue("no cookies travel with an image request",
                source.contains("\"Cookie\", \"\""));
    }

    // ---- relevance ---------------------------------------------------------------------------------

    /** Questions where a picture is genuinely part of the answer. */
    @Test public void visualQuestionsWantAPicture() {
        for (String prompt : new String[]{
                "what does a European robin look like",
                "identify this bird for me",
                "show me the Hagia Sophia",
                "what breed of dog is a vizsla",
                "compare the Model 3 and the Model Y",
                "is this mushroom safe to eat",
                "is this mushroom safe to eat"}) {
            assertTrue("[" + prompt + "] deserves a picture",
                    RichAnswerRelevance.answerWantsImage(prompt, "A short factual answer."));
        }
        // A question whose own words are not visual, whose answer plainly is. Orbit reads both,
        // because "tell me about the Hagia Sophia" is obviously a place and does not say so.
        assertTrue(RichAnswerRelevance.answerWantsImage("what is the Sagrada Familia",
                "A basilica in Barcelona and the city's best known landmark."));
    }

    /** And the far more common case, where one would add nothing at all. */
    @Test public void ordinaryQuestionsDoNotGetAPicture() {
        for (String prompt : new String[]{
                "what is 18% of 75",
                "define ontology",
                "convert 30 celsius to fahrenheit",
                "explain closures in javascript",
                "write a polite reply declining the meeting",
                "summarize this article",
                "what does it mean to be pragmatic",
                "should i take the job",
                "translate good morning into spanish",
                "what is the meaning of ubiquitous"}) {
            assertFalse("[" + prompt + "] must not force a picture",
                    RichAnswerRelevance.answerWantsImage(prompt, "A short factual answer."));
        }
        assertFalse(RichAnswerRelevance.answerWantsImage("", "anything"));
        assertFalse(RichAnswerRelevance.answerWantsImage(null, "anything"));
    }

    /** A programming question keeps its wording without becoming a visual one. */
    @Test public void aTechnicalQuestionIsNotVisualBecauseItSaysLookLike() {
        assertFalse(RichAnswerRelevance.answerWantsImage(
                "what does a closure look like in javascript",
                "A closure captures its enclosing scope."));
    }

    // ---- candidate scoring ---------------------------------------------------------------------------

    /** Chrome, tracking assets and icons are refused outright rather than merely ranked low. */
    @Test public void chromeAndTrackingAssetsAreRefused() {
        for (String url : new String[]{
                "https://cdn.example.org/logo.png",
                "https://cdn.example.org/assets/favicon.png",
                "https://cdn.example.org/icons/menu.png",
                "https://cdn.example.org/sprite.png",
                "https://cdn.example.org/avatar-default.jpg",
                "https://cdn.example.org/og-default.png",
                "https://cdn.example.org/social-card.png",
                "https://cdn.example.org/1x1.gif",
                "https://cdn.example.org/pixel.gif",
                "https://cdn.example.org/tracking/beacon.png",
                "https://cdn.example.org/hero.svg",
                "https://cdn.example.org/thing.ico"}) {
            assertTrue(url + " must never be shown as answer imagery",
                    RichAnswerRelevance.score(url, "https://example.org/p", "", true) < 0);
        }
    }

    /** A picture from a page the answer actually used outranks one that is merely available. */
    @Test public void belongingToACitedPageIsWorthMoreThanAnythingElse() {
        int cited = RichAnswerRelevance.score("https://cdn.example.org/photographs/robin-in-snow.jpg",
                "https://example.org/birds/robin", "A robin", true);
        int uncited = RichAnswerRelevance.score("https://cdn.example.org/photographs/robin-in-snow.jpg",
                "https://example.org/birds/robin", "A robin", false);
        assertTrue(cited > uncited);
        assertTrue(cited > 0);
    }

    /** A social host's preview card is usually its own branding, so it is pushed down. */
    @Test public void socialSharingCardsAreRankedBelowEditorialImages() {
        int editorial = RichAnswerRelevance.score("https://cdn.example.org/photographs/robin.jpg",
                "https://example.org/birds/robin", "A robin", true);
        int social = RichAnswerRelevance.score("https://pbs.example.com/media/abc.jpg",
                "https://twitter.com/someone/status/1", "A post", true);
        assertTrue(editorial > social);
    }

    /** A refused address never scores at all, whatever else is true of it. */
    @Test public void anUnsafeCandidateScoresBelowZero() {
        assertTrue(RichAnswerRelevance.score("http://cdn.example.org/a.jpg",
                "https://example.org/p", "", true) < 0);
        assertTrue(RichAnswerRelevance.score("https://127.0.0.1/a.jpg",
                "https://example.org/p", "", true) < 0);
        assertTrue(RichAnswerRelevance.score("javascript:alert(1)",
                "https://example.org/p", "", true) < 0);
    }

    /** Decoded bounds decide whether a picture is worth drawing, not a declared size. */
    @Test public void tinyAndBannerShapedImagesAreRejectedAfterDecoding() {
        assertTrue(RichAnswerRelevance.hasUsefulDimensions(1200, 800));
        assertTrue(RichAnswerRelevance.hasUsefulDimensions(640, 640));
        assertTrue(RichAnswerRelevance.hasUsefulDimensions(600, 900));
        assertFalse("an icon", RichAnswerRelevance.hasUsefulDimensions(48, 48));
        assertFalse("a small thumbnail", RichAnswerRelevance.hasUsefulDimensions(180, 120));
        assertFalse("a wide banner", RichAnswerRelevance.hasUsefulDimensions(1600, 120));
        assertFalse("a tall sliver", RichAnswerRelevance.hasUsefulDimensions(200, 1600));
    }

    /** One picture by default, two only where a comparison genuinely needs them. */
    @Test public void oneImageIsTheDefaultAndTwoIsTheCeiling() {
        assertEquals(1, RichAnswerRelevance.maxImagesFor("what does a robin look like"));
        assertEquals(1, RichAnswerRelevance.maxImagesFor("show me the Hagia Sophia"));
        assertEquals(2, RichAnswerRelevance.maxImagesFor(
                "what is the difference between a robin and a bluebird"));
        assertEquals(2, RichAnswerRelevance.maxImagesFor("compare the Model 3 and the Model Y"));
        assertTrue(RichAnswerRelevance.maxImagesFor("compare everything")
                <= RichAnswerImage.MAX_PER_MESSAGE);
    }

    // ---- placement ------------------------------------------------------------------------------------

    /** A picture goes after the first real paragraph, not at the top and not at the bottom. */
    @Test public void placementFollowsTheFirstParagraph() {
        assertEquals("a heading is not where a picture belongs", 1,
                RichAnswerPlacement.placementFor(
                        "## European robin\n\nA small passerine bird.\n\nIt is common in Europe."));
        assertEquals(0, RichAnswerPlacement.placementFor(
                "A small passerine bird.\n\nIt is common in Europe."));
        assertEquals("a one-block answer has one place", 0,
                RichAnswerPlacement.placementFor("A short answer."));
    }

    /** An answer of nothing but headings still places its picture somewhere real. */
    @Test public void placementNeverEscapesTheAnswer() {
        int at = RichAnswerPlacement.placementFor("# One\n\n## Two");
        assertTrue(at >= 0 && at <= 1);
        assertEquals(0, RichAnswerPlacement.placementFor(""));
        assertEquals(0, RichAnswerPlacement.placementFor((String) null));
    }

    /** A stored anchor past the end of a shorter answer settles on the last block. */
    @Test public void anOutOfRangeAnchorIsClampedRatherThanDropped() {
        RichAnswerImage image = RichAnswerImage.webSource("https://cdn.example.org/a.jpg",
                "https://example.org/p", "", "", 9);
        List<RichAnswerImage> images = java.util.Collections.singletonList(image);
        assertTrue("it must still be drawn somewhere",
                RichAnswerPlacement.imagesAfter(images, 2, 3).contains(image));
        assertTrue(RichAnswerPlacement.imagesAfter(images, 0, 3).isEmpty());
        assertTrue(RichAnswerPlacement.imagesAfter(images, 0, 0).isEmpty());
        assertTrue(RichAnswerPlacement.imagesAfter(null, 0, 3).isEmpty());
    }

    /** The cheap question a plain answer asks before it builds anything. */
    @Test public void anAnswerWithNoUsablePictureReportsNone() {
        assertFalse(RichAnswerPlacement.hasAnyImage(null));
        assertFalse(RichAnswerPlacement.hasAnyImage(java.util.Collections.emptyList()));
        assertFalse(RichAnswerPlacement.hasAnyImage(java.util.Collections.singletonList(
                RichAnswerImage.webSource("https://cdn.example.org/a.jpg", "", "", "", 0))));
        assertTrue(RichAnswerPlacement.hasAnyImage(java.util.Collections.singletonList(
                RichAnswerImage.webSource("https://cdn.example.org/a.jpg",
                        "https://example.org/p", "", "", 0))));
    }

    // ---- captions ------------------------------------------------------------------------------------

    /** A caption is the page's own words, trimmed of the publisher's name. */
    @Test public void captionsComeFromThePageAndDropTrailingBranding() {
        assertEquals("European robin", RichAnswerCoordinator.captionFor(
                RichAnswerPageMetadata.parse(
                        "<head><title>European robin | Example Birding</title></head>",
                        "https://example.org/p")));
        assertEquals("", RichAnswerCoordinator.captionFor(RichAnswerPageMetadata.empty()));
        assertEquals("", RichAnswerCoordinator.captionFor(null));
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The second picture has to be earned.
 *
 * <p><b>This file is the regression test for Beta 9.</b> Beta 8 sent Orbit to the web and it came
 * back with genuine Mallard photographs, and then the device printed the failure this closes: one
 * real Mallard beside a large blank card carrying a small blue cube, captioned with the Mallard
 * page's own title so that it read as a promise that the reader was looking at a duck. Nothing in
 * the pipeline was broken. The cube was a different image from the duck, it was large enough, it
 * decoded, and it filled the second slot because it was the next thing in the list.
 *
 * <p>Two rules are under test here and they are separate. A page about Mallards is not evidence
 * that every image on it is a Mallard, so an individual candidate needs something of its own before
 * it may take a slot. And a request for two pictures is a request for up to two useful pictures, so
 * one excellent photograph is the correct answer when there is no second one.
 *
 * <p>The opposite failure is tested just as hard. A gate that answers every plural request with one
 * picture has replaced a visible bug with an invisible one, so the pages here carry genuine second
 * photographs and the assertions insist those survive.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerCandidateQualityTest {

    private Context context;
    private RemoteImageLoader.Transport previousTransport;
    private RichAnswerPageFetcher.PageSource previousSource;
    private FakeImages images;
    private FakePages pages;
    private final Map<String, Integer> seeds = new LinkedHashMap<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        clearCache();
        RichAnswerTrace.clear(context);
        images = new FakeImages();
        pages = new FakePages();
        previousTransport = RemoteImageLoader.installTransportForTest(images);
        previousSource = RichAnswerPageFetcher.installSourceForTest(pages);
    }

    @After public void tearDown() {
        RemoteImageLoader.installTransportForTest(previousTransport);
        RichAnswerPageFetcher.installSourceForTest(previousSource);
        clearCache();
    }

    private void clearCache() {
        File[] files = new File(context.getCacheDir(), "orbit_response_images").listFiles();
        if (files != null) for (File file : files) file.delete();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static final class FakeImages implements RemoteImageLoader.Transport {
        final Map<String, RemoteImageLoader.Response> responses = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override public boolean allowsHost(String url) {
            return RichAnswerUrlPolicy.hasSafeFetchSyntax(url);
        }

        @Override public RemoteImageLoader.Response open(String url) {
            requested.add(url);
            RemoteImageLoader.Response canned = responses.get(url);
            return canned != null ? canned
                    : new RemoteImageLoader.Response(404, "text/plain", null, 0, null);
        }
    }

    private static final class FakePages implements RichAnswerPageFetcher.PageSource {
        final Map<String, String> html = new LinkedHashMap<>();

        @Override public RichAnswerPageFetcher.PageResult fetch(String pageUrl) {
            String body = html.get(pageUrl);
            if (body == null) {
                return RichAnswerPageFetcher.PageResult.failed(
                        RichAnswerTrace.Reason.HTTP_ERROR, pageUrl, 404, "", 0);
            }
            return RichAnswerPageFetcher.parse(body, pageUrl, 200, "text/html; charset=UTF-8", 0);
        }
    }

    /** A genuine photograph, a new one per address. */
    private void servePhoto(String url) { servePhoto(url, 900, 700); }

    private void servePhoto(String url, int width, int height) {
        Integer seed = seeds.get(url);
        if (seed == null) {
            seed = seeds.size() + 1;
            seeds.put(url, seed);
        }
        serve(url, TestPng.photo(width, height, seed));
    }

    /** The blue cube: valid, large, decodable, and a picture of nothing. */
    private void serveGraphic(String url) { serveGraphic(url, 900, 700); }

    private void serveGraphic(String url, int width, int height) {
        serve(url, TestPng.graphic(width, height, 0.13));
    }

    private void serve(String url, byte[] bytes) {
        images.responses.put(url, new RemoteImageLoader.Response(
                200, "image/jpeg", null, bytes.length, new ByteArrayInputStream(bytes)));
    }

    private RichAnswerTrace.Attempt attempt(int wanted) {
        RichAnswerTrace.Attempt trace = new RichAnswerTrace.Attempt();
        trace.enabled = true;
        trace.providerEligible = true;
        trace.intent = RichAnswerTrace.Intent.STRONG_VISUAL;
        trace.pageBudget = RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG;
        trace.requestedImages = wanted;
        return trace;
    }

    private List<RichAnswerImage> resolve(String page, int wanted, String prompt,
                                          RichAnswerTrace.Attempt trace) {
        List<RichAnswerImage> found = RichAnswerCoordinator.resolve(context,
                Collections.singletonList(page), wanted, 0,
                RichAnswerCandidateQuality.Demand.of(prompt), trace);
        trace.attachedImages = found.size();
        return found;
    }

    private static boolean traceContains(RichAnswerTrace.Attempt trace,
                                         RichAnswerTrace.Reason reason) {
        return trace.rejections(reason) > 0;
    }

    // ---- the Wikimedia file page the device actually hit -----------------------------------------

    private static final String FILE_PAGE =
            "https://commons.wikimedia.org/wiki/File:Anas_platyrhynchos-male-in_water.jpg";
    private static final String UPLOAD = "https://upload.wikimedia.org/wikipedia/commons/";
    private static final String DRAKE =
            UPLOAD + "thumb/1/1e/Anas_platyrhynchos-male-in_water.jpg/1280px-Anas_platyrhynchos.jpg";
    private static final String HEN =
            UPLOAD + "thumb/9/9d/Anas_platyrhynchos-female.jpg/1024px-Anas_platyrhynchos-female.jpg";
    private static final String CUBE =
            UPLOAD + "thumb/a/a1/Structured_data_panel.png/960px-Structured_data_panel.png";

    private static final String PROMPT = "Show me pics of a mallard duck";

    /**
     * The page whose title is about Mallards and whose second image is not.
     *
     * <p>Everything the device saw. The title names the subject, the first image is a genuine
     * photograph the page describes, and the second is a piece of interface artwork sitting in the
     * same article with nothing said about it.
     */
    private String cubePage() {
        return "<html><head><title>Mallard Photos and Videos</title></head><body><main><article>"
                + "<img src=\"" + DRAKE + "\" width=\"1280\" height=\"960\" "
                + "alt=\"A male mallard duck swimming on open water\">"
                + "<img src=\"" + CUBE + "\" width=\"960\" height=\"720\" alt=\"\">"
                + "</article></main></body></html>";
    }

    /**
     * The exact failure: one real Mallard, one blue cube, and one picture on the answer.
     *
     * <p>Two are asked for. One exists. The cube is refused after it downloads, because the only
     * honest way to know a large valid PNG is a piece of interface artwork is to look at it.
     */
    @Test public void theBlueCubeIsRefusedAndTheMallardPhotographIsShown() {
        pages.html.put(FILE_PAGE, cubePage());
        servePhoto(DRAKE);
        serveGraphic(CUBE);

        RichAnswerTrace.Attempt trace = attempt(2);
        List<RichAnswerImage> found = resolve(FILE_PAGE, 2, PROMPT, trace);

        assertEquals("one good photograph is the whole answer to a request for two",
                1, found.size());
        assertEquals(DRAKE, found.get(0).imageUrl);
        assertTrue("and the cube must be named as what it was",
                traceContains(trace, RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER));
        assertEquals(2, trace.requestedImages);
        assertEquals(1, trace.attachedImages);
    }

    /** A page title about Mallards is not a statement about an undescribed image on that page. */
    @Test public void thePageTitleDoesNotRescueTheCube() {
        pages.html.put(FILE_PAGE, cubePage());
        servePhoto(DRAKE);
        serveGraphic(CUBE);

        List<RichAnswerImage> found = resolve(FILE_PAGE, 2, PROMPT, attempt(2));
        for (RichAnswerImage image : found) {
            assertFalse("the cube must never reach the answer", CUBE.equals(image.imageUrl));
        }
    }

    /** A graphic in the page furniture is refused before a byte is spent on it. */
    @Test public void anUndescribedChromeGraphicIsRefusedWithoutADownload() {
        pages.html.put(FILE_PAGE, "<html><head><title>Mallard Photos and Videos</title></head>"
                + "<body><main><article><img src=\"" + DRAKE + "\" width=\"1280\" height=\"960\" "
                + "alt=\"A male mallard duck swimming on open water\"></article></main>"
                + "<footer><img src=\"" + CUBE + "\" width=\"960\" height=\"720\" alt=\"\">"
                + "</footer></body></html>");
        servePhoto(DRAKE);
        serveGraphic(CUBE);

        RichAnswerTrace.Attempt trace = attempt(2);
        List<RichAnswerImage> found = resolve(FILE_PAGE, 2, PROMPT, trace);

        assertEquals(1, found.size());
        assertFalse("page furniture must not cost a request", images.requested.contains(CUBE));
    }

    /**
     * Two genuine photographs are still two pictures.
     *
     * <p>The assertion that stops this release from turning every plural request into a single
     * picture. Both images describe themselves, both are photographs, and both are shown.
     */
    @Test public void twoRealMallardPhotographsAreBothShown() {
        pages.html.put(FILE_PAGE,
                "<html><head><title>Mallard Photos and Videos</title></head><body><main><article>"
                        + "<img src=\"" + DRAKE + "\" width=\"1280\" height=\"960\" "
                        + "alt=\"A male mallard duck swimming on open water\">"
                        + "<figure><img src=\"" + HEN + "\" width=\"1024\" height=\"768\" "
                        + "alt=\"A female mallard duck in flight\">"
                        + "<figcaption>A female mallard duck in flight</figcaption></figure>"
                        + "</article></main></body></html>");
        servePhoto(DRAKE);
        servePhoto(HEN);

        RichAnswerTrace.Attempt trace = attempt(2);
        List<RichAnswerImage> found = resolve(FILE_PAGE, 2, PROMPT, trace);

        assertEquals("two real photographs are two pictures", 2, found.size());
        List<String> urls = new ArrayList<>();
        for (RichAnswerImage image : found) urls.add(image.imageUrl);
        assertTrue("the drake must be one of them", urls.contains(DRAKE));
        assertTrue("and the hen the other", urls.contains(HEN));
        assertEquals(0, trace.rejections(RichAnswerTrace.Reason.GRAPHIC_OR_PLACEHOLDER));
    }

    /** A singular request is answered with exactly one photograph. */
    @Test public void oneRequestedPictureIsOnePhotograph() {
        pages.html.put(FILE_PAGE, cubePage());
        servePhoto(DRAKE);
        serveGraphic(CUBE);

        List<RichAnswerImage> found = resolve(FILE_PAGE, 1,
                "Show me one picture of a mallard duck", attempt(1));
        assertEquals(1, found.size());
        assertEquals(DRAKE, found.get(0).imageUrl);
    }

    /** The comparison shape, which asks for two without ever writing a plural picture word. */
    @Test public void aComparisonNeverTakesARandomSecondGraphic() {
        pages.html.put(FILE_PAGE, cubePage());
        servePhoto(DRAKE);
        serveGraphic(CUBE);

        List<RichAnswerImage> found = resolve(FILE_PAGE, 2,
                "Show me the visual differences between a male and female mallard duck.",
                attempt(2));
        assertEquals(1, found.size());
        assertEquals(DRAKE, found.get(0).imageUrl);
    }

    /** The other subject the device tested, with the same shape of interface artwork on the page. */
    @Test public void aBlackWidowAnswerCarriesSpidersRatherThanInterfaceGraphics() {
        String page = "https://commons.wikimedia.org/wiki/Category:Latrodectus_mactans";
        String spider = "https://cdn.example.org/photos/black-widow-female.jpg";
        String panel = "https://cdn.example.org/assets/structured-panel.png";
        pages.html.put(page, "<html><head><title>Black widow spider photographs</title></head>"
                + "<body><main><article>"
                + "<img src=\"" + spider + "\" width=\"1200\" height=\"900\" "
                + "alt=\"A female black widow spider on its web\">"
                + "<img src=\"" + panel + "\" width=\"900\" height=\"700\" alt=\"\">"
                + "</article></main></body></html>");
        servePhoto(spider, 1200, 900);
        serveGraphic(panel);

        List<RichAnswerImage> found = resolve(page, 2, "Show me pictures of a black widow",
                attempt(2));
        assertEquals(1, found.size());
        assertEquals(spider, found.get(0).imageUrl);
    }

    // ---- and the pictures that are not photographs ----------------------------------------------

    /** A diagram is a legitimate answer and is never refused for failing to be a photograph. */
    @Test public void aDiagramRequestStillGetsItsDiagram() {
        String page = "https://anatomy.example.edu/heart";
        String diagram = "https://anatomy.example.edu/figures/heart-chambers.png";
        pages.html.put(page, "<html><head><title>The human heart</title></head><body><main><article>"
                + "<img src=\"" + diagram + "\" width=\"900\" height=\"700\" "
                + "alt=\"Diagram of the human heart and its chambers\"></article></main></body></html>");
        serveGraphic(diagram);

        assertFalse("a diagram request is not a photograph request",
                RichAnswerRelevance.wantsPhotographs("Show me a diagram of the human heart"));
        List<RichAnswerImage> found = resolve(page, 1, "Show me a diagram of the human heart",
                attempt(1));
        assertEquals(1, found.size());
        assertEquals(diagram, found.get(0).imageUrl);
    }

    /** A flag is flat by design, and the photograph rules never run on one. */
    @Test public void aFlagRequestStillGetsItsFlag() {
        String page = "https://example.org/ireland";
        String flag = "https://example.org/media/ireland-tricolour.png";
        pages.html.put(page, "<html><head><title>Ireland</title></head><body><main><article>"
                + "<img src=\"" + flag + "\" width=\"900\" height=\"600\" "
                + "alt=\"The national flag of Ireland\"></article></main></body></html>");
        serveGraphic(flag, 900, 600);

        assertFalse(RichAnswerRelevance.wantsPhotographs("Show me the flag of Ireland"));
        List<RichAnswerImage> found = resolve(page, 1, "Show me the flag of Ireland", attempt(1));
        assertEquals(1, found.size());
        assertEquals(flag, found.get(0).imageUrl);
    }

    /** Which requests want photographs, stated directly. */
    @Test public void photographRequestsAreTheOnesThatNameAPhotograph() {
        assertTrue(RichAnswerRelevance.wantsPhotographs("Show me pics of a mallard duck"));
        assertTrue(RichAnswerRelevance.wantsPhotographs("Show me two different photos of a mallard duck."));
        assertTrue(RichAnswerRelevance.wantsPhotographs("what does a mallard duck look like"));
        assertFalse(RichAnswerRelevance.wantsPhotographs("Show me a map of Ireland"));
        assertFalse(RichAnswerRelevance.wantsPhotographs("Show me the logo of the BBC"));
        assertFalse(RichAnswerRelevance.wantsPhotographs("Show me a screenshot of the settings screen"));
        assertFalse(RichAnswerRelevance.wantsPhotographs("Explain what a hash map is"));
    }

    // ---- captions --------------------------------------------------------------------------------

    private static RichAnswerArticleImages.Candidate candidate(
            RichAnswerArticleImages.Origin origin, String alt, String caption) {
        return new RichAnswerArticleImages.Candidate(CUBE, origin, alt, caption, 960, 720,
                RichAnswerArticleImages.STRUCTURE_CONTENT);
    }

    private static RichAnswerPageMetadata.Preview mallardPreview() {
        return RichAnswerPageFetcher.parse(
                "<html><head><title>File:Anas platyrhynchos-male-in water.jpg</title>"
                        + "</head><body></body></html>",
                FILE_PAGE, 200, "text/html", 0).preview;
    }

    /** An image the page described is captioned with what the page said about it. */
    @Test public void anArticleImageKeepsItsOwnWords() {
        assertEquals("A female mallard duck in flight",
                RichAnswerCoordinator.captionFor(
                        candidate(RichAnswerArticleImages.Origin.ARTICLE_IMG,
                                "A female mallard duck in flight", ""),
                        mallardPreview()));
        assertEquals("A drake on open water",
                RichAnswerCoordinator.captionFor(
                        candidate(RichAnswerArticleImages.Origin.SRCSET, "",
                                "A drake on open water"),
                        mallardPreview()));
    }

    /**
     * An image the page said nothing about does not borrow the page's title.
     *
     * <p>The misleading caption from the device: a blue cube labelled
     * {@code File:Anas platyrhynchos-male-in water.jpg}, which reads as a claim that the reader is
     * looking at a Mallard. The source domain on its own is true and is enough.
     */
    @Test public void anUndescribedArticleImageDoesNotInheritThePageTitle() {
        for (RichAnswerArticleImages.Origin origin : new RichAnswerArticleImages.Origin[]{
                RichAnswerArticleImages.Origin.ARTICLE_IMG,
                RichAnswerArticleImages.Origin.SRCSET,
                RichAnswerArticleImages.Origin.PICTURE_SOURCE,
                RichAnswerArticleImages.Origin.LAZY_IMAGE}) {
            assertEquals(origin + " must not claim to depict the page's subject",
                    "", RichAnswerCoordinator.captionFor(candidate(origin, "", ""),
                            mallardPreview()));
        }
    }

    /** A page's own declared image is what the page says it is, so the title still stands. */
    @Test public void aPreviewImageMayStillUseThePageTitle() {
        assertEquals("File:Anas platyrhynchos-male-in water.jpg",
                RichAnswerCoordinator.captionFor(
                        candidate(RichAnswerArticleImages.Origin.OG_IMAGE, "", ""),
                        mallardPreview()));
    }

    /** A picture with no caption is still attributed, by domain. */
    @Test public void anUncaptionedPictureIsStillAttributed() {
        RichAnswerImage image = RichAnswerImage.webSource(CUBE, FILE_PAGE, "", "", 0);
        assertEquals("commons.wikimedia.org", image.attributionLine());
    }
}

package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * The whole discovery pipeline, driven end to end with no network anywhere.
 *
 * <p><b>This file is the regression test for Beta 2.</b> Its central case is the exact shape that
 * failed twice on a Galaxy S25 Ultra: a hosted web search, one cited university publication, no
 * {@code og:image} anywhere on it, and five perfectly good photographs in the article body. Beta 2
 * read the head, found nothing, and moved on - the {@code if (!preview.hasImage()) continue;} that
 * this release exists to delete.
 *
 * <p>Everything the trace has to be able to say afterwards is asserted here too, because a
 * pipeline that finds the picture and a pipeline that can explain why it did not are the two
 * halves of the same promise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerPipelineTest {

    private Context context;
    private RemoteImageLoader.Transport previousTransport;
    private RichAnswerPageFetcher.PageSource previousSource;
    private FakeImages images;
    private FakePages pages;

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
        final List<String> refusedHosts = new ArrayList<>();

        @Override public boolean allowsHost(String url) {
            for (String host : refusedHosts) if (url.contains(host)) return false;
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
        final Map<String, RichAnswerTrace.Reason> failures = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override public RichAnswerPageFetcher.PageResult fetch(String pageUrl) {
            requested.add(pageUrl);
            RichAnswerTrace.Reason failure = failures.get(pageUrl);
            if (failure != null) {
                return RichAnswerPageFetcher.PageResult.failed(failure, pageUrl,
                        failure == RichAnswerTrace.Reason.HTTP_ERROR ? 404 : 0, "", 0);
            }
            String body = html.get(pageUrl);
            if (body == null) {
                return RichAnswerPageFetcher.PageResult.failed(
                        RichAnswerTrace.Reason.HTTP_ERROR, pageUrl, 404, "", 0);
            }
            return RichAnswerPageFetcher.parse(body, pageUrl, 200, "text/html; charset=UTF-8", 0);
        }
    }

    private void serveImage(String url) {
        serveImage(url, 800, 600);
    }

    /** One seed per address, handed out in order so no two fixtures are the same photograph. */
    private final Map<String, Integer> seeds = new LinkedHashMap<>();

    /**
     * A real photograph rather than a flat rectangle.
     *
     * <p>Beta 9 refuses a featureless image when the question asked for photographs, and a solid
     * grey fixture is featureless by construction, so every picture served here now carries actual
     * structure. Seeded per address so two fixtures are two photographs.
     */
    private void serveImage(String url, int width, int height) {
        Integer seed = seeds.get(url);
        if (seed == null) {
            seed = seeds.size() + 1;
            seeds.put(url, seed);
        }
        byte[] bytes = TestPng.photo(width, height, seed);
        images.responses.put(url, new RemoteImageLoader.Response(
                200, "image/jpeg", null, bytes.length, new ByteArrayInputStream(bytes)));
    }

    private void serveStatus(String url, int status) {
        images.responses.put(url, new RemoteImageLoader.Response(status, "text/plain", null, 0, null));
    }

    private RichAnswerTrace.Attempt attempt(RichAnswerTrace.Intent intent) {
        RichAnswerTrace.Attempt attempt = new RichAnswerTrace.Attempt();
        attempt.enabled = true;
        attempt.providerEligible = true;
        attempt.intent = intent;
        attempt.pageBudget = intent == RichAnswerTrace.Intent.STRONG_VISUAL
                ? RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG
                : RichAnswerCoordinator.MAX_PAGES_EXAMINED;
        return attempt;
    }

    private List<RichAnswerImage> resolve(List<String> sources, RichAnswerTrace.Attempt attempt,
                                          String prompt) {
        return RichAnswerCoordinator.resolve(context, sources, 1, 0,
                RichAnswerCandidateQuality.Demand.of(prompt), attempt);
    }

    /** The page shape that failed Beta 1 and Beta 2 twice on real hardware. */
    private static String publicationPage(String imageUrl) {
        return "<html><head><title>Widow Spiders | Example Extension</title>"
                + "<link rel=\"stylesheet\" href=\"/style.css\"></head><body>"
                + "<header><img src=\"https://pubs.example.edu/img/logo.png\" alt=\"Example University\"></header>"
                + "<main><article><h1>Widow spiders</h1>"
                + "<p>Identification.</p>"
                + "<figure><img src=\"" + imageUrl + "\" alt=\"Female northern black widow\" "
                + "width=\"800\" height=\"600\">"
                + "<figcaption>Female northern black widow showing the hourglass</figcaption></figure>"
                + "</article></main>"
                + "<footer><img src=\"https://pubs.example.edu/img/seal.png\"></footer></body></html>";
    }

    // ---- the Beta 2 failure ----------------------------------------------------------------------

    /**
     * A cited page with no preview metadata and a photograph in its article now yields the photo.
     *
     * <p>The single most important assertion in this release.
     */
    @Test public void aPageWithNoPreviewMetadataStillYieldsItsArticlePhotograph() {
        String page = "https://pubs.example.edu/content/spiders/widow.html";
        String photo = "https://pubs.example.edu/img/northern-black-widow.jpg";
        pages.html.put(page, publicationPage(photo));
        serveImage(photo);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = resolve(Collections.singletonList(page), trace,
                "Search the web and describe what a Northern black widow looks like, "
                        + "including its important identifying features.");

        assertEquals(1, found.size());
        assertEquals(photo, found.get(0).imageUrl);
        assertEquals(page, found.get(0).sourceUrl);
        assertTrue("the page's own caption is what is drawn",
                found.get(0).caption.contains("hourglass"));

        RichAnswerTrace.PageRecord record = trace.pages.get(0);
        assertEquals(0, record.previewCandidates);
        assertTrue("the article images were genuinely found", record.articleCandidates >= 3);
        assertEquals(RichAnswerTrace.Reason.ACCEPTED, record.candidates.get(0).reason);
    }

    /** And the furniture on that same page is refused rather than drawn. */
    @Test public void theLogoAndSealOnThatPageAreNeverChosen() {
        String page = "https://pubs.example.edu/content/spiders/widow.html";
        String photo = "https://pubs.example.edu/img/northern-black-widow.jpg";
        pages.html.put(page, publicationPage(photo));
        serveImage(photo);
        serveImage("https://pubs.example.edu/img/logo.png");
        serveImage("https://pubs.example.edu/img/seal.png");

        List<RichAnswerImage> found = resolve(Collections.singletonList(page),
                attempt(RichAnswerTrace.Intent.STRONG_VISUAL), "what does a black widow look like");
        assertEquals(photo, found.get(0).imageUrl);
        assertFalse("chrome must not cost a request", images.requested.contains(
                "https://pubs.example.edu/img/logo.png"));
    }

    // ---- discovery order -------------------------------------------------------------------------

    @Test public void aPreviewImageIsUsedWhenThePageDeclaresAGoodOne() {
        String page = "https://example.org/birds/robin";
        String hero = "https://cdn.example.org/photos/european-robin.jpg";
        pages.html.put(page, "<html><head><meta property=\"og:image\" content=\"" + hero + "\">"
                + "<meta property=\"og:description\" content=\"A European robin in snow\">"
                + "</head><body><p>Text</p></body></html>");
        serveImage(hero);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = resolve(Collections.singletonList(page), trace,
                "what does a European robin look like");
        assertEquals(hero, found.get(0).imageUrl);
        assertEquals(1, trace.pages.get(0).previewCandidates);
    }

    /** A preview image that will not load must not end the page. */
    @Test public void aRejectedPreviewImageFallsThroughToTheArticle() {
        String page = "https://example.org/birds/robin";
        String hero = "https://cdn.example.org/photos/hero.jpg";
        String inArticle = "https://cdn.example.org/photos/european-robin-in-snow.jpg";
        // The preview genuinely outranks the article image here - it is the one the page named as
        // being about a robin, and the article image is captioned "Figure 2" - so it is tried
        // first, fails, and the article image is what saves the picture.
        pages.html.put(page, "<html><head><meta property=\"og:image\" content=\"" + hero + "\">"
                + "<meta property=\"og:description\" content=\"A European robin in snow\">"
                + "</head><body><article><img src=\"" + inArticle + "\" alt=\"Figure 2\">"
                + "</article></body></html>");
        serveStatus(hero, 403);
        serveImage(inArticle);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = resolve(Collections.singletonList(page), trace,
                "what does a European robin look like");
        assertEquals(inArticle, found.get(0).imageUrl);

        RichAnswerTrace.PageRecord record = trace.pages.get(0);
        assertTrue("both stages must be visible in the trace",
                record.previewCandidates == 1 && record.articleCandidates >= 1);
        assertTrue(traceContains(record, RichAnswerTrace.Reason.HTTP_ERROR));
        assertTrue(traceContains(record, RichAnswerTrace.Reason.ACCEPTED));
    }

    /** A picture that arrives and turns out to be an icon is refused, and said to be an icon. */
    @Test public void aTinyPictureIsRefusedAfterDecodingAndNamedAsTooSmall() {
        String page = "https://example.org/birds/robin";
        String icon = "https://cdn.example.org/photos/a.jpg";
        String real = "https://cdn.example.org/photos/b.jpg";
        pages.html.put(page, "<html><body><article>"
                + "<img src=\"" + icon + "\" alt=\"A European robin perched\">"
                + "<img src=\"" + real + "\" alt=\"A European robin\">"
                + "</article></body></html>");
        serveImage(icon, 60, 60);
        serveImage(real, 900, 600);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = resolve(Collections.singletonList(page), trace,
                "what does a European robin look like");
        assertEquals(real, found.get(0).imageUrl);
        assertTrue(traceContains(trace.pages.get(0), RichAnswerTrace.Reason.TOO_SMALL));
    }

    // ---- page budget -----------------------------------------------------------------------------

    @Test public void aFailedFirstSourceFallsThroughToTheSecond() {
        String first = "https://example.org/a";
        String second = "https://example.org/b";
        String photo = "https://cdn.example.org/photos/robin.jpg";
        pages.failures.put(first, RichAnswerTrace.Reason.HTTP_ERROR);
        pages.html.put(second, "<html><body><article><img src=\"" + photo
                + "\" alt=\"A European robin\"></article></body></html>");
        serveImage(photo);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.VISUAL);
        List<RichAnswerImage> found = resolve(Arrays.asList(first, second), trace,
                "what does a European robin look like");
        assertEquals(photo, found.get(0).imageUrl);
        assertEquals(2, trace.pagesAttempted);
        assertEquals(RichAnswerTrace.Reason.HTTP_ERROR, trace.pages.get(0).reason);
    }

    /** Strong visual intent is allowed to keep looking after three sources disappoint. */
    @Test public void aStrongVisualRequestMayReachTheFifthSource() {
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < 6; i++) sources.add("https://example.org/page-" + i);
        for (int i = 0; i < 4; i++) {
            pages.html.put(sources.get(i), "<html><body><p>No pictures at all.</p></body></html>");
        }
        String photo = "https://cdn.example.org/photos/widow.jpg";
        pages.html.put(sources.get(4), "<html><body><article><img src=\"" + photo
                + "\" alt=\"A black widow spider\"></article></body></html>");
        serveImage(photo);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = resolve(sources, trace, "show me pictures of a black widow");
        assertEquals(photo, found.get(0).imageUrl);
        assertEquals(RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG, trace.pagesAttempted);
        assertEquals("and never past its own budget",
                RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG, pages.requested.size());
    }

    /** An ordinary visual request keeps the smaller budget it had. */
    @Test public void anOrdinaryVisualRequestStopsAtThreeSources() {
        List<String> sources = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sources.add("https://example.org/page-" + i);
            pages.html.put(sources.get(i), "<html><body><p>No pictures.</p></body></html>");
        }
        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.VISUAL);
        assertTrue(resolve(sources, trace, "best hiking destination in the Dolomites").isEmpty());
        assertEquals(RichAnswerCoordinator.MAX_PAGES_EXAMINED, trace.pagesAttempted);
        assertEquals(RichAnswerCoordinator.MAX_PAGES_EXAMINED, pages.requested.size());
    }

    /** Refusing an address costs no request, so it must not consume one of the chances. */
    @Test public void anUnsafeSourceAddressDoesNotSpendTheBudget() {
        String photo = "https://cdn.example.org/photos/robin.jpg";
        List<String> sources = Arrays.asList(
                "http://insecure.example.org/a", "javascript:alert(1)", "https://example.org/good");
        pages.html.put("https://example.org/good", "<html><body><article><img src=\"" + photo
                + "\" alt=\"A European robin\"></article></body></html>");
        serveImage(photo);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.VISUAL);
        List<RichAnswerImage> found = resolve(sources, trace, "what does a European robin look like");
        assertEquals(photo, found.get(0).imageUrl);
        assertEquals("only the one real page was read", 1, pages.requested.size());
        assertEquals(RichAnswerTrace.Reason.UNSAFE_URL, trace.pages.get(0).reason);
        assertFalse(trace.pages.get(0).fetchAllowed);
    }

    /** Downloads per page are bounded even when the article offers a gallery. */
    @Test public void downloadsPerPageAreBounded() {
        StringBuilder body = new StringBuilder("<html><body><article>");
        for (int i = 0; i < 20; i++) {
            String url = "https://cdn.example.org/photos/robin-" + i + ".jpg";
            body.append("<img src=\"").append(url).append("\" alt=\"A European robin\">");
            serveStatus(url, 404);
        }
        body.append("</article></body></html>");
        pages.html.put("https://example.org/a", body.toString());

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        assertTrue(resolve(Collections.singletonList("https://example.org/a"), trace,
                "what does a European robin look like").isEmpty());
        assertTrue("a gallery must not become a crawl",
                images.requested.size() <= RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE);
    }

    @Test public void aPageWithNoCandidatesSaysSoRatherThanFailingSilently() {
        pages.html.put("https://example.org/a", "<html><body><p>Just words.</p></body></html>");
        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        assertTrue(resolve(Collections.singletonList("https://example.org/a"), trace,
                "show me pictures of a black widow").isEmpty());
        assertEquals(RichAnswerTrace.Reason.NO_CANDIDATES, trace.pages.get(0).reason);
        assertEquals(0, trace.pages.get(0).previewCandidates);
        assertEquals(0, trace.pages.get(0).articleCandidates);
    }

    /** A page returning a PDF where markup was expected is reported as exactly that. */
    @Test public void aNonHtmlPageIsReportedAsNotHtml() {
        pages.failures.put("https://example.org/a.pdf", RichAnswerTrace.Reason.NOT_HTML);
        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        assertTrue(resolve(Collections.singletonList("https://example.org/a.pdf"), trace,
                "show me pictures of a black widow").isEmpty());
        assertEquals(RichAnswerTrace.Reason.NOT_HTML, trace.pages.get(0).reason);
    }

    /** The same picture is never used twice in one answer. */
    @Test public void aPictureAlreadyUsedIsNotUsedAgain() {
        String shared = "https://cdn.example.org/photos/robin.jpg";
        String other = "https://cdn.example.org/photos/robin-two.jpg";
        pages.html.put("https://example.org/a", "<html><body><article><img src=\"" + shared
                + "\" alt=\"A European robin\"></article></body></html>");
        pages.html.put("https://example.org/b", "<html><body><article><img src=\"" + shared
                + "\" alt=\"A European robin\"><img src=\"" + other
                + "\" alt=\"A European robin\"></article></body></html>");
        serveImage(shared);
        serveImage(other);

        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        List<RichAnswerImage> found = RichAnswerCoordinator.resolve(context,
                Arrays.asList("https://example.org/a", "https://example.org/b"), 2, 0,
                RichAnswerCandidateQuality.Demand.of("compare these two European robin photos"), trace);
        assertEquals(2, found.size());
        assertEquals(shared, found.get(0).imageUrl);
        assertEquals(other, found.get(1).imageUrl);
        assertTrue(traceContains(trace.pages.get(1), RichAnswerTrace.Reason.DUPLICATE));
    }

    // ---- loader diagnostics ----------------------------------------------------------------------

    @Test public void theLoaderReportsAnHttpFailureWithItsStatus() {
        serveStatus("https://cdn.example.org/a.jpg", 403);
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://cdn.example.org/a.jpg");
        assertFalse(result.loaded());
        assertEquals(RemoteImageLoader.Failure.HTTP_ERROR, result.failure);
        assertEquals(403, result.status);
        assertEquals(RichAnswerTrace.Reason.HTTP_ERROR,
                RichAnswerCoordinator.reasonFor(result.failure));
    }

    @Test public void theLoaderReportsAWrongContentTypeAndKeepsIt() {
        byte[] bytes = "%PDF-1.4 not a picture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        images.responses.put("https://cdn.example.org/a.pdf", new RemoteImageLoader.Response(
                200, "application/pdf", null, bytes.length, new ByteArrayInputStream(bytes)));
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://cdn.example.org/a.pdf");
        assertEquals(RemoteImageLoader.Failure.NOT_AN_IMAGE, result.failure);
        assertEquals("application/pdf", result.contentType);
        assertEquals(RichAnswerTrace.Reason.NOT_IMAGE,
                RichAnswerCoordinator.reasonFor(result.failure));
    }

    /**
     * A format this device cannot decode is named as a format problem, with its type kept.
     *
     * <p>The neighbouring case - bytes that claim to be a JPEG and are not - cannot be exercised
     * under Robolectric, whose {@code BitmapFactory} hands back a stub bitmap for any input. The
     * translation for it is asserted directly instead, below.
     */
    @Test public void theLoaderReportsAFormatItCannotDecode() {
        byte[] bytes = new byte[2048];
        images.responses.put("https://cdn.example.org/a.ico", new RemoteImageLoader.Response(
                200, "image/x-icon", null, bytes.length, new ByteArrayInputStream(bytes)));
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://cdn.example.org/a.ico");
        assertEquals(RemoteImageLoader.Failure.UNSUPPORTED_FORMAT, result.failure);
        assertEquals("image/x-icon", result.contentType);
        assertEquals(RichAnswerTrace.Reason.UNSUPPORTED_FORMAT,
                RichAnswerCoordinator.reasonFor(result.failure));
        assertEquals(RichAnswerTrace.Reason.DECODE_FAILED,
                RichAnswerCoordinator.reasonFor(RemoteImageLoader.Failure.DECODE_FAILED));
    }

    /** A size past the ceiling is refused before the whole body is read, and says so. */
    @Test public void theLoaderReportsASizeItRefused() {
        images.responses.put("https://cdn.example.org/huge.jpg", new RemoteImageLoader.Response(
                200, "image/jpeg", null, 64L * 1024 * 1024, new ByteArrayInputStream(new byte[8])));
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://cdn.example.org/huge.jpg");
        assertEquals(RemoteImageLoader.Failure.TOO_LARGE, result.failure);
        assertEquals("image/jpeg", result.contentType);
        assertEquals(RichAnswerTrace.Reason.TOO_LARGE,
                RichAnswerCoordinator.reasonFor(result.failure));
    }

    /** A successful fetch keeps everything the transport observed, for the trace to print. */
    @Test public void aSuccessfulFetchKeepsWhatWasObserved() {
        serveImage("https://cdn.example.org/a.jpg", 900, 600);
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://cdn.example.org/a.jpg");
        assertTrue(result.loaded());
        assertEquals("image/jpeg", result.contentType);
        assertTrue(result.bytesRead > 0);
        assertEquals(0, result.redirects);
        assertEquals(900, result.decodedWidth);
        assertEquals(600, result.decodedHeight);
    }

    @Test public void theLoaderReportsRedirectsItFollowed() {
        String start = "https://cdn.example.org/a.jpg";
        String middle = "https://cdn.example.org/b.jpg";
        String end = "https://cdn.example.org/c.jpg";
        images.responses.put(start, new RemoteImageLoader.Response(301, null, middle, 0, null));
        images.responses.put(middle, new RemoteImageLoader.Response(302, null, end, 0, null));
        serveImage(end);
        RemoteImageLoader.Result result = RemoteImageLoader.fetchForRichAnswer(context, start);
        assertTrue(result.loaded());
        assertEquals(2, result.redirects);
        assertEquals(800, result.decodedWidth);
        assertEquals(600, result.decodedHeight);
    }

    @Test public void theLoaderReportsAPrivateHostRefusal() {
        images.refusedHosts.add("internal.example.org");
        serveImage("https://internal.example.org/a.jpg");
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchForRichAnswer(context, "https://internal.example.org/a.jpg");
        assertEquals(RemoteImageLoader.Failure.BLOCKED, result.failure);
        assertTrue("a refused address costs no request", images.requested.isEmpty());
        assertEquals(RichAnswerTrace.Reason.UNSAFE_URL,
                RichAnswerCoordinator.reasonFor(result.failure));
    }

    @Test public void theLoaderRefusesAnUnsafeAddressBeforeAnythingElse() {
        for (String url : new String[]{
                "http://cdn.example.org/a.jpg", "file:///etc/passwd", "javascript:alert(1)",
                "content://media/1", "intent://x", "https://user:pw@cdn.example.org/a.jpg"}) {
            assertEquals(url + " must never be fetched", RemoteImageLoader.Failure.BLOCKED,
                    RemoteImageLoader.fetchForRichAnswer(context, url).failure);
        }
        assertTrue(images.requested.isEmpty());
    }

    /** Every transport failure maps to a reason the trace can print. */
    @Test public void everyLoaderFailureHasATraceReason() {
        for (RemoteImageLoader.Failure failure : RemoteImageLoader.Failure.values()) {
            RichAnswerTrace.Reason reason = RichAnswerCoordinator.reasonFor(failure);
            assertNotNull(failure + " must translate", reason);
            assertFalse(reason == RichAnswerTrace.Reason.NONE);
        }
        assertEquals(RichAnswerTrace.Reason.NETWORK, RichAnswerCoordinator.reasonFor(null));
    }

    // ---- the Markdown path shares all of this ----------------------------------------------------

    /**
     * A model-written Markdown image gets exactly the Rich Answer safety policy, not a weaker one.
     *
     * <p>The rule that matters: a model must not be able to reach an address by writing
     * {@code ![](...)} that Rich Answers would have refused. There is one transport and one policy,
     * so the two paths cannot drift.
     */
    @Test public void theMarkdownImagePathUsesTheSameTransportAndPolicy() {
        images.refusedHosts.add("internal.example.org");
        for (String url : new String[]{
                "http://cdn.example.org/a.jpg", "file:///etc/passwd", "javascript:alert(1)",
                "content://media/1", "https://user:pw@cdn.example.org/a.jpg",
                "https://internal.example.org/a.jpg"}) {
            assertFalse(url + " must be refused before the Markdown card even tries",
                    RemoteImageLoader.fetchPicture(context, url).loaded());
        }
        assertTrue("and none of them may reach the network", images.requested.isEmpty());

        String renderer = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/OrbitRichResponseRenderer.java");
        for (String forbidden : new String[]{
                "HttpURLConnection", "openConnection", "setRequestProperty", "URLConnection"}) {
            assertFalse("the renderer must never fetch a picture itself: " + forbidden,
                    renderer.contains(forbidden));
        }
    }

    /** A failed Markdown image degrades to a compact card, and never to a crash. */
    @Test public void aFailedMarkdownImageStillProducesAResultRatherThanAnException() {
        serveStatus("https://cdn.example.org/missing.jpg", 404);
        RemoteImageLoader.Result result =
                RemoteImageLoader.fetchPicture(context, "https://cdn.example.org/missing.jpg");
        assertFalse(result.loaded());
        assertEquals("HTTP 404", result.describe());
        assertEquals("a refusal says so without naming the address",
                "Orbit blocked this private or unsafe image address",
                RemoteImageLoader.fetchPicture(context, "file:///etc/passwd").describe());
    }

    // ---- ranking through the pipeline ------------------------------------------------------------

    /** The subject decides, and it decides on the page's own words rather than the filename. */
    @Test public void theSubjectDecidesBetweenTwoArticlePhotographs() {
        String page = "https://pubs.example.edu/content/spiders/widow.html";
        String wrong = "https://cdn.example.edu/img/aaa111.jpg";
        String right = "https://cdn.example.edu/img/bbb222.jpg";
        pages.html.put(page, "<html><body><article>"
                + "<img src=\"" + wrong + "\" alt=\"The entomology building in summer\">"
                + "<img src=\"" + right + "\" alt=\"Female northern black widow underside\">"
                + "</article></body></html>");
        serveImage(wrong);
        serveImage(right);

        List<RichAnswerImage> found = resolve(Collections.singletonList(page),
                attempt(RichAnswerTrace.Intent.STRONG_VISUAL),
                "Search the web and describe what a Northern black widow looks like.");
        assertEquals(right, found.get(0).imageUrl);
        assertEquals("the wrong one must not even be fetched", 1, images.requested.size());
    }

    /** The caption drawn under the picture is the page's words for the picture, not the article. */
    @Test public void theCaptionPrefersTheFigureOverThePageDescription() {
        String page = "https://pubs.example.edu/content/spiders/widow.html";
        String photo = "https://cdn.example.edu/img/nbw.jpg";
        pages.html.put(page, "<html><head>"
                + "<meta property=\"og:description\" content=\"Extension publication 444-422\">"
                + "</head><body><article><figure><img src=\"" + photo + "\" alt=\"A widow\">"
                + "<figcaption>Female northern black widow, ventral view</figcaption>"
                + "</figure></article></body></html>");
        serveImage(photo);

        List<RichAnswerImage> found = resolve(Collections.singletonList(page),
                attempt(RichAnswerTrace.Intent.STRONG_VISUAL),
                "what does a northern black widow look like");
        assertTrue(found.get(0).caption.contains("ventral view"));
    }

    // ---- attachment ------------------------------------------------------------------------------

    /**
     * A picture that downloads perfectly and has nowhere to go is its own outcome.
     *
     * <p>The distinction Beta 2 could not make. "No image appeared" is a symptom of both, and the
     * fixes are in completely different parts of the app.
     */
    private String chatWith(String answerText) {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(
                new AssistantClient.History("user", "what does a black widow look like"),
                new AssistantClient.History("assistant", answerText)));
        return id;
    }

    private List<RichAnswerImage> onePicture() {
        return Collections.singletonList(RichAnswerImage.webSource(
                "https://cdn.example.edu/img/nbw.jpg",
                "https://pubs.example.edu/a", "A widow", "A widow", 0));
    }

    @Test public void attachingToAnAnswerThatIsNoLongerThereIsItsOwnFailure() {
        String conversation = chatWith("A shiny black spider.");
        assertFalse("no such answer text in this chat",
                ConversationStore.attachRichImages(context, conversation,
                        "An answer that was never written", onePicture()));
    }

    @Test public void attachingToTheRightAnswerSucceedsOnce() {
        String conversation = chatWith("A shiny black spider.");
        assertTrue(ConversationStore.attachRichImages(
                context, conversation, "A shiny black spider.", onePicture()));
        assertFalse("and never twice", ConversationStore.attachRichImages(
                context, conversation, "A shiny black spider.", onePicture()));
    }

    // ---- eligibility -----------------------------------------------------------------------------

    /**
     * An ordinary chat message must not fill a five-slot buffer with things nobody wanted a picture
     * from, or the one failure the user is trying to report gets pushed out of it.
     */
    @Test public void anAnswerThatCitedNothingRecordsNoAttempt() {
        RichAnswerCoordinator.discover(context, "chat-1", "req-1", "what is 2 + 2",
                new AssistantReply("Four."));
        assertTrue(RichAnswerTrace.attempts(context).isEmpty());
    }

    /** A web answer to a non-visual question is recorded, so "why no picture" has an answer. */
    @Test public void aWebAnswerThatWasNotVisualRecordsWhyItWasSkipped() {
        RichAnswerCoordinator.discover(context, "chat-1", "req-1",
                "What is the population of Michigan?",
                new AssistantReply("About 10 million.")
                        .withSourceUrls(Collections.singletonList("https://example.org/mi")));
        RichAnswerTrace.Attempt recorded = RichAnswerTrace.last(context);
        assertNotNull(recorded);
        assertEquals(RichAnswerTrace.Outcome.NOT_VISUAL, recorded.outcome);
        assertEquals(RichAnswerTrace.Intent.NONE, recorded.intent);
        assertEquals(1, recorded.sourcesReceived);
        assertTrue("and nothing was fetched to decide it", pages.requested.isEmpty());
    }

    // ---- bounds ----------------------------------------------------------------------------------

    /** Every ceiling this feature relies on, asserted as a set rather than one at a time. */
    @Test public void everyBoundStaysBounded() {
        assertTrue(RichAnswerCoordinator.MAX_PAGES_EXAMINED
                < RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG);
        assertTrue(RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG <= 5);
        assertTrue(RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE
                <= RichAnswerCoordinator.MAX_RANKED_CANDIDATES_PER_PAGE);
        assertTrue(RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE <= 5);
        assertTrue(RichAnswerCoordinator.MAX_RANKED_CANDIDATES_PER_PAGE <= 8);
        assertTrue(RichAnswerArticleImages.MAX_CANDIDATES <= 32);
        assertTrue(RichAnswerPageFetcher.MAX_BYTES <= 1024 * 1024);
        assertTrue(RichAnswerImage.MAX_PER_MESSAGE <= 2);
        assertTrue(RichAnswerTrace.MAX_ATTEMPTS <= 5);

        // The worst case one strongly visual answer can cost, stated as a number.
        int worstCasePageReads = RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG;
        int worstCaseImageReads = worstCasePageReads
                * RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE;
        assertTrue("a strongly visual answer must stay a handful of requests, never a crawl",
                worstCasePageReads + worstCaseImageReads <= 30);
    }

    /** Discovery must never be able to reach the user's own data. */
    @Test public void discoveryReachesNoneOfTheUsersOwnStores() {
        for (String file : new String[]{
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java",
                "app/src/main/java/com/orbit/assistant/RichAnswerArticleImages.java",
                "app/src/main/java/com/orbit/assistant/RichAnswerSubject.java",
                "app/src/main/java/com/orbit/assistant/RichAnswerTrace.java"}) {
            String source = ComponentUninstallTest.readRepositoryFile(file);
            for (String forbidden : new String[]{
                    "OrbitVaultStore", "MemoryStore", "NotificationStore", "AttachmentStore",
                    "SecureStore", "ConversationStore.list", "setRequestProperty"}) {
                assertFalse(file + " must not reach " + forbidden, source.contains(forbidden));
            }
        }
    }

    /**
     * The subject words are local, and they must not be able to leak through diagnostics.
     *
     * <p>The fixture's addresses are deliberately opaque, so a subject word appearing in the report
     * could only have come from the prompt - a page whose own path happens to contain the word is
     * recording its own address, which is a different thing and is allowed.
     */
    @Test public void subjectWordsAreNeverWrittenIntoTheTrace() {
        String page = "https://pubs.example.edu/p/444-422";
        String photo = "https://cdn.example.edu/i/9f8a7b6c";
        pages.html.put(page, "<html><body><article><figure>"
                + "<img src=\"" + photo + "\" alt=\"Female northern black widow\">"
                + "<figcaption>Northern black widow, ventral view</figcaption>"
                + "</figure></article></body></html>");
        serveStatus(photo, 404);
        RichAnswerTrace.Attempt trace = attempt(RichAnswerTrace.Intent.STRONG_VISUAL);
        resolve(Collections.singletonList(page), trace,
                "Search the web and describe what a Northern black widow looks like.");
        RichAnswerTrace.record(context, trace);

        String report = RichAnswerTrace.report(context).toLowerCase(java.util.Locale.US);
        assertTrue("the attempt really was recorded", report.contains("pubs.example.edu"));
        assertTrue("and its failure really was seen", report.contains("http_error"));
        for (String token : RichAnswerSubject.tokensOf("Northern black widow")) {
            assertFalse("the subject word " + token + " must not reach a copied report",
                    report.contains(token));
        }
        assertFalse("nor may a page's own words about a picture",
                report.contains("ventral"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private boolean traceContains(RichAnswerTrace.PageRecord record, RichAnswerTrace.Reason reason) {
        for (RichAnswerTrace.CandidateRecord candidate : record.candidates) {
            if (candidate.reason == reason) return true;
        }
        return false;
    }
}

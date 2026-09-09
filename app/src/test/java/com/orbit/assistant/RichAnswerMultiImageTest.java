package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import java.util.List;
import java.util.Map;

/**
 * Two pictures, and the rules that decide whether there really are two.
 *
 * <p><b>This file is the regression test for Beta 6.</b> Its central case is the exact shape the
 * device produced: one recovered source page, one photograph on it offered at three widths, and a
 * user who asked for pictures in the plural. Beta 5 answered that with one picture because a page
 * could only ever contribute one; the naive fix answers it with the same duck twice. The assertions
 * below hold both of those wrong.
 *
 * <p>No network anywhere. Pages and pictures are served by the same two seams the rest of the Rich
 * Answers suite uses, so what is being exercised is the resolver's decisions rather than a socket.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerMultiImageTest {

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
        final Map<String, Integer> httpFailures = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();

        @Override public RichAnswerPageFetcher.PageResult fetch(String pageUrl) {
            requested.add(pageUrl);
            Integer status = httpFailures.get(pageUrl);
            if (status != null) {
                return RichAnswerPageFetcher.PageResult.failed(
                        RichAnswerTrace.Reason.HTTP_ERROR, pageUrl, status, "", 0);
            }
            String body = html.get(pageUrl);
            if (body == null) {
                return RichAnswerPageFetcher.PageResult.failed(
                        RichAnswerTrace.Reason.HTTP_ERROR, pageUrl, 404, "", 0);
            }
            return RichAnswerPageFetcher.parse(body, pageUrl, 200, "text/html; charset=UTF-8", 0);
        }
    }

    private void serveImage(String url) { serveImage(url, 900, 700); }

    /**
     * A real photograph, one per underlying asset.
     *
     * <p>Seeded on the canonical asset rather than on the address, so three widths of one Mallard
     * really are one photograph and two different files really are two - which is what the
     * assertions here are about. Patterned rather than flat because Beta 9 refuses a flat image for
     * a photograph request, and a fixture that is a solid grey rectangle would be testing that
     * refusal instead of the thing under test.
     */
    private void serveImage(String url, int width, int height) {
        String asset = RichAnswerAssetIdentity.canonical(url);
        Integer seed = seeds.get(asset);
        if (seed == null) {
            seed = seeds.size() + 1;
            seeds.put(asset, seed);
        }
        byte[] bytes = TestPng.photo(width, height, seed);
        images.responses.put(url, new RemoteImageLoader.Response(
                200, "image/jpeg", null, bytes.length, new ByteArrayInputStream(bytes)));
    }

    /** One seed per underlying asset, handed out in order so no two fixtures collide. */
    private final Map<String, Integer> seeds = new LinkedHashMap<>();

    private RichAnswerTrace.Attempt attempt() {
        RichAnswerTrace.Attempt attempt = new RichAnswerTrace.Attempt();
        attempt.enabled = true;
        attempt.providerEligible = true;
        attempt.intent = RichAnswerTrace.Intent.STRONG_VISUAL;
        attempt.pageBudget = RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG;
        return attempt;
    }

    private List<RichAnswerImage> resolve(List<String> sources, int wanted, String prompt,
                                          RichAnswerTrace.Attempt trace) {
        trace.requestedImages = wanted;
        return RichAnswerCoordinator.resolve(context, sources, wanted, 0,
                RichAnswerCandidateQuality.Demand.of(prompt), trace);
    }

    private List<RichAnswerImage> resolve(List<String> sources,
                                          List<RichAnswerDiscoveryHint> hints, int wanted,
                                          String prompt, RichAnswerTrace.Attempt trace) {
        trace.requestedImages = wanted;
        trace.discoveryHintsConsidered = hints.size();
        return RichAnswerCoordinator.resolve(context, sources, hints, wanted, 0,
                RichAnswerCandidateQuality.Demand.of(prompt), trace);
    }

    private static List<String> urlsOf(List<RichAnswerImage> found) {
        List<String> urls = new ArrayList<>();
        for (RichAnswerImage image : found) urls.add(image.imageUrl);
        return urls;
    }

    // ---- the Mallard shape -----------------------------------------------------------------------

    private static final String COMMONS_FILE =
            "https://commons.wikimedia.org/wiki/File:Mallard-Duck.jpg";
    private static final String UPLOAD = "https://upload.wikimedia.org/wikipedia/commons/";
    private static final String MALLARD_1920 =
            UPLOAD + "thumb/1/1e/Mallard-Duck.jpg/1920px-Mallard-Duck.jpg";
    private static final String MALLARD_1280 =
            UPLOAD + "thumb/1/1e/Mallard-Duck.jpg/1280px-Mallard-Duck.jpg";
    private static final String MALLARD_960 =
            UPLOAD + "thumb/1/1e/Mallard-Duck.jpg/960px-Mallard-Duck.jpg";

    /** The real page: one duck, three widths, three different origins declaring them. */
    private static String mallardFilePage(String extraPhoto) {
        return "<html><head><title>File:Mallard-Duck.jpg</title>"
                + "<meta property=\"og:image\" content=\"" + MALLARD_1280 + "\">"
                + "</head><body><main><article>"
                + "<img src=\"" + MALLARD_960 + "\" srcset=\"" + MALLARD_1920 + " 1920w\" "
                + "alt=\"A mallard duck drake on water\">"
                + (extraPhoto == null ? "" : "<figure><img src=\"" + extraPhoto
                        + "\" alt=\"A female mallard duck with ducklings\">"
                        + "<figcaption>A female mallard duck with ducklings</figcaption></figure>")
                + "</article></main></body></html>";
    }

    /**
     * Three renditions of one photograph never become two pictures.
     *
     * <p>The exact diagnostic the device printed: SRCSET 1920, ARTICLE_IMG 960 and OG_IMAGE 1280,
     * all of them {@code Mallard-Duck.jpg}. Two are wanted; one exists.
     */
    @Test public void threeWidthsOfOneMallardPhotographYieldOnePicture() {
        pages.html.put(COMMONS_FILE, mallardFilePage(null));
        serveImage(MALLARD_1920);
        serveImage(MALLARD_1280);
        serveImage(MALLARD_960);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(COMMONS_FILE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals("one photograph is one picture, however many widths it is offered at",
                1, found.size());
        assertTrue(RichAnswerAssetIdentity.sameAsset(found.get(0).imageUrl, MALLARD_960));
        assertTrue("the duplicates must be named as duplicates",
                traceContains(trace, RichAnswerTrace.Reason.DUPLICATE));
        assertEquals("and a known duplicate must never cost a request",
                1, images.requested.size());
    }

    /** A genuinely second photograph on the same page is a genuinely second picture. */
    @Test public void oneGalleryPageMaySupplyTwoDistinctPhotographs() {
        String female = UPLOAD + "thumb/2/2a/Mallard-Female.jpg/1024px-Mallard-Female.jpg";
        pages.html.put(COMMONS_FILE, mallardFilePage(female));
        serveImage(MALLARD_1920);
        serveImage(MALLARD_1280);
        serveImage(MALLARD_960);
        serveImage(female);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(COMMONS_FILE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals(2, found.size());
        assertFalse("two renditions of one photograph are not two photographs",
                RichAnswerAssetIdentity.sameAsset(found.get(0).imageUrl, found.get(1).imageUrl));
        assertTrue("both must come from the page that was actually read",
                found.get(0).sourceUrl.equals(COMMONS_FILE)
                        && found.get(1).sourceUrl.equals(COMMONS_FILE));
        assertEquals("one page, so one page attempt", 1, trace.pagesAttempted);
    }

    /** A singular request takes exactly one picture from a page that offers several. */
    @Test public void aSingularRequestStillTakesOnlyOnePicture() {
        String female = UPLOAD + "thumb/2/2a/Mallard-Female.jpg/1024px-Mallard-Female.jpg";
        pages.html.put(COMMONS_FILE, mallardFilePage(female));
        serveImage(MALLARD_960);
        serveImage(MALLARD_1280);
        serveImage(MALLARD_1920);
        serveImage(female);

        List<RichAnswerImage> found = resolve(Collections.singletonList(COMMONS_FILE), 1,
                "what does a mallard duck look like", attempt());
        assertEquals(1, found.size());
    }

    // ---- across sources --------------------------------------------------------------------------

    /** A page that supplies only one picture does not lose it, and the search continues. */
    @Test public void aFirstPageWithOnePhotographKeepsItAndTheSearchContinues() {
        String first = "https://birds.example.org/mallard";
        String second = "https://guide.example.net/ducks/mallard";
        String photoA = "https://cdn.example.org/photos/mallard-drake.jpg";
        String photoB = "https://cdn.example.net/photos/mallard-hen.jpg";
        pages.html.put(first, "<html><body><article><img src=\"" + photoA
                + "\" alt=\"A mallard duck drake\"></article></body></html>");
        pages.html.put(second, "<html><body><article><img src=\"" + photoB
                + "\" alt=\"A mallard duck hen\"></article></body></html>");
        serveImage(photoA);
        serveImage(photoB);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Arrays.asList(first, second), 2,
                "show me pictures of a mallard duck", trace);

        assertEquals(2, found.size());
        assertEquals(Arrays.asList(photoA, photoB), urlsOf(found));
        assertEquals(first, found.get(0).sourceUrl);
        assertEquals(second, found.get(1).sourceUrl);
        assertEquals(2, trace.pagesAttempted);
    }

    /** The same photograph offered by two different pages is still one photograph. */
    @Test public void thesamePhotographOnTwoPagesIsNotTwoPictures() {
        String first = "https://birds.example.org/mallard";
        String second = "https://guide.example.org/ducks/mallard";
        pages.html.put(first, "<html><body><article><img src=\"" + MALLARD_1920
                + "\" alt=\"A mallard duck\"></article></body></html>");
        pages.html.put(second, "<html><body><article><img src=\"" + MALLARD_960
                + "\" alt=\"A mallard duck\"></article></body></html>");
        serveImage(MALLARD_1920);
        serveImage(MALLARD_960);

        List<RichAnswerImage> found = resolve(Arrays.asList(first, second), 2,
                "show me pictures of a mallard duck", attempt());
        assertEquals(1, found.size());
    }

    /** Showing one good picture beats showing a good one and a bad one. */
    @Test public void aBadSecondCandidateIsRefusedRatherThanUsedToMakeUpTheCount() {
        String page = "https://birds.example.org/mallard";
        String photo = "https://cdn.example.org/photos/mallard-drake.jpg";
        String tiny = "https://cdn.example.org/photos/mallard-badge.jpg";
        pages.html.put(page, "<html><body><article>"
                + "<img src=\"" + photo + "\" alt=\"A mallard duck drake\">"
                + "<img src=\"" + tiny + "\" alt=\"A mallard duck badge\">"
                + "</article></body></html>");
        serveImage(photo);
        serveImage(tiny, 64, 64);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(page), 2,
                "show me pictures of a mallard duck", trace);

        assertEquals(1, found.size());
        assertEquals(photo, found.get(0).imageUrl);
        assertTrue(traceContains(trace, RichAnswerTrace.Reason.TOO_SMALL));
    }

    /** A blocked primary source is not the end of an explicitly plural search. */
    @Test public void aBlockedPrimarySourceDoesNotEndThePluralSearch() {
        String blocked = "https://extension.example.edu/ducks";
        String usable = "https://guide.example.net/ducks/mallard";
        String photoA = "https://cdn.example.net/photos/mallard-drake.jpg";
        String photoB = "https://cdn.example.net/photos/mallard-hen.jpg";
        pages.httpFailures.put(blocked, 403);
        pages.html.put(usable, "<html><body><article>"
                + "<img src=\"" + photoA + "\" alt=\"A mallard duck drake\">"
                + "<img src=\"" + photoB + "\" alt=\"A mallard duck hen in reeds\">"
                + "</article></body></html>");
        serveImage(photoA);
        serveImage(photoB);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Arrays.asList(blocked, usable), 2,
                "show me pictures of a mallard duck", trace);

        assertEquals(2, found.size());
        assertEquals("the refused page is still refused",
                RichAnswerTrace.Reason.HTTP_ERROR, trace.pages.get(0).reason);
        assertEquals(403, trace.pages.get(0).httpStatus);
    }

    // ---- discovery hints -------------------------------------------------------------------------

    /**
     * When the only trusted source runs dry, an image link in the answer may supply the second.
     *
     * <p>The hint's picture is attributed to the page the picture came from, and nothing about the
     * hint is promoted into the answer's own provenance.
     */
    @Test public void aDiscoveryHintCanSupplyTheSecondPictureWhenTheSourceCannot() {
        String source = "https://birds.example.org/mallard";
        String hint = "https://gallery.example.net/mallard-photos";
        String photoA = "https://cdn.example.org/photos/mallard-drake.jpg";
        String photoB = "https://cdn.example.net/photos/mallard-hen.jpg";
        pages.html.put(source, "<html><body><article><img src=\"" + photoA
                + "\" alt=\"A mallard duck drake\"></article></body></html>");
        pages.html.put(hint, "<html><body><article><img src=\"" + photoB
                + "\" alt=\"A mallard duck hen in reeds\"></article></body></html>");
        serveImage(photoA);
        serveImage(photoB);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerDiscoveryHint> hints = RichAnswerDiscoveryHint.from(
                "Here are some useful mallard references.\n\n[Mallard photos](" + hint + ")",
                RichAnswerSubject.tokensOf("show me pics of a mallard duck"),
                Collections.singletonList(source));
        assertEquals(1, hints.size());

        List<RichAnswerImage> found = resolve(Collections.singletonList(source), hints, 2,
                "show me pics of a mallard duck", trace);

        assertEquals(2, found.size());
        assertEquals(photoB, found.get(1).imageUrl);
        assertEquals("the picture is attributed to the page it came from",
                hint, found.get(1).sourceUrl);
        assertEquals(1, trace.discoveryHintsUsed);
        assertTrue("and the hint page is marked as one in the report",
                trace.pages.get(1).discoveryHint);
        assertFalse("while the cited source is not", trace.pages.get(0).discoveryHint);
    }

    /** A hint is never consulted once the trusted sources have already produced enough. */
    @Test public void hintsAreNotFollowedWhenTheSourcesAlreadySufficed() {
        String source = "https://birds.example.org/mallard";
        String hint = "https://gallery.example.net/mallard-photos";
        String photoA = "https://cdn.example.org/photos/mallard-drake.jpg";
        String photoB = "https://cdn.example.org/photos/mallard-hen.jpg";
        pages.html.put(source, "<html><body><article>"
                + "<img src=\"" + photoA + "\" alt=\"A mallard duck drake\">"
                + "<img src=\"" + photoB + "\" alt=\"A mallard duck hen in reeds\">"
                + "</article></body></html>");
        serveImage(photoA);
        serveImage(photoB);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerDiscoveryHint> hints = RichAnswerDiscoveryHint.from(
                "[Mallard photos](" + hint + ")",
                RichAnswerSubject.tokensOf("show me pics of a mallard duck"),
                Collections.singletonList(source));

        List<RichAnswerImage> found = resolve(Collections.singletonList(source), hints, 2,
                "show me pics of a mallard duck", trace);
        assertEquals(2, found.size());
        assertEquals(0, trace.discoveryHintsUsed);
        assertFalse(pages.requested.contains(hint));
    }

    /** A hint written as the picture itself is fetched as a picture, not parsed as markup. */
    @Test public void aDirectImageHintIsFetchedAsAnImage() {
        String source = "https://birds.example.org/mallard";
        String photoA = "https://cdn.example.org/photos/mallard-drake.jpg";
        String photoB = "https://cdn.example.net/photos/mallard-hen.jpg";
        pages.html.put(source, "<html><body><article><img src=\"" + photoA
                + "\" alt=\"A mallard duck drake\"></article></body></html>");
        serveImage(photoA);
        serveImage(photoB);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerDiscoveryHint> hints = RichAnswerDiscoveryHint.from(
                "![A mallard hen](" + photoB + ")",
                RichAnswerSubject.tokensOf("show me pics of a mallard duck"),
                Collections.singletonList(source));
        assertEquals(1, hints.size());
        assertTrue(hints.get(0).isImage());

        List<RichAnswerImage> found = resolve(Collections.singletonList(source), hints, 2,
                "show me pics of a mallard duck", trace);
        assertEquals(2, found.size());
        assertEquals(photoB, found.get(1).imageUrl);
        assertFalse("a picture is never read as a page", pages.requested.contains(photoB));
    }

    /** A hint that turns out to be the picture already shown adds nothing. */
    @Test public void aDirectImageHintThatDuplicatesTheFirstPictureIsRefused() {
        String source = "https://birds.example.org/mallard";
        pages.html.put(source, "<html><body><article><img src=\"" + MALLARD_1920
                + "\" alt=\"A mallard duck drake\"></article></body></html>");
        serveImage(MALLARD_1920);
        serveImage(MALLARD_960);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerDiscoveryHint> hints = RichAnswerDiscoveryHint.from(
                "![A mallard](" + MALLARD_960 + ")",
                RichAnswerSubject.tokensOf("show me pics of a mallard duck"),
                Collections.singletonList(source));

        List<RichAnswerImage> found = resolve(Collections.singletonList(source), hints, 2,
                "show me pics of a mallard duck", trace);
        assertEquals(1, found.size());
        assertFalse("a known duplicate must not cost a request",
                images.requested.contains(MALLARD_960));
    }

    // ---- diagnostics -----------------------------------------------------------------------------

    /** The report has to say what was asked for and what arrived, and when they differ. */
    @Test public void theReportSaysBothWhatWasAskedForAndWhatArrived() {
        RichAnswerTrace.Attempt short_ = attempt();
        short_.requestedImages = 2;
        short_.attachedImages = 1;
        short_.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
        String report = RichAnswerTrace.describe(short_, 1);
        assertTrue(report.contains("Images requested: 2"));
        assertTrue(report.contains("Images attached: 1"));
        assertTrue(report.contains("Second image unavailable"));

        RichAnswerTrace.Attempt full = attempt();
        full.requestedImages = 2;
        full.attachedImages = 2;
        full.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
        String complete = RichAnswerTrace.describe(full, 1);
        assertTrue(complete.contains("Images attached: 2"));
        assertFalse(complete.contains("Second image unavailable"));
    }

    /** Hint counts are reported, and only ever as counts. */
    @Test public void discoveryHintsAreReportedAsBoundedCounts() {
        RichAnswerTrace.Attempt trace = attempt();
        trace.requestedImages = 2;
        trace.attachedImages = 2;
        trace.discoveryHintsConsidered = 3;
        trace.discoveryHintsUsed = 1;
        trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
        String report = RichAnswerTrace.describe(trace, 1);
        assertTrue(report.contains("Discovery hints considered: 3"));
        assertTrue(report.contains("Discovery hints used: 1"));

        RichAnswerTrace.Attempt none = attempt();
        none.outcome = RichAnswerTrace.Outcome.NO_USABLE_IMAGE;
        assertFalse("an attempt that used none must not print an empty section",
                RichAnswerTrace.describe(none, 1).contains("Discovery hints"));
    }

    /** The new fields survive the round trip through storage. */
    @Test public void theNewFieldsSurviveBeingStoredAndReadBack() {
        RichAnswerTrace.Attempt trace = attempt();
        trace.requestedImages = 2;
        trace.attachedImages = 2;
        trace.discoveryHintsConsidered = 4;
        trace.discoveryHintsUsed = 2;
        trace.outcome = RichAnswerTrace.Outcome.IMAGE_FOUND_AND_ATTACHED;
        RichAnswerTrace.PageRecord page = trace.page();
        page.host = "gallery.example.net";
        page.discoveryHint = true;
        RichAnswerTrace.record(context, trace);

        RichAnswerTrace.Attempt back = RichAnswerTrace.last(context);
        assertEquals(2, back.requestedImages);
        assertEquals(2, back.attachedImages);
        assertEquals(4, back.discoveryHintsConsidered);
        assertEquals(2, back.discoveryHintsUsed);
        assertTrue(back.pages.get(0).discoveryHint);
    }

    /** Nothing about the question or the answer reaches the report. */
    @Test public void theReportStillCarriesNoContent() {
        String source = "https://birds.example.org/reference/a1b2";
        String photo = "https://cdn.example.org/assets/a1b2.jpg";
        pages.html.put(source, "<html><body><article><img src=\"" + photo
                + "\" alt=\"A mallard duck drake\"></article></body></html>");
        serveImage(photo);

        RichAnswerTrace.Attempt trace = attempt();
        resolve(Collections.singletonList(source), 2, "show me pics of a mallard duck", trace);
        trace.attachedImages = 1;
        RichAnswerTrace.record(context, trace);

        String report = RichAnswerTrace.report(context).toLowerCase(java.util.Locale.US);
        assertFalse(report.contains("mallard"));
        assertFalse(report.contains("show me"));
        assertFalse(report.contains("drake"));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static boolean traceContains(RichAnswerTrace.Attempt trace,
                                         RichAnswerTrace.Reason reason) {
        for (RichAnswerTrace.PageRecord page : trace.pages) {
            for (RichAnswerTrace.CandidateRecord candidate : page.candidates) {
                if (candidate.reason == reason) return true;
            }
        }
        return false;
    }

    /** Guards the assumption the whole file rests on. */
    @Test public void theTwoImageCeilingIsUnchanged() {
        assertEquals(2, RichAnswerImage.MAX_PER_MESSAGE);
        assertEquals(4, RichAnswerCoordinator.MAX_FETCHED_CANDIDATES_PER_PAGE);
        assertNotEquals(RichAnswerAssetIdentity.canonical(MALLARD_1920), "");
    }
}

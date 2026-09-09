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
 * Two image slots mean two photographs, proven from the photographs.
 *
 * <p><b>This file is the regression test for Beta 7.</b> Beta 6 taught the resolver to read a URL
 * and see that {@code 1920px-Mallard-Duck.jpg} and {@code 960px-Mallard-Duck.jpg} were one duck.
 * The device then produced the case that reasoning cannot reach: two addresses with nothing at all
 * in common - a different host, a different path, a different filename - that turn out to be one
 * photograph, shown side by side as though they were two. Every fixture below is that shape.
 *
 * <p>The opposite failure is tested just as hard. A resolver that merges two genuinely different
 * mallards because both are ducks has replaced a visible bug with an invisible one, so the pages
 * here carry real second photographs and the assertions insist they survive.
 *
 * <p>No network. Pages and pictures come from the same two seams the rest of the Rich Answers suite
 * uses, and the pictures are {@link TestPng#photo} rather than a flat colour, because a flat colour
 * has no visual identity to compare.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class RichAnswerVisualDedupeTest {

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
        final List<String> requested = new ArrayList<>();

        @Override public RichAnswerPageFetcher.PageResult fetch(String pageUrl) {
            requested.add(pageUrl);
            String body = html.get(pageUrl);
            if (body == null) {
                return RichAnswerPageFetcher.PageResult.failed(
                        RichAnswerTrace.Reason.HTTP_ERROR, pageUrl, 404, "", 0);
            }
            return RichAnswerPageFetcher.parse(body, pageUrl, 200, "text/html; charset=UTF-8", 0);
        }
    }

    /** Serves one photograph, identified by seed rather than by address. */
    private void servePhoto(String url, int seed) { servePhoto(url, seed, 900, 700); }

    /** The same photograph at another size, which is what a CDN rendition is. */
    private void servePhoto(String url, int seed, int width, int height) {
        byte[] bytes = TestPng.photo(width, height, seed);
        images.responses.put(url, new RemoteImageLoader.Response(
                200, "image/jpeg", null, bytes.length, new ByteArrayInputStream(bytes)));
    }

    private RichAnswerTrace.Attempt attempt() {
        RichAnswerTrace.Attempt trace = new RichAnswerTrace.Attempt();
        trace.enabled = true;
        trace.providerEligible = true;
        trace.intent = RichAnswerTrace.Intent.STRONG_VISUAL;
        trace.pageBudget = RichAnswerCoordinator.MAX_PAGES_EXAMINED_STRONG;
        return trace;
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

    /** One image-shaped discovery hint, built the way the answer parser builds one. */
    private static RichAnswerDiscoveryHint hint(String url, String prompt) {
        RichAnswerDiscoveryHint hint = RichAnswerDiscoveryHint.hintFor(
                "A mallard duck", url, true, RichAnswerSubject.tokensOf(prompt));
        assertTrue("fixture must be a usable image hint", hint != null && hint.isImage());
        return hint;
    }

    private static boolean traceContains(RichAnswerTrace.Attempt trace,
                                         RichAnswerTrace.Reason reason) {
        for (RichAnswerTrace.PageRecord page : trace.pages) {
            for (RichAnswerTrace.CandidateRecord candidate : page.candidates) {
                if (candidate.reason == reason) return true;
            }
        }
        return false;
    }

    private static int countReason(RichAnswerTrace.Attempt trace, RichAnswerTrace.Reason reason) {
        int total = 0;
        for (RichAnswerTrace.PageRecord page : trace.pages) {
            for (RichAnswerTrace.CandidateRecord candidate : page.candidates) {
                if (candidate.reason == reason) total++;
            }
        }
        return total;
    }

    private static String article(String... imgTags) {
        StringBuilder b = new StringBuilder("<html><body><article>");
        for (String tag : imgTags) b.append(tag);
        return b.append("</article></body></html>").toString();
    }

    private static String img(String url, String alt) {
        return "<img src=\"" + url + "\" alt=\"" + alt + "\">";
    }

    // ---- the failure the device reported -----------------------------------------------------------

    private static final String PAGE = "https://birds.example.org/mallard";
    private static final String DRAKE = "https://cdn.example.org/photos/mallard-drake.jpg";
    /** The same photograph, rehosted. Nothing in this address says so. */
    private static final String REHOSTED = "https://images.example.net/gallery/duck-on-water.jpg";
    private static final String HEN = "https://cdn.example.org/photos/mallard-hen.jpg";

    /**
     * One photograph at two unrelated addresses is one picture.
     *
     * <p>The exact Beta 6 failure. {@link RichAnswerAssetIdentity} looks at these two addresses and
     * correctly reports that they are different, because they are: different host, different
     * directory, different filename. Only the decoded picture can say otherwise.
     */
    @Test public void onePhotographAtTwoUnrelatedAddressesIsShownOnce() {
        pages.html.put(PAGE, article(
                img(DRAKE, "A mallard duck drake on water"),
                img(REHOSTED, "A mallard duck swimming")));
        servePhoto(DRAKE, 1);
        servePhoto(REHOSTED, 1, 640, 498);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals("one photograph is one picture, whatever it is called", 1, found.size());
        assertFalse("and the URL layer could not have caught this one",
                RichAnswerAssetIdentity.sameAsset(DRAKE, REHOSTED));
        assertTrue("the refusal must be named as a visual one",
                traceContains(trace, RichAnswerTrace.Reason.VISUAL_DUPLICATE));
        assertEquals(1, trace.visualDuplicates);
        assertEquals("both were downloaded, because only the picture could settle it",
                2, images.requested.size());
    }

    /** A genuinely different second photograph is still a second picture. */
    @Test public void twoGenuinelyDifferentPhotographsAreBothShown() {
        pages.html.put(PAGE, article(
                img(DRAKE, "A mallard duck drake on water"),
                img(HEN, "A mallard duck hen in reeds")));
        servePhoto(DRAKE, 1);
        servePhoto(HEN, 2);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals(2, found.size());
        assertEquals("nothing was merged", 0, trace.visualDuplicates);
        assertNotEquals(found.get(0).imageUrl, found.get(1).imageUrl);
    }

    /**
     * The acceptance page from the Beta 7 brief, end to end.
     *
     * <p>Three renditions of photograph A under three different naming schemes, and one genuinely
     * different photograph B. The answer is A and B, and it is never A and A.
     */
    @Test public void theAcceptancePageYieldsOneOfEachPhotograph() {
        String wide = "https://cdn.example.org/photos/mallard_1920.jpg";
        String narrow = "https://cdn.example.org/photos/mallard_960.jpg";
        String cdn = "https://cdn.example.net/render/mallard-portrait.jpg";
        String other = "https://cdn.example.org/photos/mallard-front-view.jpg";
        pages.html.put(PAGE, article(
                img(wide, "A mallard duck drake"),
                img(narrow, "A mallard duck drake"),
                img(cdn, "A mallard duck"),
                img(other, "A mallard duck seen from the front")));
        servePhoto(wide, 1, 1200, 900);
        servePhoto(narrow, 1, 600, 450);
        servePhoto(cdn, 1, 800, 600);
        servePhoto(other, 2);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals(2, found.size());
        assertEquals("photograph A is the first one accepted", wide, found.get(0).imageUrl);
        assertEquals("and photograph B is the second, never another A",
                other, found.get(1).imageUrl);
        assertTrue(trace.visualDuplicates >= 1);
    }

    // ---- across pages and hosts --------------------------------------------------------------------

    /** A photograph copied onto a second page is not a second photograph. */
    @Test public void thesamePhotographOnTwoPagesIsShownOnce() {
        String second = "https://guide.example.net/ducks/mallard";
        pages.html.put(PAGE, article(img(DRAKE, "A mallard duck drake on water")));
        pages.html.put(second, article(img(REHOSTED, "A mallard duck swimming")));
        servePhoto(DRAKE, 1);
        servePhoto(REHOSTED, 1, 700, 545);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Arrays.asList(PAGE, second), 2,
                "show me pictures of a mallard duck", trace);

        assertEquals("the fingerprint set belongs to the answer, not to one page", 1, found.size());
        assertEquals(2, trace.pagesAttempted);
        assertEquals(1, trace.visualDuplicates);
    }

    /** Two hosts serving one photograph is still one photograph. */
    @Test public void thesamePhotographOnTwoHostsIsShownOnce() {
        String second = "https://guide.example.net/ducks/mallard";
        String otherHost = "https://static.example.com/media/waterfowl-1.jpg";
        pages.html.put(PAGE, article(img(DRAKE, "A mallard duck drake on water")));
        pages.html.put(second, article(img(otherHost, "A mallard duck on a lake")));
        servePhoto(DRAKE, 3);
        servePhoto(otherHost, 3, 500, 389);

        List<RichAnswerImage> found = resolve(Arrays.asList(PAGE, second), 2,
                "show me pictures of a mallard duck", attempt());
        assertEquals(1, found.size());
        assertEquals("the page that was actually read owns it", PAGE, found.get(0).sourceUrl);
    }

    /** A discovery hint that leads back to a picture the answer already has is refused. */
    @Test public void aDiscoveryHintThatRepeatsAnAcceptedPhotographIsRefused() {
        pages.html.put(PAGE, article(img(DRAKE, "A mallard duck drake on water")));
        servePhoto(DRAKE, 4);
        servePhoto(REHOSTED, 4, 640, 498);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE),
                Collections.singletonList(hint(REHOSTED, "show me two pictures of a mallard duck")), 2,
                "show me two pictures of a mallard duck", trace);

        assertEquals(1, found.size());
        assertEquals(DRAKE, found.get(0).imageUrl);
        assertEquals(1, trace.visualDuplicates);
        assertTrue(traceContains(trace, RichAnswerTrace.Reason.VISUAL_DUPLICATE));
    }

    /** A discovery hint carrying a genuinely different photograph is accepted. */
    @Test public void aDiscoveryHintWithADifferentPhotographIsAccepted() {
        pages.html.put(PAGE, article(img(DRAKE, "A mallard duck drake on water")));
        servePhoto(DRAKE, 4);
        servePhoto(REHOSTED, 5, 640, 498);

        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE),
                Collections.singletonList(hint(REHOSTED, "show me two pictures of a mallard duck")), 2,
                "show me two pictures of a mallard duck", attempt());
        assertEquals(2, found.size());
        assertEquals(REHOSTED, found.get(1).imageUrl);
    }

    // ---- the two layers in order -------------------------------------------------------------------

    /**
     * The cheap layer still answers first, and a URL duplicate still costs no request.
     *
     * <p>The visual layer is not a replacement for {@link RichAnswerAssetIdentity} and must not
     * become the excuse to download everything. A rendition Orbit can recognise from its address is
     * refused before a byte moves, and is named {@code DUPLICATE} rather than
     * {@code VISUAL_DUPLICATE} so a report says which layer did the work.
     */
    @Test public void theUrlLayerStillAnswersFirstAndSpendsNothing() {
        String upload = "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1e/Mallard.jpg/";
        String wide = upload + "1920px-Mallard.jpg";
        String narrow = upload + "960px-Mallard.jpg";
        pages.html.put(PAGE, article(
                img(wide, "A mallard duck drake"),
                img(narrow, "A mallard duck drake")));
        servePhoto(wide, 1, 1200, 900);
        servePhoto(narrow, 1, 600, 450);

        RichAnswerTrace.Attempt trace = attempt();
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", trace);

        assertEquals(1, found.size());
        assertEquals("a known rendition must never cost a request", 1, images.requested.size());
        assertTrue(traceContains(trace, RichAnswerTrace.Reason.DUPLICATE));
        assertEquals("the picture never had to settle this one", 0, trace.visualDuplicates);
    }

    // ---- what a shortfall means --------------------------------------------------------------------

    /**
     * A plural request with only one unique photograph attaches one, and says why.
     *
     * <p>The product invariant. Showing the same duck twice to satisfy "Images requested: 2" is the
     * bug; showing one is the answer.
     */
    @Test public void aPluralRequestWithOneUniquePhotographAttachesOne() {
        String third = "https://static.example.com/media/waterfowl-2.jpg";
        pages.html.put(PAGE, article(
                img(DRAKE, "A mallard duck drake on water"),
                img(REHOSTED, "A mallard duck swimming"),
                img(third, "A mallard duck on a lake")));
        servePhoto(DRAKE, 6, 1000, 780);
        servePhoto(REHOSTED, 6, 640, 499);
        servePhoto(third, 6, 500, 390);

        RichAnswerTrace.Attempt trace = attempt();
        trace.attachedImages = 0;
        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", trace);
        trace.attachedImages = found.size();

        assertEquals(1, found.size());
        assertEquals(2, trace.visualDuplicates);
        String report = RichAnswerTrace.describe(trace, 1);
        assertTrue(report.contains("Images requested: 2"));
        assertTrue(report.contains("Images attached: 1"));
        assertTrue(report.contains("Visual duplicates rejected: 2"));
        assertTrue("and the shortfall is explained rather than left as a gap",
                report.contains("every other candidate was the same photograph"));
    }

    /** Each surviving picture keeps its own caption and source. Nothing is merged onto it. */
    @Test public void anAcceptedPictureNeverBorrowsARejectedOnesMetadata() {
        pages.html.put(PAGE, article(
                img(DRAKE, "A mallard duck drake on water"),
                img(REHOSTED, "A completely different description of the same duck")));
        servePhoto(DRAKE, 1);
        servePhoto(REHOSTED, 1, 640, 498);

        List<RichAnswerImage> found = resolve(Collections.singletonList(PAGE), 2,
                "show me pics of a mallard duck", attempt());

        assertEquals(1, found.size());
        assertEquals(DRAKE, found.get(0).imageUrl);
        assertEquals(PAGE, found.get(0).sourceUrl);
        assertEquals("A mallard duck drake on water", found.get(0).caption);
        assertFalse("the refused candidate contributes nothing at all",
                found.get(0).caption.contains("completely different"));
    }

    // ---- diagnostics -------------------------------------------------------------------------------

    /**
     * The report names both the count and the identity, without describing a picture.
     *
     * <p>The addresses here are deliberately opaque. The subject word lives in the alt text, which
     * is never written down, so a report that mentions it would be a real leak rather than a
     * coincidence of the fixture's own filenames.
     */
    @Test public void theReportExplainsAVisualDuplicateWithoutDescribingThePicture() {
        String opaque = "https://cdn.example.org/media/a91f3c2e.jpg";
        String opaqueCopy = "https://images.example.net/render/7b20de41.jpg";
        pages.html.put("https://example.org/entry/4471", article(
                img(opaque, "A mallard duck drake on water"),
                img(opaqueCopy, "A mallard duck swimming")));
        servePhoto(opaque, 1);
        servePhoto(opaqueCopy, 1, 640, 498);

        RichAnswerTrace.Attempt trace = attempt();
        trace.attachedImages = resolve(Collections.singletonList("https://example.org/entry/4471"),
                2, "show me pics of a mallard duck", trace).size();
        assertEquals("the fixture must actually produce the duplicate it is reporting on",
                1, trace.visualDuplicates);
        RichAnswerTrace.record(context, trace);

        String report = RichAnswerTrace.report(context);
        assertTrue(report.contains("Visual duplicates rejected: 1"));
        assertTrue(report.contains("VISUAL_DUPLICATE"));
        assertTrue("an accepted picture carries a readable identity", report.contains("Visual ID "));
        for (String forbidden : new String[]{"mallard", "duck", "drake", "swimming"}) {
            assertFalse("no subject word may reach the report: " + forbidden,
                    report.toLowerCase(java.util.Locale.US).contains(forbidden));
        }
    }

    /** Every candidate that got as far as a decode is named, accepted or refused. */
    @Test public void aVisualDuplicateIsCountedAsFetchedRatherThanHidden() {
        pages.html.put(PAGE, article(
                img(DRAKE, "A mallard duck drake on water"),
                img(REHOSTED, "A mallard duck swimming")));
        servePhoto(DRAKE, 1);
        servePhoto(REHOSTED, 1, 640, 498);

        RichAnswerTrace.Attempt trace = attempt();
        resolve(Collections.singletonList(PAGE), 2, "show me pics of a mallard duck", trace);

        assertEquals("a duplicate cost a real request and must be reported as one",
                2, trace.imagesFetched());
        assertEquals(1, countReason(trace, RichAnswerTrace.Reason.VISUAL_DUPLICATE));
        assertEquals(1, countReason(trace, RichAnswerTrace.Reason.ACCEPTED));
    }

    // ---- scope -------------------------------------------------------------------------------------

    /**
     * The fingerprint set belongs to one answer and never outlives it.
     *
     * <p>A set that leaked between attempts would be a slow, invisible failure: the second time
     * somebody asked about mallards, Orbit would refuse the picture because a previous answer had
     * shown it.
     */
    @Test public void theFingerprintSetIsPerAttempt() {
        pages.html.put(PAGE, article(img(DRAKE, "A mallard duck drake on water")));
        servePhoto(DRAKE, 1);

        assertEquals(1, resolve(Collections.singletonList(PAGE), 1,
                "what does a mallard duck look like", attempt()).size());
        assertEquals("the next answer starts with an empty set",
                1, resolve(Collections.singletonList(PAGE), 1,
                        "what does a mallard duck look like", attempt()).size());
    }

    /** Ownership and cancellation are unchanged by any of this. */
    @Test public void discoveryStillChecksOwnershipBeforeAttaching() {
        String coordinator = ComponentUninstallTest.readRepositoryFile(
                "app/src/main/java/com/orbit/assistant/RichAnswerCoordinator.java");
        assertTrue("a stopped request must still never decorate its answer",
                coordinator.contains("OrbitRequestManager.isCancelled(app, owner)"));
        assertTrue(coordinator.contains("ConversationStore.attachRichImages(app, chat, owner, answer"));
        assertFalse("the fingerprint set must not be process-wide state",
                coordinator.contains("static final Visuals"));
    }
}
